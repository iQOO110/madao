package com.codearts.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * 协议适配层：OpenAI Chat Completions （请求/响应） <-> Intern InkStone orbit 自定义 SSE。
 *
 * <p>Orbit 侧事实（来自前端 bundle 逆向）：
 * <ul>
 *   <li>请求体：{@code {scenario:0, thinking_mode, planning_mode, model, space_id, query, chat_id}}</li>
 *   <li>SSE data 中的增量字段：{@code answer | delta | response | (event=="message" ? text)}</li>
 *   <li>思考增量字段：{@code thinking} 或 事件名含 thinking 时的 {@code text}</li>
 *   <li>结束事件：{@code message_end / done / close / error / workflow_finished}</li>
 *   <li>会话号：{@code chat_id | cid | chatId}</li>
 * </ul>
 */
public final class ProtocolAdapter {

    private ProtocolAdapter() {
    }

    // ---------------------------------------------------------------- 请求方向

    /** 把 OpenAI messages 里的最后一条 user 内容抽成 orbit 的 query 字段。 */
    public static String extractQuery(JSONArray messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String role = m.optString("role", "");
            if (!"user".equals(role)) {
                continue;
            }
            Object content = m.opt("content");
            String text = flattenContent(content);
            if (!text.isEmpty()) {
                return text;
            }
        }
        // 没有 user 消息时退化为拼接全部内容，避免直接失败
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m != null) {
                sb.append(flattenContent(m.opt("content")));
            }
        }
        return sb.toString();
    }

    /** OpenAI messages → 纯文本（DevCloud 等把整段对话拍平成一条 content 的上游用）。 */
    public static String openaiToQueryText(JSONObject openai) {
        if (openai == null) {
            return "";
        }
        JSONArray messages = openai.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) {
                continue;
            }
            String role = m.optString("role", "user");
            String text = flattenContent(m.opt("content"));
            if (text.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            if ("system".equals(role)) {
                sb.append("[系统] ").append(text);
            } else if ("assistant".equals(role)) {
                sb.append("[助手] ").append(text);
            } else {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    /** content 既可能是字符串，也可能是 [{type:"text",text:"..."}] 数组。 */
    public static String flattenContent(Object content) {
        if (content == null || content == JSONObject.NULL) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                if (item instanceof JSONObject) {
                    JSONObject o = (JSONObject) item;
                    String t = o.optString("text", "");
                    if (!t.isEmpty()) {
                        sb.append(t);
                        continue;
                    }
                    // 多模态 / 非文本块：保留占位，避免整条 user 消息被当成空内容丢弃
                    String type = o.optString("type", "");
                    if (type.contains("image")) {
                        sb.append("[图片]");
                    } else if (type.contains("audio")) {
                        sb.append("[音频]");
                    } else if (type.contains("file") || type.contains("document")) {
                        String name = o.optString("filename", o.optString("name", ""));
                        sb.append(name.isEmpty() ? "[文件]" : "[文件: " + name + "]");
                    } else if ("tool_result".equals(type)) {
                        // Anthropic 风格：内容块可能再嵌套一层 content
                        String inner = flattenContent(o.opt("content"));
                        if (!inner.isEmpty()) {
                            sb.append(inner);
                        }
                    }
                } else if (item instanceof String) {
                    sb.append((String) item);
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    /**
     * 组装 orbit 请求体。
     *
     * @param openai      原始 OpenAI 请求体
     * @param chatId      已有会话号；&lt;=0 表示新建会话
     * @param defThinking 站点默认是否开启深度思考
     * @param defPlanning 站点默认是否开启规划模式
     */
    public static JSONObject buildOrbitBody(JSONObject openai, long chatId,
                                            boolean defThinking, boolean defPlanning) {
        JSONObject body = new JSONObject();
        try {
            body.put("scenario", 0);
            body.put("query", extractQuery(openai.optJSONArray("messages")));

            boolean thinking = openai.has("thinking_mode")
                    ? openai.optBoolean("thinking_mode", defThinking) : defThinking;
            boolean planning = openai.has("planning_mode")
                    ? openai.optBoolean("planning_mode", defPlanning) : defPlanning;
            body.put("thinking_mode", thinking);
            body.put("planning_mode", planning);

            String model = openai.optString("model", "").trim();
            if (!model.isEmpty() && !"auto".equalsIgnoreCase(model)
                    && !"default".equalsIgnoreCase(model)) {
                body.put("model", model);
            }

            if (chatId > 0) {
                body.put("chat_id", chatId);
            }

            // 透传若干可选字段（存在才带）
            copyIfPresent(openai, body, "space_id");
            copyIfPresent(openai, body, "files");
            copyIfPresent(openai, body, "mentions");
            copyIfPresent(openai, body, "extra");
        } catch (Throwable t) {
            LogRing.log("adapter", "buildOrbitBody 异常: " + t);
        }
        return body;
    }

    private static void copyIfPresent(JSONObject src, JSONObject dst, String key) {
        if (src.has(key) && !src.isNull(key)) {
            try {
                dst.put(key, src.get(key));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 发消息的 URL。
     *
     * <p>逆向证据：前端 `ra=(e,t,o=Ro)=> e ? e : (t==null ? o : `${o}/${t}`)`，
     * 而发消息时传入的 `endpoint` 恒为 {@code /api/orbit/v1/chat}（非空字符串），
     * 因此 URL 永远是 {@code /api/orbit/v1/chat}，<b>不会</b>拼上 chat_id；
     * 会话号只通过请求体的 {@code chat_id} 字段传递。
     * （{@code /chat/{id}} 是拉历史记录的 GET 路由，用它发消息只会拿到空响应。）
     */
    public static String chatUrl(long chatId) {
        return AppConfig.ORBIT_BASE + "/chat";
    }

    /** 拉取指定会话的历史记录（GET）。 */
    public static String historyUrl(long chatId) {
        return AppConfig.ORBIT_BASE + "/chat/" + chatId;
    }

    public static String modelsUrl() {
        return AppConfig.ORBIT_BASE + "/models";
    }

    // ---------------------------------------------------------------- 响应方向：orbit -> OpenAI

    /** 取事件名。 */
    public static String eventName(JSONObject obj, String sseEventLine) {
        String e = obj.optString("event", "");
        if (e.isEmpty()) {
            e = obj.optString("type", "");
        }
        if (e.isEmpty()) {
            e = sseEventLine == null ? "" : sseEventLine;
        }
        return e;
    }

    public static boolean isTerminal(String event) {
        if (event == null) {
            return false;
        }
        switch (event) {
            case "message_end":
            case "done":
            case "close":
            case "error":
            case "workflow_finished":
                return true;
            default:
                return false;
        }
    }

    public static boolean isHardStop(String event) {
        if (event == null) {
            return false;
        }
        switch (event) {
            case "done":
            case "close":
            case "workflow_finished":
                return true;
            default:
                return false;
        }
    }

    /** 从 SSE data 里抽正文增量，没有增量返回 null。 */
    public static String extractDelta(JSONObject obj) {
        Object v = firstString(obj, "answer", "delta", "response");
        if (v == null) {
            String ev = obj.optString("event", obj.optString("type", ""));
            if ("message".equals(ev)) {
                v = firstString(obj, "text");
            }
        }
        return v == null ? null : (String) v;
    }

    /** 从 SSE data 里抽思考链增量。 */
    public static String extractThinking(JSONObject obj) {
        Object v = firstString(obj, "thinking", "reasoning_content", "thinking_content");
        if (v == null) {
            String ev = obj.optString("event", obj.optString("type", ""));
            if (ev.contains("thinking") || ev.contains("reasoning")) {
                v = firstString(obj, "text", "content");
            }
        }
        return v == null ? null : (String) v;
    }

    /** 从 SSE data 里抽会话号，0 表示没有。 */
    public static long extractChatId(JSONObject obj) {
        String[] keys = {"chat_id", "cid", "chatId", "conversation_id"};
        for (String k : keys) {
            Object o = obj.opt(k);
            if (o == null || o == JSONObject.NULL) {
                continue;
            }
            if (o instanceof Number) {
                return ((Number) o).longValue();
            }
            try {
                return Long.parseLong(String.valueOf(o).trim());
            } catch (Throwable ignored) {
            }
        }
        return 0L;
    }

    private static Object firstString(JSONObject obj, String... keys) {
        for (String k : keys) {
            Object o = obj.opt(k);
            if (o instanceof String && !((String) o).isEmpty()) {
                return o;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 响应方向：拼 OpenAI 报文

    public static String newCompletionId() {
        return "chatcmpl-" + Long.toHexString(System.nanoTime())
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    /** OpenAI 流式分片。deltaJson 直接作为 delta 对象内容（如 {"role":"assistant"}）。 */
    public static String chunk(String id, String model, String deltaJson, String finishReason) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("object", "chat.completion.chunk");
            o.put("created", System.currentTimeMillis() / 1000L);
            o.put("model", model == null || model.isEmpty() ? "codearts" : model);
            JSONArray choices = new JSONArray();
            JSONObject c = new JSONObject();
            c.put("index", 0);
            c.put("delta", new JSONObject(deltaJson));
            if (finishReason == null) {
                c.put("finish_reason", JSONObject.NULL);
            } else {
                c.put("finish_reason", finishReason);
            }
            choices.put(c);
            o.put("choices", choices);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** OpenAI 非流式完整报文。 */
    public static String completion(String id, String model, String content, String reasoning) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("object", "chat.completion");
            o.put("created", System.currentTimeMillis() / 1000L);
            o.put("model", model == null || model.isEmpty() ? "codearts" : model);
            JSONArray choices = new JSONArray();
            JSONObject c = new JSONObject();
            c.put("index", 0);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", content == null ? "" : content);
            if (reasoning != null && !reasoning.isEmpty()) {
                msg.put("reasoning_content", reasoning);
            }
            c.put("message", msg);
            c.put("finish_reason", "stop");
            choices.put(c);
            o.put("choices", choices);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            o.put("usage", usage);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /** OpenAI 错误报文。 */
    public static String error(String message, String type, String code) {
        JSONObject o = new JSONObject();
        try {
            JSONObject e = new JSONObject();
            e.put("message", message == null ? "unknown error" : message);
            e.put("type", type == null ? "upstream_error" : type);
            e.put("param", JSONObject.NULL);
            e.put("code", code == null ? JSONObject.NULL : code);
            o.put("error", e);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }

    /**
     * 把 orbit 的模型列表转成 OpenAI /v1/models 格式。
     * 兼容 {code,data:[...]} / data.list / data.models / 嵌套包裹 等多种形态。
     */
    /** 单个模型。 */
    public static final class ModelInfo {
        public final String id;
        public final String name;
        public final java.util.List<String> tags;

        public ModelInfo(String id, String name, java.util.List<String> tags) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.tags = tags == null ? new java.util.ArrayList<String>() : tags;
        }
    }

    /**
     * 解析 orbit 的模型列表，兼容多种包裹形态。
     * 这是模型数据的唯一解析入口，网关 /v1/models 与 App 内模型页共用。
     */
    public static java.util.List<ModelInfo> parseModels(String raw) {
        java.util.List<ModelInfo> out = new java.util.ArrayList<>();
        try {
            JSONObject root = new JSONObject(raw == null ? "{}" : raw);
            JSONArray items = findModelArray(root, 0);
            JSONObject mapForm = null;
            if (items == null) {
                mapForm = findModelMap(root, 0);
            }
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Object raw0 = items.opt(i);
                    String key = "";
                    String label = "";
                    java.util.List<String> tags = new java.util.ArrayList<>();
                    if (raw0 instanceof String) {
                        key = ((String) raw0).trim();
                        label = key;
                    } else if (raw0 instanceof JSONObject) {
                        JSONObject src = (JSONObject) raw0;
                        key = firstNonEmpty(src, "key", "id", "model", "model_name", "name");
                        label = src.optString("name", key);
                        tags = stringList(src.optJSONArray("tags"));
                    }
                    if (!key.isEmpty()) {
                        out.add(new ModelInfo(key, label, tags));
                    }
                }
            } else if (mapForm != null) {
                java.util.Iterator<String> it = mapForm.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = mapForm.optJSONObject(k);
                    out.add(new ModelInfo(k, v == null ? k : v.optString("name", k),
                            v == null ? null : stringList(v.optJSONArray("tags"))));
                }
            }
        } catch (Throwable t) {
            LogRing.log("adapter", "模型列表解析失败: " + t);
        }
        return out;
    }

    private static java.util.List<String> stringList(JSONArray arr) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 转成 OpenAI /v1/models 报文。 */
    public static String modelsToOpenAi(String raw) {
        JSONArray out = new JSONArray();
        try {
            for (ModelInfo m : parseModels(raw)) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("object", "model");
                o.put("created", 1700000000);
                o.put("owned_by", "intern-inkstone");
                o.put("name", m.name);
                o.put("tags", new JSONArray(m.tags));
                out.put(o);
            }
        } catch (Throwable ignored) {
        }
        JSONObject root = new JSONObject();
        try {
            root.put("object", "list");
            root.put("data", out);
        } catch (Throwable ignored) {
        }
        return root.toString();
    }

    private static String firstNonEmpty(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** 识别 {"id1":{name:...},"id2":{name:...}} 这种以模型名为键的 Map 形态。 */
    private static JSONObject findModelMap(Object node, int depth) {
        if (node == null || depth > 3) {
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (isModelMap(obj)) {
                return obj;
            }
            String[] preferred = {"data", "models", "list", "result", "payload"};
            for (String k : preferred) {
                JSONObject found = findModelMap(obj.opt(k), depth + 1);
                if (found != null) {
                    return found;
                }
            }
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                JSONObject found = findModelMap(obj.opt(it.next()), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findModelMap(arr.opt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isModelMap(JSONObject o) {
        if (o == null || o.length() == 0) {
            return false;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (!(v instanceof JSONObject)) {
                return false;
            }
            JSONObject vo = (JSONObject) v;
            if (!(vo.has("name") || vo.has("key") || vo.has("id")
                    || vo.has("desc") || vo.has("tags") || vo.has("model"))) {
                return false;
            }
        }
        return true;
    }

    /** 递归找出第一个“看起来像模型列表”的数组。 */
    private static JSONArray findModelArray(Object node, int depth) {
        if (node == null || depth > 4) {
            return null;
        }
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            if (arr.length() > 0) {
                Object first = arr.opt(0);
                if (first instanceof String && !((String) first).trim().isEmpty()) {
                    return arr;
                }
                if (looksLikeModel(arr.optJSONObject(0))) {
                    return arr;
                }
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONArray found = findModelArray(arr.opt(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            // 优先按已知字段找
            String[] preferred = {"data", "list", "models", "records", "items", "result", "rows"};
            for (String k : preferred) {
                Object v = obj.opt(k);
                if (v != null) {
                    JSONArray found = findModelArray(v, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
            java.util.Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                Object v = obj.opt(it.next());
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    JSONArray found = findModelArray(v, depth + 1);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    private static boolean looksLikeModel(JSONObject o) {
        if (o == null) {
            return false;
        }
        return o.has("key") || o.has("id") || o.has("name")
                || o.has("model") || o.has("model_name");
    }

    /** 调试用：把 JSONObject 的键拼成一行。 */
    public static String keysOf(JSONObject o) {
        if (o == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(it.next());
        }
        return sb.toString();
    }
}
