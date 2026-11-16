package com.example.quest

import android.content.Context

/** Volume du son reçu dans le casque (0..1), mémorisé entre les sessions. */
object VolumeSettings {
    private const val PREFS = "audio"
    private const val KEY_VOLUME = "volume"
    private const val KEY_MUTED = "muted"

    @Volatile var volume = 1f
        private set
    @Volatile var muted = false
        private set

    /** Volume réellement appliqué (0 si coupé). */
    val effectiveVolume get() = if (muted) 0f else volume

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        volume = p.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)
        muted = p.getBoolean(KEY_MUTED, false)
    }

    fun setVolume(context: Context, value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (volume > 0f) muted = false
        save(context)
    }

    fun setMuted(context: Context, value: Boolean) {
        muted = value
        save(context)
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_VOLUME, volume).putBoolean(KEY_MUTED, muted).apply()
    }
}
