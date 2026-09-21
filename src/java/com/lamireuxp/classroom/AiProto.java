package com.lamireuxp.classroom;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * AI 接口协议 —— 把「一家厂商一套协议」的差异全收在这一处。
 *
 * 原来这里默认全世界都是 OpenAI：POST {地址}/chat/completions + Bearer +
 * 从 choices[0].message.content 取字。国内厂商并不都长这样：
 *
 *  - 阿里通义原生把消息放在 input.messages，正文在 output.text / output.choices；
 *  - 百度文心老接口要先把 client_id + client_secret 换成 access_token 挂在 URL 上，
 *    正文在 result 里，而且**出错也返回 HTTP 200**（只认 error_code，不能只看状态码）；
 *  - 各家云网关路径各写各的，还有把模型名写进路径的（文心）。
 *
 * 所以定义四种协议：OpenAI 兼容（绝大多数厂商，含各家的 compatible-mode）、
 * 通义原生、百度文心、自定义（路径 / 鉴权 / 取值全部可填，兜住没有预设的厂商）。
 * 怎么发请求、怎么取回复由协议决定，Net 只管传输，两边互不知道对方的细节。
 */
public final class AiProto {

    private AiProto() {}

    // ---------- 协议 id ----------
    public static final String OPENAI = "openai";
    public static final String DASHSCOPE = "dashscope";
    public static final String ERNIE = "ernie";
    public static final String CUSTOM = "custom";

    /** 文心老接口的路径模板：模型名就嵌在路径里。 */
    private static final String ERNIE_PATH = "/rpc/2.0/ai_custom/v1/wenxinworkshop/chat/{model}";
    private static final String ERNIE_OAUTH = "/oauth/2.0/token";
    /** 通义原生：和 OpenAI 的 /chat/completions 完全不是一条路。 */
    private static final String DASHSCOPE_PATH = "/api/v1/services/aigc/text-generation/generation";

    public static String[] ids() {
        return new String[] { OPENAI, DASHSCOPE, ERNIE, CUSTOM };
    }

    public static String label(String id) {
        if (DASHSCOPE.equals(id)) return "通义千问（原生）";
        if (ERNIE.equals(id)) return "百度文心";
        if (CUSTOM.equals(id)) return "自定义";
        return "OpenAI 兼容";
    }

    public static String desc(String id) {
        if (DASHSCOPE.equals(id)) return "阿里 DashScope 原生接口，input / output 结构";
        if (ERNIE.equals(id)) return "百度文心老接口：API Key + Secret Key 换 access_token";
        if (CUSTOM.equals(id)) return "自己填请求路径、鉴权方式与取值字段";
        return "DeepSeek / 智谱 / Kimi / 混元 / 豆包 / 讯飞 / 硅基流动等绝大多数厂商";
    }

    // ================== 配置 ==================

    /** 一个厂商配置的全体可变项。存进 Prefs 就是为了原样还给它。 */
    public static final class Cfg {
        public String id = OPENAI;
        public String endpoint = "";
        /** API Key；文心协议下是 client_id。 */
        public String key = "";
        /** 文心协议的 client_secret，其它协议不用。 */
        public String secret = "";
        public String model = "";
        /** 自定义协议：请求路径（相对地址），空则用 /chat/completions。 */
        public String path = "";
        /** 自定义协议：响应取值字段路径，如 output.text；空则按 OpenAI 形状找。 */
        public String field = "";
        /** 自定义协议：bearer | query | none。 */
        public String auth = "bearer";
    }

    /** AI 总结是「对话」类接口，没有 Key（文心还要 Secret）就没法开工。 */
    public static String missing(Cfg c) {
        if (ERNIE.equals(c.id)) {
            if (c.key.trim().length() == 0) return "请填写文心的 API Key（即 client_id）";
            if (c.secret.trim().length() == 0) return "请填写文心的 Secret Key（即 client_secret）";
        } else if (c.key.trim().length() == 0 && !"none".equals(c.auth)) {
            return "请填写 API Key";
        }
        if (c.endpoint.trim().length() == 0) return "请填写 API 地址";
        if (c.model.trim().length() == 0 && !CUSTOM.equals(c.id)) return "请填写模型名";
        return null;
    }

    // ================== 地址 ==================

