package com.codearts.gateway.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.codearts.gateway.R;
import com.codearts.gateway.core.Store;
import com.codearts.gateway.core.ToolSelfTest;
import com.codearts.gateway.service.FloatingBallService;
import com.codearts.gateway.service.GatewayService;
import com.google.android.material.materialswitch.MaterialSwitch;

/** 设置页：端口 / 局域网 / 自启 / 悬浮球保活 / CPU 保活。 */
public class SettingsActivity extends AppCompatActivity {

    private Store store;
    private EditText etPort;
    private MaterialSwitch cbLan;
    private MaterialSwitch cbAutostart;
    private MaterialSwitch cbFloat;
    private MaterialSwitch cbWakelock;
    private MaterialSwitch cbNotify;
    private MaterialSwitch cbTrimSystem;
    private MaterialSwitch cbStreamThinking;
    private MaterialSwitch cbDropToolProtocol;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = Store.get(this);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etPort = findViewById(R.id.et_port);
        cbLan = findViewById(R.id.cb_lan);
        cbAutostart = findViewById(R.id.cb_autostart);
        cbFloat = findViewById(R.id.cb_float);
        cbWakelock = findViewById(R.id.cb_wakelock);
        cbNotify = findViewById(R.id.cb_notify);
        cbTrimSystem = findViewById(R.id.cb_trim_system);
        cbStreamThinking = findViewById(R.id.cb_stream_thinking);
        cbDropToolProtocol = findViewById(R.id.cb_drop_tool_protocol);

