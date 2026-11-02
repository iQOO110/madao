package com.codearts.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 华为 CodeArts 码道（devcloud.cn-north-4.huaweicloud.com/chat）协议客户端。
 *
 * <p>协议事实（逆向自线上前端 bundle 126.8.942）：
 * <ul>
 *   <li>鉴权：会话 cookie（名含 cftk，此站点 cookie 键 devclouddevuibjtcftk），
 *       请求带 Cookie 头即可；前端 x-auth-token 为空串。</li>
 *   <li>建会话：POST /v1/cloudagent/sessions，body {}，响应 result.session_id。</li>
 *   <li>发消息：POST /codebaseservice/v1/cloudagent/sessions/:id/messages，
 *       body {content, model_id, repos:[]}，响应 SSE。</li>
 *   <li>SSE 事件：thought（思考增量 content）、message（正文增量 content）、
 *       done、error、status、heartbeat、step_finish 等。</li>
 *   <li>模型：GET /PromptCenterService/v1/model/builtin。</li>
 *   <li>签到福利：GET /PromptCenterService/v1/ops/delivery?channel=WEB（items[]，
 *       type=daily_claim，status ELIGIBLE 可领，pendingTotalAmount 待领积分）；
 *       领取 POST /ops/claim {campaignId,idempotentKey,channel:"WEB"}；
 *       确认 POST /ops/confirm {campaignId}。</li>
 * </ul>
 */
public final class DevCloudClient {

    public static final String ORIGIN = "https://devcloud.cn-north-4.huaweicloud.com";
    public static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 截断文本用于日志。 */
    public static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
    public static final String BASE = ORIGIN + "/chat";
    public static final String LOGIN_URL = BASE + "/home";
    public static final String COOKIE_NAME = "devclouddevuibjtcftk";
    /** 前端 listModels 用的 agent_id（bundle 常量 k5t）。 */
    public static final String AGENT_ID = "a8bcb36232554267a5142361cc25a393";


    /** 流式回调。 */
    public interface Sink {
        void onDelta(String text);

        void onThinking(String text);

        void onEvent(String eventName, org.json.JSONObject raw);

        void onDone(boolean ok, String error);

        /** 上游心跳（SSE heartbeat）：默认空实现，流式转发方可用它下发 keep-alive。 */
        default void heartbeat() {
        }
    }

    public static final class Resp {
        public int code;
        public String contentType = "";
        public byte[] body = new byte[0];
        public String location = "";
        public java.util.Map<String, java.util.List<String>> headers;

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private DevCloudClient() {
    }

    // ---------------------------------------------------------------- 请求

