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

### release 签名的可选配置

`app/build.gradle.kts` 里这段是**可选**的：四项齐全才 `create("release")` 签名配置，缺任何一项
就整段跳过，产物变成 `app-release-unsigned.apk`（文件名会明说不签名，不会让人误以为是渠道包）。

```properties
# local.properties（已被 .gitignore 挡住，不要提交）
release.storeFile=../keystore/wordlens.jks
release.storePassword=...
release.keyAlias=wordlens
release.keyPassword=...
```

要点：

- **不影响 R8**。签不签名，`minifyReleaseWithR8` 都会跑，`mapping.txt` 照样产出——所以下面的冒烟
  在没有 keystore 的机器上（包括 CI）能原样跑。
- 未签名的包要装到真机，得自己签：`apksigner sign --ks … app-release-unsigned.apk`，
  或者本地补上那四项直接产出签名包。
- keystore 一旦对外发版就是**长期承诺**：换 key 等于换一个应用，老用户收不到更新。
  所以 `待做` 里那条「要发版需要一个长期 keystore」是发布前的真门槛，不是形式主义。

## 只编 arm64

`ndk.abiFilters` 只有 `arm64-v8a`。MediaPipe 与 ML Kit 每个 ABI 带 10–15 MB 原生库，而
`minSdk 26` 之后还在跑的机器基本都是 64 位。

后果是 **x86_64 模拟器装不上**。要在模拟器上跑，本地临时加一行 `x86_64` 就好，别提交：

```kotlin
ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
```

## 发布前收口：R8、mapping.txt 与数据可发现性冒烟

### 为什么这件事需要一个脚本守着

release 走 R8（`isMinifyEnabled = true` + `isShrinkResources = true`）。本项目的落盘格式是明文 JSON，
读写靠 kotlinx.serialization 的 Java 反射，而**名字稳定**是它的正确性条件之一：枚举常量名
（含 `states` map 的 key）、生成的 `$$serializer`、`Companion` 上的 `serializer()`。
R8 动它们不会崩、不会报错——`WordLensJson` 配了 `coerceInputValues = true`，读不出来的枚举名会被
**夹成默认值**。JVM 单测跑的是没混淆的 class，UI 测试跑的是 debug 包，两者都抓不到这一类回归。

补规则之前，`assembleRelease` 的 mapping.txt 里实际是这样（原样摘录）：

```
com.ilyskyo.wordlens.data.model.StudyDirection -> af3:
    com.ilyskyo.wordlens.data.model.StudyDirection RECOGNIZE -> T
com.ilyskyo.wordlens.data.model.RatingPalette -> mq2:
    com.ilyskyo.wordlens.data.model.RatingPalette WARM -> T
```

`RECOGNIZE -> T` 说的是**字段名**被改走，而且只经 `$VALUES` 用到的常量字段会被整个删掉
（改动前的 mapping.txt 里 `CardOrigin` 只剩一条 `STICKER -> U` 字段）。但实测结论要写清楚，
别照网上的说法抄：用 `dexdump` 反汇编 release 包的 `classes.dex`，那个被改名成 `r30` 的枚举里
`<clinit>` 传给 `Enum.<init>` 的 name 字符串**并没有被重写**——

```
r30.<clinit>:()V
  0002: const-string v1, "STICKER"
  000c: const-string v2, "SCENE"
  0014: const-string v3, "TEXT"
  001a: filled-new-array {v0, v1, v2}, [Lr30;   // $VALUES 三个对象都还在
```

也就是说 `.name` 在运行时仍然是 `"STICKER"`，**那一版产物读得回来，没有正在发生的数据损坏**。

那还补规则做什么：因为「读得回来」这件事现在靠的是 R8 当前版本（9.3.16）的实现细节，而不是我们
写下的契约。名字由规则钉死之后，升级 R8、打开更多 `-optimizations`、或者哪天代码里不再直接引用
某个常量，落盘的名字都不会跟着漂。反射建枚举序列化器要用的 `values()` / `valueOf(String)` 也一起
钉住——那两个被裁掉才是当场就坏的事，而 kotlinx 查不到序列化器时不抛异常，只当默认值处理。

规则的体积代价实测（同一棵树、只换 proguard 文件，`assembleRelease` 各跑一次）：
`classes.dex` 未压缩 **+1,592 B**、压缩后 **+1,850 B**，APK 总字节数没变（49,467,334）。
所以「keep 加多了会让包白胖」这个担心，在这个量级上不成立；真正该省的是那些本项目用不到的
模板条目（`<fields>` 加在 data class 上、`kotlinx.serialization.json.**` 的 Companion 之类）。

### 怎么跑

```bash
./gradlew assembleRelease
python3 tools/release_smoke.py
```

只用标准库，不需要 `dexdump` / `apkanalyzer`。退出码：

| 码 | 含义 |
|---|---|
| 0 | 落盘要用的名字都在 |
| 1 | 有名字被改名或裁掉——修 `app/proguard-rules.pro`，**不要修断言** |
| 2 | 没有 mapping.txt（或解析结果为空）。**不会静默放行**：这一步没跑就等于没验 |

