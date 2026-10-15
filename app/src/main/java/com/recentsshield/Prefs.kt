package com.recentsshield

import android.content.Context
import android.content.SharedPreferences
import java.io.File

object Prefs {
    private const val NAME = "shield"
    const val KEY_HIDDEN = "hidden_packages"
    const val KEY_SUMMARY = "last_summary"
    const val KEY_DEBUG_OUTLINE = "debug_outline"
    const val KEY_PERSISTENT_WINDOW = "persistent_window"
    const val KEY_NUDGE = "nudge_window"
    const val KEY_SOFTWARE = "software_draw"
    val DRAWING_KEYS = setOf(KEY_DEBUG_OUTLINE, KEY_PERSISTENT_WINDOW, KEY_NUDGE, KEY_SOFTWARE)
    private const val DUMP_FILE = "last_recents_dump.txt"

    fun get(c: Context): SharedPreferences = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun hidden(c: Context): Set<String> =
        get(c).getStringSet(KEY_HIDDEN, emptySet())?.toSet() ?: emptySet()

    fun setHidden(c: Context, pkgs: Set<String>) {
        get(c).edit().putStringSet(KEY_HIDDEN, HashSet(pkgs)).apply()
    }

    fun summary(c: Context): String? = get(c).getString(KEY_SUMMARY, null)

    fun setSummary(c: Context, s: String) {
        get(c).edit().putString(KEY_SUMMARY, s).apply()
    }

    fun dumpFile(c: Context) = File(c.filesDir, DUMP_FILE)
}
