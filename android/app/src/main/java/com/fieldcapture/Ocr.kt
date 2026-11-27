package com.fieldcapture

import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

object Ocr {
  const val ENGINE = "mlkit-text-recognition-16.0.1"
  private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

  suspend fun read(bmp: Bitmap): JSONObject {
    val t = SystemClock.elapsedRealtime()
    val text = suspendCancellableCoroutine { cont ->
      client.process(InputImage.fromBitmap(bmp, 0))
          .addOnSuccessListener { cont.resume(it) }
          .addOnFailureListener { cont.resumeWithException(it) }
    }
    val lines = JSONArray()
    for (block in text.textBlocks) for (line in block.lines) {
      val b = line.boundingBox
      lines.put(JSONObject()
          .put("text", line.text)
          .put("conf", line.confidence.toDouble())
          .put("box", if (b == null) JSONArray() else JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
          .put("words", JSONArray(line.elements.map { JSONObject().put("text", it.text).put("conf", it.confidence.toDouble()) })))
    }
    return JSONObject().put("engine", ENGINE).put("ms", SystemClock.elapsedRealtime() - t).put("w", bmp.width).put("h", bmp.height).put("lines", lines)
  }

  fun fit(bmp: Bitmap, longSide: Int): Bitmap {
    val s = longSide.toFloat() / maxOf(bmp.width, bmp.height)
    return if (s >= 1f) bmp else Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
  }
}
