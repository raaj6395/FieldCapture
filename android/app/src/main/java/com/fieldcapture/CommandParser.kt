package com.fieldcapture

sealed interface Command {
  data object CaptureNameplate : Command
  data class CaptureDamage(val room: String?, val surface: String?, val unrecognised: String?) : Command
  data class Note(val text: String) : Command
  data object Start : Command
  data object Pause : Command
  data object Resume : Command
  data object Stop : Command
  data object Retake : Command
  data class Unknown(val text: String) : Command
}

object CommandParser {

  val ROOMS = listOf(
      "kitchen", "bathroom", "master bathroom", "bedroom", "master bedroom", "living room", "dining room",
      "family room", "basement", "attic", "laundry", "laundry room", "hallway", "hall", "garage", "utility room",
      "utility", "office", "closet", "stairs", "porch", "crawl space",
  ).sortedByDescending { it.length }

  val SURFACES = listOf(
      "north wall", "south wall", "east wall", "west wall", "wall", "drywall", "baseboard", "trim", "stud", "insulation",
      "door frame", "window sill", "window", "door",
      "ceiling", "ceiling fan", "light fixture", "vent", "duct", "skylight", "roof",
      "floor", "subfloor", "carpet", "carpet pad", "hardwood", "laminate", "vinyl", "tile", "grout", "joist", "slab", "foundation",
      "cabinet", "vanity", "countertop", "backsplash", "sink", "faucet", "toilet", "bathtub", "tub", "shower", "pipe", "drain",
      "water heater", "dishwasher", "washing machine", "refrigerator", "outlet", "switch", "fan", "curtain rod",
  ).sortedByDescending { it.length }

  private val FIXES = listOf(
      "name plate" to "nameplate", "name please" to "nameplate", "name plates" to "nameplate", "nameplates" to "nameplate",
      "captured" to "capture", "captures" to "capture", "capturing" to "capture", "captain" to "capture", "capital" to "capture", "capture the" to "capture",
      "notes" to "note", "node" to "note", "sub floor" to "subfloor", "basements" to "basement",
      "damaged" to "damage", "damages" to "damage", "celling" to "ceiling", "sealing" to "ceiling",
      "sync" to "sink", "zinc" to "sink", "hanger" to "curtain rod", "toilets" to "toilet", "pipes" to "pipe",
  )

  private val FILLER = setOf("in", "the", "on", "at", "of", "a", "by", "and")

  private val KEYWORDS = setOf("capture", "note", "pause", "resume", "retake", "start", "begin", "end", "stop", "finish")

  private val TABLE: List<Pair<Regex, (MatchResult) -> Command>> = listOf(
      Regex("^capture nameplate$") to { _ -> Command.CaptureNameplate },
      Regex("^capture damage(?: (.+))?$") to { m -> damage(m.groupValues[1]) },
      Regex("^note (.+)$") to { m -> Command.Note(m.groupValues[1]) },
      Regex("^(?:start|begin) (?:the )?job$") to { _ -> Command.Start },
      Regex("^pause(?: job)?$") to { _ -> Command.Pause },
      Regex("^resume(?: job)?$") to { _ -> Command.Resume },
      Regex("^(?:end|stop|finish) (?:the )?job$") to { _ -> Command.Stop },
      Regex("^(?:retake|again|retake that)$") to { _ -> Command.Retake },
  )

  fun normalize(raw: String): String {
    var s = " " + raw.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
    for ((a, b) in FIXES) s = s.replace(" $a ", " $b ")
    return s.trim()
  }

  fun parse(raw: String): Command? {
    val s = normalize(raw)
    if (s.isEmpty()) return null
    strict(s)?.let { return it }
    fuzzy(s)?.let { return it }
    return if (s.substringBefore(' ') in KEYWORDS) Command.Unknown(s) else null
  }

  private fun strict(s: String): Command? {
    for ((re, build) in TABLE) re.matchEntire(s)?.let { return build(it) }
    return null
  }

  private val LEAD = setOf("uh", "um", "umm", "ok", "okay", "so", "hey", "now", "please", "alright", "right")
  private val ECHOES = setOf("noted", "paused", "resumed")
  private val PLACE_WORDS = (ROOMS + SURFACES).flatMap { it.split(' ') }.toSet()

  private fun fuzzy(s: String): Command? {
    val t = s.split(' ').dropWhile { it in LEAD }.dropLastWhile { it == "please" }
    if (t.isEmpty() || t[0] in ECHOES) return null
    strict(t.joinToString(" "))?.let { return it }
    val head = t[0]
    val rest = t.drop(1)
    if (rest.isEmpty()) {
      return when {
        near(head, "pause", 1) -> Command.Pause
        near(head, "resume", 1) -> Command.Resume
        near(head, "retake", 1) -> Command.Retake
        else -> null
      }
    }
    if (rest == listOf("nameplate")) return Command.CaptureNameplate
    if (near(head, "capture", 2) || head in CAPTURE_SOUNDS) {
      if (rest.size <= 2 && (rest.joinToString("").let { lev(it, "nameplate") <= 4 } || rest[0].startsWith("nam"))) return Command.CaptureNameplate
      if (near(rest[0], "damage", 3)) return damage(rest.drop(1).joinToString(" "))
    }
    if (rest.firstOrNull() == "damage" && lev(head, "capture") <= 3) return damage(rest.drop(1).joinToString(" "))
    if ((near(head, "note", 1) || head in setOf("no", "know")) && rest.size >= 3 && rest[0] !in KEYWORDS) return Command.Note(rest.joinToString(" "))
    if (head in setOf("end", "and", "stop", "finish", "start", "begin") && rest.size == 1 && near(rest[0], "job", 1)) {
      return if (head == "start" || head == "begin") Command.Start else Command.Stop
    }
    return null
  }

  private val CAPTURE_SOUNDS = setOf("captain", "capital", "captor", "rapture", "kept", "capsule", "picture")

  private fun places(words: List<String>): String = words.filter { it.isNotEmpty() }.joinToString(" ") { w ->
    if (w in PLACE_WORDS || w.length < 4) w
    else PLACE_WORDS.filter { near(w, it, if (it.length <= 5) 1 else 2) }.minByOrNull { lev(w, it) } ?: w
  }

  private fun near(a: String, b: String, max: Int) = a.length >= 3 && lev(a, b) <= max

  fun lev(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val cur = IntArray(b.length + 1)
      cur[0] = i
      for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      prev = cur
    }
    return prev[b.length]
  }

  private fun damage(args: String): Command.CaptureDamage {
    var rest = " ${places(args.split(' '))} "
    fun take(list: List<String>): String? =
        list.firstOrNull { rest.contains(" $it ") }?.also { rest = rest.replaceFirst(" $it ", " ") }
    val room = take(ROOMS)
    val surface = take(SURFACES)
    val left = rest.trim().split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    return Command.CaptureDamage(room, surface, left.ifEmpty { null })
  }
}
