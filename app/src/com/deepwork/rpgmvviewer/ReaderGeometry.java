package com.deepwork.rpgmvviewer;

/**
 * 纯 Java 的阅读器几何模型：每页高度、前缀和（O(1) 定位）、页面查找。
 * 无 Android 依赖，可在桌面 JDK 单元测试（见 tools/src/ReaderGeometryTest.java）。
 */
public final class ReaderGeometry {

    private final int[] heights;
    /** cum[i] = 前 i 页的累计高度，长 n+1。 */
    private final long[] cum;
    private long total;

    public ReaderGeometry(int[] initialHeights) {
        heights = new int[initialHeights.length];
        cum = new long[heights.length + 1];
        for (int i = 0; i < initialHeights.length; i++) {
            heights[i] = Math.max(1, initialHeights[i]);
            cum[i + 1] = cum[i] + heights[i];
        }
        total = cum[heights.length];
    }

    public int pageCount() {
        return heights.length;
    }

    public long total() {
        return total;
    }

    public int height(int i) {
        return heights[i];
    }

    /** 第 i 页顶部的内容坐标（前 i 页累计高度）。 */
    public long cumStart(int i) {
        return cum[Math.max(0, Math.min(heights.length, i))];
    }

    /** 更新第 i 页高度，返回高度变化量（新-旧）。 */
    public int setHeight(int i, int newH) {
        newH = Math.max(1, newH);
        int old = heights[i];
        if (old == newH) return 0;
        heights[i] = newH;
        long d = (long) newH - old;
        for (int k = i + 1; k < cum.length; k++) cum[k] += d;
        total += d;
        return newH - old;
    }

    /** 内容坐标 y 所在页索引；越界夹到 [0, n-1]。二分，O(log n)。 */
    public int pageAt(long y) {
        int n = heights.length;
        if (n == 0) return 0;
        if (y <= 0) return 0;
        if (y >= total) return n - 1;
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cum[mid + 1] > y) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }
}
