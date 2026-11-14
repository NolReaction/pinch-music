package music.pinch

import music.pinch.core.GameStats

enum class Scene { Menu, Loading, Playing, Paused, Resuming, Result, Error }

data class ScreenState(
    val scene: Scene = Scene.Menu,
    val preview: Boolean = false,
    val title: String = "Первый ритм",
    val bpm: Int = 120,
    val durationMs: Long = 20_000,
    val lastHitWindowMs: Long = 17_120,
    val positionMs: Long = 0,
    val stats: GameStats? = null,
    val reaction: String? = null,
    val reactionId: Int = 0,
    val countdown: Int? = null,
    val error: String? = null,
    val bestScore: Int? = null,
    val newRecord: Boolean = false,
    val volume: Float = 0.8f,
    val haptics: Boolean = false
)

interface Actions {
    fun start(preview: Boolean)
    fun pause()
    fun resume()
    fun restart()
    fun menu()
    fun tap(uptimeMs: Long)
    fun tapNow()
    fun volume(value: Float)
    fun haptics(value: Boolean)
}