    /** 请求地址。文心要先把 access_token 换好传进来。 */
    public static String url(Cfg c, String accessToken) {
        String b = base(c.endpoint);
        if (ERNIE.equals(c.id)) {
            String p = c.path.trim().length() > 0 ? c.path.trim() : ERNIE_PATH;
            p = p.replace("{model}", c.model.trim());
            String u = b + slash(p);
            return u + (u.indexOf('?') >= 0 ? "&" : "?") + "access_token=" + accessToken;
        }
        if (DASHSCOPE.equals(c.id)) {
            String p = c.path.trim().length() > 0 ? c.path.trim() : DASHSCOPE_PATH;
            return b + slash(p);
        }
        String path = c.path.trim();
        if (CUSTOM.equals(c.id) && path.length() > 0) {
            String u = b + slash(path);
            // 鉴权挂在查询串上的网关（access_token / api_key 这类）：路径里没写就补上
            if ("query".equals(c.auth) && u.indexOf("token=") < 0 && u.indexOf("key=") < 0) {
                u = u + (u.indexOf('?') >= 0 ? "&" : "?") + "access_token=" + c.key.trim();
            }
            return u;
        }
        return b + "/chat/completions";
    }

    /**
     * 文心的换 token 地址。参数挂在查询串上——百度文档给的例子就是
     * `…/oauth/2.0/token?grant_type=client_credentials&client_id=…`，
     * 放请求体里它会当参数缺失。
     *
     * 同源拼接：地址填的是 aip.baidubce.com 还是自建镜像，都从用户填的地址派生。
     */
    public static String oauthUrl(Cfg c) {
        String b = base(c.endpoint);
        int i = b.indexOf("://");
        if (i > 0) {
            int j = b.indexOf('/', i + 3);
            if (j > 0) b = b.substring(0, j);
        }
        return b + ERNIE_OAUTH + "?" + oauthQuery(c);
    }

    /** 换 token 的查询串。 */
    public static String oauthQuery(Cfg c) {
        return "grant_type=client_credentials&client_id=" + enc(c.key.trim())
                + "&client_secret=" + enc(c.secret.trim());
    }

    /** 发给请求头的 Bearer；文心（token 在 URL 上）和自定义的无鉴权返回 null。 */
    public static String bearer(Cfg c) {
        if (ERNIE.equals(c.id)) return null;
        if (CUSTOM.equals(c.id) && !"bearer".equals(c.auth)) return null;
        return c.key.trim().length() == 0 ? null : c.key.trim();
    }

    // ================== 请求体 ==================

    public static String body(Cfg c, String system, String user, boolean tiny) throws Exception {
        JSONObject b = new JSONObject();
        int maxTokens = tiny ? 8 : 2048;   // 「测试连接」只发一条最小的请求，别为自检花掉一次完整额度

        if (DASHSCOPE.equals(c.id)) {
            b.put("model", c.model.trim());
            JSONObject input = new JSONObject();
            input.put("messages", messages(system, user));
            b.put("input", input);
            JSONObject pa = new JSONObject();
            // result_format=message 才会给 output.choices[0].message.content；
            // 默认的 text 只给 output.text（纯文本，拿不到分段）。
            pa.put("result_format", "message");
            pa.put("temperature", 0.3);
            pa.put("max_tokens", maxTokens);
            b.put("parameters", pa);
            return b.toString();
        }

        if (ERNIE.equals(c.id)) {
            // 文心的 system 是顶层字段，不是 messages 里的一条；
            // 可选项一律不加——它对多余参数很敏感，直接报 Invalid parameter。
            JSONArray m = new JSONArray();
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", user);
            m.put(u);
            b.put("messages", m);
            b.put("system", system);
            b.put("temperature", 0.3);
            return b.toString();
        }

        // OpenAI 兼容 / 自定义：标准形状，自建网关基本都认这个
        b.put("model", c.model.trim());
        b.put("temperature", 0.3);
        b.put("max_tokens", maxTokens);
        b.put("messages", messages(system, user));
        return b.toString();
    }

    private static JSONArray messages(String system, String user) throws Exception {
        JSONArray msgs = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", system);
        msgs.put(sys);
        JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", user);
        msgs.put(usr);
        return msgs;
    }

    // ================== 响应 ==================

