# Finance 7x24 News 架构说明

面向开发者的实现文档，覆盖入口链路、各模块设计、线程模型、持久化键与已知限制。功能说明见 [README.md](README.md)，版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 总览

单工具窗口 IntelliJ 插件（纯 Java + Swing，唯一第三方依赖 Gson），在 IDE 右侧挂载「Finance 7x24 News」窗口，内含快讯、自选两个标签页。三大模块：

| 模块 | 主要类 | 职责 |
|------|--------|------|
| 快讯 | KuaixunService / KuaixunPanel | 拉取东方财富 7x24 快讯，卡片流展示 + 自动刷新 |
| 行情 | QuoteService / WatchlistPanel / StockSearchDialog | 自选行情多级兜底拉取、日K均线、市场筛选、大盘指数 |
| AI | AiQuickActions / AIService / AISettingsPanel / WatchlistPanel 内嵌对话框 | 多配置管理、快讯速览与自选组合分析、多轮追问 |
| 状态栏隐蔽行情 | MarketMonitorService / MarketStatusBarWidgetFactory / AbnormalThresholds / MarketHours | 低频行情快照、状态栏图标 + 悬停明细、个股异动阈值、开市时段共享判断 |

## 入口与页面结构

```
plugin.xml (toolWindow id="Finance 7x24 News", anchor=right)
  └─ KuaixunToolWindowFactory
       └─ KuaixunPanel                    # 根面板
            └─ JTabbedPane
                 ├─ Tab0 快讯: newsPanel
                 └─ Tab1 自选: WatchlistPanel
```

KuaixunPanel 在 tab 切换监听里互斥启停两个面板各自的刷新计时器——只有可见 tab 的计时器在运行。两个面板都通过 `addNotify`/`removeNotify` 管理生命周期（挂到 UI 时启动计时器并立即刷新一次，移除时停止）。

状态栏 widget 经 `statusBarWidgetFactory`（id=`Finance7x24.Market`）独立注册，不依赖工具窗口是否打开；与 KuaixunPanel 一样订阅 MarketMonitorService 快照，`addNotify`/`removeNotify`（widget 为 install/dispose）管理订阅。

## 快讯模块

- **KuaixunService**：请求东方财富 `newsapi.eastmoney.com` 快讯列表（column=102，每页 50 条）；响应是 JSONP 包裹的 JSON，`doRequest` 里剥壳后用 Gson 解析为 `KuaixunItem` 列表。`checkNewCount` 接口已定义但当前未使用。
- **KuaixunPanel**：
  - 单一 1 秒 tick 的 Swing Timer 驱动倒计时和触发刷新（20 秒间隔），避免两个 timer 不同步；状态栏文案由 `setStatus`/`updateCountdownDisplay` 维护。
  - 手动刷新 `refreshData()` 清空重建；自动刷新 `doCheckNewData()` 用 SwingWorker 增量插入：按 `knownIds` 去重、逆序插到顶部，并做滚动位置补偿（保存视口 y → 插入卡片 → 布局完成后累加插入高度回写视口位置），刷新期间所有卡片 alpha 半透明闪烁。
  - `NewsCard` 为自绘圆角卡片（paintComponent 画背景/边框/时间 pill），支持 hover 高亮与 fade 动画；标题和摘要用自动换行的不可编辑 JTextArea。卡片顶行右侧有「AI 解读」按钮，点击调 `WatchlistPanel.openNewsInterpretation`：后台批量拉一次自选行情拼出"名称(代码)"清单，连同快讯标题/摘要以 `NEWS_INTERPRET_PROMPT` 在 AI 会话页签首发提问，解读结果可继续追问（复用自选分析的面板与发送逻辑）。同一会话按新闻标题归属，点另一条快讯会重建会话，不复用上一条的追问上下文。卡片另带自选关联静默标记：标题/摘要命中快照中的自选名称时，paintComponent 在时间胶囊右侧画主题蓝小圆点，tooltip 显示命中的自选名称；快照更新时 `refreshCardMarks` 重算已有卡片，无任何弹窗通知。

