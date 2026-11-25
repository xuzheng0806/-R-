package com.warship.droid.core;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Colour semantics of module/legacy/vm.py (NOT color.py). */
final class LegacyColor {
    private static final Pattern HEX6 = Pattern.compile("[0-9a-fA-F]{6}");
    private static final Map<String, int[][]> CACHE = new HashMap<String, int[][]>();

    private LegacyColor() {}

    /** Alternatives; each row = {R,G,B, dR,dG,dB}. Colour strings are BBGGRR. */
    static synchronized int[][] colors(String value) {
        int[][] cached = CACHE.get(value);
        if (cached != null) return cached;
        String[] alts = value.split("\\|", -1);
        int[][] out = new int[alts.length][];
        for (int k = 0; k < alts.length; k++) {
            String[] pieces = alts[k].split("-", -1);
            if (!HEX6.matcher(pieces[0]).matches())
                throw new IllegalArgumentException("原脚本颜色表达式需要核对：" + alts[k]);
            int[] row = new int[6];
            fill(row, 0, pieces[0]);
            if (pieces.length > 1) fill(row, 3, pieces[1]);
            out[k] = row;
        }
        CACHE.put(value, out);
        return out;
    }

    private static void fill(int[] row, int at, String hex) {
        // for i in (4, 2, 0): RGB order out of BBGGRR
        row[at] = Integer.parseInt(hex.substring(4, 6), 16);
        row[at + 1] = Integer.parseInt(hex.substring(2, 4), 16);
        row[at + 2] = Integer.parseInt(hex.substring(0, 2), 16);
    }

    /** round((1 - similarity) * 255) + 2, Python round = half-even. */
    static int tolerance(double similarity) {
        return (int) Math.rint((1 - similarity) * 255) + 2;
    }

    static boolean match(int r, int g, int b, int[][] alts, int tol) {
        for (int[] a : alts) {
            if (Math.abs(r - a[0]) <= Math.max(a[3], tol)
                    && Math.abs(g - a[1]) <= Math.max(a[4], tol)
                    && Math.abs(b - a[2]) <= Math.max(a[5], tol)) return true;
        }
        return false;
    }
}