        etPort.setText(String.valueOf(store.getPort()));
        cbLan.setChecked(store.isLanEnabled());
        cbAutostart.setChecked(store.isAutostart());
        cbFloat.setChecked(store.isFloatBall());
        cbWakelock.setChecked(store.isWakeLock());
        cbNotify.setChecked(store.isNotifyGuard());
        cbTrimSystem.setChecked(store.isTrimSystem());
        cbStreamThinking.setChecked(store.isStreamThinking());
        cbDropToolProtocol.setChecked(store.isDropToolProtocol());
        findViewById(R.id.btn_notify_settings).setOnClickListener(v -> {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, "请到系统设置→通知 中配置", Toast.LENGTH_SHORT).show();
            }
        });

        // 悬浮球需要「显示在其他应用上层」权限
        cbFloat.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "需要「显示在其他应用上层」权限，请在接下来的页面允许后回来重新保存",
                        Toast.LENGTH_LONG).show();
                requestOverlayPermission();
                b.setChecked(false);
            }
        });

        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        findViewById(R.id.btn_tool).setOnClickListener(v -> showToolDialog());
        findViewById(R.id.btn_selftest).setOnClickListener(v -> runSelfTest());
    }

    /** 工具链一键自检：每环真实报错直接弹窗 + 落盘 codearts-selftest.json。 */
    private void runSelfTest() {
        Toast.makeText(this, "正在自检…（连接上游与 MCP，约几秒）", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final ToolSelfTest.Report r = ToolSelfTest.run(store);
            runOnUiThread(() -> {
                StringBuilder sb = new StringBuilder();
                sb.append(r.allOk ? "✅ 全部通过\n" : "❌ 有失败项\n");
                for (ToolSelfTest.Item it : r.items) {
                    sb.append(it.ok ? "  ✓ " : "  ✗ ").append(it.name).append('\n');
                    if (!it.detail.isEmpty()) {
                        sb.append("      ").append(it.detail.replace("\n", "\n      ")).append('\n');
                    }
                }
                sb.append('\n').append(r.fixHint);
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("工具链自检（" + r.ts + " " + r.build + "）")
                        .setMessage(sb.toString())
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }, "selftest").start();
    }

    /** 工具调用 / MCP 设置：工具上游 + 任意第三方 MCP 服务器。 */
    private void showToolDialog() {
        final float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * dp);
        box.setPadding(pad, pad / 2, pad, 0);

        final EditText etBase = new EditText(this);
        etBase.setHint(R.string.tool_hint_base);
        // 默认给一个可用地址，用户只需贴 Key 即可（留空=关闭工具路径）
        String base0 = store.getToolBase();
        if (base0 == null || base0.trim().isEmpty()) {
            base0 = "http://127.0.0.1:3065/v1";
        }
        etBase.setText(base0);
        etBase.setTextSize(12);
        box.addView(etBase);

        final EditText etKey = new EditText(this);
        etKey.setHint(R.string.tool_hint_key);
        etKey.setText(store.getToolKey());
        etKey.setTextSize(12);
        box.addView(etKey);

        final EditText etModel = new EditText(this);
        etModel.setHint(R.string.tool_hint_model);
        etModel.setText(store.getToolModel());
        etModel.setTextSize(12);
        box.addView(etModel);

        final EditText etMcp = new EditText(this);
        etMcp.setHint(R.string.tool_hint_mcp);
        etMcp.setText(store.getMcpServers());
        etMcp.setTextSize(11);
        etMcp.setMinLines(2);
        box.addView(etMcp);

        final MaterialSwitch swEnable = new MaterialSwitch(this);
        swEnable.setText(R.string.tool_hint_enable);
        swEnable.setTextSize(12);
        swEnable.setChecked(store.isMcpEnabled());
        box.addView(swEnable);

        final MaterialSwitch swMutate = new MaterialSwitch(this);
        swMutate.setText(R.string.tool_hint_mutating);
        swMutate.setTextSize(12);
        swMutate.setChecked(store.isAllowMutating());
        box.addView(swMutate);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.tool_dialog_title)
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String mcp = etMcp.getText() == null ? "" : etMcp.getText().toString().trim();
                    if (!mcp.isEmpty() && !mcp.startsWith("[")) {
                        Toast.makeText(this, "MCP 配置需是 JSON 数组，已忽略本次修改", Toast.LENGTH_LONG).show();
                    } else {
                        store.setMcpServers(mcp.isEmpty() ? Store.DEFAULT_MCP_SERVERS : mcp);
                    }
                    store.setToolBase(etBase.getText() == null ? "" : etBase.getText().toString());
                    store.setToolKey(etKey.getText() == null ? "" : etKey.getText().toString());
                    store.setToolModel(etModel.getText() == null ? "" : etModel.getText().toString());
                    store.setMcpEnabled(swEnable.isChecked());
                    store.setAllowMutating(swMutate.isChecked());
                    Toast.makeText(this, R.string.tool_saved, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 跳转本应用的悬浮窗授权页。 */
    private void requestOverlayPermission() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (Throwable t2) {
                Toast.makeText(this, "请到 系统设置→应用→CodeArts网关→权限 手动授予悬浮窗",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private void save() {
        String v = etPort.getText() == null ? "" : etPort.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(v);
        } catch (Exception e) {
            Toast.makeText(this, "端口必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, "端口需在 1024–65535 之间", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean wasRunning = GatewayService.isRunning();
        boolean notifyGuardBefore = store.isNotifyGuard();
        boolean restart = wasRunning
                && (port != store.getPort() || cbLan.isChecked() != store.isLanEnabled());

        store.setPort(port);
        store.setLanEnabled(cbLan.isChecked());
        store.setAutostart(cbAutostart.isChecked());
        store.setWakeLock(cbWakelock.isChecked());
        store.setNotifyGuard(cbNotify.isChecked());
        store.setTrimSystem(cbTrimSystem.isChecked());
        store.setStreamThinking(cbStreamThinking.isChecked());
        store.setDropToolProtocol(cbDropToolProtocol.isChecked());
        com.codearts.gateway.core.GatewayServer.sTrimSystem = store.isTrimSystem();
        com.codearts.gateway.core.GatewayServer.sStreamThinking = store.isStreamThinking();
        com.codearts.gateway.core.GatewayServer.sDropToolProtocol = store.isDropToolProtocol();
        // 常驻通知：渠道 importance 不可变，切开关必须重启网关服务才能重建渠道生效
        boolean notifyChanged = cbNotify.isChecked() != notifyGuardBefore;

        boolean wantFloat = cbFloat.isChecked();
        if (wantFloat && !Settings.canDrawOverlays(this)) {
            // 无权限却保存了开 → 纠正为关（否则下次启动服务会崩）
            wantFloat = false;
            cbFloat.setChecked(false);
            Toast.makeText(this, "未授予悬浮窗权限，悬浮球保持关闭", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
        }
        store.setFloatBall(wantFloat);
        if (wantFloat) {
            store.setFloatBallHidden(false);
        }
        final boolean wantFloatF = wantFloat;
        if (wantFloatF) {
            try {
                startService(new Intent(this, FloatingBallService.class));
                Toast.makeText(this, "悬浮球已请求显示（若未见请看诊断日志 float 行）",
                        Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(this, "悬浮球启动失败：" + t, Toast.LENGTH_SHORT).show();
            }
        } else {
            stopService(new Intent(this, FloatingBallService.class));
        }

        if (restart || (wasRunning && notifyChanged)) {
            // 通知渠道重建需要重启网关服务；悬浮球服务独立运行不受影响
            GatewayService.ensureStopped(this);
            Toast.makeText(this, "已保存，网关正以新配置重启…", Toast.LENGTH_SHORT).show();
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        GatewayService.ensureStarted(this);
                        if (wantFloatF) {
                            // 网关重启后刷新悬浮球状态显示
                            try {
                                startService(new Intent(this, FloatingBallService.class));
                            } catch (Throwable ignored) {
                            }
                        }
                        finish();
                    }, 500);
        } else if (wasRunning && cbWakelock.isChecked() != (GatewayService.wakeLockHeld())) {
            // WakeLock 开关变化需要重启服务生效
            GatewayService.ensureStopped(this);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> GatewayService.ensureStarted(this), 400);
            Toast.makeText(this, "已保存，保活配置已应用", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
