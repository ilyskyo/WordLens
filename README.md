# 见词 / WordLens

举起手机，画面里的东西自动变成可以点的词。**点了什么就记住那个词，没点就存下整个场景。**

它不是背单词 App 加一个拍照按钮。顺序是反的：先是一本日记，记忆是副产品。

## 为什么又做一个

现有的「拍照记单词」断在两处：

1. 拍照前要先选模式，或者拍完再挑要记哪个词 —— 多一次决策就少一半人。
2. 词和画面分离 —— 复习时看到的是 `cup /kʌp/ 杯子`，你想不起来是在哪儿见过它。

第二处更致命。人回忆一个词靠的是画面，不是那一行字。WordLens 的整个结构就是为了把画面留住：
词片锚在物体上方，点了它才生成贴纸词卡；没点，整张照片连同氛围词进时间轴。

## 交互只有两种

| 你做了什么 | 存下来的是 | 去哪里 |
|---|---|---|
| 点了一个词片 | 抠图贴纸 + 词卡 | 「记住」页，进 FSRS 队列 |
| 直接按快门 | 整张照片 + 氛围词 | 「回看」页时间轴 |

「没点」不是失败分支，它是完整的一个产品动作。

## 现状

个人项目，能装能用，还没有发布版本。

- Kotlin + Jetpack Compose，`compileSdk 36 / minSdk 26`，只编 `arm64-v8a`
- 全部识别在设备端跑：MediaPipe `efficientdet_lite0`（检测）+ `magic_touch`（抠图）+ ML Kit `ImageLabeler`（兜底）
- FSRS-6 间隔复习，中英日韩四语，认识与产出各自独立调度
- 不依赖 Room、不依赖 Hilt；数据是 `filesDir` 下两份可读的 JSON
- 86 个 JVM 单元测试，`./gradlew testDebugUnitTest`

产品的完整取舍写在 [docs/PRODUCT_SPEC.md](docs/PRODUCT_SPEC.md)，构建与环境写在
[docs/BUILD.md](docs/BUILD.md)。

## 隐私是功能，不是免责声明

- **软件不内置任何在线 AI。** 只提供「你自己填 API Key」的可选后端，默认关闭。Key 明文存在本机
  DataStore，不会离开设备。
- **上传前剥掉 EXIF 与位置信息**（`redactBeforeUpload`，默认开）。
- **照片不进云备份。** 云端只同步两份 JSON 文本；照片、抠图与设置文件被显式排除。换机迁移
  （端到端加密的那条路径）才带照片。
- **没有分享入口。** 不是还没做，是不做：照片比词卡敏感得多，这本日记的价值建立在「它只在我的机器上」。
- AI 整理出来的内容在复习时会明确标注来源并提示核对 —— 复习会把错误内容巩固成长期记忆，那比没有这个功能更糟。

## 构建

```bash
git clone https://github.com/ilyskyo/WordLens.git
cd WordLens
./gradlew assembleDebug        # 产物在 app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

需要 JDK 17 与 Android SDK 36。模型权重（约 20 MB）已随仓库提交，克隆下来就能离线构建。

## 许可

MIT，见 [LICENSE](LICENSE)。第三方依赖与两个模型文件的来源、许可证在
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；两款字体为 OFL，原文在 `third_party/fonts/`。
界面图标全部自绘。
