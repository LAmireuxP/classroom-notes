package com.lamireuxp.classroom;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 语音会话：系统实时识别（SpeechRecognizer）+ 音频录制（MediaRecorder）。
 *
 * 修复原版核心 bug：原版依赖 Web Speech API，而 Android WebView 根本不支持，
 * 导致 App 内录音完全不可用。这里改用系统原生能力，App 内可直接识别。
 */
public class SpeechSession {

    public interface Listener {
        void onPartial(String text);
        /** finalText 为识别到的完整文本（可能为空）；audio 为录音字节（可能为 null）。 */
        void onFinished(String finalText, byte[] audio);
        void onError(String message, boolean fatal);
    }

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private android.media.MediaRecorder recorder;
    private File recFile;

    private final StringBuilder finalText = new StringBuilder();
    private String partial = "";
    private boolean recording = false;
    private boolean paused = false;
    private boolean wantRestart = false;
    private boolean recordAudio = false;
    private long startedAt = 0;

    public SpeechSession(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public boolean isRecording() { return recording; }
    public boolean isPaused() { return paused; }
    public long elapsedMs() { return recording ? System.currentTimeMillis() - startedAt : 0; }

    /**
     * 主动查一次设备上有哪些语音识别服务，返回要绑定的组件。
     *
     * 不直接用 SpeechRecognizer.createSpeechRecognizer(ctx) 的原因：那个重载传 null 组件，
     * 让系统去读 secure 设置 voice_recognition_service 里的「默认识别服务」。国内 ROM
     * （MIUI / EMUI 等）这个设置常常是空的，于是只会打一行
     * "no selected voice recognition service" 然后静默失败——服务明明装着却用不了。
     * 自己查 service 列表再显式指定组件，就不依赖那个设置了。
     *
     * 注意：manifest 里必须有 <queries><intent><action
     * android:name="android.speech.RecognitionService"/></intent></queries>，
     * 否则 targetSdk>=30 的包可见性过滤会让这里 queryIntentServices 返回空。
     */
    private static ComponentName findService(Context c) {
        try {
            List<ResolveInfo> list = c.getPackageManager().queryIntentServices(
                    new Intent(RecognitionService.SERVICE_INTERFACE), 0);
            if (list == null || list.isEmpty()) return null;
            ComponentName fallback = null;
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo == null) continue;
                ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                if (ri.isDefault) return cn;   // 系统标记为默认的优先
                if (fallback == null) fallback = cn;
            }
            return fallback;
        } catch (Throwable e) {
            return null;
        }
    }

    public static boolean recognitionAvailable(Context c) {
        return findService(c) != null;
    }

    /**
     * @param recordAudio 是否同时录制音频（用于云转写兜底）
     */
    public void start(boolean recordAudio) {
        this.recordAudio = recordAudio;
        finalText.setLength(0);
        partial = "";
        recording = true;
        paused = false;
        startedAt = System.currentTimeMillis();
        explicitRetried = false;
        boundExplicit = false;

        if (recordAudio) startRecorder();

        if (recognitionAvailable(ctx)) {
            main.post(new Runnable() {
                @Override public void run() { startRecognizer(); }
            });
        } else if (!recordAudio) {
            // 既没有系统识别，又没有录音 -> 无法工作
            recording = false;
            listener.onError("当前设备没有可用的语音识别服务，请在「转写设置」开启云端转写", true);
            return;
        } else {
            // 有录音兜底：识别跑不了，但得说一声，否则用户只会看到「录完没出字」
            listener.onError("系统语音识别不可用，本次仅录音，结束后走云端转写", false);
        }
    }

    // ---------- 绑定识别服务 ----------

    /** 当前 recognizer 的绑定方式：false=系统默认服务，true=显式指定组件。 */
    private boolean boundExplicit = false;
    /** 是否已因绑定失败改用过显式组件，避免来回重试。 */
    private boolean explicitRetried = false;
    /**
     * 识别器有没有给过任何回调。
     * 用来识别「绑上了但根本没工作」：MIUI 这类 ROM 只肯对「被指定为系统默认识别服务」
     * 的调用方真正放行，其余情况既不报错也不出声，界面上就是个假的录音面板。
     */
    private boolean anyCallback = false;
    private final Runnable deadRecognizerWatchdog = new Runnable() {
        @Override public void run() {
            if (!recording || paused || anyCallback) return;
            listener.onError(recordAudio
                            ? "系统语音识别无响应，本次仅录音，结束后走云端转写"
                            : "系统语音识别无响应，请在系统设置里把语音识别服务设为默认",
                    !recordAudio);
        }
    };

    private Intent recognizerIntent() {
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return it;
    }

    private void startRecognizer() {
        try {
            if (recognizer == null) {
                recognizer = createRecognizer(false);
                recognizer.setRecognitionListener(recognitionListener);
            }
            anyCallback = false;
            main.removeCallbacks(deadRecognizerWatchdog);
            main.postDelayed(deadRecognizerWatchdog, 4000);
            recognizer.startListening(recognizerIntent());
        } catch (Throwable e) {
            listener.onError("语音识别启动失败：" + e.getMessage(), true);
        }
    }

    /** explicit=false 走系统默认识别服务；true 自己查服务列表、显式指定组件。 */
    private SpeechRecognizer createRecognizer(boolean explicit) {
        if (explicit) {
            ComponentName svc = findService(ctx);
            if (svc != null) {
                boundExplicit = true;
                return SpeechRecognizer.createSpeechRecognizer(ctx, svc);
            }
        }
        boundExplicit = false;
        return SpeechRecognizer.createSpeechRecognizer(ctx);
    }

    /**
     * 系统默认识别服务用不了时，换成显式组件再试一次。
     *
     * 两条路都要覆盖，因为 ROM 行为正好相反：有的没设默认服务（只能走显式），
     * MIUI 这种反过来只认被指定为默认的那个（走显式会被拒）。
     */
    private void retryWithExplicit() {
        if (explicitRetried || findService(ctx) == null) return;
        explicitRetried = true;
        destroyRecognizer();
        startRecognizer();
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { anyCallback = true; }
        @Override public void onBeginningOfSpeech() { anyCallback = true; }
        @Override public void onRmsChanged(float rmsdB) { anyCallback = true; }
        @Override public void onBufferReceived(byte[] buffer) { anyCallback = true; }
        @Override public void onEndOfSpeech() { anyCallback = true; }
        @Override public void onEvent(int eventType, Bundle params) { anyCallback = true; }

        @Override public void onPartialResults(Bundle results) {
            anyCallback = true;
            ArrayList<String> list = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && list.size() > 0) {
                partial = list.get(0);
                listener.onPartial(fullText());
            }
        }

        @Override public void onResults(Bundle results) {
            anyCallback = true;
            ArrayList<String> list = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && list.size() > 0) {
                String s = list.get(0);
                if (s != null && s.trim().length() > 0) {
                    if (finalText.length() > 0) finalText.append("\n");
                    finalText.append(s.trim());
                }
            }
            partial = "";
            listener.onPartial(fullText());
            maybeRestart();
        }

        @Override public void onError(int error) {
            partial = "";
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                maybeRestart();
                return;
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                main.postDelayed(new Runnable() {
                    @Override public void run() { maybeRestart(); }
                }, 300);
                return;
            }
            // 默认服务没配好时先换显式组件重试，别急着把错误抛给用户
            if ((error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    && !boundExplicit && recording && !paused) {
                retryWithExplicit();
                return;
            }
            boolean fatal = error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
            listener.onError(describe(ctx, error), fatal);
            if (!fatal) maybeRestart();
        }
    };

    private void maybeRestart() {
        if (recording && !paused) {
            wantRestart = true;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (recording && !paused && wantRestart) {
                        wantRestart = false;
                        try {
                            recognizer.startListening(recognizerIntent());
                        } catch (Throwable ignored) {}
                    }
                }
            }, 180);
        }
    }

    public void setPaused(boolean p) {
        paused = p;
        if (p) {
            try { if (recognizer != null) recognizer.stopListening(); } catch (Throwable ignored) {}
            try { if (recorder != null) recorder.pause(); } catch (Throwable ignored) {}
        } else {
            try { if (recorder != null) recorder.resume(); } catch (Throwable ignored) {}
            maybeRestart();
        }
    }

    public String fullText() {
        String f = finalText.toString();
        if (partial != null && partial.length() > 0) {
            return f.length() > 0 ? f + " " + partial : partial;
        }
        return f;
    }

    private void startRecorder() {
        try {
            recFile = new File(ctx.getCacheDir(), "rec_" + System.currentTimeMillis() + ".m4a");
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Throwable e) {
            recorder = null;
            recFile = null;
        }
    }

    /** 停止并回调结果。 */
    public void stop() {
        if (!recording) return;
        recording = false;
        paused = false;
        main.removeCallbacks(deadRecognizerWatchdog);
        if (recognizer != null) {
            try { recognizer.stopListening(); } catch (Throwable ignored) {}
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        byte[] audio = null;
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
            if (recFile != null && recFile.exists()) {
                audio = readFile(recFile);
                recFile.delete();
            }
        }
        listener.onFinished(fullText().trim(), audio);
    }

    /** 中止，不回调结果。 */
    public void cancel() {
        recording = false;
        paused = false;
        main.removeCallbacks(deadRecognizerWatchdog);
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable ignored) {}
            try { recorder.release(); } catch (Throwable ignored) {}
            recorder = null;
        }
        if (recFile != null && recFile.exists()) recFile.delete();
    }

    private byte[] readFile(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 把识别错误码翻译成人话。
     *
     * ERROR_INSUFFICIENT_PERMISSIONS 不能想当然当成「App 没拿到麦克风权限」：
     * 实测 MIUI 上录音权限明明是 granted，识别服务照样回这个码（服务只对
     * 「被指定为系统默认」的调用方放行）。这里自己再查一次权限，真缺才说缺，
     * 否则如实说明是识别服务的问题——否则用户会去反复授权，而问题根本不在这。
     */
    private static String describe(Context c, int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT: return "识别服务连接失败";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return hasMicPermission(c)
                        ? "系统语音识别服务拒绝了请求，请在系统设置里把语音识别服务设为默认"
                        : "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误，语音识别需要联网";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有匹配的语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务出错";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到语音";
            default: return "语音识别出错(" + error + ")";
        }
    }

    private static boolean hasMicPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
}