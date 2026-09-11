package com.henrylumis.mediaprayer.util

import android.content.Context
import org.json.JSONArray

/** Persistent local playlists. Song ids are MediaStore ids, matching MediaItem.mediaId. */
object PlaylistStore {
    private const val FILE = "media_prayer_playlists"
    private const val KEY_IDS = "favorite_ids"
    private const val KEY_PLAYLISTS = "playlist_names"
    private const val KEY_PLAYLISTS_V2 = "playlist_names_v2"
    private const val PREFIX = "playlist_"
    private const val ORDER_SUFFIX = "_order_v2"

    fun getFavoriteIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()
    }

    fun isFavorite(context: Context, songId: String): Boolean = getFavoriteIds(context).contains(songId)

    fun toggleFavorite(context: Context, songId: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val current = getFavoriteIds(context).toMutableSet()
        val nowFavorite = if (current.contains(songId)) { current.remove(songId); false } else { current.add(songId); true }
        prefs.edit().putStringSet(KEY_IDS, current).apply()
        return nowFavorite
    }

    fun getPlaylistNames(context: Context): List<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PLAYLISTS_V2, null)
        if (stored != null) return decodeNames(stored)

        // Migrate the original StringSet store. StringSet is intentionally not
        // used for the new store because Android does not guarantee stable
        // iteration semantics for it and mutable-set persistence has caused
        // playlist lists to disappear on some preference implementations.
        val legacy = prefs.getStringSet(KEY_PLAYLISTS, emptySet())?.toList().orEmpty()
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (legacy.isNotEmpty() || prefs.contains(KEY_PLAYLISTS)) {
            prefs.edit().putString(KEY_PLAYLISTS_V2, encodeNames(legacy)).apply()
        }
        return legacy
    }

    fun createPlaylist(context: Context, name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank() || clean.length > 60) return false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val names = getPlaylistNames(context).toMutableList()
        if (names.any { it.equals(clean, ignoreCase = true) }) return false
        names.add(clean)
        prefs.edit()
            .putString(KEY_PLAYLISTS_V2, encodeNames(names))
            .putString(PREFIX + clean + ORDER_SUFFIX, "")
            .apply()
        return true
    }

    fun renamePlaylist(context: Context, oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isBlank() || clean.length > 60) return false
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val names = getPlaylistNames(context).toMutableList()
        val actual = names.firstOrNull { it.equals(oldName, ignoreCase = true) } ?: return false
        if (actual.equals(clean, ignoreCase = true)) return actual == clean
        if (names.any { it.equals(clean, ignoreCase = true) }) return false
        val ids = getSongIds(context, actual)
        val index = names.indexOf(actual)
        names[index] = clean
        prefs.edit()
            .remove(PREFIX + actual)
            .remove(PREFIX + actual + ORDER_SUFFIX)
            .putString(PREFIX + clean + ORDER_SUFFIX, encode(ids))
            .putString(KEY_PLAYLISTS_V2, encodeNames(names))
            .apply()
        return true
    }

    fun deletePlaylist(context: Context, name: String): Boolean {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val names = getPlaylistNames(context).toMutableList()
        val actual = names.firstOrNull { it.equals(name, ignoreCase = true) } ?: return false
        names.remove(actual)
        prefs.edit()
            .remove(PREFIX + actual)
            .remove(PREFIX + actual + ORDER_SUFFIX)
            .putString(KEY_PLAYLISTS_V2, encodeNames(names))
            .apply()
        return true
    }

    /** Returns playlist songs in their user-defined order. Migrates legacy StringSet storage on first read. */
    fun getSongIds(context: Context, playlistName: String): List<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val orderedKey = PREFIX + playlistName + ORDER_SUFFIX
        if (prefs.contains(orderedKey)) return decode(prefs.getString(orderedKey, "") ?: "")

        // Legacy StringSet did not guarantee insertion order. Preserve all ids, then move them
        // into the ordered format so every future edit is deterministic.
        val legacy = prefs.getStringSet(PREFIX + playlistName, emptySet())?.toList() ?: emptyList()
        if (legacy.isNotEmpty() || prefs.contains(PREFIX + playlistName)) {
            prefs.edit().putString(orderedKey, encode(legacy)).apply()
        }
        return legacy
    }

    fun contains(context: Context, playlistName: String, songId: String): Boolean = getSongIds(context, playlistName).contains(songId)

    fun toggleSong(context: Context, playlistName: String, songId: String): Boolean {
        val actual = findPlaylist(context, playlistName) ?: return false
        val ids = getSongIds(context, actual).toMutableList()
        val index = ids.indexOf(songId)
        val added = index < 0
        if (added) ids.add(songId) else ids.removeAt(index)
        saveOrder(context, actual, ids)
        return added
    }

    fun addSongs(context: Context, playlistName: String, songIds: Collection<String>): Int {
        val actual = findPlaylist(context, playlistName) ?: return 0
        val ids = getSongIds(context, actual).toMutableList()
        val existing = ids.toHashSet()
        var added = 0
        songIds.forEach { if (it.isNotBlank() && existing.add(it)) { ids.add(it); added++ } }
        if (added > 0) saveOrder(context, actual, ids)
        return added
    }

    fun addSong(context: Context, playlistName: String, songId: String): Boolean = addSongs(context, playlistName, listOf(songId)) == 1

    fun removeSong(context: Context, playlistName: String, songId: String): Boolean {
        val actual = findPlaylist(context, playlistName) ?: return false
        val ids = getSongIds(context, actual).toMutableList()
        val index = ids.indexOf(songId)
        if (index < 0) return false
        ids.removeAt(index)
        saveOrder(context, actual, ids)
        return true
    }

    /** Replaces a playlist's order after a completed drag operation. */
    fun setSongOrder(context: Context, playlistName: String, songIds: List<String>): Boolean {
        val actual = findPlaylist(context, playlistName) ?: return false
        val clean = songIds.filter { it.isNotBlank() }.distinct()
        saveOrder(context, actual, clean)
        return true
    }

    /** Reorders only playable songs while preserving any unavailable ids in their original slots. */
    fun setPlayableSongOrder(context: Context, playlistName: String, playableIdsInOrder: List<String>): Boolean {
        val actual = findPlaylist(context, playlistName) ?: return false
        val original = getSongIds(context, actual)
        val available = original.filter { it in playableIdsInOrder.toSet() }.toSet()
        val ordered = playableIdsInOrder.filter { it in available }.distinct()
        if (available.size != ordered.size) return false
        val iterator = ordered.iterator()
        val merged = original.map { id -> if (id in available) iterator.next() else id }
        saveOrder(context, actual, merged)
        return true
    }

    /** Reorders one playlist item and persists the new order. */
    fun moveSong(context: Context, playlistName: String, fromIndex: Int, toIndex: Int): Boolean {
        val actual = findPlaylist(context, playlistName) ?: return false
        val ids = getSongIds(context, actual).toMutableList()
        if (fromIndex !in ids.indices || toIndex !in ids.indices || fromIndex == toIndex) return false
        val moved = ids.removeAt(fromIndex)
        ids.add(toIndex, moved)
        saveOrder(context, actual, ids)
        return true
    }

    private fun findPlaylist(context: Context, name: String): String? =
        getPlaylistNames(context).firstOrNull { it.equals(name, ignoreCase = true) }

    private fun encodeNames(names: List<String>): String = JSONArray().apply {
        names.forEach { put(it) }
    }.toString()

    private fun decodeNames(value: String): List<String> = try {
        val array = JSONArray(value)
        buildList {
            for (i in 0 until array.length()) {
                val name = array.optString(i).trim()
                if (name.isNotBlank() && none { it.equals(name, ignoreCase = true) }) add(name)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun saveOrder(context: Context, playlistName: String, ids: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(PREFIX + playlistName + ORDER_SUFFIX, encode(ids))
            .remove(PREFIX + playlistName)
            .apply()
    }

    // MediaStore ids are numeric strings, so comma-separated storage is compact and unambiguous.
    private fun encode(ids: List<String>): String = ids.joinToString(",")
    private fun decode(value: String): List<String> = value.split(',').filter { it.isNotBlank() }.distinct()
}
