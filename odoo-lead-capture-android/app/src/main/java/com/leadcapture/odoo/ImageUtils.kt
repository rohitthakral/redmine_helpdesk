package com.leadcapture.odoo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

object ImageUtils {
    /**
     * Downscales a camera photo to at most [maxDim] px on its long side, applies the EXIF
     * rotation and writes it as JPEG. Full-resolution photos are often 5–10 MB; this keeps
     * the upload around a few hundred KB while text on the card stays readable.
     */
    fun shrinkJpeg(src: File, dst: File, maxDim: Int = 2000, quality: Int = 85) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Could not read the photo")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val decoded = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("Could not decode the photo")

        val matrix = Matrix()
        val scale = maxDim.toFloat() / maxOf(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        val rotation = when (ExifInterface(src.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation != 0f) matrix.postRotate(rotation)

        val out = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        dst.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (out !== decoded) out.recycle()
        decoded.recycle()
    }
}
