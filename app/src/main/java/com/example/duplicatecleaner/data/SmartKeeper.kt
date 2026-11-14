package com.example.duplicatecleaner.data

import java.io.File

/** The copy Smart chose to keep, why, and whether it is sure. */
data class SmartChoice(
    val keeperPath: String,
    val reason: String,
    val confident: Boolean
)

/**
 * Picks which copy of a duplicate to keep by looking at where it lives and what it is called.
 * It is a heuristic: it can be wrong, so uncertain groups are flagged for the user to review.
 *
 * Signals (higher score = better keeper):
 *  - Location: copies in downloads / chat-app / cache folders lose; deeper, organised folders win.
 *  - Name: "report (1).pdf", "Copy of report.pdf", "report - Copy.pdf" lose; chat-app names lose a little.
 *  - Neighbours: a copy filed among many other files in its folder wins a little.
 *  Ties are broken by the oldest date, then the shortest path.
 */
object SmartKeeper {

    private val JUNK_FOLDERS = listOf(
        "whatsapp", "telegram", "download", "bluetooth", "cache", "temp", "tmp",
        "received", "snapchat", "instagram", "messenger", "signal", "shareit", "xender"
    )
    private val COPY_SUFFIX = Regex("""(\s?\(\d+\)|[\s_-]+copy(\s?\d+)?)$""", RegexOption.IGNORE_CASE)
    private val COPY_PREFIX = Regex("""^copy of\s""", RegexOption.IGNORE_CASE)
    private val CHAT_APP_NAME = Regex("""-wa\d+$""", RegexOption.IGNORE_CASE) // WhatsApp: IMG-20240412-WA0001

    /** A winner must beat the runner-up by at least this many points to count as confident. */
    const val CONFIDENCE_MARGIN = 12

    private data class Score(
        val file: MediaFile,
        val location: Int,
        val name: Int,
        val siblings: Int,
        val inJunk: Boolean,
        val copyLooking: Boolean
    ) {
        val total get() = location + name + siblings
    }

    fun decideAll(
        groups: List<DuplicateGroup>,
        siblingCount: (File) -> Int = { dir -> dir.list()?.size ?: 0 }
    ): Map<String, SmartChoice> {
        val cache = HashMap<String, Int>()
        val out = HashMap<String, SmartChoice>(groups.size * 2)
        for (g in groups) {
            val choice = decide(g) { dir -> cache.getOrPut(dir.path) { siblingCount(dir) } }
            g.files.forEach { out[it.path] = choice }
        }
        return out
    }

    fun decide(group: DuplicateGroup, siblingCount: (File) -> Int): SmartChoice {
        val scored = group.files
            .map { score(it, siblingCount) }
            .sortedWith(
                compareByDescending<Score> { it.total }
                    .thenBy { it.file.dateModified }
                    .thenBy { it.file.path.length }
            )
        val winner = scored[0]
        val runner = scored[1]
        val margin = winner.total - runner.total
        return SmartChoice(
            keeperPath = winner.file.path,
            reason = reasonFor(winner, runner),
            confident = margin >= CONFIDENCE_MARGIN
        )
    }

    private fun score(file: MediaFile, siblingCount: (File) -> Int): Score {
        val folderLower = file.folder.lowercase()
        val inJunk = JUNK_FOLDERS.any { folderLower.contains(it) }
        val depth = file.folder.split('/').count { it.isNotEmpty() }
        val location = if (inJunk) -30 else minOf(depth, 3) * 4

        val base = file.name.substringBeforeLast('.', file.name)
        val copyLooking = COPY_SUFFIX.containsMatchIn(base) || COPY_PREFIX.containsMatchIn(base)
        val name = when {
            copyLooking -> -25
            CHAT_APP_NAME.containsMatchIn(base) -> -10
            else -> 0
        }

        val parent = File(file.path).parentFile
        val siblings = if (parent == null) 0 else minOf(siblingCount(parent), 50) / 5

        return Score(file, location, name, siblings, inJunk, copyLooking)
    }

    private fun reasonFor(winner: Score, runner: Score): String {
        val deltas = listOf(
            "name" to winner.name - runner.name,
            "location" to winner.location - runner.location,
            "siblings" to winner.siblings - runner.siblings
        )
        val top = deltas.maxByOrNull { it.second }
        return when {
            top == null || top.second <= 0 ->
                "Oldest copy (nothing else tells them apart)"
            top.first == "name" ->
                "The other copy looks like a duplicate by name (\"${runner.file.name}\")"
            top.first == "location" && runner.inJunk && !winner.inJunk ->
                "Kept in ${shortFolder(winner.file)}; the other is in ${shortFolder(runner.file)}"
            top.first == "location" ->
                "Kept in the more organised folder, ${shortFolder(winner.file)}"
            else ->
                "Sits with more files in ${shortFolder(winner.file)}"
        }
    }

    private fun shortFolder(f: MediaFile): String {
        val parts = f.folder.split('/').filter { it.isNotEmpty() }
        return if (parts.isEmpty()) "storage root" else parts.takeLast(2).joinToString("/")
    }
}
