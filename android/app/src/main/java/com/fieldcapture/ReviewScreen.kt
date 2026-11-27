package com.fieldcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class JobSummary(val id: String, val started: String, val photos: Int, val notes: Int, val status: String, val waiting: Boolean)

private val WHEN = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

fun jobSummaries(ctx: Context): List<JobSummary> =
    UploadWorker.jobsRoot(ctx).listFiles().orEmpty().filter { File(it, "session.json").exists() }.sortedByDescending { it.lastModified() }.mapNotNull { dir ->
      runCatching {
        val s = JSONObject(File(dir, "session.json").readText())
        val ids = (s.getJSONArray("captures").objs() + s.getJSONArray("notes").objs()).map { it.getString("id") }
        val left = (ids - UploadWorker.uploaded(dir)).size
        val status = when {
          s.getString("state") !in setOf("STOPPED", "IDLE") -> "In progress"
          left > 0 -> "$left waiting to upload"
          File(dir, "finalized").exists() -> "Uploaded"
          else -> "Waiting to upload"
        }
        JobSummary(dir.name, WHEN.format(Date(s.getLong("started_at"))), s.getJSONArray("captures").length(), s.getJSONArray("notes").length(),
            status, status != "Uploaded" && status != "In progress")
      }.getOrNull()
    }

private fun fetchRecord(id: String): JSONObject? {
  val c = URL("${BuildConfig.BACKEND_URL}/jobs/$id").openConnection() as HttpURLConnection
  return try {
    c.connectTimeout = 5_000
    c.readTimeout = 10_000
    if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
  } finally {
    c.disconnect()
  }
}

private fun localRecord(ctx: Context, id: String): JSONObject {
  val s = JSONObject(File(UploadWorker.jobsRoot(ctx), "$id/session.json").readText())
  val caps = s.getJSONArray("captures").objs().map { c ->
    val fields = JSONObject()
    for (k in listOf("room", "surface")) if (!c.isNull(k)) fields.put(k, JSONObject().put("value", c.getString(k)).put("confidence", 0.9))
    JSONObject().put("capture_id", c.getString("id")).put("type", c.getString("kind")).put("fields", fields)
        .put("quality", JSONObject().put("flag", c.optString("quality_flag")).put("failed_checks", c.optJSONArray("quality_reasons")))
  }
  val notes = s.getJSONArray("notes").objs().map { n -> JSONObject().put("note_id", n.getString("id")).put("text", n.getString("text")).put("fields", JSONObject()) }
  return JSONObject().put("job_id", id).put("captures", JSONArray(caps)).put("notes", JSONArray(notes)).put("line_items", JSONArray())
}

private fun thumbnail(ctx: Context, jobId: String, captureId: String): Bitmap? {
  val f = File(UploadWorker.jobsRoot(ctx), jobId).listFiles().orEmpty().firstOrNull { it.name.startsWith("$captureId.") && it.extension in setOf("jpg", "heic") }
      ?: return null
  val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 }) ?: return null
  val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
    else -> 0f
  }
  return if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}

private val ORDER = listOf("make", "model", "serial", "mfg_date", "appliance", "room", "surface", "damage_type", "size_cm",
    "location", "issue", "material", "severity", "action")
private val LABEL = mapOf("mfg_date" to "Made", "damage_type" to "Damage", "size_cm" to "Size", "voice_unrecognised" to "Heard")
private val VERBATIM = setOf("make", "model", "serial", "mfg_date")
private val BASIS = mapOf("measured" to "measured", "estimated" to "estimated from photo", "default" to "default quantity")

private fun absent(v: Any?) = v == null || v == JSONObject.NULL

private fun display(key: String, v: Any?): String = when {
  absent(v) -> "Not read"
  v is JSONObject && v.has("width") -> "${v.optInt("width")} × ${v.optInt("height")} cm"
  key in VERBATIM -> v.toString()
  else -> human(v.toString())
}

private fun qty(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)

@Composable
private fun FieldRow(key: String, f: JSONObject) {
  val v = f.opt("value")
  val conf = f.optDouble("confidence", 0.0)
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(LABEL[key] ?: human(key), style = Fc.label, modifier = Modifier.width(88.dp))
    Text(display(key, v), style = if (absent(v)) Fc.body.copy(color = Fc.muted) else Fc.body, modifier = Modifier.weight(1f),
        maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (!absent(v)) {
      if (conf < CONF_CHECK) Text("Check", style = Fc.label.copy(color = Fc.amber), modifier = Modifier.padding(end = 8.dp))
      ConfidenceMeter(conf)
      Text("%.2f".format(conf), style = Fc.meta, modifier = Modifier.padding(start = 8.dp))
    }
  }
}

@Composable
private fun Fields(fields: JSONObject?) {
  fields ?: return
  val keys = fields.keys().asSequence().filter { it != "needs_review" && fields.optJSONObject(it) != null }.toList()
  Column {
    keys.sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }.forEach { FieldRow(it, fields.getJSONObject(it)) }
  }
}

