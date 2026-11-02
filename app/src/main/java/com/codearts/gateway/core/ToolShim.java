package com.codearts.gateway.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI function calling 垫片（shim）。
 *
 * <p>背景：码道上游的对话接口
 * （{@code /codebaseservice/v1/cloudagent/sessions/{id}/messages}）只接受
 * {@code {content, model_id, repos}}，没有任何"客户端工具定义"入口——它的 MCP 是
 * 平台侧「连接器」。因此第三方 OpenAI 兼容客户端携带的 {@code tools} 会被上游直接忽略，
 * 模型永远不会回 {@code tool_calls}，表现为"MCP 工具调不动"。
 *
 * <p>本类把工具调用在网关这一侧"造"出来：
 * <ol>
 *   <li><b>请求方向</b>：把 tools 的 JSON Schema 转成一段强约束的文本协议附在 query 末尾；
 *       历史里的 {@code assistant.tool_calls} / {@code role=tool} 也渲染成同一格式，
 *       让模型看得见自己之前的调用与工具返回。</li>
 *   <li><b>响应方向</b>：扫描模型输出中的 {@code <|tool_call|>...<|/tool_call|>} 块，
 *       剥离哨兵并合成标准 OpenAI {@code tool_calls}（流式分片 / 非流式 message 两种形态）。</li>
 * </ol>
 *
 * <p>只有当请求真的带 tools 时才启用，普通对话路径完全不受影响。
 */
public final class ToolShim {

    /** 调用块起止哨兵：ASCII + 特殊包裹，正常文本几乎不可能自然出现。 */
    public static final String START = "<|tool_call|>";
    public static final String END = "<|/tool_call|>";

    private ToolShim() {
    }

    // ------------------------------------------------------------------ 请求方向

    /** 请求是否要启用工具垫片（有 tools 且 tool_choice != none）。 */
    public static boolean requestHasTools(JSONObject openai) {
        if (openai == null) {
            return false;
        }
        JSONArray tools = openai.optJSONArray("tools");
        if (tools == null || tools.length() == 0) {
            return false;
        }
        String choice = openai.optString("tool_choice", "auto");
        return !"none".equalsIgnoreCase(choice == null ? "" : choice.trim());
    }

