// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Log
import java.io.File

/**
 * 把磁盘上的 JPEG 解成**正着看**、且**长边正好是目标**的位图。
 *
 * ## 为什么必须单独成文
 *
 * `BitmapFactory` 完全不读 EXIF 方向。CameraX 竖持拍摄时写出的 JPEG 像素网格仍是传感器的横向，
 * 方向只存在 EXIF 里。于是「直接 decodeFile」得到的图是躺倒的，而界面上没有任何异常——
 * 只有照片是歪的。时间轴和详情页都必须先过这里。
 *
 * ## 为什么要两步降采样
 *
 * `inSampleSize` 只有 2 的幂才既快又准，因此单靠它解码出的长边最多能顶到目标的**近 2 倍**，
 * 按面积算是近 4 倍内存。算术本身抽成了纯函数 [DecodeSizing]（可 JVM 测），这里只负责把它
 * 的结果落到一次真实的解码上：缩放与旋转合并进同一个 `Matrix`，只分配一张位图。
 *
 * ## 为什么分开 catch OOM 与一般异常
 *
 * 原来的写法是一句 `runCatching`，它捕获的是 `Throwable`，会把 `OutOfMemoryError` 一起吞掉，
 * 于是「时间轴滚到一半照片开始静默空白」这种现场什么都留不下。现在两条路径各自记日志，
 * 并且**带上文件名、源尺寸与采样率**——那正是判断是文件坏了还是内存不够所需要的三个数。
 */
object PhotoDecoder {

    private const val TAG = "PhotoDecoder"

    /**
     * @param maxPx 长边上限。返回的位图长边**恰好**是它（源图本来更小则原样）。
     * @return 转正后的位图，以及它当初是按哪个角度转正的——调用方把标注坐标映射到这张图时要用。
     */
    fun decodeUpright(file: File, maxPx: Int): UprightImage? {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 只读边界就失败：文件坏了或不可读。这时候没必要再解第二次。
            Log.w(TAG, "cannot read bounds: ${file.name} (${bounds.outWidth}x${bounds.outHeight})")
            return null
        }

        val sample = DecodeSizing.inSampleSizeFor(bounds.outWidth, bounds.outHeight, maxPx)
        val raw = decodeScaled(file, sample, bounds.outWidth, bounds.outHeight) ?: return null

        val degrees = exifDegrees(file)
        val (targetWidth, targetHeight) = DecodeSizing.scaledSize(raw.width, raw.height, maxPx)
        val needsScale = raw.width != targetWidth || raw.height != targetHeight
        if (degrees == 0 && !needsScale) return UprightImage(raw, degrees)

        // 先缩放再旋转：矩阵是从「原始像素空间」往目的地方向组合的。
        // 反过来先旋转会让缩放比例落在已被交换过的宽高上，90°/270° 时得到一张拉伸的图。
        val matrix = Matrix().apply {
            if (needsScale) {
                postScale(targetWidth.toFloat() / raw.width, targetHeight.toFloat() / raw.height)
            }
            if (degrees != 0) postRotate(degrees.toFloat())
        }
        val transformed = runCatching {
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        }.onFailure { error ->
            Log.w(TAG, "transform failed for ${file.name}: ${error.message}")
        }.getOrNull()

        // 这里的 recycle 是安全的：raw 从来没被交给任何人。
        // （位图缓存那边规则相反——见 ByteLruCache 的文件注释。）
        if (transformed === null) {
            // 变换失败也要有图可用：退回已解码但没转好的那张，比让照片整张消失好。
            return UprightImage(raw, degrees)
        }
        if (transformed !== raw) raw.recycle()
        return UprightImage(transformed, degrees)
    }

    private fun decodeScaled(
        file: File,
        sample: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Bitmap? {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            BitmapFactory.decodeFile(file.path, options)
        } catch (error: OutOfMemoryError) {
            Log.e(
                TAG,
                "OOM decoding ${file.name}: ${sourceWidth}x$sourceHeight, inSampleSize=$sample" +
                    " (≈${sourceWidth / sample}x${sourceHeight / sample} requested)",
                error,
            )
            null
        } catch (error: Exception) {
            Log.w(TAG, "decode failed for ${file.name}, inSampleSize=$sample", error)
            null
        }
    }

    /** EXIF 方向 → 需要顺时针转过的角度。镜像方向（翻转拍摄）这里按不处理，角度照给。 */
    fun exifDegrees(file: File): Int = runCatching {
        when (
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** 转正后的位图 + 它被转过的角度。 */
    data class UprightImage(val bitmap: Bitmap, val exifDegrees: Int)
}
