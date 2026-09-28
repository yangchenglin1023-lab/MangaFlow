package com.deepwork.rpgmvviewer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** 文件夹摘要工具：「N 张图 · M 个文件 · K 个子目录」，带缓存避免列表滚动重复扫描。 */
public final class FolderInfo {

    private static final Map<String, String> CACHE = new HashMap<>();

    private FolderInfo() {}

    public static void clearCache() {
        CACHE.clear();
    }

    /** 文件夹摘要；不可读返回"无法读取"，空返回"空文件夹"。 */
    public static String describe(File dir) {
        String key = dir.getAbsolutePath();
        String c = CACHE.get(key);
        if (c != null) return c;
        File[] ch = dir.listFiles();
        String s;
        if (ch == null) {
            s = "无法读取";
        } else if (ch.length == 0) {
            s = "空文件夹";
        } else {
            int imgs = 0, files = 0, dirs = 0;
            for (File f : ch) {
                if (f.isDirectory()) dirs++;
                else {
                    files++;
                    if (MainActivity.isImageExt(MainActivity.extOf(f))) imgs++;
                }
            }
            s = imgs + " 张图 · " + files + " 个文件";
            if (dirs > 0) s += " · " + dirs + " 个子目录";
        }
        CACHE.put(key, s);
        return s;
    }

    /** 图片文件数量；不可读返回 -1。 */
    public static int countImages(File dir) {
        File[] ch = dir.listFiles();
        if (ch == null) return -1;
        int n = 0;
        for (File f : ch) {
            if (f.isFile() && MainActivity.isImageExt(MainActivity.extOf(f))) n++;
        }
        return n;
    }
}
