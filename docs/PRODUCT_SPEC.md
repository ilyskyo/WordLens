# WordLens（见词）产品说明书

> 交接文档。以产品经理视角写：先说这个软件是什么、替谁解决什么问题，再拆到每个页面的
> 交互细节与取舍。代码现状见文末「已完成 / 待做」。
>
> 状态：v0.9，MIT 开源，`compileSdk 36 / minSdk 26 / targetSdk 36`，包名 `com.ilyskyo.wordlens`

---

## 1. 一句话定义

**举起手机，画面里的东西自动变成可以点的词；点了什么就记住那个词，没点就存下整个场景。**

它不是背单词 App 加一个拍照按钮。顺序反过来：**先是日记，记忆是副产品**。

---

## 2. 要解决的问题

市面上的「拍照记单词」大多做成了这样：拍完 → AI 认出图 → 给你一张词卡 → 扔进复习队列。

这条链断在两处：

| 断裂 | 用户的实际感受 |
|---|---|
| 拍照前要先选模式（物体/场景），或拍完再选要记什么 | 「我就想拍张照」，多一步决策就少一半人 |
| 词和画面分离 | 复习时看到的是「cup /kʌp/ 杯子」，但**想不起来是在哪见过的** |

第二处更要命。人回忆一个词靠的是**画面**，不是那一行字。WordLens 的整个结构就是为了保住这个画面。

---

## 3. 目标用户

**主要**：已经有一定词汇量、在真实生活里遇到生词的人。拍照是他们原有的学习习惯，我们只是让这个动作顺便留下记忆。

**次要**：想学外语、但讨厌做题和背表的人。走 FSRS 间隔复习，但复习的入口是「那天在哪见过」。

**不是**：刷榜型用户。不做社交、不做排行榜、不做公开分享（见 §8.4）。

---

## 4. 核心交互：点不点，决定存什么

这是整个产品的地基，也是最容易被做错的地方。

### 4.1 早期方案：模式开关（已否掉）

「物体模式 / 场景模式」，拍照前切换。看起来清晰，实际有两个问题：

- 拍照那一刻用户**并不知道自己想记什么**。看到一杯咖啡才决定要记 cup，切换模式发生在动机之后，所以每次都别扭。
- 多一次选择就少一半完成率。这不是设置项，这是漏斗。

### 4.2 现行方案：动作二分

用户在取景器上**点某个物体**，或者**不点直接按快门**。就这两种，别的都没有。

| 用户动作 | 落库 | 去向 | 视觉 |
|---|---|---|---|
| 点了某个词片 | `WordCard` + 抠图贴纸 | 「记住」页 | 卡片有 die-cut 白色描边，像贴纸 |
| 没点，直接快门 | `Entry` 整张照片 + 氛围词 | 「回看」页时间轴 | 时间轴卡片 |
| 拍完补一句当时发生了什么 | `EventCard`（`source=USER`） | 「记住」页 | 无抠图，照片作缩略图 |

**「不点」不是一个失败分支，它是完整的一个产品动作。** 随手拍一张街景、记下当时的感觉，这本身就是日记。给它一个「你什么都没选」的空状态，等于告诉用户这次拍照白拍了。

### 4.3 覆盖层：两类词，分轻重

取景画面上同时浮着两种词，视觉权重由 `ShotClassifier` 的判帧结果分配：

- **物品词片**（`OverlayLayer.ITEM`）：锚在检测框**上方**，可点，点了推近镜头。
  锚在上方而不是中心——词压在杯子正中间会挡住用户真正在看的东西。
- **氛围词**（`OverlayLayer.AMBIENCE`）：自由分布，非交互。

权重分配规则：

| 判帧 | 物品词 | 氛围词 |
|---|---|---|
| `OBJECT`（主体饱满） | 最大、最醒目 | 缩到角落 |
| `SCENE`（环境） | 降为点缀 | 铺开 |
| `UNCLEAR` | 中性 | 中性 |

