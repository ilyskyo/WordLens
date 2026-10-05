# Copyright (c) 2026 ilyskyo
# SPDX-License-Identifier: MIT

# WordLens 的用户数据是 filesDir 下的明文 JSON（deck.json / diary.json，说明书 §8.6），
# 由 kotlinx.serialization 通过 Java 反射读写。落盘格式里有三类东西依赖**名字稳定**：
# 枚举名（含 Map 的 key）、生成的序列化器、以及嵌套泛型的签名。
# R8 动它们不会崩、不会报错：WordLensJson 配了 coerceInputValues = true，读不出来的名字会被
# 静默夹成默认值——磁盘上的记录还在，界面上变成「没复习过 / 没来源」，日志一行都没有。
# 本项目当前这一版 R8（9.3.16）实测还没踩中这个坑（原因写在第 3 节），所以这个文件买的不是
# 「修一个正在坏的 bug」，而是「把落盘格式从 R8 的实现细节变成我们写下的契约」。
#
# 所以这个文件里每一条规则都对应一个具体的落盘依赖，通用模板里对不上的条目一律不抄
# （多余的一条就是 APK 上白添的重量）。这份清单由 tools/release_smoke.py 在 CI 上
# 对着 app/build/outputs/mapping/release/mapping.txt 逐条核对，规则失效当场就红。

# ── 1. 反射与泛型元数据 ────────────────────────────────────────────────────────
# 改动前这里只有 `*Annotation*, InnerClasses`，缺的正是 Signature 与 EnclosingMethod。
# 注意 `*Annotation*` 是通配，已经包含 RuntimeVisibleAnnotations、
# RuntimeVisibleParameterAnnotations 与 AnnotationDefault，再显式写一遍
# RuntimeVisibleAnnotations 不改变任何行为，只会让下一个读规则的人怀疑这里有过事故。
#
# Signature：DeckDocument.cards 是 List<WordCard>、WordCard.states 是 Map<String, FsrsState>，
# 这类嵌套泛型的**元素类型**只存在于这个属性里。当前代码每一处都显式传 `X.serializer()`
# （已 grep 确认：没有 reified、没有 typeOf、没有 serializersModule），所以今天还不是致命项；
# 但 `Json.decodeFromString<DeckDocument>(text)` 是这行代码最容易「顺手改短」的写法，
# 一旦有人改过去而 Signature 被裁掉，`List<WordCard>` 就退化成 LinkedHashMap 列表，
# serializer 找不到元素类型——同样是静默夹成空列表，而不是抛异常。几十 KB 属性表换一条
# 不会静默失败的路径，划算。
# EnclosingMethod：$$serializer 与 $Companion 都是嵌套类，反射取 companionObject / 还原嵌套关系
# 时要靠它。本项目没有直接走这条路（已 grep 确认），留着它的理由是「拿不准的时候别删元数据」：
# 它和 Signature 一样只占属性表，而缺了它们出问题时同样是静默而不是报错。
-keepattributes Signature, EnclosingMethod, InnerClasses, *Annotation*

# kotlinx.serialization 的注解本身对 R8 是「未知类型」，逐条报 note 会把日志淹掉。
-dontnote kotlinx.serialization.**

# ── 2. kotlinx.serialization 的生成物 ─────────────────────────────────────────
# 只钉住带 @Serializable 的类的 Companion 字段与 serializer()。
# 刻意**不加 allowobfuscation**（官方模板里有这个修饰符）：这条路径是反射查找，
# 而查找失败在 kotlinx 那边不抛异常。实测（改动前的 mapping.txt）：三个带 @Serializable
# 注解的枚举 EntrySource / CardOrigin / ReviewSource **都没有生成 $$serializer**，
# 序列化器是运行时反射建出来的，Companion 与 serializer() 就是它的两个入口。
# 把这两个名字改掉不会当场出问题，但等于把数据格式押在「库恰好不是按名字找」上——
# 而这条规则的成本只有十几个类的方法名，不值得赌。
# 也刻意**不加 `<fields>;`**：$$serializer 把元素名写成字符串常量（"headword"、"states"），
# 读写走构造器，不反射字段；保住字段名只会挡住 R8 的字段合并，白占体积。
# 同理不加 `-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }`
# （官方模板的另一条）：它服务的是 `Json.encodeToString(value)` 这种 reified 写法，
# 本项目的 JsonDocument / LexiconRepository / VisionRepository 全部显式传 serializer 实例，
# 那条规则在这里一行代码都用不到。
#
# 这里用 `-if @Serializable` 而不是原先那三条 `class com.ilyskyo.wordlens.**` 全项目通配：
# 后者会把每个 ViewModel、每个 repository、每个 Compose 文件类的 Companion 一起钉住，
# 而需要 Companion 稳定的只有带注解的那十几个类。
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# 生成的 $$serializer 整体存活。它的 `*;` 里包含元素名常量表、shouldEncodeElementDefault
# 的位图、以及 getDescriptor() 里那些 PluginGeneratedSerialDescriptor 的构造调用——
# 少掉任何一项都是「读成默认值」而不是「报错」。
# includedescriptorclasses 顺带把 descriptor 引用的类（WordCard、FsrsState……）拉进 keep 图，
# 这就是为什么改动前这些 data class 的名字也是原样留着的。
# 这里用 **$$serializer 而不是 com.ilyskyo.wordlens.**$$serializer：实测 16 个全在本包内，
# 通配的意义只是「以后换包、加模块照样保住」，本身零代价。
-keep,includedescriptorclasses class **$$serializer { *; }