## 行情模块

### 代码规范与市场识别

- `CodeUtils.normalize(raw)`：用户输入 → 统一存储格式。纯 6 位数字按首位（5/6/9 → sh，否则 sz）推断前缀；黄金别名（xauusd/gold 等）归一为内置品种代码。
- `CodeUtils.stripPrefix(code)`：去 sh/sz/hk/us 前缀，用于把用户存储的代码与行情返回的 `QuoteItem.getCode()`（裸代码）对上。
- `MarketType.fromCode(code)`：按前缀与形态判别 A/HK/US/GOLD，A 是兜底值。
- `GoldInstrument`：内置贵金属品种库（伦敦金 xau、纽约金 gc、伦敦银 xag、纽约银 si）。东财搜索接口返回的黄金代码取不到行情，所以搜索时用关键词匹配本库品种混入结果。

### 多级兜底拉取（QuoteService.fetchQuotes）

每个代码先归一化为 `RequestedCode`（携带各源专用编码），随后三级数据源依次补齐：

1. **东方财富**（仅 A股/港股）：push2delay JSON 接口，UTF-8；
2. **腾讯财经**（其余全部含美股、黄金）：GBK 文本接口，`;` 分行 `~` 分字段，黄金走 `hf_` 系列、字段逗号分隔单独解析；
3. **新浪财经**（最终兜底）：GBK 文本接口，A股/港股/美股字段布局不同分别解析，美股昨收由现价减涨跌额反推。

任一环节异常仅记录日志继续下一源；结果按请求顺序还原输出。全失败抛 `IllegalStateException` 由上层提示网络异常。每个源使用各自 Referer，超时均为连接/读取 10 秒。

### 日K均线（fetchMaInfos）

仅在用户触发 AI 分析时调用，不挂在行情轮询上：

- `MA_EXECUTOR`（固定 4 daemon 线程池）逐只并行拉腾讯前复权日K（70 根收盘价），计算 MA5/10/20/60、MA20 乖离、5 日涨跌封装为 `MaInfo`；
- 黄金品种不支持日K直接跳过；港股指数字母代码转大写（如 hkHSI）走通用端点；美股走专用端点且逐个尝试交易所后缀 `.OQ/.N/.A`；
- 数据不足 6 根视为无效 → 该股在 AI 上下文中没有均线行（渐进降级）。

### WatchlistPanel

- 表格 4 列：名称（两行式单元格——名称一行、代码小字灰色一行，单元格直接携带 QuoteItem）+ 最新价/涨跌幅/涨跌额；名称列按中文 Collator 排序，数值列比较器先解析字符串再比大小；表头点击三态排序（倒序 → 正序 → 默认），排序列与状态持久化（恢复时校验列号越界，兼容旧版 5 列布局存下的状态）。
- 顶部市场筛选标签（A股/港股/美股/黄金）：切换时过滤自选 codes、替换底部大盘指数组并恢复该市场的选中项、重置倒计时刷新。
- 底部大盘指数：每组固定代码清单（上证指数等/恒指恒科/道纳标普/四只贵金属），与自选合并成一次行情请求拉回后拆分显示，涨跌沿用 A 股配色（涨红跌绿，深浅主题双色适配）。
- 刷新流程 `refreshQuotes(force)`：闭市时只置“闭市中”；开市则起后台线程请求，失败重试最多 3 次（间隔 1.5s），成功后 invokeLater 更新表格/指数/状态栏；`isRefreshing` 防重入，10 秒轮询间隔由倒计时控制。
- 各市场开市判断委托共享的 `MarketHours.isOpen`（A股/港股/美股/黄金）。A 股、港股、美股显式指定时区；黄金按伦敦夏令时/冬令时区分开收盘时间并计入每日结算休市窗；均不处理法定节假日（已知简化）。
- 右键菜单除「删除选中」外另有「异动阈值…」：单只或多选批量设置个股异动阈值（%），留空恢复默认；阈值经 `AbnormalThresholds` 按规范化自选代码存应用级配置（表格内是行情源展示码，需 `storedCodeFor` 映射回存储码），保存后调 `MarketMonitorService.refreshNow()` 立即重算；删除自选时同步清除对应阈值键。