`ShotClassifier` 因此**降级为视觉权重分配器**，不再是模式门。判不清就取中性——**用户点哪个本身就是最终裁决**。

### 4.4 镜头推近

点词片 → `CameraFocusController` 逐帧下发 `SCALER_CROP_REGION` → 物体移到画面中央 → 抠图 → 存词。

- 320ms（用户主动点）/ 180ms（隐式聚焦），smoothstep 缓动。
- 必须走 Camera2 interop：`setZoomRatio` 只能**居中**放大，没法把物体推到中央。
- 动画起点**自己记**（`lastCrop`），不用 `zoomState` 反推。反推只在裁切居中时成立，而我们的动画会把裁切推偏——表现为「连续聚焦第二个物体时从错误位置开始移动」。这种 bug 在真机上极难察觉，因为画面看起来确实在动。

---

## 5. 信息架构

```
首页（两级，无第三个 tab）
├─ 顶部胶囊页签
│   ├─ 回看   ← 时间轴，主页
│   └─ 记住   ← 复习
├─ 底部常驻（仅一级页面出现）
│   ├─ 📷 左
│   └─ 🔍 右
├─ 二级页
│   ├─ 取景器（拍照）
│   ├─ 条目详情
│   ├─ 设置
│   └─ 搜索 / 添加
└─ 桌面小组件：今日待复习数
```

**为什么没有第三个 tab**：`词库` 作为独立页面没有价值——词汇库就是时间轴的一个筛选视图。分散成三个平级页面会让「今天该做什么」变得不明显，而这个问题每天都要回答一次。

### 5.1 底部两个圆

**参考 Apple 最新 tab 的视觉规范，但不做液态玻璃。** 理由：液态玻璃在 Android 上没有原生支持，做出来的都是模拟，还损失了可读性。

- 两个**独立**悬浮圆，不是一个胶囊拆两半。
- 56dp 视觉直径 / **88dp 触摸目标**（无障碍下限）。
- 主色**只用在拍照键**上。搜索键是中性色——强调拍摄符合产品定位（「拍下来」是主动作）。
- 二级页面隐藏，避免和页面内容抢注意力。

---

## 6. 两个主页

### 6.1 回看 —— 时间轴

日记的主路径，不是设置项。

- **按天分组**，今天 / 昨天 / 具体日期。前两种用相对说法——昨天那天的日期数字其实不携带信息。
  分组键存 ISO 日期而不是标签文本：标签随界面语言变，而 `LazyColumn` 的 key 一变整列重建。
- 时间轴卡片**两种形态**：
  - 有点纸：照片 + 散落的贴纸词（±3° 轻微旋转，手贴上去的感觉）。
  - 无贴纸：纯照片 + 摘要 + 关键词。
- 点击 → 条目详情：**在原图上重新把词长回物体上**（`EntryObject` 存的是四角归一化坐标，不是中心点加尺寸）。
- 顶部问候语随时段变化（`timeOfDayGreeting`）。

**`EntryObject` 为什么存四角坐标**：回看时要在图上画出框，不同宽高比下「中心 + 尺寸」与真实框的误差远大于四角直接存储。这条是「场景式」和普通日记的分界线。

### 6.2 记住 —— 复习

顶部二级切换：**词汇 / 事件**（`StudyMaterial` 还有第三档「都要」，多数时候用户就是想一起练，分开意味着要切两次页签）。

- 卡片**沿 Y 轴翻转**动效。
- 评级：**模糊 / 记住了** → 继续参与 FSRS 调度。
- **「标记已掌握」放长按菜单，不和评级混在一起。**

  实现细节：归档之后卡片永久退出队列，而**这个动作必须能撤销**。入口是复习页进度条下面常驻的
  「已归档 N 张」，点开是一个只负责列出来和「取消标记」的弹窗。它不只在空状态里出现——
  刚归档完那张卡后面还有下一张，如果入口只藏在队列清空之后，用户就没有反悔的地方了。