脚本核对的是四件事：七个落盘枚举的**每一个常量名** + `values()`/`valueOf()`、清单里 15 个
`$$serializer` 类（产物里实测 16 个，多出来的那个是 `srs.Fsrs$State`）、`Companion` 字段、
以及 APK 里 `classes.dex` 中的 JSON 元素名与长枚举名。
最后那一项是佐证：短名字（`log`、`mood`、`AI` 这类）在 dex 字符串池里可能偶然命中，
脚本只把它标成「弱证据」，不作为通过的依据；反过来**缺失一定是真缺失**。

CI 里挂在 `assembleRelease` 之后、体积报告与 `upload-artifact` 之前——格式不稳定时不该把包传上去。

### mapping.txt 怎么读

产物目录 `app/build/outputs/mapping/release/`：

| 文件 | 是什么 | 什么时候看 |
|---|---|---|
| `mapping.txt` | `原名 -> 改名` 的全表 | 想知道某个类/成员保没保住 |
| `seeds.txt` | 被 `-keep` 规则**真正钉住**的条目 | 规则写了却没生效时，先看这里有没有它 |
| `usage.txt` | 被判定无用而删掉的类与成员 | 「这个类怎么不见了」 |
| `configuration.txt` | 合并后的最终 R8 配置（含 AGP 自带的模板） | 怀疑某条规则被上游覆盖或写错 |

读法三条：

1. 不缩进的行是类，`Foo -> Foo:` 这种**箭头两边相同**才叫「名字保住了」；`Foo -> a:` 是被改名；
   `Foo -> R8$$REMOVED$$CLASS$$123:` 是整个类被删。
2. 缩进行是成员/方法，前面可能带行号区间与 `# {"id":"com.android.tools.r8.residualsignature"...}`
   注释——注释里的签名改动不影响名字稳定性，别被它吓到。
3. 想知道某条规则命中了什么，就查 `seeds.txt`。这一步很关键：本项目就遇到过
   `-keepclassmembers` 写法在枚举上不生效（类和 `values()` 都被钉住了，常量字段却没有），
   光看 mapping.txt 会误判成「规则没写」，看 seeds.txt 才定位到是**写法**问题
   （结论写在 `app/proguard-rules.pro` 的注释里）。

## 真机验证清单

类型检查与单测能证明几何和调度是对的，证明不了「画面里的词片确实压在杯子上」。
发布前逐个打勾，每条都写清了**看什么**，不写「正常即可」：

1. **数据往返（R8 的最后一公里）**：装 release 包 → 拍一张、存词、退出进程 → 重进 →
   `adb shell run-as com.ilyskyo.wordlens cat files/deck.json`。
   要看的是 `"origin": "STICKER"`、`"source": "ON_DEVICE"`、`"states": { "RECOGNIZE": {...} }`
   这三处名字**是不是原样**——出现 `"U"`、`"T"` 这种单字母就是 `tools/release_smoke.py` 该拦住的事故。
2. **覆盖安装不丢数据**：先装 debug 包造数据，再覆盖装 release 包（applicationId 不同，
   要真验就都用 release 的前后两个构建）。复习状态、心情标签、来源标记都要还在。
3. **抠图边缘**：点词片 → 推近 → 抠图，贴纸边缘干净、没有沿台阶起伏的白描边
   （`magic_touch` 的 seed point 走传感器归一化坐标，`AlphaMatte` 的双线性放大是否真的跑到贴纸上）。
4. **旋转**：横竖屏与不同 `rotationDegrees` 下，回看页把词长回原图的位置是否还准；
   详情页照片不再躺倒（`PhotoDecoder` 读 EXIF，`BitmapFactory` 自己是不读的）。
5. **时间轴滚动**：几十张照片连续滚动不 OOM（`DecodeSizing` 的两步降采样 + `ByteLruCache` 的字节上限）。
   这一条只能在真机上验，JVM 里没有位图。
6. **国产 ROM 首帧黑屏**：`PreviewView` 已按 `ImplementationMode.COMPATIBLE` 处理，要找具体机器确认。
7. **触觉与动效**：评级、归档、完成结算三处的触觉分级是否出得来（没有 `Vibrator` 的机器要静默降级，
   不许崩）；按压只有缩放 + 透明度，屏幕上**找不到任何波纹**。
8. **设置页生效**：拖保持率 → 复习页四个按钮预告的间隔要跟着变；换评级色系 → 四档按钮的色相要变。
9. **深色模式小组件**：读数颜色现在还是硬编码一组（`待做` 里那条），看一眼错成什么样，别当成 bug 重复记。
10. **备份白名单**：`adb backup` 或 Google One 备份完成后，检查备份内容只有 `deck.json` / `diary.json`，
    没有 `filesDir/photos`、`filesDir/entries`、也没有 DataStore（里面有用户自己填的 key）。


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
