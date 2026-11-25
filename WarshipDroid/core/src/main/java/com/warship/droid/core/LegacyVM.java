package com.warship.droid.core;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Java port of WarshipPilot module/legacy/vm.py: runs the original main.lua52.luac
 * through LuaJ and implements the old engine's platform operations.
 */
public final class LegacyVM {
    static final Charset UTF8 = Charset.forName("UTF-8");

    /** Task -> original global entry function. */
    public static final Map<String, String> ENTRIES = new LinkedHashMap<String, String>();
    static {
        ENTRIES.put("expedition", "收远征");
        ENTRIES.put("reward", "收任务");
        ENTRIES.put("campaign", "打战役");
        ENTRIES.put("exercise", "打演习");
        ENTRIES.put("decisive", "决战");
        ENTRIES.put("sortie", "出征战斗");
    }
    static final String[][] DECISIVE_ENDS = {
            {"今天的决战次数打完了", "limit"}, {"票用完了,结束决战", "tickets"},
            {"仓满了,停止决战,转远征", "dock_full"}, {"仓库满了,停止决战,转远征", "dock_full"}};
    static final String RECORD_FILE = "记录文件.txt";
    static final String PKG = "com.huanmeng.zhanjian2";

    /** Callbacks into the controller. */
    public interface Callbacks {
        void page(String page);
        void progress();
        /** cmd 177 KillApp / 222 RunApp; return false to refuse (task stops). */
        boolean appControl(int command, String pkg) throws Exception;
    }

