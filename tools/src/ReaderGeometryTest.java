import com.deepwork.rpgmvviewer.ReaderGeometry;

import java.util.Random;

/**
 * 阅读器几何模型 + 视口锚定不变量测试。
 * 模拟真实场景：300 页、初始全是估计高度、解码顺序随机、真实高度与估计差异大，
 * 验证：① 视口顶部内容点在解码过程中保持不动（不跳、不漂移）
 *      ② 任何内部事件都不可能把视口"传送"回第一张（单步位移不超过该次高度变化量）
 */
public class ReaderGeometryTest {

    static int failures = 0;

    public static void main(String[] args) {
        testPageAtBruteForce();
        testSetHeightConsistency();
        testAnchorStability();
        testNeverTeleport();
        System.out.println(failures == 0 ? "\n全部测试通过" : "\n失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    // ---------- 1. pageAt 与暴力扫描对拍 ----------

    static void testPageAtBruteForce() {
        Random rnd = new Random(42);
        boolean ok = true;
        for (int round = 0; round < 50 && ok; round++) {
            int n = 1 + rnd.nextInt(300);
            int[] hs = new int[n];
            for (int i = 0; i < n; i++) hs[i] = 1 + rnd.nextInt(2000);
            ReaderGeometry g = new ReaderGeometry(hs);
            long total = 0;
            for (int h : hs) total += h;
            if (g.total() != total) { check("total 一致", false); ok = false; break; }
            for (int q = 0; q < 300; q++) {
                long y = (long) (rnd.nextDouble() * total * 1.2) - 100;
                int expect = brutePageAt(hs, y);
                if (g.pageAt(y) != expect) {
                    check("pageAt(" + y + ") 对拍 n=" + n, false);
                    ok = false;
                    break;
                }
            }
        }
        check("pageAt 二分与暴力扫描 50 轮对拍", ok);
    }

    static int brutePageAt(int[] hs, long y) {
        if (y <= 0) return 0;
        long acc = 0;
        for (int i = 0; i < hs.length; i++) {
            acc += hs[i];
            if (y < acc) return i;
        }
        return hs.length - 1;
    }

    // ---------- 2. setHeight 后前缀和/总高一致 ----------

    static void testSetHeightConsistency() {
        Random rnd = new Random(7);
        int[] hs = {500, 500, 500, 500, 500};
        ReaderGeometry g = new ReaderGeometry(hs);
        boolean ok = true;
        for (int step = 0; step < 200; step++) {
            int i = rnd.nextInt(5);
            g.setHeight(i, 100 + rnd.nextInt(1500));
            // 重算校验
            long acc = 0;
            for (int k = 0; k < 5; k++) {
                if (g.cumStart(k) != acc) { ok = false; break; }
                acc += g.height(k);
            }
            if (g.cumStart(5) != acc || g.total() != acc) ok = false;
            if (!ok) break;
        }
        check("setHeight 前缀和一致", ok);
    }

    // ---------- 3. 视口锚定不变量 ----------

    static void testAnchorStability() {
        Random rnd = new Random(2024);
        int n = 300;
        int[] est = new int[n];
        for (int i = 0; i < n; i++) est[i] = 1000; // 初始估计（zoom=1，宽 1000 的屏）
        ReaderGeometry g = new ReaderGeometry(est);

        float top = 150000f; // 视口顶部内容坐标：深入文件夹
        // 追踪视口顶部对应的"内容点"（第几页 + 页内偏移）
        int pageIdx = g.pageAt((long) top);
        float offInPage = top - g.cumStart(pageIdx);

        boolean ok = true;
        for (int step = 0; step < 2000 && ok; step++) {
            int i = rnd.nextInt(n);
            int newH = 300 + rnd.nextInt(1800); // 真实高度与估计差异巨大
            long cumBefore = g.cumStart(i);
            int oldH = g.height(i);
            int delta = g.setHeight(i, newH);
            if (delta == 0) continue;

            // 复刻 VerticalReaderView.onPageReady 的补偿公式
            float topBefore = top;
            if (cumBefore + oldH <= top + 1f) {
                top += delta;                    // 完全在视口上方：平移补偿
            } else if (cumBefore < top) {
                float frac = (top - cumBefore) / (float) oldH;
                top = cumBefore + frac * newH;   // 跨视口顶部：按比例
            }
            if (i == pageIdx) {
                offInPage = offInPage * newH / (float) Math.max(1, oldH);
            }

            // 不变量 1：视口顶部还是同一个内容点（误差 ≤ 1px）
            long expect = (long) (g.cumStart(pageIdx) + offInPage);
            if (Math.abs(top - expect) > 1.5f) {
                check("锚定不变量 step=" + step + " top=" + top + " expect=" + expect, false);
                ok = false;
            }
            // 不变量 2：单步位移不超过本次高度变化量（不存在"传送"）
            if (Math.abs(top - topBefore) > Math.abs(delta) + 2f) {
                check("单步位移超界 step=" + step, false);
                ok = false;
            }
        }
        check("2000 次随机解码，视口锚定稳定", ok);
    }

    // ---------- 4. 绝不"传送"回第一张 ----------

    static void testNeverTeleport() {
        Random rnd = new Random(99);
        int n = 500;
        int[] est = new int[n];
        for (int i = 0; i < n; i++) est[i] = 1200;
        ReaderGeometry g = new ReaderGeometry(est);
        float viewH = 2000f;
        float top = 300000f; // 很深的位置
        boolean ok = true;
        for (int step = 0; step < 3000 && ok; step++) {
            float topBefore = top;
            int event = rnd.nextInt(3);
            if (event == 0) {
                // 解码事件（高度变化 ±800 内）
                int i = rnd.nextInt(n);
                long cumBefore = g.cumStart(i);
                int oldH = g.height(i);
                int newH = 400 + rnd.nextInt(1600);
                int delta = g.setHeight(i, newH);
                if (cumBefore + oldH <= top + 1f) top += delta;
                else if (cumBefore < top) {
                    float frac = (top - cumBefore) / (float) oldH;
                    top = cumBefore + frac * newH;
                }
            } else if (event == 1) {
                // 飞行结束回弹：夹取到 [0, total-viewH]（与 animateSettle 一致）
                float maxTop = Math.max(0f, g.total() - viewH);
                if (g.total() <= viewH) top = (g.total() - viewH) / 2f;
                else top = Math.max(0f, Math.min(maxTop, top));
            } else {
                // 用户拖动/甩动（合法的大位移，但方向由用户决定）
                top += (rnd.nextInt(2) == 0 ? -1 : 1) * rnd.nextInt(4000);
            }
            // 内部事件（0/1）绝不允许大幅向后传送
            if (event != 2 && topBefore - top > 2500f) {
                check("内部事件传送回顶部 step=" + step + " " + topBefore + "->" + top, false);
                ok = false;
            }
            if (top < -0.5f) { check("top 越界为负", false); ok = false; }
        }
        check("3000 次混合事件，从未传送回第一张", ok);
    }

    static void check(String name, boolean ok) {
        System.out.println((ok ? "[通过] " : "[失败] ") + name);
        if (!ok) failures++;
    }
}