**这条很容易做错**：FSRS 里 EASY 会给出很长的间隔，界面上看着像「学完了」。但长间隔 ≠ 已掌握。一旦把两者混为一谈，用户会把还没真记住的词归档掉，而 FSRS 之后再也不会提醒他。

| | `WordCard` | `EventCard` |
|---|---|---|
| 正面 | 单词（或释义） | 一句事件描述 |
| 背面 | 音标、翻译、例句 | 原始要点、时间、照片 |
| 方向 | 认识 / 产出**两套** FSRS 状态 | 只有一套 |
| 视觉 | 抠出来的贴纸 | 照片缩略图 |

**为什么事件也做成卡片**：用户想练的不只是外语词，还有「那天发生了什么」。按间隔重复回放某一天的片段是真实有效的记忆训练，而且是这本日记**独有**的能力——背单词 App 做不到这个。两者共用同一个调度器（而不是写两套），是为了以后能得出「哪种素材更容易记住」的结论。

事件没有「产出」方向：复述一段经历和认出它的难度相同，所以只给一个状态。

### 6.3 四语双向

`Lang.ENGLISH / CHINESE / JAPANESE / KOREAN`。

- 认识 `cup` 和产出 `カップ` 是**两个不同的记忆**，各有独立的稳定度和遗忘次数，所以 `states` 按 `StudyDirection.name` 分开存。
- 词卡正面显示哪种语言由设置里的 `direction` 决定，不是每张卡各自决定。
- TTS 用 `TextToSpeech`。**中/日 TTS 质量随引擎差异极大**，所以界面要有 `voiceHint` 告诉用户「现在这个引擎念出来是什么样」以及怎么改善。

---

## 7. 记忆调度：FSRS-6

自己移植，已对照 awesome-fsrs wiki 与 fsrs4anki 官方 JS 校验。

修过 Blancall 版本的两处缺陷：

1. **同日复习下限缺失**：`stability = max(stability, 1)`，否则同日连续复习会让间隔塌到 0。
2. **缺两位小数取整**，导致日期间隔在小数位上漂移。

`requestRetention` 默认 0.9（Anki 的默认值）。

---

## 8. 几条硬规矩（每条都有具体的理由）

### 8.1 AI 生成的内容必须标来源

`ReviewSource.AI` 的事件，**复习时提示用户核对原文**。

这不是免责声明，是功能缺陷的修复：AI 摘要**可能本身就是错的**，而复习会不断巩固它。不标记的话，一个错误会被 FSRS 当成正确记忆刻进长期记忆——那比没有这个功能更糟。

`Entry.summarySource` 同理。用户自己写的东西被 AI 改过，要明确告知，不能悄悄替换。

### 8.2 不做具体性过滤

同类产品会过滤抽象词（只保留看得见的名物）。这里**故意不做**——匹配由检测器驱动，抽象词在场景模式下是**资产**：「孤独」「匆忙」正是拍照那一刻最该记住的。

### 8.3 软件不带在线 AI

只提供「用户填自己的 API Key」的可选后端（`CloudVisionEngine`）。默认关闭。

- 默认全本地运行。
- `redactBeforeUpload = true`：上传前剥掉 EXIF / 位置信息。

### 8.4 不做公开分享

没有分享入口。照片比单词卡敏感得多（家、孩子、证件），而这本日记的价值完全建立在「它只在我的机器上」之上。加分享是**另一个产品决策**，会连带影响 `mood` 是否可空，不在当前范围内。

### 8.5 API Key 明文存 DataStore

已确认的取舍，不是疏忽。key 属于用户，除了他主动发起的请求外不出设备；引入 keystore 意味着要么用已废弃的 API，要么背上一个维护负担。

### 8.6 存储用明文 JSON

原子写 + 损坏留证（损坏文件重命名保留，不静默丢弃）。

Room 能扛更大规模、查询也更方便，但**明文 JSON 可以 git diff、可以手改、可以 grep、可以不依赖工具直接备份**。一个个人词汇 App 的牌组规模，低几千条，够用。

### 8.7 云备份只带文字，不带照片和 key

