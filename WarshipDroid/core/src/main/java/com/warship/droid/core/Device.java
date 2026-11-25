package com.warship.droid.core;

/** Screen + input of the emulator, in 1280x720 landscape screen coordinates. */
public interface Device {
    Rgb capture() throws Exception;

    void tap(int x, int y) throws Exception;

    void swipe(int x1, int y1, int x2, int y2, int durationMs) throws Exception;

    /** Throws if the game is not in the foreground (controller.ensure_input_ready). */
    void ensureReady() throws Exception;

    void killApp(String pkg) throws Exception;

    void runApp(String pkg) throws Exception;
}
