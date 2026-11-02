# 已知问题与 Bug 清单（v1.9.9 / versionCode 39）

> 状态核实说明：本清单逐条对照 v1.9.9 源码现状核实（标注文件:行号）。分两类：
> **A. 现存问题**（当前代码仍未解决，按影响分级）与 **B. 历史已修复**（防止
> 回归的存档，均已验证在当前代码中落实）。技术细节源自开发期排障记录。

## A. 现存问题

### A1. /health 自报版本落后（低）
- `AppConfig.VERSION = "1.9.4"`（`AppConfig.java:18`）与 `app/build.gradle` 的
  `versionName "1.9.9"` 不联动，/health 与部分日志自报 1.9.4；`BUILD_TAG`
  （`v1.9.9+heartbeat`）是正确的。
- 复现：`GET /health` → `"version":"1.9.4"`。
- 建议：改为读 `BuildConfig.VERSION_NAME`，单一事实来源。

### A2. InkStone 移植遗留字符串（低，不影响行为）
- 9 处 `inkstone` 字样：纯注释、剪贴板内部标签 `newPlainText("inkstone",…)`、
  死代码 `ProtocolAdapter.modelsToOpenAi`（`owned_by:"intern-inkstone"`，无调用点）、
  无引用常量 `AppConfig.DEFAULT_ORIGIN`。逐条位置见 `LEGACY-IDENTIFIERS.md`。
- 影响：仅观感与潜在混淆（参见 1.9.4 /health 自报事故）；活跃路径的
  `owned_by` 是 `codearts-devcloud`，与实机一致。

### A3. 类注释悬空引用（极低）
- `GatewayServer.java:35` Javadoc 引用 `{@link OrbitClient}`，该类在本源码树中
  不存在（移植期移除）。无编译影响；开启 doclint 时会报错。

### A4. 工具调用服从度依赖模型（中，设计限制）
- ToolShim 是"协议注入 + 哨兵解析"的垫片方案，成功率取决于码道 agent 模型对
  协议的服从度；模型可能无视协议继续"演"自己的 tool_search/bash。1.9.7 的
  few-shot 示例段已显著缓解，但非 100%。
- 排障命门：日志/落盘证据中的 **query 字段**——任何"协议没生效"先查它是否
  含【工具调用协议】/【MCP 工具】/【示例】三段。

### A5. 工具协议导致 prompt 膨胀（中）
- 工具 schema 每轮随 query 重发；MCP 侧 49 个 MT 工具全量注入时协议段显著
  变长（Token 消耗 + 上游处理变慢）。无裁剪/按需注入机制。

### A6. 工具轮数硬编码、超限提示非标（低）
- 内部路径与外部工具路径的上限 `maxRounds = 6` 分别硬编码于
  `GatewayServer.java:462/937`，不可配置。
- 达到上限时以**正文文本**提示"已达最大工具调用轮数 6，停止"，而非标准
  finish_reason 语义；严格解析 finish_reason 的客户端无法区分。

### A7. MCP 变更类工具拦截为名字正则（中）
- `McpHub.MUTATING` 正则按**工具名**判定写/删/改/构建（`McpHub.java:69`）：
  可能误伤名字含关键词的只读工具，也可能漏放名字不含关键词的变更工具；
  `allow_mutating` 打开后放行全部变更工具（无白名单粒度）。默认关闭（安全）。

### A8. 上游非流式 tool_calls 缺失（外部依赖，已规避）
- 部分原生 function calling 上游的非流式响应缺 `message.tool_calls`；网关已在
  1.9.0 用"统一走流式并自行聚合"规避。若某上游连流式
  `delta.tool_calls` 都不合规，工具路径 A 对其不可用（属上游能力问题）。

### A9. JVM 测试与 Android org.json 行为差异（流程风险）
- Android 的 org.json put/构造是受检异常，JVM 测试用的 json-java 不是 →
  JVM 全绿 ≠ 真机无编译问题（1.9.0 实录踩坑）。改动 core 层后必须真机
  assembleDebug 验证。

### A10. 会话 Cookie 的固有限制（中）
- **WAF/安全 Cookie**：该站点可能要求额外安全 Cookie；刚保存仍失败时无自动
  过关能力（`DevCloudClient.java:401` 已有对应提示文案），需在官网完成安全
  校验后重登。
- **Cookie 明文存储**：DevCloud 会话 Cookie 明文保存在应用私有 SharedPreferences
  （/data/data/…，无 root/备份不可读，但 adb backup/Root 场景可被提取）。
- Cookie 失效特征：HTTP 200 + 空 body + `HW-AJAX-REDIRECT` 登录地址，或模型
  列表返回 0 个——均需手动重登（App 已有对应提示）。

### A11. 诊断落盘文件为明文（低）
- 「查询额度」→ `/sdcard/Download/codearts-package.json`、签到诊断 →
  `codearts-welfare.json`：包含上游原始报文（模型/额度/福利条目；不含 Cookie，
  但属个人用量信息），落在外部存储 Download 目录，注意分享截图时剔除。

