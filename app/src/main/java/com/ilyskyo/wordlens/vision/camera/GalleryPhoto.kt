// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import android.content.ContentResolver
import android.media.ExifInterface
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 相册里选中的那张照片：**整份拷进应用私有目录**，并带回两个时刻线索。
 *
 * ## 为什么非要拷一份，而不是直接读 content URI
 *
 * `PickVisualMedia` 给的 URI 是一次性的读授权：进程活多久它有效多久，重启之后就是一串
 * 没有意义的字符。而这本日记要能在三年后还翻得出那一页——`Entry.photoPath` 必须是
 * 一个**我们自己持有的文件**，命名与拍照那条路完全一致（`$entryId.jpg`，相对
 * `filesDir/entries`），删除级联才不需要为导入单独开一条逻辑。
 *
 * ## 为什么这个边界要校验、别处不用
 *
 * URI 是这台机器上唯一由外部提供的输入：Provider 可以给 0 字节、可以中途失效、可以返回
 * 一列单位不明的时间。所以这一层的每个分支都真的在挡一种现实存在的坏情况。
 * 拷进来之后的一切都是我们自己写的文件，后面不再做防御性检查。
 *
 * ## 为什么没有任何权限
 *
 * 系统照片选择器本身就是隐私闸门：用户挑哪张、才授权哪张。申请
 * `READ_MEDIA_IMAGES` 会让整个相册进 App 的可达范围，而这个产品的立场是
 * 「没有明确的一次动作，什么都不能进来、也不能出去」。
 */
object GalleryPhoto {

    /** 拷进私有目录的照片，与它在相册里的时刻线索。 */
    data class Imported(
        val file: File,
        /** 相册里那一项的修改时刻（毫秒）；0 表示拿不到，交给 [PhotoTiming] 往下兜底。 */
        val lastModifiedMs: Long,
    )

    /**
     * @param target 必须由调用方按 `$entryId.jpg` 在 `filesDir/entries` 下取好——
     *   文件名在这里定了，Entry 与词卡引用的就都是同一个键。
     * @return null 表示这张照片没拿到（授权失效、Provider 读不出、内容是空的）。
     *   失败时不留半张照片：任何一条失败路径都会删掉目标文件。
     */
    fun import(resolver: ContentResolver, uri: Uri, target: File): Imported? {
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output ->
                    // 流式拷贝而不是 readBytes()：一张 48MP 的原图或一张无损 PNG 能到几十 MB，
                    // 整份进堆就是在这次导入里凭空造一次 OOM 风险。
                    // 32KB 是单趟系统调用的量级：再小是白多的 syscall，再大只是把同一份内存搬两次。
                    input.copyTo(output, COPY_BUFFER_BYTES)
                }
                // copyTo 返回的是字节数，这里要的是一个「读到了东西」的判断：
                // 把 Long 直接留在 use 的返回值上，下面那句 `?: false` 会把类型推成
                // `Comparable & Serializable`，然后整段以「不是 Boolean」失败。
                true
            } ?: false
        }.onFailure { Log.w(TAG, "picked photo cannot be read: $uri", it) }.getOrDefault(false)

        if (!copied || target.length() == 0L) {
            // 空的 content 是真实存在的：个别云相册 Provider 在未同步的项上给 0 字节。
            // 这种照片既解不出也裁不出，留在私有目录里只是一枚没人引用的孤儿。
            runCatching { target.delete() }
            if (copied) Log.w(TAG, "picked photo is empty: $uri")
            return null
        }
        return Imported(target, lastModifiedOf(resolver, uri))
    }

    /**
     * EXIF 的 `DateTimeOriginal`（相机写下的那一刻）。
     *
     * 用框架自带的 `android.media.ExifInterface` 而不是 `androidx.exifinterface`：
     * 照片此刻已经在私有目录里、是一个真实文件，而 JPEG 是框架这个类支持的格式；
     * AndroidX 那份的增值在于支持更多格式与可回写，这个功能用不上，不值得为它加一条依赖。
     * PNG / WebP 通常根本没有 EXIF，构造就抛 IOException —— 夹住返回 null，
     * [PhotoTiming.takenAtOf] 会落到修改时间那一档。
     */
    fun dateTimeOriginal(file: File): String? = runCatching {
        ExifInterface(file.path).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
    }.getOrNull()

    /**
     * 相册里那一项的修改时刻。
     *
     * 两列都试：MediaStore 的 `DATE_MODIFIED` 与 DocumentsProvider 的
     * `COLUMN_LAST_MODIFIED`，单位不同（秒 / 毫秒），由 [PhotoTiming.toMillis] 归一。
     */
    private fun lastModifiedOf(resolver: ContentResolver, uri: Uri): Long {
        val raw = queryLong(resolver, uri, MediaStore.MediaColumns.DATE_MODIFIED)
            ?: queryLong(resolver, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ?: 0L
        return PhotoTiming.toMillis(raw)
    }

    /**
     * 单列查询。
     *
     * 用 `getColumnIndexOrThrow` 而不是 `getColumnIndex`：后者在新 SDK 上已标废弃，
     * 而「这一列不存在」本来就是要走的路径——整段夹在 runCatching 里，抛出来就是 null。
     */
    private fun queryLong(resolver: ContentResolver, uri: Uri, column: String): Long? = runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndexOrThrow(column)
            if (cursor.isNull(index)) null else cursor.getLong(index)
        }
    }.onFailure { Log.d(TAG, "no $column for $uri") }.getOrNull()

    private const val TAG = "GalleryPhoto"

    /** 拷贝缓冲：`DEFAULT_BUFFER_SIZE`(8KB) 的四倍，见 [import]。 */
    private const val COPY_BUFFER_BYTES = 4 * 8 * 1024
}
