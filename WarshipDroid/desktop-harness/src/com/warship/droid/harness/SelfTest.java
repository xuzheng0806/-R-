package com.warship.droid.harness;

import com.warship.droid.core.*;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Ports of WarshipPilot tests/test_legacy_tasks.py LegacyEntryTest to the Java VM. */
public final class SelfTest {
    static final String NO_ATTEMPTS = "103|225|424242,107|225|CFCFCF,115|225|CFCFCF,118|225|424242,123|226|CFCFCF,"
            + "110|221|424242,110|216|CFCFCF,115|222|424242,115|229|424242,106|222|424242";
    static int fails;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + name);
        if (!ok) fails++;
    }

    /** 1280x720 grey frame with given 720x1280-canvas points painted (BBGGRR colours). */
    static Rgb frame(String points) {
        byte[] b = new byte[1280 * 720 * 3];
        java.util.Arrays.fill(b, (byte) 128);
        for (String p : points.split(",")) {
            if (p.isEmpty()) continue;
            String[] s = p.split("\\|");
            int x = Integer.parseInt(s[0]), y = Integer.parseInt(s[1]);
            int bb = Integer.parseInt(s[2].substring(0, 2), 16), gg = Integer.parseInt(s[2].substring(2, 4), 16),
                    rr = Integer.parseInt(s[2].substring(4, 6), 16);
            int i = ((719 - x) * 1280 + y) * 3;
            b[i] = (byte) rr; b[i + 1] = (byte) gg; b[i + 2] = (byte) bb;
        }
        return Rgb.packed(1280, 720, b);
    }

    static LegacyVM vm(File assets, String task, Map<String, Object> legacy) throws Exception {
        Map<String, Object> s = new HashMap<String, Object>();
        s.put("runs", 1L);
        if (legacy != null) s.put("legacy", legacy);
        DesktopHost h = new DesktopHost(assets, new File("build/selftest"));
        h.quiet = true;
        return new LegacyVM(h, task, s, new LegacyVM.Callbacks() {
            public void page(String p) {}
            public void progress() {}
            public boolean appControl(int c, String p) { return false; }
        });
    }

    static Object field(Object o, String name) throws Exception {
        Field f = LegacyVM.class.getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }

    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]);
        System.out.println("SelfTest (test_legacy_tasks.py 移植)");
        check("campaign 战役开关=true", Boolean.TRUE.equals(((Map<?, ?>) field(vm(assets, "campaign", null), "config")).get("战役开关")));
        Map<String, Object> sl = new HashMap<String, Object>(); sl.put("战役SL", Boolean.TRUE);
        check("campaign 战役SL 被强制 false", Boolean.FALSE.equals(((Map<?, ?>) field(vm(assets, "campaign", sl), "config")).get("战役SL")));
        for (String t : LegacyVM.ENTRIES.keySet()) check("入口可构建 " + t, field(vm(assets, t, null), "resumeFn") != null);
        try { vm(assets, "sortie_a", null); check("未知任务拒绝", false); } catch (IllegalArgumentException e) { check("未知任务拒绝", true); }

        LegacyVM m = vm(assets, "campaign", null);
        LuaValue attempts = m.G.get(LegacyVM.id("战役剩余次数"));
        m.feed(frame(NO_ATTEMPTS));
        LuaValue r0 = attempts.call();
        check("战役剩余次数=0 (物理画布) 实际=" + r0, r0.isnumber() && r0.toint() == 0);
        m.feed(frame(""));
        LuaValue r1 = attempts.call();
        check("战役剩余次数=未知 实际=" + r1.tojstring(), r1.tojstring().equals("未知"));
        Method sdk = LegacyVM.class.getDeclaredMethod("sdk", int.class, org.luaj.vm2.Varargs.class);
        sdk.setAccessible(true);
        check("画布与 GetScreen 已复位", (Integer) field(m, "cw") == 540 && (Integer) field(m, "chh") == 960
                && !(Boolean) field(m, "physical") && ((LuaValue) sdk.invoke(m, 44, LuaValue.valueOf(0))).toint() == 540);
        for (int c : new int[]{177, 222}) {
            try { sdk.invoke(m, c, LuaValue.valueOf("com.huanmeng.zhanjian2")); check("拒绝重启 " + c, false); }
            catch (java.lang.reflect.InvocationTargetException e) { check("拒绝重启 " + c, e.getCause() instanceof IllegalStateException); }
        }
        // colour primitives on the probe-free synthetic frame
        m.feed(frame("100|200|112233"));
        sdk.invoke(m, 191, LuaValue.varargsOf(new LuaValue[]{LuaValue.ZERO, LuaValue.valueOf(720), LuaValue.valueOf(1280)}));
        LuaValue px = (LuaValue) sdk.invoke(m, 1, LuaValue.varargsOf(LuaValue.valueOf(100), LuaValue.valueOf(200)));
        check("GetPixelColor 返回 BBGGRR 实际=" + px, px.tojstring().equals("112233"));
        org.luaj.vm2.Varargs f = (org.luaj.vm2.Varargs) sdk.invoke(m, 168, LuaValue.varargsOf(new LuaValue[]{
                LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO, LuaValue.ZERO, LuaValue.valueOf("112233"), LuaValue.ZERO, LuaValue.valueOf(1.0)}));
        check("FindColor 全屏定位 实际=" + f.arg1() + "," + f.arg(2), f.arg1().toint() == 100 && f.arg(2).toint() == 200);
        System.out.println(fails == 0 ? "SELFTEST PASS" : "SELFTEST FAIL " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
