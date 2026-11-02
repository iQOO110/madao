# 更新记录（CHANGELOG）

> 考据来源：开发期记忆存档、源码内版本注释（`GatewayServer.BUILD_TAG`、各文件
> `v1.x.x` 行内标注）、`app/build.gradle` versionCode 链、早期 PROGRESS.md。
> 个别早期版本（1.6.x、1.9.2/1.9.3）细节无独立存档，条目系据 versionCode 链
> 与同期修复推定，已逐条标注置信度。

## 版本线概览

| 阶段 | 版本 | 说明 |
|---|---|---|
| InkStone 网关 | ≤1.6.1 | 前身项目（Intern InkStone orbit 平台） |
| 移植启动 | 1.7.0 | 更名 CodeArts 网关，对接华为 CodeArts 码道 |
| 功能成熟 | 1.8.0–1.9.9 | function calling / MCP / 工具路径 / 心跳 |

## [1.9.10] — 传参优化 + 思考过程透出（用户定制版）

基于 1.9.9 的本地修改（ARM64 Ubuntu 构建支持 + 两个功能修复）：

### 传参优化（agent 注入内容不再转发给码道）
- **问题**：DSH 等 agent 客户端每轮把 system prompt 全文 + 运行时上下文快照
  （"Current runtime context..."，含工作目录/时区/Time sampled 等环境信息）
  原样带进 OpenAI 请求。码道是多轮续聊会话，这些内容既非用户原话、又会随
  每轮请求反复注入，导致上下文越滚越大、指令被稀释。
- **修复**：`openaiToQueryText()` 默认直接丢弃：
  - 整条 system 消息；
  - 以 "Current runtime context" 开头的 user 消息（或含 Time sampled + 工作目录/
    浏览器时区特征的环境块）；
  只保留用户原话与 assistant 历史（工具调用/结果仍渲染成协议文本，模型需要
  看见自己之前的调用与返回）。
- **开关**：设置页「隐藏 agent 注入的上下文」（`Store.isTrimSystem`，默认开），
  关闭后恢复 1.9.9 行为（原样转发）。

### 隐藏 agent 工具清单（tools 与协议段默认丢弃）
- **问题**：DSH 每次请求携带 20+ 个工具的完整 OpenAI function schema（几十 KB），
  网关为把 function calling 转成文本协议，每轮把「工具调用协议」段全量注入码道
  query，多轮会话里反复累积。但 agent 侧自己执行工具，码道侧根本不需要这些。
- **修复**：新增开关「隐藏 agent 工具清单」（`Store.isDropToolProtocol`，默认开）：
  开启时客户端携带的 `tools` 不参与路径分流、也不注入 ToolShim 协议段，
  只把用户原话发给码道；关闭则恢复把 tools 转给码道（走 ToolShim 协议）。
  注意：MCP 自动执行不受影响，仍按独立开关工作。

### 只发新消息（不带历史）+ 关闭 MCP 默认 + 未知事件日志
- **只发新消息**（`Store.isOnlyLastUser`，默认开）：agent 侧每轮携带完整历史，
  码道是多轮会话导致上下文反复累积。开启后 `openaiToQueryText` 只提取最后一条
  用户原话（跳过快照碎片），码道侧每次独立上下文；关闭恢复转发完整历史。
- **MCP 默认关**（`Store.isMcpEnabled` 默认改为 false）：agent 场景下 DSH 自己执行
  工具，网关不再自动把第三方 MCP 工具喂给码道；需要时在设置里开启。
- **未知 SSE 事件落日志**：码道的 `artifact` / 文件类事件如果存在，现在会进
  LogRing（`SSE 未知事件 ...`），为下一步「任务产物自动拉回」提供排查依据。

### 思考过程透出（reasoning_content）
- **问题**：码道 SSE 的 "thought" 事件原本只在流式路径部分透出；非流式路径
  `onThinking` 为空；内部工具循环路径（`handleInternalToolPath`）思考只累计不下发。
- **修复**：
  - `handleChatSync`：累积 `thinking`，响应 message 里加 `reasoning_content` 字段
    （`ToolShim.completion` 新增 5 参重载，不需要的客户端直接忽略）。
  - `handleInternalToolPath`：跨轮累计 `thinkingAll`，流式在 content 前先补发
    `reasoning_content` 分片，非流式走 `toolCompletion` 新参。
  - 设置页新增「透传码道思考过程」（`Store.isStreamThinking`，默认开），
    关闭后所有路径不再下发 thinking。

