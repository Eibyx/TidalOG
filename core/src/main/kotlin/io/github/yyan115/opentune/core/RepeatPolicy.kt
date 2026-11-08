package io.github.yyan115.opentune.core

/** Keeps tracks that played a short while ago from coming back soon. */
object RepeatPolicy {
    /** Strict radio exclusions, unlike short user-selected lists which may retain recent tracks. */
    fun radioCandidates(items: List<Track>, queued: Set<String>, recent: Set<String>, limit: Int): List<Track> =
        items.asSequence().filter { !it.isFile && it.id.isNotBlank() && it.id !in queued && it.id !in recent }
            .distinctBy(Track::id).take(limit).toList()

    /** Puts the items that played recently after the others. The order inside each group stays as it is. */
    fun <T> deferRecent(items: List<T>, id: (T) -> String, recent: Set<String>): List<T> {
        if (recent.isEmpty()) return items
        val (old, played) = items.partition { id(it) !in recent }
        return old + played
    }

    /** Leaves out the items that played recently. When nothing else is left, all of them stay, so that a short list never turns empty. */
    fun <T> dropRecent(items: List<T>, id: (T) -> String, recent: Set<String>): List<T> {
        if (recent.isEmpty()) return items
        return items.filter { id(it) !in recent }.ifEmpty { items }
    }
}
