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

        /**
         * @param fatal        会话已经没法继续了，调用方需要结束它（cancel() 会连同录音
         *                     文件一起删掉）。只有「没开录音兜底、识别又用不了」时才为
         *                     true——开了录音兜底的话，会话会丢掉识别器降级成纯录音继续
         *                     跑，音频不会因为识别出错被赔进去。
         * @param cloudFallback 这个错误能靠「开启云端转写」绕过。
         *                      为 true 时调用方应给出跳转入口，而不是只甩一句话——
         *                      遇到没有识别服务或识别服务不响应的设备，用户照着
         *                      「去系统设置里改」往往找不到地方，开云转写才是真出路。
         */
        void onError(String message, boolean fatal, boolean cloudFallback);
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

    /**
     * @param ctx     只要有 getCacheDir() 可用即可（录音临时文件落在这里）
     * @param listener 识别结果与错误都回调到它；所有回调都在主线程
     */
    public SpeechSession(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    /** 会话是否在跑（识别被放弃、降级成纯录音时仍然是 true——录音还在继续）。 */
    public boolean isRecording() { return recording; }

    /** 是否处于暂停（识别与录音都停了；恢复时识别会重新开始听）。 */
    public boolean isPaused() { return paused; }

    /** 已录制时长（毫秒）。没在录时返回 0，界面上的计时器才不会在结束后继续跳。 */
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

    /** 这台设备上能不能找到可用的识别服务（找不到就只能靠录音 + 云端转写）。 */
    public static boolean recognitionAvailable(Context c) {
        return findService(c) != null;
    }

    /**
     * 本进程是否已确认「这台设备的识别服务不放行本 App」。
     * 调用方据此把提示条的入口换成「去授权」（厂商语音助手），而不是让用户去翻系统设置。
     */
    public static boolean recognizeBlocked() {
        return sRecognizeBlocked;
    }

    /**
     * 清除「被拦下」标记：把用户送去走授权流程之后调它。
     * 授权可能就在刚刚这一步完成，下次录音必须重新试一次系统识别，
     * 而不是拿一次旧的失败把这条路永久堵死。
     */
    public static void clearRecognizeBlocked() {
        sRecognizeBlocked = false;
    }

    /**
     * 进程级「这台设备/这套识别服务已经放行过本 App 吗」。
     *
     * 起因：澎湃 OS 4 的小米 AsrService 里有个 CTA / 设备型号白名单（isCTAAllow），
     * 对没同意过「跨应用语音识别」、或机型不在白名单里的调用方一律拒绝并立刻自毁。
     * 这样的设备上，每次录音都去戳一下识别服务、等它报 ERROR_SERVER_DISCONNECTED
     * 再降级，是在白费一次绑定 + 一次失败提示。这里把「这服务不肯对本 App 放行」
     * 记到进程里：同一进程内的后续录音直接走纯录音 + 云转写，不再戳识别。
     *
     * 进程重启会清空——所以升级、换机后会自动重新试一次（fresh 状态），不会把一个
     * 也许修好了的服务永久关掉；真的出过字（onPartialResults / onResults）也会立刻清标记。
     * 判定只在「一次回调都没给过就被拒」时成立（见 giveUpRecognition），保守地只记确认不行的。
     */
    private static boolean sRecognizeBlocked = false;

    /**
     * @param recordAudio 是否同时录制音频（用于云转写兜底）
     * @return 是否真的开始工作。false 表示同步就失败了（调用方别再渲染录音界面，
     *         否则会留下一个「正在录音」的假面板），并且 onError 已经回调过。
     */
    public boolean start(boolean recordAudio) {
        this.recordAudio = recordAudio;
        finalText.setLength(0);
        partial = "";
        recording = true;
        paused = false;
        startedAt = System.currentTimeMillis();
        explicitRetried = false;
        boundExplicit = false;

        if (recordAudio) startRecorder();

        // 这台机器上识别服务确认过不肯对本 App 放行（澎湃 OS 4 的 CTA/白名单就是这类）：
        // 别再去戳它、等它拒绝。直接按「识别不可用」处理。
        if (recognitionAvailable(ctx) && !sRecognizeBlocked) {
            main.post(new Runnable() {
                @Override public void run() { startRecognizer(); }
            });
            return true;
        }
        if (!recordAudio) {
            // 既没有系统识别、又没开录音兜底 —— 无法工作
            recording = false;
            listener.onError(sRecognizeBlocked
                    ? "系统语音识别未获厂商放行，可去语音助手里同意「跨应用识别」，或改用云端转写"
                    : "这台设备没有系统语音识别服务，可改用云端转写", true, true);
            return false;
        }
        // 有录音兜底：识别跑不了，但得说一声，否则用户只会看到「录完没出字」
        listener.onError(sRecognizeBlocked
                ? "系统语音识别未获厂商放行，本次仅录音，结束后走云端转写"
                : "系统语音识别不可用，本次仅录音，结束后走云端转写", false, true);
        return true;
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
            giveUpRecognition("系统语音识别无响应");
        }
    };

    /**
     * 识别请求参数：中文、要中间结果、只要一个候选。
     *
     * EXTRA_PARTIAL_RESULTS 是「实时出字」的关键——没有它，用户要等到整段结束才看见字；
     * EXTRA_MAX_RESULTS 取 1 是因为界面上只显示一条，多要候选只是白等。
     */
    private Intent recognizerIntent() {
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        return it;
    }

    /**
     * 开始（或重新开始）听。识别器是懒创建的：真正要用时才绑定服务。
     *
     * 顺手挂上 4 秒看门狗：MIUI 这类 ROM 在「不肯放行」时既不报错也不回调，
     * 界面上就成了一个假的录音面板——到点还没有任何回调就按识别不可用处理。
     */
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
            giveUpRecognition("语音识别启动失败：" + e.getMessage());
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

    /** 释放识别器。cancel/destroy 都要包 try：个别 ROM 在未连接状态下会抛异常。 */
    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Throwable ignored) {}
            try { recognizer.destroy(); } catch (Throwable ignored) {}
            recognizer = null;
        }
    }

    /**
     * 识别彻底用不了了（服务连不上 / 被拒 / 连接断开 / 启动就抛异常）。
     *
     * 只放弃「识别」，不放弃会话：开着录音兜底的话音频还在手上，录到结束照样能出字，
     * 所以这里丢掉识别器、让会话降级成纯录音继续跑，并按非致命上报。
     * 没开录音兜底才是真的没退路，那时按致命上报，由调用方结束会话。
     *
     * 原来这几种错误一律按致命报上去，调用方拿到就 cancel()——而 cancel() 会删掉
     * 录音文件：识别出的毛病，赔进去的是用户整段录音。
     *
     * cloudFallback 只在真正有出路的那一支为 true：还能继续录的时候，云转写本来
     * 就开着，再给一个「去设置」的入口只会把人指到已经配好的页面上。
     */
    private void giveUpRecognition(String reason) {
        // 一次回调都没给过就被拒，是设备侧「不放行」的特征（服务直接拒绝并自毁，见 VoiceAuth）；
        // 出过回调之后才失败的更像偶发故障，不记，下次录音仍然重新去试。
        // 标记只在进程内，重启/升级/换机后清零，所以不会永久关掉一个也许已经能用的服务。
        sRecognizeBlocked = !anyCallback;
        destroyRecognizer();
        main.removeCallbacks(deadRecognizerWatchdog);
        wantRestart = false;
        if (sRecognizeBlocked) {
            listener.onError(recordAudio
                    ? "系统语音识别未获厂商放行，继续录音，结束后走云端转写"
                    : "系统语音识别未获厂商放行，可去语音助手里同意「跨应用识别」，或改用云端转写",
                    !recordAudio, true);
            return;
        }
        listener.onError(recordAudio
                        ? reason + "，继续录音，结束后走云端转写"
                        : reason + "，可改用云端转写",
                !recordAudio, !recordAudio);
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
            // 识别真的出过字 = 这台机器上服务对本 App 是放行的；之前的「放行失败」标记就过时了。
            sRecognizeBlocked = false;
            ArrayList<String> list = results.getStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION);
            if (list != null && list.size() > 0) {
                partial = list.get(0);
                listener.onPartial(fullText());
            }
        }

        @Override public void onResults(Bundle results) {
            anyCallback = true;
            sRecognizeBlocked = false;
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
            // 这三个码意味着识别器本身已经废了：重建也接不上，不能再指望识别
            if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                giveUpRecognition(describe(ctx, error));
                return;
            }
            listener.onError(describe(ctx, error), false, true);
            maybeRestart();
        }
    };

    /**
     * 连续识别：一次结果/一次超时之后重新开始听，直到用户按停。
     *
     * 延迟 180ms 不是随手写的：紧接着 onResults 立刻 startListening，部分 ROM 会回
     * ERROR_RECOGNIZER_BUSY，识别就此断掉——用户看到的就是「说着说着不认了」。
     */
    private void maybeRestart() {
        if (recording && !paused && recognizer != null) {
            wantRestart = true;
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (recording && !paused && wantRestart && recognizer != null) {
                        wantRestart = false;
                        try {
                            recognizer.startListening(recognizerIntent());
                        } catch (Throwable ignored) {}
                    }
                }
            }, 180);
        }
    }

    /**
     * 暂停 / 恢复。识别与录音一起停：只停一个的话，暂停期间要么继续录进静音、
     * 要么继续识别被用户听到，都不是「暂停」该有的样子。
     */
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

    /** 当前可见的全文 = 已确认的最终文本 + 正在说的那句中间结果。 */
    public String fullText() {
        String f = finalText.toString();
        if (partial != null && partial.length() > 0) {
            return f.length() > 0 ? f + " " + partial : partial;
        }
        return f;
    }

    /**
     * 开始录音（云转写的原料）。
     *
     * 16kHz 单声道 AAC：语音识别的标准采样率，40 分钟课约 12 MB，上传不肉疼。
     * 文件落在 cacheDir——系统清理时能回收，不用我们管生命周期。
     *
     * 任何一步失败都只是「没有录音兜底」，不抛给调用方：识别本身还能继续用。
     */
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

    /**
     * 正常结束：停掉识别与录音，把音频读进内存后**删掉临时文件**，再回调结果。
     * 读文件必须在删之前完成（stop/release 之后文件才可读，某些 ROM 上早读会拿到空数据）。
     */
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

    /**
     * 中止：不回调结果，并且**删除录音文件**。
     * 注意这与「识别失败」不是一回事——识别失败走的是降级（音频留着），
     * 只有调用方明确不要这段录音时才走这里。
     */
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

    /** 把录音整段读进内存。时长由用户控制（一节课量级），不做流式。 */
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
                // 录音权限明明给了、识别服务却回这个码：是服务侧的「跨应用放行」没通过
                // （澎湃 OS 4 小米 AsrService 的 CTA / 机型白名单就是这类，见 VoiceAuth）。
                // 这种设备在系统设置里改不了，用户要去厂商语音助手同意一次，或开云端转写。
                return hasMicPermission(c)
                        ? "系统语音识别未获厂商放行（厂商策略）"
                        : "缺少麦克风权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误，语音识别需要联网";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时，语音识别需要联网";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有匹配的语音";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别服务忙";
            case SpeechRecognizer.ERROR_SERVER: return "识别服务出错";
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED: return "与识别服务的连接断开";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "未检测到语音";
            // 下面几个是 API 31+ 才有的常量，用字面量避免低版本编译不过
            case 10: return "请求过于频繁，识别服务限流了";
            case 12: return "识别服务不支持中文";
            case 13: return "识别服务的语言包不可用";
            default: return "语音识别出错（错误码 " + error + "）";
        }
    }

    /** 自查麦克风权限：API 23 以下安装即授予，直接算有。 */
    private static boolean hasMicPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }
}