    public static String id(String name) {
        StringBuilder sb = new StringBuilder("id");
        for (byte b : name.getBytes(UTF8)) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public final String task;
    public final Globals G;
    final Host host;
    final Callbacks cb;
    final LuaValue resumeFn;
    final Map<String, Object> config;
    final long started = System.nanoTime();
    final Map<String, double[][]> patternCoords = new HashMap<String, double[][]>();
    final Map<String, String[]> patternColors = new HashMap<String, String[]>();
    final Rgb[] continueTpl, clearTpl;
    final int[][] continueAt = {{390, 190}, {515, 315}}, clearAt = {{276, 254}, {766, 254}};

    Rgb image;
    int cw = 540, chh = 960;
    boolean physical;
    boolean speedNotice;
    public boolean exerciseCompleted;
    public String decisiveEnd;
    /** Set when the original deliberately stops the whole script (LegacyHalt). */
    public volatile String halt;
    public final List<String> snapshots = new ArrayList<String>();
    private int[] searchCols, searchRows; // cache for the current canvas
    private int searchW = -1, searchH = -1;

    public LegacyVM(Host host, String task, Map<String, Object> settings, Callbacks cb) throws Exception {
        if (!ENTRIES.containsKey(task)) throw new IllegalArgumentException("原脚本没有对应的任务入口：" + task);
        this.host = host; this.task = task; this.cb = cb;
        continueTpl = new Rgb[]{tpl("expedition-continue-title.png"), tpl("expedition-continue-question.png")};
        clearTpl = new Rgb[]{tpl("decisive-clear-title.png"), tpl("decisive-abandon-title.png")};

        config = Json.object(new String(host.asset("legacy/task-defaults.json"), UTF8));
        if (settings.get("general_legacy") instanceof Map) config.putAll(map(settings.get("general_legacy")));
        if (settings.get("legacy") instanceof Map) config.putAll(map(settings.get("legacy")));
        for (String k : new String[]{"前方案A", "前方案B", "后方案A", "后方案B", "后方案C", "网络日志", "保持加速",
                "大破出征C", "大破进击C", "战役开关", "演习开关"}) config.put(k, Boolean.FALSE);
        config.put("前方案C", Boolean.TRUE);
        if (task.equals("sortie")) {
            Object runs = settings.get("runs");
            config.put("战斗次数限制", String.valueOf((runs instanceof Number ? ((Number) runs).intValue() : 1) - 1));
        }
        if (task.equals("campaign")) { config.put("战役开关", Boolean.TRUE); config.put("战役SL", Boolean.FALSE); }
        if (task.equals("exercise")) config.put("演习开关", Boolean.TRUE);
        if (task.equals("decisive")) config.put("决战开关", Boolean.TRUE);

        G = JsePlatform.debugGlobals();
        byte[] bc = host.asset("legacy/main.lua52.luac");
        Prototype proto = LoadState.instance.undump(new ByteArrayInputStream(bc), "@main.lua52.luac");
        new LuaClosure(proto, G).call();

        Map<String, Object> defaults = Json.object(new String(host.asset("legacy/global-defaults.json"), UTF8));
        for (Map.Entry<String, Object> e : defaults.entrySet()) G.set(e.getKey(), toLua(e.getValue()));
        Object[][] init = {{"现在方案", "C"}, {"回港", 0L}, {"试用", 1L}, {"心跳时间戳", 0L}, {"渠道服", ""},
                {"点击时间点", -10000L}, {"X暂存", -1L}, {"Y暂存", -1L}, {"有用", 1L}, {"上次养成记录", 0L},
                {"战斗次数C", 0L}, {"快修次数C", 0L}};
        for (Object[] kv : init) G.set(id((String) kv[0]), toLua(kv[1]));
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("养成记录", 0L);
        record.put("日期", new SimpleDateFormat("MMdd", Locale.US).format(new Date()));
        record.put("决战BOSS次数", 0L);
        if (task.equals("decisive")) record.putAll(readRecord());
        G.set(id("TABLE"), toLua(record));

        G.set("host_sdk", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                try {
                    return sdk(a.arg1().toint(), a.subargs(2));
                } catch (LuaError e) {
                    throw e;
                } catch (Exception e) {
                    if (Boolean.getBoolean("wd.debug")) e.printStackTrace();
                    throw new LuaError("host_sdk(" + a.arg1() + ") " + e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        });
        G.set("host_page", new OneArgFunction() {
            public LuaValue call(LuaValue v) { if (!v.isnil()) cb.page(text(v)); return NIL; }
        });
        G.set("host_continue", new ZeroArgFunction() {
            public LuaValue call() { return valueOf(expeditionContinue()); }
        });
        G.set("host_decisive_clear", new ZeroArgFunction() {
            public LuaValue call() { return valueOf(decisiveClear()); }
        });
        G.set("host_split", new TwoArgFunction() {
            public LuaValue call(LuaValue v, LuaValue sep) {
                String s = text(v);
                String[] parts = sep.isnil() ? s.trim().split("\\s+") : s.split(Pattern.quote(text(sep)), -1);
                LuaTable t = new LuaTable();
                for (int i = 0; i < parts.length; i++) t.set(i + 1, valueOf(parts[i]));
                return t;
            }
        });
        G.load("LuaAuxLib = {TableSize=function(t)\n"
                + " if type(t) == 'string' then return #t end\n"
                + " local n=0 for _ in pairs(t) do n=n+1 end return n end,\n"
                + " StringSplit=host_split}\n"
                + "LuaAuxLib.LuaAuxLid = function(cmd, ...)\n"
                + " if cmd == 13 or cmd == 15 or cmd == 65 or cmd == 219 then return coroutine.yield(cmd, ...) end\n"
                + " return host_sdk(cmd, ...)\nend\n", "aux").call();

        LuaValue dt = table("DateTime");
        final String[][] dates = {{"Year", "yyyy"}, {"Month", "MM"}, {"Day", "dd"}, {"Hour", "HH"}, {"Minute", "mm"}, {"Second", "ss"}};
        for (final String[] d : dates)
            dt.set(d[0], new ZeroArgFunction() {
                public LuaValue call() { return valueOf(Integer.parseInt(new SimpleDateFormat(d[1], Locale.US).format(new Date()))); }
            });
        final Random rnd = new Random();
        table("ShanHai").set("RndEx", new TwoArgFunction() {
            public LuaValue call(LuaValue lo, LuaValue hi) {
                int l = (int) num(lo, 0), h = (int) num(hi, 0);
                return valueOf(l + rnd.nextInt(Math.max(1, h - l + 1)));
            }
        });
        LuaValue file = table("File");
        file.set("Read", new OneArgFunction() {
            public LuaValue call(LuaValue name) {
                try { byte[] b = readFile(text(name)); return b == null ? NIL : LuaString.valueOf(b); }
                catch (IOException e) { throw new LuaError(e.getMessage()); }
            }
        });
        file.set("Write", new TwoArgFunction() {
            public LuaValue call(LuaValue name, LuaValue content) {
                try { writeFile(text(name), content.isstring() ? bytes(content) : text(content).getBytes(UTF8)); return TRUE; }
                catch (IOException e) { throw new LuaError(e.getMessage()); }
            }
        });
        LuaValue cjson = table("Cjson");
        cjson.set("Encode", new OneArgFunction() {
            public LuaValue call(LuaValue v) { return valueOf(Json.stringify(fromLua(v))); }
        });
        cjson.set("Decode", new OneArgFunction() {
            public LuaValue call(LuaValue v) { return toLua(Json.parse(text(v))); }
        });

        String cur = id("现在画面"), click = id("固定延时点击");
        G.load("local original = " + cur + "\n" + cur + " = function(...)\n"
                + " if host_continue() then host_page('继续远征确认') " + click + "(198, 368, 700) LuaAuxLib.LuaAuxLid(13, true) end\n"
                + " if host_decisive_clear() then host_page('决战确认通关') " + click + "(284, 300, 700) "
                + click + "(82, 484, 1500) LuaAuxLib.LuaAuxLid(13, true) end\n"
                + " local value = original(...)\n host_page(value)\n return value\nend\n", "page").call();

        G.set("host_physical", new OneArgFunction() {
            public LuaValue call(LuaValue v) { physical = v.toboolean(); return NIL; }
        });
        String camp = id("战役剩余次数");
        G.load("local original = " + camp + "\n" + camp + " = function(...)\n host_physical(true)\n"
                + " local result = table.pack(pcall(original, ...))\n host_physical(false)\n"
                + " if not result[1] then error(result[2], 0) end\n"
                + " return table.unpack(result, 2, result.n)\nend\n", "camp").call();

        LuaValue entry = G.get(id(ENTRIES.get(task)));
        if (task.equals("exercise")) {
            G.set("host_exercise_done", new ZeroArgFunction() {
                public LuaValue call() { exerciseCompleted = true; return NIL; }
            });
            entry = G.load("return function(exercise) return function()\n"
                    + " local eligible = " + id("打演习时间戳") + " == 0 and LuaAuxLib.LuaAuxLid(193, '演习开关') == true\n"
                    + " exercise()\n"
                    + " if eligible and " + id("演习关闭加速") + " == 0 then host_exercise_done() end\n"
                    + "end end", "exercise").call().call(entry);
        }
        if (task.equals("decisive")) {
            G.set("host_decisive_wait", new ZeroArgFunction() {
                public LuaValue call() { cb.progress(); return NIL; }
            });
            G.set(id("出征战斗"), G.load("return function() host_decisive_wait() end", "dw").call());
            entry = G.load("return function(exchange, decisive, collect) return function()\n"
                    + " if LuaAuxLib.LuaAuxLid(193, '决战换票开关') == true then exchange() end\n"
                    + " decisive()\n collect()\nend end", "decisive").call()
                    .call(G.get(id("决战换票")), entry, G.get(id("收远征")));
        }
        if (task.equals("sortie")) {
            entry = G.load("return function(collect, sortie) return function()\n"
                    + " while true do\n  sortie()\n  local waiting = " + id("现在方案") + " == '挂远征'\n"
                    + "  collect()\n  if waiting then return end\n end\nend end", "sortie").call()
                    .call(G.get(id("收远征")), G.get(id("出征战斗")));
        }
        if (!entry.isfunction()) throw new IllegalStateException("字节码缺少入口函数：" + ENTRIES.get(task));
        resumeFn = G.load("return function(f)\n local t = coroutine.create(f)\n"
                + " local function budget() error('Original task exceeded instruction budget') end\n"
                + " return function(...)\n  debug.sethook(t, budget, '', 1000000)\n"
                + "  local result = table.pack(coroutine.resume(t, ...))\n"
                + "  if not result[1] then error(result[2]) end\n"
                + "  return coroutine.status(t), table.unpack(result, 2, result.n)\n end\nend", "wrap").call().call(entry);

        LuaValue os = G.get("os");
        for (String n : new String[]{"execute", "exit", "remove", "rename", "getenv", "tmpname"}) os.set(n, LuaValue.NIL);
        for (String n : new String[]{"io", "package", "require", "dofile", "loadfile", "luajava"}) G.set(n, LuaValue.NIL);
    }

    // ------------------------------------------------------------------ runtime

    public Varargs resume(boolean first) {
        return first ? resumeFn.invoke(LuaValue.NONE) : resumeFn.invoke(LuaValue.valueOf(0));
    }

    public void feed(Rgb frame) {
        if (frame.width != 1280 || frame.height != 720) throw new IllegalArgumentException("原脚本适配当前仅支持1280×720舰R画面");
        image = frame;
    }

    public Rgb image() { return image; }

    /** The VM's millisecond clock (old GetTickCount). */
    public long elapsedMs() { return (System.nanoTime() - started) / 1000000L; }

    boolean templatesMatch(Rgb[] tpls, int[][] at) {
        for (int k = 0; k < tpls.length; k++) {
            if (tpls[k] == null) return false;
            if (image.ncc(at[k][0], at[k][1], tpls[k]) < .96) return false;
        }
        return true;
    }

    boolean near(int x, int y, int r, int g, int b) {
        return Math.abs(image.ch(x, y, 0) - r) < 35 && Math.abs(image.ch(x, y, 1) - g) < 35 && Math.abs(image.ch(x, y, 2) - b) < 35;
    }

    boolean expeditionContinue() {
        return image != null && templatesMatch(continueTpl, continueAt) && near(450, 445, 231, 194, 32);
    }

    boolean decisiveClear() {
        return image != null && task.equals("decisive") && templatesMatch(clearTpl, clearAt) && near(645, 610, 34, 143, 246);
    }

    /** Pixel of canvas point (x, y) as {R,G,B}; vm.py pixel(). */
    int[] pixel(double xd, double yd) {
        int x = (int) Math.rint(xd), y = (int) Math.rint(yd);
        int w = cw, h = chh;
        if (!(0 <= x && x < w && 0 <= y && y < h))
            throw new IllegalArgumentException("原脚本坐标越界：" + x + "," + y + "（画布 " + w + "x" + h + "）");
        int sy = (int) Math.rint((w - 1 - x) * 719.0 / (w - 1));
        int sx = (int) Math.rint(y * 1279.0 / (h - 1));
        return new int[]{image.ch(sx, sy, 0), image.ch(sx, sy, 1), image.ch(sx, sy, 2)};
    }

    boolean compare(double x, double y, String color, double similarity) {
        int[] p = pixel(x, y);
        return LegacyColor.match(p[0], p[1], p[2], LegacyColor.colors(color), LegacyColor.tolerance(similarity));
    }

    boolean points(String expression, double similarity) {
        double[][] xy = patternCoords.get(expression);
        String[] cs = patternColors.get(expression);
        if (xy == null) {
            String[] pts = expression.split(",", -1);
            xy = new double[pts.length][2]; cs = new String[pts.length];
            for (int i = 0; i < pts.length; i++) {
                String[] p = pts[i].split("\\|", 3);
                xy[i][0] = Double.parseDouble(p[0]); xy[i][1] = Double.parseDouble(p[1]); cs[i] = p[2];
            }
            patternCoords.put(expression, xy); patternColors.put(expression, cs);
        }
        for (int i = 0; i < xy.length; i++) if (!compare(xy[i][0], xy[i][1], cs[i], similarity)) return false;
        return true;
    }

    private void searchMaps() {
        if (searchW == cw && searchH == chh) return;
        int w = cw, h = chh;
        searchCols = new int[w]; searchRows = new int[h];
        double cr = 719.0 / (w - 1), rr = 1279.0 / (h - 1);
        for (int x = 0; x < w; x++) searchCols[x] = 719 - (int) Math.rint(x * cr); // screen Y
        for (int y = 0; y < h; y++) searchRows[y] = (int) Math.rint(y * rr);       // screen X
        searchW = w; searchH = h;
    }

    private boolean viewMatch(int x, int y, int[][] alts, int tol) {
        int sx = searchRows[y], sy = searchCols[x];
        return LegacyColor.match(image.ch(sx, sy, 0), image.ch(sx, sy, 1), image.ch(sx, sy, 2), alts, tol);
    }

    /** vm.py find_colors; returns {x, y} or {-1, -1}. */
    int[] findColors(double x1d, double y1d, double x2d, double y2d, String color, String offsets, int direction, double similarity) {
        int w = cw, h = chh;
        int x1 = (int) Math.rint(x1d), y1 = (int) Math.rint(y1d), x2 = (int) Math.rint(x2d), y2 = (int) Math.rint(y2d);
        if (x1 == 0 && y1 == 0 && x2 == 0 && y2 == 0) { x2 = w - 1; y2 = h - 1; }
        if (x1 > x2) { int t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { int t = y1; y1 = y2; y2 = t; }
        x1 = Math.max(0, x1); y1 = Math.max(0, y1); x2 = Math.min(w - 1, x2); y2 = Math.min(h - 1, y2);
        if (x2 < x1 || y2 < y1) return new int[]{-1, -1};
        searchMaps();
        int tol = LegacyColor.tolerance(similarity);
        int[][] alts = LegacyColor.colors(color);
        int[][] off = null; int[][][] offAlts = null;
        if (offsets != null && !offsets.isEmpty() && !offsets.equals("nil")) {
            String[] pts = offsets.split(",", -1);
            off = new int[pts.length][2]; offAlts = new int[pts.length][][];
            for (int i = 0; i < pts.length; i++) {
                String[] p = pts[i].split("\\|", 3);
                off[i][0] = (int) Double.parseDouble(p[0]); off[i][1] = (int) Double.parseDouble(p[1]);
                offAlts[i] = LegacyColor.colors(p[2]);
            }
        }
        double cx = (x1 + x2) / 2.0, cy = (y1 + y2) / 2.0;
        int bx = -1, by = -1;
        for (int y = y1; y <= y2; y++)
            for (int x = x1; x <= x2; x++) {
                if (!viewMatch(x, y, alts, tol)) continue;
                boolean ok = true;
                if (off != null)
                    for (int i = 0; i < off.length && ok; i++) {
                        int xx = x + off[i][0], yy = y + off[i][1];
                        if (xx < 0 || xx >= w || yy < 0 || yy >= h) { ok = false; break; }
                        ok = viewMatch(xx, yy, offAlts[i], tol);
                    }
                if (!ok) continue;
                if (bx < 0 || before(x, y, bx, by, direction, cx, cy)) { bx = x; by = y; }
            }
        return new int[]{bx, by};
    }

    /** color.py _scan_order: is (x,y) ordered before (bx,by)? */
    static boolean before(int x, int y, int bx, int by, int d, double cx, double cy) {
        switch (d) {
            case 0: return y != by ? y < by : x < bx;
            case 1: {
                double a = Math.max(Math.abs(x - cx), Math.abs(y - cy)), b = Math.max(Math.abs(bx - cx), Math.abs(by - cy));
                if (a != b) return a < b;
                return y != by ? y < by : x < bx;
            }
            case 2: return y != by ? y > by : x > bx;
            case 3: return y != by ? y > by : x < bx;
            case 4: return y != by ? y < by : x > bx;
            case 5: return x != bx ? x < bx : y < by;
            case 6: return x != bx ? x > bx : y < by;
            case 7: return x != bx ? x < bx : y > by;
            case 8: return x != bx ? x > bx : y > by;
            default: throw new IllegalArgumentException("未知找色方向：" + d);
        }
    }

    // ------------------------------------------------------------------ host_sdk

    Varargs sdk(int command, Varargs a) throws Exception {
        switch (command) {
            case 10: {
                if (a.narg() > 0 && !a.arg(1).isnil()) {
                    String m = text(a.arg(1));
                    host.log(m.length() > 500 ? m.substring(0, 500) : m);
                    if (task.equals("decisive")) decisiveLog(m);
                }
                return LuaValue.ZERO;
            }
            case 186: return LuaValue.valueOf(snapshot(a.narg() > 0 ? text(a.arg(1)) : ""));
            case 74: {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= a.narg(); i++) {
                    if (a.arg(i).isnil()) continue;
                    if (b.length() > 0) b.append(' ');
                    b.append(text(a.arg(i)));
                }
                String m = b.toString();
                host.log(m.length() > 500 ? m.substring(0, 500) : m);
                return LuaValue.ZERO;
            }
            case 84: return LuaValue.valueOf((double) elapsedMs());
            case 29: return LuaValue.valueOf(180);
            case 44: return LuaValue.valueOf(physical ? (a.narg() > 0 && num(a.arg(1), 0) != 0 ? 1280 : 720) : 540);
            case 191: {
                if (a.narg() >= 3 && !a.arg(2).isnil() && !a.arg(3).isnil()) {
                    int w = (int) num(a.arg(2), 0), h = (int) num(a.arg(3), 0);
                    if (!((w == 540 && h == 960) || (w == 720 && h == 1280)))
                        throw new IllegalStateException("原脚本请求的画布 " + w + "x" + h + " 与 1280×720 画面不符");
                    cw = w; chh = h;
                    return LuaValue.ZERO;
                }
                return LuaValue.valueOf(960);
            }
            case 177: case 222: {
                String pkg = a.narg() > 0 ? text(a.arg(1)) : "";
                if (cb.appControl(command, pkg.isEmpty() ? PKG : pkg)) return LuaValue.ZERO;
                throw new IllegalStateException("原脚本请求结束并重启游戏（" + pkg + "），未开启自动重启，已停止并保留现场");
            }
            case 193: {
                Object v = config.get(text(a.arg(1)));
                return v == null ? LuaValue.valueOf("") : toLua(v);
            }
            case 229: return LuaValue.valueOf(points(text(a.arg(1)), num(a.arg(2), .9)) ? 1 : 0);
            case 2: return LuaValue.valueOf(compare(num(a.arg(1), 0), num(a.arg(2), 0), text(a.arg(3)), num(a.arg(4), .9)) ? 0 : -1);
            case 1: case 119: {
                int[] p = pixel(num(a.arg(1), 0), num(a.arg(2), 0));
                return LuaValue.valueOf(String.format("%02X%02X%02X", p[2], p[1], p[0]));
            }
            case 66: {
                int[] r = findColors(num(a.arg(1), 0), num(a.arg(2), 0), num(a.arg(3), 0), num(a.arg(4), 0), text(a.arg(5)),
                        a.arg(6).isnil() ? "" : text(a.arg(6)), (int) num(a.arg(7), 0), num(a.arg(8), .9));
                return LuaValue.varargsOf(LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1]));
            }
            case 168: {
                int[] r = findColors(num(a.arg(1), 0), num(a.arg(2), 0), num(a.arg(3), 0), num(a.arg(4), 0), text(a.arg(5)),
                        "", (int) num(a.arg(6), 0), num(a.arg(7), .9));
                return LuaValue.varargsOf(LuaValue.valueOf(r[0]), LuaValue.valueOf(r[1]));
            }
            case 138: return LuaValue.valueOf(PKG);
            case 207: return LuaValue.valueOf(text(a.arg(1)).indexOf(text(a.arg(2))) + 1);
            case 94: return LuaValue.valueOf((int) Double.parseDouble(text(a.arg(1)).trim()));
            case 88:
                if (!speedNotice) { host.log("旧引擎变速接口使用游戏原速；原点击、滑动和延时继续执行"); speedNotice = true; }
                return LuaValue.ZERO;
            default: {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= a.narg(); i++) b.append(i > 1 ? ", " : "").append(text(a.arg(i)));
                String s = b.toString();
                throw new IllegalStateException("原脚本运行接口尚未适配：" + command + "，参数：[" + (s.length() > 240 ? s.substring(0, 240) : s) + "]");
            }
        }
    }

    void decisiveLog(String message) {
        for (String[] e : DECISIVE_ENDS) if (message.startsWith(e[0])) decisiveEnd = e[1];
        if (message.startsWith("决战慢修等待")) cb.progress();
        if (message.equals("新船上锁停止")) {
            halt = "决战捞到新船，已按“新船上锁停止”设置上锁并停止，请到船坞查看";
            throw new LuaError(halt);
        }
    }

    int snapshot(String filename) throws IOException {
        String name = new File(filename.replace('\\', '/')).getName();
        if (name.isEmpty()) name = System.currentTimeMillis() + ".png";
        if (!name.toLowerCase(Locale.US).endsWith(".png")) name += ".png";
        File folder = new File(host.dataDir(), "screenshots/decisive");
        folder.mkdirs();
        if (image != null) {
            host.savePng(new File(folder, name), image.copy());
            snapshots.add(name);
            host.log("已保存决战截图：screenshots/decisive/" + name);
        }
        return 1;
    }

    Map<String, Object> readRecord() {
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        try {
            byte[] b = readFile(RECORD_FILE);
            if (b == null) return record;
            Object v = Json.parse(new String(b, UTF8));
            if (!(v instanceof Map)) return record;
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.get("日期") instanceof String) record.put("日期", m.get("日期"));
            for (String k : new String[]{"决战BOSS次数", "养成记录"}) if (m.get(k) instanceof Number) record.put(k, m.get(k));
        } catch (Exception ignored) {
        }
        return record;
    }

    File filePath(String filename) {
        String name = new File(filename.replace('\\', '/')).getName();
        if (name.isEmpty() || Pattern.compile("账号|注册|密码|token", Pattern.CASE_INSENSITIVE).matcher(name).find())
            throw new IllegalArgumentException("不迁移旧平台的账户文件");
        return new File(new File(host.dataDir(), "log/legacy-files"), name);
    }

    byte[] readFile(String filename) throws IOException {
        File f = filePath(filename);
        if (!f.isFile()) return null;
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            return b;
        } finally { in.close(); }
    }

    void writeFile(String filename, byte[] content) throws IOException {
        File f = filePath(filename);
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(content); } finally { out.close(); }
    }

    // ------------------------------------------------------------------ helpers

    private Rgb tpl(String name) {
        try { return host.decodePng(host.asset("game/" + name)); } catch (Exception e) { return null; }
    }

    private LuaValue table(String name) {
        LuaValue t = G.get(name);
        if (!t.istable()) { t = new LuaTable(); G.set(name, t); }
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return (Map<String, Object>) o; }

    static String text(LuaValue v) {
        if (v.isnil()) return "None";
        if (v.type() == LuaValue.TNUMBER) {
            double d = v.todouble();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return Double.toString(d);
        }
        return v.tojstring();
    }

    static byte[] bytes(LuaValue v) {
        LuaString s = v.checkstring();
        byte[] b = new byte[s.m_length];
        System.arraycopy(s.m_bytes, s.m_offset, b, 0, s.m_length);
        return b;
    }

    static double num(LuaValue v, double def) {
        if (v.isnil()) return def;
        if (v.type() == LuaValue.TNUMBER) return v.todouble();
        if (v.type() == LuaValue.TBOOLEAN) return v.toboolean() ? 1 : 0;
        return Double.parseDouble(v.tojstring().trim());
    }

    public static LuaValue toLua(Object o) {
        if (o == null) return LuaValue.NIL;
        if (o instanceof String) return LuaValue.valueOf((String) o);
        if (o instanceof Boolean) return LuaValue.valueOf((Boolean) o);
        if (o instanceof Double || o instanceof Float) return LuaValue.valueOf(((Number) o).doubleValue());
        if (o instanceof Number) {
            long l = ((Number) o).longValue();
            return l == (int) l ? LuaValue.valueOf((int) l) : LuaValue.valueOf((double) l);
        }
        if (o instanceof Map) {
            LuaTable t = new LuaTable();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) t.set(String.valueOf(e.getKey()), toLua(e.getValue()));
            return t;
        }
        if (o instanceof List) {
            LuaTable t = new LuaTable();
            int i = 1;
            for (Object v : (List<?>) o) t.set(i++, toLua(v));
            return t;
        }
        return LuaValue.valueOf(o.toString());
    }

    public static Object fromLua(LuaValue v) {
        switch (v.type()) {
            case LuaValue.TNIL: return null;
            case LuaValue.TBOOLEAN: return v.toboolean();
            case LuaValue.TNUMBER: {
                double d = v.todouble();
                return d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : (Object) d;
            }
            case LuaValue.TTABLE: {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = v.next(k);
                    k = n.arg1();
                    if (k.isnil()) break;
                    m.put(text(k), fromLua(n.arg(2)));
                }
                return m;
            }
            default: return v.tojstring();
        }
    }
}