### 构建（ARM64 Ubuntu / DSH 手机容器）
- 新增 `ARM64_BUILD.md` + `build-arm64-ubuntu.sh`：Google 官方 aapt2 只有 x86_64
  原生二进制，ARM64 上通过 qemu-user + x86_64 的 libc6/libgcc-s1 运行；
  脚本自动把 build-tools 里的 aapt2 替换为 QEMU wrapper（原始二进制备份为 aapt2.orig）。
- `gradle.properties` 保持与官方发布一致（aapt2FromMavenOverride 不可用：
  Maven 的 aapt2 JAR 内嵌的同样是 x86_64 原生二进制）。
- `app/build`、`.gradle`、`local.properties` 已加 `.gitignore`，不入仓库。

## [1.9.9] — versionCode 39

`BUILD_TAG = "v1.9.9+heartbeat"`

### 修复
- **协议不注入（v1.9.8 引入的回归）**：`handleInternalToolPath` 原先仅在请求显式
  携带 `tools` 时才拼装【工具调用协议】段；第三方 App 在内部工具路径下通常不带
  tools（等网关回 tool_calls），导致协议段、【MCP 工具】段、【示例】段全部缺失，
  码道"演"起自己的 tool_search/bash。现改为：只要 `mcpOn` 且有可用 MCP 工具即注入
  协议，与请求是否带 tools 无关。
- **思考过程丢失（v1.9.5 引入的回归）**：v1.9.5 重写内部路径时 thinking 只攒进
  StringBuilder 从不下发；恢复流式 `reasoning_content` 分片（对齐 v1.8 老路径行为）。
- **第三方 App 误判连接超时**：码道 SSE 心跳此前未转发给客户端；多轮工具循环期间
  （每轮可达 60–100s、最多 6 轮）客户端收不到任何字节即断言超时。现全链路转发：
  `DevCloudClient.Sink` 新增 `heartbeat()`，内部路径在循环开始前就发 role 帧
  （SSE 从第一秒"活着"），每次上游心跳 → `sse.comment("keep-alive")`；
  `handleChatStream`（老路径）同样转发。

## [1.9.8] — versionCode 38

- 内部工具路径协议注入条件收紧（以"请求是否带 tools"为前提）——该方案随后在
  1.9.9 被推翻修正（见上）。本版主要为 1.9.9 修复铺垫的中间版本。

## [1.9.7] — versionCode 37

- **few-shot 示例注入**：码道是 agent 模型，不看示例就会"演"自己的工具
  （tool_search/bash），在协议中追加【示例】段演示标准调用块写法。
- **会话复用降级**：无显式会话标识时按「模型 + 首条用户消息」派生会话键，避免
  多轮对话被无谓拆散。
- **会话失败废弃重建**：记录 `sessionFailureAt`，下次 `ensureSession` 时废弃重建
  （源码 `GatewayServer.java:45` 注释佐证）。

## [1.9.6] — versionCode 36

- 维护版本：小修与稳定性（无独立功能存档，置信度低）。

## [1.9.5] — versionCode 35（内部工具路径）

- **零外部依赖工具调用**：澄清 "raccoon2api:3065" 真相——3065 实为网关自身换用
  端口，外部工具上游从不存在。`tool_base` 为空或 self_loop（地址端口 == 网关
  监听端口）时自动进入 `handleInternalToolPath`：码道 + ToolShim 垫片当模型，
  MCP 工具由网关执行（McpHub）→ [工具结果] 回灌 → 循环（最多 6 轮）；命中客户端
  自带工具则标准 tool_calls 回传。
- 修复：异步回调里 throw 无法跨线程传递错误 → 改标志位；self_loop 判定改用网关
  当前监听 port 字段（用户换端口也能正确识别）。
- 配置语义：第三方 App 的 base_url 直接填网关自身地址。

## [1.9.4] — versionCode 34（身份修正 + 防自杀防护）

- **自报身份修正**：AppConfig 此前仍是 InkStone 移植遗留（APP_NAME="InkStone
  网关"、VERSION="1.6.1"），导致 /health 自报成另一个项目；改为
  「CodeArts 网关 / 1.9.4」。
- **self_loop 防护**：工具路径地址端口与网关自身相同时（如误填
  `http://127.0.0.1:8055/v1` 而网关就在 8055）直接 400 拒绝，提示改为真正的
  模型服务——防"自己指自己无限转圈"。
- **自检分级**：先探 TCP 端口再调 /models，把「服务没跑（连接拒绝 → 去拉起目标
  App 并锁后台）」与「端口通但路径/Key 错」区分开，报错原文直接可行动。

## [1.9.1] — versionCode 31–33

- **限时福利模型识别**：官网硬编码的限时福利组（glm-5.3-flash / deepseek-v4-*）
  也必须算福利模型，发消息需带 `maas-type: benefit` 头，否则上游报
  "未找到模型：inferhub-provider/xxx"（`DevCloudClient.java:781` 注释佐证）。