    /**
     * 取出正文。按协议找一轮，全落空再把整棵 JSON 挖一遍——
     * 没预设的厂商也能活下来，这比给每家写一个解析器划算得多。
     */
    public static String extract(Cfg c, JSONObject resp) {
        String s = null;
        if (DASHSCOPE.equals(c.id)) {
            JSONObject out = resp.optJSONObject("output");
            if (out != null) {
                s = choiceContent(out);
                if (blank(s)) s = str(out, "text");
            }
        } else if (ERNIE.equals(c.id)) {
            s = str(resp, "result");
        } else if (CUSTOM.equals(c.id) && c.field.trim().length() > 0) {
            s = byPath(resp, c.field.trim());
        } else {
            s = choiceContent(resp);
        }
        if (blank(s)) s = deepText(resp, 6);
        return s == null ? "" : s.trim();
    }

    /**
     * 错误码。**有的厂商出错也返回 HTTP 200**（百度就是），只看状态码会把
     * 「Access token invalid」当成一次成功的总结，最后在解析 JSON 时崩掉。
     */
    public static String bodyError(JSONObject o) {
        if (o == null) return null;
        int ec = o.optInt("error_code", 0);
        if (ec != 0) {
            String m = str(o, "error_msg");
            return m == null ? "服务返回错误码 " + ec : m;
        }
        String code = str(o, "code");
        if (code != null && !"0".equals(code) && !"success".equalsIgnoreCase(code)
                && !"ok".equalsIgnoreCase(code)) {
            String m = str(o, "message");
            if (m == null) m = str(o, "msg");
            return m == null ? "服务返回错误码 " + code : m;
        }
        String status = str(o, "status");
        if (status != null && "error".equalsIgnoreCase(status)) {
            String m = str(o, "message");
            return m == null ? "服务返回 status=error" : m;
        }
        return null;
    }