### 状态栏隐蔽行情（MarketMonitorService / MarketStatusBarWidgetFactory）

- **MarketMonitorService**（applicationService）：应用级只读快照 `Snapshot`（自选行情、异动清单及同序阈值、A 股三大指数、闭市标志）。首个订阅者启动轮询：`AppExecutorUtil` 共享调度器 60 秒触发一次，实际请求派发到应用线程池执行，`inFlight` 防重入；开市 60 秒 / 全闭市 5 分钟低频维护（`refreshNow` 可跳过闭市间隔）。自选代码 + 上证指数/深证成指/创业板指合并为一次 `fetchQuotes` 批量请求；异动判定按个股阈值（`AbnormalThresholds.get`，默认 3%）。快照更新经 `invokeLater` 通知订阅者（状态栏 widget、KuaixunPanel 卡片标记）。
- **MarketStatusBarWidgetFactory**：IconPresentation widget，常态仅显示工具窗口同款灰色折线图标（13px，无红绿、形似性能监控 sparkline）；`getClickConsumer` 返回 null——无点击行为，误点不暴露行情窗口；全部明细在悬停 tooltip 的三列单行网格：「大盘」标题行（休市标记居右）+ 指数逐行 + 自选异动逐行（名称左、阈值灰字右对齐中列、涨跌幅右列；自选行与大盘行同高），异动红涨在前、绿跌在后，组内按幅度降序。排版硬约束：单元格包 `nobr`（IDE 会先以窄宽度布局 tooltip，可断行单元格被压成逐字换行）；不用 colspan（跨列长行的偏好宽度会把表格撑宽，超屏幕可用宽度后右列遭裁切）；不用 valign（JBR17 的 Swing 表格使 valign 单元格内容整体消失）。不产生任何系统通知；用户可经状态栏右键菜单隐藏 widget。扩展点声明 `order="last"` 把 widget 钉在状态栏最右端：开闭文件引起编辑器 widget（行号/编码/缩进等）增减时图标位置恒定，否则会被挤进编辑器 widget 区、形似平台小图而被误认为消失。
- 设计约束（产品硬要求）：面向 IDE 内隐蔽看盘场景，禁止主动提醒、禁止状态栏出现金融字样、禁止点击弹出行情窗口；信息只做静默被动呈现。

## AI 模块

### 预设模板与多配置

`AIService` 不再维护运行时厂商 / 模型注册表。`PRESETS` 仅作为「新建配置」时的一键填充模板（商汤 SenseNova、智谱 GLM 常用模型 + 空白配置），每项含接口地址、模型 ID、采样温度与是否附加 `reasoning_effort=none`。

实际生效的数据是 `AiProfile`（id、名称、BaseURL、模型 ID、temperature、reasoningEffortNone、registerUrl、legacyKeySlot）：列表以 JSON 存应用级 `finance.ai.profiles`，启用项存 `finance.ai.profile`。每份配置的 Key 按 `profile:<id>` 凭据槽单独存 PasswordSafe，互不覆盖。`AIService(apiKey, AiProfile)` 按配置构造——BaseURL 经 `normalizeBaseUrl` 规范化并自动补全 `/chat/completions`，采样参数取实例字段。

旧版迁移 `migrateLegacyProfiles` 在首次读取时把 `finance.ai.custom.*` 与 `finance.ai.model` 转成配置列表，旧 Key 只记录原槽位，由 `migrateProfileKey` 在后台首次读 Key 时懒迁移，避免升级启动同步访问钥匙串卡住 EDT。

