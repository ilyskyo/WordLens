# 构建与环境

## 需要的东西

| 项 | 版本 | 备注 |
|---|---|---|
| JDK | 17 | AGP 8.x 的要求。Android Studio 自带的 JBR 就可以直接用 |
| Android SDK | `compileSdk 36`（含 platform 与 build-tools） | `minSdk 26 / targetSdk 36` |
| Gradle | 用仓库里的 wrapper，别自己装 | `./gradlew` 会自动拉对应版本 |

模型权重（`efficientdet_lite0.tflite` 13.5 MB + `magic_touch.tflite` 6.1 MB）**已随仓库提交**，
克隆下来就能离线构建，不需要任何下载步骤。

## 指向一个 JDK

```bash
# Linux / macOS
export JAVA_HOME="/path/to/jdk-17"
# Windows（PowerShell）
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

`gradle.properties` 里刻意**不写** `org.gradle.java.home`：那是机器特定的路径，写进去之后
别人的机器就构建不了。

## 常用命令

```bash
./gradlew assembleDebug          # debug APK，applicationId 带 .debug 后缀
./gradlew installDebug           # 装到已连接的设备
./gradlew testDebugUnitTest      # 86 个 JVM 单测，毫秒级
./gradlew assembleRelease        # R8 + shrinkResources + lintVitalRelease
```

release 产物默认**不签名**：签名四项（`release.storeFile` / `storePassword` / `keyAlias` /
`keyPassword`）写在 git-ignored 的 `local.properties` 里，缺任一项就跳过签名而不是退回 debug key。
静默用 debug key 签一个「release」，是把所有人都没法升级的包发出去的经典做法。

## 只编 arm64

`ndk.abiFilters` 只有 `arm64-v8a`。MediaPipe 与 ML Kit 每个 ABI 带 10–15 MB 原生库，而
`minSdk 26` 之后还在跑的机器基本都是 64 位。

后果是 **x86_64 模拟器装不上**。要在模拟器上跑，本地临时加一行 `x86_64` 就好，别提交：

```kotlin
ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
```

## 词典是怎么生成的

`assets/lexicon/en.json`（约 12000 条）由 `tools/build_lexicon.py` 从 ECDICT（MIT）的 CSV 生成：

```bash
curl -L -o ecdict.csv https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv
python3 tools/build_lexicon.py ecdict.csv --out app/src/main/assets/lexicon/en.json
```

生成物提交进仓库，这样 clone 之后不需要再跑 Python。中/日/韩的词典数据还没有，`LexiconEntry.words`
的结构已经支持多语言，缺的是数据来源。

## 三个已经踩过的坑

1. **Kotlin 编译失败时，测试仍会跑上一次的 class。** 于是你会看到一批早已删掉的断言在报错，
   而真正的原因只在 `compileDebugKotlin` 的输出里。先 `grep '^e:'` 确认没有编译错误，再信测试结果；
   结果看着不可能时，用 `./gradlew testDebugUnitTest --rerun-tasks --no-configuration-cache`。
2. **纯逻辑不许 import `android.graphics`。** `unitTests.isReturnDefaultValues = true` 会让那些类
   返回 0/空值，`RectF.equals` 又不比较内容，于是几何测试全红且报错信息完全指不到原因。
   `CameraFocusMath` 和 `OverlayGeometry` 因此各自定义 `NormBox` / `SensorCrop`。
3. **取景首帧黑屏**在部分国产 ROM 上出现过，`PreviewView` 需要 `ImplementationMode.COMPATIBLE`。
   真机问题，CI 测不出来。

## 国内网络

`settings.gradle.kts` 走的是官方 `google()` + `mavenCentral()`。要换镜像就在自己机器上加
init script，或者临时改 `settings.gradle.kts` —— 但**别把镜像地址提交进仓库**，CI 和别人的机器
都会被带偏。
