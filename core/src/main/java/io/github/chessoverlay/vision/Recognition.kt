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
 * How sure the model is of the whole board: the mean, over the 64 squares,
 * of the probability of what each was read as (the least certain square's
 * alone when the probabilities are not there). A board read at slightly
 * wrong corners has many squares cut across, and this drops with them.
 */
fun Recognition.meanConfidence(): Float {
    val p = drawnProbs ?: return confidence
    if (p.isEmpty()) return confidence
    var sum = 0.0
    for (sq in p) sum += sq.maxOrNull() ?: 0f
    return (sum / p.size).toFloat()
}

/** How far apart two sets of corners are, as a share of the board's side: the largest corner distance. */
fun cornerShift(a: List<FloatArray>, b: List<FloatArray>): Float {
    if (a.size != 4 || b.size != 4) return 1f
    val side = maxOf(a.maxOf { it[0] } - a.minOf { it[0] }, a.maxOf { it[1] } - a.minOf { it[1] }, 1f)
    return (0 until 4).maxOf { i -> kotlin.math.hypot(a[i][0] - b[i][0], a[i][1] - b[i][1]) } / side
}

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
