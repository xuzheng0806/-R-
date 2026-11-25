package com.warship.droid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.warship.droid.core.LegacyVM;
import com.warship.droid.core.TaskRunner;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private Spinner taskSpinner;
    private CheckBox flipBox;
    private CheckBox restartBox;
    private Button startBtn;
    private Button stopBtn;
    private TextView logView;
    private ScrollView scroll;

    private final SuDevice device = new SuDevice();
    private volatile TaskRunner runner;
    private Thread worker;
    private final List<String> logs = new ArrayList<String>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = 24;
        root.setPadding(pad, pad, pad, pad);

        taskSpinner = new Spinner(this);
        List<String> tasks = new ArrayList<String>(LegacyVM.ENTRIES.keySet());
        taskSpinner.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, tasks));
        root.addView(taskSpinner);

        flipBox = new CheckBox(this);
        flipBox.setText("旋转 180° (ROTATION_270)");
        root.addView(flipBox);

        restartBox = new CheckBox(this);
        restartBox.setText("允许 KillApp/RunApp 自动重启游戏");
        root.addView(restartBox);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        startBtn = new Button(this);
        startBtn.setText("启动任务");
        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setEnabled(false);
        row.addView(startBtn);
        row.addView(stopBtn);
        root.addView(row);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(12);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        startBtn.setOnClickListener(v -> startTask());
        stopBtn.setOnClickListener(v -> {
            TaskRunner r = runner;
            if (r != null) r.stop();
        });
    }

    private void startTask() {
        if (worker != null && worker.isAlive()) return;
        final String task = (String) taskSpinner.getSelectedItem();
        device.setFlip180(flipBox.isChecked());
        final Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("runs", 1L);
        settings.put("allow_restart", restartBox.isChecked());

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        appendLog("准备启动：" + task);

        worker = new Thread(() -> {
            try {
                AndroidHost host = new AndroidHost(MainActivity.this, this::appendLog);
                TaskRunner r = new TaskRunner(host, device, TaskRunner.REAL, this::appendLog, task, settings);
                runner = r;
                r.run();
            } catch (Throwable e) {
                appendLog("启动失败：" + e.getMessage());
            } finally {
                runner = null;
                runOnUiThread(() -> {
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                });
            }
        }, "warship-worker");
        worker.start();
    }

    private void appendLog(final String msg) {
        final String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + msg;
        runOnUiThread(() -> {
            logs.add(line);
            if (logs.size() > 200) logs.remove(0);
            StringBuilder sb = new StringBuilder();
            for (String s : logs) sb.append(s).append('\n');
            logView.setText(sb.toString());
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        TaskRunner r = runner;
        if (r != null) r.stop();
        device.close();
        super.onDestroy();
    }
}
