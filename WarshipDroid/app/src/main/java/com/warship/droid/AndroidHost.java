package com.warship.droid;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.warship.droid.core.Host;
import com.warship.droid.core.Rgb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class AndroidHost implements Host {
    public interface Logger {
        void onLog(String message);
    }

    private final Context ctx;
    private final Logger logger;

    public AndroidHost(Context ctx, Logger logger) {
        this.ctx = ctx.getApplicationContext();
        this.logger = logger;
    }

    @Override
    public byte[] asset(String path) throws IOException {
        InputStream in = ctx.getAssets().open(path);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    @Override
    public Rgb decodePng(byte[] data) throws IOException {
        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
        if (bmp == null) throw new IOException("无法解码 PNG");
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();
        byte[] rgb = new byte[w * h * 3];
        int k = 0;
        for (int p : px) {
            rgb[k++] = (byte) ((p >> 16) & 0xFF);
            rgb[k++] = (byte) ((p >> 8) & 0xFF);
            rgb[k++] = (byte) (p & 0xFF);
        }
        return Rgb.packed(w, h, rgb);
    }

    @Override
    public void savePng(File file, Rgb image) throws IOException {
        int w = image.width, h = image.height;
        int[] px = new int[w * h];
        int k = 0;
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[k++] = image.argb(x, y);
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        FileOutputStream out = new FileOutputStream(file);
        try {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
            bmp.recycle();
        }
    }

    @Override
    public File dataDir() {
        return ctx.getFilesDir();
    }

    @Override
    public void log(String message) {
        if (logger != null) logger.onLog(message);
    }
}
