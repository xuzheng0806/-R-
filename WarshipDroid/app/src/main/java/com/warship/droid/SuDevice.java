package com.warship.droid;

import com.warship.droid.core.Device;
import com.warship.droid.core.Rgb;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Dual-pipe persistent root device:
 * - suCap: raw binary screencap stream (auto-detects 12 or 16 byte header).
 * - suInput: text commands (input tap/swipe, dumpsys focus check, am/monkey).
 */
public final class SuDevice implements Device {
    private static final int W = 1280;
    private static final int H = 720;
    private static final int PIXEL_BYTES = W * H * 4;
    private static final String GAME_PKG = "com.huanmeng.zhanjian2";

    private Process capProc;
    private BufferedInputStream capIn;
    private OutputStream capOut;
    private int headerLen = -1;
    private byte[] frameBuf;

    private Process inProc;
    private BufferedReader inReader;
    private OutputStream inOut;

    private volatile boolean flip180;

    public void setFlip180(boolean flip) {
        this.flip180 = flip;
    }

    private synchronized void ensureCap() throws IOException {
        if (capProc != null) {
            try {
                capProc.exitValue();
                closeCap();
            } catch (IllegalThreadStateException alive) {
                return;
            }
        }
        capProc = new ProcessBuilder("su").start();
        capIn = new BufferedInputStream(capProc.getInputStream(), 1 << 20);
        capOut = capProc.getOutputStream();
    }

    private synchronized void ensureIn() throws IOException {
        if (inProc != null) {
            try {
                inProc.exitValue();
                closeIn();
            } catch (IllegalThreadStateException alive) {
                return;
            }
        }
        inProc = new ProcessBuilder("su").redirectErrorStream(true).start();
        inReader = new BufferedReader(new InputStreamReader(inProc.getInputStream(), StandardCharsets.UTF_8));
        inOut = inProc.getOutputStream();
    }

    @Override
    public synchronized Rgb capture() throws Exception {
        ensureCap();
        if (headerLen < 0) {
            // Probe first frame with a marker to determine 12 vs 16 byte header.
            capOut.write("screencap; echo __CAP_END__\n".getBytes(StandardCharsets.US_ASCII));
            capOut.flush();
            byte[] marker = "__CAP_END__\n".getBytes(StandardCharsets.US_ASCII);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(PIXEL_BYTES + 32);
            byte[] chunk = new byte[65536];
            while (true) {
                int n = capIn.read(chunk);
                if (n < 0) throw new IOException("suCap 意外关闭");
                baos.write(chunk, 0, n);
                byte[] cur = baos.toByteArray();
                int idx = indexOf(cur, marker);
                if (idx >= 0) {
                    int hdr = idx - PIXEL_BYTES;
                    if (hdr != 12 && hdr != 16) {
                        throw new IOException("未知 screencap 头长度: " + hdr + " (总长=" + idx + ")");
                    }
                    int w = le32(cur, 0), h = le32(cur, 4), fmt = le32(cur, 8);
                    if (w != W || h != H || fmt != 1) {
                        throw new IOException("屏幕格式不符: " + w + "x" + h + " fmt=" + fmt);
                    }
                    headerLen = hdr;
                    frameBuf = new byte[headerLen + PIXEL_BYTES];
                    System.arraycopy(cur, 0, frameBuf, 0, frameBuf.length);
                    return new Rgb(W, H, frameBuf, headerLen, 4, false, flip180);
                }
                if (baos.size() > PIXEL_BYTES + 4096) {
                    throw new IOException("screencap 探测超长，未找到结束标记");
                }
            }
        }

        capOut.write("screencap\n".getBytes(StandardCharsets.US_ASCII));
        capOut.flush();
        int read = 0;
        while (read < frameBuf.length) {
            int n = capIn.read(frameBuf, read, frameBuf.length - read);
            if (n < 0) {
                closeCap();
                throw new IOException("suCap 读取中断");
            }
            read += n;
        }
        return new Rgb(W, H, frameBuf, headerLen, 4, false, flip180);
    }

    private synchronized String execInput(String cmd) throws IOException {
        ensureIn();
        String full = cmd + "; echo __IN_OK__\n";
        inOut.write(full.getBytes(StandardCharsets.UTF_8));
        inOut.flush();
        StringBuilder sb = new StringBuilder();
        while (true) {
            String line = inReader.readLine();
            if (line == null) {
                closeIn();
                throw new IOException("suInput 意外关闭");
            }
            if (line.equals("__IN_OK__")) return sb.toString();
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
    }

    @Override
    public void tap(int x, int y) throws Exception {
        if (flip180) { x = W - 1 - x; y = H - 1 - y; }
        execInput("input tap " + x + " " + y);
    }

    @Override
    public void swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception {
        if (flip180) {
            x1 = W - 1 - x1; y1 = H - 1 - y1;
            x2 = W - 1 - x2; y2 = H - 1 - y2;
        }
        execInput("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    @Override
    public void ensureReady() throws Exception {
        String out = execInput("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'");
        if (!out.contains(GAME_PKG)) {
            throw new IllegalStateException("游戏不在前台，已停止输入");
        }
    }

    @Override
    public void killApp(String pkg) throws Exception {
        execInput("am force-stop " + pkg);
    }

    @Override
    public void runApp(String pkg) throws Exception {
        execInput("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
    }

    public synchronized void close() {
        closeCap();
        closeIn();
    }

    private void closeCap() {
        if (capProc != null) { capProc.destroy(); capProc = null; }
        capIn = null; capOut = null;
    }

    private void closeIn() {
        if (inProc != null) { inProc.destroy(); inProc = null; }
        inReader = null; inOut = null;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = Math.max(0, PIXEL_BYTES); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