    /** 生成注入到 query 末尾的工具协议说明。 */
    public static String buildInstruction(JSONObject openai) {
        JSONArray tools = openai == null ? null : openai.optJSONArray("tools");
        StringBuilder sb = new StringBuilder();
        sb.append("【工具调用协议】\n");
        sb.append("本次会话已为你注册下列可调用工具。需要工具时，只输出调用块，")
                .append("不要输出任何解释或多余文字：\n");
        sb.append(START).append("{\"name\":\"工具名\",\"arguments\":{参数JSON}}").append(END).append('\n');
        sb.append("规则：\n")
                .append("1) 需要多个工具时可连续输出多个调用块，每块一行；\n")
                .append("2) arguments 必须是合法 JSON 对象，键名与下方 parameters 一致；\n")
                .append("3) 不需要工具时正常回答即可，不要输出任何调用块；\n")
                .append("4) 调用块内的 JSON 不要换行、不要包 markdown 代码围栏。\n");
        String choice = openai == null ? "auto" : openai.optString("tool_choice", "auto");
        if ("required".equalsIgnoreCase(choice)) {
            sb.append("5) 本次必须调用至少一个工具。\n");
        }
        sb.append("\n可用工具（OpenAI function schema 原样列出）：\n");
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) {
                    continue;
                }
                JSONObject f = t.optJSONObject("function");
                sb.append(f != null ? f.toString() : t.toString()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 把历史里的 assistant.tool_calls 渲染成同款调用块文本。 */
    public static String renderAssistantToolCalls(JSONArray toolCalls) {
        if (toolCalls == null || toolCalls.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject c = toolCalls.optJSONObject(i);
            if (c == null) {
                continue;
            }
            JSONObject f = c.optJSONObject("function");
            String name = f != null ? f.optString("name", "") : first(c, "name", "tool", "tool_name");
            String args = f != null ? f.optString("arguments", "") : first(c, "arguments", "args", "parameters");
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(START).append("{\"name\":").append(JSONObject.quote(name))
                    .append(",\"arguments\":").append(embedJson(args)).append('}').append(END);
        }
        return sb.toString();
    }

    /** 把 role=tool 的结果消息渲染成文本。 */
    public static String renderToolResult(JSONObject m) {
        String name = first(m, "name", "tool_name");
        String id = m.optString("tool_call_id", "");
        String text = ProtocolAdapter.flattenContent(m.opt("content"));
        StringBuilder sb = new StringBuilder("[工具结果]");
        if (name != null && !name.isEmpty()) {
            sb.append(' ').append(name);
        }
        if (!id.isEmpty()) {
            sb.append(" (").append(id).append(')');
        }
        sb.append(": ").append(text == null ? "" : text);
        return sb.toString();
    }

    // ------------------------------------------------------------------ 数据结构

    /** 一次工具调用。 */
    public static final class Call {
        public final String id;
        public final String name;
        public final String arguments;

        public Call(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments == null ? "{}" : arguments;
        }
    }

    /** 非流式解析结果：对外文本 + 调用列表。 */
    public static final class Parsed {
        public final String content;
        public final List<Call> calls;

        public Parsed(String content, List<Call> calls) {
            this.content = content == null ? "" : content;
            this.calls = calls == null ? new ArrayList<Call>() : calls;
        }
    }

    public static String newCallId() {
        return "call_" + Long.toHexString(System.nanoTime())
                + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    // ------------------------------------------------------------------ 非流式解析

    /** 剥离哨兵块：得到对外文本与调用列表；解析失败的块按普通文本保留（不吞内容）。 */
    public static Parsed parse(String full) {
        return parse(full, null);
    }

    /** 同上，并把工具名对齐到客户端已声明的名称（{@code declared} 可为 null）。 */
    public static Parsed parse(String full, List<String> declared) {
        StringBuilder text = new StringBuilder();
        List<Call> calls = new ArrayList<>();
        String s = full == null ? "" : full;
        int pos = 0;
        while (true) {
            int a = s.indexOf(START, pos);
            if (a < 0) {
                text.append(s.substring(pos));
                break;
            }
            text.append(s, pos, a);
            int b = s.indexOf(END, a + START.length());
            if (b < 0) {
                String body = s.substring(a + START.length());
                if (!absorb(body, calls, declared)) {
                    text.append(s.substring(a));
                }
                break;
            }
            String body = s.substring(a + START.length(), b);
            if (!absorb(body, calls, declared)) {
                text.append(START).append(body).append(END);
            }
            pos = b + END.length();
        }
        return new Parsed(text.toString(), calls);
    }

    /** 从一个块体里提取调用（可能含多个顶层 JSON 对象）；一个都没解析出来返回 false。 */
    static boolean absorb(String body, List<Call> out) {
        return absorb(body, out, null);
    }

    /**
     * 从一个块体里提取调用；JSON 不合法时用正则兜底捞取 name/arguments，
     * 名称一律对齐到客户端已声明的工具名（{@code declared} 可为 null）。
     */
    static boolean absorb(String body, List<Call> out, List<String> declared) {
        List<String> objs = extractJsonObjects(body);
        int before = out.size();
        for (String o : objs) {
            Call c = toCall(o, declared);
            if (c != null) {
                out.add(c);
            }
        }
        if (out.size() > before) {
            return true;
        }
        Call salvaged = salvage(body, declared);
        if (salvaged != null) {
            out.add(salvaged);
            return true;
        }
        return false;
    }

    /**
     * JSON 解析失败时的兜底：用正则把 {@code "name":"mt_xxx"} 与后面的第一个 JSON 对象捞出来。
     * 典型场景：模型吐出 {@code {"name":"mt_apk_open","arguments":{...}} 但括号没闭合/含中文注释}。
     */
    static Call salvage(String body, List<String> declared) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String name = null;
        java.util.regex.Matcher mn = java.util.regex.Pattern
                .compile("\"?(?:name|tool|tool_name)\"?\\s*[:=]\\s*\"?([A-Za-z_][A-Za-z0-9_.\\-]*)\"?")
                .matcher(body);
        if (mn.find()) {
            name = mn.group(1);
        }
        if (name == null || name.isEmpty()) {
            return null;
        }
        String args = "{}";
        int ai = body.indexOf("arguments");
        if (ai < 0) {
            ai = body.indexOf("args");
        }
        if (ai >= 0) {
            int brace = body.indexOf('{', ai);
            if (brace >= 0) {
                List<String> objs = extractJsonObjects(body.substring(brace));
                if (!objs.isEmpty()) {
                    args = normalizeArgs(objs.get(0));
                }
            }
        }
        return new Call(newCallId(), normalizeName(name, declared), args);
    }

    /**
     * 把模型给出的名称对齐到客户端已声明的工具名：完全一致 → 忽略大小写/符号 → 唯一包含匹配。
     * 都不命中则原样返回（宁可让客户端报错，也不要张冠李戴）。
     */
    static String normalizeName(String name, List<String> declared) {
        if (name == null || name.isEmpty() || declared == null || declared.isEmpty()) {
            return name;
        }
        for (String d : declared) {
            if (d != null && d.equals(name)) {
                return d;
            }
        }
        String nl = norm(name);
        if (nl.isEmpty()) {
            return name;
        }
        for (String d : declared) {
            if (d != null && norm(d).equals(nl)) {
                return d;
            }
        }
        String hit = null;
        int hits = 0;
        for (String d : declared) {
            if (d == null) {
                continue;
            }
            String dl = norm(d);
            if (!dl.isEmpty() && (dl.contains(nl) || nl.contains(dl))) {
                hit = d;
                hits++;
            }
        }
        return hits == 1 ? hit : name;
    }

    private static String norm(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 按花括号配平抽取顶层 JSON 对象（忽略字符串内的括号与转义）。 */
    static List<String> extractJsonObjects(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && s.charAt(i) != '{') {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            boolean closed = false;
            for (; i < n; i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (ch == '\\') {
                        esc = true;
                    } else if (ch == '"') {
                        inStr = false;
                    }
                    continue;
                }
                if (ch == '"') {
                    inStr = true;
                } else if (ch == '{') {
                    depth++;
                } else if (ch == '}') {
                    depth--;
                    if (depth == 0) {
                        i++;
                        closed = true;
                        break;
                    }
                }
            }
            if (!closed) {
                break;
            }
            out.add(s.substring(start, i));
        }
        return out;
    }