`allowBackup` 是开着的，但两份规则文件（`backup_rules.xml` / `data_extraction_rules.xml`）都是**白名单**：
云端只有 `deck.json` 与 `diary.json`。

- 照片不上传：Android 的默认云备份**不是端到端加密**，而照片里可能是家、孩子、证件。
- `datastore` 里的设置不上传：里面有用户自己填的云端 API Key。§8.5 说的「不出设备」要靠这条兜住——
  备份是系统在你没点的情况下自己做的动作。
- `<device-transfer>` 反过来带全部（含照片与 key）：面对面迁移是端到端加密的，而且发生在用户主动换机那一刻。

代价写在这儿：**换机后旧条目只有文字没有原图**。这是刻意选择，不是遗漏。

顺带一个坑：`<exclude domain="sharedpref" path="."/>` 配 `<include domain="file" .../>` 会让
`lintVitalRelease` 直接报 `FullBackupContent` 错误——exclude 的路径必须落在某个 include 里。
白名单写法根本不需要 exclude。

---

## 9. 视觉规范

### 9.1 配色

| 角色 | 色值 |
|---|---|
| Primary | `#FF8A65` 暖珊瑚橙 |
| Secondary | `#4DB6AC` |
| Tertiary | `#FFD54F` |
| Background | `#FFF8F3` |

### 9.2 圆角

卡片 24dp / 按钮 20dp / 输入 16dp / Sheet 28dp。

### 9.3 贴纸

**4dp 白描边 + 6dp 阴影 + 旋转 ±3°**。白描边让它在任何背景上都读得出轮廓；±3° 的随机微旋转是「手贴上去」的来源，太大显得随意，太小没感觉。

### 9.4 字体

**Nunito**（标题）+ **Inter**（正文），可变字体。IPA 单独一套等宽样式（`IpaTextStyle`）——音标里的 `ʃ` `ɪ` `ː` 在比例字体下会连成一片。

### 9.5 网格

8dp 基准。

---

## 10. 依赖清单（全部钉死版本）

| 用途 | 库 | 许可证 |
|---|---|---|
| 检测 | MediaPipe tasks-vision `efficientdet_lite0`（13.5MB） | Apache-2.0 |
| 抠图 | MediaPipe `InteractiveSegmenter` / `magic_touch`（6.1MB） | Apache-2.0 |
| 识别兜底 | ML Kit `ImageLabeler` | Apache-2.0 |
| 相机 | CameraX 1.6.2 | Apache-2.0 |
| 词典 | ECDICT 12000 条（`tools/build_lexicon.py` 生成） | MIT |
| 存储 | kotlinx.serialization + DataStore | Apache-2.0 |
| 字体 | Nunito / Inter | OFL（原文在 `third_party/fonts/`） |

- **7 个图标全部自绘**，因此去掉了 `material-icons-extended`。
- **不用 Hilt / Room**。
- ABI 只编 arm64。
- **不用 `@latest`**：供应链可控，不要自动更新机制自动换版本。
- CC-CEDICT（CC BY-SA）已弃用。

`LICENSE`（MIT）、`THIRD_PARTY_NOTICES.md`、`third_party/fonts/OFL-*.txt` 齐备。

---

## 11. 代码结构

