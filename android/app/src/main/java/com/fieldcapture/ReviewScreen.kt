package com.fieldcapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val REVIEW_BELOW = 0.5
private const val SURE_ABOVE = 0.8

fun pastJobs(ctx: Context): List<String> =
    UploadWorker.jobsRoot(ctx).listFiles().orEmpty().filter { File(it, "session.json").exists() }.sortedByDescending { it.lastModified() }.map { it.name }

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
  return JSONObject().put("job_id", id).put("status", "on phone only (state ${s.getString("state")})")
      .put("captures", org.json.JSONArray(caps)).put("notes", org.json.JSONArray(notes)).put("line_items", org.json.JSONArray())
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

private fun confColor(c: Double) = when {
  c < REVIEW_BELOW -> Color(0xFFC62828)
  c < SURE_ABOVE -> Color(0xFFB26A00)
  else -> Color(0xFF2E7D32)
}

private fun show(v: Any?): String = when (v) {
  null, JSONObject.NULL -> "-"
  is JSONObject -> v.keys().asSequence().joinToString(" x ") { "${v.get(it)}" } + if (v.has("width")) " cm" else ""
  else -> v.toString()
}

@Composable
private fun Fields(fields: JSONObject?) {
  fields ?: return
  for (k in fields.keys()) {
    val f = fields.optJSONObject(k) ?: continue
    if (k == "needs_review") continue
    val conf = f.optDouble("confidence", 0.0)
    val value = f.opt("value")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(k.replace('_', ' '), fontSize = 13.sp, modifier = Modifier.padding(end = 2.dp))
      Text(show(value), fontSize = 13.sp, fontWeight = FontWeight.Bold)
      if (value == null || value == JSONObject.NULL) Text("not read", fontSize = 12.sp, color = Color.Gray)
      else Text("%.2f".format(conf), fontSize = 12.sp, color = confColor(conf))
      if (value != null && value != JSONObject.NULL && conf < REVIEW_BELOW) Text("check", fontSize = 12.sp, color = confColor(conf))
    }
  }
}

@Composable
fun ReviewScreen(jobId: String, onBack: () -> Unit) {
  val ctx = LocalContext.current
  var rec by remember { mutableStateOf<JSONObject?>(null) }
  var source by remember { mutableStateOf("Loading...") }
  LaunchedEffect(jobId) {
    val remote = withContext(Dispatchers.IO) { runCatching { fetchRecord(jobId) }.getOrNull() }
    rec = remote ?: withContext(Dispatchers.IO) { localRecord(ctx, jobId) }
    source = if (remote != null) "AI results from the backend" else "Not uploaded yet or backend offline: showing what is on the phone. AI results appear after upload."
  }
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = onBack) { Text("Back") }
      Text("Job $jobId", fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
    Text(source, fontSize = 12.sp)
    val r = rec ?: return@Column
    val summary = r.optJSONObject("summary")
    Text("Status ${r.optString("status")}" + (summary?.let { " - ${it.optInt("captures")} photos, ${it.optInt("notes")} notes, ${it.optInt("needs_review")} items to review, ${it.optInt("low_quality")} low-quality photos" } ?: ""),
        fontSize = 14.sp)
    LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      items(r.optJSONArray("captures")?.objs().orEmpty()) { c ->
        val id = c.getString("capture_id")
        val bmp by produceState<Bitmap?>(null, id) { value = withContext(Dispatchers.IO) { thumbnail(ctx, jobId, id) } }
        Card(Modifier.fillMaxWidth()) {
          Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = "photo $id", modifier = Modifier.size(96.dp)) }
            Column {
              val q = c.optJSONObject("quality")
              val failed = q?.optJSONArray("failed_checks")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty()
              Text("$id ${c.getString("type")}", fontWeight = FontWeight.Bold)
              Text("photo quality ${q?.optString("flag") ?: "-"}" + if (failed.isNotEmpty()) " (${failed.joinToString()})" else "", fontSize = 12.sp)
              Fields(c.optJSONObject("fields"))
            }
          }
        }
      }
      items(r.optJSONArray("notes")?.objs().orEmpty()) { n ->
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(8.dp)) {
            Text("${n.getString("note_id")} note", fontWeight = FontWeight.Bold)
            Text("\"${n.getString("text")}\"", fontSize = 13.sp)
            Fields(n.optJSONObject("fields"))
          }
        }
      }
      val items = r.optJSONArray("line_items")?.objs().orEmpty()
      if (items.isNotEmpty()) item { Text("Draft line items", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
      items(items) { i ->
        val status = i.optString("status")
        Column(Modifier.fillMaxWidth().background(if (status == "draft") Color.Transparent else Color(0x22FFA000)).padding(6.dp)) {
          Text("${i.getString("code")}  ${i.optString("description")}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
          Text("${show(i.opt("qty"))} ${i.optString("unit")} (${i.optString("qty_basis")}) - $status - conf %.2f - from ${show(i.optJSONArray("source_ids")?.join(", ")?.replace("\"", ""))}"
              .format(i.optDouble("confidence", 0.0)) + (i.optString("reason").takeIf { it.isNotEmpty() }?.let { " - $it" } ?: ""), fontSize = 12.sp)
        }
      }
    }
  }
}