@Composable
fun ReviewScreen(jobId: String, onBack: () -> Unit) {
  val ctx = LocalContext.current
  var rec by remember { mutableStateOf<JSONObject?>(null) }
  var remote by remember { mutableStateOf(true) }
  LaunchedEffect(jobId) {
    val r = withContext(Dispatchers.IO) { runCatching { fetchRecord(jobId) }.getOrNull() }
    remote = r != null
    rec = r ?: withContext(Dispatchers.IO) { localRecord(ctx, jobId) }
  }
  LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
    item {
      Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(Fc.rControl).clickable(onClick = onBack), contentAlignment = Alignment.Center) { FcIcon(Lucide.back, Fc.text, 22.dp) }
        Spacer(Modifier.width(4.dp))
        Column {
          Text("Job review", style = Fc.heading)
          Text(jobId, style = Fc.meta)
        }
      }
    }
    val r = rec ?: return@LazyColumn
    if (!remote) item {
      Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Fc.rControl).background(Fc.amber.copy(alpha = 0.08f))
          .border(1.dp, Fc.amber.copy(alpha = 0.24f), Fc.rControl).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FcIcon(Lucide.upload, Fc.amber)
        Text("Not uploaded yet. Photos and spoken details are on this phone; AI results appear after upload.", style = Fc.label.copy(color = Fc.text))
      }
    }
    r.optJSONObject("summary")?.let { s ->
      item {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          val missing = r.optJSONArray("missing")?.length() ?: 0
          val low = s.optInt("low_quality")
          Text((if (r.optString("status") == "complete") "Complete" else "Partial, $missing not uploaded") +
              "  ·  " + if (low == 0) "all photos passed the quality check" else plural(low, "low-quality photo"), style = Fc.label)
          Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("Photos", "${s.optInt("captures")}", Modifier.weight(1f))
            Stat("Notes", "${s.optInt("notes")}", Modifier.weight(1f))
            Stat("To review", "${s.optInt("needs_review")}", Modifier.weight(1f), if (s.optInt("needs_review") > 0) Fc.amber else Fc.text)
          }
        }
      }
    }
    val caps = r.optJSONArray("captures")?.objs().orEmpty()
    if (caps.isNotEmpty()) item { SectionLabel("Photos") }
    items(caps) { c ->
      val id = c.getString("capture_id")
      val bmp by produceState<Bitmap?>(null, id) { value = withContext(Dispatchers.IO) { thumbnail(ctx, jobId, id) } }
      val q = c.optJSONObject("quality")
      val failed = q?.optJSONArray("failed_checks")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
      Box(Modifier.padding(bottom = 12.dp)) {
        Card {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Box(Modifier.size(72.dp).clip(Fc.rSmall).background(Fc.raised)) {
                bmp?.let { Image(it.asImageBitmap(), contentDescription = "Photo $id", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
              }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(human(c.getString("type")), style = Fc.heading, modifier = Modifier.weight(1f))
                  Text(id, style = Fc.meta)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  val low = q?.optString("flag") == "low"
                  FcIcon(if (low) Lucide.alert else Lucide.check, if (low) Fc.amber else Fc.muted, 14.dp)
                  Text(if (low) "Low quality: ${failed.joinToString { human(it).lowercase() }}" else "Photo quality ok",
                      style = Fc.label.copy(color = if (low) Fc.amber else Fc.text2))
                }
              }
            }
            Hairline()
            Fields(c.optJSONObject("fields"))
          }
        }
      }
    }
    val notes = r.optJSONArray("notes")?.objs().orEmpty()
    if (notes.isNotEmpty()) item { SectionLabel("Notes") }
    items(notes) { n ->
      Box(Modifier.padding(bottom = 12.dp)) {
        Card {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              FcIcon(Lucide.note, Fc.muted)
              Text("“${n.getString("text")}”", style = Fc.body, modifier = Modifier.weight(1f))
              Text(n.getString("note_id"), style = Fc.meta)
            }
            n.optJSONObject("fields")?.takeIf { f -> f.keys().asSequence().any { it != "needs_review" } }?.let { f ->
              Hairline()
              Fields(f)
            }
          }
        }
      }
    }
    val lines = r.optJSONArray("line_items")?.objs().orEmpty()
    if (lines.isNotEmpty()) {
      item { SectionLabel("Draft estimate") }
      item {
        Column(Modifier.padding(bottom = 32.dp).clip(Fc.rCard).background(Fc.card).border(1.dp, Fc.hairline, Fc.rCard)) {
          lines.forEachIndexed { i, li ->
            if (i > 0) Box(Modifier.padding(start = 16.dp)) { Hairline() }
            val status = li.optString("status")
            val sources = li.optJSONArray("source_ids")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(li.optString("description"), style = Fc.body, modifier = Modifier.weight(1f))
                ConfidenceMeter(li.optDouble("confidence", 0.0))
              }
              Text("${li.getString("code")}  ·  ${qty(li.optDouble("qty", 0.0))} ${li.optString("unit").let { u -> if (u == "day" && li.optDouble("qty") != 1.0) "days" else u }}, ${BASIS[li.optString("qty_basis")] ?: li.optString("qty_basis")}  ·  from ${sources.joinToString()}",
                  style = Fc.label)
              if (status != "draft") {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  FcIcon(Lucide.alert, Fc.amber, 14.dp)
                  Text(human(status) + (li.optString("reason").takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""), style = Fc.label.copy(color = Fc.amber))
                }
              }
            }
          }
        }
      }
    }
  }
}
