package com.fieldcapture

import android.graphics.Bitmap

object Frames {
  fun i420(b: ByteArray, w: Int, h: Int): Bitmap? {
    val ySize = w * h
    val cw = (w + 1) / 2
    val cSize = cw * ((h + 1) / 2)
    if (b.size < ySize + 2 * cSize) return null
    val px = IntArray(ySize)
    for (r in 0 until h) {
      val cr = (r / 2) * cw
      for (c in 0 until w) {
        val y = (b[r * w + c].toInt() and 0xFF) - 16
        val u = (b[ySize + cr + c / 2].toInt() and 0xFF) - 128
        val v = (b[ySize + cSize + cr + c / 2].toInt() and 0xFF) - 128
        val yy = 1192 * maxOf(y, 0)
        val red = ((yy + 1634 * v) shr 10).coerceIn(0, 255)
        val green = ((yy - 833 * v - 400 * u) shr 10).coerceIn(0, 255)
        val blue = ((yy + 2066 * u) shr 10).coerceIn(0, 255)
        px[r * w + c] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
      }
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
  }
}
