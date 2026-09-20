package com.lamireuxp.classroom;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份：JSON 导出/导入。
 * 通过 SAF（系统文件选择器）真正落地到用户选定位置，修掉原版在 WebView 里点了没反应的问题。
 * 导出同时提供 Markdown。
 */
public final class Backup {

    private Backup() {}

    /** 根节点上的格式标记。导入时只用来挡「拿错文件」，不强求存在。 */
    private static final String FORMAT = "classroom-v2";

    // ---------------- JSON 备份 ----------------

    public static String exportJson(Context c) throws Exception {
        Db db = Db.get(c);
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray courses = new JSONArray();
        for (Db.Course course : db.courses()) {
            JSONObject co = new JSONObject();
            co.put("id", course.id);
            co.put("name", nz(course.name));
            co.put("teacher", nz(course.teacher));
            co.put("color", nz(course.color));

            JSONArray notes = new JSONArray();
            for (Db.Note n : db.notes(course.id, null)) {
                JSONObject o = new JSONObject();
                o.put("id", n.id);
                o.put("title", nz(n.title));
                o.put("date", nz(n.date));
                o.put("content", nz(n.content));
                o.put("pinned", n.pinned);
                o.put("created", n.created);
                JSONArray kp = new JSONArray();
                for (String k : n.keyPoints) kp.put(k);
                o.put("keyPoints", kp);
                notes.put(o);
            }
            co.put("notes", notes);

            JSONArray todos = new JSONArray();
            for (Db.Todo t : db.todos(course.id)) {
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("title", nz(t.title));
                o.put("due", nz(t.due));
                o.put("priority", nz(t.priority));
                o.put("completed", t.completed);
                o.put("created", t.created);
                todos.put(o);
            }
            co.put("todos", todos);
            courses.put(co);
        }
        root.put("courses", courses);
        return root.toString(2);
    }

    /**
     * 导入：整体替换现有数据。返回导入的课程数。
     *
     * 分两步走，顺序是关键：
     *   1. 先把整份 JSON 解析成内存对象——格式不对、记录结构不对都在这一步失败；
     *   2. 全部通过之后，才在一个事务里清空旧数据并重建。
     *
     * 原先是边解析边写、而且先清空再写：文件里随便哪一条记录有问题，
     * 用户看到的是「导入失败」，手里却已经是一个被清空（或写了一半）的库。
     * 现在只要有任何一步不通过，数据库一个字节都不会动。
     */
    public static int importJson(Context c, String json) throws Exception {
        JSONObject root = new JSONObject(json);

        // 带 format 的必须是自己的格式；不带的（更早的网页版导出文件）照常放行
        String format = root.optString("format", "");
        if (format.length() > 0 && !FORMAT.equals(format)) {
            throw new Exception("不认识的备份格式：" + format);
        }
        JSONArray courses = root.optJSONArray("courses");
        if (courses == null) throw new Exception("文件格式不正确");

        Parsed parsed = parse(courses);
        // 清空 + 重建整个交给 replaceAll：它自己开一个事务，内部用预编译语句批量写。
        // 不再逐条查存在性，也不再出现「外层事务里套内层事务」——
        // 那样一旦内层失败只回滚内层，外层照样提交，会留下半新半旧的库。
        Db.get(c).replaceAll(parsed.courses, parsed.notes, parsed.todos);
        return parsed.courses.size();
    }

    /** 解析结果。全部解析通过之后才允许碰数据库。 */
    private static final class Parsed {
        final List<Db.Course> courses = new ArrayList<Db.Course>();
        final List<Db.Note> notes = new ArrayList<Db.Note>();
        final List<Db.Todo> todos = new ArrayList<Db.Todo>();
    }

