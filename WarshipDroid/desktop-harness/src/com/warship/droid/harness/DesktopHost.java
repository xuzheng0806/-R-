package com.warship.droid.harness;

import com.warship.droid.core.Host;
import com.warship.droid.core.Rgb;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** Desktop Host: assets from a directory, PNG through ImageIO. */
public final class DesktopHost implements Host {
    final File assets, data;
    public boolean quiet;
    public int logs;

    public DesktopHost(File assets, File data) { this.assets = assets; this.data = data; data.mkdirs(); }

    public byte[] asset(String path) throws IOException { return Files.readAllBytes(new File(assets, path).toPath()); }

    public Rgb decodePng(byte[] bytes) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        if (img == null) throw new IOException("无法解码 PNG");
        return fromImage(img);
    }

    public static Rgb fromImage(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        byte[] out = new byte[w * h * 3];
        int k = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                out[k++] = (byte) (p >> 16); out[k++] = (byte) (p >> 8); out[k++] = (byte) p;
            }
        return Rgb.packed(w, h, out);
    }

    public void savePng(File file, Rgb image) throws IOException {
        BufferedImage img = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.height; y++)
            for (int x = 0; x < image.width; x++) img.setRGB(x, y, image.argb(x, y));
        ImageIO.write(img, "png", file);
    }

    public File dataDir() { return data; }

    public void log(String message) { logs++; if (!quiet) System.out.println("  [脚本] " + message); }
}
