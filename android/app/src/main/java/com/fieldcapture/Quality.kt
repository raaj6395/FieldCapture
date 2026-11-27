package com.fieldcapture

import kotlin.math.abs
import kotlin.math.roundToInt

data class QualityScores(
    val blur: Double,
    val brightness: Double,
    val darkFrac: Double,
    val glareFrac: Double,
    val edgeDensity: Double,
    val borderEdgeFrac: Double,
)

data class KindThresholds(val blur: Double, val glareFrac: Double? = null, val emptyEdgeDensity: Double? = null, val borderEdgeFrac: Double? = null, val textEdgeMargin: Double? = null)

data class Thresholds(
    val edgeE: Double,
    val darkMean: Double,
    val darkFrac: Double,
    val kinds: Map<String, KindThresholds>,
    val provisional: Boolean,
)

object Quality {
  const val WIDTH = 360

  fun luma(rgb: IntArray) = DoubleArray(rgb.size) {
    val p = rgb[it]
    0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)
  }

  fun shrink(y: DoubleArray, w: Int, h: Int, width: Int = WIDTH): Pair<DoubleArray, Int> {
    val height = (h.toDouble() * width / w).roundToInt()
    val ii = DoubleArray((h + 1) * (w + 1))
    for (r in 0 until h) {
      var row = 0.0
      for (c in 0 until w) {
        row += y[r * w + c]
        ii[(r + 1) * (w + 1) + c + 1] = ii[r * (w + 1) + c + 1] + row
      }
    }
    val out = DoubleArray(width * height)
    for (r in 0 until height) {
      val y0 = r * h / height
      val y1 = maxOf((r + 1) * h / height, y0 + 1)
      for (c in 0 until width) {
        val x0 = c * w / width
        val x1 = maxOf((c + 1) * w / width, x0 + 1)
        val s = ii[y1 * (w + 1) + x1] - ii[y0 * (w + 1) + x1] - ii[y1 * (w + 1) + x0] + ii[y0 * (w + 1) + x0]
        out[r * width + c] = s / ((y1 - y0) * (x1 - x0))
      }
    }
    return out to height
  }

  fun scores(y: DoubleArray, w: Int, h: Int, edgeE: Double): QualityScores {
    val n = (w - 2) * (h - 2)
    val lap = DoubleArray(n)
    var i = 0
    for (r in 1 until h - 1) for (c in 1 until w - 1) {
      lap[i++] = y[(r - 1) * w + c] + y[(r + 1) * w + c] + y[r * w + c - 1] + y[r * w + c + 1] - 4 * y[r * w + c]
    }
    val mean = lap.average()
    val blur = lap.sumOf { (it - mean) * (it - mean) } / n
    val bw = (0.08 * w).toInt()
    val bh = (0.08 * h).toInt()
    var edges = 0
    var borderEdges = 0
    i = 0
    for (r in 1 until h - 1) for (c in 1 until w - 1) {
      if (abs(lap[i++]) > edgeE) {
        edges++
        if (c < bw || c >= w - bw || r < bh || r >= h - bh) borderEdges++
      }
    }
    var glare = 0
    val r0 = (0.2 * h).toInt()
    val r1 = (0.8 * h).toInt()
    val c0 = (0.2 * w).toInt()
    val c1 = (0.8 * w).toInt()
    for (r in r0 until r1) for (c in c0 until c1) if (y[r * w + c] > 250) glare++
    return QualityScores(
        blur = blur,
        brightness = y.average(),
        darkFrac = y.count { it < 20 }.toDouble() / y.size,
        glareFrac = glare.toDouble() / ((r1 - r0) * (c1 - c0)),
        edgeDensity = edges.toDouble() / n,
        borderEdgeFrac = borderEdges.toDouble() / maxOf(edges, 1),
    )
  }

  fun score(rgb: IntArray, w: Int, h: Int, t: Thresholds): QualityScores {
    val (small, sh) = shrink(luma(rgb), w, h)
    return scores(small, WIDTH, sh, t.edgeE)
  }

  fun verdict(s: QualityScores, kind: String, t: Thresholds): List<String> {
    val k = t.kinds.getValue(kind)
    return buildList {
      if (s.brightness < t.darkMean || s.darkFrac > t.darkFrac) add("dark")
      if (s.blur < k.blur) add("blurry")
      if (k.glareFrac != null && s.glareFrac > k.glareFrac) add("glare")
      if (k.emptyEdgeDensity != null && s.edgeDensity < k.emptyEdgeDensity) add("empty")
      if (k.borderEdgeFrac != null && s.borderEdgeFrac > k.borderEdgeFrac) add("cut_off")
    }
  }

  fun textAtEdge(lines: List<Pair<String, IntArray>>, w: Int, h: Int, margin: Double) = lines.count { (text, b) ->
    text.trim().length >= 3 && b.size == 4 && (b[0] <= margin * w || b[1] <= margin * h || b[2] >= w - margin * w || b[3] >= h - margin * h)
  }

  fun prompt(reason: String) = when (reason) {
    "dark" -> "Too dark. Turn on your light."
    "blurry" -> "Blurry. Hold still."
    "glare" -> "Glare. Tilt a little."
    "cut_off" -> "Cut off. Step back."
    else -> "Nothing in view."
  }
}
