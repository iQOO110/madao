package com.codearts.gateway.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.codearts.gateway.R;
import com.codearts.gateway.core.DevCloudClient;
import com.codearts.gateway.core.LogRing;
import com.codearts.gateway.core.Store;

/**
 * DevCloud 登录页：WebView 全屏打开 chat/home，顶部悬浮「保存会话 / 复制 / 粘贴」。
 * 用户完成登录后点保存，从 CookieManager 抓取含 cftk 的会话 Cookie 存入 Store；
 * 也可以直接粘贴浏览器里的 Cookie（设置页同入口）。
 */
public class LoginActivity extends AppCompatActivity {

    /** 传入 true 时，进入页面即弹出「粘贴 Cookie」对话框。 */
    public static final String EXTRA_PASTE = "paste_cookie";

    private Store store;
    private WebView webView;
    private View actionMenu;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        store = Store.get(this);

        actionMenu = findViewById(R.id.action_menu);
        webView = findViewById(R.id.webview);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        // 不强制「宽视口/整页缩放」：那会让部分页面内容高度异常、触摸滚动发涩
        webView.setVerticalScrollBarEnabled(true);
        webView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        webView.setWebViewClient(new WebViewClient());
        loadLogin();

        View fab = findViewById(R.id.fab_menu);
        fab.setOnClickListener(v -> actionMenu.setVisibility(
                actionMenu.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_reload).setOnClickListener(v -> loadLogin());
        findViewById(R.id.btn_save).setOnClickListener(v -> saveFromWebView());
        findViewById(R.id.btn_copy).setOnClickListener(v -> copyCookie());
        findViewById(R.id.btn_paste).setOnClickListener(v -> showPasteDialog());

        if (getIntent().getBooleanExtra(EXTRA_PASTE, false)) {
            webView.post(this::showPasteDialog);
        }
    }

    private void loadLogin() {
        webView.loadUrl(DevCloudClient.LOGIN_URL);
    }

    private String currentCookie() {
        // 先 flush（把 WebView 内存中的会话 Cookie 同步到 CookieManager 持久层）再读
        CookieManager.getInstance().flush();
        String c = CookieManager.getInstance().getCookie(DevCloudClient.ORIGIN);
        return c == null ? "" : c;
    }

