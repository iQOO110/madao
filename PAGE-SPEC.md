# CodeArts 网关 · Page Spec

> 依据 `DESIGN.md`（Apple 设计语言，取自 [awesome-design-md](https://github.com/VoltAgent/awesome-design-md) 的 `apple/DESIGN.md`）落地到 Android。
> 本文定义每个页面的 **路由 / 布局插槽 / 区块 / 操作清单 / 状态 / 交互规则**，作为 UI 改版的唯一依据。

---

## 0. 全局约定

### 0.1 设计令牌（`res/values/colors.xml`、`themes.xml`、`styles.xml`）

| Token | 值 | 用途 |
|---|---|---|
| `brand_primary` | `#0066cc` Action Blue | 唯一强调色：主按钮、链接、选中态 |
| `apple_ink` | `#1d1d1f` | 标题与正文 |
| `apple_parchment` | `#f5f5f7` | 页面画布 |
| `apple_canvas` | `#ffffff` | 卡片表面 |
| `apple_pearl` | `#fafafc` | 次按钮底色 |
| `apple_hairline` | `#e0e0e0` | 卡片 1px 边框 |
| `apple_divider` | `#f0f0f0` | 分隔线 |
| `apple_muted` | `#7a7a7a` | 说明文字 |
| 圆角 | 18dp 卡片 / 999dp 胶囊 / 8dp 工具块 | 不混用 |
| 字体 | `sans-serif`（SF 替代） | 标题 600，正文 400，正文 17sp |

**规则**：单强调色；卡片只用细边框、不加阴影；按钮胶囊化；标题负字距（`letterSpacing=-0.02`）。

### 0.2 插槽映射（Web → Android）

| Web 插槽 | Android 实现 |
|---|---|
| `header` | `MaterialToolbar` + `Apple.ToolbarTitle`（返回 + 标题 + 右侧操作） |
| `sidebar` | 无固定侧栏；改为 **功能分组卡片**，登录页为右下角 **FAB 菜单** |
| `main` | `NestedScrollView` 内容区，分组卡片纵向排列 |
| `right panel` | `AlertDialog` / 弹层（粘贴 Cookie、登录方式、确认操作） |
| `footer` | 页面底部脚注（`Apple.Footnote`），不悬浮 |

### 0.3 通用状态

| 状态 | 表现 |
|---|---|
| `loading` | 按钮禁用 + 文案「处理中…」；不阻塞整页 |
| `empty` | 区块内 `Apple.Footnote` 空态说明 |
| `error` | `Toast` 短提示 + 状态行变红点（`err_red`） |
| `success` | `Toast` 短提示 + 状态行绿点（`ok_green`） |

### 0.4 通用交互规则

- 主按钮点击 `scale(0.95)` 微交互（MaterialButton `rippleColor` + 主题）。
- 顶部 `header` 的左返回始终可用；系统返回手势等价。
- 可复制文本（地址、密钥、Cookie）长按可选中，点击「复制」写入剪贴板并 Toast。
- 破坏性操作（卸载/清空）需二次确认（`AlertDialog`）。

---

## 1. 主页 `/`

- **路由**：`com.codearts.gateway.ui.MainActivity` → `activity_main.xml`
- **插槽**：`header`（品牌标题 + 运行状态副标题） / `main`（状态、接入、操作、结果） / `footer`

### 区块

| # | 区块 | 内容 | 数据来源 |
|---|---|---|---|
| 1 | 状态卡 | 状态点、`tv_state`、端口芯片 `chip_port`、详情 `tv_detail` | `GatewayService.isRunning()` |
| 2 | 接入卡 | `tv_base`（Base URL）、`btn_copy`、`tv_endpoints` | 端口 + 局域网开关 |
| 3 | 主操作 | `btn_toggle` 启动/停止网关 | 服务状态 |
| 4 | 功能分组 | `btn_login`、`btn_models`、`btn_checkin`、`btn_balance`、`btn_diag`、`btn_settings`、`btn_keys`、`btn_battery` | — |
| 5 | 结果区 | `tv_balance`（余额）、`tv_welfare`（福利/签到结果） | 上游查询 |
| 6 | 脚注 | `main_footer` | 静态 |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 启动/停止网关 | `btn_toggle` | 服务状态切换，按钮文案与状态点同步 |
| 复制接入地址 | `btn_copy` | 剪贴板 + Toast |
| 修改端口 | `chip_port` | 跳转设置页 |
| 登录码道 | `btn_login` | 跳转 `/login` |
| 模型列表 | `btn_models` | 跳转 `/models` |
| 签到 / 查额度 | `btn_checkin` / `btn_balance` | 结果写入 `tv_welfare` / `tv_balance` |
| 诊断 / 设置 / 密钥 / 电池 | `btn_diag` / `btn_settings` / `btn_keys` / `btn_battery` | 跳转对应页 |

### 状态

- `stopped`：灰点、`tv_state=未启动`、`btn_toggle=启动网关`
- `running`：绿点、`tv_state=运行中`、`btn_toggle=停止网关`
- `no_cookie`：`tv_detail` 提示「未登录，先完成登录」
- `balance.loading/error`：结果区先「查询中…」再落结果或错误

### 交互规则

- 网关运行中修改端口/局域网 → 提示需重启，跳设置页保存后自动重启。
- `btn_copy` 复制内容为当前 Base URL（含端口）。
- 功能分组内按钮均为 `Apple.Button.Tonal` 胶囊，两列网格。

---

## 2. 设置 `/settings`

- **路由**：`SettingsActivity` → `activity_settings.xml`
- **插槽**：`header`（返回 + 「设置」） / `main`（分组卡片） / 无 panel

### 区块

| # | 分组 | 内容 |
|---|---|---|
| 1 | 账号 | `tv_login_state`、`btn_login_cookie`（弹出登录方式 panel）、`settings_login_cookie_help` |
| 2 | 网络 | `et_port`、`cb_lan` |
| 3 | 常驻与保活 | `cb_autostart`、`cb_float`、`cb_wakelock`、`cb_notify`、`btn_notify_settings`、说明 |
| 4 | 对话适配 | `cb_trim_system`、`cb_stream_thinking`、`cb_only_last_user` + 说明 |
| 5 | 底部操作 | `btn_save` 主按钮 |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 登录 / 粘贴 Cookie | `btn_login_cookie` | `right panel`：打开登录页 / 粘贴 Cookie |
| 保存 | `btn_save` | 校验端口 → 持久化 → 必要时重启网关 |
| 悬浮球开关 | `cb_float` | 无权限时自动回关并请求权限 |
| 通知设置 | `btn_notify_settings` | 跳系统通知设置 |

### 状态

- 端口非法（非 1024–65535）→ Toast 阻止保存
- 浮窗无权限 → 强制关闭开关 + 请求授权
- 保存后端口/局域网/通知变化 → 网关自动重启

### 交互规则

- 所有开关即时写入内存，点「保存」才持久化。
- `header` 返回不保存，直接退出。

---

## 3. 登录 `/login`

- **路由**：`LoginActivity` → `activity_login.xml`
- **插槽**：`header`（**无固定 header**，网页全屏） / `main`（WebView 全屏） / `panel`（右下角 FAB 菜单）

### 区块

| # | 区块 | 内容 |
|---|---|---|
| 1 | 网页 | `webview` 全屏加载 `LOGIN_URL` |
| 2 | 操作面板 | `fab_menu` → `action_menu`：`btn_save`、`btn_copy`、`btn_paste`、`btn_reload`、`btn_back` |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 展开/收起操作 | `fab_menu` | 切换 `action_menu` 可见性 |
| 保存会话 | `btn_save` | 读取 WebView Cookie → 校验 → 存 Store → 返回 |
| 复制 Cookie | `btn_copy` | 写入剪贴板 |
| 粘贴 Cookie | `btn_paste` | 弹 `dialog_paste_cookie`：读取剪贴板 / 历史 Cookie / 手动输入 |
| 重新加载 | `btn_reload` | 重新加载登录页 |
| 返回 | `btn_back` | 关闭页面 |

### 状态

- `no_session`：粘贴保存时提示「缺少 cftk/session」
- `paste_success`：Toast「已保存，网页已同步刷新」并重载网页
- `clipboard_denied`：Toast 提示长按手动粘贴

### 交互规则

- 粘贴保存后**写回 CookieManager + 刷新网页**（不只存 App）。
- 历史 Cookie 取最近 8 条、去重。
- 网页全屏，操作面板不遮挡内容（收起时不可见）。

---

## 4. 模型 `/models`

- **路由**：`ModelsActivity` → `activity_models.xml`（列表 `item_model.xml`）
- **插槽**：`header`（返回 + 「模型」） / `main`（列表）

### 区块

| # | 区块 | 内容 |
|---|---|---|
| 1 | 说明 | 顶部脚注：模型来源与福利标记 |
| 2 | 模型列表 | `RecyclerView` / 列表项：名称、`model_id`、福利标签、选中态 |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 选择模型 | 列表项 | 写 Store，返回主页 |
| 刷新 | header 刷新 | 重新拉取 `/model/builtin` |

### 状态

- `loading`：列表骨架/「加载中…」
- `empty`：空态说明
- `error`：脚注显示错误 + 重试按钮

### 交互规则

- 当前选中项左侧蓝色勾选（`brand_primary`）。
- 福利模型展示 `maas-type: benefit` 标签。

---

## 5. 网关密钥 `/apikeys`

- **路由**：`ApiKeysActivity` → `activity_apikeys.xml`
- **插槽**：`header`（返回 + 「网关 API Key」） / `main`（说明 + 输入 + 列表）

### 区块

| # | 区块 | 内容 |
|---|---|---|
| 1 | 说明 | 默认密钥 `123456`；`Authorization: Bearer <key>` / `x-api-key` |
| 2 | 添加 | `et_new` + `btn_add`（留空自动生成） |
| 3 | 列表 | `box_keys` 动态行：密钥 + 删除 |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 添加 | `btn_add` | 去重后写入，刷新列表 |
| 删除 | 行内删除 | 二次确认后移除 |
| 复制 | 点击密钥 | 剪贴板 |

### 状态

- 列表为空 = 不校验（任意 Key 可用）
- 首次安装默认 `123456`

### 交互规则

- 密钥以等宽字体展示，避免误读。
- 删除最后一个密钥时提示「将不再校验」。

---

## 6. 诊断 `/diag`

- **路由**：`DiagActivity` → `activity_diag.xml`
- **插槽**：`header`（返回 + 「诊断」） / `main`（操作 + 日志面板）

### 区块

| # | 区块 | 内容 |
|---|---|---|
| 1 | 操作 | 复制日志、清空、导出、刷新 |
| 2 | 日志面板 | 等宽滚动日志（请求、SSE 事件、错误） |
| 3 | 环境信息 | 构建标记、端口、会话数 |

### 操作清单

| 操作 | 控件 | 结果 |
|---|---|---|
| 复制/导出日志 | 顶部按钮 | 剪贴板 / `Download` 文件 |
| 清空 | 顶部按钮 | 清空内存日志 |
| 刷新 | 顶部按钮 | 重读日志 |

### 状态

- 日志为空 → 空态说明
- 导出失败 → Toast + 保留日志

### 交互规则

- 日志自动滚动到底部，用户手动上滑后暂停自动滚动。
- 敏感字段（Cookie/Key）不落日志。

---

## 7. 组件复用清单

| 组件 | 样式 | 用于 |
|---|---|---|
| 页面标题 | `Apple.ToolbarTitle` | 所有 header |
| 分组标题 | `Apple.SectionHeader` | 设置页、主页功能分组 |
| 卡片 | `AppTheme.Card`（18dp / 1px hairline） | 所有区块容器 |
| 主按钮 | `Apple.Button`（蓝底胶囊） | 启动网关、保存、添加 |
| 次按钮 | `Apple.Button.Tonal`（浅灰底蓝字） | 功能入口、次级操作 |
| 描边按钮 | `Apple.Button.Outlined` | 取消/辅助 |
| 说明文字 | `Apple.Footnote` | 所有帮助文本 |
| 弹层 | `AlertDialog` + 自定义布局 | 粘贴 Cookie、登录方式 |

---

## 8. 状态与交互总表

| 页面 | 主要状态 | 关键交互 |
|---|---|---|
| 主页 | stopped / running / no_cookie / balance.loading | 启停、复制地址、跳转 |
| 设置 | dirty / saving / restart_required | 开关、保存、重启 |
| 登录 | no_session / pasting / synced | 全屏网页、FAB、粘贴同步 |
| 模型 | loading / empty / error / selected | 选择、刷新 |
| 密钥 | empty(no-auth) / default(123456) / custom | 增删、复制 |
| 诊断 | empty / streaming / exported | 复制、清空、导出 |