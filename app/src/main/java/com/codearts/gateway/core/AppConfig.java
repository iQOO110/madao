package com.codearts.gateway.core;

/**
 * 目标站点常量。来源：对 https://discovery.intern-ai.org.cn/chat/ 前端 bundle 的静态分析。
 *
 * <pre>
 *   GET  {ORIGIN}/api/orbit/v1/models          -> 模型列表
 *   POST {ORIGIN}/api/orbit/v1/chat            -> 新建会话并开始 SSE 生成
 *   POST {ORIGIN}/api/orbit/v1/chat/{chat_id}  -> 续聊（同一条会话）
 *   POST {ORIGIN}/api/orbit/v1/chat/{id}/stop  -> 中断
 *   鉴权：Authorization: Bearer &lt;localStorage["uaa-token"]&gt;
 * </pre>
 */
public final class AppConfig {

    // 身份信息（v1.9.4 修正：此前是 InkStone 移植遗留，导致 /health 自报成另一个项目）
    public static final String APP_NAME = "CodeArts 网关";
    // v1.9.10：与 build.gradle 的 versionName 保持一致；此前写死 1.9.4 导致
    // 装新包后 /health 仍报旧号，用户无法确认装的是哪一版
    public static final String VERSION = "1.9.10";
    public static final String DEFAULT_ORIGIN = "https://discovery.intern-ai.org.cn";

    /**
     * 上游基址。默认是 Intern InkStone 官方站点；保留可覆盖能力，
     * 便于平台更换域名时就地适配，也便于离线回归测试指向本地假上游。
     */
    public static volatile String ORIGIN = DEFAULT_ORIGIN;
    public static volatile String ORBIT_BASE = DEFAULT_ORIGIN + "/api/orbit/v1";
    public static volatile String LOGIN_URL = DEFAULT_ORIGIN + "/chat/";

    /** 前端把登录令牌存在 localStorage 的这个 key 下（space-common-provider 中的常量 pC）。 */
    public static final String TOKEN_STORAGE_KEY = "uaa-token";

    public static final int DEFAULT_PORT = 8080;

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 14; V2463A) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36";

    /**
     * 在 WebView 中执行的取令牌脚本：优先 localStorage，其次 Cookie。
     */
    public static final String TOKEN_PROBE_JS =
            "(function(){try{"
                    + "var k='" + TOKEN_STORAGE_KEY + "';"
                    + "var v=window.localStorage.getItem(k)||'';"
                    + "if(!v){var m=document.cookie.match(new RegExp('(^|;\\\\s*)'+k+'=([^;]+)'));"
                    + "if(m){v=decodeURIComponent(m[2]);}}"
                    + "return v;}catch(e){return 'ERR:'+e;}})()";

    private AppConfig() {
    }
}
