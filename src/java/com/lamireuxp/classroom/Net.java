package com.lamireuxp.classroom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

/** 网络层：AI 总结 + 云转写。纯 HttpURLConnection，无需第三方库。 */
public final class Net {

    private Net() {}

    public static class AiResult {
        public String summary;
        public List<String> keyPoints;
    }

    /**
     * 调用 OpenAI 兼容的 /chat/completions 做课堂笔记整理。
     * 做了严格容错：模型多说话/少括号都能尽量解析出来。
     */
    public static AiResult summarize(String endpoint, String key, String model,
                                     String title, String text) throws Exception {
        String prompt =
                "你是一个课堂笔记整理助手。请先纠正语音转录文字（修正同音字、补充标点、理顺逻辑），使语句通顺，然后提炼核心内容：\n" +
                "1.去除重复废话\n2.保留核心知识点\n3.组织成简洁语言\n4.提取3-5个重点\n\n" +
                "标题：" + (title == null ? "" : title) + "\n内容：\n" + text +
                "\n\n只输出 JSON，不要任何额外说明：\n{\"summary\":\"提炼后的笔记\",\"keyPoints\":[\"重点1\",\"重点2\",\"重点3\"]}";

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("max_tokens", 2048);
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", "你是课堂笔记助手，擅长提炼重点。只输出 JSON。");
        msgs.put(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", prompt);
        msgs.put(usr);
        body.put("messages", msgs);

        String resp = postJson(endpoint + "/chat/completions", key, body.toString());
        JSONObject data = new JSONObject(resp);
        JSONObject choice = data.optJSONArray("choices") != null && data.optJSONArray("choices").length() > 0
                ? data.optJSONArray("choices").getJSONObject(0) : null;
        String content = "";
        if (choice != null && choice.optJSONObject("message") != null) {
            content = choice.optJSONObject("message").optString("content", "");
        }
        if (content.trim().length() == 0) throw new Exception("模型返回内容为空");

        String clean = stripFence(content);
        AiResult r = new AiResult();
        JSONObject parsed = tryParseJson(clean);
        if (parsed != null) {
            r.summary = parsed.optString("summary", "");
            r.keyPoints = Db.splitString(toLines(parsed.optJSONArray("keyPoints")));
        }
        if (r.summary == null || r.summary.length() == 0) {
            // 兜底：模型没按 JSON 返回时，把正文当总结，按行/句提取重点
            r.summary = clean;
            if (r.keyPoints == null || r.keyPoints.isEmpty()) r.keyPoints = Extract.keyPoints(clean);
        }
        return r;
    }

    private static String toLines(JSONArray arr) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    /** 去掉 ```json 围栏。 */
    private static String stripFence(String s) {
        String t = s.trim();
        t = t.replace("```json", "").replace("```JSON", "").replace("```", "");
        return t.trim();
    }

    /** 尽力从文本中抠出 JSON 对象：直接解析 → 截取首个 { 到末个 }。 */
    private static JSONObject tryParseJson(String s) {
        try {
            return new JSONObject(s);
        } catch (Exception ignored) {}
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            try {
                return new JSONObject(s.substring(a, b + 1));
            } catch (Exception ignored) {}
        }
        return null;
    }

    public static String postJson(String url, String key, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            byte[] payload = json.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                throw new Exception(friendlyError(code, resp));
            }
            return resp;
        } finally {
            conn.disconnect();
        }
    }

    /** 上传音频做转写（OpenAI /audio/transcriptions 格式，multipart）。 */
    public static String transcribe(String endpoint, String key, String model,
                                    byte[] audio, String fileName) throws Exception {
        String boundary = "----classroom" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint + "/audio/transcriptions").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeField(out, boundary, "model", model == null || model.length() == 0 ? "whisper-1" : model);
            out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n")
                    .getBytes("UTF-8"));
            out.write("Content-Type: audio/mp4\r\n\r\n".getBytes("UTF-8"));
            out.write(audio);
            out.write("\r\n".getBytes("UTF-8"));
            out.write(("--" + boundary + "--\r\n").getBytes("UTF-8"));

            byte[] payload = out.toByteArray();
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new Exception(friendlyError(code, resp)
                    + (code == 404 ? "；转写走 /audio/transcriptions，纯对话 API（如 DeepSeek）不提供这个接口" : ""));
            JSONObject o = new JSONObject(resp);
            String text = o.optString("text", "").trim();
            if (text.length() == 0) throw new Exception("服务返回内容为空");
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 连通性自检：GET {base}/models。OpenAI 兼容服务基本都实现这个接口，
     * 点一下当场就知道「地址 + Key」通不通——录完 40 分钟才发现 404 的代价太大。
     *
     * @return 一行可直接展示的成功文案
     * @throws Exception 失败原因已翻译成中文（配合 humanize 在 UI 层处理）
     */
    public static String probe(String endpoint, String key) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint + "/models").openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                if (code == 401 && (key == null || key.length() == 0)) {
                    // 没填 Key 的 401 是好消息：地址和网络都通了，就差 Key
                    throw new Exception("地址可达、服务在线，但该服务要求填写 API Key");
                }
                String msg = friendlyError(code, resp);
                if (code == 404) {
                    msg += "；确认地址填的是 API 根（如 https://api.openai.com/v1），不是完整接口路径";
                }
                throw new Exception(msg);
            }
            try {
                JSONArray data = new JSONObject(resp).optJSONArray("data");
                if (data != null) return "服务在线，可用模型 " + data.length() + " 个";
            } catch (Exception ignored) {}
            return "服务在线";
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 把底层网络异常翻译成人话。SSLException / UnknownHostException 的原文对中文用户
     * 等于没说；这层映射不求全，把课堂场景真正常见的几种盖住就行。
     */
    public static String humanize(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.length() == 0) return e.getClass().getSimpleName();
        String l = m.toLowerCase();
        if (l.contains("unable to resolve host")) return "域名解析失败：检查服务地址拼写，或手机网络";
        if (l.contains("connection refused")) return "服务拒绝连接：端口不对，或服务没启动";
        if (l.contains("timed out") || l.contains("timeout")) return "连接超时：服务地址不可达";
        if (l.contains("ssl") || l.contains("handshake") || l.contains("certificate"))
            return "HTTPS 握手失败：证书异常，或网络被劫持";
        if (l.contains("cleartext")) return "明文 HTTP 被系统拒绝：请把地址改成 https://";
        return m;
    }

    private static void writeField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("UTF-8"));
        out.write((value + "\r\n").getBytes("UTF-8"));
    }

    private static String friendlyError(int code, String body) {
        String detail = "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            if (err != null && err.optString("message", "").length() > 0) {
                detail = "：" + err.optString("message");
            }
        } catch (Exception ignored) {}
        if (code == 401) return "API Key 无效或未授权" + detail;
        if (code == 404) return "接口地址不正确（HTTP 404）" + detail;
        if (code == 429) return "请求过于频繁或被限流" + detail;
        return "请求失败 HTTP " + code + detail;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }
}