package com.deepwork.rpgmvviewer;

import java.util.Locale;

/**
 * 自然排序（Windows 资源管理器风格）：连续数字段按数值比较，其余字符忽略大小写逐字符比较。
 * 例如 img34 &lt; img223（223 数值更大，不会因为首位 '2'&lt;'3' 而排前）。
 * 纯 Java 实现，可在桌面单元测试（见 tools/src/NaturalOrderTest.java）。
 */
public final class NaturalOrder {

    private NaturalOrder() {}

    public static int compare(String a, String b) {
        String x = a.toLowerCase(Locale.US);
        String y = b.toLowerCase(Locale.US);
        int ia = 0, ib = 0, la = x.length(), lb = y.length();
        while (ia < la && ib < lb) {
            char ca = x.charAt(ia), cb = y.charAt(ib);
            boolean da = ca >= '0' && ca <= '9';
            boolean db = cb >= '0' && cb <= '9';
            if (da && db) {
                int ja = ia, jb = ib;
                while (ja < la && x.charAt(ja) >= '0' && x.charAt(ja) <= '9') ja++;
                while (jb < lb && y.charAt(jb) >= '0' && y.charAt(jb) <= '9') jb++;
                String na = x.substring(ia, ja), nb = y.substring(ib, jb);
                String ta = strip0(na), tb = strip0(nb);
                if (ta.length() != tb.length()) return ta.length() - tb.length();
                int c = ta.compareTo(tb);
                if (c != 0) return c;
                // 数值相同但位数不同（前导零）：短的原串在前，保证稳定
                c = Integer.compare(na.length(), nb.length());
                if (c != 0) return c;
                ia = ja;
                ib = jb;
            } else if (ca != cb) {
                return ca - cb;
            } else {
                ia++;
                ib++;
            }
        }
        return (la - ia) - (lb - ib);
    }

    private static String strip0(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') i++;
        return s.substring(i);
    }
}
