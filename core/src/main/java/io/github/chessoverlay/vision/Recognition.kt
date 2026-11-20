package io.github.chessoverlay.vision

/** A recognised board. Corners are TL, TR, BR, BL in image pixels, as drawn. */
data class Recognition(
    val placement: String,          // FEN placement from White's side
    val blackAtBottom: Boolean,
    val confidence: Float,          // probability of the least certain square
    val orientationConfidence: Float,
    val corners: List<FloatArray>,
    val legalityChanges: Int,
    /** The placement as drawn (top-left square first), whichever way round the board is. */
    val drawn: String = if (blackAtBottom) Fen.flip(placement) else placement,
    /** Each drawn square's class probabilities (Fen.CLASSES order), when the model gave them. */
    val drawnProbs: List<FloatArray>? = null,
    /** The model's probability that Black is at the bottom. */
    val blackBottomP: Float = if (blackAtBottom) orientationConfidence else 1f - orientationConfidence,
)
