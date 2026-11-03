package com.codearts.gateway.core;

import android.content.Context;

import com.koushikdutta.async.AsyncNetworkSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.http.body.StringBody;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地 OpenAI 兼容网关。
 *
 * <p>对外（OpenAI 协议）：
 * <ul>
 *   <li>GET  /v1/models</li>
 *   <li>POST /v1/chat/completions（stream=true/false 都支持）</li>
 *   <li>GET  /health、GET /v1/models/raw</li>
 * </ul>
 *
 * <p>对内（Intern InkStone orbit）：{@link OrbitClient}
 */
public final class GatewayServer {

    private final Store store;
    private final Context appCtx;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);
    private final AtomicLong requestCounter = new AtomicLong();
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> devCloudSessions = new ConcurrentHashMap<>();
    /** 会话失败时间戳（下次 ensureSession 时废弃重建，v1.9.7）。 */
    private final Map<String, Long> devCloudSessionFailed = new ConcurrentHashMap<>();
    /** 最近几次工具链请求的原始证据（落盘到 /sdcard/Download/codearts-tools-debug.json）。 */
    private final java.util.Deque<String> toolTrace = new java.util.ArrayDeque<>();
    /** MCP 服务器（按配置懒加载）。 */
    private final List<McpHub.Server> mcpServers = new ArrayList<>();
    private String mcpCfgSig = "\u0000";
    /** 构建标记：写进诊断文件，便于确认机上装的是哪一版。 */
    public static final String BUILD_TAG = "v1.9.10+apple-ui+no-mcp+ask-schema+session-key+login-fullscreen";

    private AsyncHttpServer server;
    private volatile boolean running;
    private int port = AppConfig.DEFAULT_PORT;
    private long startedAt;

    public GatewayServer(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.store = Store.get(ctx);
        sTrimSystem = store.isTrimSystem();
        sStreamThinking = store.isStreamThinking();
        sDropToolProtocol = store.isDropToolProtocol();
        sOnlyLastUser = store.isOnlyLastUser();
    }

    /** 采集请求里声明的工具名（用于把模型吐出的错名对齐回真实工具名）。 */
    static java.util.List<String> collectToolNames(JSONObject openai) {
        java.util.List<String> out = new java.util.ArrayList<>();
        JSONArray tools = openai == null ? null : openai.optJSONArray("tools");
        if (tools == null) {
            return out;
        }
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) {
                continue;
            }
            JSONObject f = t.optJSONObject("function");
            String name = f != null ? f.optString("name", "") : t.optString("name", "");
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** 记录一次工具链请求的证据并落盘（只读诊断，失败不影响对话）。 */
    private void recordTrace(JSONObject entry) {
        try {
            synchronized (toolTrace) {
                toolTrace.addLast(entry.toString());
                while (toolTrace.size() > 4) {
                    toolTrace.removeFirst();
                }
            }
            StringBuilder sb = new StringBuilder();
            sb.append("== 网关工具链路诊断（最近若干次请求，按时间先后）\n");
            synchronized (toolTrace) {
                int i = 0;
                for (String s : toolTrace) {
                    sb.append("\n---- request[").append(i++).append("] ----\n").append(s).append('\n');
                }
            }
            DiagDump.save(appCtx, "codearts-tools-debug.json", sb.toString());
        } catch (Throwable ignore) {
            // 诊断落盘失败不影响对话
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public long getRequestCount() {
        return requestCounter.get();
    }

    /** 活跃 DevCloud 会话映射数（UI 展示用）。 */
    public int getDevCloudSessionCount() {
        return devCloudSessions.size();
    }

    public long getStartedAt() {
        return startedAt;
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        port = store.getPort();
        server = new AsyncHttpServer();

        // 路由用正则匹配（androidasync 的 path 直接按正则编译）：
        // 同时兼容带/不带 /v1 前缀、带/不带结尾斜杠的客户端。精确路由必须注册在兜底路由之前。
        server.addAction("GET", "/(v1/)?models/raw/?", this::handleModelsRaw);
        server.addAction("GET", "/(v1/)?models/?", this::handleModels);
        server.addAction("POST", "/(v1/)?chat/completions/?", this::handleChatSubmit, h -> new StringBody());
        // 管理接口（仅本机回环；allowRemote 会强制）：外部工具可直接读写工具/MCP 配置并自检
        server.addAction("GET", "/admin/state/?", (req, res) -> {
            if (!allowRemote(req, res)) {
                return;
            }
            JSONObject o = new JSONObject();
            try {
                o.put("ok", true);
                o.put("build", BUILD_TAG);
                o.put("port", port);
                o.put("cookiePresent", !store.getCookie().isEmpty());
                o.put("lanEnabled", store.isLanEnabled());
                o.put("tool", toolStateJson());
            } catch (Throwable ignore) {
                // 忽略
            }
            json(res, 200, o.toString());
        });
        server.addAction("GET", "/admin/selftest/?", this::handleAdminSelfTest);
        server.addAction("POST", "/admin/config/?", this::handleAdminConfig, h -> new StringBody());
        server.addAction("GET", "/(v1/)?(health|status)/?", (req, res) -> {
            JSONObject o;
            try {
                o = new JSONObject(statusJson());
                o.put("cookie_present", !store.getCookie().isEmpty());
                o.put("cftk_present", !DevCloudClient.extractCftk(store.getCookie()).isEmpty());
            } catch (Throwable t) {
                o = new JSONObject();
            }
            json(res, 200, o.toString());
        });
        server.addAction("GET", "/", (req, res) -> json(res, 200, statusJson()));
        server.addAction("OPTIONS", "/.*", (req, res) -> {
            cors(res);
            try {
                res.code(204);
                res.send("text/plain; charset=utf-8", new byte[0]);
            } catch (Throwable ignored) {
            }
        });
        server.addAction("GET", "/.*", this::handleNotFound);
        server.addAction("POST", "/.*", this::handleNotFound);

        server.listen(port);
        running = true;
        startedAt = System.currentTimeMillis();
        LogRing.log("gateway", "已启动，监听 " + (store.isLanEnabled() ? "0.0.0.0:" : "本机:") + port);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (server != null) {
                server.stop();
            }
        } catch (Throwable t) {
            LogRing.log("gateway", "停止异常: " + t);
        }
        server = null;
        LogRing.log("gateway", "已停止");
    }

    public void shutdown() {
        stop();
        pool.shutdownNow();
    }

    /** 账号/Cookie 变更后清空会话映射，避免把旧凭据的 chat_id 与上游会话用到新凭据上。 */
    public void resetSessions() {
        sessions.clear();
        devCloudSessions.clear();
        devCloudSessionFailed.clear();
        pendingUpstreamQuestions.clear();
        LogRing.log("session", "凭据变更，已清空上游会话缓存");
    }

    // ------------------------------------------------------------------ 处理器

    private void handleModels(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        submit(res, () -> {
            if (!checkAuth(req, res)) {
                return;
            }
            String cookie = store.getCookie();
            if (cookie.isEmpty()) {
                json(res, 401, ProtocolAdapter.error(
                        "尚未登录 DevCloud：请先在 App 内登录并保存会话",
                        "invalid_request_error", "no_cookie"));
                return;
            }
            List<Store.ModelInfo> models = DevCloudClient.fetchModels(cookie);
            if (models.isEmpty()) {
                json(res, 502, ProtocolAdapter.error(
                        "DevCloud 返回了 0 个模型（可能 Cookie 失效，请重新登录）",
                        "upstream_error", "empty_models"));
                return;
            }
            String openaiJson = Store.modelsToOpenAi(models);
            store.setModelsCache(openaiJson);
            DevCloudModelSet.set(models);
            json(res, 200, openaiJson);
            LogRing.log("models", "输出 " + models.size() + " 个模型");
        });
    }

    /** DevCloud 模型名集合（命中即提示客户端可用）。 */
    static final class DevCloudModelSet {
        private static final java.util.Set<String> SET = java.util.concurrent.ConcurrentHashMap.newKeySet();

        static void set(List<Store.ModelInfo> models) {
            SET.clear();
            if (models != null) {
                for (Store.ModelInfo m : models) {
                    SET.add(m.id);
                }
            }
        }

        static boolean contains(String model) {
            return model != null && SET.contains(model);
        }
    }

    private void handleModelsRaw(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        submit(res, () -> {
            if (!checkAuth(req, res)) {
                return;
            }
            String cookie = store.getCookie();
            if (cookie.isEmpty()) {
                json(res, 200, "{\"local_error\":\"未登录 DevCloud\"}");
                return;
            }
            DevCloudClient.Resp r = DevCloudClient.request(cookie, "GET",
                    DevCloudClient.BASE + "/PromptCenterService/v1/model/builtin", null, null);
            json(res, 200, r.text());
        });
    }

    private void handleChatSubmit(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        String raw = readBody(req);
        JSONObject openai;
        try {
            openai = new JSONObject(raw);
        } catch (Throwable t) {
            json(res, 400, ProtocolAdapter.error("请求体不是合法 JSON: " + t,
                    "invalid_request_error", null));
            return;
        }
        if (!checkAuth(req, res)) {
            return;
        }
        final long n = requestCounter.incrementAndGet();
        final String cookie = store.getCookie();
        final String model = openai.optString("model", "");
        final boolean stream = openai.optBoolean("stream", true);
        // 工具 / MCP 功能已移除：不再注入工具协议段，也不再进入工具执行路径。
        final boolean toolsDeclared = false;
        final java.util.List<String> declaredNames = collectToolNames(openai);
        // 提问工具规格（名字 + 参数形态），决定码道提问如何转成 tool_calls
        final AskSpec askSpec = resolveAskSpec(openai, declaredNames);

        // （工具 / MCP 执行路径已移除：请求一律走下方普通对话路径）

        // 标题生成请求（Claude Code / codex 等 agent 新建会话时先发一条，让模型概括标题）：
        // 它不是用户对话。本地直接生成并返回，不转发码道，避免在码道多开一个对话。
        final String rawQuery = openaiToQueryText(openai);
        if (isTitleRequest(rawQuery)) {
            String title = localSessionTitle(rawQuery);
            LogRing.log("req#" + n, "标题生成请求 → 本地返回: " + title);
            respondLocalTitle(res, model, title, stream);
            return;
        }
        if (cookie.isEmpty()) {
            json(res, 401, ProtocolAdapter.error(
                    "尚未登录 DevCloud：请先在 App 内登录并保存会话",
                    "invalid_request_error", "no_cookie"));
            return;
        }
        String content = rawQuery;
        if (toolsDeclared) {
            content = content + "\n\n" + ToolShim.buildInstruction(openai);
        }
        final String sessionKey = sessionKey(req, openai);
        // 客户端显式新建对话：废弃旧码道会话与待回灌提问，强制新建上游会话
        if (openai.optBoolean("new_chat", false)) {
            devCloudSessions.remove(sessionKey);
            devCloudSessionFailed.remove(sessionKey);
            sessions.remove(sessionKey);
            pendingUpstreamQuestions.remove(sessionKey);
        }
        // 上一轮码道提问：本轮把「原提问 + 用户新回复」一起回灌，码道才知道按哪个选项继续
        String pendingQ = pendingUpstreamQuestions.remove(sessionKey);
        if (pendingQ != null && pendingQ.length() > 0 && !openai.optBoolean("new_chat", false)) {
            content = buildPendingContext(pendingQ, content);
            LogRing.log("req#" + n, "回灌上轮码道提问: " + DevCloudClient.trim(pendingQ, 120));
        }
        if (content.isEmpty()) {
            json(res, 400, ProtocolAdapter.error("messages 为空", "invalid_request_error", null));
            return;
        }
        LogRing.log("req#" + n, (stream ? "stream" : "sync") + " model="
                + (model.isEmpty() ? "-" : model) + " session=" + sessionKey
                + (toolsDeclared ? " tools=" + declaredNames.size() : "")
                + " q=" + DevCloudClient.trim(content, 60));
        if (!stream) {
            handleChatSync(res, cookie, content, model, sessionKey, n, toolsDeclared, declaredNames,
                    askSpec);
            return;
        }
        handleChatStream(res, cookie, content, model, sessionKey, n, toolsDeclared, declaredNames,
                askSpec);
    }

    /** 是否为 agent 的会话标题生成请求（不是用户对话）。 */
    static boolean isTitleRequest(String q) {
        if (q == null) {
            return false;
        }
        String t = q.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.isEmpty()) {
            return false;
        }
        if (t.contains("generate the session title") && t.contains("json")) {
            return true;
        }
        if (t.contains("session title") && t.contains("json array of human messages")) {
            return true;
        }
        return t.contains("生成会话标题") || t.contains("生成对话标题");
    }

    /**
     * 构造「上一轮提问 + 用户回复」的回灌上下文：只保留问题与选项文案，
     * 不带 question_id/call_id/turn_id 等内部字段，避免啰嗦和重复提问。
     */
    static String buildPendingContext(String pendingJson, String userReply) {
        StringBuilder sb = new StringBuilder();
        sb.append("[系统] 上一轮你向用户提问，用户已回复。请根据用户回复直接继续，")
                .append("不要重复同样的提问。\n");
        try {
            JSONObject p = new JSONObject(pendingJson == null ? "{}" : pendingJson);
            JSONArray qs = p.optJSONArray("questions");
            if (qs != null && qs.length() > 0) {
                sb.append("上一轮提问：\n");
                for (int i = 0; i < qs.length(); i++) {
                    JSONObject q = qs.optJSONObject(i);
                    if (q == null) {
                        continue;
                    }
                    sb.append(i + 1).append(") ").append(q.optString("question", "")).append('\n');
                    JSONArray opts = q.optJSONArray("options");
                    if (opts != null) {
                        for (int j = 0; j < opts.length(); j++) {
                            JSONObject o = opts.optJSONObject(j);
                            if (o != null) {
                                sb.append("   - ").append(o.optString("label", "")).append('\n');
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // 解析失败也要把用户回复带上
        }
        String reply = userReply == null ? "" : userReply.trim();
        sb.append("用户回复：").append(reply.isEmpty() ? "（未提供）" : reply);
        return sb.toString();
    }

    /** 从标题请求里提取第一条 human 文本作为标题（本地生成，不调上游）。 */
    static String localSessionTitle(String q) {
        String title = "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                    .matcher(q == null ? "" : q);
            if (m.find()) {
                title = m.group(1)
                        .replace("\\n", " ")
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        title = title.replaceAll("\\s+", " ").trim();
        if (title.isEmpty() && q != null) {
            title = q.replaceAll("\\s+", " ").trim();
        }
        if (title.isEmpty()) {
            return "新对话";
        }
        if (title.length() > 40) {
            title = title.substring(0, 40) + "…";
        }
        return title;
    }

    /** 本地返回标题（兼容流式/非流式），不经过 DevCloud，也不产生码道会话。 */
    private void respondLocalTitle(AsyncHttpServerResponse res, String model,
                                   String title, boolean stream) {
        String id = ProtocolAdapter.newCompletionId();
        if (!stream) {
            json(res, 200, ProtocolAdapter.completion(id, model, title, null));
            return;
        }
        com.koushikdutta.async.AsyncSocket sock = res.getSocket();
        if (sock == null) {
            json(res, 500, ProtocolAdapter.error("网关无法获取连接句柄", "internal_error", null));
            return;
        }
        SseWriter sse = new SseWriter(sock);
        sse.data(ProtocolAdapter.chunk(id, model,
                "{\"role\":\"assistant\",\"content\":\"\"}", null));
        sse.data(ProtocolAdapter.chunk(id, model, deltaJson(title), null));
        sse.data(ProtocolAdapter.chunk(id, model, "{}", "stop"));
        sse.data("[DONE]");
        sse.close();
    }

    // ================================================================ 工具路径（A + B）

    /** MCP 服务器列表（配置变了就重建）。 */
    private List<McpHub.Server> mcpServers() {
        String cfg = store.getMcpServers();
        synchronized (this) {
            if (!cfg.equals(mcpCfgSig)) {
                mcpServers.clear();
                mcpServers.addAll(McpHub.parseServers(cfg));
                mcpCfgSig = cfg;
                LogRing.log("mcp", "MCP 服务器已加载: " + mcpServers.size() + " 个");
            }
            return mcpServers;
        }
    }

    /** 发现并暴露 MCP 工具（只读类；与客户端工具重名时加前缀）。 */
    private List<McpHub.Exposed> exposedMcpTools(java.util.Set<String> clientNames,
                                                 List<String> blocked, List<String> problems) {
        List<McpHub.Exposed> out = new ArrayList<>();
        boolean allowMutating = store.isAllowMutating();
        for (McpHub.Server s : mcpServers()) {
            try {
                McpHub.refresh(s, 60000, false);
            } catch (Throwable t) {
                s.lastError = String.valueOf(t);
                problems.add(s.name + ": " + DevCloudClient.trim(String.valueOf(t), 120));
                LogRing.log("mcp", "工具列表失败 " + s.name + " " + t);
                continue;
            }
            for (McpHub.Tool t : s.tools) {
                if (McpHub.mutating(t.name) && !allowMutating) {
                    blocked.add(s.name + "/" + t.name);
                    continue;
                }
                String exposed = t.name;
                if (clientNames.contains(exposed)) {
                    exposed = "mcp__" + s.name + "__" + t.name;
                }
                out.add(new McpHub.Exposed(exposed, s, t));
            }
        }
        return out;
    }

    /** 组装发给工具上游的 tools（客户端工具在前，MCP 工具在后）。 */
    private JSONArray buildToolPayload(JSONArray clientTools, List<McpHub.Exposed> mcpTools) {
        JSONArray tools = new JSONArray();
        if (clientTools != null) {
            for (int i = 0; i < clientTools.length(); i++) {
                JSONObject t = clientTools.optJSONObject(i);
                if (t != null) {
                    tools.put(t);
                }
            }
        }
        for (McpHub.Exposed e : mcpTools) {
            JSONObject fn = new JSONObject();
            JSONObject t = new JSONObject();
            try {
                fn.put("name", e.name);
                fn.put("description", DevCloudClient.trim(e.tool.description, 900));
                fn.put("parameters", new JSONObject(e.tool.schema));
                t.put("type", "function");
                t.put("function", fn);
            } catch (Throwable ignore) {
                continue;
            }
            tools.put(t);
        }
        return tools;
    }

    /**
     * 工具路径主流程：模型 ↔ 工具循环。
     * <ul>
     *   <li>模型要求调用的工具若属于 MCP → 网关自己执行，结果回灌，继续下一轮；</li>
     *   <li>若属于客户端自己声明的 tools → 原样回传，由客户端执行（标准 OpenAI 行为）。</li>
     * </ul>
     */
    private void handleToolPath(AsyncHttpServerResponse res, JSONObject openai, String model,
                                boolean stream, long n, String toolBase, boolean mcpOn,
                                List<String> clientNames) {
        final String toolKey = store.getToolKey();
        final String toolModel = store.getToolModel();
        final int timeoutMs = 300000;
        final int maxRounds = 6;
        final long t0 = System.currentTimeMillis();
        final java.util.Set<String> clientSet = new java.util.HashSet<>(clientNames);
        List<String> blocked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<McpHub.Exposed> mcpTools = mcpOn ? exposedMcpTools(clientSet, blocked, problems)
                : new ArrayList<McpHub.Exposed>();
        Map<String, McpHub.Exposed> byName = new java.util.HashMap<>();
        for (McpHub.Exposed e : mcpTools) {
            byName.put(e.name, e);
        }
        JSONArray tools = buildToolPayload(openai.optJSONArray("tools"), mcpTools);
        JSONArray messages = new JSONArray();
        JSONArray src = openai.optJSONArray("messages");
        if (src != null) {
            for (int i = 0; i < src.length(); i++) {
                JSONObject m = src.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                try {
                    // 注意：Android 的 org.json 的 put/构造是**受检异常**（JVM 侧 json-java 不是），
                    // 所以这里必须显式 try/catch，否则只在真机构建时才报错。
                    messages.put(new JSONObject(m.toString()));
                } catch (Throwable ignore) {
                    // 单条消息有问题就跳过，不影响整轮
                }
            }
        }
        List<JSONObject> mcpTrace = new ArrayList<>();
        String content = "";
        List<ToolUpstream.Call> clientCalls = new ArrayList<>();
        int rounds = 0;
        String error = null;
        try {
            while (true) {
                JSONObject body = new JSONObject();
                body.put("model", toolModel.isEmpty() ? model : toolModel);
                body.put("messages", messages);
                body.put("stream", true);
                if (tools.length() > 0) {
                    body.put("tools", tools);
                    body.put("tool_choice", "auto");
                }
                ToolUpstream.Turn turn = ToolUpstream.chat(toolBase, toolKey, body.toString(), timeoutMs);
                if (turn.calls.isEmpty()) {
                    content = turn.content == null ? "" : turn.content;
                    break;
                }
                boolean hasClientCall = false;
                for (ToolUpstream.Call c : turn.calls) {
                    if (clientSet.contains(c.name) && !byName.containsKey(c.name)) {
                        hasClientCall = true;
                        break;
                    }
                }
                if (hasClientCall) {
                    // 交给客户端执行（标准行为）：连同模型已产出的文字一起回传
                    content = turn.content == null ? "" : turn.content;
                    clientCalls = turn.calls;
                    break;
                }
                // 全部是 MCP 工具 → 网关执行
                JSONArray tcs = new JSONArray();
                for (ToolUpstream.Call c : turn.calls) {
                    JSONObject tc = new JSONObject();
                    tc.put("id", c.id);
                    tc.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", c.name);
                    fn.put("arguments", c.arguments);
                    tc.put("function", fn);
                    tcs.put(tc);
                }
                JSONObject asst = new JSONObject();
                asst.put("role", "assistant");
                asst.put("content", JSONObject.NULL);
                asst.put("tool_calls", tcs);
                messages.put(asst);
                for (ToolUpstream.Call c : turn.calls) {
                    McpHub.Exposed e = byName.get(c.name);
                    String result;
                    long ms0 = System.currentTimeMillis();
                    if (e == null) {
                        result = "错误：该工具未在本网关注册（可能属于客户端）";
                    } else {
                        try {
                            result = McpHub.call(e.server, e.tool.name, c.arguments, timeoutMs);
                        } catch (Throwable t) {
                            result = "工具执行失败: " + DevCloudClient.trim(String.valueOf(t), 300);
                        }
                    }
                    JSONObject tm = new JSONObject();
                    tm.put("role", "tool");
                    tm.put("tool_call_id", c.id);
                    tm.put("name", c.name);
                    tm.put("content", result);
                    messages.put(tm);
                    JSONObject tr = new JSONObject();
                    tr.put("tool", e == null ? c.name : e.tool.name);
                    tr.put("args", c.arguments);
                    tr.put("ms", System.currentTimeMillis() - ms0);
                    tr.put("result", DevCloudClient.trim(result, 500));
                    mcpTrace.add(tr);
                    LogRing.log("req#" + n, "MCP 执行 " + (e == null ? c.name : e.tool.name)
                            + " → " + DevCloudClient.trim(result, 120));
                }
                rounds++;
                if (rounds >= maxRounds) {
                    content = (content.isEmpty() ? "" : content + "\n")
                            + "（已达最大工具调用轮数 " + maxRounds + "，停止）";
                    break;
                }
            }
        } catch (Throwable t) {
            error = String.valueOf(t);
            LogRing.log("req#" + n, "工具路径失败: " + t);
        }
        long elapsed = System.currentTimeMillis() - t0;
        LogRing.log("req#" + n, "工具路径完成 轮数=" + rounds + " MCP调用=" + mcpTrace.size()
                + " 客户端调用=" + clientCalls.size() + " 拦截写入类=" + blocked.size()
                + " 耗时=" + elapsed + "ms" + (error == null ? "" : " 错误=" + error));
        // 证据落盘（便于外部核对）
        try {
            JSONObject trace = new JSONObject();
            trace.put("ts", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date()));
            trace.put("n", n);
            trace.put("build", BUILD_TAG);
            trace.put("path", "tool(A+B)");
            trace.put("toolUpstream", toolBase);
            trace.put("toolModel", toolModel);
            trace.put("mcpEnabled", mcpOn);
            trace.put("mcpTools", mcpTools.size());
            trace.put("blockedMutating", new JSONArray(blocked));
            trace.put("mcpProblems", new JSONArray(problems));
            trace.put("clientTools", new JSONArray(clientNames));
            trace.put("rounds", rounds);
            trace.put("mcpCalls", new JSONArray(mcpTrace.toString()));
            trace.put("clientCalls", clientCalls.size());
            trace.put("content", DevCloudClient.trim(content, 3000));
            trace.put("elapsedMs", elapsed);
            if (error != null) {
                trace.put("error", error);
            }
            recordTrace(trace);
        } catch (Throwable ignore) {
            // 忽略
        }

        if (error != null) {
            json(res, 502, ProtocolAdapter.error("工具路径失败: " + error, "upstream_error", null));
            return;
        }
        final String cid = ProtocolAdapter.newCompletionId();
        final String outModel = toolModel.isEmpty() ? model : toolModel;
        if (!stream) {
            json(res, 200, toolCompletion(cid, outModel, content, clientCalls));
            return;
        }
        final com.koushikdutta.async.AsyncSocket sock = res.getSocket();
        if (sock == null) {
            json(res, 500, ProtocolAdapter.error("网关无法获取连接句柄", "internal_error", null));
            return;
        }
        SseWriter sseClient = new SseWriter(sock);
        sseClient.data(ProtocolAdapter.chunk(cid, outModel,
                "{\"role\":\"assistant\",\"content\":\"\"}", null));
        if (content != null && !content.isEmpty()) {
            sseClient.data(ProtocolAdapter.chunk(cid, outModel, deltaJson(content), null));
        }
        int idx = 0;
        for (ToolUpstream.Call c : clientCalls) {
            JSONObject delta = new JSONObject();
            JSONArray arr = new JSONArray();
            JSONObject tc = new JSONObject();
            try {
                tc.put("index", idx);
                tc.put("id", c.id);
                tc.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", c.name);
                fn.put("arguments", "");
                tc.put("function", fn);
                arr.put(tc);
                delta.put("tool_calls", arr);
            } catch (Throwable ignore) {
                // 忽略
            }
            sseClient.data(ProtocolAdapter.chunk(cid, outModel, delta.toString(), null));
            JSONObject d2 = new JSONObject();
            JSONArray a2 = new JSONArray();
            JSONObject t2 = new JSONObject();
            try {
                t2.put("index", idx);
                JSONObject fn2 = new JSONObject();
                fn2.put("arguments", c.arguments);
                t2.put("function", fn2);
                a2.put(t2);
                d2.put("tool_calls", a2);
            } catch (Throwable ignore) {
                // 忽略
            }
            sseClient.data(ProtocolAdapter.chunk(cid, outModel, d2.toString(), null));
            idx++;
        }
        sseClient.data(ProtocolAdapter.chunk(cid, outModel, "{}",
                clientCalls.isEmpty() ? "stop" : "tool_calls"));
        sseClient.data("[DONE]");
        sseClient.close();
    }

    /** 非流式完整报文（工具路径用）。 */
    private static String toolCompletion(String cid, String model, String content,
                                         List<ToolUpstream.Call> calls) {
        return toolCompletion(cid, model, content, calls, null);
    }

    /** 非流式完整报文（工具路径用），可携带思考过程（v1.9.10）。 */
    private static String toolCompletion(String cid, String model, String content,
                                         List<ToolUpstream.Call> calls, String reasoning) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", cid);
            o.put("object", "chat.completion");
            o.put("created", System.currentTimeMillis() / 1000L);
            o.put("model", model == null || model.isEmpty() ? "mcp-auto" : model);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", content == null ? "" : content);
            if (reasoning != null && !reasoning.isEmpty()) {
                msg.put("reasoning_content", reasoning);
            }
            if (calls != null && !calls.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (ToolUpstream.Call c : calls) {
                    JSONObject item = new JSONObject();
                    item.put("id", c.id);
                    item.put("type", "function");
                    JSONObject f = new JSONObject();
                    f.put("name", c.name);
                    f.put("arguments", c.arguments);
                    item.put("function", f);
                    arr.put(item);
                }
                msg.put("tool_calls", arr);
            }
            JSONArray choices = new JSONArray();
            JSONObject c0 = new JSONObject();
            c0.put("index", 0);
            c0.put("message", msg);
            c0.put("finish_reason", calls != null && !calls.isEmpty() ? "tool_calls" : "stop");
            choices.put(c0);
            o.put("choices", choices);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            o.put("usage", usage);
        } catch (Throwable ignore) {
            // 忽略
        }
        return o.toString();
    }

    /**
     * 把 agent 客户端（DSH 等）的 messages 还原成发给码道的 query 文本。
     *
     * <p>码道是<b>多轮续聊</b>会话，之前轮次塞进去的文本还会残留在上游上下文里。
     * DSH 等客户端每轮携带：
     * <ul>
     *   <li>system 消息：10KB+ 的工具清单 / 硬约束 / 运行时说明；</li>
     *   <li>「Current runtime context」运行时快照 user 消息：工作目录 / 时区 /
     *       文件策略 / GUI URL / Time sampled… 这些全是环境状态，不是用户原话。</li>
     * </ul>
     * 这些内容与「用户说了什么」无关，整段拍进码道只会浪费 token、稀释指令，
     * 并在多轮会话里反复累积。因此默认只抽取<b>用户原始输入</b>：
     * <ul>
     *   <li>system / developer 提示：整条丢弃（agent 的角色设定、工具说明、硬约束都不发）；</li>
     *   <li>运行时快照 user 消息（Current runtime context / environment_context 等）：整条丢弃；</li>
     *   <li>user 消息里夹带的 agent 注入块（&lt;system-reminder&gt; 等）：就地剥离；</li>
     * </ul>
     * 只保留真正的 user 原话；关闭「每轮只发新消息」时才转发完整对话历史，
     * 但依然过滤上述注入内容。
     */
    static String openaiToQueryText(JSONObject openai) {
        JSONArray messages = openai == null ? null : openai.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return "";
        }
        // 默认：只抽取最后一条「用户原始输入」，其余全部视为 agent 注入内容丢弃。
        if (sOnlyLastUser) {
            return rawUserInput(messages);
        }
        // 关闭开关：转发完整对话历史，但依然过滤 system/开发者提示与运行时快照。
        return renderRange(messages, 0, messages.length());
    }

    /**
     * 只抽取最后一条真正的<b>用户原始输入</b>，兼容 codex / Claude Code / DSH 等 agent：
     * 它们会在 user 消息里夹带运行时快照、{@code <system-reminder>}、{@code <environment_context>}
     * 等注入内容，这里逐层剥离；剥离后为空的整条跳过，继续往前找真正的用户原话。
     */
    private static String rawUserInput(JSONArray messages) {
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            if (!"user".equals(normalizeRole(m.optString("role", "user")))) {
                continue;
            }
            String text = ProtocolAdapter.flattenContent(m.opt("content"));
            if (sTrimSystem) {
                text = stripAgentWrappers(text); // 先剥离 agent 注入的包装块
                if (isRuntimeContextSnapshot(text)) {
                    continue; // 纯运行时快照：整条跳过
                }
            }
            if (text.trim().isEmpty()) {
                continue; // 注入内容被剥空 / 纯图片无文本，继续往前找
            }
            return text.trim();
        }
        return fallbackQueryText(messages);
    }

    /**
     * 完整历史模式：过滤 system / 开发者提示、运行时快照与 agent 包装，保留真实对话轮次。
     */
    private static String renderRange(JSONArray messages, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String role = normalizeRole(m.optString("role", "user"));
            String text = ProtocolAdapter.flattenContent(m.opt("content"));
            boolean isSystem = "system".equals(role);
            if (sTrimSystem && isSystem) {
                continue; // 丢弃所有 system / developer 提示
            }
            if (sTrimSystem) {
                if ("user".equals(role)) {
                    text = stripAgentWrappers(text);
                }
                if (isRuntimeContextSnapshot(text)) {
                    continue;
                }
            }
            if ("tool".equals(role) || "function".equals(role)) {
                // 工具结果：渲染成文本协议的一部分，模型才能看到"自己调用后的返回"
                text = ToolShim.renderToolResult(m);
            }
            String calls = "assistant".equals(role)
                    ? ToolShim.renderAssistantToolCalls(m.optJSONArray("tool_calls")) : "";
            if (text.isEmpty() && calls.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            if ("assistant".equals(role)) {
                if (!text.isEmpty()) {
                    sb.append("[助手] ").append(text);
                }
                if (!calls.isEmpty()) {
                    if (!text.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(calls);
                }
            } else {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /**
     * 没有可用 user 消息时的兜底：取最后一条非 system 的非空内容（含 assistant.tool_calls），
     * 避免 agent 只回传工具结果、或客户端只发 assistant 消息时直接 400。
     */
    private static String fallbackQueryText(JSONArray messages) {
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String role = normalizeRole(m.optString("role", "user"));
            if ("system".equals(role)) {
                continue;
            }
            String text = ProtocolAdapter.flattenContent(m.opt("content"));
            if (sTrimSystem) {
                text = stripAgentWrappers(text);
            }
            if (!text.trim().isEmpty() && !isRuntimeContextSnapshot(text)) {
                return text.trim();
            }
            String calls = "assistant".equals(role)
                    ? ToolShim.renderAssistantToolCalls(m.optJSONArray("tool_calls")) : "";
            if (!calls.isEmpty()) {
                return calls;
            }
        }
        return "";
    }

    /** OpenAI 新版用 developer 代替 system，统一按 system 处理。 */
    private static String normalizeRole(String role) {
        return "developer".equals(role) ? "system" : role;
    }

    /**
     * 剥离 codex / Claude Code / DSH 等 agent 在 user 消息里夹带的注入块，只留人类输入。
     * 覆盖：
     * <ul>
     *   <li>成对注入块整体删除：{@code <system-reminder>}、{@code <environment_context>}、
     *       {@code <runtime_context>}、{@code <local-command-stdout>}、{@code <command-*>}；</li>
     *   <li>包裹用户原话的标签本身去掉，保留标签内文本：
     *       {@code <user_query>} / {@code <user_input>} / {@code <human>} / {@code <prompt>}。</li>
     * </ul>
     */
    static String stripAgentWrappers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String t = text;
        t = t.replaceAll("(?is)<system-reminder>.*?</system-reminder>", " ");
        t = t.replaceAll("(?is)<environment_context>.*?</environment_context>", " ");
        t = t.replaceAll("(?is)<runtime_context>.*?</runtime_context>", " ");
        t = t.replaceAll("(?is)<local-command-stdout>.*?</local-command-stdout>", " ");
        t = t.replaceAll("(?is)<local-command-caveat>.*?</local-command-caveat>", " ");
        t = t.replaceAll("(?is)<command-name>.*?</command-name>", " ");
        t = t.replaceAll("(?is)<command-message>.*?</command-message>", " ");
        t = t.replaceAll("(?is)<command-args>.*?</command-args>", " ");
        t = t.replaceAll("(?is)</?(user_query|user_input|user_message|human|prompt|query)>", " ");
        return t.trim();
    }

    /**
     * 判断 user 消息是否为 agent 运行时上下文快照（DSH 的「Current runtime context.
     * This snapshot supersedes earlier runtime-context snapshots.」+ 工作目录 / 时区 /
     * Time sampled 等环境信息），而非用户原话。
     */
    static boolean isRuntimeContextSnapshot(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String t = text.trim();
        // 快照头：完整快照消息以固定文案开头；碎片快照（被上游折叠/截断后）
        // 通常只剩 "Time sampled while preparing turn" + "Browser time zone" 两行。
        // 兼容 DSH / Codex / Cursor / 中文 agent 等不同标记。
        String head = t.length() > 80 ? t.substring(0, 80).toLowerCase() : t.toLowerCase();
        if (head.startsWith("current runtime context")
                || head.startsWith("<environment_context>")
                || head.startsWith("<runtime_context>")
                || head.startsWith("<environment>")
                || head.startsWith("运行时上下文")
                || head.startsWith("当前运行时上下文")
                || head.startsWith("环境上下文")) {
            return true;
        }
        if (t.contains("Time sampled") && t.contains("Browser time zone")) {
            return true;
        }
        // 中文/紧凑快照：工作目录 + 时区，且带时间采样或环境块特征
        if (t.contains("工作目录")
                && (t.contains("时区") || t.contains("Time zone"))
                && (t.contains("Current time") || t.contains("当前时间")
                || t.contains("Time sampled") || t.contains("时间采样"))) {
            return true;
        }
        // 超大环境块：>4KB 且带 agent 环境特征（工作目录 / 浏览器时区 / 策略）
        if (t.length() > 4000
                && (t.contains("Current working directory") || t.contains("工作目录")
                || t.contains("Browser time zone") || t.contains("浏览器时区"))) {
            return true;
        }
        return false;
    }

    /** 网关全局的 system 压缩开关（默认开；构造时从 Store 同步，单元测试可单独置位）。 */
    public static volatile boolean sTrimSystem = true;

    /** 网关全局的思考过程透传开关（默认开；构造时从 Store 同步）。 */
    public static volatile boolean sStreamThinking = true;

    /**
     * 网关全局的「工具协议」丢弃开关（默认开；构造时从 Store 同步）。
     * 开：agent 侧携带的 tools 与 ToolShim 协议段直接丢弃，只把用户原话发给码道
     * （DSH 等 agent 自己执行工具，码道侧无需知道其工具清单）。
     * 关：把 tools 转给码道（注入 ToolShim 协议段，让码道模型学着调用）。
     */
    public static volatile boolean sDropToolProtocol = true;

    /**
     * 网关全局的「只发新消息」开关（默认开；构造时从 Store 同步）。
     * 开：openaiToQueryText 只提取最后一条用户原话，不转发之前轮次的历史；
     * 码道侧每次都是独立上下文，不会累积 agent 侧的历史。
     * 关：转发完整历史（1.9.9 行为）。
     */
    public static volatile boolean sOnlyLastUser = true;

    /** 工具/MCP 配置状态（密钥只报长度，不回明文）。 */
    private JSONObject toolStateJson() {
        JSONObject t = new JSONObject();
        try {
            String base = store.getToolBase();
            t.put("tool_base", base);
            t.put("tool_key_len", store.getToolKey().length());
            t.put("tool_model", store.getToolModel());
            t.put("mcp_enabled", store.isMcpEnabled());
            t.put("allow_mutating", store.isAllowMutating());
            t.put("tool_path_active", ToolUpstream.configured(base));
            JSONArray arr = new JSONArray();
            for (McpHub.Server s : mcpServers()) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("tools", s.tools.size());
                o.put("error", s.lastError == null ? "" : s.lastError);
                arr.put(o);
            }
            t.put("mcp_servers", arr);
        } catch (Throwable ignore) {
            // 忽略
        }
        return t;
    }

    /** POST /admin/config：直接写入工具/MCP 配置（本机回环）。 */
    private void handleAdminConfig(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        if (!allowRemote(req, res)) {
            return;
        }
        String raw = readBody(req);
        JSONObject b;
        try {
            b = new JSONObject(raw);
        } catch (Throwable t) {
            json(res, 400, ProtocolAdapter.error("请求体不是合法 JSON", "invalid_request_error", null));
            return;
        }
        try {
            if (b.has("tool_base")) {
                store.setToolBase(b.optString("tool_base", ""));
            }
            if (b.has("tool_key")) {
                store.setToolKey(b.optString("tool_key", ""));
            }
            if (b.has("tool_model")) {
                store.setToolModel(b.optString("tool_model", ""));
            }
            if (b.has("mcp_servers")) {
                String mcp = b.optString("mcp_servers", "");
                if (mcp.trim().startsWith("[")) {
                    store.setMcpServers(mcp);
                }
            }
            if (b.has("mcp_enabled")) {
                store.setMcpEnabled(b.optBoolean("mcp_enabled", true));
            }
            if (b.has("allow_mutating")) {
                store.setAllowMutating(b.optBoolean("allow_mutating", false));
            }
            // 配置变了：强制重建 MCP 服务器
            synchronized (this) {
                mcpCfgSig = "\u0000";
            }
            LogRing.log("admin", "工具配置已更新: base=" + store.getToolBase()
                    + " key_len=" + store.getToolKey().length()
                    + " model=" + store.getToolModel()
                    + " mcp=" + store.isMcpEnabled());
        } catch (Throwable t) {
            json(res, 500, ProtocolAdapter.error(String.valueOf(t), "internal_error", null));
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("build", BUILD_TAG);
            o.put("tool", toolStateJson());
        } catch (Throwable ignore) {
            // 忽略
        }
        json(res, 200, o.toString());
    }

    /** GET /admin/selftest：MCP 连通性 + 工具上游连通性 + 一次只读工具真实调用。 */
    private void handleAdminSelfTest(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        if (!allowRemote(req, res)) {
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("build", BUILD_TAG);
            JSONArray servers = new JSONArray();
            int total = 0;
            for (McpHub.Server s : mcpServers()) {
                JSONObject one = new JSONObject();
                one.put("name", s.name);
                one.put("url", s.url);
                try {
                    McpHub.refresh(s, 30000, true);
                    one.put("ok", true);
                    one.put("tools", s.tools.size());
                    JSONArray sample = new JSONArray();
                    for (McpHub.Tool t : s.tools) {
                        if (sample.length() >= 6) {
                            break;
                        }
                        sample.put(t.name);
                    }
                    one.put("sample", sample);
                    total += s.tools.size();
                } catch (Throwable t) {
                    one.put("ok", false);
                    one.put("error", DevCloudClient.trim(String.valueOf(t), 200));
                }
                servers.put(one);
            }
            o.put("mcp", servers);
            o.put("mcpToolTotal", total);
            // 工具上游
            try {
                JSONArray m = ToolUpstream.models(store.getToolBase(), store.getToolKey(), 20000);
                o.put("upstream_ok", m != null);
                o.put("upstream_models", m == null ? 0 : m.length());
                JSONArray ids = new JSONArray();
                if (m != null) {
                    for (int i = 0; i < m.length() && i < 8; i++) {
                        JSONObject mm = m.optJSONObject(i);
                        if (mm != null) {
                            ids.put(mm.optString("id", ""));
                        }
                    }
                }
                o.put("upstream_model_ids", ids);
            } catch (Throwable t) {
                o.put("upstream_ok", false);
                o.put("upstream_error", DevCloudClient.trim(String.valueOf(t), 200));
            }
            // 一次真实只读工具调用（若服务器里有 mt_file_list）
            try {
                for (McpHub.Server s : mcpServers()) {
                    for (McpHub.Tool t : s.tools) {
                        if ("mt_file_list".equals(t.name)) {
                            String r = McpHub.call(s, t.name,
                                    "{\"path\":\"\",\"recursive\":false,\"cursor\":\"\",\"limit\":5}", 30000);
                            o.put("probe_tool", t.name);
                            o.put("probe_result", DevCloudClient.trim(r, 500));
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                o.put("probe_error", DevCloudClient.trim(String.valueOf(t), 200));
            }
        } catch (Throwable ignore) {
            // 忽略
        }
        json(res, 200, o.toString());
    }

    /**
     * 内部工具路径（v1.9.5）：「码道 + ToolShim 垫片」当模型，MCP 工具由网关执行。
     *
     * <p>循环：① 把客户端 tools + MCP 只读工具渲染成工具协议，随 query 发给码道；
     * ② 哨兵解析模型输出；③ 命中 MCP 工具 → McpHub 执行 → 结果以 "[工具结果]" 回灌 → 回到①
     * （最多 {@code maxRounds} 轮）；④ 命中客户端自带工具 → 标准 tool_calls 回传给客户端执行。
     */
    private void handleInternalToolPath(AsyncHttpServerResponse res, JSONObject openai,
                                        String model, boolean stream, long n, String cookie,
                                        boolean toolsDeclared, boolean mcpOn,
                                        List<String> clientNames) {
        final int maxRounds = 6;
        final long t0 = System.currentTimeMillis();
        // 流式时先发响应头 + role 帧：之后循环期间每次心跳都写注释行，
        // 第三方 App 从第一秒起就看到"活着的 SSE"，不会误判连接超时（v1.9.9）
        final SseWriter sseClient = stream ? new SseWriter(res.getSocket()) : null;
        // 整个内部工具循环共用一个下游 completion id：客户端（DSH）按同一 id 把
        // 所有 delta 帧拼成一次响应；中途换 id 会把思考流切断、块被拆开渲染（v1.9.10）
        final String cid = ProtocolAdapter.newCompletionId();
        if (sseClient != null) {
            sseClient.data(ProtocolAdapter.chunk(cid, model,
                    "{\"role\":\"assistant\",\"content\":\"\"}", null));
        }
        final java.util.Set<String> clientSet = new java.util.HashSet<>(clientNames);
        List<String> blocked = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<McpHub.Exposed> mcpTools = mcpOn ? exposedMcpTools(clientSet, blocked, problems)
                : new ArrayList<McpHub.Exposed>();
        Map<String, McpHub.Exposed> byName = new java.util.HashMap<>();
        for (McpHub.Exposed e : mcpTools) {
            byName.put(e.name, e);
        }
        // 历史拍平：assistant.tool_calls / role=tool 都渲染成协议文本，模型看得见之前的调用与结果
        StringBuilder query = new StringBuilder(openaiToQueryText(openai));
        final StringBuilder lastQuery = new StringBuilder();
        JSONArray clientTools = openai.optJSONArray("tools");
        StringBuilder proto = new StringBuilder();
        if (toolsDeclared && clientTools != null) {
            JSONObject fake = new JSONObject();
            try {
                fake.put("tools", clientTools);
                proto.append(ToolShim.buildInstruction(fake));
            } catch (Throwable ignore) {
                // 忽略
            }
        }
        if (mcpOn && !mcpTools.isEmpty()) {
            if (proto.length() > 0) {
                proto.append('\n');
            }
            proto.append("【MCP 工具】由运行环境自动注册，调用方式与上述协议相同：\n");
            for (McpHub.Exposed e : mcpTools) {
                try {
                    JSONObject fn = new JSONObject();
                    fn.put("name", e.name);
                    fn.put("description", DevCloudClient.trim(e.tool.description, 400));
                    fn.put("parameters", new JSONObject(e.tool.schema));
                    proto.append(fn.toString()).append('\n');
                } catch (Throwable ignore) {
                    // 忽略
                }
            }
        }
        if (proto.length() > 0) {
            // few-shot 示例（v1.9.7）：码道这类 agent 模型不看示例就会"演"自己的工具
            // （tool_search/tool_describe/长篇检测报告）。示例是最有效的服从手段。
            proto.append('\n')
                    .append("【示例】\n")
                    .append("用户：列出 gongzuo 目录里的文件\n")
                    .append("正确回应：\n")
                    .append(ToolShim.START)
                    .append("{\"name\":\"mt_file_list\",\"arguments\":{\"path\":\"\",\"recursive\":false,\"cursor\":\"\",\"limit\":200}}")
                    .append(ToolShim.END).append('\n')
                    .append("（然后停止输出；收到 [工具结果] 后再给最终答案）\n")
                    .append("错误示范（禁止）：输出“让我检测 MCP 连接状态…”或调用本环境自带的")
                    .append(" tool_search/tool_describe/bash —— 这些工具在本会话不可用，")
                    .append("唯一可用工具就是上面列出的。收到 [工具结果] 后必须直接给出最终答案，")
                    .append("禁止再描述检测过程。\n");
        }

        List<ToolShim.Call> clientCalls = new ArrayList<>();
        List<JSONObject> mcpTrace = new ArrayList<>();
        StringBuilder answerAll = new StringBuilder();
        // v1.9.10 修正：MCP 多轮循环里每轮的思考即时下发（流式）或带轮次标记累计（同步），
        // 不再把多轮思考裸拼成一坨
        final StringBuilder thinkingForSync = new StringBuilder();
        String error = null;
        int rounds = 0;
        try {
            final String sessionKey = sessionKey(null, openai);
            String sid = ensureSession(cookie, sessionKey, model, n);
            // v1.9.10：上一轮码道停在 ask_user 提问处等答案。本轮 query 里夹带
            // 「原提问 JSON + 用户新消息」回灌，码道才知道该按哪个选项继续。
            // 只在首轮回灌一次，回灌后清掉（避免下轮重复带旧提问）
            String pendingQ = pendingUpstreamQuestions.remove(sessionKey);
            if (pendingQ != null && pendingQ.length() > 0 && !openai.optBoolean("new_chat", false)) {
                String userMsg = query.toString();
                query.setLength(0);
                query.append(buildPendingContext(pendingQ, userMsg));
                LogRing.log("req#" + n, "回灌上轮码道提问: " + DevCloudClient.trim(pendingQ, 120));
            }
            while (rounds <= maxRounds) {
                String content = query.toString()
                        + (proto.length() > 0 ? "\n\n" + proto : "");
                lastQuery.setLength(0);
                lastQuery.append(content);
                StringBuilder turnOut = new StringBuilder();
                ToolShim.Stream shim = new ToolShim.Stream(clientNames);
                final String sidF = sid;
                final StringBuilder thinking = new StringBuilder();
                final int roundNo = rounds + 1;
                // 本轮思考是否已发过轮次头（避免连续 delta 帧重复打头）
                final boolean[] thinkingEmitted = {false};
                final String[] upErr = new String[1];
                DevCloudClient.streamChat(cookie, sidF, content, model, new DevCloudClient.Sink() {
                    @Override
                    public void onDelta(String text) {
                        turnOut.append(shim.feed(text));
                    }

                    @Override
                    public void onThinking(String text) {
                        // v1.9.10 修正：思考即时下发。所有 reasoning delta 帧共用
                        // 同一个 cid（OpenAI SSE 里 id 是「一次响应的标识」，中途换 id
                        // 会让 DSH 把流切开、块被拆开渲染——这就是思考被合并的根因）。
                        // 轮次分隔用帧内标记实现（客户端在流内看到 ── 第N轮思考 ── 即分块）
                        thinking.append(text);
                        if (sseClient != null && sStreamThinking) {
                            sseClient.data(ProtocolAdapter.chunk(cid, model,
                                    reasoningDeltaJson(
                                            (thinkingEmitted[0] ? "" : "── 第" + roundNo + "轮思考 ──\n")
                                            + text),
                                    null));
                            thinkingEmitted[0] = true;
                        }
                    }

                    @Override
                    public void onEvent(String eventName, JSONObject raw) {
                        // v1.9.10：码道提问/确认类事件转发为可见 content 帧；
                        // 提问文本挂到 query 尾部，下一轮回灌时模型知道"用户问了什么"
                        forwardUpstreamQuestion(eventName, raw, sseClient, model, n, query,
                                sessionKey, stream);
                    }

                    @Override
                    public void heartbeat() {
                        // 内部路径多轮循环每轮都可能耗时很长，靠心跳对冲第三方 App 的超时判定
                        if (sseClient != null) {
                            sseClient.comment("keep-alive");
                        }
                    }

                    @Override
                    public void onDone(boolean ok, String err) {
                        // 不能在异步回调里直接 throw（会静默丢到没人接的线程），
                        // 用标志位带回去（v1.9.5 的坑）
                        if (!ok && err != null) {
                            upErr[0] = err;
                        }
                    }
                });
                if (upErr[0] != null) {
                    // 上游断了/会话废了：标记会话失败（下轮请求废弃重建），
                    // 并给客户端一个明确的 SSE 错误帧，而不是让它干等到超时
                    markSessionFailed(sessionKey);
                    LogRing.log("req#" + n, "上游会话失败: " + upErr[0]);
                    if (stream) {
                        final com.koushikdutta.async.AsyncSocket sk = res.getSocket();
                        if (sk != null) {
                            SseWriter es = new SseWriter(sk);
                            es.data(ProtocolAdapter.error("码道上游连接中断（已自动重建会话，请重发本条消息）: "
                                    + upErr[0], "upstream_error", null));
                            es.data("[DONE]");
                            es.close();
                        }
                    } else {
                        json(res, 502, ProtocolAdapter.error(
                                "码道上游连接中断（已自动重建会话，请重发本条消息）: " + upErr[0],
                                "upstream_error", null));
                    }
                    try {
                        JSONObject tr = new JSONObject();
                        tr.put("ts", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                                .format(new java.util.Date()));
                        tr.put("n", n);
                        tr.put("build", BUILD_TAG);
                        tr.put("path", "tool(internal)");
                        tr.put("rounds", rounds);
                        tr.put("error", "upstream: " + upErr[0]);
                        recordTrace(tr);
                    } catch (Throwable ignore) {
                        // 忽略
                    }
                    return;
                }
                String tail = shim.flush();
                if (tail != null && !tail.isEmpty()) {
                    turnOut.append(tail);
                }
                List<ToolShim.Call> calls = new ArrayList<>(shim.drain());
                rounds++;
                // 同步路径：每轮思考带轮次标记累计（流式已在 onThinking 即时下发）
                if (thinking.length() > 0) {
                    thinkingForSync.append("── 第").append(rounds).append("轮思考 ──\n")
                            .append(thinking).append('\n');
                }
                LogRing.log("req#" + n, "内部工具循环 第" + rounds + "轮: 输出 " + turnOut.length()
                        + " 字, 调用 " + calls.size() + " 个, 思考 " + thinking.length() + " 字");
                if (calls.isEmpty()) {
                    answerAll.append(turnOut);
                    break;
                }
                boolean hasClientCall = false;
                for (ToolShim.Call c : calls) {
                    if (clientSet.contains(c.name) && !byName.containsKey(c.name)) {
                        hasClientCall = true;
                        break;
                    }
                }
                if (hasClientCall) {
                    // 客户端自带工具 → 标准 tool_calls 交给客户端
                    answerAll.append(turnOut);
                    clientCalls = calls;
                    break;
                }
                // 全是 MCP/网关注册的工具 → 执行并回灌
                StringBuilder results = new StringBuilder();
                for (ToolShim.Call c : calls) {
                    McpHub.Exposed e = byName.get(c.name);
                    String result;
                    long ms0 = System.currentTimeMillis();
                    if (e == null) {
                        result = "错误：工具 " + c.name + " 未注册（不在 MCP 列表，也不属于客户端）";
                    } else {
                        try {
                            result = McpHub.call(e.server, e.tool.name, c.arguments, 120000);
                        } catch (Throwable t) {
                            result = "工具执行失败: " + DevCloudClient.trim(String.valueOf(t), 300);
                        }
                    }
                    results.append("[工具结果] ").append(c.name).append(": ").append(result).append('\n');
                    JSONObject tr = new JSONObject();
                    try {
                        tr.put("tool", e == null ? c.name : e.tool.name);
                        tr.put("args", c.arguments);
                        tr.put("ms", System.currentTimeMillis() - ms0);
                        tr.put("result", DevCloudClient.trim(result, 400));
                    } catch (Throwable ignore) {
                        // 忽略
                    }
                    mcpTrace.add(tr);
                    LogRing.log("req#" + n, "MCP 执行 " + (e == null ? c.name : e.tool.name)
                            + " → " + DevCloudClient.trim(result, 100));
                }
                // 回灌：把"模型刚才的调用"与"工具结果"作为历史，继续下一轮
                query.append('\n').append(turnOut).append('\n').append(results)
                        .append("\n请根据以上工具结果继续；如果已经能回答，直接给出最终答案，不要再调用工具。");
                answerAll.append(turnOut);
                if (rounds >= maxRounds) {
                    answerAll.append("\n（已达最大工具调用轮数 ").append(maxRounds).append("，停止）");
                    break;
                }
            }
        } catch (Throwable t) {
            error = String.valueOf(t);
            LogRing.log("req#" + n, "内部工具路径失败: " + t);
        }
        long elapsed = System.currentTimeMillis() - t0;
        LogRing.log("req#" + n, "内部工具路径完成 轮数=" + rounds + " MCP调用=" + mcpTrace.size()
                + " 客户端调用=" + clientCalls.size() + " 拦截写入类=" + blocked.size()
                + " 耗时=" + elapsed + "ms" + (error == null ? "" : " 错误=" + error));
        String contentOut = answerAll.toString();
        try {
            JSONObject trace = new JSONObject();
            trace.put("ts", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date()));
            trace.put("n", n);
            trace.put("build", BUILD_TAG);
            trace.put("path", "tool(internal)");
            trace.put("mode", stream ? "stream" : "sync");
            trace.put("toolsDeclared", toolsDeclared);
            trace.put("declared", new JSONArray(clientNames));
            trace.put("mcpEnabled", mcpOn);
            trace.put("mcpTools", mcpTools.size());
            trace.put("blockedMutating", new JSONArray(blocked));
            trace.put("mcpProblems", new JSONArray(problems));
            trace.put("rounds", rounds);
            // 发给上游的最终 query（协议段在里面），排障必需
            trace.put("query", DevCloudClient.trim(lastQuery.toString(), 6000));
            JSONArray cs = new JSONArray();
            for (JSONObject tr : mcpTrace) {
                cs.put(tr);
            }
            trace.put("mcpCalls", cs);
            trace.put("clientCalls", clientCalls.size());
            trace.put("content", DevCloudClient.trim(contentOut, 3000));
            trace.put("elapsedMs", elapsed);
            if (error != null) {
                trace.put("error", error);
            }
            recordTrace(trace);
        } catch (Throwable ignore) {
            // 忽略
        }
        if (error != null) {
            json(res, 502, ProtocolAdapter.error("内部工具链路失败: " + error, "upstream_error", null));
            return;
        }
        // 同步路径：用带轮次标记的累计版（thinkingForSync）；流式已即时下发，不再重发
        String reasoningOut = thinkingForSync.length() > 0 ? thinkingForSync.toString() : null;
        if (!stream) {
            json(res, 200, toolCompletion(cid, model, contentOut, toUpCalls(clientCalls), reasoningOut));
            return;
        }
        if (sseClient == null) {
            json(res, 500, ProtocolAdapter.error("流式句柄缺失", "internal_error", null));
            return;
        }
        // 流式路径：思考已在 onThinking 里逐轮即时下发，此处只补最终 content / tool_calls / 收尾
        if (!contentOut.isEmpty()) {
            sseClient.data(ProtocolAdapter.chunk(cid, model, deltaJson(contentOut), null));
        }
        int idx = 0;
        for (ToolUpstream.Call c : toUpCalls(clientCalls)) {
            sseClient.data(ToolShim.toolCallStartChunk(cid, model, idx, c.id, c.name, c.arguments));
            sseClient.data(ToolShim.toolCallArgsChunk(cid, model, idx, c.arguments));
            idx++;
        }
        sseClient.data(ProtocolAdapter.chunk(cid, model, "{}",
                clientCalls.isEmpty() ? "stop" : "tool_calls"));
        sseClient.data("[DONE]");
        sseClient.close();
    }

    /**
     * 上游提问/确认事件（ask/confirm/question/clarif 类）转发给客户端。
     * v1.9.10：码道可能以事件形式向用户提问并停住等答案；网关把它转成
     * 可见 content 帧（带 [码道提问] 前缀），agent 能看见并回复，避免干等卡死。
     *
     * @param queryAppend 回灌用的 query（可为 null，仅流式转发）
     * @return 提问 JSON 文本；非提问事件返回 null
     */
    private String forwardUpstreamQuestion(String eventName, JSONObject raw,
                                           SseWriter sse, String model, long n,
                                           StringBuilder queryAppend,
                                           String sessionKey, boolean stream) {
        if (raw == null || eventName == null) {
            return null;
        }
        if (!isAskEvent(eventName)) {
            return null;
        }
        String q = raw.toString(); // 完整提问 JSON（含 question_id / options）原样带给客户端与下一轮回灌
        LogRing.log("req#" + n, "上游提问事件 " + eventName + " → 转发给客户端: "
                + DevCloudClient.trim(q, 200));
        if (sse != null && stream) {
            sse.data(ProtocolAdapter.chunk(ProtocolAdapter.newCompletionId(), model,
                    deltaJson("\n\n[码道提问] " + q + "\n（请在你的下一条回复中直接给出答案，码道会据此继续）\n"), null));
        }
        if (sessionKey != null) {
            // 存到会话级 pending，下一轮请求进来时和"用户新回复"一起回灌给码道
            pendingUpstreamQuestions.put(sessionKey, q);
        }
        if (queryAppend != null) {
            queryAppend.append("\n[码道提问] ").append(q);
        }
        return q;
    }

    /** 上游事件是否为提问/确认类。 */
    private static boolean isAskEvent(String eventName) {
        if (eventName == null) {
            return false;
        }
        String low = eventName.toLowerCase();
        return low.contains("ask") || low.contains("confirm")
                || low.contains("question") || low.contains("clarif");
    }

    /**
     * 从客户端声明的 tools 里找「提问类工具」名。命中后即可把码道提问转成标准
     * tool_calls，让 Claude Code / codex / DSH 等 agent 弹出交互式选项，
     * 而不是只看到一段 {@code [码道提问]} 文本。
     */
    static String findAskToolName(java.util.List<String> declared) {
        if (declared == null || declared.isEmpty()) {
            return null;
        }
        String[] common = {"AskUserQuestion", "ask_user_question", "ask_user",
                "request_user_input", "ask_question", "AskUserQuestionTool"};
        for (String c : common) {
            for (String d : declared) {
                if (c.equalsIgnoreCase(d)) {
                    return d;
                }
            }
        }
        for (String d : declared) {
            if (d == null) {
                continue;
            }
            String l = d.toLowerCase();
            if (l.contains("ask") || l.contains("question")) {
                return d;
            }
        }
        return null;
    }

    /** 提问工具规格：工具名 + arguments 形态 + schema 要求的必填 id 字段。 */
    static final class AskSpec {
        final String name;
        final boolean arrayStyle;
        final boolean needsQuestionId;
        final boolean needsOptionId;

        AskSpec(String name, boolean arrayStyle, boolean needsQuestionId, boolean needsOptionId) {
            this.name = name;
            this.arrayStyle = arrayStyle;
            this.needsQuestionId = needsQuestionId;
            this.needsOptionId = needsOptionId;
        }
    }

    /**
     * 依据客户端 tools 里的 schema 决定提问参数形态与必填字段：
     * Claude Code 的 AskUserQuestion 用 {@code {questions:[{question,header,options}]}}；
     * DSH 同为数组式但要求每项带 {@code id}（否则报 missing required property
     * {@code "questions[0].id"}）。
     */
    static AskSpec resolveAskSpec(JSONObject openai, java.util.List<String> declared) {
        String name = findAskToolName(declared);
        if (name == null) {
            return null;
        }
        boolean claude = name.toLowerCase(java.util.Locale.ROOT).contains("askuserquestion");
        boolean arrayStyle = claude;
        boolean needsQuestionId = false;
        boolean needsOptionId = false;
        boolean schemaKnown = false;
        try {
            JSONArray tools = openai == null ? null : openai.optJSONArray("tools");
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject t = tools.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    JSONObject f = t.optJSONObject("function");
                    String n = f != null ? f.optString("name", "") : t.optString("name", "");
                    if (!name.equals(n)) {
                        continue;
                    }
                    JSONObject params = f != null ? f.optJSONObject("parameters")
                            : t.optJSONObject("input_schema");
                    JSONObject props = params == null ? null : params.optJSONObject("properties");
                    if (props != null) {
                        arrayStyle = props.has("questions");
                        JSONObject qProp = props.optJSONObject("questions");
                        JSONObject items = qProp == null ? null : qProp.optJSONObject("items");
                        if (items != null) {
                            schemaKnown = true;
                            needsQuestionId = requiredContains(items, "id");
                            JSONObject innerProps = items.optJSONObject("properties");
                            JSONObject optProp = innerProps == null ? null
                                    : innerProps.optJSONObject("options");
                            JSONObject optItems = optProp == null ? null
                                    : optProp.optJSONObject("items");
                            if (optItems != null) {
                                needsOptionId = requiredContains(optItems, "id");
                            }
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignore) {
            // 解析失败用名字兜底
        }
        // schema 不可见时：非 Claude 的数组式提问工具按 DSH 约定补 id
        if (!schemaKnown && !claude && arrayStyle) {
            needsQuestionId = true;
        }
        return new AskSpec(name, arrayStyle, needsQuestionId, needsOptionId);
    }

    /** schema 的 required 数组是否包含某字段。 */
    private static boolean requiredContains(JSONObject schema, String field) {
        JSONArray req = schema == null ? null : schema.optJSONArray("required");
        if (req == null) {
            return false;
        }
        for (int i = 0; i < req.length(); i++) {
            if (field.equals(req.optString(i))) {
                return true;
            }
        }
        return false;
    }

    /** 旧重载：默认数组式（Claude Code 形态）。 */
    static String buildAskArguments(JSONObject raw) {
        return buildAskArguments(null, raw);
    }

    /** 按工具 schema 形态构造提问 arguments。 */
    static String buildAskArguments(AskSpec spec, JSONObject raw) {
        JSONArray qs = extractQuestions(raw);
        try {
            JSONObject args = new JSONObject();
            if (spec == null || spec.arrayStyle) {
                if (spec != null && (spec.needsQuestionId || spec.needsOptionId)) {
                    ensureIds(qs, spec.needsQuestionId, spec.needsOptionId);
                }
                args.put("questions", qs);
                return args.toString();
            }
            // 单问形态：取第一问的 question/header/options/multiple
            JSONObject q0 = qs.optJSONObject(0);
            if (q0 == null) {
                args.put("question", "");
                return args.toString();
            }
            args.put("question", q0.optString("question", ""));
            if (q0.has("header")) {
                args.put("header", q0.optString("header", ""));
            }
            if (q0.has("options")) {
                args.put("options", q0.optJSONArray("options"));
            }
            args.put("multiple", q0.optBoolean("multiple", false));
            return args.toString();
        } catch (Throwable t) {
            return spec == null || spec.arrayStyle ? "{\"questions\":[]}" : "{\"question\":\"\"}";
        }
    }

    /** 统一取出 questions 数组；单问形态则包成一条。 */
    private static JSONArray extractQuestions(JSONObject raw) {
        JSONArray qs = raw == null ? null : raw.optJSONArray("questions");
        if (qs != null && qs.length() > 0) {
            return qs;
        }
        try {
            JSONObject q = new JSONObject();
            String text = raw == null ? "" : raw.optString("question",
                    raw.optString("prompt", raw.optString("text", raw.optString("message", ""))));
            q.put("question", text);
            if (raw != null && raw.has("header")) {
                q.put("header", raw.optString("header", ""));
            }
            if (raw != null && raw.has("options")) {
                q.put("options", raw.optJSONArray("options"));
            }
            if (raw != null && raw.has("multiple")) {
                q.put("multiple", raw.optBoolean("multiple", false));
            }
            JSONArray arr = new JSONArray();
            arr.put(q);
            return arr;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    /** 按 schema 要求补齐 questions[i].id / options[j].id（DSH 等工具会严格校验）。 */
    private static void ensureIds(JSONArray qs, boolean questionId, boolean optionId) {
        if (qs == null) {
            return;
        }
        try {
            for (int i = 0; i < qs.length(); i++) {
                JSONObject q = qs.optJSONObject(i);
                if (q == null) {
                    continue;
                }
                if (questionId && q.optString("id", "").isEmpty()) {
                    q.put("id", "q" + (i + 1));
                }
                if (optionId) {
                    JSONArray opts = q.optJSONArray("options");
                    if (opts != null) {
                        for (int j = 0; j < opts.length(); j++) {
                            JSONObject o = opts.optJSONObject(j);
                            if (o != null && o.optString("id", "").isEmpty()) {
                                o.put("id", "q" + (i + 1) + "o" + (j + 1));
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
            // 补 id 失败不影响主流程（交由客户端校验兜底）
        }
    }

    private static ToolUpstream.Call toUpCall(ToolShim.Call c) {
        return new ToolUpstream.Call(c.id, c.name, c.arguments);
    }

    private static List<ToolUpstream.Call> toUpCalls(List<ToolShim.Call> calls) {
        List<ToolUpstream.Call> out = new ArrayList<>();
        if (calls != null) {
            for (ToolShim.Call c : calls) {
                out.add(toUpCall(c));
            }
        }
        return out;
    }

    /** 复用/新建上游会话；坏会话（上游掐断过）自动废弃重建。 */
    /** 网关会话级：码道最近一次 ask_user 提问的完整 JSON。v1.9.10 新增：
     *  下一轮请求进来时把「用户回复 + 原提问」一起回灌给码道，码道才知道该按哪个选项继续。
     *  key=网关 sessionKey（即 devCloudSessions 的 key） */
    private final Map<String, String> pendingUpstreamQuestions = new ConcurrentHashMap<>();

    private String ensureSession(String cookie, String sessionKey, String model, long n)
            throws Exception {
        String sid = devCloudSessions.get(sessionKey);
        Long failedAt = devCloudSessionFailed.get(sessionKey);
        if (sid != null && failedAt != null) {
            // 上次在这个会话上出过错（连接被掐/HTTP 错误）：废弃它，重建新会话
            LogRing.log("req#" + n, "会话 " + sessionKey + " 上次失败(" + failedAt
                    + ")，废弃旧上游会话 " + sid + " 并重建");
            devCloudSessions.remove(sessionKey);
            devCloudSessionFailed.remove(sessionKey);
            sid = null;
        }
        if (sid == null || sid.isEmpty()) {
            sid = DevCloudClient.createSession(cookie, model);
            devCloudSessions.put(sessionKey, sid);
            LogRing.log("req#" + n, "DevCloud 新会话 " + sid);
        }
        return sid;
    }

    /** 标记会话失败（下次请求时废弃重建）。 */
    private void markSessionFailed(String sessionKey) {
        devCloudSessionFailed.put(sessionKey, System.currentTimeMillis());
    }

    /**
     * 非流式对话：上游只有 SSE，这里在网关侧聚合后一次性返回。
     * 响应统一过垫片解析（有哨兵就产出标准 tool_calls，没有则原样文本）。
     */
    private void handleChatSync(AsyncHttpServerResponse res, String cookie, String content,
                                String model, String sessionKey, long n, boolean toolsDeclared,
                                java.util.List<String> declaredNames, AskSpec askSpec) {
        final StringBuilder answer = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final JSONObject trace = new JSONObject();
        // v1.9.10：非流式路径无法分帧，码道提问先累积，onDone 时拼进响应正文
        final StringBuilder question = new StringBuilder();
        final java.util.List<ToolShim.Call> askCalls = new java.util.ArrayList<>();
        final boolean[] finished = {false};
        final long t0 = System.currentTimeMillis();
        try {
            trace.put("ts", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                    .format(new java.util.Date()));
            trace.put("n", n);
            trace.put("build", BUILD_TAG);
            trace.put("mode", "sync");
            trace.put("toolsDeclared", toolsDeclared);
            trace.put("declared", new JSONArray(declaredNames));
            trace.put("query", DevCloudClient.trim(content, 4000));
            final String sid = ensureSession(cookie, sessionKey, model, n);
            trace.put("upstreamSession", sid);
            DevCloudClient.streamChat(cookie, sid, content, model, new DevCloudClient.Sink() {
                @Override
                public void onDelta(String text) {
                    answer.append(text);
                }

                @Override
                public void onThinking(String text) {
                    // v1.9.10：非流式路径也把思考过程累积下来，随响应里的
                    // reasoning_content 字段透出（客户端若不关心该字段会被直接忽略）
                    thinking.append(text);
                }

                @Override
                public void onEvent(String eventName, JSONObject raw) {
                    // 有提问类工具 → 转成标准 tool_calls；否则回退为正文文本
                    if (askSpec != null && isAskEvent(eventName) && raw != null) {
                        String args = buildAskArguments(askSpec, raw);
                        askCalls.add(new ToolShim.Call(
                                raw.optString("call_id", ToolShim.newCallId()),
                                askSpec.name, args));
                        if (sessionKey != null) {
                            pendingUpstreamQuestions.put(sessionKey, raw.toString());
                        }
                        LogRing.log("req#" + n, "码道提问 → tool_calls(sync): " + askSpec.name
                                + " args=" + DevCloudClient.trim(args, 160));
                        // 非流式：码道提问后同样不会主动结束，立即返回完整报文
                        finished[0] = true;
                        json(res, 200, ToolShim.completion(
                                ProtocolAdapter.newCompletionId(), model, "",
                                new java.util.ArrayList<>(askCalls),
                                thinking.length() > 0 ? thinking.toString() : null));
                        return;
                    }
                    String q = forwardUpstreamQuestion(eventName, raw, null, model, n, null,
                            sessionKey, false);
                    if (q != null) {
                        question.append("\n\n[码道提问] ").append(q)
                                .append("\n（请在你的下一条回复中直接给出答案，码道会据此继续）\n");
                    }
                }

                @Override
                public void onDone(boolean ok, String error) {
                    if (finished[0]) {
                        LogRing.log("req#" + n, "提问已响应(sync)，忽略上游后续 onDone");
                        return;
                    }
                    try {
                        trace.put("upstreamText", DevCloudClient.trim(answer.toString(), 4000));
                        trace.put("thinkingLen", thinking.length());
                        trace.put("elapsedMs", System.currentTimeMillis() - t0);
                        trace.put("ok", ok);
                        if (error != null) {
                            trace.put("error", error);
                        }
                    } catch (Throwable ignore) {
                        // 忽略
                    }
                    if (!ok && error != null) {
                        recordTrace(trace);
                        json(res, 502, ProtocolAdapter.error(error, "upstream_error", null));
                        return;
                    }
                    String id = ProtocolAdapter.newCompletionId();
                    ToolShim.Parsed p = ToolShim.parse(answer.toString(), declaredNames);
                    java.util.List<ToolShim.Call> allCalls = new java.util.ArrayList<>(p.calls);
                    allCalls.addAll(askCalls);
                    try {
                        JSONArray cs = new JSONArray();
                        for (ToolShim.Call c : allCalls) {
                            cs.put(c.name + " " + c.arguments);
                        }
                        trace.put("toolCalls", cs);
                        trace.put("finish", allCalls.isEmpty() ? "stop" : "tool_calls");
                    } catch (Throwable ignore) {
                        // 忽略
                    }
                    recordTrace(trace);
                    LogRing.log("req#" + n, "工具垫片(sync)：文本 " + p.content.length()
                            + " 字，调用 " + allCalls.size() + " 个，思考 " + thinking.length() + " 字");
                    String outContent = question.length() > 0
                            ? p.content + question.toString() : p.content;
                    json(res, 200, ToolShim.completion(id, model, outContent, allCalls,
                            thinking.length() > 0 ? thinking.toString() : null));
                    LogRing.log("req#" + n, "DevCloud 完成(sync)，输出 " + answer.length() + " 字");
                }
            });
        } catch (Throwable t) {
            LogRing.log("req#" + n, "DevCloud 失败(sync): " + t);
            try {
                trace.put("error", String.valueOf(t));
                recordTrace(trace);
            } catch (Throwable ignore) {
                // 忽略
            }
            json(res, 500, ProtocolAdapter.error(String.valueOf(t), "upstream_error", null));
        }
    }

    private void handleChatStream(AsyncHttpServerResponse res, String cookie, String content,
                                  String model, String sessionKey, long n, boolean toolsDeclared,
                                  java.util.List<String> declaredNames, AskSpec askSpec) {
        final com.koushikdutta.async.AsyncSocket sock = res.getSocket();
        if (sock == null) {
            json(res, 500, ProtocolAdapter.error("网关无法获取连接句柄", "internal_error", null));
            return;
        }
        final SseWriter sse = new SseWriter(sock);
        final String id = ProtocolAdapter.newCompletionId();
        final StringBuilder answer = new StringBuilder();
        final StringBuilder thinking = new StringBuilder();
        final JSONObject trace = new JSONObject();
        final long t0 = System.currentTimeMillis();
        final boolean[] askSent = {false};
        // 提问转 tool_calls 后立即收尾：码道停在提问处不会主动结束流，
        // 必须由网关补 finish_reason + [DONE] 并关闭 SSE，agent 才会弹提问界面。
        final boolean[] ended = {false};
        final java.util.Set<String> seenEvents =
                java.util.Collections.synchronizedSet(new java.util.HashSet<String>());
        final Map<String, Integer> eventCounts = new java.util.concurrent.ConcurrentHashMap<>();
        sse.data(ProtocolAdapter.chunk(id, model, "{\"role\":\"assistant\",\"content\":\"\"}", null));

        try {
            try {
                trace.put("ts", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
                        .format(new java.util.Date()));
                trace.put("n", n);
                trace.put("build", BUILD_TAG);
                trace.put("mode", "stream");
                trace.put("toolsDeclared", toolsDeclared);
                trace.put("declared", new JSONArray(declaredNames));
                trace.put("query", DevCloudClient.trim(content, 4000));
            } catch (Throwable ignore) {
                // 忽略
            }
            final String sid = ensureSession(cookie, sessionKey, model, n);
            try {
                trace.put("upstreamSession", sid);
            } catch (Throwable ignore) {
                // 忽略
            }
            DevCloudClient.streamChat(cookie, sid, content, model, new DevCloudClient.Sink() {
                /** 工具垫片状态机：常开（哨兵解析），declaredNames 用于把错名对齐回真实工具名。 */
                private final ToolShim.Stream shim = new ToolShim.Stream(declaredNames);
                private ToolShim.Stream thinkingShim;
                private int callIndex = 0;
                private int callsSent = 0;

                private void sendToolCalls() {
                    for (ToolShim.Call c : shim.drain()) {
                        int idx = callIndex++;
                        sse.data(ToolShim.toolCallStartChunk(id, model, idx, c));
                        sse.data(ToolShim.toolCallArgsChunk(id, model, idx, c.arguments));
                        callsSent++;
                        LogRing.log("req#" + n, "工具调用 #" + idx + " " + c.name
                                + " args=" + DevCloudClient.trim(c.arguments, 120));
                    }
                    if (thinkingShim != null) {
                        for (ToolShim.Call c : thinkingShim.drain()) {
                            int idx = callIndex++;
                            sse.data(ToolShim.toolCallStartChunk(id, model, idx, c));
                            sse.data(ToolShim.toolCallArgsChunk(id, model, idx, c.arguments));
                            callsSent++;
                            LogRing.log("req#" + n, "工具调用 #" + idx + " " + c.name
                                    + "（来自思考流）args=" + DevCloudClient.trim(c.arguments, 120));
                        }
                    }
                }

                @Override
                public void onDelta(String text) {
                    if (ended[0]) {
                        return;
                    }
                    String out = shim.feed(text);
                    if (out != null && !out.isEmpty()) {
                        answer.append(out);
                        sse.data(ProtocolAdapter.chunk(id, model, deltaJson(out), null));
                    }
                    sendToolCalls();
                }

                @Override
                public void onThinking(String text) {
                    if (ended[0]) {
                        return;
                    }
                    // 思考型模型可能把调用块写进 reasoning_content，这里同样走哨兵解析
                    if (thinkingShim == null) {
                        thinkingShim = new ToolShim.Stream(declaredNames);
                    }
                    String out = thinkingShim.feed(text);
                    if (out != null && !out.isEmpty()) {
                        thinking.append(out);
                        if (sStreamThinking) {
                            // v1.9.10：受 Store.isStreamThinking 控制（默认开）
                            sse.data(ProtocolAdapter.chunk(id, model, reasoningDeltaJson(out), null));
                        }
                    }
                    sendToolCalls();
                }

                @Override
                public void onEvent(String eventName, JSONObject raw) {
                    if (ended[0]) {
                        return;
                    }
                    noteEvent(eventName, raw, n, seenEvents, eventCounts);
                    // 有提问类工具时，把码道提问转成标准 tool_calls，agent 才会弹出交互式选项；
                    // 没有对应工具则回退为可见文本（[码道提问] ...）
                    if (askSpec != null && isAskEvent(eventName) && raw != null) {
                        String args = buildAskArguments(askSpec, raw);
                        int idx = 900; // 避开 ToolShim 从 0 起的索引
                        sse.data(ToolShim.toolCallStartChunk(id, model, idx,
                                new ToolShim.Call(
                                        raw.optString("call_id", ToolShim.newCallId()),
                                        askSpec.name, args)));
                        sse.data(ToolShim.toolCallArgsChunk(id, model, idx, args));
                        askSent[0] = true;
                        if (sessionKey != null) {
                            pendingUpstreamQuestions.put(sessionKey, raw.toString());
                        }
                        LogRing.log("req#" + n, "码道提问 → tool_calls: " + askSpec.name
                                + " args=" + DevCloudClient.trim(args, 160));
                        // 立即收尾：码道停在提问处不会主动结束流，若等 onDone，
                        // agent 只会显示占位（"提问"），直到手动终止连接才渲染界面。
                        ended[0] = true;
                        sse.data(ProtocolAdapter.chunk(id, model, "{}", "tool_calls"));
                        sse.data("[DONE]");
                        sse.close();
                        return;
                    }
                    forwardUpstreamQuestion(eventName, raw, sse, model, n, null, sessionKey, true);
                }

                @Override
                public void heartbeat() {
                    if (ended[0]) {
                        return;
                    }
                    // 上游心跳 → SSE 注释行 keep-alive：第三方 App 不会误判连接超时（v1.9.9）
                    sse.comment("keep-alive");
                }

                @Override
                public void onDone(boolean ok, String error) {
                    if (ended[0]) {
                        LogRing.log("req#" + n, "提问已收尾，忽略上游后续 onDone");
                        return;
                    }
                    String tail = shim.flush();
                    if (tail != null && !tail.isEmpty()) {
                        answer.append(tail);
                        sse.data(ProtocolAdapter.chunk(id, model, deltaJson(tail), null));
                    }
                    if (thinkingShim != null) {
                        String tTail = thinkingShim.flush();
                        if (tTail != null && !tTail.isEmpty()) {
                            thinking.append(tTail);
                            if (sStreamThinking) {
                                sse.data(ProtocolAdapter.chunk(id, model, reasoningDeltaJson(tTail), null));
                            }
                        }
                    }
                    sendToolCalls();
                    if (!ok && error != null) {
                        sse.data(ProtocolAdapter.error(error, "upstream_error", null));
                    }
                    // 有工具调用时以 tool_calls 收尾，客户端据此进入"执行工具"分支
                    sse.data(ProtocolAdapter.chunk(id, model, "{}", (callsSent > 0 || askSent[0]) ? "tool_calls" : "stop"));
                    sse.data("[DONE]");
                    sse.close();
                    try {
                        trace.put("upstreamText", DevCloudClient.trim(answer.toString(), 4000));
                        trace.put("thinkingLen", thinking.length());
                        trace.put("elapsedMs", System.currentTimeMillis() - t0);
                        trace.put("ok", ok);
                        JSONArray cs = new JSONArray();
                        for (int i = 0; i < callsSent; i++) {
                            cs.put("call#" + i);
                        }
                        trace.put("toolCallCount", callsSent);
                        trace.put("finish", (callsSent > 0 || askSent[0]) ? "tool_calls" : "stop");
                        trace.put("events", new JSONObject(eventCounts));
                        if (error != null) {
                            trace.put("error", error);
                        }
                    } catch (Throwable ignore) {
                        // 忽略
                    }
                    recordTrace(trace);
                    LogRing.log("req#" + n, "DevCloud 完成，输出 " + answer.length() + " 字"
                            + (thinking.length() > 0 ? "（思考 " + thinking.length() + " 字）" : "")
                            + (callsSent > 0 ? "，工具调用 " + callsSent + " 个" : "")
                            + " 事件=" + eventCounts);
                }
            });
        } catch (Throwable t) {
            LogRing.log("req#" + n, "DevCloud 失败: " + t);
            try {
                trace.put("error", String.valueOf(t));
                recordTrace(trace);
            } catch (Throwable ignore) {
                // 忽略
            }
            sse.data(ProtocolAdapter.error(String.valueOf(t), "upstream_error", null));
            sse.data("[DONE]");
            sse.close();
        }
    }

    // ------------------------------------------------------------------ 工具

    private void submit(AsyncHttpServerResponse res, Runnable task) {
        try {
            pool.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    LogRing.log("gateway", "处理请求异常: " + t);
                    try {
                        json(res, 500, ProtocolAdapter.error(String.valueOf(t), "internal_error", null));
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            json(res, 503, ProtocolAdapter.error("网关繁忙", "internal_error", null));
        }
    }

    /**
     * 事件日志降噪：每种事件只在首次出现时打印字段明细，其余只计数，
     * 完成时汇总。否则单次对话会刷出上百行日志。
     */
    static void noteEvent(String eventName, JSONObject raw, long n,
                          java.util.Set<String> seen, java.util.Map<String, Integer> counts) {
        if (eventName == null || eventName.isEmpty()) {
            return;
        }
        Integer c = counts.get(eventName);
        counts.put(eventName, c == null ? 1 : c + 1);
        if (seen.add(eventName)) {
            LogRing.log("req#" + n, "首次事件 " + eventName
                    + " 字段=" + ProtocolAdapter.keysOf(raw));
        }
    }

    private static String deltaJson(String text) {
        return "{\"content\":" + JSONObject.quote(text) + "}";
    }

    /** 思考增量（DeepSeek/OpenAI 生态通用字段 reasoning_content）。 */
    private static String reasoningDeltaJson(String text) {
        return "{\"reasoning_content\":" + JSONObject.quote(text) + "}";
    }

    /** 网关侧密钥校验；未配置任何密钥时放行。 */
    private boolean checkAuth(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        if (!allowRemote(req, res)) {
            return false;
        }
        java.util.List<String> keys = store.getApiKeys();
        if (keys.isEmpty()) {
            return true;
        }
        String auth = req.getHeaders().get("Authorization");
        String provided = "";
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = auth.substring(7).trim();
        }
        if (provided.isEmpty()) {
            provided = req.getHeaders().get("x-api-key");
        }
        for (String k : keys) {
            if (k.equals(provided)) {
                return true;
            }
        }
        LogRing.log("auth", "拒绝未授权请求，path=" + req.getPath());
        json(res, 401, ProtocolAdapter.error("网关密钥无效", "invalid_request_error", "invalid_api_key"));
        return false;
    }

    /** 关闭局域网访问时，只允许本机回环请求。 */
    private boolean allowRemote(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        if (store.isLanEnabled()) {
            return true;
        }
        try {
            com.koushikdutta.async.AsyncSocket sock = res.getSocket();
            if (sock instanceof AsyncNetworkSocket) {
                InetSocketAddress a = ((AsyncNetworkSocket) sock).getRemoteAddress();
                if (a != null && a.getAddress() != null) {
                    if (a.getAddress().isLoopbackAddress()) {
                        return true;
                    }
                    LogRing.log("auth", "已关闭局域网访问，拒绝来自 "
                            + a.getAddress().getHostAddress());
                    json(res, 403, ProtocolAdapter.error("网关仅允许本机访问",
                            "invalid_request_error", "forbidden"));
                    return false;
                }
            }
        } catch (Throwable ignored) {
            // 取不到地址时按放行处理（本机场景）
        }
        return true;
    }

    private static String readBody(AsyncHttpServerRequest req) {
        try {
            Object body = req.getBody() == null ? null : req.getBody().get();
            if (body == null) {
                return "";
            }
            return body instanceof String ? (String) body : body.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 会话键：优先显式会话级标识（header / body / metadata），
     * 最后按「模型 + 用户 + 首条消息」派生。
     *
     * <p>注意：{@code body.user} 是「终端用户」而不是「会话」，不能单独当 key，
     * 否则同一用户新建的所有对话都会复用同一个码道会话（表现为新对话串到旧对话）。
     */
    private static String sessionKey(AsyncHttpServerRequest req, JSONObject openai) {
        // 1) 会话级 header（req 可能为 null：内部工具路径没有 HTTP 请求对象）
        if (req != null) {
            for (String h : new String[]{"X-Session-Id", "X-Conversation-Id",
                    "X-Chat-Id", "X-Thread-Id"}) {
                String s = req.getHeaders().get(h);
                if (s != null && !s.trim().isEmpty()) {
                    return s.trim();
                }
            }
        }
        // 2) body 里的会话级字段（不同 agent 命名不一）
        for (String k : new String[]{"conversation_id", "conversationId", "session_id",
                "sessionId", "chat_id", "chatId", "thread_id", "threadId", "prompt_cache_key"}) {
            String v = openai.optString(k, "");
            if (!v.trim().isEmpty()) {
                return v.trim();
            }
        }
        // 3) metadata 里的会话 id
        JSONObject meta = openai.optJSONObject("metadata");
        if (meta != null) {
            for (String k : new String[]{"conversation_id", "session_id", "chat_id", "thread_id"}) {
                String v = meta.optString(k, "");
                if (!v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        }
        // 4) 网关自己调码道做元任务（如会话标题生成）走独立会话
        if (req != null && "1".equals(req.getHeaders().get("X-CodeArts-Meta"))) {
            return "meta:" + Integer.toHexString(openai.toString().hashCode());
        }
        // 5) 回退：模型 + 终端用户 + 首条用户消息，三者组合才能区分同一用户的不同对话
        String u = openai.optString("user", "").trim();
        String firstUser = "";
        JSONArray messages = openai.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m != null && "user".equals(m.optString("role"))) {
                    firstUser = ProtocolAdapter.flattenContent(m.opt("content"));
                    break;
                }
            }
        }
        String seed = openai.optString("model", "") + "|"
                + (u.isEmpty() ? "" : u + "|")
                + DevCloudClient.trim(firstUser, 80);
        return "c" + Integer.toHexString(seed.hashCode());
    }

    private long resolveChatId(String sessionKey, JSONObject openai) {
        // 允许客户端显式指定 / 重置
        if (openai.optBoolean("new_chat", false)) {
            sessions.remove(sessionKey);
            return 0L;
        }
        long explicit = openai.optLong("chat_id", 0L);
        if (explicit > 0) {
            sessions.put(sessionKey, explicit);
            return explicit;
        }
        Long v = sessions.get(sessionKey);
        return v == null ? 0L : v;
    }

    private String statusJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("status", running ? "running" : "stopped");
            o.put("app", AppConfig.APP_NAME);
            o.put("version", AppConfig.VERSION);
            o.put("port", port);
            o.put("lan", store.isLanEnabled());
            o.put("requests", requestCounter.get());
            o.put("sessions", sessions.size());
            o.put("account", store.getActiveName());
            String t = store.getCookie();
            o.put("token_tail", t.isEmpty() ? "" : ("..." + t.substring(Math.max(0, t.length() - 6))));
            o.put("upstream", AppConfig.ORBIT_BASE);
            // 工具路径（A+B）状态
            JSONObject tool = new JSONObject();
            tool.put("tool_upstream", store.getToolBase());
            tool.put("tool_model", store.getToolModel());
            tool.put("mcp_enabled", store.isMcpEnabled());
            tool.put("allow_mutating", store.isAllowMutating());
            JSONArray srv = new JSONArray();
            try {
                for (McpHub.Server s : mcpServers()) {
                    JSONObject one = new JSONObject();
                    one.put("name", s.name);
                    one.put("url", s.url);
                    one.put("tools", s.tools.size());
                    one.put("error", s.lastError == null ? "" : s.lastError);
                    srv.put(one);
                }
            } catch (Throwable ignore) {
                // 忽略
            }
            tool.put("mcp_servers", srv);
            o.put("tool", tool);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** 未匹配到任何路由时，明确告诉调用方走错了路径，而不是静默 404。 */
    private void handleNotFound(AsyncHttpServerRequest req, AsyncHttpServerResponse res) {
        String m = "";
        String p = "";
        try {
            m = req.getMethod();
            p = req.getPath();
        } catch (Throwable ignored) {
        }
        LogRing.log("http", "未匹配路径: " + m + " " + p);
        json(res, 404, ProtocolAdapter.error(
                "网关没有这个路径：" + m + " " + p
                        + "。可用：GET /v1/models、GET /v1/models/raw、POST /v1/chat/completions"
                        + "、GET /health（/v1 前缀与结尾斜杠均可省略）",
                "invalid_request_error", "not_found"));
    }

    private static void cors(AsyncHttpServerResponse res) {
        try {
            res.getHeaders().set("Access-Control-Allow-Origin", "*");
            res.getHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            res.getHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Session-Id, x-api-key");
            res.getHeaders().set("Access-Control-Max-Age", "86400");
        } catch (Throwable ignored) {
        }
    }

    private static void json(AsyncHttpServerResponse res, int code, String body) {
        try {
            cors(res);
            res.code(code);
            // 显式 Connection: close：androidasync 的 keep-alive 实现与 okhttp 复用存在
            // 兼容性问题（客户端报 unexpected end of stream on Address），逐请求关闭最稳
            res.getHeaders().set("Connection", "close");
            res.send("application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            try {
                res.end();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * SSE 写出封装——<b>直接往连接的 socket 写 HTTP 报文</b>。
     *
     * <p>为什么不用 {@code AsyncHttpServerResponse.write()}：androidasync 3.1.0 在
     * “无 Content-Length → 自动 Chunked”这条路径上存在缺陷，实测结果（JVM 复现 +
     * 原始 socket 抓包）是响应头能正常发出、{@code write()} 也返回成功，
     * 但字节在 {@code ChunkedOutputFilter/BufferedDataSink} 滤镜链里被吞掉，
     * 客户端只收到一个空 body，表现为“SSE 头合法但没有任何内容”。
     *
     * <p>因此这里绕过该通道：自己写状态行与响应头，采用
     * {@code Connection: close} + 连接关闭界定报文长度（HTTP/1.1 §6.1 合法），
     * 结束时主动关闭 socket，客户端读到 EOF 即视为流结束。
     */
    static final class SseWriter {
        private final com.koushikdutta.async.AsyncSocket sock;
        private final Object lock = new Object();
        private boolean closed;
        private int written;
        private long bytes;

        SseWriter(com.koushikdutta.async.AsyncSocket sock) {
            this.sock = sock;
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 200 OK\r\n")
                    .append("Content-Type: text/event-stream; charset=utf-8\r\n")
                    .append("Cache-Control: no-cache, no-transform\r\n")
                    .append("Pragma: no-cache\r\n")
                    .append("X-Accel-Buffering: no\r\n")
                    .append("Access-Control-Allow-Origin: *\r\n")
                    .append("Access-Control-Allow-Headers: Authorization, Content-Type, X-Session-Id, x-api-key\r\n")
                    .append("Connection: close\r\n")
                    .append("\r\n");
            try {
                com.koushikdutta.async.Util.writeAll(sock, head.toString().getBytes(StandardCharsets.UTF_8), null);
            } catch (Throwable t) {
                LogRing.log("sse", "写响应头失败: " + t);
                closed = true;
            }
        }

        void data(String payload) {
            write("data: " + payload + "\n\n");
        }

        void comment(String c) {
            write(": " + c + "\n\n");
        }

        private void write(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            synchronized (lock) {
                if (closed) {
                    return;
                }
                written++;
                bytes += b.length;
            }
            try {
                sock.write(new ByteBufferList(b));
            } catch (Throwable t) {
                LogRing.log("sse", "写出失败: " + t);
                synchronized (lock) {
                    closed = true;
                }
            }
        }

        void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                LogRing.log("sse", "收尾：已推送 " + written + " 个分片 / " + bytes + " 字节");
            }
            // 延迟 80ms 再关：androidasync 的写是异步队列，立即 close 会截断
            // 尾部字节（okhttp 端表现为 unexpected end of stream）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    sock.end();  // 触发底层数据排空
                } catch (Throwable ignored) {
                }
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }, 80);
        }
    }
}

