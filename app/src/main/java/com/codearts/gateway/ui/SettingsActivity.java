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
    private MaterialSwitch cbOnlyLastUser;

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
        cbOnlyLastUser = findViewById(R.id.cb_only_last_user);

        etPort.setText(String.valueOf(store.getPort()));
        cbLan.setChecked(store.isLanEnabled());
        cbAutostart.setChecked(store.isAutostart());
        cbFloat.setChecked(store.isFloatBall());
        cbWakelock.setChecked(store.isWakeLock());
        cbNotify.setChecked(store.isNotifyGuard());
        cbTrimSystem.setChecked(store.isTrimSystem());
        cbStreamThinking.setChecked(store.isStreamThinking());
        cbOnlyLastUser.setChecked(store.isOnlyLastUser());

        android.widget.TextView tvLoginState = findViewById(R.id.tv_login_state);
        String cookieNow = store.getCookie();
        tvLoginState.setText(cookieNow.isEmpty()
                ? "未登录 · 点下方按钮网页登录或直接粘贴 Cookie"
                : "已登录 · 会话 …" + cookieNow.substring(Math.max(0, cookieNow.length() - 6)));
        findViewById(R.id.btn_login_cookie).setOnClickListener(v -> showLoginDialog());

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
    }

    /** 登录入口：打开全屏网页登录，或直接粘贴 Cookie。 */
    private void showLoginDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("登录 CodeArts 码道")
                .setItems(new String[]{"打开登录页（网页登录）", "粘贴 Cookie"}, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, LoginActivity.class));
                    } else {
                        startActivity(new Intent(this, LoginActivity.class)
                                .putExtra(LoginActivity.EXTRA_PASTE, true));
                    }
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
        store.setOnlyLastUser(cbOnlyLastUser.isChecked());
        com.codearts.gateway.core.GatewayServer.sTrimSystem = store.isTrimSystem();
        com.codearts.gateway.core.GatewayServer.sStreamThinking = store.isStreamThinking();
        com.codearts.gateway.core.GatewayServer.sOnlyLastUser = store.isOnlyLastUser();
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