### API Key 安全存储（v2.1.0 起）

- Key 存 IDE PasswordSafe，凭据子系统 `Finance7x24AI`（对应 macOS 钥匙串 / Windows 凭据管理器 / Linux KeePass）；空串保存等于清除凭据。
- `KEY_CACHE` 内存缓存避免每次访问系统钥匙串；`loadApiKeyAsync` 把可能弹授权框的首访移到后台线程，杜绝 EDT 卡顿。
- `migrateLegacyKey`：PasswordSafe 无值时检查旧版明文文件 `~/.finance7x24_ai_config.json`（支持 `key_厂商` 与更早的单 key 格式），一次性迁入凭据存储、从文件剔除该字段；无残留 Key 时整个文件删除。

### 分析上下文构建（buildMarketContext）

上下文头部含“数据时间”（北京时间）与“市场状态”——`sessionStatus` 按 code 所属市场算出子状态（盘前/集合竞价/开盘准备/盘中(早盘/午盘)/午间休市/盘后/夜盘；美股换算美东自动处理夏令时；伦敦金开收市时间随伦敦夏令时平移——冬令时北京时间周一 07:00 开、周六 06:00 收，夏令时各提前 1 小时；节假日未覆盖）。每股输出开高低收、振幅、日内位置、距开盘涨幅、开盘缺口及 K 线实体/影线比例；阳线/阴线/十字星以“现价 vs 昨收”判定并与涨跌幅符号保持一致。有均线数据的股票附均线行。

### 调用链

- **首轮分析** `analyzeWatchlistAsync`：后台拉均线（失败降级无均线）→ 构建上下文 → 以 `ANALYSIS_SYSTEM_PROMPT` 调模型 → `enforceConsistency` 后处理。
- **追问聊天** `chatAsync`：对话历史按下标定角色（偶数位 user、奇数位 assistant），先做 snapshot 防并发修改，再注入基线后发整段 messages。
- **HTTP 层** `callAPI`：temperature 与 `reasoning_effort` 由当前配置的实例字段决定（默认 0.3；kimi-k3 预设为 1，商汤预设附加 `reasoning_effort=none`），max_tokens 8000（思考过程与正文共用预算，避免正文被挤断）；超时分级——连接 10s、流式空闲 120s、整包 180s。401 报 Key 失效、429 指数退避重试最多 3 次、5xx 带错误体抛出；参数降级只在 400/422 上生效（400 按错误内容去掉 `temperature`、`max_tokens` 改 `max_completion_tokens`、或去掉 `stream` 改走整包），401/429/5xx 不会被错误体里的参数名带偏。流式判定不依赖 Content-Type：预读时跳过心跳注释与 `event:` 元数据，按第一条有内容的行是否以 `data:` 开头决定（兼容标成 `application/json` 却发 SSE 的渠道）；`cancel()` 会直接断开当前连接，不必等读超时。
- **解析** `extractResponseText`：依次尝试 `choices[0].message.content` → `reasoning_content` → `reasoning`；无法识别的结构只写日志、不把原文回显给用户（防敏感信息泄露）。输出统一过 `stripMarkdown` 去除 markdown 符号以适配 Swing 纯文本气泡。
- **流式增量** `parseDeltaParts`：一行 SSE 只解析一次 JSON，同时取出正文与思考增量（`delta.content` / `delta.reasoning_content`，个别渠道为 `reasoning`）。思考过程单独缓冲、不进气泡，等待期间只显示「模型思考中…（已接收 N 字）」；只有整条流完全没有正文时（如商汤 reasoning 模型把答案放在 reasoning 字段），才在收尾时把 reasoning 当正文处理，读超时也一样保留已接收的 reasoning 而不是只剩一句超时提示。

### 输出确定性与跨轮一致性

