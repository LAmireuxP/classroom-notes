package com.lamireuxp.classroom;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;

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

    public static boolean recognitionAvailable(Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c);
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

        if (recordAudio) startRecorder();

        if (SpeechRecognizer.isRecognitionAvailable(ctx)) {
            main.post(new Runnable() {
                @Override public void run() { startRecognizer(); }
            });
        } else if (!recordAudio) {
            // 既没有系统识别，又没有录音 -> 无法工作
            recording = false;
            listener.onError("当前设备不支持系统语音识别，请在「转写设置」开启云端转写", true);
            return;
        }
    }

    private void startRecognizer() {
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx);
                recognizer.setRecognitionListener(new RecognitionListener() {
                    @Override public void onReadyForSpeech(Bundle params) {}
                    @Override public void onBeginningOfSpeech() {}
                    @Override public void onRmsChanged(float rmsdB) {}
                    @Override public void onBufferReceived(byte[] buffer) {}
                    @Override public void onEndOfSpeech() {}
                    @Override public void onEvent(int eventType, Bundle params) {}

                    @Override public void onPartialResults(Bundle results) {
                        ArrayList<String> list = results.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION);
                        if (list != null && list.size() > 0) {
                            partial = list.get(0);
                            listener.onPartial(fullText());
                        }
                    }

                    @Override public void onResults(Bundle results) {
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
                        boolean fatal = error == SpeechRecognizer.ERROR_CLIENT
                                || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED;
                        listener.onError(describe(error), fatal);
                        if (!fatal) maybeRestart();
                    }
                });
            }
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            recognizer.startListening(it);
        } catch (Throwable e) {
            listener.onError("语音识别启动失败：" + e.getMessage(), true);
        }
    }

    private void maybeRestart() {
        if (recording && !paused) {
            wantRestart = true;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (recording && !paused && wantRestart) {
                        wantRestart = false;
                        try {
                            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
                            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
                            recognizer.startListening(it);
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

    private static String describe(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT: return "识别客户端出错";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误，语音识别需要联网";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有匹配的语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务出错";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到语音";
            default: return "语音识别出错(" + error + ")";
        }
    }
}