package com.fieldcapture

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    var ok = true
    for (dir in jobsRoot(applicationContext).listFiles().orEmpty()) {
      val f = File(dir, "session.json")
      if (!f.exists()) continue
      ok = runCatching { uploadJob(dir, JSONObject(f.readText())) }.onFailure { log("upload ${dir.name} failed: $it") }.getOrDefault(false) && ok
    }
    (applicationContext as FieldCaptureApp).session.refreshQueue()
    if (ok) Result.success() else Result.retry()
  }

  private fun uploadJob(dir: File, job: JSONObject): Boolean {
    val jobId = job.getString("job_id")
    val done = uploaded(dir)
    var ok = true
    for (c in job.getJSONArray("captures").objs()) {
      val id = c.getString("id")
      if (id in done) continue
      val meta = JSONObject()
          .put("job_id", jobId).put("capture_id", id).put("type", c.getString("kind")).put("taken_at", c.getLong("at"))
          .put("room", c.opt("room")).put("surface", c.opt("surface")).put("unrecognised", c.opt("unrecognised"))
          .put("transcript", c.opt("transcript")).put("asr_confidence", c.opt("asr_confidence")).put("retake_of", c.opt("retake_of"))
          .put("quality", JSONObject().put("flag", c.optString("quality_flag", "unchecked")).put("attempts", c.optInt("attempts", 1))
              .put("reasons", c.optJSONArray("quality_reasons") ?: JSONArray()).put("scores", c.opt("quality")))
      File(dir, "$id.ocr.json").takeIf { it.exists() }?.let { meta.put("ocr", JSONObject(it.readText())) }
      meta.put("timing", (c.optJSONObject("timing") ?: JSONObject()).put("upload_start", System.currentTimeMillis()))
      val code = multipart("/captures", meta.toString(), File(dir, c.getString("file")))
      ok = mark(dir, id, code) && ok
    }
    for (n in job.getJSONArray("notes").objs()) {
      val id = n.getString("id")
      if (id in done) continue
      val body = JSONObject().put("job_id", jobId).put("note_id", id).put("taken_at", n.getLong("at")).put("text", n.getString("text")).put("transcript", n.opt("transcript"))
      ok = mark(dir, id, post("/notes", body.toString())) && ok
    }
    if (!ok || job.getString("state") != "STOPPED" || File(dir, "finalized").exists()) return ok
    val events = job.getJSONArray("events").objs()
    val body = JSONObject()
        .put("tech_id", "tech-1")
        .put("started_at", job.getLong("started_at"))
        .put("ended_at", events.lastOrNull { it.getString("type").endsWith("->STOPPED") }?.getLong("at"))
        .put("device", JSONObject().put("type", if (BuildConfig.DEBUG) "mock" else "glasses").put("sdk_version", "DAT 1.1.0"))
        .put("capture_ids", JSONArray(job.getJSONArray("captures").objs().map { it.getString("id") }))
        .put("note_ids", JSONArray(job.getJSONArray("notes").objs().map { it.getString("id") }))
        .put("events", JSONArray(events))
    val code = post("/jobs/$jobId/finalize", body.toString())
    log("finalize $jobId -> $code")
    if (code != 200) return false
    File(dir, "finalized").writeText(System.currentTimeMillis().toString())
    return true
  }

  private fun mark(dir: File, id: String, code: Int): Boolean {
    log("upload ${dir.name}/$id -> $code")
    if (code != 200 && code != 201) return false
    File(dir, "uploaded.txt").appendText("$id\n")
    return true
  }

  private fun post(path: String, json: String) = send(path, "application/json", json.toByteArray())

  private fun multipart(path: String, meta: String, photo: File): Int {
    val b = "----fc" + UUID.randomUUID()
    val head = "--$b\r\nContent-Disposition: form-data; name=\"meta\"\r\n\r\n$meta\r\n" +
        "--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"${photo.name}\"\r\nContent-Type: image/jpeg\r\n\r\n"
    return send(path, "multipart/form-data; boundary=$b", head.toByteArray() + photo.readBytes() + "\r\n--$b--\r\n".toByteArray())
  }

  private fun send(path: String, type: String, body: ByteArray): Int {
    val c = URL(BuildConfig.BACKEND_URL + path).openConnection() as HttpURLConnection
    return try {
      c.requestMethod = "POST"
      c.connectTimeout = 10_000
      c.readTimeout = 30_000
      c.doOutput = true
      c.setRequestProperty("Content-Type", type)
      c.setFixedLengthStreamingMode(body.size)
      c.outputStream.use { it.write(body) }
      c.responseCode
    } finally {
      c.disconnect()
    }
  }

  private fun log(msg: String) = Log.i("FC", "upload: $msg")

  companion object {
    fun jobsRoot(ctx: Context) = File(ctx.getExternalFilesDir(null), "jobs")

    fun uploaded(dir: File) = File(dir, "uploaded.txt").takeIf { it.exists() }?.readLines()?.toSet().orEmpty()

    fun kick(ctx: Context) {
      val req = OneTimeWorkRequestBuilder<UploadWorker>()
          .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
          .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
          .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork("upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
  }
}

fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