- `enforceConsistency` 后处理两条确定性规则：① 按三阶段纠正“今日/目前/昨日”措辞（盘前→昨日、盘中→目前、盘后→今日）；② K 线阴阳/十字星与涨跌幅符号矛盾时纠正。提示词层面的强约束（颜色唯一依据涨跌幅字段、阶段措辞、禁止编造数据等）是对抗模型跑偏的第一层，后处理是第二层兜底。
- `extractBaseline` 从最近一条含结论的 assistant 回答中逐品种提取操作建议；`appendBaseline` 将其作为最高优先级 header 注入 system prompt，要求数据未变不得推翻、推翻必须说明变化原因；`isUserChallenge` 用关键词启发式识别“质疑≠新数据”，此时追加辩护指引防止因用户反驳就翻转结论。

## 设置入口与对话框

- **AISettingsPanel**：多配置表单（配置下拉 + 新建/删除、名称/地址/模型/Key 编辑、测试连接），由 `AISettingsDialog`（工具窗口标题栏齿轮的一键入口，已打开则置前防重复弹窗）与 `AISettingsConfigurable`（IDE 设置页，Settings → Tools → Finance 7x24 News AI，applicationConfigurable）共用。切换配置时表单值写回上一份、Key 异步回填且互不覆盖；只有用户真正编辑过的 Key 才回写，避免异步回填未完成时把空串当成「清空 Key」落盘；测试连接期间按钮禁用并显示耗时进度，结束就地回显结果。
- **AISettingsDialog**：薄壳对话框，只负责窗口尺寸/居中与取消/保存按钮；保存前统一校验所有配置的地址/模型/名称，再整体落盘。
- **AISettingsConfigurable**：标准 Configurable 生命周期（isModified/apply/reset）；`isModified` 用面板快照 `getSnapshot()` 比较，快照不含异步回填的 Key 值，避免钥匙串首访导致误判或阻塞设置页。保存后工具窗口下次进入 AI 入口时经 `refreshAiConfigFromStorage` 重读配置。
- **AI 会话面板** `AiChatPanel`：作为工具窗口内的页签承载，不再弹独立 JDialog。聊天气泡为自绘圆角面板（不拼 HTML），主题色取 `JBColor`；气泡宽度按页签实际宽度自适应并随窗口缩放重排。发送失败会向 history 补一条 assistant 占位符，维持偶 user/奇 assistant 交替——这是 `chatAsync` 下标定角色机制的前提。三类会话（选股分析 / 速览 / 快讯解读）各占一个页签，页签标题带关闭按钮：关闭即 `disposeSession`（中止进行中的请求并标记失效），再次点入口是全新一轮；会话仍在时点入口只切页签，气泡与 history 原样保留。页签关闭期间的流式回调由 `disposed` 标志兜底跳过。生成期间发送按钮切换为「停止」。请求发出即显示「正在等待模型响应…」占位，思考过程（`reasoning_content`）不进入气泡、只显示「模型思考中…（已接收 N 字）」；正文一到即经 `STREAM_HINT_PREFIX` 以灰字斜体、无底框的弱化样式**只追加**显示，不压草稿、不中途整段替换；流结束时 `finishStreaming` 切回正式气泡，最终文本只丢弃开头前言与夹在中间的推理自述，不再按「最后一份」重排（流式已显示过的内容不会被抹掉）。快讯解读会话按新闻标题归属，换一条快讯会重建会话。
- **会话宿主** `AiSessionHost`（由 `KuaixunPanel` 实现）：把 AI 面板挂到「快讯 / 自选」同一组内层页签上；页签切换驱动两侧行情轮询的启停，打开 AI 会话时会 `toolWindow.activate` 把收起的工具窗口唤回前台。
- **标题栏入口** `AiQuickActions` / `ShowAiSettingsAction`：通过 `toolWindow.setTitleActions` 注册，Action 从 ToolWindow Content 反查 `KuaixunPanel` 再触发对应入口，快讯 / 自选 / AI 任意页签下都能一键直达。

## 线程模型