### A12. androidasync 3.1.0 库缺陷只能绕不能修（架构限制）
- Chunked 自动路径静默丢数据 → 已用"直写连接 socket"绕过；JSON 响应逐请求
  `Connection: close`（网关对客户端无 keep-alive，串行短连接，性能可接受）。
  该库社区已停更，后续应考虑迁移（如 Ktor/NanoHTTPD）。

### A13. 保活与悬浮窗强依赖 ROM 策略（环境限制）
- vivo/OriginOS 等需要：悬浮窗权限 + 电池白名单 + 后台锁定，缺一可能"开关开了
  但悬浮窗不出现/网关被杀"。App 已做前台先行 + 延迟重试 + try-catch 兜底，
  但无法对抗极端 ROM 查杀。

### A14. 码道提问的回答协议未实现（中，功能未闭环）
- 网关已能把码道 `ask_user` 类事件转成标准 `tool_calls`（按客户端 tools schema
  补齐 `questions[].id` 等字段），agent 侧能弹出选项并作答；但**码道侧的提问卡片
  需要一条专门的应答请求**（携带 `question_id` / `call_id` / `turn_id` + 用户答案）
  才会被置为"已回答"。
- 当前实现只会把「上一轮提问 + 用户回复」当**普通聊天消息**回灌
  （`GatewayServer.buildPendingContext`，内部路径与普通路径同款），码道会把这条
  消息当新输入，原提问卡片仍停留在等待状态。
- 缺少协议证据：尚未抓取网页端"回答提问"的请求 URL / body / 特殊请求头。
- 待办：拿到抓包后，在 `DevCloudClient` 增加 `answerQuestion(cookie, sessionId,
  questionId, callId, turnId, answers)`，body 形如
  `{question_id, call_id, turn_id, answers:[...]}`；`handleChat` 检测到 pending
  提问时先调用它，再发送后续消息。

### A15. 码道产物未透传（低，暂缓）
- `DevCloudClient` 会把 `deliverable` / `artifact` / `file` / `file_diff` /
  `task_result` 等事件交给 `Sink.onEvent` 并落日志；`GatewayServer` 只做计数。
- 曾尝试把产物摘要以 `[码道产物]` 文本透传给 agent，但实测 `file_diff` 事件高频、
  字段常为空（如 `{"diff":[],"ts":...}`），透传只会产生噪声，**已回滚**。
- 待办：待确认产物事件里可靠的字段（真实文件 URL / 内容）后再实现；若只有工作区
  路径或 file_id，需网关带 Cookie 下载后再转发。

## B. 历史已修复（防回归存档，当前代码已验证落实）

| 版本 | 问题 | 修复验证点 |
|---|---|---|
| 1.9.9 | 协议不注入（1.9.8 回归）/ 思考丢失（1.9.5 回归）/ 心跳不转发致客户端超时 | 内部路径 mcpOn 即注入；reasoning_content 流式恢复；`heartbeat()` 全链路 + role 帧先行 |
| 1.9.5→1.9.4 | self_loop 自指 | 端口相同即 400（`ToolSelfTest` 先探 TCP） |
| 1.9.1 | 福利模型缺 `maas-type: benefit` 头 → 上游"未找到模型" | `DevCloudClient.java:782` |
| 1.8.0 | 非流式 501；onThinking 传参错位 | 流式聚合已实现（源码无 501 残留） |
| 1.7.2 | 签到永远"今日已签到"（type 归一化误杀 USER_LOGIN） | delivery schema 对齐 + did 位成功后置位 |
| 1.6.9 | 模型标签显示 "admin" | 上游 desc 弃用 + ModelInfo 净化 |
| 1.6.4 | 悬浮球 addView NPE（WindowManager 初始化顺序） | `FloatingBallService.java:46` |
| ≤1.6.x | okhttp "unexpected end of stream"（keep-alive EOF） | JSON 显式 Connection: close；SSE close 前 end() + 80ms |
| ≤1.6.x | androidasync Chunked 静默丢数据 | SSE 直写 socket |
| ≤1.6.x | Android 14 FGS 类型子集校验崩溃 | Manifest 双服务声明 specialUse（`AndroidManifest.xml:63/72`）+ try-catch |
| ≤1.6.x | 后台 addView 被 ROM 拒绝（vivo） | onCreate 先 startForeground 再 addView + 600ms 重试 |
| ≤1.6.x | MainActivity 无条件 startService → BadTokenException 2038 | syncFloatBall 先判开关+权限 |
| ≤1.6.x | 通知渠道 importance 不可变 | 切换时 deleteNotificationChannel 重建（`GatewayService.java:182`） |

## 维护建议（优先级序）

1. A1 版本常量联动（一行改动，收益立现）；
2. A6 maxRounds 提为 Store 配置 + 超限改 finish_reason="length"（或自定义语义）；
3. A7 MUTATING 从"名字正则"升级为"工具注解/声明级别"判定（需 MCP server 侧
   annotations 支持，Streamable HTTP 规范已有 tools.annotations 字段）；
4. A5 协议注入按 tool_choice/历史压缩裁剪；
5. A2/A3 遗留清理（照 LEGACY-IDENTIFIERS.md 清单执行即可）。
