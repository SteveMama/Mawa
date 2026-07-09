package com.mawa.face.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

fun ImageProxy.toBitmap(): Bitmap {
    val nv21 = yuv420888ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val stream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, stream)
    val bytes = stream.toByteArray()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val uvSize = width * height / 2
    val out = ByteArray(ySize + uvSize)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    var outputIndex = 0
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    for (row in 0 until height) {
        val rowOffset = row * yRowStride
        for (col in 0 until width) {
            out[outputIndex++] = yBuffer.get(rowOffset + col * yPixelStride)
        }
    }

    val chromaHeight = height / 2
    val chromaWidth = width / 2
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride
    for (row in 0 until chromaHeight) {
        val uRowOffset = row * uRowStride
        val vRowOffset = row * vRowStride
        for (col in 0 until chromaWidth) {
            out[outputIndex++] = vBuffer.get(vRowOffset + col * vPixelStride)
            out[outputIndex++] = uBuffer.get(uRowOffset + col * uPixelStride)
        }
    }

    return out
}
