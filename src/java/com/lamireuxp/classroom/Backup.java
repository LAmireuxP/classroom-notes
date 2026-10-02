package com.lamireuxp.classroom;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份：JSON 导出/导入。
 * 通过 SAF（系统文件选择器）真正落地到用户选定位置，修掉原版在 WebView 里点了没反应的问题。
 * 导出同时提供 Markdown。
 */
public final class Backup {

    /** 工具类，不实例化。 */
    private Backup() {}

    // 备份走两条独立通道：JSON（可再导入，格式与网页版互通）与 Markdown（给人看）。
    // 两者都只做「把 Db 里的东西转成文本 / 把文本转回 Db」，不关心文件落在哪——
    // 选择路径、读写流都是调用方（Activity 的 SAF 回调）的事。

    /** 根节点上的格式标记。导入时只用来挡「拿错文件」，不强求存在。 */
    private static final String FORMAT = "classroom-v2";

    // ---------------- JSON 备份 ----------------

    public static String exportJson(Context c) throws Exception {
        Db db = Db.get(c);
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("exportedAt", System.currentTimeMillis());
        JSONArray courses = new JSONArray();
        // allCourses：含已归档的课。归档课是历史数据，备份丢掉它们等于静默丽数据。
        for (Db.Course course : db.allCourses()) {
            JSONObject co = new JSONObject();
            co.put("id", course.id);
            co.put("name", nz(course.name));
            co.put("teacher", nz(course.teacher));
            co.put("color", nz(course.color));
            co.put("archived", course.archived);

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
                // 配图文件名（相对名，不带路径）——文件本身打进 zip，导入时拼当前设备路径。
                // 旧版备份没这个字段，导入时 optString 返回空，笔记无配图，向后兼容。
                o.put("images", imageBasenames(n.images));
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
                // 提醒时间也带上：不带的话导出再导入，提醒就静默丢了。
                // 这是本项目自己的补充字段，网页版读到会忽略，双向互通不受影响。
                o.put("remindAt", t.remindAt);
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

        Parsed parsed = parse(c, courses);
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
    private static Parsed parse(Context c, JSONArray courses) throws Exception {
        Parsed p = new Parsed();
        for (int i = 0; i < courses.length(); i++) {
            JSONObject co = courses.optJSONObject(i);
            if (co == null) throw new Exception("第 " + (i + 1) + " 门课程不是有效记录");

            Db.Course course = new Db.Course();
            course.id = idOrNew(co.optString("id", ""));
            course.name = co.optString("name", "未命名课程");
            course.teacher = co.optString("teacher", "");
            course.color = co.optString("color", "#4f5bff");
            // 归档状态跟着备份走；旧备份没这个字段，默认不归档
            course.archived = co.optBoolean("archived", false);
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
                // images：备份里存的是相对文件名，导入时拼当前设备的 note-img 绝对路径。
                // 旧版备份没这个字段，optString 返回空——笔记无配图，向后兼容。
                n.images = resolveImagePaths(new File(c.getFilesDir(), "note-img"),
                        o.optString("images", ""));
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
                t.remindAt = o.optLong("remindAt", 0);
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
        // 含归档课：Markdown 是给人看的完整学习记录，归档的历史课也在内
        for (Db.Course course : db.allCourses()) {
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

    // ---------------- ZIP 备份（含配图） ----------------

    /**
     * 把 JSON + 被引用的配图文件打进一个 zip，写到 uri 指向的位置。
     *
     * 1.6 之前的备份是纯 .json 文本，配图（文件，不在 DB 里）不包含——换机/重装后配图丢失。
     * 1.7 起改成 zip：内部 data.json + note-img/ 下被引用的图片文件。
     * zip 里的 data.json 仍是 classroom-v2 格式，手动解出来仍能与网页版互通。
     *
     * 只打包「被某条笔记引用」的图片，孤立文件（笔记已删但文件残留）不带——
     * 既缩小体积，也避免导入时复活垃圾。
     */
    public static void writeZip(Context c, Uri uri, String json) throws Exception {
        Set<String> imageNames = collectImageNames(new JSONObject(json));

        OutputStream os = c.getContentResolver().openOutputStream(uri, "wt");
        if (os == null) throw new Exception("无法写入该位置");
        ZipOutputStream zos = new ZipOutputStream(os);
        try {
            ZipEntry data = new ZipEntry("data.json");
            zos.putNextEntry(data);
            zos.write(json.getBytes("UTF-8"));
            zos.closeEntry();

            File imgDir = new File(c.getFilesDir(), "note-img");
            byte[] buf = new byte[8192];
            for (String name : imageNames) {
                File f = new File(imgDir, name);
                if (!f.exists()) continue;   // 文件已不在（被删/换机），跳过不报错
                ZipEntry ie = new ZipEntry("note-img/" + name);
                zos.putNextEntry(ie);
                FileInputStream fis = new FileInputStream(f);
                try {
                    int n;
                    while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                } finally {
                    fis.close();
                }
                zos.closeEntry();
            }
        } finally {
            zos.close();
        }
    }

    /**
     * 统一导入入口：按文件头判断是 zip 还是旧版 JSON。
     * zip 头是 "PK"（0x50 0x4B），JSON 以 '{' 开头。
     * 两种都能导入——新 zip 含配图，旧 JSON 无配图但数据完整。
     */
    public static int importBackup(Context c, Uri uri) throws Exception {
        InputStream peek = c.getContentResolver().openInputStream(uri);
        if (peek == null) throw new Exception("无法读取该文件");
        byte[] head = new byte[2];
        int read;
        try {
            read = peek.read(head);
        } finally {
            peek.close();
        }
        if (read >= 2 && head[0] == 'P' && head[1] == 'K') {
            return importZip(c, uri);
        }
        return importJson(c, readText(c, uri));
    }

    /**
     * 解 zip 备份：先解配图文件到 note-img/，再解 data.json 走 importJson。
     * 图片要先落盘——importJson → parse 拼绝对路径时文件得在位。
     */
    private static int importZip(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("无法读取该文件");
        File imgDir = new File(c.getFilesDir(), "note-img");
        imgDir.mkdirs();
        ZipInputStream zis = new ZipInputStream(in);
        String json = null;
        byte[] buf = new byte[8192];
        try {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String name = e.getName();
                if (name.equals("data.json")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int n;
                    while ((n = zis.read(buf)) > 0) bos.write(buf, 0, n);
                    json = new String(bos.toByteArray(), "UTF-8");
                } else if (name.startsWith("note-img/")) {
                    // 防路径穿越：只取 basename，不接受 ../ 之类
                    String fname = name.substring("note-img/".length());
                    int slash = fname.lastIndexOf('/');
                    if (slash >= 0) fname = fname.substring(slash + 1);
                    if (fname.length() == 0 || fname.contains("..")) {
                        zis.closeEntry();
                        continue;
                    }
                    File f = new File(imgDir, fname);
                    FileOutputStream fos = new FileOutputStream(f);
                    try {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    } finally {
                        fos.close();
                    }
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        if (json == null) throw new Exception("备份包里没有 data.json");
        return importJson(c, json);
    }

    // ---------------- 配图文件名转换 ----------------

    /** 把 Note.images（绝对路径，分号分隔）转成相对文件名（basename），用于导出。 */
    private static String imageBasenames(String images) {
        if (images == null || images.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : images.split(";")) {
            if (p.length() == 0) continue;
            int slash = p.lastIndexOf('/');
            String name = slash >= 0 ? p.substring(slash + 1) : p;
            if (sb.length() > 0) sb.append(";");
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * 把备份里的 images（相对文件名，分号分隔）拼成当前设备的绝对路径，用于导入。
     * 已经是绝对路径的（旧格式或外部写入）原样保留。
     */
    private static String resolveImagePaths(File imgDir, String images) {
        if (images == null || images.length() == 0) return "";
        String dir = imgDir.getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        for (String name : images.split(";")) {
            if (name.length() == 0) continue;
            String abs = name.startsWith("/") ? name : (dir + "/" + name);
            if (sb.length() > 0) sb.append(";");
            sb.append(abs);
        }
        return sb.toString();
    }

    /** 从导出的 JSON 里收集所有被引用的配图文件名（basename），writeZip 只打包这些。 */
    private static Set<String> collectImageNames(JSONObject root) throws Exception {
        Set<String> names = new HashSet<String>();
        JSONArray courses = root.optJSONArray("courses");
        if (courses == null) return names;
        for (int i = 0; i < courses.length(); i++) {
            JSONObject co = courses.optJSONObject(i);
            if (co == null) continue;
            JSONArray notes = co.optJSONArray("notes");
            for (int j = 0; notes != null && j < notes.length(); j++) {
                JSONObject n = notes.optJSONObject(j);
                if (n == null) continue;
                String imgs = n.optString("images", "");
                if (imgs.length() == 0) continue;
                for (String p : imgs.split(";")) {
                    int slash = p.lastIndexOf('/');
                    String name = slash >= 0 ? p.substring(slash + 1) : p;
                    if (name.length() > 0) names.add(name);
                }
            }
        }
        return names;
    }

    /** null 安全取字符串（导出时字段可能为空）。 */
    private static String nz(String s) { return s == null ? "" : s; }
}