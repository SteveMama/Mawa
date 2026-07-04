package com.mawa.face.scene

enum class PanelSlot { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Small, low-luminance information card composed by the cloud brain. */
data class ScenePanel(
    val id: String,
    val slot: PanelSlot,
    val eyebrow: String,
    val title: String,
    val detail: String,
    val accent: String = "#8FA6C0",
)
