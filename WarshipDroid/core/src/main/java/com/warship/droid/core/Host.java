package com.warship.droid.core;

import java.io.File;
import java.io.IOException;

/** Platform services the VM needs (Android or desktop). */
public interface Host {
    /** Bytes of an asset, path relative to the assets root, e.g. "legacy/main.lua52.luac". */
    byte[] asset(String path) throws IOException;

    Rgb decodePng(byte[] data) throws IOException;

    void savePng(File file, Rgb image) throws IOException;

    /** Writable data folder (Android: filesDir). */
    File dataDir();

    void log(String message);
}
