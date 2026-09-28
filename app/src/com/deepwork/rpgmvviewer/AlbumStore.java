package com.deepwork.rpgmvviewer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 画册（收藏的图片文件夹）存取。纯 Java 无 Android 依赖，可在桌面单元测试。
 * 存储格式（每行一条）：URL编码(名称) \t URL编码(路径) \t 上次看到页 \t 上次打开时间戳
 */
public final class AlbumStore {

    public static final class Album {
        public final String path;
        public String name;
        public int lastIndex;
        public long lastOpenAt;

        Album(String name, String path, int lastIndex, long lastOpenAt) {
            this.name = name;
            this.path = path;
            this.lastIndex = lastIndex;
            this.lastOpenAt = lastOpenAt;
        }
    }

    private final File file;
    private final List<Album> albums = new ArrayList<>();

    public AlbumStore(File file) {
        this.file = file;
        load();
    }

    /** 画册列表快照（最近打开在前）。 */
    public synchronized List<Album> list() {
        return new ArrayList<>(albums);
    }

    public synchronized Album find(String path) {
        for (Album a : albums) {
            if (a.path.equals(path)) return a;
        }
        return null;
    }

    /** 新增画册（默认名=文件夹名）；已存在则仅刷新时间。返回是否新增。 */
    public synchronized boolean add(String path) {
        Album a = find(path);
        if (a != null) {
            a.lastOpenAt = System.currentTimeMillis();
            albums.remove(a);
            albums.add(0, a);
            save();
            return false;
        }
        String name = new File(path).getName();
        if (name == null || name.isEmpty()) name = path;
        albums.add(0, new Album(name, path, 0, System.currentTimeMillis()));
        save();
        return true;
    }

    public synchronized boolean rename(String path, String newName) {
        Album a = find(path);
        if (a == null) return false;
        String n = newName == null ? "" : newName.trim();
        if (n.isEmpty()) return false;
        a.name = n;
        save();
        return true;
    }

    public synchronized boolean remove(String path) {
        Iterator<Album> it = albums.iterator();
        while (it.hasNext()) {
            if (it.next().path.equals(path)) {
                it.remove();
                save();
                return true;
            }
        }
        return false;
    }

    /** 更新浏览进度并置顶；画册不存在时不做任何事。 */
    public synchronized void touch(String path, int lastIndex) {
        Album a = find(path);
        if (a == null) return;
        a.lastIndex = Math.max(0, lastIndex);
        a.lastOpenAt = System.currentTimeMillis();
        albums.remove(a);
        albums.add(0, a);
        save();
    }

    // ---------- 持久化 ----------

    private void load() {
        albums.clear();
        if (!file.isFile()) return;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] p = line.split("\t", -1);
                if (p.length < 2) continue;
                try {
                    albums.add(new Album(
                            URLDecoder.decode(p[0], "UTF-8"),
                            URLDecoder.decode(p[1], "UTF-8"),
                            p.length > 2 ? Integer.parseInt(p[2]) : 0,
                            p.length > 3 ? Long.parseLong(p[3]) : 0L));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                for (Album a : albums) {
                    w.write(URLEncoder.encode(a.name, "UTF-8"));
                    w.write('\t');
                    w.write(URLEncoder.encode(a.path, "UTF-8"));
                    w.write('\t');
                    w.write(String.valueOf(a.lastIndex));
                    w.write('\t');
                    w.write(String.valueOf(a.lastOpenAt));
                    w.write('\n');
                }
            }
        } catch (Exception ignored) {
        }
    }
}