    /**
     * 解析课程 / 笔记 / 待办。字段缺失一律取默认值——备份来自哪个版本都尽量把数据救回来；
     * 只有结构本身不对（数组里塞的不是对象）才报错，那种文件继续导入没有意义。
     */
    private static Parsed parse(JSONArray courses) throws Exception {
        Parsed p = new Parsed();
        for (int i = 0; i < courses.length(); i++) {
            JSONObject co = courses.optJSONObject(i);
            if (co == null) throw new Exception("第 " + (i + 1) + " 门课程不是有效记录");

            Db.Course course = new Db.Course();
            course.id = idOrNew(co.optString("id", ""));
            course.name = co.optString("name", "未命名课程");
            course.teacher = co.optString("teacher", "");
            course.color = co.optString("color", "#4f5bff");
            p.courses.add(course);

            JSONArray notes = co.optJSONArray("notes");
            for (int j = 0; notes != null && j < notes.length(); j++) {
                JSONObject o = notes.optJSONObject(j);
                if (o == null) throw new Exception("第 " + (i + 1) + " 门课程的第 " + (j + 1) + " 条笔记不是有效记录");
                Db.Note n = new Db.Note();
                n.id = idOrNew(o.optString("id", ""));
                n.courseId = course.id;
                n.title = o.optString("title", "");
                n.date = o.optString("date", "");
                n.content = o.optString("content", "");
                n.pinned = o.optBoolean("pinned", false);
                n.created = o.optLong("created", System.currentTimeMillis());
                JSONArray kp = o.optJSONArray("keyPoints");
                for (int k = 0; kp != null && k < kp.length(); k++) {
                    n.keyPoints.add(kp.optString(k, ""));
                }
                p.notes.add(n);
            }

            JSONArray todos = co.optJSONArray("todos");
            for (int j = 0; todos != null && j < todos.length(); j++) {
                JSONObject o = todos.optJSONObject(j);
                if (o == null) throw new Exception("第 " + (i + 1) + " 门课程的第 " + (j + 1) + " 个待办不是有效记录");
                Db.Todo t = new Db.Todo();
                t.id = idOrNew(o.optString("id", ""));
                t.courseId = course.id;
                t.title = o.optString("title", "");
                t.due = o.optString("due", "");
                t.priority = o.optString("priority", "medium");
                t.completed = o.optBoolean("completed", false);
                t.created = o.optLong("created", System.currentTimeMillis());
                p.todos.add(t);
            }
        }
        return p;
    }

    /** id 缺失或为空就现生成一个：两条空 id 的记录会在主键上互相覆盖，悄悄少掉一条。 */
    private static String idOrNew(String id) {
        return id == null || id.length() == 0 ? Id.gen() : id;
    }

    // ---------------- Markdown ----------------

    public static String exportMarkdown(Context c) {
        Db db = Db.get(c);
        StringBuilder sb = new StringBuilder();
        String[] label = {"低", "中", "高"};
        for (Db.Course course : db.courses()) {
            sb.append("# ").append(nz(course.name)).append("\n\n");
            if (course.teacher != null && course.teacher.length() > 0) {
                sb.append("> 授课教师：").append(course.teacher).append("\n\n");
            }
            List<Db.Note> notes = db.notes(course.id, null);
            for (Db.Note n : notes) {
                sb.append("## ").append(nz(n.title)).append("\n\n");
                if (n.date != null && n.date.length() > 0) {
                    sb.append("- 日期：").append(n.date).append("\n");
                }
                if (n.content != null && n.content.length() > 0) {
                    sb.append("\n").append(n.content).append("\n");
                }
                if (n.keyPoints != null && !n.keyPoints.isEmpty()) {
                    sb.append("\n**重点**\n");
                    for (String k : n.keyPoints) sb.append("- ").append(k).append("\n");
                }
                sb.append("\n");
            }
            List<Db.Todo> todos = db.todos(course.id);
            if (!todos.isEmpty()) {
                sb.append("## 待办\n\n");
                for (Db.Todo t : todos) {
                    sb.append("- [").append(t.completed ? "x" : " ").append("] ")
                            .append(nz(t.title));
                    if (t.due != null && t.due.length() > 0) sb.append("（截止 ").append(t.due).append("）");
                    sb.append("\n");
                }
                sb.append("\n");
            }
            sb.append("---\n\n");
        }
        if (sb.length() == 0) sb.append("（暂无内容）\n");
        return sb.toString();
    }

    // ---------------- 读写 ----------------

    public static void writeText(Context c, Uri uri, String text) throws Exception {
        OutputStream os = c.getContentResolver().openOutputStream(uri, "wt");
        if (os == null) throw new Exception("无法写入该位置");
        try {
            os.write(text.getBytes("UTF-8"));
            os.flush();
        } finally {
            os.close();
        }
    }

    public static String readText(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取该文件");
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}