    /** 单个 JSON 对象 → Call（兼容 function 嵌套与多种字段别名）。 */
    static Call toCall(String json) {
        return toCall(json, null);
    }

    /** 单个 JSON 对象 → Call，并把名称对齐到客户端已声明的工具名。 */
    static Call toCall(String json, List<String> declared) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject f = o.optJSONObject("function");
            String name = first(o, "name", "tool", "tool_name", "toolName");
            String args = first(o, "arguments", "args", "parameters", "input");
            if (f != null) {
                String fn = f.optString("name", "");
                if (fn != null && !fn.isEmpty()) {
                    name = fn;
                }
                String fa = first(f, "arguments", "args", "parameters", "input");
                if (fa != null && !fa.isEmpty()) {
                    args = fa;
                }
            }
            if (name == null || name.isEmpty()) {
                return null;
            }
            return new Call(newCallId(), normalizeName(name, declared), normalizeArgs(args));
        } catch (Throwable t) {
            return null;
        }
    }

    /** arguments 归一化成 JSON 字符串（对象原样、数组包一层、其它尽量兜底成对象）。 */
    static String normalizeArgs(String args) {
        String s = args == null ? "" : args.trim();
        if (s.isEmpty()) {
            return "{}";
        }
        if (s.startsWith("{")) {
            try {
                new JSONObject(s);
                return s;
            } catch (Throwable ignore) {
                // 落到下面兜底
            }
        }
        if (s.startsWith("[")) {
            try {
                JSONArray arr = new JSONArray(s);
                JSONObject w = new JSONObject();
                w.put("value", arr);
                return w.toString();
            } catch (Throwable ignore) {
                // 落到下面兜底
            }
        }
        JSONObject kv = new JSONObject();
        try {
            boolean any = false;
            for (String part : s.split("[,;\\n]|\\s+(?=[A-Za-z_][A-Za-z0-9_]*\\s*=)")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
                    any = true;
                }
            }
            if (any) {
                return kv.toString();
            }
        } catch (Throwable ignore) {
            // 落到下面兜底
        }
        JSONObject w = new JSONObject();
        try {
            w.put("value", s);
        } catch (Throwable ignore) {
            // 忽略
        }
        return w.toString();
    }

    // ------------------------------------------------------------------ 流式状态机

    /**
     * 流式解析：把模型输出里的调用块剥离，返回可立即下发的普通文本。
     *
     * <p>为避免哨兵被切成两半时泄漏给客户端，未出现哨兵时会保留末尾
     * {@code START.length()-1} 个字符不下发，等下一段增量再做判断。
     */
    public static final class Stream {
        private final StringBuilder buf = new StringBuilder();
        private final List<Call> ready = new ArrayList<>();
        private final List<String> declared;
        private boolean callSeen;

        public Stream() {
            this(null);
        }

        /** @param declared 客户端已声明的工具名（用于把模型吐出的错名对齐），可为 null */
        public Stream(List<String> declared) {
            this.declared = declared;
        }

        /** 喂入一段上游增量，返回本次可立即作为 content 输出的文本（可能为空串）。 */
        public String feed(String delta) {
            if (delta != null && !delta.isEmpty()) {
                buf.append(delta);
            }
            return scan(false);
        }

        /** 取走已完成、待下发的调用（取走后清空）。 */
        public List<Call> drain() {
            if (ready.isEmpty()) {
                return Collections.emptyList();
            }
            List<Call> out = new ArrayList<>(ready);
            ready.clear();
            return out;
        }

        /** 是否出现过至少一次调用。 */
        public boolean sawCall() {
            return callSeen;
        }

        /** 流结束时收尾：返回剩余文本，未闭合的调用块按宽容策略处理。 */
        public String flush() {
            String s = scan(true);
            if (buf.length() == 0) {
                return s;
            }
            String rest = buf.toString();
            buf.setLength(0);
            int a = rest.indexOf(START);
            if (a >= 0) {
                String body = rest.substring(a + START.length());
                List<Call> tmp = new ArrayList<>();
                if (absorb(body, tmp, declared)) {
                    ready.addAll(tmp);
                    callSeen = true;
                    return s + rest.substring(0, a);
                }
                return s + rest;
            }
            return s + rest;
        }

        /** 末尾有多少个字符恰好是 START 的前缀（只有这种才需要暂缓下发）。 */
        private int holdBack() {
            int max = Math.min(buf.length(), START.length());
            for (int k = max; k > 0; k--) {
                if (buf.substring(buf.length() - k).equals(START.substring(0, k))) {
                    return k;
                }
            }
            return 0;
        }

        private String scan(boolean atEnd) {
            StringBuilder out = new StringBuilder();
            while (true) {
                int a = buf.indexOf(START);
                if (a < 0) {
                    // 没有 START：只保留"末尾恰好是哨兵前缀"的那几个字符，
                    // 其余立即下发（既防哨兵被切碎时泄漏，也不额外增加延迟）
                    int keep = atEnd ? 0 : holdBack();
                    int flush = buf.length() - keep;
                    if (flush > 0) {
                        out.append(buf, 0, flush);
                        buf.delete(0, flush);
                    }
                    return out.toString();
                }
                if (a > 0) {
                    out.append(buf, 0, a);
                    buf.delete(0, a);
                }
                int b = buf.indexOf(END, START.length());
                if (b < 0) {
                    return out.toString();
                }
                String body = buf.substring(START.length(), b);
                List<Call> tmp = new ArrayList<>();
                if (absorb(body, tmp, declared)) {
                    ready.addAll(tmp);
                    callSeen = true;
                } else {
                    out.append(START).append(body).append(END);
                }
                buf.delete(0, b + END.length());
            }
        }
    }

    // ------------------------------------------------------------------ 响应组装

    /** 流式：tool_calls 起始分片（带 id/type/name，arguments 为空串）。 */
    public static String toolCallStartChunk(String completionId, String model, int index, Call c) {
        JSONObject tc = new JSONObject();
        JSONObject delta = new JSONObject();
        try {
            tc.put("index", index);
            tc.put("id", c.id);
            tc.put("type", "function");
            JSONObject f = new JSONObject();
            f.put("name", c.name);
            f.put("arguments", "");
            tc.put("function", f);
            JSONArray arr = new JSONArray();
            arr.put(tc);
            delta.put("tool_calls", arr);
        } catch (Throwable ignored) {
            // 忽略
        }
        return ProtocolAdapter.chunk(completionId, model, delta.toString(), null);
    }

    /** 便捷重载：直接用工具上游的调用对象（字段与 ToolShim.Call 同义）。 */
    public static String toolCallStartChunk(String completionId, String model, int index,
                                            String id, String name, String arguments) {
        return toolCallStartChunk(completionId, model, index,
                new Call(id, name, arguments));
    }

    /** 流式：tool_calls 参数分片。 */
    public static String toolCallArgsChunk(String completionId, String model, int index, String args) {
        JSONObject tc = new JSONObject();
        JSONObject delta = new JSONObject();
        try {
            tc.put("index", index);
            JSONObject f = new JSONObject();
            f.put("arguments", args == null ? "{}" : args);
            tc.put("function", f);
            JSONArray arr = new JSONArray();
            arr.put(tc);
            delta.put("tool_calls", arr);
        } catch (Throwable ignored) {
            // 忽略
        }
        return ProtocolAdapter.chunk(completionId, model, delta.toString(), null);
    }

    /** 非流式完整报文（带 tool_calls 时 content 置空串、finish_reason=tool_calls）。 */
    public static String completion(String completionId, String model, String content, List<Call> calls) {
        return completion(completionId, model, content, calls, null);
    }

    /**
     * 非流式完整报文，可携带思考过程（reasoning_content）。
     *
     * <p>v1.9.10：码道 SSE 的 "thought" 事件原本只在流式路径透出；同步路径也
     * 在 message 里带上该字段，与 DeepSeek/OpenAI 生态的约定一致（不需要的客户端直接忽略）。
     */
    public static String completion(String completionId, String model, String content,
                                    List<Call> calls, String reasoning) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", completionId);
            o.put("object", "chat.completion");
            o.put("created", System.currentTimeMillis() / 1000L);
            o.put("model", model == null || model.isEmpty() ? "codearts" : model);
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            boolean hasCalls = calls != null && !calls.isEmpty();
            msg.put("content", content == null ? "" : content);
            if (reasoning != null && !reasoning.isEmpty()) {
                msg.put("reasoning_content", reasoning);
            }
            if (hasCalls) {
                JSONArray arr = new JSONArray();
                for (Call c : calls) {
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
            c0.put("finish_reason", hasCalls ? "tool_calls" : "stop");
            choices.put(c0);
            o.put("choices", choices);
            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            o.put("usage", usage);
        } catch (Throwable ignored) {
            // 忽略
        }
        return o.toString();
    }

    // ------------------------------------------------------------------ 小工具

    private static String first(JSONObject o, String... keys) {
        if (o == null) {
            return "";
        }
        for (String k : keys) {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) {
                continue;
            }
            if (v instanceof String) {
                String s = ((String) v).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            } else if (v instanceof JSONObject || v instanceof JSONArray) {
                return v.toString();
            } else {
                return String.valueOf(v);
            }
        }
        return "";
    }

    /** 把一段"可能是 JSON 也可能不是"的参数文本安全嵌入到对象字面量里。 */
    private static String embedJson(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return "{}";
        }
        if (s.startsWith("{")) {
            try {
                new JSONObject(s);
                return s;
            } catch (Throwable ignore) {
                // 继续
            }
        }
        if (s.startsWith("[")) {
            try {
                new JSONArray(s);
                return s;
            } catch (Throwable ignore) {
                // 继续
            }
        }
        return JSONObject.quote(s);
    }
}
