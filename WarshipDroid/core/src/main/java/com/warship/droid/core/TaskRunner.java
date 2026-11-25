package com.warship.droid.core;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.*;

/**
 * Java port of controller.py TaskController.step/input/complete for one original task.
 * Blocking: call {@link #run()} on a worker thread; {@link #stop()} from anywhere.
 */
public final class TaskRunner implements LegacyVM.Callbacks {
    public interface Clock {
        long nowMs();
        void sleep(long ms) throws InterruptedException;
    }

    public interface Listener {
        void event(String message);
    }

    public static final Clock REAL = new Clock() {
        public long nowMs() { return System.nanoTime() / 1000000L; }
        public void sleep(long ms) throws InterruptedException { Thread.sleep(ms); }
    };

    public enum Status { RUNNING, COMPLETED, STOPPED, FAULT, HALTED }

    static final long BURST = 400;

    final Device device;
    final Clock clock;
    final Listener listener;
    final Map<String, Object> settings;
    final boolean allowRestart;
    public final LegacyVM vm;

    volatile boolean stopRequested;
    long delayUntil, pageTime;
    boolean firstResume = true, needsFrame = true;
    int[] touchStart, touchEnd; int touchDuration;
    final ArrayDeque<Object[]> clicks = new ArrayDeque<Object[]>();

    public volatile Status status = Status.RUNNING;
    public volatile String page = "", action = "", error = "";
    public volatile int inputs, battles;
    /** State left by the original for scheduling (legacy_next_run). */
    public final Map<String, Object> resultState = new LinkedHashMap<String, Object>();

    public TaskRunner(Host host, Device device, Clock clock, Listener listener, String task,
                      Map<String, Object> settings) throws Exception {
        this.device = device; this.clock = clock; this.listener = listener; this.settings = settings;
        this.allowRestart = Boolean.TRUE.equals(settings.get("allow_restart"));
        this.vm = new LegacyVM(host, task, settings, this);
        this.pageTime = clock.nowMs();
    }

    public void stop() { stopRequested = true; }

    void event(String m) { listener.event(m); }

    // ---------------------------------------------------------------- callbacks
    public void page(String value) {
        if (!value.equals(page)) {
            page = value;
            pageTime = clock.nowMs();
            event("当前页面：" + value);
        }
    }

    public void progress() { pageTime = clock.nowMs(); }

    public boolean appControl(int command, String pkg) throws Exception {
        if (!allowRestart) return false;
        if (command == 177) { event("原脚本结束游戏：" + pkg); device.killApp(pkg); }
        else { event("原脚本启动游戏：" + pkg); device.runApp(pkg); }
        return true;
    }

    // ---------------------------------------------------------------- main loop
    public Status run() {
        event("开始：" + vm.task + "；使用恢复的原Lua函数");
        try {
            while (status == Status.RUNNING) {
                if (stopRequested) { status = Status.STOPPED; action = "任务已停止"; break; }
                long now = clock.nowMs();
                if (now < delayUntil) { clock.sleep(Math.min(delayUntil - now, 200)); checkStuck(); continue; }
                if (needsFrame) { vm.feed(device.capture()); needsFrame = false; }
                step();
                checkStuck();
            }
        } catch (InterruptedException e) {
            status = Status.STOPPED; action = "任务被中断";
        } catch (Throwable e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            if (vm.halt != null) { status = Status.HALTED; error = vm.halt; }
            else { status = Status.FAULT; error = msg.length() > 700 ? msg.substring(0, 700) : msg; }
            action = "已停止输入";
        }
        event("结束：" + status + (error.isEmpty() ? "" : "，" + error) + (action.isEmpty() ? "" : "（" + action + "）"));
        return status;
    }

    void checkStuck() {
        if (status != Status.RUNNING) return;
        if (vm.task.equals("sortie")) battles = (int) LegacyVM.num(vm.G.get(LegacyVM.id("战斗次数C")), 0);
        else if (vm.task.equals("decisive")) battles = (int) LegacyVM.num(vm.G.get(LegacyVM.id("TABLE")).get("决战BOSS次数"), 0);
        long limit = page.contains("战斗") ? 360000 : 150000;
        if (clock.nowMs() - pageTime > limit) throw new IllegalStateException("原任务页面长期没有进展：" + page);
    }

    static int[] screenPoint(LuaValue xv, LuaValue yv) {
        double x = LegacyVM.num(xv, 0), y = LegacyVM.num(yv, 0);
        if (!(0 <= x && x < 540 && 0 <= y && y < 960)) throw new IllegalArgumentException("原函数输入坐标越界：" + x + "," + y);
        return new int[]{(int) Math.rint(y * 1279 / 959), (int) Math.rint((539 - x) * 719 / 539)};
    }

