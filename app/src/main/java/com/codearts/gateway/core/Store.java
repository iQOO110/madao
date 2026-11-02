package com.codearts.gateway.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 本地存储：DevCloud 会话 Cookie、网关端口、局域网开关、网关密钥、模型缓存。 */
public final class Store {

    private static final String K_COOKIE = "devcloud_cookie";
    private static final String K_COOKIE_TIME = "devcloud_cookie_time";
    private static final String K_PORT = "port";
    private static final String K_LAN = "lan";
    private static final String K_AUTOSTART = "autostart";
    private static final String K_APIKEYS = "api_keys";
    private static final String K_MODELS_CACHE = "models_cache";
    private static final String K_TRIM_SYSTEM = "trim_system";
    private static final String K_STREAM_THINKING = "stream_thinking";
    private static final String K_DROP_TOOL_PROTOCOL = "drop_tool_protocol";

    private static volatile Store sInstance;

    private final android.content.SharedPreferences sp;

    private Store(Context ctx) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences("codearts_gateway", Context.MODE_PRIVATE);
    }

    public static Store get(Context ctx) {
        if (sInstance == null) {
            synchronized (Store.class) {
                if (sInstance == null) {
                    sInstance = new Store(ctx);
                }
            }
        }
        return sInstance;
    }

    // ---------------- Cookie ----------------

    public String getCookie() {
        return sp.getString(K_COOKIE, "");
    }

    public void setCookie(String cookie) {
        sp.edit().putString(K_COOKIE, cookie == null ? "" : cookie.trim())
                .putLong(K_COOKIE_TIME, System.currentTimeMillis()).apply();
    }

    public long getCookieTime() {
        return sp.getLong(K_COOKIE_TIME, 0);
    }

    public void clearCookie() {
        sp.edit().remove(K_COOKIE).remove(K_COOKIE_TIME).apply();
    }

    // ---------------- 网络 ----------------

    public int getPort() {
        return sp.getInt(K_PORT, 8080);
    }

    public void setPort(int port) {
        sp.edit().putInt(K_PORT, port).apply();
    }

    public boolean isLanEnabled() {
        return sp.getBoolean(K_LAN, true);
    }

    public void setLanEnabled(boolean b) {
        sp.edit().putBoolean(K_LAN, b).apply();
    }

    public boolean isAutostart() {
        return sp.getBoolean(K_AUTOSTART, false);
    }

    public void setAutostart(boolean b) {
        sp.edit().putBoolean(K_AUTOSTART, b).apply();
    }

    // ---------------- 悬浮球保活 ----------------

    private static final String K_FLOAT = "float_ball";
    private static final String K_FLOAT_HIDDEN = "float_ball_hidden";

    public boolean isFloatBall() {
        return sp.getBoolean(K_FLOAT, false);
    }

    public void setFloatBall(boolean b) {
        sp.edit().putBoolean(K_FLOAT, b).apply();
    }

    // ---------------- 网关密钥 ----------------

    public List<String> getApiKeys() {
        List<String> out = new ArrayList<>();
        String raw = sp.getString(K_APIKEYS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String s = arr.optString(i, "");
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public void saveApiKeys(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String s : list) {
            if (s != null && !s.trim().isEmpty()) {
                arr.put(s.trim());
            }
        }
        sp.edit().putString(K_APIKEYS, arr.toString()).apply();
    }

    public void addApiKey(String key) {
        List<String> list = getApiKeys();
        if (!list.contains(key)) {
            list.add(key);
            saveApiKeys(list);
        }
    }

    public void removeApiKey(String key) {
        List<String> list = getApiKeys();
        list.remove(key);
        saveApiKeys(list);
    }

    public static String newApiKey() {
        String u = java.util.UUID.randomUUID().toString().replace("-", "");
        return "sk-codearts-" + u.substring(0, 24);
    }

    // ---------------- 模型缓存 ----------------

    public String getModelsCache() {
        return sp.getString(K_MODELS_CACHE, "");
    }

    public void setModelsCache(String raw) {
        if (raw != null) {
            sp.edit().putString(K_MODELS_CACHE, raw).apply();
        }
    }

    /** 缓存的模型列表（可能为空）。 */
    public List<ModelInfo> cachedModels() {
        return parseModels(getModelsCache());
    }

    // ---------------- 模型解析（自包含，OpenAI 输出） ----------------

    /** 单个模型。 */
    public static final class ModelInfo {
        public final String id;
        public final String name;
        public final List<String> tags;

        public ModelInfo(String id, String name, List<String> tags) {
            this.id = id;
            this.name = name == null || name.isEmpty() ? id : name;
            this.tags = sanitizeTags(tags);
        }

        /**
         * 标签净化（所有模型来源的唯一出口：内置/福利/缓存/builtin）：
         * 丢掉空标签、上游元数据噪声（如创建者 "admin"）以及纯数字/布尔参数
         * （model_parameters 展开曾把 temperature=0 之类渲染成孤零零的 "0" Chip）。
         */
        private static List<String> sanitizeTags(List<String> tags) {
            List<String> out = new ArrayList<>();
            if (tags == null) {
                return out;
            }
            for (String t : tags) {
                if (t == null) {
                    continue;
                }
                String v = t.trim();
                if (v.isEmpty() || v.equalsIgnoreCase("admin")) {
                    continue;
                }
                if (v.matches("[-+]?\\d+(\\.\\d+)?")
                        || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
                    continue;
                }
                out.add(v);
            }
            return out;
        }
    }

    /** 解析 /model/builtin 响应，兼容 data 数组（对象/字符串）与 map 形态。 */
    public static List<ModelInfo> parseModels(String raw) {
        List<ModelInfo> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(raw == null || raw.trim().isEmpty() ? "{}" : raw);
            JSONArray items = findArray(root, 0);
            JSONObject map = null;
            if (items == null) {
                map = findMap(root, 0);
            }
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    Object o = items.opt(i);
                    String key = "";
                    String label = "";
                    List<String> tags = new ArrayList<>();
                    if (o instanceof String) {
                        key = ((String) o).trim();
                        label = key;
                    } else if (o instanceof JSONObject) {
                        JSONObject m = (JSONObject) o;
                        key = firstNonEmpty(m, "key", "id", "model", "model_id", "model_name", "name");
                        label = m.optString("name", key);
                        JSONArray ta = m.optJSONArray("tags");
                        if (ta != null) {
                            for (int j = 0; j < ta.length(); j++) {
                                String t = ta.optString(j, "");
                                if (!t.isEmpty()) {
                                    tags.add(t);
                                }
                            }
                        }
                    }
                    if (!key.isEmpty() && !hasModel(out, key)) {
                        out.add(new ModelInfo(key, label, tags));
                    }
                }
            } else if (map != null) {
                java.util.Iterator<String> it = map.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONObject v = map.optJSONObject(k);
                    String label = v == null ? k : v.optString("name", k);
                    List<String> tags = new ArrayList<>();
                    if (v != null) {
                        JSONArray ta = v.optJSONArray("tags");
                        if (ta != null) {
                            for (int j = 0; j < ta.length(); j++) {
                                String t = ta.optString(j, "");
                                if (!t.isEmpty()) {
                                    tags.add(t);
                                }
                            }
                        }
                    }
                    if (!hasModel(out, k)) {
                        out.add(new ModelInfo(k, label, tags));
                    }
                }
            }
        } catch (Throwable t) {
            LogRing.log("models", "解析失败: " + t);
        }
        return out;
    }

    /** 转 OpenAI /v1/models 报文。 */
    public static String modelsToOpenAi(List<ModelInfo> models) {
        JSONArray data = new JSONArray();
        try {
            for (ModelInfo m : models) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("object", "model");
                o.put("created", 1700000000);
                o.put("owned_by", "codearts-devcloud");
                o.put("name", m.name);
                o.put("tags", new JSONArray(m.tags));
                data.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("object", "list");
            root.put("data", data);
            return root.toString();
        } catch (Throwable ignored) {
            return "{\"object\":\"list\",\"data\":[]}";
        }
    }

    private static boolean hasModel(List<ModelInfo> list, String id) {
        for (ModelInfo m : list) {
            if (m.id.equals(id)) {
                return true;
            }
        }
        return false;
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

    private static JSONArray findArray(JSONObject o, int depth) {
        if (depth > 4) {
            return null;
        }
        JSONArray direct = o.optJSONArray("data");
        if (direct != null && direct.length() > 0 && (direct.opt(0) instanceof String
                || direct.opt(0) instanceof JSONObject)) {
            return direct;
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject) {
                JSONArray r = findArray((JSONObject) v, depth + 1);
                if (r != null) {
                    return r;
                }
            } else if (v instanceof JSONArray && depth < 4) {
                JSONArray arr = (JSONArray) v;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject inner = arr.optJSONObject(i);
                    if (inner != null) {
                        JSONArray r = findArray(inner, depth + 1);
                        if (r != null) {
                            return r;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static JSONObject findMap(JSONObject o, int depth) {
        if (depth > 4) {
            return null;
        }
        Object data = o.opt("data");
        if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            if (d.length() > 0) {
                java.util.Iterator<String> it = d.keys();
                if (it.hasNext()) {
                    Object first = d.opt(it.next());
                    if (first instanceof JSONObject || first instanceof String) {
                        return d;
                    }
                }
            }
        }
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            Object v = o.opt(it.next());
            if (v instanceof JSONObject) {
                JSONObject r = findMap((JSONObject) v, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
    /** 兼容旧网关日志字段的账号描述。 */
    public String getActiveName() {
        String c = getCookie();
        return c.isEmpty() ? "未登录" : "DevCloud 会话（…" + c.substring(Math.max(0, c.length() - 6)) + "）";
    }
    // ---------------- CPU 保活（WakeLock） ----------------

    private static final String K_WAKELOCK = "wake_lock";

    // ---------------- 工具/MCP 设置（v1.9.0） ----------------
    private static final String K_TOOL_BASE = "tool_base";
    private static final String K_TOOL_KEY = "tool_key";
    private static final String K_TOOL_MODEL = "tool_model";
    private static final String K_MCP_SERVERS = "mcp_servers";
    private static final String K_MCP_ENABLED = "mcp_enabled";
    private static final String K_ALLOW_MUTATING = "allow_mutating";
    public static final String DEFAULT_MCP_SERVERS =
            "[{\"name\":\"mt\",\"url\":\"http://127.0.0.1:8780/mcp\",\"token\":\"\"}]";

    /** 工具模式上游地址（原生支持 function calling 的 OpenAI 兼容接口）。 */
    public String getToolBase() {
        return sp.getString(K_TOOL_BASE, "");
    }

    public void setToolBase(String v) {
        sp.edit().putString(K_TOOL_BASE, v == null ? "" : v.trim()).apply();
    }

    public String getToolKey() {
        return sp.getString(K_TOOL_KEY, "");
    }

    public void setToolKey(String v) {
        sp.edit().putString(K_TOOL_KEY, v == null ? "" : v.trim()).apply();
    }

    public String getToolModel() {
        return sp.getString(K_TOOL_MODEL, "sn-glm-5-3");
    }

    public void setToolModel(String v) {
        sp.edit().putString(K_TOOL_MODEL, v == null ? "" : v.trim()).apply();
    }

    /** MCP 服务器列表（JSON 数组字符串）。 */
    public String getMcpServers() {
        return sp.getString(K_MCP_SERVERS, DEFAULT_MCP_SERVERS);
    }

    public void setMcpServers(String v) {
        sp.edit().putString(K_MCP_SERVERS, v == null ? DEFAULT_MCP_SERVERS : v.trim()).apply();
    }

    /** 是否自动把 MCP 工具喂给模型（B 方案开关）。 */
    public boolean isMcpEnabled() {
        return sp.getBoolean(K_MCP_ENABLED, true);
    }

    public void setMcpEnabled(boolean b) {
        sp.edit().putBoolean(K_MCP_ENABLED, b).apply();
    }

    /** 是否允许自动执行写入类 MCP 工具（默认 false，只放只读类）。 */
    public boolean isAllowMutating() {
        return sp.getBoolean(K_ALLOW_MUTATING, false);
    }

    public void setAllowMutating(boolean b) {
        sp.edit().putBoolean(K_ALLOW_MUTATING, b).apply();
    }

    public boolean isWakeLock() {
        return sp.getBoolean(K_WAKELOCK, true);
    }

    public void setWakeLock(boolean b) {
        sp.edit().putBoolean(K_WAKELOCK, b).apply();
    }
    // ---------------- 常驻通知保护 ----------------

    private static final String K_NOTIFY = "notify_guard";

    /** 常驻通知保护：true=通知可见（前台优先级最高）；false=静默前台（仍保优先级）。 */
    public boolean isNotifyGuard() {
        return sp.getBoolean(K_NOTIFY, true);
    }

    public void setNotifyGuard(boolean b) {
        sp.edit().putBoolean(K_NOTIFY, b).apply();
    }

    /** 是否把 system 消息压缩成一行摘要（v1.9.10）：避免每轮把整段 system prompt 塞进码道。 */
    public boolean isTrimSystem() {
        return sp.getBoolean(K_TRIM_SYSTEM, true);
    }

    public void setTrimSystem(boolean b) {
        sp.edit().putBoolean(K_TRIM_SYSTEM, b).apply();
    }

    /** 是否把思考过程（reasoning_content）透出给客户端（v1.9.10，默认开）。 */
    public boolean isStreamThinking() {
        return sp.getBoolean(K_STREAM_THINKING, true);
    }

    public void setStreamThinking(boolean b) {
        sp.edit().putBoolean(K_STREAM_THINKING, b).apply();
    }

    /**
     * 是否丢弃客户端携带的 tools 与「工具调用协议」文本段（v1.9.10，默认开）：
     * agent（如 DSH）自己执行工具，码道侧不需要知道 DSH 的工具清单，避免每轮
     * 全量 schema 反复注入、稀释对话。关闭则把 tools 转给码道（走 ToolShim 协议）。
     */
    public boolean isDropToolProtocol() {
        return sp.getBoolean(K_DROP_TOOL_PROTOCOL, true);
    }

    public void setDropToolProtocol(boolean b) {
        sp.edit().putBoolean(K_DROP_TOOL_PROTOCOL, b).apply();
    }
    /** 用户长按隐藏标记：主页不再自动拉起悬浮球，直到重新保存开关。 */
    public boolean isFloatBallHidden() {
        return sp.getBoolean(K_FLOAT_HIDDEN, false);
    }

    public void setFloatBallHidden(boolean b) {
        sp.edit().putBoolean(K_FLOAT_HIDDEN, b).apply();
    }
}
