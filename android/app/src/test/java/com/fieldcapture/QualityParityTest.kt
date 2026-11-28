package com.fieldcapture

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityParityTest {

  private val t = Thresholds(20.0, 45.0, 0.5, mapOf("nameplate" to KindThresholds(60.0), "damage" to KindThresholds(15.0)), true)

  private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream("quality/$name")!!

  private fun ppm(name: String): Triple<IntArray, Int, Int> {
    val bytes = GZIPInputStream(res(name)).readBytes()
    var pos = 0
    fun token(): String {
      while (bytes[pos].toInt().toChar().isWhitespace()) pos++
      val start = pos
      while (!bytes[pos].toInt().toChar().isWhitespace()) pos++
      return String(bytes, start, pos - start)
    }
    token()
    val w = token().toInt()
    val h = token().toInt()
    token()
    pos++
    val rgb = IntArray(w * h) { i -> val o = pos + i * 3; (bytes[o].toInt() and 0xFF shl 16) or (bytes[o + 1].toInt() and 0xFF shl 8) or (bytes[o + 2].toInt() and 0xFF) }
    return Triple(rgb, w, h)
  }

  @Test
  fun kotlinMatchesPython() {
    val rows = res("expected.csv").bufferedReader().readLines().drop(1).map { it.split(",") }
    assertEquals(5, rows.size)
    val report = rows.map { row ->
      val (rgb, w, h) = ppm(row[0])
      val s = Quality.score(rgb, w, h, t)
      val got = listOf(s.blur, s.brightness, s.darkFrac, s.glareFrac, s.edgeDensity, s.borderEdgeFrac)
      val want = row.drop(1).map { it.toDouble() }
      val bad = got.zip(want).withIndex().filter { (_, p) -> abs(p.first - p.second) > maxOf(0.02 * abs(p.second), 0.002) }
      Triple(row[0], got.zip(want), bad.map { it.index })
    }
    val text = report.joinToString("\n") { (f, pairs, bad) -> "$f bad=$bad " + pairs.joinToString { "%.4f/%.4f".format(it.first, it.second) } }
    println(text)
    assertTrue(text, report.all { it.third.isEmpty() })
  }

  @Test
  fun verdictOrderPutsDarkFirst() {
    val dark = QualityScores(4.7, 31.0, 0.28, 0.0, 0.0001, 0.92)
    assertEquals(listOf("dark", "blurry"), Quality.verdict(dark, "damage", t))
    assertEquals("Too dark. Turn on your light.", Quality.prompt(Quality.verdict(dark, "damage", t).first()))
    val sharp = QualityScores(120.0, 120.0, 0.01, 0.0, 0.07, 0.3)
    assertEquals(emptyList<String>(), Quality.verdict(sharp, "nameplate", t))
  }

  @Test
  fun textRunningOffTheFrameIsCutOff() {
    val inside = "MODEL XR-200" to intArrayOf(100, 200, 500, 240)
    val atRight = "SERIAL 4471" to intArrayOf(300, 260, 639, 300)
    val shortAtEdge = "UL" to intArrayOf(0, 10, 30, 40)
    assertEquals(0, Quality.textAtEdge(listOf(inside, shortAtEdge), 640, 480, 0.03))
    assertEquals(1, Quality.textAtEdge(listOf(inside, atRight), 640, 480, 0.03))
    assertEquals(1, Quality.textAtEdge(listOf("WATER HEATER" to intArrayOf(80, 2, 400, 40)), 640, 480, 0.03))
  }

  @Test
  fun sidewaysMotionBlurIsBlurryEvenWithStrongEdges() {
    val w = 360
    val h = 480
    val rnd = java.util.Random(7)
    val noise = IntArray(w * h) { if (rnd.nextBoolean()) 230 else 20 }
    fun rgb(g: IntArray) = IntArray(g.size) { (g[it] shl 16) or (g[it] shl 8) or g[it] }
    val smeared = IntArray(w * h) { i -> val r = i / w; val c = i % w; (0 until 15).sumOf { noise[r * w + minOf(w - 1, c + it)] } / 15 }
    val tm = t.copy(motionRatio = 0.24)
    val sharp = Quality.score(rgb(noise), w, h, tm)
    val moved = Quality.score(rgb(smeared), w, h, tm)
    assertTrue("sharp ratio ${sharp.motionRatio}", sharp.motionRatio > 0.8)
    assertTrue("moved ratio ${moved.motionRatio} blur ${moved.blur}", moved.motionRatio < 0.24 && moved.blur > 60.0)
    assertEquals(emptyList<String>(), Quality.verdict(sharp, "nameplate", tm))
    assertEquals(listOf("blurry"), Quality.verdict(moved, "nameplate", tm))
  }
}
