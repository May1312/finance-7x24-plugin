# Finance 7x24 News 架构说明

面向开发者的实现文档，覆盖入口链路、各模块设计、线程模型、持久化键与已知限制。功能说明见 [README.md](README.md)，版本历史见 [CHANGELOG.md](CHANGELOG.md)。

## 总览

单工具窗口 IntelliJ 插件（纯 Java + Swing，唯一第三方依赖 Gson），在 IDE 右侧挂载「Finance 7x24 News」窗口，内含快讯、自选两个标签页。三大模块：

| 模块 | 主要类 | 职责 |
|------|--------|------|
| 快讯 | KuaixunService / KuaixunPanel | 拉取东方财富 7x24 快讯，卡片流展示 + 自动刷新 |
| 行情 | QuoteService / WatchlistPanel / StockSearchDialog | 自选行情多级兜底拉取、日K均线、市场筛选、大盘指数 |
| AI | AIService / AISettingsDialog / WatchlistPanel 内嵌对话框 | 免费大模型 API 的自选组合分析与多轮追问 |

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

## 快讯模块

- **KuaixunService**：请求东方财富 `newsapi.eastmoney.com` 快讯列表（column=102，每页 50 条）；响应是 JSONP 包裹的 JSON，`doRequest` 里剥壳后用 Gson 解析为 `KuaixunItem` 列表。`checkNewCount` 接口已定义但当前未使用。
- **KuaixunPanel**：
  - 单一 1 秒 tick 的 Swing Timer 驱动倒计时和触发刷新（20 秒间隔），避免两个 timer 不同步；状态栏文案由 `setStatus`/`updateCountdownDisplay` 维护。
  - 手动刷新 `refreshData()` 清空重建；自动刷新 `doCheckNewData()` 用 SwingWorker 增量插入：按 `knownIds` 去重、逆序插到顶部，并做滚动位置补偿（保存视口 y → 插入卡片 → 布局完成后累加插入高度回写视口位置），刷新期间所有卡片 alpha 半透明闪烁。
  - `NewsCard` 为自绘圆角卡片（paintComponent 画背景/边框/时间 pill），支持 hover 高亮与 fade 动画；标题和摘要用自动换行的不可编辑 JTextArea。卡片顶行右侧有「AI 解读」按钮，点击调 `WatchlistPanel.openNewsInterpretation`：后台批量拉一次自选行情拼出"名称(代码)"清单，连同快讯标题/摘要以 `NEWS_INTERPRET_PROMPT` 在 AI 对话框首发提问，解读结果可继续追问（复用自选分析的聊天框，发送逻辑经 rootPane clientProperty 的 `sendMessage` 暴露）。

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
- 各市场开市判断：`isAShareOpen` / `isHKOpen` / `isUSOpen` / `isGoldOpen`。港股、美股显式指定时区；黄金按伦敦夏令时/冬令时区分开收盘时间并计入每日结算休市窗；均不处理法定节假日（已知简化）。

## AI 模块

### 厂商与模型注册

`AIService` 的 static 块通过 `registerProvider` / `registerModel` 注册厂商与模型信息（id、展示名、apiUrl、是否免费、允许的采样温度），维护 `MODELS`（id → ModelInfo）与 `PROVIDER_MODELS`（厂商 → 模型列表）两张表供设置对话框联动。新增厂商/模型只需在此追加一行。

自定义渠道：厂商表里有固定的「自定义 (OpenAI 兼容)」（`CUSTOM_PROVIDER`），无预置模型；设置对话框选中后隐藏模型下拉，切换为 BaseURL + 模型 ID 输入行。保存时 BaseURL/模型名写应用级 `finance.ai.custom.baseurl` / `finance.ai.custom.model`，`finance.ai.model` 记为哨兵值 `custom`（`CUSTOM_MODEL_ID`），Key 存 PasswordSafe 的自定义厂商名下。`AIService(apiKey, modelId, customBaseUrl)` 三参构造走自定义端点——BaseURL 经 `normalizeBaseUrl` 规范化并自动补全 `/chat/completions`；自定义模式不发 `reasoning_effort`、采样温度走默认值。

### API Key 安全存储（v2.1.0 起）

- Key 存 IDE PasswordSafe，凭据子系统 `Finance7x24AI`（对应 macOS 钥匙串 / Windows 凭据管理器 / Linux KeePass）；空串保存等于清除凭据。
- `KEY_CACHE` 内存缓存避免每次访问系统钥匙串；`loadApiKeyAsync` 把可能弹授权框的首访移到后台线程，杜绝 EDT 卡顿。
- `migrateLegacyKey`：PasswordSafe 无值时检查旧版明文文件 `~/.finance7x24_ai_config.json`（支持 `key_厂商` 与更早的单 key 格式），一次性迁入凭据存储、从文件剔除该字段；无残留 Key 时整个文件删除。

### 分析上下文构建（buildMarketContext）

上下文头部含“数据时间”（北京时间）与“市场状态”——`sessionStatus` 按 code 所属市场算出子状态（盘前/集合竞价/开盘准备/盘中(早盘/午盘)/午间休市/盘后/夜盘；美股换算美东自动处理夏令时；伦敦金周一 07:00 至周六 04:00 视为交易中，节假日未覆盖）。每股输出开高低收、振幅、日内位置、距开盘涨幅、开盘缺口及 K 线实体/影线比例；阳线/阴线/十字星以“现价 vs 昨收”判定并与涨跌幅符号保持一致。有均线数据的股票附均线行。

### 调用链