规则：UI 全部在 EDT；所有网络 IO 不占用 EDT，结果一律 `SwingUtilities.invokeLater` 回传。

| 场景 | 机制 |
|------|------|
| 快讯/自选自动刷新驱动 | 各自 1 秒 tick Swing Timer（EDT），只在到点时派发后台任务 |
| 快讯页面加载 / 自选行情刷新 | 裸 `new Thread`（后者带 3 次重试） |
| 快讯自动检查新增 | SwingWorker |
| 均线批量拉取 | QuoteService.MA_EXECUTOR，固定 4 daemon 线程 |
| 状态栏快照轮询 | AppExecutorUtil 共享调度器触发 + 应用线程池执行，inFlight 防重入 |
| AI 请求（分析/追问/Key 加载） | AIService.SHARED_EXECUTOR，固定 4 daemon 线程，CompletableFuture 回调 |
| 对话框异步回填 / 测试连接 | 裸 `new Thread` |

刷新均有 `isRefreshing` 防重入；倒计时归零才触发请求，闭市时 Timer 直接停掉。

## 持久化键清单

应用级 PropertiesComponent（全局共享，同一 IDE 的所有项目共用一份；键名常量在 `WatchlistPanel` 与 `AbnormalThresholds`）：

| 键 | 内容 |
|----|------|
| `eastmoney.7x24.watchlist.codes` | 自选代码，逗号分隔（normalize 后格式） |
| `eastmoney.7x24.watchlist.sortColumn` / `.sortState` | 三态排序状态 |
| `eastmoney.7x24.watchlist.marketFilter` | 当前市场筛选标签 |
| `eastmoney.7x24.watchlist.index.<MARKET>` | 各市场的大盘指数选中序号 |
| `eastmoney.7x24.watchlist.abnormal.<code>` | 个股异动阈值（%），未设置走默认 3%；删除自选时清除 |

迁移：2.1.0 及之前这些键存在项目级配置（各项目一份，互不相通）。升级后 `WatchlistPanel` 构造时执行 `migrateProjectScopedSettings`，把当前项目的旧值补种到应用级——仅当应用级从未写入过该键时才补种，因此用户在应用级已做的修改或清空不会被其他项目的旧数据覆盖。

应用级 PropertiesComponent：`finance.ai.profiles`（配置列表 JSON）、`finance.ai.profile`（启用配置 id）、`finance.ai.names.cleaned`（一次性名称规范化标记）。旧版 `finance.ai.model` / `finance.ai.custom.baseurl` / `finance.ai.custom.model` 只作迁移来源，不再写入。

PasswordSafe：子系统 `Finance7x24AI`，按配置凭据槽 `profile:<id>` 各存一条 Credentials；旧版按厂商名存放的槽位仅用于懒迁移。

沙箱调试的数据落在 `.intellijPlatform/sandbox/` 下（不入库）。

## 已知限制与技术债

- 所有市场开市判断均不含法定节假日（`MarketHours`，自选页与状态栏快照共用；各市场均已显式指定时区）。
- 网络请求散布多处裸 Thread，未复用共享线程池；每次分析新建 `QuoteService` 实例。
- 状态栏文案依赖 `contains("下次刷新")` 字符串判断再拼接，各状态文案互相耦合。
- 删除自选用 `endsWith` 尾号匹配，理论上有撞码边界。
- 聊天历史角色按下标硬编码（偶 user / 奇 assistant），依赖失败占位符维持交替。
- 无单元测试；外部公开接口的内容与可用性依赖数据源方。

## 发布惯例

1. 同步修改 `build.gradle.kts` 的 `pluginConfiguration.version` 与 `plugin.xml` 的 `<version>`；
2. `CHANGELOG.md` 新增版本小节；
3. `plugin.xml` 的 `<change-notes>` 只保留当前版本的更新内容；
4. 提交信息习惯用 `vX.Y.Z: 概述`（或 `feat:` / `fix:` / `docs:`），文档同步类改动单独提交。
