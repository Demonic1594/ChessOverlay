package io.github.chessoverlay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.chessoverlay.DebugLog
import io.github.chessoverlay.KeptPositions
import io.github.chessoverlay.R
import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.OverlayService

/**
 * The main screen's position card: the last position the overlay saw (its
 * board with the engine's arrows, its FEN and line), what can be done with
 * it, and the positions kept.
 */
class PositionCard(private val a: AppCompatActivity, private val preview: PreviewBoardView) {
    init { buildActions() }

    /** Redraw the last position if the overlay saw a new one since (called while the screen shows). */
    fun followOverlay() { if (OverlayService.lastPosition !== lastShown) showLast() }

    private fun toast(s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()

    /** What can be done with the last position and with the kept ones. */
    private fun buildActions() {
        val actions = Rows(a.findViewById(R.id.positionActions))
        actions.buttons(listOf("Copy FEN", "Copy line")) { i ->
            val p = OverlayService.lastPosition ?: return@buttons
            if (i == 0) copy("ChessOverlay position", p.fen) else copy("ChessOverlay line", p.text())
        }
        actions.buttons(listOf("Analyse online", "Keep")) { i ->
            val p = OverlayService.lastPosition ?: return@buttons
            if (i == 0) analyseOnTheWeb(p.fen) else { KeptPositions.add(a, p); toast("Kept"); show() }
        }
    }

    /** The position shown by showLast(). */
    private var lastShown: Any? = null

    /** The position the overlay saw last (its board with the engine's arrows, its FEN and line), and the kept ones. */
    fun show() {
        showLast()
        showKept()
    }

    private fun showLast() {
        val p = OverlayService.lastPosition
        lastShown = p
        val board = p?.let { try { Board.fromPlacement(it.fen.split(' ')[0]) } catch (_: Exception) { null } }
        val has = p != null && board != null
        a.findViewById<View>(R.id.positionActions).visibility = if (has) View.VISIBLE else View.GONE
        val fen = a.findViewById<TextView>(R.id.positionFen)
        fen.visibility = if (has) View.VISIBLE else View.GONE
        val title = a.findViewById<TextView>(R.id.positionTitle)
        val detail = a.findViewById<TextView>(R.id.positionDetail)
        if (p != null && board != null) {
            title.text = p.side + if (p.expected) " (expected)" else ""
            detail.text = listOfNotNull(p.moveNumber?.let { "move $it" }, p.score?.let { "eval $it" },
                if (p.depth > 0) "depth ${p.depth}" else null).joinToString(" · ") + (if (p.line.isNotEmpty()) "\n${p.line}" else "")
            fen.text = p.fen
            preview.shown = PreviewBoardView.Shown(board, p.whiteToMove, p.pv.takeIf { it.isNotEmpty() }?.let { PvLine(1, p.depth,
                p.whiteCp?.let { c -> if (p.whiteToMove) c else -c }, p.whiteMate?.let { m -> if (p.whiteToMove) m else -m }, it) })
        } else {
            title.text = "No position yet"
            detail.text = "The overlay's last position appears here."
            preview.shown = null
        }
    }

    /** The positions kept: one row each, and what to do with all of them. */
    private fun showKept() {
        val kept = KeptPositions.all(a)
        val list = a.findViewById<android.widget.LinearLayout>(R.id.kept)
        list.removeAllViews()
        list.visibility = if (kept.isEmpty()) View.GONE else View.VISIBLE
        if (kept.isEmpty()) return
        val rows = Rows(list)
        rows.subhead("Kept (${kept.size})")
        for (k in kept) rows.action(R.drawable.ic_grid, k.note, k.fen) { askAboutKept(k) }
        rows.buttons(listOf("Share all", "Clear")) { i ->
            if (i == 0) a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, KeptPositions.text(kept))
            }, "Share the kept positions"))
            else MaterialAlertDialogBuilder(a)
                .setMessage("Remove the ${kept.size} kept position${if (kept.size == 1) "" else "s"}?")
                .setNegativeButton("Keep them", null)
                .setPositiveButton("Remove") { _, _ -> KeptPositions.clear(a); show() }
                .show()
        }
    }

    private fun askAboutKept(k: KeptPositions.Kept) {
        MaterialAlertDialogBuilder(a)
            .setTitle(k.note)
            .setItems(arrayOf("Copy the FEN", "Analyse online (lichess.org)", "Remove")) { _, which ->
                when (which) {
                    0 -> copy("ChessOverlay position", k.fen)
                    1 -> analyseOnTheWeb(k.fen)
                    else -> { KeptPositions.remove(a, k.fen); show() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun copy(label: String, text: String) {
        a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied")
    }

    /** lichess.org's analysis of a position: its address is the FEN with spaces as underscores. */
    fun analyseOnTheWeb(fen: String) {
        try {
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse("https://lichess.org/analysis/" + fen.replace(' ', '_'))), "Analyse the position"))
        } catch (e: Exception) {
            DebugLog.e("Positions", "no app to open an address", e)
            toast("No browser found")
        }
    }
}