```
app/src/main/java/com/ilyskyo/wordlens/
├── MainActivity.kt                   两页 + 取景页宿主（CaptureHost）
├── WordLensApplication.kt            建 AppContainer
├── core/AppContainer.kt              手写 DI
├── data/
│   ├── model/      Entry · EventCard · WordCard · Lexicon · Scene · FsrsState
│   ├── repository/ Deck · Diary · Lexicon · Settings
│   └── store/      JsonDocument（原子写 + 损坏留证）
├── srs/Fsrs.kt                       FSRS-6
├── speech/Speaker.kt
├── vision/
│   ├── ShotClassifier · SceneClassifier · AmbienceScorer
│   ├── VisionRepository              引擎调度 + 背压
│   ├── MlKitOnDeviceEngine · CloudVisionEngine
│   ├── MagicTouchSegmenter · SubjectSegmenter · MlKitSubjectSegmenter
│   └── camera/
│       ├── CameraFocusMath.kt         纯几何（19 测试）
│       ├── OverlayGeometry.kt         框 → 屏幕坐标（15 测试）
│       ├── CameraFocusController.kt   逐帧下发裁切
│       ├── PhotoDecoder.kt            解码即按 EXIF 转正
│       └── YuvFrames.kt               YUV_420_888 → ARGB（11 测试）
├── widget/DueWidgetProvider.kt
└── ui/
    ├── theme/     Color · Type · Shape · Theme
    ├── icons/     WordLensIcons（7 个手绘）
    ├── components/ Common
    ├── nav/       HomeTabBar · HomeViewModel · WordLensApp
    ├── lookback/  时间轴 · 条目详情（词长回原图 + 补一句）
    ├── search/    搜索 / 添加（SearchScreen · SearchViewModel）
    ├── remember/  复习
    └── capture/   CaptureScreen · CaptureCamera · CaptureViewModel · ViewfinderOverlay
```

**两条架构约束**：

1. **纯逻辑不 import `android.graphics`。** `unitTests.isReturnDefaultValues = true` 会让 `android.graphics` 返回 0/空值，而 `RectF.equals` 不比内容——曾经因此 13/15 个测试失败，而且报错完全指不到真正的原因。`CameraFocusMath` 和 `OverlayGeometry` 因此各自定义 `NormBox` / `SensorCrop`。
2. **依赖方向 model ← vision。** `OverlayLayer` 放在 `data.model` 而不是视觉包里，因为它是**要持久化进日记文件**的语义。

---

## 12. 已完成 / 待做

### ✅ 已完成

- `assembleDebug` / `assembleRelease` + **86 个单测全通过**
- FSRS-6、`ShotClassifier`、`AmbienceScorer`、全部数据模型、JSON 存储、设置
- 视觉引擎全套：检测 / 抠图 / 标签 / 场景分类 / 云端（可选）
- 主题、7 个图标、两页 UI、取景器覆盖层、`CameraFocusController`
- **相机流水线端到端跑通**：`MainActivity.CaptureHost` 把 `PreviewView` 灌进 `CaptureScreen`，
  `CaptureViewModel` 管分析帧、选词片、抠图与落库（取景页每次进入都是一台干净的相机，所以它的
  VM 不挂导航 key）
- **搜索 / 添加页**：一个输入框同时命中牌组、词典与日记，词典命中可一键收录。
  `EntrySource.MANUAL` 从此有了可达路径——取景页那句「你可以直接把它写下来」现在能兑现了
- **四语 UI 资源齐了**：`values`（中，默认）+ `values-en` / `values-ja` / `values-ko`，74 条一一对应。
  时段问候、日期标签、氛围标记都改走资源——日期只把「月份短名」交给 CLDR，语序由每种语言自己的字符串决定
- **「标记已掌握」＋撤销入口**（见 §6.2），数据层与队列层都按它过滤
- **时间轴按天分组**（今天 / 昨天 / 日期）
- **条目详情页**：`EntryDetailScreen` 把 `EntryObject` 的四角坐标经 `imageBoxFromSensorNorm` +
  EXIF 转正映射回显示图，词片重新长在物体上方；页面下方的「补一句当时发生了什么」是
  **事件卡唯一的诞生地**（此前 `DiaryRepository.addEvent` 只被 Preview 调过）
- **照片解码统一到一个 `PhotoDecoder`**：`BitmapFactory` 不读 EXIF，CameraX 竖持写出的 JPEG
  像素网格是横的，所以时间轴此前会把照片显示成躺倒——而且不报任何错
- **桌面小组件**：读数走 `AppContainer.dueCount()`，点它直达「记住」页（冷启动读 intent、
  热启动走 `onNewIntent`，`launchMode` 已是 singleTask），评级与归档之后主动 `sendBroadcast` 刷新——
  系统自己的 `updatePeriodMillis` 下限是 30 分钟，对一个「还剩几个」的读数没有意义
