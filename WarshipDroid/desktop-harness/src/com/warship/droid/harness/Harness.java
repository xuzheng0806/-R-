package com.warship.droid.harness;

import com.warship.droid.core.*;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;

/**
 * Runs every original task against static PNG frames with a virtual clock and a fake device.
 * Usage: Harness <assetsDir> <png>...   (each task cycles through the given frames)
 * Success = the VM runs, issues waits/screenshots/taps and only stops on the stuck-page guard
 * or completion -- never on "接口尚未适配" or a Lua error.
 */
public final class Harness {
    static final class VirtualClock implements TaskRunner.Clock {
        long now;
        public long nowMs() { return now; }
        public void sleep(long ms) { now += Math.max(1, ms); }
    }

    static final class FakeDevice implements Device {
        final List<Rgb> frames; final VirtualClock clock;
        int captures, taps, swipes, next;
        FakeDevice(List<Rgb> frames, VirtualClock clock) { this.frames = frames; this.clock = clock; }
        public Rgb capture() { captures++; clock.now += 60; return frames.get(next++ % frames.size()); }
        public void tap(int x, int y) { taps++; clock.now += 30; }
        public void swipe(int x1, int y1, int x2, int y2, int d) { swipes++; clock.now += d; }
        public void ensureReady() {}
        public void killApp(String p) {}
        public void runApp(String p) {}
    }

    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]);
        List<Rgb> frames = new ArrayList<Rgb>();
        for (int i = 1; i < args.length; i++) frames.add(DesktopHost.fromImage(ImageIO.read(new File(args[i]))));
        int failures = 0;
        for (String task : LegacyVM.ENTRIES.keySet()) {
            DesktopHost host = new DesktopHost(assets, new File("build/harness-data/" + task));
            host.quiet = true;
            final VirtualClock clock = new VirtualClock();
            FakeDevice dev = new FakeDevice(frames, clock);
            final List<String> pages = new ArrayList<String>();
            final int[] events = {0};
            Map<String, Object> settings = new HashMap<String, Object>();
            settings.put("runs", 1L);
            long t0 = System.nanoTime();
            TaskRunner runner = new TaskRunner(host, dev, clock, new TaskRunner.Listener() {
                public void event(String m) {
                    events[0]++;
                    if (m.startsWith("当前页面：")) { String p = m.substring(5); if (!pages.contains(p)) pages.add(p); }
                }
            }, task, settings);
            TaskRunner.Status st = runner.run();
            long wall = (System.nanoTime() - t0) / 1000000;
            boolean ok = st == TaskRunner.Status.COMPLETED
                    || (st == TaskRunner.Status.FAULT && (runner.error.startsWith("原任务页面长期没有进展")
                    || runner.error.startsWith("同一操作一分钟重复十次") || runner.error.startsWith("原任务未回到母港")));
            if (!ok) failures++;
            System.out.printf("%-10s %-4s status=%s 虚拟=%ds 实耗=%dms 截图=%d 点击=%d 滑动=%d 脚本日志=%d%n",
                    task, ok ? "OK" : "FAIL", st, clock.now / 1000, wall, dev.captures, dev.taps, dev.swipes, host.logs);
            System.out.println("           页面=" + pages);
            if (!runner.error.isEmpty()) System.out.println("           结束原因=" + runner.error);
        }
        System.out.println(failures == 0 ? "HARNESS PASS" : "HARNESS FAIL " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