    public static Resp request(String cookie, String method, String url, String body,
                               java.util.Map<String, String> extra) {
        HttpURLConnection conn = null;
        Resp resp = new Resp();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(180000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept", "application/json, text/plain, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Referer", LOGIN_URL);
            // 前端同款鉴权/CSRF 头（bundle 证据）：
            //   Agent-Type: PromptCenter
            //   cftk: <cookie 里的 cftk 值>（double-submit）
            //   x-auth-token: ""（恒空串，真实鉴权在 Cookie）
            //   x-requested-with / x-codearts-doer-scenario-type
            // 前端规则（bundle 证据）：URL 以 /v1/cloudagent/ 开头 → Agent-Type: CodeBase；
            // PromptCenterService 路径 → Agent-Type: PromptCenter；其余不带。
            String path = url.startsWith(ORIGIN) ? url.substring(ORIGIN.length()) : url;
            if (path.startsWith("/chat/")) {
                path = path.substring("/chat".length());
            }
            if (path.startsWith("/v1/cloudagent/")) {
                conn.setRequestProperty("Agent-Type", "CodeBase");
            } else if (path.startsWith("/PromptCenterService/")) {
                conn.setRequestProperty("Agent-Type", "PromptCenter");
            }
            // /codebaseservice/v1/cloudagent/* 前端走 Lb 客户端时无 Agent-Type（Hz 不命中）
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            conn.setRequestProperty("x-codearts-doer-scenario-type", "web");
            String cftk = extractCftk(cookie);
            if (!cftk.isEmpty()) {
                conn.setRequestProperty("cftk", cftk);
            }
            conn.setRequestProperty("x-auth-token", "");
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            if (extra != null) {
                for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(payload.length);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }
            resp.code = conn.getResponseCode();
            resp.contentType = String.valueOf(conn.getContentType());
            resp.headers = conn.getHeaderFields();
            if (conn.getHeaderFields() != null) {
                String loc = conn.getHeaderField("Location");
                if (loc != null) {
                    resp.location = loc;
                }
            }
            InputStream in = resp.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            resp.body = readAll(in);
        } catch (Throwable t) {
            resp.code = -1;
            resp.body = ("{\"error\":\"" + t + "\"}").getBytes(StandardCharsets.UTF_8);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return resp;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        in.close();
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- 会话与消息

    /** 创建会话，返回 session_id；失败抛异常。 */
    public static String createSession(String cookie, String modelId) throws Exception {
        Resp r = request(cookie, "POST", BASE + "/v1/cloudagent/sessions", "{}", null);
        String raw = r.text();
        if (r.code != 200) {
            throw new IllegalStateException("建会话 HTTP " + r.code + ": " + trim(raw, 200));
        }
        JSONObject o = new JSONObject(raw);
        JSONObject result = o.optJSONObject("result");
        String id = result == null ? "" : result.optString("session_id",
                result.optString("sessionId", ""));
        if (id.isEmpty()) {
            id = o.optString("session_id", o.optString("sessionId", ""));
        }
        if (id.isEmpty()) {
            throw new IllegalStateException("建会话无 session_id: " + trim(raw, 200));
        }
        return id;
    }

    /** 发消息并流式消费 SSE。 */
    public static void streamChat(String cookie, String sessionId, String content,
                                  String modelId, Sink rawSink) {
        boolean ok = false;
        String err = null;
        OnceSinkResult done = new OnceSinkResult(rawSink);
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("content", content == null ? "" : content);
            body.put("repos", new JSONArray());
            if (modelId != null && !modelId.isEmpty()) {
                body.put("model_id", modelId);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String url = BASE + "/codebaseservice/v1/cloudagent/sessions/"
                    + sessionId + "/messages";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(300000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Origin", ORIGIN);
            conn.setRequestProperty("Referer", LOGIN_URL);
            conn.setRequestProperty("x-codearts-doer-scenario-type", "web");
            conn.setRequestProperty("x-requested-with", "XMLHttpRequest");
            conn.setRequestProperty("Agent-Type", "CodeBase");
            conn.setRequestProperty("x-auth-token", "");
            // 福利模型（benefit/gateway/config 来源）必须带 maas-type: benefit，
            // 否则后端不路由到福利算力池（实测表现为 SSE error 事件、0 输出）
            if (isBenefitModel(modelId)) {
                conn.setRequestProperty("maas-type", "benefit");
                LogRing.log("devcloud", "福利模型 " + modelId + " → maas-type=benefit");
            }
            String cftkSse = extractCftk(cookie);
            if (!cftkSse.isEmpty()) {
                conn.setRequestProperty("cftk", cftkSse);
            }
            if (cookie != null && !cookie.isEmpty()) {
                conn.setRequestProperty("Cookie", cookie);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String raw = trim(readErr(conn), 300);
                LogRing.log("devcloud", "发消息 HTTP " + code + " " + raw);
                done.finish(false, humanError(code, raw));
                return;
            }

            InputStream in = conn.getInputStream();
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
            String line;
            StringBuilder data = new StringBuilder();
            String eventLine = null;
            int payloads = 0;
            boolean stop = false;

            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        payloads++;
                        if (dispatch(data.toString(), eventLine, done)) {
                            stop = true;
                        }
                    }
                    data.setLength(0);
                    eventLine = null;
                    if (stop) {
                        break;
                    }
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue;
                }
                if (line.startsWith("data:")) {
                    String v = line.substring(5);
                    if (v.startsWith(" ")) {
                        v = v.substring(1);
                    }
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(v);
                } else if (line.startsWith("event:")) {
                    eventLine = line.substring(6).trim();
                }
            }
            if (!stop && data.length() > 0) {
                dispatch(data.toString(), eventLine, done);
                payloads++;
            }
            if (payloads == 0) {
                LogRing.log("devcloud", "上游未产生任何 SSE 事件（可能未登录或协议变化）");
                done.finish(false, "上游未返回任何事件（可能未登录，请重新登录 DevCloud）");
                return;
            }
            ok = true;
        } catch (Throwable t) {
            err = String.valueOf(t);
            LogRing.log("devcloud", "流式请求异常: " + t);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        done.finish(ok, err);
    }

    private static boolean dispatch(String data, String eventLine, OnceSinkResult sink) {
        String payload = data.trim();
        if (payload.isEmpty()) {
            return false;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(payload);
        } catch (Throwable t) {
            LogRing.log("devcloud", "无法解析 SSE 片段: " + trim(payload, 160));
            return false;
        }
        String ev = eventLine == null || eventLine.isEmpty()
                ? obj.optString("event", "message") : eventLine;

        switch (ev) {
            case "thought": {
                String c = obj.optString("content", "");
                if (!c.isEmpty()) {
                    sink.target.onThinking(c);
                }
                break;
            }
            case "message": {
                String c = obj.optString("content", "");
                if (!c.isEmpty()) {
                    sink.target.onDelta(c);
                }
                break;
            }
            case "done": {
                LogRing.log("devcloud", "SSE event=done " + trim(obj.toString(), 500));
                sink.target.onEvent(ev, obj);
                return true;
            }
            case "error": {
                String msg = obj.optString("error", obj.optString("message", ""));
                if (msg.isEmpty()) {
                    String code = obj.optString("code", "");
                    msg = code.isEmpty() ? "上游错误: " + obj : "上游错误 code=" + code;
                }
                LogRing.log("devcloud", "SSE error 详情: " + trim(obj.toString(), 300));
                sink.target.onEvent(ev, obj);
                sink.finish(false, msg);
                return true;
            }
            case "tool_call":
            case "tool_calls":
            case "function_call":
            case "tool_result":
            case "tool":
            case "command":
            case "cmd":
            case "deliverable":
            case "deliverables":
            case "artifact":
            case "artifacts":
            case "file":
            case "files":
            case "file_change":
            case "file_changed":
            case "mark_deliverable":
            case "task":
            case "tasks":
            case "task_result":
                // 捕获码道可能透出的工具/产物/文件类事件（v1.9.10）
                LogRing.log("devcloud", "SSE 捕获事件 " + ev + ": " + trim(obj.toString(), 500));
                sink.target.onEvent(ev, obj);
                return false;
            default:
                // 未知事件（上游新增类型）也落日志，不终止流
                LogRing.log("devcloud", "SSE 未知事件 " + ev + ": " + trim(obj.toString(), 300));
                sink.target.onEvent(ev, obj);
                return false;
        }
        if ("heartbeat".equals(ev)) {
            // 心跳：告诉客户端"链路活着，上游在干活"，防止第三方 App 判超时（v1.9.9）
            sink.target.onEvent(ev, obj);
            sink.heartbeat();
            return false;
        }
        sink.target.onEvent(ev, obj);
        return false;
    }

    private static String readErr(HttpURLConnection conn) {
        try {
            byte[] b = readAll(conn.getErrorStream());
            if (b.length > 0) {
                return new String(b, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        try {
            byte[] b = readAll(conn.getInputStream());
            if (b.length > 0) {
                return new String(b, StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static String humanError(int code, String raw) {
        if (looksLikeHtml(raw)) {
            return "DevCloud HTTP " + code + "：上游返回了登录页 HTML（会话未生效）。"
                    + "请在 App 内重新登录并保存会话；若刚保存过仍如此，说明该站点还需要安全 Cookie，"
                    + "可尝试在登录页多停留几秒再保存。";
        }
        String hint = "";
        try {
            JSONObject o = new JSONObject(raw);
            String msg = o.optString("message", o.optString("msg", o.optString("error", "")));
            if (!msg.isEmpty()) {
                hint = msg;
            }
        } catch (Throwable ignored) {
        }
        if (hint.isEmpty()) {
            hint = trim(raw == null ? "" : raw, 260);
        }
        return "DevCloud HTTP " + code + ": " + hint;
    }

    /** 识别上游返回的是 HTML（登录页/WAF 拦截页）而非 JSON。 */
    public static boolean looksLikeHtml(String raw) {
        if (raw == null) {
            return false;
        }
        String head = raw.trim();
        return head.startsWith("<!DOCTYPE") || head.startsWith("<html")
                || head.startsWith("<HTML") || head.startsWith("<script");
    }

    /**
     * 从 Cookie 串提取 cftk CSRF 值（double-submit 用）。
     * cftk 键名含 "cftk"（此站点为 devclouddevuibjtcftk）。
     */
    public static String extractCftk(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return "";
        }
        for (String pair : cookie.split(";")) {
            String p = pair.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = p.substring(0, eq);
            if (name.toLowerCase().contains("cftk")) {
                return p.substring(eq + 1);
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- 模型

    /** 拉取内置模型列表。 */
    /**
     * 拉取模型列表，与前端 listModels() 完全同序：
     * ① 主源 /agent-center/agents/detail（AgentCenter 头 + agent_id）→ gpts.models，
     *    过滤 GLM-5.1 / Qwen3-VL-235B / maas-glm-4.7；空则回落静态表（Ex，仅 GLM-5.2）
     * ② 合并 /benefit/gateway/config 的 result.models（限时福利🎁，如 deepseek-v4-*）
     * ③ 再合并 /model/builtin
     *
     * modelId 取值顺序同前端 V3()：model_id → id → name → model_name；
     * label = model_name → name → display_name → modelId。
     */
    public static List<Store.ModelInfo> fetchModels(String cookie) {
        List<Store.ModelInfo> out = new ArrayList<>();

        // ① agents/detail 主源
        Resp r = request(cookie, "GET",
                BASE + "/PromptCenterService/v1/agent-center/agents/detail?agent_id="
                        + AGENT_ID, null, null);
        String raw = r.text();
        LogRing.log("devcloud", "agents/detail HTTP " + r.code + " " + trim(raw, 200));
        int fromAgents = 0;
        if (r.code == 200 && !looksLikeHtml(raw)) {
            fromAgents = mergeAgentModels(out, raw);
        }
        if (out.isEmpty()) {
            LogRing.log("devcloud", "主源为空，回落静态 KERNEL_MODELS（GLM-5.2）");
            out.add(new Store.ModelInfo("GLM-5.2", "GLM-5.2", java.util.Collections
                    .singletonList("深度推理模型，适合复杂任务与深度思考")));
        }

        // ② benefit/gateway/config（限时福利）。
        //    bundle 证据：前端主调是 Zz() 无参 → 不带 channel 查询参数；
        //    channel:"WEB" 只出现在 ops/claim 的 POST body 里，与此无关。
        //    实测带 channel=WEB 会返回 4000 invalid channel, group not found。
        Resp r2 = request(cookie, "GET",
                BASE + "/codebaseservice/v1/cloudagent/benefit/gateway/config",
                null, null);
        String raw2 = r2.text();
        LogRing.log("devcloud", "benefit/gateway/config HTTP " + r2.code + " " + trim(raw2, 200));
        if (r2.code == 200 && !looksLikeHtml(raw2)) {
            try {
                JSONObject o = new JSONObject(raw2);
                if (!"0000".equals(o.optString("error_code"))) {
                    LogRing.log("devcloud", "benefit/config 未放行（"
                            + o.optString("error_code") + " " + o.optString("error_msg")
                            + "），跳过福利模型合并");
                } else if (o.optJSONObject("result") != null) {
                    org.json.JSONArray models = o.getJSONObject("result").optJSONArray("models");
                    if (models != null) {
                        for (int i = 0; i < models.length(); i++) {
                            JSONObject m = models.optJSONObject(i);
                            if (m == null) {
                                continue;
                            }
                            String id = firstOf(m, "model_id", "modelId");
                            String name = firstOf(m, "model_name", "modelName", "model_id");
                            if (id.isEmpty()) {
                                continue;
                            }
                            List<String> tags = new ArrayList<>();
                            tags.add("限时福利");
                            if (!hasModel(out, id)) {
                                BENEFIT_MODELS.add(id);
                                out.add(new Store.ModelInfo(id, name, tags));
                            }
                            // 注：BENEFIT_MODELS 注册在上方，保证重复拉取时也幂等
                        }
                    }
                }
            } catch (Throwable t) {
                LogRing.log("devcloud", "benefit/config 解析失败: " + t);
            }
        }

        // ③ builtin 补充
        Resp r3 = request(cookie, "GET",
                BASE + "/PromptCenterService/v1/model/builtin", null, null);
        String raw3 = r3.text();
        if (r3.code == 200 && !looksLikeHtml(raw3)) {
            for (Store.ModelInfo m : Store.parseModels(raw3)) {
                if (!hasModel(out, m.id)) {
                    out.add(m);
                }
            }
        }
        LogRing.log("devcloud", "模型合并完成：agents=" + fromAgents + "，总计=" + out.size());
        return out;
    }

    /** 解析 agents/detail 响应并合并进 out，返回合并条数。 */
    private static int mergeAgentModels(List<Store.ModelInfo> out, String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            // 优先走前端同款路径 gpts.models
            JSONObject gpts = findGpts(o, 0);
            if (gpts != null && gpts.optJSONArray("models") != null) {
                return mergeModelArray(out, gpts.optJSONArray("models"), false);
            }
            // 服务端 schema 已变（用户实测返回 agent 配置树，gpts 不在顶层）：
            // 递归扫描整棵树，收集所有名为 models 的数组
            org.json.JSONArray collected = new org.json.JSONArray();
            collectModelArrays(o, collected, 0);
            if (collected.length() == 0) {
                LogRing.log("devcloud", "agents/detail 整树未找到 models 数组");
                return 0;
            }
            LogRing.log("devcloud", "递归扫描到 " + collected.length() + " 个模型节点");
            return mergeModelArray(out, collected, false);
        } catch (Throwable t) {
            LogRing.log("devcloud", "agents/detail 解析失败: " + t);
            return 0;
        }
    }

    /** 前端同款路径：两层 data 解包后找 gpts。 */
    private static JSONObject findGpts(JSONObject o, int depth) {
        if (depth > 3) {
            return null;
        }
        JSONObject gpts = o.optJSONObject("gpts");
        if (gpts != null && gpts.optJSONArray("models") != null) {
            return gpts;
        }
        for (String k : new String[]{"data", "result"}) {
            JSONObject next = o.optJSONObject(k);
            if (next != null) {
                JSONObject found = findGpts(next, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 递归收集 JSON 树中所有 "models" 数组的元素（对象且含模型标识字段）。 */
    private static void collectModelArrays(Object node, org.json.JSONArray out, int depth) {
        if (depth > 8 || node == null) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            org.json.JSONArray arr = o.optJSONArray("models");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Object e = arr.opt(i);
                    if (e instanceof JSONObject) {
                        out.put(e);
                    }
                }
            }
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                collectModelArrays(o.opt(it.next()), out, depth + 1);
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray arr = (org.json.JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                collectModelArrays(arr.opt(i), out, depth + 1);
            }
        }
    }

    /** 把 models 数组按前端规则（过滤+取值顺序）合并进 out，返回新增条数。 */
    private static int mergeModelArray(List<Store.ModelInfo> out, org.json.JSONArray models,
                                       boolean benefit) {
        int added = 0;
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String name0 = m.optString("model_name", "");
            if ("GLM-5.1".equals(name0) || "Qwen3-VL-235B".equals(name0)
                    || "maas-glm-4.7".equals(name0)) {
                continue;
            }
            if (m.optBoolean("display_enabled", true) == false
                    || m.optBoolean("enable", true) == false) {
                continue;
            }
            // 前端 map(p=>({...p.model_parameters, model_name, provider, model_type}))
            // —— model_parameters 的字段展开在【前】，会覆盖同名字段：
            //    inferhub 真实路由 ID 在 model_parameters.model_id，
            //    外层 model_id 只是展示名（如 OpenPangu-2.0-Pro → inferhub 内部 ID）
            JSONObject effective = new JSONObject();
            JSONObject params = m.optJSONObject("model_parameters");
            if (params != null) {
                java.util.Iterator<String> pit = params.keys();
                while (pit.hasNext()) {
                    String k = pit.next();
                    Object v = params.opt(k);
                    if (v != null && !(v instanceof JSONObject) && !(v instanceof org.json.JSONArray)) {
                        try {
                            effective.put(k, v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            java.util.Iterator<String> mit = m.keys();
            while (mit.hasNext()) {
                String k = mit.next();
                if (!effective.has(k)) {
                    Object v = m.opt(k);
                    if (v != null && !(v instanceof JSONObject) && !(v instanceof org.json.JSONArray)) {
                        try {
                            effective.put(k, v);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            String id = firstOf(effective, "model_id", "modelId", "id", "name", "model_name");
            if (id.isEmpty()) {
                continue;
            }
            String name = firstOf(m, "model_name", "display_name", "name", "model_id");
            if (name.isEmpty()) {
                name = id;
            }
            List<String> tags = new ArrayList<>();
            // 标签只取自「官网同款硬编码表」（与官网模型面板逐字一致）。
            // 上游 desc 类字段会返回 "admin"（创建者元数据）等无关文本，一律不采用。
            String desc = officialDesc(id, name);
            if (desc != null) {
                tags.add(desc);
            } else if (isOfficialBenefitModel(id, name)) {
                // 官网「限时福利」分组只列模型名、不展示描述文案，此处用分组名作标签
                tags.add("限时福利");
            }
            if (!hasModel(out, id)) {
                if (benefit) {
                    BENEFIT_MODELS.add(id);
                }
                out.add(new Store.ModelInfo(id, name, tags));
                added++;
            }
        }
        return added;
    }

    /**
     * 官网「模型」面板逐字文案（硬编码，与码道官网展示完全一致）。
     *
     * <p>key 统一按 {@link #normKey(String)} 归一化（去空白 + 转小写），因此
     * 「展示名」（如 GLM-5.2-ArkTS-SPARK）与「路由 ID」（如 glm-5.2-sft-harmony）
     * 两种写法都能命中；查表时先按路由 ID 再按展示名。
     *
     * <p>为什么不直接读上游字段：agents/detail 里 models[].model_desc 等 desc 类字段
     * 实测返回的是创建者元数据（"admin"），渲染出来就是模型卡片下那个 admin 标签，
     * 故一律不用上游文案，避免再出现脏标签。
     */
    private static final java.util.Map<String, String> OFFICIAL_DESCRIPTIONS =
            new java.util.HashMap<>();
    /** 官网「限时福利」分组的模型（官网该分组不展示描述文案）。 */
    private static final java.util.Set<String> OFFICIAL_BENEFIT_MODELS =
            new java.util.HashSet<>();

    static {
        // —— 官网「内置」分组：文案与官网逐字一致 ——
        desc("GLM-5.2", "深度推理模型，适合复杂任务与深度思考");
        desc("GLM-5.2-ArkTS-SPARK", "基于GLM-5.2增训鸿蒙代码与开发知识");
        desc("glm-5.2-sft-harmony", "基于GLM-5.2增训鸿蒙代码与开发知识");
        desc("OpenPangu-2.0-Pro", "最新旗舰模型，专为长程任务打通，复杂工程稳定交付");
        desc("openpangu-2.0-pro", "最新旗舰模型，专为长程任务打通，复杂工程稳定交付");
        desc("OpenPangu-2.0-Flash", "均衡推理效果与性能");
        desc("openpangu-2.0-flash", "均衡推理效果与性能");
        // —— 官网「限时福利」分组：官网只列模型名 ——
        benefit("deepseek-v4-flash-0731");
        benefit("glm-5.3-flash");
        benefit("deepseek-v4-pro-0813");
        benefit("deepseek-v4.1-flash");
    }

    /** 登记官网描述文案（同时接受展示名与路由 ID 写法）。 */
    private static void desc(String key, String text) {
        OFFICIAL_DESCRIPTIONS.put(normKey(key), text);
    }

    /** 登记官网「限时福利」分组成员。 */
    private static void benefit(String key) {
        OFFICIAL_BENEFIT_MODELS.add(normKey(key));
    }

    /** 归一化查表键：去首尾空白 + 转小写（大小写/空格差异不影响命中）。 */
    private static String normKey(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 取官网描述文案：先按路由 ID，再按展示名；都没有返回 null（不猜、不回落上游）。 */
    private static String officialDesc(String id, String name) {
        String d = OFFICIAL_DESCRIPTIONS.get(normKey(id));
        return d != null ? d : OFFICIAL_DESCRIPTIONS.get(normKey(name));
    }

    /** 是否为官网「限时福利」分组的模型（按路由 ID 或展示名匹配）。 */
    private static boolean isOfficialBenefitModel(String id, String name) {
        return OFFICIAL_BENEFIT_MODELS.contains(normKey(id))
                || OFFICIAL_BENEFIT_MODELS.contains(normKey(name));
    }

    /** 前端同款取值：依次尝试多个键，返回第一个非空。 */
    private static String firstOf(JSONObject m, String... keys) {
        for (String k : keys) {
            String v = m.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /**
     * 限时福利模型 ID 集（来自 benefit/gateway/config）。
     * bundle 证据：isFreeBenefit 模型发消息时必须带请求头 maas-type: benefit，
     * 否则后端不路由到福利算力池（表现为 SSE error 事件、0 输出）。
     */
    private static final java.util.Set<String> BENEFIT_MODELS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static boolean isBenefitModel(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return false;
        }
        if (BENEFIT_MODELS.contains(modelId)) {
            return true;
        }
        // 修复（v1.9.1）：官网硬编码的限时福利组（glm-5.3-flash / deepseek-v4-* …）也必须算福利模型，
        // 否则不带 maas-type: benefit 会被上游回 "未找到模型：inferhub-provider/xxx，该模型不存在"。
        // 之前这里只查动态拉取的 BENEFIT_MODELS，而 benefit/gateway/config 对本账号报 4000，
        // 于是福利模型全都漏掉了 maas-type 头。
        return OFFICIAL_BENEFIT_MODELS.contains(normKey(modelId));
    }

    private static boolean hasModel(List<Store.ModelInfo> list, String id) {
        for (Store.ModelInfo m : list) {
            if (m.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 签到（welfare）

    /** 福利条目。 */
    public static final class Welfare {
        public String campaignId;
        public String type;       // 上游原始 type
        public String typeNorm;   // 归一化后的 type（前端 R0 同款：未知一律视为 daily_claim）
        public String title;
        public String status;     // ELIGIBLE 可领 / CLAIMED 已领
        public boolean claimed;
        public long pendingAmount;
        public String rawStatus;
        public String jumpUrl;    // displayConfig.jumpUrl 或 hooks[0].jumpUrl
        /** 上游直接给出的「能否领取」标记（缺字段时默认 true，交给状态规则判断）。 */
        public boolean claimableFlag = true;

        /**
         * 是否可直接领取（对齐官网前端 Uh/N0 规则 + 上游 claimable 标记）：
         * <ul>
         *   <li>已领（CLAIMED/FINISHED）→ 不可领；</li>
         *   <li>status = ELIGIBLE / ENTRY → 可领（每日签到的 USER_LOGIN 条目就是 ELIGIBLE）；</li>
         *   <li>无 status：有 jumpUrl 时官网是「打开链接」→ 不可直接领；无 jumpUrl 时才看上游
         *       claimable 标记（如「Web端完成1次对话」这类 AUTO_GRANT 条目 claimable=false）。</li>
         * </ul>
         */
        public boolean claimable() {
            if (claimed) {
                return false;
            }
            if (rawStatus == null || rawStatus.isEmpty()) {
                if (jumpUrl != null && !jumpUrl.isEmpty()) {
                    return false;
                }
                return claimableFlag;
            }
            return "ELIGIBLE".equalsIgnoreCase(rawStatus)
                    || "ENTRY".equalsIgnoreCase(rawStatus);
        }

        /** 需要跳转到外部页面手动完成（官网是「打开链接」，不是直接领取）。 */
        public boolean needJump() {
            return (rawStatus == null || rawStatus.isEmpty())
                    && jumpUrl != null && !jumpUrl.isEmpty();
        }
    }

    public static final class WelfareResult {
        public boolean success;
        public String message;
        public List<Welfare> items = new ArrayList<>();
        public long claimedAmount;
        /** 原始报文轨迹（delivery / claim / confirm），供落盘诊断。 */
        public final StringBuilder trace = new StringBuilder();
        /** 是否真的发起过 claim 请求。 */
        public boolean claimAttempted;
    }

    /** 拉取活动福利列表。 */
    public static WelfareResult listWelfare(String cookie) {
        WelfareResult out = new WelfareResult();
        Resp r = request(cookie, "GET",
                BASE + "/PromptCenterService/v1/ops/delivery?channel=WEB", null, null);
        String raw = r.text();
        out.trace.append("== GET /PromptCenterService/v1/ops/delivery?channel=WEB → HTTP ")
                .append(r.code).append('\n').append(trim(raw, 30000)).append("\n");
        LogRing.log("welfare", "福利列表 HTTP " + r.code + " " + trim(raw, 200));
        if (r.code != 200) {
            out.message = humanError(r.code, raw);
            if (r.location != null && !r.location.isEmpty()) {
                out.message = "未登录（被重定向到 " + r.location + "）";
            }
            return out;
        }
        if (looksLikeHtml(raw)) {
            out.message = "上游返回登录页 HTML（会话未生效或被 WAF 拦截），请重新登录保存会话";
            return out;
        }
        if (raw.isEmpty()) {
            // 华为云 WAF 对失效会话的典型表现：200 + 空 body（另带 HW-AJAX-REDIRECT 头）
            out.message = "上游返回空响应（会话已被判定失效），请到平台页重新登录后再签到";
            return out;
        }
        try {
            JSONObject o = new JSONObject(raw);
            if (o.optInt("code", 0) != 0 && o.optInt("code", 0) != 200) {
                out.message = o.optString("message", "code=" + o.optInt("code"));
                return out;
            }
            JSONObject data = o.optJSONObject("data");
            JSONArray items = data == null ? o.optJSONArray("items") : data.optJSONArray("items");
            if (items == null && data != null) {
                items = data.optJSONArray("list");   // 兜底：少数版本用 data.list
            }
            if (items == null) {
                out.message = "响应无 items 字段";
                return out;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                Welfare w = new Welfare();
                w.campaignId = it.optString("campaignId", it.optString("id", ""));
                w.type = it.optString("type", "");
                w.typeNorm = normWelfareType(w.type);
                w.title = it.optString("title", "");
                w.rawStatus = it.optString("status", "");
                w.status = w.rawStatus;
                w.claimed = w.rawStatus.equalsIgnoreCase("CLAIMED")
                        || w.rawStatus.equalsIgnoreCase("FINISHED");
                w.pendingAmount = pickAmount(it);
                w.claimableFlag = it.optBoolean("claimable", true);
                w.jumpUrl = extractJumpUrl(it);
                out.items.add(w);
            }
            out.success = true;
        } catch (Throwable t) {
            out.message = "解析失败: " + t;
        }
        return out;
    }

    /**
     * 前端同款 type 归一化（bundle WorkbenchKpiCards.R0）：
     * INVITE_USER→invite、STUDENT_CERTIFIED→student_certify、USER_LOGIN→login、
     * NEW_USER_REGISTER→register，其余（含空/未知，如 DAILY_SIGN_IN）一律视为 daily_claim。
     */
    private static String normWelfareType(String type) {
        String t = type == null ? "" : type.trim();
        if ("INVITE_USER".equalsIgnoreCase(t) || "invite".equalsIgnoreCase(t)) {
            return "invite";
        }
        if ("STUDENT_CERTIFIED".equalsIgnoreCase(t) || "student_certify".equalsIgnoreCase(t)) {
            return "student_certify";
        }
        if ("USER_LOGIN".equalsIgnoreCase(t) || "login".equalsIgnoreCase(t)) {
            return "login";
        }
        if ("NEW_USER_REGISTER".equalsIgnoreCase(t) || "register".equalsIgnoreCase(t)) {
            return "register";
        }
        return "daily_claim";
    }

    /**
     * 取条目展示额度：实测 {@code pendingTotalAmount} 常是 0.00（表示“待领为 0”），
     * 而活动额度在 {@code benefitAmount}（如每日签到 1000、国庆邀请 188），故按此优先级兜底。
     */
    private static long pickAmount(JSONObject it) {
        long pending = it.optLong("pendingTotalAmount", 0);
        if (pending > 0) {
            return pending;
        }
        return it.optLong("benefitAmount", it.optLong("amount", 0));
    }

    /** 从 displayConfig(JSON 字符串) / hooks[0] 里取跳转链接（前端 k0 同款）。 */
    private static String extractJumpUrl(JSONObject it) {
        String jump = "";
        String cfg = it.optString("displayConfig", "");
        if (!cfg.isEmpty()) {
            try {
                JSONObject pe = new JSONObject(cfg);
                jump = pe.optString("jumpUrl", "");
            } catch (Throwable ignore) {
                // displayConfig 不是 JSON 时忽略
            }
        }
        if (jump.isEmpty()) {
            JSONArray hooks = it.optJSONArray("hooks");
            if (hooks != null && hooks.length() > 0) {
                JSONObject h0 = hooks.optJSONObject(0);
                if (h0 != null) {
                    jump = h0.optString("jumpUrl", "");
                }
            }
        }
        return jump;
    }

    /**
     * 一键签到：找 daily_claim 且可领的条目 → claim（幂等键）→ confirm。
     * 前端同款三步：delivery → claim → confirm。
     */
    public static WelfareResult dailyCheckin(String cookie) {
        WelfareResult out = new WelfareResult();
        WelfareResult list = listWelfare(cookie);
        out.items = list.items;
        out.trace.append(list.trace);
        if (!list.success) {
            out.message = list.message == null ? "获取福利列表失败" : list.message;
            return out;
        }
        boolean did = false;
        String lastError = null;
        int skippedJump = 0;
        int skippedInvite = 0;
        for (Welfare w : list.items) {
            // 只有 invite 不是“领取”流程（官网那一条是复制邀请链接）。
            // 每日签到的 type 实测是 USER_LOGIN（归一化后 = login），它恰恰是要 claim 的条目；
            // 之前把 login/register/student_certify 一并当“其他流程”跳过，签到条目就被漏掉了。
            if ("invite".equals(w.typeNorm)) {
                skippedInvite++;
                continue;
            }
            if (!w.claimable()) {
                continue;
            }
            if (w.needJump()) {
                skippedJump++;
                continue;
            }
            out.claimAttempted = true;
            String idem = "wb_" + w.campaignId + "_" + System.currentTimeMillis();
            Resp claim = request(cookie, "POST",
                    BASE + "/PromptCenterService/v1/ops/claim",
                    "{\"campaignId\":\"" + w.campaignId + "\",\"idempotentKey\":\""
                            + idem + "\",\"channel\":\"WEB\"}", null);
            String claimRaw = claim.text();
            out.trace.append("== POST /ops/claim campaignId=").append(w.campaignId)
                    .append(" → HTTP ").append(claim.code).append('\n')
                    .append(trim(claimRaw, 3000)).append("\n");
            LogRing.log("welfare", "claim " + w.campaignId + " → HTTP " + claim.code
                    + " " + trim(claimRaw, 200));
            if (claim.code != 200) {
                lastError = humanError(claim.code, claimRaw);
                continue;
            }
            if (!jsonOk(claimRaw)) {
                lastError = trim(claimRaw, 200);
                continue;
            }
            // claim 成功才算领到（之前 did 在请求前置位，失败也会显示“签到成功”）
            did = true;
            // confirm：前端只 warn 不判失败，领取成功即算成功（confirm 失败不影响积分到账）
            Resp confirm = request(cookie, "POST",
                    BASE + "/PromptCenterService/v1/ops/confirm",
                    "{\"campaignId\":\"" + w.campaignId + "\"}", null);
            String confirmRaw = confirm.text();
            out.trace.append("== POST /ops/confirm campaignId=").append(w.campaignId)
                    .append(" → HTTP ").append(confirm.code).append('\n')
                    .append(trim(confirmRaw, 2000)).append("\n");
            LogRing.log("welfare", "confirm " + w.campaignId + " → HTTP " + confirm.code
                    + " " + trim(confirmRaw, 200));
            long got = w.pendingAmount > 0 ? w.pendingAmount : extractAmount(claimRaw);
            if (got > 0) {
                out.claimedAmount += got;
            }
            w.claimed = true;
        }
        out.success = true;
        if (did) {
            out.message = out.claimedAmount > 0
                    ? "签到成功，领取 " + out.claimedAmount + " 积分"
                    : "签到成功（上游未返回金额）";
        } else if (lastError != null) {
            // 真发起了领取但被上游拒绝：原样带出失败原因 + 条目摘要
            out.message = "领取失败: " + lastError + "；"
                    + summarizeItems(list.items, skippedJump, skippedInvite);
        } else {
            // 未领到：给出条目摘要，便于一次性定位上游 schema 差异
            out.message = "未领到积分：" + summarizeItems(list.items, skippedJump, skippedInvite);
        }
        return out;
    }

    /** 生成福利条目摘要（type/status/金额），失败提示用，避免只回一句没信息的话。 */
    private static String summarizeItems(List<Welfare> items, int skippedJump, int skippedInvite) {
        if (items == null || items.isEmpty()) {
            return "上游福利列表为空（可能今天已领完或会话被降级）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(items.size()).append(" 项 [");
        int n = 0;
        for (Welfare w : items) {
            if (n++ > 0) {
                sb.append("; ");
            }
            if (n > 6) {
                sb.append("...");
                break;
            }
            sb.append(w.typeNorm).append('/')
                    .append(w.rawStatus == null || w.rawStatus.isEmpty() ? "-" : w.rawStatus)
                    .append('/').append(w.pendingAmount);
            if (w.claimed) {
                sb.append("(已领)");
            }
            if (w.needJump()) {
                sb.append("(需跳转)");
            }
            if (!w.claimableFlag) {
                sb.append("(上游claimable=false)");
            }
        }
        sb.append("]");
        if (skippedInvite > 0) {
            sb.append("，其中 ").append(skippedInvite).append(" 项是邀请类（官网走复制邀请链接）");
        }
        if (skippedJump > 0) {
            sb.append("，其中 ").append(skippedJump).append(" 项需打开链接完成");
        }
        return sb.toString();
    }

    private static boolean jsonOk(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            int c = o.optInt("code", o.optInt("status", 0));
            return c == 0 || c == 200 || o.optBoolean("success", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 claim 响应里尽量挖出本次到账积分（字段名因版本而异，挖不到返回 0）。 */
    private static long extractAmount(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject[] cands = {o, o.optJSONObject("data"), o.optJSONObject("result")};
            String[] keys = {"amount", "benefitAmount", "totalAmount", "pendingTotalAmount",
                    "creditAmount", "points", "value"};
            for (JSONObject c : cands) {
                if (c == null) {
                    continue;
                }
                for (String k : keys) {
                    long v = c.optLong(k, 0);
                    if (v > 0) {
                        return v;
                    }
                }
            }
        } catch (Throwable ignore) {
            // 挖不到就算了，不带金额展示
        }
        return 0;
    }
    // ---------------------------------------------------------------- 剩余额度

    /** 额度信息（benefit/tokens/balance，前端同款字段 used_amount / total_quota）。 */
    public static final class Balance {
        public boolean ok;
        public String message = "";
        public Long usedAmount;
        public Long totalQuota;
        public Long balance() {
            return (usedAmount == null || totalQuota == null) ? null : Math.max(0, totalQuota - usedAmount);
        }
        public Integer percentUsed() {
            if (usedAmount == null || totalQuota == null || totalQuota <= 0) {
                return null;
            }
            return (int) Math.round(usedAmount * 100.0 / totalQuota);
        }
    }

    /**
     * 套餐/积分用量（官网「用量统计」同源）。
     *
     * <p>bundle 证据：GET /snap-manager/v1/statistics/plugin →
     * {package:{package_name_cn,status,spec_code,is_credit_package,is_token_package},
     *  metrics:[{name,usage_token_num,package_token_amount,
     *            package_credit_used,package_credit_amount,package_credit_remain,...}]}；
     * 官网工作台 KPI 取 metrics 里 name=="usageTotalPackageCredit" 的那条算积分，
     * 取 name==spec_code 的那条算 token 用量；usage_* == -1 表示无限。
     */
    public static final class PackageStats {
        public boolean ok;
        public String message = "";
        public String packageName = "";
        public String status = "";
        public String specCode = "";
        public boolean isCreditPackage;
        public boolean isTokenPackage;
        /** 积分：已用 / 总量 / 剩余（官网展示 65.61 / 7500.00，剩余 7434.39）。 */
        public Double creditUsed;
        public Double creditTotal;
        public Double creditRemain;
        /** token：已用 / 总量；null 表示该账号没有 token 计量；unlimited=true 表示无限。 */
        public Double tokenUsed;
        public Double tokenTotal;
        public boolean unlimited;

        public Double creditPercent() {
            if (creditUsed == null || creditTotal == null || creditTotal <= 0) {
                return null;
            }
            return Math.min(100.0, creditUsed * 100.0 / creditTotal);
        }

        public Double tokenPercent() {
            if (tokenUsed == null || tokenTotal == null || tokenTotal <= 0) {
                return null;
            }
            return Math.min(100.0, tokenUsed * 100.0 / tokenTotal);
        }
    }

    /** 数字容错读取：兼容 number 与数字字符串，缺字段返回 null。 */
    private static Double num(JSONObject o, String key) {
        if (o == null) {
            return null;
        }
        Object v = o.opt(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (Throwable ignore) {
                return null;
            }
        }
        return null;
    }

    /** 数值展示：整数不带小数点，小数保留 2 位（与官网 7500.00 / 7434.39 一致）。 */
    public static String fmtNum(Double d) {
        if (d == null) {
            return "-";
        }
        if (Math.abs(d - Math.rint(d)) < 1e-9) {
            return String.valueOf((long) Math.rint(d));
        }
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    /** 拉取套餐/积分用量（官网「用量统计」面板同源接口）。 */
    public static PackageStats fetchPackageStats(String cookie) {
        Resp r = request(cookie, "GET", BASE + "/snap-manager/v1/statistics/plugin", null, null);
        String raw = r.text();
        LogRing.log("balance", "package HTTP " + r.code + " " + trim(raw, 200));
        if (r.code != 200) {
            PackageStats p = new PackageStats();
            p.message = humanError(r.code, raw);
            return p;
        }
        if (looksLikeHtml(raw)) {
            PackageStats p = new PackageStats();
            p.message = "返回登录页 HTML（会话未生效），请重新登录";
            return p;
        }
        return parsePackageStats(raw);
    }

    /** 解析 snap-manager/v1/statistics/plugin 响应（抽出来便于离线回归测试）。 */
    static PackageStats parsePackageStats(String raw) {
        PackageStats p = new PackageStats();
        try {
            JSONObject o = new JSONObject(raw);
            JSONObject pkg = o.optJSONObject("package");
            if (pkg != null) {
                p.packageName = pkg.optString("package_name_cn", pkg.optString("package_name_en", ""));
                p.status = pkg.optString("status", "");
                p.specCode = pkg.optString("spec_code", "");
                p.isCreditPackage = pkg.optBoolean("is_credit_package", false);
                p.isTokenPackage = pkg.optBoolean("is_token_package", false);
            }
            JSONArray metrics = o.optJSONArray("metrics");
            JSONObject creditMetric = null;
            JSONObject tokenMetric = null;
            JSONObject tokenFallback = null;
            if (metrics != null) {
                for (int i = 0; i < metrics.length(); i++) {
                    JSONObject m = metrics.optJSONObject(i);
                    if (m == null) {
                        continue;
                    }
                    String name = m.optString("name", "");
                    if ("usageTotalPackageCredit".equals(name)) {
                        creditMetric = m;
                    }
                    if (!p.specCode.isEmpty() && p.specCode.equals(name)) {
                        tokenMetric = m;
                    }
                    if ("usageTokenChatMessages".equals(name)) {
                        tokenFallback = m;
                    }
                }
            }
            if (tokenMetric == null) {
                tokenMetric = tokenFallback;
            }
            if (creditMetric != null) {
                p.creditUsed = num(creditMetric, "package_credit_used");
                p.creditTotal = num(creditMetric, "package_credit_amount");
                p.creditRemain = num(creditMetric, "package_credit_remain");
            }
            if (tokenMetric != null) {
                Double used = num(tokenMetric, "usage_token_num");
                Double total = num(tokenMetric, "package_token_amount");
                if ((used != null && used < 0) || (total != null && total < 0)) {
                    p.unlimited = true;
                } else {
                    p.tokenUsed = used;
                    p.tokenTotal = total;
                }
            }
            p.ok = pkg != null || creditMetric != null || tokenMetric != null;
            if (!p.ok) {
                p.message = "响应无 package/metrics";
            }
        } catch (Throwable t) {
            p.message = "解析失败: " + t;
        }
        return p;
    }

    /**
     * 拉取剩余额度。bundle 证据：GET /benefit/tokens/balance（无 channel），
     * 响应 error_code="0000" 且 result.used_amount / result.total_quota 为 number 时有效。
     */
    public static Balance fetchBalance(String cookie) {
        Balance b = new Balance();
        Resp r = request(cookie, "GET",
                BASE + "/codebaseservice/v1/cloudagent/benefit/tokens/balance", null, null);
        String raw = r.text();
        LogRing.log("balance", "HTTP " + r.code + " " + trim(raw, 200));
        if (r.code != 200) {
            b.message = humanError(r.code, raw);
            return b;
        }
        if (looksLikeHtml(raw)) {
            b.message = "返回登录页 HTML（会话未生效），请重新登录";
            return b;
        }
        try {
            JSONObject o = new JSONObject(raw);
            if (!"0000".equals(o.optString("error_code"))) {
                b.message = o.optString("error_msg", "code=" + o.optString("error_code"));
                return b;
            }
            JSONObject result = o.optJSONObject("result");
            if (result == null) {
                b.message = "响应无 result";
                return b;
            }
            if (result.has("used_amount") && result.opt("used_amount") instanceof Number) {
                b.usedAmount = result.getLong("used_amount");
            }
            if (result.has("total_quota") && result.opt("total_quota") instanceof Number) {
                b.totalQuota = result.getLong("total_quota");
            }
            if (b.usedAmount == null && b.totalQuota == null) {
                b.message = "响应无 used_amount/total_quota 字段：" + trim(raw, 120);
                return b;
            }
            b.ok = true;
        } catch (Throwable t) {
            b.message = "解析失败: " + t;
        }
        return b;
    }
}