# ── 3. 落盘的枚举名：最容易被误判、也最不该交给 R8 运气的一段 ─────────────────
# 写进 JSON / Preferences 的就是枚举**常量的名字**本身：
#   WordCard.origin（CardOrigin）· WordCard.source（EntrySource）·
#   Entry.mood（EntryMood）· Entry.summarySource / detectedBy（EntrySource）·
#   EntryObject.layer（OverlayLayer）· ReviewLog.direction（StudyDirection）·
#   EventCard.source（ReviewSource）· WordCard.states 与 EventCard.states 的 **key**
#   （代码是 `states[direction.name]`）· DataStore 的 rating_palette / direction
#   （RatingPalette.name、StudyDirection.name）。
# 机制（dexdump 实测，别照抄网上说法）：R8 会改掉枚举常量的**字段名**，也会把只经 $VALUES
# 用到的常量字段整个删掉（改动前的 mapping.txt 里 CardOrigin 只剩 `STICKER -> U` 一条字段），
# 但它**不会重写** `<clinit>` 里传给 `Enum.<init>` 的那份 name 字符串——
# `r30.<clinit>` 里仍然是 `const-string "STICKER"` / `"SCENE"` / `"TEXT"`，三个对象也都还在
# `$VALUES` 里。所以今天这一版产物读得回来，`.name` 还是 "STICKER"。
# 正因为如此，这条规则买到的不是「修好一个正在发生的损坏」，而是「不要把数据格式的正确性
# 寄托在 R8 当前版本的仁慈上」：名字由规则钉死，升级 R8、改 `-optimizations`、
# 或者哪天代码里不再直接引用某个常量，都不会让落盘的名字跟着漂。
# 顺带一个真会致命的点：反射建 EnumSerializer 靠的是 `values()` / `valueOf(String)`，
# 这两个方法一旦被裁掉，读回来就是默认值而不是报错。
#
# 所以保的是**成员**（常量字段名 + values()/valueOf()），类名照旧允许改——类名不落盘。
# 这里既不能用 allowobfuscation（那正是我们要禁止的动作），也不能用 allowshrinking。
# 写法是实测出来的：`-if class ... extends java.lang.Enum` + `-keepclassmembers class <1>` 这一种
# 会把 `values()` / `valueOf()` 钉进 seeds.txt，**但钉不住常量字段**（改名照旧）；
# `enum` 修饰符 + 类名通配才对整个包生效。别把两种写法混在一起，看着更严其实没严。
# 范围只圈 data.model.**：那是唯一会落盘的枚举所在的包，新增会落盘的枚举放进去就自动被覆盖。
# 特意**不**做全项目 keep 枚举——`srs.Fsrs.Rating` 落的是 Int，ShotKind / StudyMaterial /
# SceneGuess / LexiconIndex 这些不落盘，而库与 Compose 里的枚举有几十个，钉住它们换不来正确性，
# 只会让 APK 白胖。tools/release_smoke.py 里那份「必须存活的名单」是第二道闸：
# 规则写法哪天不再匹配，脚本会当场红，而不是留下一个读不回数据的 release。
-keepclassmembers enum com.ilyskyo.wordlens.data.model.** {
    <fields>;
    ***[] values();
    *** valueOf(java.lang.String);
}

# MediaPipe Tasks loads its graph/assets through native reflection.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# ML Kit entry points.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.internal.mlkit_vision_subject_segmentation.** { *; }
-dontwarn com.google.android.gms.internal.mlkit_vision_subject_segmentation.**
