package com.warship.droid.core;

/**
 * Read-only RGB view over a byte buffer. Supports packed RGB (stride 3) and the raw
 * screencap RGBA/BGRA stream (stride 4, with header offset) without copying.
 */
public final class Rgb {
    public final int width, height;
    final byte[] data;
    final int offset, stride;
    final boolean bgr, flip;

    public Rgb(int width, int height, byte[] data, int offset, int stride, boolean bgr, boolean flip) {
        this.width = width; this.height = height; this.data = data;
        this.offset = offset; this.stride = stride; this.bgr = bgr; this.flip = flip;
    }

    public static Rgb packed(int width, int height, byte[] rgb) {
        return new Rgb(width, height, rgb, 0, 3, false, false);
    }

    /** Channel c (0=R, 1=G, 2=B) of screen pixel (x, y). */
    public int ch(int x, int y, int c) {
        if (flip) { x = width - 1 - x; y = height - 1 - y; }
        int i = offset + (y * width + x) * stride;
        return data[i + (bgr ? 2 - c : c)] & 0xFF;
    }

    public int argb(int x, int y) {
        return 0xFF000000 | (ch(x, y, 0) << 16) | (ch(x, y, 1) << 8) | ch(x, y, 2);
    }

    /** Independent packed copy (for snapshots that outlive the capture buffer). */
    public Rgb copy() {
        byte[] out = new byte[width * height * 3];
        int k = 0;
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++) {
                out[k++] = (byte) ch(x, y, 0); out[k++] = (byte) ch(x, y, 1); out[k++] = (byte) ch(x, y, 2);
            }
        return packed(width, height, out);
    }

    /** cv2.TM_CCOEFF_NORMED of template t placed at (x, y), summed over 3 channels. */
    public double ncc(int x, int y, Rgb t) {
        if (x < 0 || y < 0 || x + t.width > width || y + t.height > height) return -1;
        int n = t.width * t.height;
        double[] mi = new double[3], mt = new double[3];
        for (int j = 0; j < t.height; j++)
            for (int i = 0; i < t.width; i++)
                for (int c = 0; c < 3; c++) { mi[c] += ch(x + i, y + j, c); mt[c] += t.ch(i, j, c); }
        for (int c = 0; c < 3; c++) { mi[c] /= n; mt[c] /= n; }
        double num = 0, si = 0, st = 0;
        for (int j = 0; j < t.height; j++)
            for (int i = 0; i < t.width; i++)
                for (int c = 0; c < 3; c++) {
                    double a = ch(x + i, y + j, c) - mi[c], b = t.ch(i, j, c) - mt[c];
                    num += a * b; si += a * a; st += b * b;
                }
        double d = Math.sqrt(si * st);
        return d == 0 ? (si == st ? 1 : 0) : num / d;
    }
}
