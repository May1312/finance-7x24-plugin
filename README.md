# Finance 7x24 News IntelliJ Plugin

Finance 7x24 News 是一个 IntelliJ IDEA 工具窗口插件，用于在 IDE 内查看 7x24 财经快讯，并维护股票、ETF、指数的自选行情。

## 功能

- 在 IDEA 右侧工具窗口展示 7x24 财经快讯
- 快讯支持自动刷新、手动刷新和内容自动换行
- 提供自选行情标签页，支持股票、ETF 和指数代码
- 自选列表名称与代码两行展示
- 自选行情支持添加、右键删除、手动刷新和自动刷新
- 支持按表头排序，连续点击可在倒序、正序、默认顺序间切换
- 行情接口支持多数据源兜底，优先使用东方财富，失败时自动尝试腾讯财经和新浪财经
- 行情涨跌使用 A 股习惯配色：上涨红色，下跌绿色
- 支持 A 股 / 港股 / 美股 / 国际金价（伦敦金 XAU/USD）自选行情
- 自选页提供市场筛选标签：A 股 / 港股 / 美股 / 黄金
- 各市场自动刷新，均按各自开市时段运行，闭市时自动暂停并显示"闭市中"
- 搜索框可搜索 A 股、港股、美股（带市场标签）
- 底部展示大盘指数实时行情，按当前市场筛选标签自动切换（A股/港股/美股/黄金指数）
- **AI 自选股分析**：基于实时行情一键分析自选组合，自动接入日K均线数据（MA5/10/20/60），支持多轮追问对话
- AI 支持多厂商模型切换：商汤 SenseNova、智谱 GLM（均提供免费模型）
- AI 对话气泡界面自适应 IDEA 深色 / 浅色主题

## AI 自选股分析

自选页提供 **AI 分析** 功能，可对当前市场筛选下的自选组合进行综合研判，并支持追问的多轮对话。

### 支持的厂商与模型

| 厂商 | 模型 | 说明 |
|------|------|------|
| 商汤 SenseNova | `deepseek-v4-flash`、`sensenova-6.8-flash-lite`、`glm-5.2`、`kimi-k3` | 免费模型，需注册获取 API Key |
| 智谱 GLM | `glm-4-flash` | 免费模型，需注册获取 API Key |

### 首次使用

1. 点击自选页的 **AI 分析** 按钮
2. 在弹出的设置对话框中，选择厂商与模型，填入对应的 API Key
3. 点击「保存」后即可自动发起首次分析

之后点击 AI 分析会直接基于最新行情生成分析，无需重复配置；如需更换 Key 或模型，可从 AI 对话框右上角再次打开设置。

### 数据与隐私

- API Key 保存在 IDE 的凭据存储中（PasswordSafe，对应 macOS 钥匙串 / Windows 凭据管理器 / Linux KeePass），不明文落盘
- 旧版本曾将 Key 明文保存在 `~/.finance7x24_ai_config.json`；升级后会自动将该文件中的 Key 迁移到凭据存储，并从原文件剔除，无残留 Key 时删除该文件
- 发送给 AI 服务的内容仅包含自选代码与实时行情数据（不含 IDE 内其他信息）
- AI 输出基于模型生成，仅供参考，不构成任何投资建议

## 安装

### 从 JetBrains Marketplace 安装

可搜索 `Finance 7x24 News` 安装，或直接访问 JetBrains Marketplace 插件页安装。

### 从本地文件安装

运行以下命令构建插件包：

```bash
./gradlew buildPlugin
```

然后在 IDEA 中选择 `Settings` -> `Plugins` -> 齿轮图标 -> `Install Plugin from Disk...`，选择 `build/distributions/` 下生成的 zip 文件。

## 兼容性

- IntelliJ IDEA 2024.1 及以上版本（sinceBuild 241，不设上限版本）
- 基于 IntelliJ Platform 2024.1.7 构建，Gradle Wrapper 9.6.1

## 开发

环境要求：JDK 17 及以上（IntelliJ Platform 2024.1 的要求）。常用任务：

```bash
./gradlew buildPlugin   # 构建插件包，输出到 build/distributions/
./gradlew runIde        # 启动沙箱 IDEA 实例进行本地调试
```

代码结构（`src/main/java/com/plugin/finance/`）：

```
├── KuaixunToolWindowFactory   # 工具窗口入口
├── KuaixunPanel               # 快讯页：卡片列表 + 20秒自动刷新
├── WatchlistPanel             # 自选页：行情表格 / 市场筛选 / 大盘指数 / AI 对话框
├── QuoteService                # 行情多级兜底 + 日K均线计算
├── KuaixunService              # 快讯接口
├── StockSearchDialog/Service   # 股票搜索
├── AIService                   # AI 厂商模型注册 / 提示词 / Key 凭据存储 / 接口调用
├── AISettingsDialog            # AI 设置对话框
└── 其余为模型类与工具类          # QuoteItem / KuaixunItem / MarketType / CodeUtils / GoldInstrument
```

架构设计、线程模型、持久化键与已知限制等实现细节见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 数据来源

插件展示的财经快讯来自公开财经快讯接口。自选行情按以下优先级尝试数据源：

1. **东方财富**（延迟行情 JSON 接口，UTF-8）
2. **腾讯财经**（GBK 编码文本接口）
3. **新浪财经**（GBK 编码文本接口）

任一数据源成功即使用，全部失败时提示网络异常。

自选代码格式：

| 市场 | 格式 | 示例 |
|------|------|------|
| A 股 | `sh`/`sz` + 6 位代码 | `sh600000`、`sz000001` |
| 指数 | `sh` + 6 位代码 | `sh000001` |
| 港股 | `hk` + 5 位代码 | `hk00700` |
| 美股 | `us` + 字母代码 | `usAAPL` |
| 黄金/贵金属 | 内置品种库代码 | `xau`（伦敦金）、`gc`（纽约金）、`xag`（伦敦银）、`si`（纽约白银） |

公开接口的数据内容、更新频率、实时性和可用性均取决于对应数据来源服务，插件不会保证行情数据实时、完整或持续可用。

## 免责声明

本插件为非官方插件，与相关数据服务商及其关联公司无隶属、授权、认可或赞助关系。

插件仅用于在 IDE 中便捷查看公开财经资讯，不提供投资建议。财经信息可能存在延迟、遗漏或错误，请以官方渠道和实际市场信息为准。使用本插件产生的任何投资决策和后果由用户自行承担。

## 隐私

本插件不会收集、存储或上传用户个人信息。插件会请求公开财经快讯和行情接口以获取资讯内容。自选代码与界面状态保存在 IDE 的应用级本地配置中，同一 IDE 的多个项目共享一份（旧版按项目保存的数据会在升级后首次运行时自动迁移）。AI 服务的 API Key 保存在 IDE 凭据存储（PasswordSafe）中，不上传、不明文落盘；旧版明文保存的 Key 会自动迁移并从原文件清除。AI 分析仅向所选 AI 服务发送自选代码与行情数据。