    private void copyCookie() {
        String c = currentCookie();
        if (c.isEmpty()) {
            Toast.makeText(this, "还没有可复制的 Cookie", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("devcloud-cookie", c));
            Toast.makeText(this, "Cookie 已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /** 从 WebView 抓 Cookie 并保存。 */
    private void saveFromWebView() {
        // 登录跳转后 Cookie 可能刚写完，多 flush 一次
        CookieManager.getInstance().flush();
        applyCookie(currentCookie(), false);
    }

    /**
     * 弹窗粘贴 Cookie。系统剪贴板只保留最后一次复制、且后台读剪贴板受限，
     * 所以不自动预填：用户可点「读取剪贴板」主动读，或从应用内「历史 Cookie」
     * 挑选之前保存过的会话。
     */
    private void showPasteDialog() {
        View box = getLayoutInflater().inflate(R.layout.dialog_paste_cookie, null);
        final EditText et = box.findViewById(R.id.et_cookie);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.login_paste_title)
                .setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        box.findViewById(R.id.btn_read_clip).setOnClickListener(v -> {
            String c = readClipboard();
            if (c.isEmpty()) {
                Toast.makeText(this, "剪贴板为空或系统限制读取，请长按输入框手动粘贴",
                        Toast.LENGTH_LONG).show();
            } else {
                et.setText(c);
                et.setSelection(et.getText().length());
            }
        });
        box.findViewById(R.id.btn_history).setOnClickListener(v -> showHistoryPicker(et));
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String raw = et.getText() == null ? "" : et.getText().toString();
                    if (applyCookie(raw, true)) {
                        dialog.dismiss();
                    }
                }));
        dialog.show();
    }

    /** 主动读取系统剪贴板（此时 Activity 在前台，Android 10+ 才允许读取）。 */
    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence s = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                return s == null ? "" : s.toString();
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        return "";
    }

    /** 从应用内 Cookie 历史里挑一条填入输入框。 */
    private void showHistoryPicker(final EditText target) {
        final java.util.List<Store.CookieHist> hist = store.getCookieHistory();
        if (hist.isEmpty()) {
            Toast.makeText(this, R.string.login_history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT);
        String[] items = new String[hist.size()];
        for (int i = 0; i < hist.size(); i++) {
            Store.CookieHist h = hist.get(i);
            String tail = h.cookie.length() > 12
                    ? h.cookie.substring(h.cookie.length() - 12) : h.cookie;
            items[i] = fmt.format(new java.util.Date(h.ts)) + " · …" + tail;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.login_history_title)
                .setItems(items, (d, which) -> {
                    target.setText(hist.get(which).cookie);
                    target.setSelection(target.getText().length());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 统一校验并保存 Cookie（网页抓取与手动粘贴共用）。 */
    private boolean applyCookie(String raw, boolean syncWeb) {
        String all = normalizeCookie(raw);
        if (all.isEmpty()) {
            Toast.makeText(this, "未取到任何 Cookie，请先完成登录或粘贴完整 Cookie",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        // CSRF 双提交要求 Cookie 里必须有 cftk，否则上游直接返回 csrf validate fail。
        // 浏览器 document.cookie 读不到 HttpOnly 的 cftk，所以手动粘贴常缺这一项。
        if (!hasCftk(all)) {
            Toast.makeText(this, syncWeb
                            ? "缺少 cftk：document.cookie 读不到 HttpOnly 的 cftk。请从浏览器 DevTools → Application → Cookies 复制完整值，或直接在网页登录"
                            : "未取到 cftk，请先在网页完成登录再保存",
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (!hasSessionKey(all)) {
            Toast.makeText(this, R.string.login_paste_invalid, Toast.LENGTH_LONG).show();
            return false;
        }
        store.setCookie(all);
        // 凭据变更：清空网关上游会话缓存，避免首次请求复用旧会话导致 400
        try {
            com.codearts.gateway.core.GatewayServer gw =
                    com.codearts.gateway.service.GatewayService.server();
            if (gw != null) {
                gw.resetSessions();
            }
        } catch (Throwable ignore) {
            // 服务未启动时无需清理
        }
        LogRing.log("login", "DevCloud Cookie 已保存，长度 " + all.length());
        if (syncWeb) {
            // 粘贴保存后写回 WebView，并刷新网页：网页端也能立即用上这份会话
            syncCookieToWebView(all);
            Toast.makeText(this, "已保存，网页已同步刷新", Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            return true;
        }
        Toast.makeText(this, R.string.login_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
        return true;
    }

    /** 把 Cookie 串逐条写回 WebView 的 CookieManager，然后刷新登录页。 */
    private void syncCookieToWebView(String all) {
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            // 先清掉旧 Cookie，避免新旧混合导致首次请求 400
            cm.removeAllCookies(null);
            cm.flush();
            for (String pair : all.split(";")) {
                String p = pair.trim();
                int eq = p.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = p.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT);
                // 跳过 Cookie 属性段，只写 name=value
                if (name.equals("path") || name.equals("domain") || name.equals("expires")
                        || name.equals("max-age") || name.equals("secure")
                        || name.equals("httponly") || name.equals("samesite")) {
                    continue;
                }
                cm.setCookie(DevCloudClient.ORIGIN, p);
            }
            cm.flush();
        } catch (Throwable ignore) {
            // 写 WebView cookie 失败不影响 App 侧保存
        }
        if (webView != null) {
            webView.loadUrl(DevCloudClient.LOGIN_URL);
        }
    }

    /** 兼容 "Cookie: xxx" 前缀与换行粘贴。 */
    private static String normalizeCookie(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.regionMatches(true, 0, "cookie:", 0, 7)) {
            s = s.substring(7).trim();
        }
        s = s.replace("\r", "").replace("\n", "; ");
        return s.trim();
    }

    /** 是否含会话键（cftk / session / hwwafses）。 */
    private static boolean hasSessionKey(String all) {
        for (String pair : all.split(";")) {
            String name = pair.trim();
            int eq = name.indexOf('=');
            if (eq > 0) {
                name = name.substring(0, eq);
            }
            String low = name.toLowerCase();
            if (low.contains("cftk") || low.contains("session") || low.contains("hwwafses")) {
                return true;
            }
        }
        return false;
    }

    /** 是否含 CSRF 双提交所需的 cftk。 */
    private static boolean hasCftk(String all) {
        for (String pair : all.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).trim().toLowerCase().contains("cftk")) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}