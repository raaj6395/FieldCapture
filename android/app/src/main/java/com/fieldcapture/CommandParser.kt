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
      "north wall", "south wall", "east wall", "west wall", "wall", "ceiling", "floor", "subfloor", "baseboard",
      "cabinet", "window", "door", "countertop", "carpet", "tile", "drywall", "pipe", "vanity",
  ).sortedByDescending { it.length }

  private val FIXES = listOf(
      "name plate" to "nameplate", "name plates" to "nameplate", "nameplates" to "nameplate",
      "captured" to "capture", "captures" to "capture", "capturing" to "capture", "capture the" to "capture",
      "notes" to "note", "node" to "note", "sub floor" to "subfloor",
      "damaged" to "damage", "damages" to "damage", "celling" to "ceiling", "sealing" to "ceiling",
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
    for ((re, build) in TABLE) re.matchEntire(s)?.let { return build(it) }
    return if (s.substringBefore(' ') in KEYWORDS) Command.Unknown(s) else null
  }

  private fun damage(args: String): Command.CaptureDamage {
    var rest = " $args "
    fun take(list: List<String>): String? =
        list.firstOrNull { rest.contains(" $it ") }?.also { rest = rest.replaceFirst(" $it ", " ") }
    val room = take(ROOMS)
    val surface = take(SURFACES)
    val left = rest.trim().split(' ').filter { it.isNotEmpty() && it !in FILLER }.joinToString(" ")
    return Command.CaptureDamage(room, surface, left.ifEmpty { null })
  }
}