    void input(String name, int[] c) throws Exception {
        device.ensureReady();
        long stamp = clock.nowMs();
        StringBuilder k = new StringBuilder(name);
        for (int v : c) k.append(',').append(Math.floorDiv(v, 20));
        String key = k.toString();
        Object[] last = clicks.peekLast();
        if (last != null && last[1].equals(key) && stamp - (Long) last[0] < BURST) {
            last[0] = stamp;
        } else {
            int same = 0;
            for (Object[] e : clicks) if (e[1].equals(key) && stamp - (Long) e[0] < 60000) same++;
            if (same >= 10) throw new IllegalStateException("同一操作一分钟重复十次，已停止并保存现场");
            clicks.addLast(new Object[]{stamp, key});
            while (clicks.size() > 50) clicks.removeFirst();
        }
        if (name.equals("tap")) device.tap(c[0], c[1]);
        else device.swipe(c[0], c[1], c[2], c[3], c[4]);
        inputs++;
        StringBuilder a = new StringBuilder(name).append(' ');
        for (int i = 0; i < c.length; i++) a.append(i > 0 ? "," : "").append(c[i]);
        action = a.toString();
        event(action);
    }

    void step() throws Exception {
        int taps = 0;
        for (int n = 0; n < 30; n++) {
            Varargs r = vm.resume(firstResume);
            firstResume = false;
            String st = r.arg1().tojstring();
            if (st.equals("dead")) { complete(); return; }
            int command = r.arg(2).toint();
            Varargs args = r.subargs(3);
            switch (command) {
                case 15: {
                    double ms = Math.max(0, LegacyVM.num(args.arg(1), 0));
                    delayUntil = clock.nowMs() + (long) ms;
                    action = "原函数等待 " + (long) ms + " ms";
                    return;
                }
                case 13:
                    needsFrame = true;
                    action = "原函数请求新截图";
                    return;
                case 65:
                    input("tap", screenPoint(args.arg(1), args.arg(2)));
                    if (++taps >= 4) return;
                    continue;
                case 219: {
                    int phase = args.arg(1).toint();
                    if (phase == 0) { touchStart = screenPoint(args.arg(2), args.arg(3)); touchEnd = null; }
                    else if (phase == 1) {
                        if (touchStart == null) throw new IllegalStateException("原滑动缺少按下事件");
                        touchEnd = screenPoint(args.arg(2), args.arg(3));
                        touchDuration = Math.max(50, Math.min(3000, (int) LegacyVM.num(args.arg(5), 0)));
                    } else if (phase == 2) {
                        if (touchStart == null || touchEnd == null) throw new IllegalStateException("原滑动缺少移动事件");
                        input("swipe", new int[]{touchStart[0], touchStart[1], touchEnd[0], touchEnd[1], touchDuration});
                        touchStart = touchEnd = null;
                    }
                    continue;
                }
                default:
                    throw new IllegalStateException("未知原函数异步事件：" + command);
            }
        }
    }

    void complete() {
        String task = vm.task;
        if (task.equals("sortie")) battles = (int) LegacyVM.num(vm.G.get(LegacyVM.id("战斗次数C")), 0);
        if (!page.equals("港区")) throw new IllegalStateException("原任务未回到母港，不能标记完成");
        Object runs = settings.get("runs");
        int want = runs instanceof Number ? ((Number) runs).intValue() : 1;
        if (task.equals("sortie") && battles < want) throw new IllegalStateException("原函数已返回，但尚未达到出征次数，保留现场供核对");
        if (task.equals("exercise") && !vm.exerciseCompleted) throw new IllegalStateException("原演习提前返回，尚未确认两页对手已打完，保留现场供核对");
        if (task.equals("decisive")) battles = (int) LegacyVM.num(vm.G.get(LegacyVM.id("TABLE")).get("决战BOSS次数"), 0);
        for (String k : new String[]{"打战役时间戳", "战役泡澡时间戳"}) resultState.put(k, LegacyVM.num(vm.G.get(LegacyVM.id(k)), 0));
        if (task.equals("exercise")) resultState.put("演习完成", vm.exerciseCompleted);
        if (task.equals("decisive")) resultState.put("决战结束", vm.decisiveEnd);
        resultState.put("elapsed_ms", vm.elapsedMs());
        status = Status.COMPLETED;
        action = "原流程已回港，本轮完成";
    }
}
