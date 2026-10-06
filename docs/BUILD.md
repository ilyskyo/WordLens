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
./gradlew testDebugUnitTest      # 280 个 JVM 单测，毫秒级
./gradlew assembleRelease        # R8 + shrinkResources + lintVitalRelease
```

### 模拟器能看什么、不能看什么

本机 AVD 叫 `wl`（`system-images;android-34;google_apis;x86_64`，WHPX 加速）。
debug 变体额外打一份 `x86_64` 原生库（`app/build.gradle.kts` 的 `debug.ndk`），release 仍然
arm64-only——不这么做就得让 MediaPipe 跑在 ARM→x86 转译上，实测一次安装能卡十分钟。

它**值得**用来做的：静态布局与配色——页签胶囊盖住内容、拍照键没有底盘、图标被 tint 抹成一团，
这三处都是在这里第一次被看见的，而类型检查、单测与 lint 对它们全都没有意见。
`adb -s emulator-5554 shell input tap x y` 在这里也能用（真机上要 MIUI 的
「USB 调试（安全设置）」才允许注入）。

它**不能**代替真机的：相机预览（软件渲染下是黑的，于是整条检测/抠图链路无从判断）、
触摸振动的有无与强弱、厂商 ROM 的首帧与后台策略、TTS 引擎差异、真实镜头的 EXIF 与
`rotationDegrees`。另外它的 CPU 压力常年偏高，`/proc/pressure/cpu some` 到 35 时会把
设置页这类长组合甩成一次 ANR——**看到 ANR 先怀疑模拟器，别急着当成 App 的缺陷**，
本次抓到的两处主线程栈都停在正常的组合代码上，没有 IO、没有循环。

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
   记录**多于 40 条**时要单独验一遍：列表末尾必须出现「更早的还有 N 条 · 载入」，按下去多出下一批
   而**不重复、不跳过**（上限是往上加而不是按偏移翻页，为的就是删一条/导一批之后仍然对得上）；
   滚到第 80 条之后再回头筛某一天、然后取消筛选，列表应该还是完整的而不缺一段。
   N 这个数字要跟着减少——删掉一条之后按一次载入，句里的条数也该跟着变。
   这一条只能在真机上验，JVM 里没有位图。
6. **国产 ROM 首帧黑屏**：`PreviewView` 已按 `ImplementationMode.COMPATIBLE` 处理，要找具体机器确认。
7. **触觉与动效**：评级、归档、完成结算三处的触觉分级是否出得来（没有 `Vibrator` 的机器要静默降级，
   不许崩）；按压只有缩放 + 透明度，屏幕上**找不到任何波纹**；
   系统「设置 → 声音 → 触摸时振动」关掉之后，本 App 的按钮**不该再震**——这一族效果全部直接调
   `Vibrator`，绕过了 `View.performHapticFeedback` 那个会自动核对开关的路径，所以只能自己问；
   中途改设置要立刻生效，不需要重进 App。
8. **设置页生效**：拖保持率 → 复习页四个按钮预告的间隔要跟着变；换评级色系 → 四档按钮的色相要变。
   「我的词条」那两条也要走通：补一个 App 认不出的词（词 + 意思）→ 回搜索页或取景页手输同一个词，
   **不该再撞**「词典里还没有它，暂时不能进复习队列」；删掉它 → 那句重新出现。
   另外把 `filesDir/lexicon/user-en.json` 手动改坏（多一个逗号）→ 设置里要出现那句红字说明是哪一份，
   而不是安静地退回内置词典（那一份文件是用户自己能打开改的文本，报不出原因就等于没报错）。
9. **深色模式的两处 XML 资源**：小组件与冷启动。night 资源已经补上
   （`values-night/colors.xml` 的 `wl_background` / `widget_*`，`values-night/themes.xml` 覆盖
   `Theme.WordLens` 的父主题与 `windowLightStatusBar`），要看的是两件事：
   把系统切到深色后，小组件的底/标签/读数是否一起变暗（对比度实测 9.55:1 与 7.11:1），
   以及**杀掉进程再打开**时还有没有那一下奶油白的闪——那是 starting window 画的，
   Compose 的第一帧管不到它。
10. **备份白名单**：`adb backup` 或 Google One 备份完成后，检查备份内容只有 `deck.json` / `diary.json`，
    没有 `filesDir/photos`、`filesDir/entries`、也没有 DataStore（里面有用户自己填的 key）。
11. **日历筛选**：换到周一/周日两种起点（系统地区设置改一下就行），月历的第一列要跟着换，
    月初不能整行错位；筛到某一天之后把那天删空，空状态里必须还能退出筛选。
    在当月那个月按「下一月」应该是**灰的**：未来不可能有记录，能翻进去只会得到一整张
    一个格子都点不开的月历，而它和「这个月你还没记」在画面上完全同一个样子。
12. **「移除动画」开关**：系统设置里打开之后，呼吸提示要停在不透明 0.80、结算页四行同时到位、
    提示条只剩淡入淡出；**不该停的是**翻卡、跟手滑动的位移、进度条增长与滚动数字、
    快门不可用时的缩放——那几样是信息不是装饰，跟着停就等于功能少了。
    开关拨回来要立刻生效（`ContentObserver` 接的），不需要杀进程。
13. **相册导入走完整链路**：挑一张几个月前的原图（12MP 或更大），看四件事——
    记录落在**它拍下的那天**而不是今天（EXIF `DateTimeOriginal`；相机时钟没设对的那张会落到
    修改时间那一档，那也是对的）；时间轴上的卡片**有贴纸那一个角**，和拍照进来的形态一致；
    连续导三四张大图不 OOM（只解一次 2560，氛围词另取 160 边的小图）；
    云相册里那张还没同步下来的项（Provider 给 0 字节）要说一句「读不出这张照片」，
    而不是静默什么都不发生、也不是留下一条只有坏照片的记录。
    竖持拍的横向 EXIF 那张要重点看：词长回画面的位置准不准、贴纸裁的是不是同一个物体。
14. **语音附件的三条离开路径**：录一段 30 秒 → 播放条放得出声、拖动能跳到想听的位置（松手那一下
    **不许弹回原位**——`MediaPlayer` 的 seek 是异步的，回读会拿到旧值）。然后逐条试打断：
    录到一半**接一个电话**，要说的是「麦克风正被通话占着，等它空下来再录」，而不是笼统一句失败；
    录到一半按 Home 退到后台，回来时那一段要停在「收下 / 丢弃」上等他决定，不是凭空没了、
    也不是替他收下了；**正在录的时候转一次屏幕**，录音不该被停掉（VM 活着，界面死了不算离开）。
    录满 90 秒要自己收手并说一句。**按下之后立刻按返回**这一条要单独试：开录是异步的，
    如果在那几十毫秒里人已经离开这一条，麦克风要当场交还、`files/audio` 里不该留下那半截文件——
    留下的一段偷偷在写的人声，比少录一句严重得多。
15. **语音附件留下的文件（隐私，比体积要紧）**：删一条日记 → `adb shell run-as … ls files/audio`
    里那一份一并没了，**包括还没收下就离开的那段**；详情页「删除录音」之后磁盘上确无残留
    （只清 `audioPath` 字段是一种假装）；录一段然后丢弃 → 当场删掉，不该等到下次启动；
    冷启动之后 `files/audio` 里不该有任何不属于现存条目的 .m4a。
    顺带核对备份边界：`adb backup` 或系统云备份之后**不该**在人声目录里看到东西
    （`data_extraction_rules.xml` 是 `<include>` 白名单，只列了 deck/diary 两个 JSON，
    所以 `files/audio` 与 `files/entries` 按构造就不上云——而 diary.json 里只有文件名）。
16. **麦克风权限的三种答案**：第一次按下「录一段」要弹系统框；**答「允许」之后那一按要直接开录**
    （不该出现「权限刚给完、键还是没反应、得再按一次」）；拒绝一次之后再按要**还能再弹**；
    勾了「不再询问」之后再按，按钮要变成「去设置」并真的落到应用详情页，
    而不是每次都发一个系统不会再答的 request。永久拒绝的判别只能在用户答过之后做
    （第一次进这一页时 `shouldShowRequestPermissionRationale` 也是 false）。
    没有麦克风的机器（平板）要能装上、其余功能照常：`microphone` 声明的是 `required=false`。
17. **重录必须问一次**：已经有声音的那条按「重录」，要先出现「会换掉原来那段」的确认，
    确认之后才动旧的——文件名按条目 id 定死，新的那一段是**截断重写**，旧的没掉之前必须有过同意。
    顺序也要紧：确认之后权限没到手时不能先摘 `audioPath`，否则「点了同意 → 被拒 → 旧的没了 →
    新的没录上」这一串就成立了。
18. **搜索 / 添加页**（手写词那条路的兑现处，之前清单里一直没有它）：
    **连点两下「收下」**只进一张卡（判重查的是牌组，而写入要等一次挂起之后才回来——只看牌组挡不住）；
    **收下之后再删掉那张卡**，回搜索页同一个词必须又能收（按钮不该灰着写「已收」——那是个说谎的读数）；
    全新装机第一次点进来不能是一片空白，要有话说清楚这页同时找牌组、词典和日记；
    从别的 App 分享一段多行文字进来，取的是第一行且掐到 80 字，纯空白的那次**不打开**页面；
    牌组多于 30 张时空输入只显示「最近」，得知道那不是全部。
    **取景页的「这个词我还认不出来」面板也算这一条**：写下词典里没有的词 + 一句意思 →
    要出现「『X』收下了，也已经记进你的词典」，随即能在搜索页与复习队列里看到它；
    再拍一次同一个物体，那个词该认识（`nomatch_body` 那句承诺的全部兑现处）。
    取景页那颗「收下」**也要连点两下**：只进一张卡（`toCard` 每次发新 id，牌组按 id 判重挡不住
    自己造出来的第二个 id；而这一支有失败路径，不能靠清空输入框来防连点）。
    只写词不写意思时不该静默造一张空释义卡，而该说出去并指到设置里那一节。
19. **复习结算的三个读数**：第一张卡故意想满 20 秒再翻开、评完，结算页的「用时」要**含得到**那 20 秒
    （原来它从第一次评级起算，等于每轮都把最长的一张剪掉）；换素材（词 ↔ 事件）之后计数与用时必须归零。
    **中途把系统时间往前调一小时**（设置里关掉自动校时），用时不该跳、不该变负——那一轮的答题时长
    还会写进复习日志给 FSRS 当原料，坏掉的数当场不报错，只在几个月后的间隔里露出来。
    **故意把一张卡连错两次**（第一次评「忘了」，等一分钟它重新排进来，第二次评「好」）：
    「已完成」只能加一次（那张卡是一张，不是两张），进度条**不该往回退一格**，
    而「一次答对率」要把这张算成 0/1（它第一次没想起来）而不是 1/2。
20. **时间轴多选**：长按卡片进入多选之后，单击是勾选而不是打开——两套语义不能同时生效；
    筛到某一天再按「全选」，选中的应该只有**那一天加载出来的那些**（顶部读数要等于屏幕上的张数，
    而不是整本日记）。**走得通的那一条**：进入多选 → 长按顶部页签随机漫步进另一条 →
    在详情页把它删掉 → 返回时间轴，「已选 N 项」里不该还指着那张已经不存在的卡片
    （原来选中集合从不跟随删除清理，留下的是一个指着空气的读数加一颗按下去什么文件都不少的删除键）。
    删除之后的确认框要点「删除」而不是取消，并核对 `filesDir/entries` 与 `files/audio` 里那几份一并没了。
    **开读屏走一遍多选**：选中那张卡片要被念成「已选中」（此前只有一层紫色遮罩和一个灰色的勾选框，
    而遮罩读屏看不见、勾选框是 disabled 的——滚过一张张卡片时用户唯一能抓到的只有顶部那句「已选 N 项」，
    而那句话说的是数量，不是**哪几张**）。
22. **详情页的长按面板与编辑**：长按大图 → 复制 / 编辑 / 删除三项各自走通；「复制这段文字」贴到
    备忘录里应该只有标题、那句话说过的话和画面上的词，**不能有 id 或文件路径**。
    编辑里改标题、改一句话、选心情 → 保存之后页面当场是新的那句；原来摘要顶着「模型整理过，请核对」
    的那一条，用户重写过之后那句话必须**消失**（§8.1 要求的是「机器生成的东西能被认出来」，
    反向误标同样是破坏）。选中的那颗心情再点一次要能取消。
    **取消之后再打开，框里必须是条目本来的样子**：打字 → 取消 → 再长按 → 编辑（原来会把那份
    已经作废的草稿原样填回来，用户看不见差别，按下保存就等于落库一句他明确取消过的话）。
    **打字打到一半转一次屏**：对话框要还开着、字要还在（原来转屏直接把对话框关掉，
    字留在一个已经关上的门上，而注释承诺的是不丢）。
    删除必须过确认框，确认之后自动离开详情页——留在一条已经不存在的记录上是看着一片空。
23. **连点两下不是两条记录**（只能在真机上验）：结果页按「保存」之后**立刻再按一下**，
    时间轴只该多一条记录，`adb shell run-as … cat files/diary.json` 里那个条目 id 只出现一次；
    详情页「记下」按两下，复习队列里只有一句。提交是挂起的（JPEG 落盘 + 两份 JSON 各一次原子写），
    窗口只有几毫秒到几百毫秒，**取决于那张照片落地要多久**——JVM 里磁盘从来不慢，所以这条一直在
    测试覆盖之外。碰到导一张 12MB 的原图时最容易试出来。
    重复的条目 id 过去不是「多一条一样的」而是**一次闪退**：时间轴拿条目 id 当 LazyColumn 的 key，
    重复 key 直接抛 `IllegalArgumentException`。
21. **没人引用的照片（冷启动那一扫）**：拍一张 → 按左上角关闭退出取景页（不保存、也不按重拍）→
    `adb shell run-as com.ilyskyo.wordlens.debug ls -l files/entries`，那一张此刻**还在**——它要老满
    一小时才会被扫掉，而那道门槛是为了不删掉正在导入的那一张。一小时之后杀进程重进再看一次：
    那个文件该没了，而现存条目的照片、以及「词卡还在引用的那张贴纸」都必须还在
    （保留集是跨 `diary.json` 与 `deck.json` 两份算的，只盯一份就会删掉别人正在用的东西）。
    反向也要成立：冷启动时把一张照片**分享给见词**（导入那几十秒里文件先落盘、引用还没写），
    期间不该有任何文件被扫掉。最后一条最要紧：把 `diary.json` 改成一个坏 JSON 再启动
    （`.corrupt-*` 留证 + 从空文档起步），现存照片与录音**一张都不该少**——
    读成空文档的那一次，不允许拿它去判断「没人引用」。


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
