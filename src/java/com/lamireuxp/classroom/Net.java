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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 网络层：AI 总结 + 云转写。纯 HttpURLConnection，无需第三方库。 */
public final class Net {

    private Net() {}

    public static class AiResult {
        public String summary;
        public List<String> keyPoints;
    }

    /**
     * 让模型整理课堂笔记。请求怎么发、回复怎么取都交给 {@link AiProto}——
     * OpenAI 兼容只是其中一种，通义原生、百度文心的结构完全不同。
     * 解析依旧严格容错：模型多说话/少括号都要尽量抠出来。
     */
    public static AiResult summarize(AiProto.Cfg cfg, String title, String text) throws Exception {
        String prompt =
                "你是一个课堂笔记整理助手。请先纠正语音转录文字（修正同音字、补充标点、理顺逻辑），使语句通顺，然后提炼核心内容：\n" +
                "1.去除重复废话\n2.保留核心知识点\n3.组织成简洁语言\n4.提取3-5个重点\n\n" +
                "标题：" + (title == null ? "" : title) + "\n内容：\n" + text +
                "\n\n只输出 JSON，不要任何额外说明：\n{\"summary\":\"提炼后的笔记\",\"keyPoints\":[\"重点1\",\"重点2\",\"重点3\"]}";

        String system = "你是课堂笔记助手，擅长提炼重点。只输出 JSON。";
        String body = AiProto.body(cfg, system, prompt, false);

        // 文心的老接口：先拿 client_id + client_secret 换 access_token，token 挂在 URL 上
        String token = AiProto.ERNIE.equals(cfg.id) ? baiduToken(cfg) : null;
        String resp = postJson(AiProto.url(cfg, token), AiProto.bearer(cfg), body);

        JSONObject data = toJson(resp);
        // 出错也可能是 HTTP 200（百度就这么干），所以响应体本身要查一遍
        String bodyErr = AiProto.bodyError(data);
        if (bodyErr != null) throw new Exception(bodyErr);

        String content = AiProto.extract(cfg, data);
        if (content.trim().length() == 0) throw new Exception(noContent(data));

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

    /**
     * 「响应里没有正文」的原因通常很具体：把实时音频模型、向量模型之类的当对话模型填了，
     * 请求会被正常受理，但回复里只有状态字段。这时说清是模型选错，别让人对着
     * 「模型返回内容为空」猜。
     */
    private static String noContent(JSONObject data) {
        if (data.optString("status_message", "").length() > 0
                || data.optString("status_name", "").length() > 0) {
            return "服务只返回了状态、没有正文：这个模型可能不支持纯文本对话"
                    + "（实时音频 / 语音类模型常见），换一个文本模型试试";
        }
        return "模型返回内容为空";
    }

    /**
     * 百度文心的 access_token。有效期 30 天，进程内缓存一份——
     * 每总结一次就换一次 token 既慢又多一次失败点。
     */
    private static String tokenCache = "";
    private static long tokenExpire = 0L;

    private static String baiduToken(AiProto.Cfg cfg) throws Exception {
        long now = System.currentTimeMillis();
        if (tokenCache.length() > 0 && now < tokenExpire) return tokenCache;
        String resp = get(AiProto.oauthUrl(cfg));
        JSONObject o;
        try {
            o = new JSONObject(resp);
        } catch (Exception e) {
            throw new Exception("换取 access_token 失败：返回的不是 JSON");
        }
        String err = o.optString("error", "");
        if (err.length() > 0) {
            String d = o.optString("error_description", "");
            throw new Exception("换取 access_token 失败：" + (d.length() > 0 ? d : err)
                    + "（检查 API Key / Secret Key 是否填反）");
        }
        String t = o.optString("access_token", "");
        if (t.length() == 0) throw new Exception("换取 access_token 失败：返回里没有 token");
        tokenCache = t;
        long ttl = o.optLong("expires_in", 2592000L);
        // 提前 5 分钟过期，避免边界上刚好用到废 token
        tokenExpire = now + (ttl - 300L) * 1000L;
        return t;
    }

    /** 响应可能根本不是 JSON（网关的 HTML 错误页、门户拦截），这时报错要说人话。 */
    private static JSONObject toJson(String resp) throws Exception {
        try {
            return new JSONObject(resp);
        } catch (Exception e) {
            throw new Exception("服务返回的不是 JSON（可能被网关或门户页拦截了）："
                    + shortOf(resp));
        }
    }

    private static String shortOf(String s) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(0, 80) + "…" : t;
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
        HttpURLConnection conn = (HttpURLConnection)
                new URL(AiProto.base(endpoint) + "/audio/transcriptions").openConnection();
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
                    + (code == 404 ? "；转写要的是 OpenAI 兼容的 /audio/transcriptions 接口，"
                    + "纯对话服务（DeepSeek 等）和只代理对话的中转站都不提供它" : ""));
            JSONObject o = new JSONObject(resp);
            String text = o.optString("text", "").trim();
            if (text.length() == 0) throw new Exception("服务返回内容为空");
            return text;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 连通性自检，按协议来。点一下当场就知道「地址 + Key + 模型」通不通——
     * 录完 40 分钟才发现配错，代价太大。
     *
     * OpenAI 兼容的服务先用 GET /models 探（不花钱）；通义原生、文心、以及自定义了
     * 请求路径的服务没有这个接口，就发一条最小的对话请求（几个 token），
     * 换来「所有协议都能自检」。
     *
     * @return 一行可直接展示的成功文案
     * @throws Exception 失败原因已翻译成中文
     */
    public static String probe(AiProto.Cfg cfg) throws Exception {
        boolean maybeModels = AiProto.OPENAI.equals(cfg.id)
                || (AiProto.CUSTOM.equals(cfg.id) && cfg.path.trim().length() == 0);
        if (maybeModels) {
            String r = probeModels(cfg);
            if (r != null) return r;   // null = 这个服务没有 /models，落到最小请求
        }
        return probeChat(cfg);
    }