- 词典 12000 条、两个模型文件、`scenes.json` / `ambience.json`
- `LICENSE` + `THIRD_PARTY_NOTICES.md` + `README.md` + `docs/BUILD.md` + GitHub Actions CI

### 🔧 进行中

**整条取景—拍摄—落库链路只在真机上跑过一部分，还没做系统的真机验证。** 类型检查与单测能证明几何
和调度是对的，证明不了「画面里的词片确实压在杯子上」。需要验的三件事：

1. 点词片 → 推近 → 抠图，贴纸的边缘是否干净（`magic_touch` 的 seed point 走的是传感器归一化坐标）。
2. 横竖屏与不同 `rotationDegrees` 下，回看页把词长回原图的位置是否还准。
3. 国产 ROM 的首帧黑屏（已按 `ImplementationMode.COMPATIBLE` 处理，要找具体机器确认）。

已修掉的三个真 bug（都有回归测试）：

- `OverlayGeometry` **漏了传感器旋转**：手机竖持时预览转 90°，不换算宽高比就比，映射在最常见的场景下是错的。
  之前测试用 `rotationDegrees = 0` 写，这条路径一次都没被覆盖。
- `OverlayGeometry` 的 **`FILL_CENTER` 语义搞反**：它是**居中裁切**，永不留黑边，两个分支的偏移都是负的。
- `CameraFocusMath.imageBoxFromSensorNorm` 的 **180° 分支不是 `orientedBox` 的逆**：180° 安装的传感器上，
  抠图会取到错的那块区域。现场表现是「贴纸内容对不上词」，而不是崩溃，所以极难发现。

### ⬜ 待做

| 项 | 备注 |
|---|---|
| 真机验证 | 见上面「进行中」，这是发布前唯一的硬门槛。详情页的词片位置是坐标链路的终点，也是最需要先看一眼的一段 |
| 四语词典 | 只有 `lexicon/en.json`。中/日/韩的词条需要从其他来源补，`LexiconEntry.words` 的结构已经支持多语言 |
| release 签名 | 签名四项写在 `local.properties`，缺省产物不签名（见 `docs/BUILD.md`）；要发版需要一个长期 keystore |
| 小组件配色 | RemoteViews 用不了 MaterialTheme，深色模式下读数颜色还是硬编码的一组，需要一套 night 资源 |
| `PROCESS_TEXT` / `SEND` intent | manifest 里声明了，接收端未实现 |

### ✅ 已决定：模型文件直接提交

`efficientdet_lite0.tflite` 13.5 MB + `magic_touch.tflite` 6.1 MB，**跟着仓库走**（原方案 A/B/C 里选 C）。

- 单文件远低于 GitHub 100 MB 上限；20 MB 一次性代价，换来 clone 后**离线可构建**，CI 不需要下载步骤，
  也不依赖第三方模型的 URL 一直有效。
- LFS 会把免费额度（1 GB 存储 / 10 GB 月流量）花在两个几乎不变的二进制上，clone 次数一多就先撞流量。
- 已经提交并推送过了，现在改 LFS 等于改写历史，代价大于收益。
- 以后如果加第三个模型文件、总量到 50 MB 以上，再考虑 LFS 或 release asset。

### R8 之后的实测体积

`assembleRelease`（arm64 单 ABI，`lintVitalRelease` 一起过）**49.1 MB**：

| 内容 | 体积 |
|---|---|
| `lib/arm64-v8a`（MediaPipe + ML Kit 原生库） | 22.1 MB |
| `assets/models`（两个 tflite，不压缩） | 20.1 MB |
| `assets/mlkit_label_default_model` | 3.0 MB |
| `classes.dex` | 2.7 MB |
| 资源 + 词典 + arsc | 约 1.1 MB |

代码只占 5%，剩下全是模型和原生库。想再瘦只能减模型，减 R8 配置没有意义。
debug 包 77.5 MB 的差值主要来自未压缩的 dex 与 debug 资源。