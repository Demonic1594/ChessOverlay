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

/**
 * Whether located corners can be a chess board on a `w` x `h` screen: a
 * near-square (sides within 12% of each other) at least 15% of the
 * screen's shorter side. Anything else is a false find, not a board to read.
 */
fun plausibleBoard(k: List<FloatArray>, w: Int, h: Int): Boolean {
    if (k.size != 4) return false
    val sides = (0 until 4).map { i ->
        val a = k[i]; val b = k[(i + 1) % 4]
        kotlin.math.hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble())
    }
    val mean = sides.average()
    if (mean < 0.15 * minOf(w, h)) return false
    return sides.all { kotlin.math.abs(it - mean) <= 0.12 * mean }
}