    /** OpenAI 形状：choices[0].message.content（content 可能是分段数组）。 */
    private static String choiceContent(JSONObject o) {
        JSONArray arr = o.optJSONArray("choices");
        if (arr == null || arr.length() == 0) return null;
        JSONObject c0 = arr.optJSONObject(0);
        if (c0 == null) return null;
        JSONObject msg = c0.optJSONObject("message");
        if (msg == null) msg = c0;   // 少数网关把 content 直接挂在 choice 上
        Object content = msg.opt("content");
        if (content instanceof String) {
            String v = ((String) content).trim();
            return v.length() == 0 ? null : v;
        }
        if (content instanceof JSONArray) {   // [{"type":"text","text":"…"}]
            StringBuilder sb = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject p = parts.optJSONObject(i);
                String t = p == null ? null : str(p, "text");
                if (t != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(t);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    /** 按 "output.text"、"choices.0.message.content" 这类点号路径取值。 */
    private static String byPath(JSONObject root, String path) {
        Object cur = root;
        String[] seg = path.split("\\.");
        for (int i = 0; i < seg.length; i++) {
            if (cur instanceof JSONObject) {
                cur = ((JSONObject) cur).opt(seg[i]);
            } else if (cur instanceof JSONArray) {
                int idx = -1;
                try { idx = Integer.parseInt(seg[i].trim()); } catch (Exception ignored) {}
                JSONArray a = (JSONArray) cur;
                if (idx < 0 || idx >= a.length()) return null;
                cur = a.opt(idx);
            } else {
                return null;
            }
        }
        if (cur instanceof String) {
            String v = ((String) cur).trim();
            return v.length() == 0 ? null : v;
        }
        return null;
    }

    /** 整棵 JSON 里找第一个像正文的字符串字段。 */
    private static final String[] TEXT_KEYS =
            { "content", "text", "result", "summary", "answer", "reply", "output", "message" };

    private static String deepText(Object o, int depth) {
        for (int i = 0; i < TEXT_KEYS.length; i++) {
            String r = findKey(o, TEXT_KEYS[i], depth);
            if (r != null) return r;
        }
        return null;
    }

    private static String findKey(Object o, String key, int depth) {
        if (depth <= 0) return null;
        if (o instanceof JSONObject) {
            JSONObject obj = (JSONObject) o;
            String s = str(obj, key);
            if (s != null) return s;
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (key.equals(k)) continue;
                String r = findKey(obj.opt(k), key, depth - 1);
                if (r != null) return r;
            }
            return null;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            for (int i = 0; i < a.length(); i++) {
                String r = findKey(a.opt(i), key, depth - 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ================== 厂商预设 ==================
    //
    // 地址都是实测过的（无 Key 请求返回 401 说明路径存在）。模型名随厂商迭代，
    // 拿不准的宁可不填、只写进 note 让人去控制台抄，也不硬塞一个会 404 的值。

    public static final class Preset {
        public final String name;
        public final String endpoint;
        public final String protocol;
        public final String model;
        /** 填不下的注意事项，选中后显示在页面上。 */
        public final String note;
        /** 控制台地址：在这儿注册账号、创建 API Key。没有自带账号体系的（自建）留空。 */
        public final String console;

        Preset(String name, String endpoint, String protocol, String model, String note,
               String console) {
            this.name = name;
            this.endpoint = endpoint;
            this.protocol = protocol;
            this.model = model;
            this.note = note;
            this.console = console;
        }
    }

    public static List<Preset> presets() {
        List<Preset> l = new ArrayList<Preset>();
        l.add(new Preset("DeepSeek", "https://api.deepseek.com/v1", OPENAI, "deepseek-chat",
                "国内可直连，OpenAI 兼容", "https://platform.deepseek.com/"));
        l.add(new Preset("阿里通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                OPENAI, "qwen-plus", "兼容模式；想用原生 input/output 结构选下面那条",
                "https://bailian.console.aliyun.com/"));
        l.add(new Preset("阿里通义（原生协议）", "https://dashscope.aliyuncs.com",
                DASHSCOPE, "qwen-plus", "DashScope 原生接口：/api/v1/services/aigc/text-generation/generation",
                "https://bailian.console.aliyun.com/"));
        l.add(new Preset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", OPENAI,
                "glm-4.7", "有免费额度；模型名会随版本变，用下面的「读取可用模型」挑",
                "https://open.bigmodel.cn/"));
        l.add(new Preset("月之暗面 Kimi", "https://api.moonshot.cn/v1", OPENAI,
                "moonshot-v1-8k", "长上下文，适合长笔记", "https://platform.moonshot.cn/"));
        l.add(new Preset("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", OPENAI,
                "hunyuan-lite", "", "https://console.cloud.tencent.com/hunyuan"));
        l.add(new Preset("字节豆包（方舟）", "https://ark.cn-beijing.volces.com/api/v3", OPENAI, "",
                "OpenAI 兼容，但模型要填控制台里的「接入点 ID」（ep- 开头），不是模型名",
                "https://console.volcengine.com/ark"));
        l.add(new Preset("讯飞星火", "https://spark-api-open.xf-yun.com/v1", OPENAI, "lite",
                "Key 填控制台的 APIPassword；模型可选 lite / generalv3.5 / 4.0Ultra",
                "https://console.xfyun.cn/"));
        l.add(new Preset("百度千帆（新版）", "https://qianfan.baidubce.com/v2", OPENAI,
                "ernie-4.0-turbo-8k", "OpenAI 兼容，一个 API Key 就够", "https://console.bce.baidu.com/"));
        l.add(new Preset("百度文心（老接口）", "https://aip.baidubce.com", ERNIE, "ernie_speed",
                "要 API Key + Secret Key 两个；模型名就是接口路径里的那段（ernie_speed / completions 等）",
                "https://console.bce.baidu.com/"));
        l.add(new Preset("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", OPENAI,
                "Qwen/Qwen2.5-7B-Instruct", "同一个 Key 还能跑转写（SenseVoice）",
                "https://cloud.siliconflow.cn/"));
        l.add(new Preset("OpenAI", "https://api.openai.com/v1", OPENAI, "gpt-4o-mini",
                "需要能直连 OpenAI 的网络", "https://platform.openai.com/"));
        l.add(new Preset("自建（Ollama / vLLM / one-api）", "http://192.168.1.10:11434/v1",
                OPENAI, "qwen2.5", "局域网自建：地址改成你的 IP，明文 http 已在清单里放行", ""));
        return l;
    }

    // ================== 小工具 ==================

    private static boolean blank(String s) { return s == null || s.trim().length() == 0; }

    /** 取一个字符串字段，空白当没有。 */
    private static String str(JSONObject o, String k) {
        if (o == null) return null;
        Object v = o.opt(k);
        if (!(v instanceof String)) return null;
        String s = ((String) v).trim();
        return s.length() == 0 ? null : s;
    }

    /** 去掉结尾斜杠的地址，拼接路径前统一走这里。 */
    public static String base(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String slash(String p) {
        String t = p.trim();
        return t.startsWith("/") ? t : "/" + t;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}