## [1.9.0] — versionCode 30（工具路径 A/B）

- **工具路径 A（工具上游转发）**：带 tools 的请求改走原生支持 function calling
  的 OpenAI 兼容上游（ToolUpstream）；客户端 tools 原样透传、由客户端执行。因
  部分上游非流式响应缺 `message.tool_calls`，网关内部统一走流式并自行聚合。
- **工具路径 B（MCP 客户端）**：新增 `core/McpHub.java`，Streamable HTTP JSON-RPC，
  支持 `Mcp-Session-Id` 与 JSON/SSE 双响应体；MCP 只读工具自动喂给模型，模型产出
  的调用由网关执行、结果回灌、循环（默认最多 6 轮）。
- 配置：`tool_base/tool_key/tool_model`、`mcp_servers`
  （JSON 数组，默认 MT `http://127.0.0.1:8780/mcp`）、`mcp_enabled`、
  `allow_mutating`（默认 false，写/删/改/构建类工具名一律拦截）。
  `tool_model` 默认 `sn-glm-5-3`。
- 新增独立实现 `/root/mcpgw.py`（Python，端口 8077，不入本仓库）。
- 踩坑沉淀：Android org.json 的 put/构造是受检异常，JVM 测试用的 json-java 不是 →
  只在真机构建暴露；新增代码所有 JSON put 必须 try/catch。

## [1.8.x] — versionCode 28–29

### [1.8.1]（置信度中）
- **诊断探针 400 修复**：`OrbitClient.request()` 写 POST body 时未设
  Content-Type，所有 POST 被上游按非 JSON 解析返回 400；"探针报错但功能正常"
  时应先对比探针与真实请求差异。
- （注：OrbitClient 相关为移植期代码；本条同时合入 InkStone 前身项目）

### [1.8.0] — versionCode 28（function calling 垫片）

- **ToolShim 垫片**：码道 messages 接口只收 {content, model_id, repos}，客户端
  tools 无处可传 → 网关自造 function calling：请求方向把 tools schema 逐条拼成
  【工具调用协议】文本附在拍平 query 末尾（仅当请求带非空 tools 且
  tool_choice != none，普通对话零影响）；历史里 assistant.tool_calls 渲染为同款
  调用块、role=tool 渲染为 "[工具结果] name (call_id): content"。
- 哨兵协议：`START="<|tool_call|>"` / `END="<|/tool_call|>"`，流式状态机仅当缓冲
  末尾恰好是 START 前缀时才暂缓下发（哨兵被切碎不泄漏、不增延迟）；块体按花括号
  配平抽 JSON（兼容 function 嵌套与 name/tool/tool_name、arguments/args/parameters/
  input 别名；arguments 非 JSON 时按 key=value 兜底；解析失败原样吐出绝不吞内容）。
- 每命中一次发两片 delta.tool_calls（先 id/type/name + 空 arguments，再 arguments），
  最后 finish_reason="tool_calls"。
- **非流式补齐**：此前直接 501，改为网关侧聚合上游 SSE 回 chat.completion（带
  message.tool_calls）。
- thinking 流过同一套哨兵解析；顺手修旧 bug：onThinking 原先把 reasoningDeltaJson
  当 finish_reason 传、delta=null。
- 验证：JVM 16 套件全绿（ToolShimTest 40+ 断言 + ToolStreamReplayTest SSE 回放）。

## [1.7.x] — versionCode 24–27

### [1.7.3] — versionCode 27（额度双显）

- 「查询额度」同时显示两套体系：**积分**（剩余 X / Y，已用 n%，取
  snap-manager statistics 的 usageTotalPackageCredit）与**免费额度**
  （benefit/tokens/balance 的 used_amount/total_quota）；点查询落盘
  `/sdcard/Download/codearts-package.json`。
- 体检页新增「上游 积分/套餐用量（snap-manager）」探针。

### [1.7.2] — versionCode 26（签到 schema 重对齐）
（注：存档原文将此版本号写作 1.1.7.2，疑为笔误，此处按 versionCode 26 对齐）

- 上游 `/ops/delivery` 真实 schema 重对齐（实机抓包）：`data.items[]` 字段 =
  campaignId/type/benefitAmount/benefitUnit/displayConfig/pageUrl/claimable/
  status/pendingTotalAmount/access[] 等；领取三步 delivery → claim
  {campaignId, idempotentKey "wb_<id>_<ts>", channel:"WEB"} → confirm。
