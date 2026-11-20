package io.github.chessoverlay.vision

/** A recognised board. Corners are TL, TR, BR, BL in image pixels, as drawn. */
data class Recognition(
    val placement: String,          // FEN placement from White's side
    val blackAtBottom: Boolean,
    val confidence: Float,          // probability of the least certain square
    val orientationConfidence: Float,
    val corners: List<FloatArray>,
    val legalityChanges: Int,
)