    /** GET {地址}/models。返回 null 表示服务没实现这个接口——那不是失败，换条路试。 */
    private static String probeModels(AiProto.Cfg cfg) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(AiProto.base(cfg.endpoint) + "/models").openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (cfg.key.trim().length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.key.trim());
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 404 || code == 405 || code == 400) return null;
            if (code >= 400) {
                if (code == 401 && cfg.key.trim().length() == 0) {
                    // 没填 Key 的 401 是好消息：地址和网络都通了，就差 Key
                    throw new Exception("地址可达、服务在线，但该服务要求填写 API Key");
                }
                throw new Exception(friendlyError(code, resp));
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

    /** 发一条最小的对话请求做探活。文心连 access_token 一起验了。 */
    private static String probeChat(AiProto.Cfg cfg) throws Exception {
        String body = AiProto.body(cfg, "你只需要回复两个字", "ping", true);
        String token = AiProto.ERNIE.equals(cfg.id) ? baiduToken(cfg) : null;
        String resp = postJson(AiProto.url(cfg, token), AiProto.bearer(cfg), body);
        JSONObject data = toJson(resp);
        String err = AiProto.bodyError(data);
        if (err != null) throw new Exception(err);
        if (AiProto.extract(cfg, data).length() == 0) throw new Exception(noContent(data));
        return "服务在线，模型有回应";
    }

    /**
     * 读服务端可用的模型清单（GET /models），按名字排序。
     *
     * 配好地址和 Key 之后直接选一个，比照着文档手敲强：厂商的模型名过几个月就可能下架
     * （智谱的 glm-4-flash 现在就查不到了），手敲的名字要到总结失败才发现。
     *
     * 通义原生、百度文心这类没有 /models 的服务会在 404 上失败，调用方提示手填即可。
     */
    public static List<String> listModels(AiProto.Cfg cfg) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)
                new URL(AiProto.base(cfg.endpoint) + "/models").openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (cfg.key.trim().length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + cfg.key.trim());
            }
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) {
                if (code == 404) {
                    throw new Exception("这个服务没有 /models 接口，模型名只能手填"
                            + "（通义原生、百度文心就是这种情况）");
                }
                throw new Exception(friendlyError(code, resp));
            }
            JSONObject o = toJson(resp);
            JSONArray data = o.optJSONArray("data");
            List<String> out = new ArrayList<String>();
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject m = data.optJSONObject(i);
                    if (m == null) continue;
                    String id = m.optString("id", "").trim();
                    if (id.length() > 0) out.add(id);
                }
            }
            if (out.isEmpty()) throw new Exception("服务返回的模型清单是空的");
            Collections.sort(out);
            return out;
        } finally {
            conn.disconnect();
        }
    }

    /** 换 token 这类简单 GET。 */
    public static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code >= 400) throw new Exception(friendlyError(code, resp));
            return resp;
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
        String detail = detailOf(body);
        if (code == 401) return "API Key 无效或未授权" + detail;
        if (code == 403) return "没有权限（HTTP 403）" + detail;
        if (code == 404) return "接口地址不正确（HTTP 404）" + detail;
        if (code == 429) return "请求过于频繁或被限流" + detail;
        if (code >= 500) return "服务端错误 HTTP " + code + detail;
        return "请求失败 HTTP " + code + detail;
    }

    /**
     * 把错误详情抠出来。各家的错误体长得不一样：OpenAI 是 error.message，
     * 百度是 error_code + error_msg，通义 / 智谱 / 多数网关是 code + message。
     * 都认一遍，别让用户拿到一个只有状态码的空错误。
     */
    private static String detailOf(String body) {
        if (body == null || body.trim().length() == 0) return "";
        try {
            JSONObject o = new JSONObject(body);
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String m = err.optString("message", "");
                if (m.length() == 0) m = err.optString("msg", "");
                if (m.length() > 0) return "：" + m;
            } else if (o.opt("error") instanceof String) {
                String s = (String) o.opt("error");
                if (s.length() > 0) return "：" + s;
            }
            String em = o.optString("error_msg", "");
            if (em.length() > 0) {
                int ec = o.optInt("error_code", 0);
                return "：" + em + (ec != 0 ? "（错误码 " + ec + "）" : "");
            }
            String m = o.optString("message", "");
            if (m.length() == 0) m = o.optString("msg", "");
            if (m.length() > 0) return "：" + m;
        } catch (Exception ignored) {}
        return "：" + shortOf(body);
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