- 根因修复：v1.7.0 之前把 login/register/student_certify 都当"其他流程"跳过，
  连每日签到（type=USER_LOGIN）也跳过 → 永远"今日已签到"。正确规则：只跳 invite；
  ELIGIBLE/ENTRY 直接 claim；无 status 时看 jumpUrl 与上游 claimable 标记；
  优先 pendingTotalAmount，否则 benefitAmount。
- UX 修正：did（已签到标记）只在 claim 成功（200 + code OK）后置位；失败显示
  上游原文，杜绝"失败了还说签到成功"。

### [1.7.0] — versionCode 24（移植启动）

- **项目更名 CodeArts 网关**（package `com.codearts.gateway`），对接华为
  CodeArts 码道（devcloud.cn-north-4.huaweicloud.com/chat，bundle 126.8.942）。
- 逆向并实现：会话 Cookie 鉴权（`devclouddevuibjtcftk`）、建会话、发消息 SSE
  （thought/message/done/error/status/heartbeat 事件）、模型列表（agents/detail
  主源 + builtin 兜底 + benefit/gateway/config 合并）、签到/福利。
- 新增 `core/Platform.java`（平台枚举 + route 纯函数）、`core/DevCloudClient.java`、
  `core/OnceSinkResult.java`；Store 账号加 platform 维度；GatewayServer 按
  Platform.route 分流。
- UI：PlatformActivity（平台切换、DevCloud WebView 登录抓 cookie、签到按钮、
  福利列表）。

## [1.6.x] — InkStone 网关时期（versionCode 20–23）

### [1.6.9] — versionCode 23（模型标签官网化）

- 模型列表标签改为「官网文案硬编码」（`DevCloudClient.OFFICIAL_DESCRIPTIONS`）：
  GLM-5.2=深度推理模型、GLM-5.2-ArkTS-SPARK=鸿蒙代码增训、OpenPangu-2.0-Pro=最新
  旗舰（面向长程任务与复杂工程）、OpenPangu-2.0-Flash=均衡推理与性能；OFFICIAL_BENEFIT_MODELS
  存官网「限时福利」组（deepseek-v4-*、glm-5.3-flash）。
- 根因：agents/detail 的 models[].model_desc 实测返回创建者元数据 "admin"，
  渲染即卡片上的 admin 标签 → 彻底不采用上游 desc；ModelInfo 构造器做标签净化
  （丢弃 admin/纯数字/布尔/空）兜底全部来源。

### [1.6.4] — versionCode（存档缺，据源码注释）

- FloatingBallService 重写时误删 WindowManager 最先初始化，导致 addView NPE
  （日志实测）；已恢复"WindowManager 必须最先初始化"顺序（源码 46 行注释佐证）。

### [1.6.1] — versionCode 20（历史基线）

- 前身「InkStone 网关」：Intern InkStone orbit 平台适配、OpenAI 兼容网关雏形、
  悬浮球、前台服务。AppConfig.VERSION 曾长期停在此值（移植后未联动，见 1.9.4）。

## 更早（≤1.5.x，InkStone 时期）

- **androidasync keep-alive EOF**：okhttp 系客户端报 "unexpected end of stream" →
  JSON 响应显式 `Connection: close` 逐请求关闭；SSE 裸写 socket 路径 close 前先
  `sock.end()` 排空并 postDelayed 80ms 再 close（防尾部字节截断）。
- **androidasync Chunked 路径静默丢数据**：`response.write()` 在自动 Chunked
  路径上 mSink 未就绪时静默丢字节 → SSE 改直写连接 socket（手写状态行 + 头 +
  data: 帧）。
- **FGS 类型强校验（Android 14）**：startForeground 的类型必须是 Manifest 声明
  类型的子集，否则 IllegalArgumentException；GatewayService/FloatingBallService
  均声明 specialUse 并 try-catch 兜底。
- **悬浮窗前台先行（vivo/OriginOS）**：后台服务直接 addView 被 ROM 静默拒绝 →
  onCreate 先 startForeground（IMPORTANCE_MIN）再 addView，失败延迟重试一次；
  MainActivity.syncFloatBall 先判断开关+权限再 startService（防 BadTokenException）。
- **通知渠道 importance 不可变**：切换"常驻通知可见/静默"必须 deleteNotificationChannel
  后重建，否则永远停在首次创建的优先级（`GatewayService.java:182`）。

## 附注

- 本文件由 AI 协助整理自开发记忆与源码注释；1.9.2/1.9.3、1.9.6 及 1.6.x 部分小版本
  无独立存档，条目置信度已标注，欢迎使用者指正补充。
- 历史版本号 `1.1.7.2`（versionCode 26）为存档原文记录，疑为 `1.7.2` 的笔误，
  按「保持源码原样」原则未做订正，仅在此说明（条目标题已按 1.7.2 呈现）。
