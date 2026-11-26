package com.sally.glues

import android.content.Context

data class GlueConfig(
    val videoUri: String? = null,
    val muted: Boolean = true,
    /** 1f = video menutupi layar (cover). Rentang 0.5 - 3f. */
    val scale: Float = 1f,
    /** Pergeseran horizontal sebagai fraksi lebar layar (-1..1). */
    val offsetX: Float = 0f,
    /** Pergeseran vertikal sebagai fraksi tinggi layar (-1..1). */
    val offsetY: Float = 0f,
    /** Posisi awal loop dalam milidetik. */
    val startMs: Long = 0L,
    /** Posisi akhir loop dalam milidetik. 0 = sampai akhir video. */
    val endMs: Long = 0L
)

object GlueStore {
    private const val PREFS = "glues_prefs"

    fun load(context: Context): GlueConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return GlueConfig(
            videoUri = p.getString("uri", null),
            muted = p.getBoolean("muted", true),
            scale = p.getFloat("scale", 1f),
            offsetX = p.getFloat("ox", 0f),
            offsetY = p.getFloat("oy", 0f),
            startMs = p.getLong("start", 0L),
            endMs = p.getLong("end", 0L)
        )
    }

    fun save(context: Context, c: GlueConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("uri", c.videoUri)
            .putBoolean("muted", c.muted)
            .putFloat("scale", c.scale)
            .putFloat("ox", c.offsetX)
            .putFloat("oy", c.offsetY)
            .putLong("start", c.startMs)
            .putLong("end", c.endMs)
            .apply()
    }
}