- **首轮分析** `analyzeWatchlistAsync`：后台拉均线（失败降级无均线）→ 构建上下文 → 以 `ANALYSIS_SYSTEM_PROMPT` 调模型 → `enforceConsistency` 后处理。
- **追问聊天** `chatAsync`：对话历史按下标定角色（偶数位 user、奇数位 assistant），先做 snapshot 防并发修改，再注入基线后发整段 messages。
- **HTTP 层** `callAPI`：temperature 0.3、max_tokens 4000；`reasoning_effort=none` 仅商汤渠道附加；401 报 Key 失效、429 指数退避重试最多 3 次、5xx 带错误体抛出。
- **解析** `extractResponseText`：依次尝试 `choices[0].message.content` → `reasoning_content` → `reasoning`；无法识别的结构只写日志、不把原文回显给用户（防敏感信息泄露）。输出统一过 `stripMarkdown` 去除 markdown 符号以适配 Swing 纯文本气泡。

### 输出确定性与跨轮一致性

- `enforceConsistency` 后处理两条确定性规则：① 按三阶段纠正“今日/目前/昨日”措辞（盘前→昨日、盘中→目前、盘后→今日）；② K 线阴阳/十字星与涨跌幅符号矛盾时纠正。提示词层面的强约束（颜色唯一依据涨跌幅字段、阶段措辞、禁止编造数据等）是对抗模型跑偏的第一层，后处理是第二层兜底。
- `extractBaseline` 从最近一条含结论的 assistant 回答中逐品种提取操作建议；`appendBaseline` 将其作为最高优先级 header 注入 system prompt，要求数据未变不得推翻、推翻必须说明变化原因；`isUserChallenge` 用关键词启发式识别“质疑≠新数据”，此时追加辩护指引防止因用户反驳就翻转结论。

## 设置入口与对话框

- **AISettingsPanel**：AI 设置表单（厂商/模型、自定义 BaseURL+模型 ID、Key、测试连接），由 `AISettingsDialog`（工具窗口「设置」按钮的一键入口，已打开则置前防重复弹窗）与 `AISettingsConfigurable`（IDE 设置页，Settings → Tools → Finance 7x24 News AI，applicationConfigurable）共用。
- **AISettingsDialog**：薄壳对话框，只负责窗口尺寸/居中与取消/保存按钮；自定义渠道校验必填后保存。
- **AISettingsConfigurable**：标准 Configurable 生命周期（isModified/apply/reset）；`isModified` 中 Key 与内存缓存（`getCachedApiKey`）比较，避免首次访问系统钥匙串阻塞设置页。设置页保存后，工具窗口在下次进入 AI 分析/解读入口时经 `refreshAiConfigFromStorage` 拾取新配置。
- **AI 对话框**（内嵌于 WatchlistPanel）：聊天区是单个 JTextPane 拼 HTML；由于 Swing 只支持 HTML 3.2 且不支持 rgba，气泡颜色按面板背景亮度预计算并预融合；跨组件引用（loadingPanel/loadingLabel/appendMessage/sendMessage 回调）挂 rootPane clientProperty 传递。发送失败会向 history 补一条 assistant 占位符，维持偶 user/奇 assistant 交替——这是 `chatAsync` 下标定角色机制的前提。Esc 关闭，重复点 AI 分析按钮只会 toFront 已有对话框。

## 线程模型

规则：UI 全部在 EDT；所有网络 IO 不占用 EDT，结果一律 `SwingUtilities.invokeLater` 回传。

| 场景 | 机制 |
|------|------|
| 快讯/自选自动刷新驱动 | 各自 1 秒 tick Swing Timer（EDT），只在到点时派发后台任务 |
| 快讯页面加载 / 自选行情刷新 | 裸 `new Thread`（后者带 3 次重试） |
| 快讯自动检查新增 | SwingWorker |
| 均线批量拉取 | QuoteService.MA_EXECUTOR，固定 4 daemon 线程 |
| AI 请求（分析/追问/Key 加载） | AIService.SHARED_EXECUTOR，固定 4 daemon 线程，CompletableFuture 回调 |
| 对话框异步回填 / 测试连接 | 裸 `new Thread` |

刷新均有 `isRefreshing` 防重入；倒计时归零才触发请求，闭市时 Timer 直接停掉。

## 持久化键清单

应用级 PropertiesComponent（全局共享，同一 IDE 的所有项目共用一份；键名常量在 `WatchlistPanel`）：

| 键 | 内容 |
|----|------|
| `eastmoney.7x24.watchlist.codes` | 自选代码，逗号分隔（normalize 后格式） |
| `eastmoney.7x24.watchlist.sortColumn` / `.sortState` | 三态排序状态 |
| `eastmoney.7x24.watchlist.marketFilter` | 当前市场筛选标签 |
| `eastmoney.7x24.watchlist.index.<MARKET>` | 各市场的大盘指数选中序号 |

迁移：2.1.0 及之前这些键存在项目级配置（各项目一份，互不相通）。升级后 `WatchlistPanel` 构造时执行 `migrateProjectScopedSettings`，把当前项目的旧值补种到应用级——仅当应用级从未写入过该键时才补种，因此用户在应用级已做的修改或清空不会被其他项目的旧数据覆盖。

应用级 PropertiesComponent：`finance.ai.model`（选中的模型 id）。

PasswordSafe：子系统 `Finance7x24AI`，按厂商名各存一条 Credentials。

沙箱调试的数据落在 `.intellijPlatform/sandbox/` 下（不入库）。

## 已知限制与技术债

- 所有市场开市判断均不含法定节假日；`isAShareOpen` 使用系统默认时区而非显式 Asia/Shanghai（其余市场均已显式指定），非东八区机器上判断会偏差。
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
