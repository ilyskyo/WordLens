// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.wordlens.vision.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.max

/**
 * 把磁盘上的 JPEG 解成**正着看**的位图。
 *
 * 为什么要单独成文：`BitmapFactory` 完全不读 EXIF 方向。CameraX 在竖持拍摄时写出的 JPEG
 * 像素网格仍是传感器的横向，方向信息只存在 EXIF 里。于是「直接 decodeFile」得到的图是躺倒的，
 * 而界面上没有任何异常——只有照片是歪的。时间轴和详情页都必须先过这里。
 */
object PhotoDecoder {

    /**
     * @param maxPx 长边上限，按 2 的幂降采样（`inSampleSize` 只有 2 的幂才既快又准）。
     * @return 转正后的位图，以及**它当初是按哪个角度转正的**（调用方把标注坐标映射到这张图时要用）。
     */
    fun decodeUpright(file: File, maxPx: Int): UprightImage? {
        if (!file.isFile) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxPx * 2) sample *= 2
            val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val degrees = exifDegrees(file)
            val bitmap = if (degrees == 0) {
                raw
            } else {
                Bitmap.createBitmap(
                    raw, 0, 0, raw.width, raw.height,
                    Matrix().apply { postRotate(degrees.toFloat()) },
                    true,
                ).also { if (it !== raw) raw.recycle() }
            }
            UprightImage(bitmap, degrees)
        }.getOrNull()
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
