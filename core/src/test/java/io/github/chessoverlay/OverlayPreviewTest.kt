package io.github.chessoverlay

import io.github.chessoverlay.chess.Board
import io.github.chessoverlay.engine.PvLine
import io.github.chessoverlay.overlay.Align
import io.github.chessoverlay.overlay.BoardLayer
import io.github.chessoverlay.overlay.BoardStyle
import io.github.chessoverlay.overlay.CardControls
import io.github.chessoverlay.overlay.CardLayer
import io.github.chessoverlay.overlay.CardStyle
import io.github.chessoverlay.overlay.MoveMark
import io.github.chessoverlay.overlay.OverlayState
import io.github.chessoverlay.overlay.Shape
import io.github.chessoverlay.overlay.TextMeasure
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the overlay (board layer and floating card) over a phone-sized
 * mock of a chess app, into app/build/preview/, so the design can be judged
 * without a device. Also checks that no card text runs past the card.
 */
class OverlayPreviewTest {
    private val dp = 2.75f            // a 1080 px wide phone
    private val screenW = 1080
    private val screenH = 2340
    private val boardTop = 640f

    @Test fun previews() {
        val out = File(System.getProperty("preview.dir") ?: "build/preview").apply { mkdirs() }
        // From a game on a phone: 8. Qd3 e5, White to move.
        render(out, "middlegame", "r2qkb2/ppp2p1r/2np1p2/4p2p/3P2bB/2NQ4/PPP2PPP/R3KBNR", true, false, listOf(
            PvLine(1, 22, 967, null, "d3h7 d6d5 f1b5 e5d4 c3e2 d8d7 h7d3 e8c8 h2h3 g4f5".split(" ")),
            PvLine(2, 22, 828, null, "d4e5 h7h6 e5f6 g4e6 g1f3 h6h8 d3d2 d8d7".split(" ")),
            PvLine(3, 22, 820, null, "h2h3 h7h8 h3g4 h5g4 e1c1 e5d4 c3d5 f8g7".split(" ")),
        ), MoveMark(Board.squareOf("e5"), "?", 0xFFE07020.toInt()))
        // Later: 14. Qxc6+ Bd7, a mate in 2.
        render(out, "mate", "r3kb2/p1pb1p1r/1pQp1B2/4P2p/8/2N5/PPP2PPP/R3KBNR", true, false, listOf(
            PvLine(1, 20, null, 2, "c6a8 d7c8 a8c8".split(" ")),
            PvLine(2, 20, 3031, null, "f1b5 a8d8 c6c7 f8e7 c3d5 d8a8".split(" ")),
            PvLine(3, 20, 2062, null, "c6b7 a8c8 c3d5 f8h6 f1d3 d6e5".split(" ")),
        ), null)
        // Black at the bottom, Black to move, a knight move first.
        render(out, "black", "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R", false, true, listOf(
            PvLine(1, 24, -30, null, "g8f6 d2d3 f8c5 c2c3 d7d6 b1d2".split(" ")),
            PvLine(2, 24, -42, null, "f8c5 c2c3 g8f6 d2d4 e5d4".split(" ")),
        ), null)
    }

    private fun render(dir: File, name: String, placement: String, whiteToMove: Boolean, blackAtBottom: Boolean,
                       lines: List<PvLine>, mark: MoveMark?) {
        val img = BufferedImage(screenW, screenH, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val board = Board.fromPlacement(placement)
        mockApp(g, board, blackAtBottom)
        val size = screenW.toFloat()
        val corners = listOf(floatArrayOf(0f, boardTop), floatArrayOf(size, boardTop),
            floatArrayOf(size, boardTop + size), floatArrayOf(0f, boardTop + size))
        val state = OverlayState(corners = corners, blackAtBottom = blackAtBottom, board = board,
            whiteToMove = whiteToMove, lines = lines, lastMove = mark, searching = true)
        val m = AwtMeasure()
        AwtRenderer.draw(g, BoardLayer.build(state, BoardStyle(arrows = 2, pathPlies = 2, dp = dp), m))

        // The floating card just above the board, where the service puts it.
        val cardW = minOf(screenW - 16 * dp, 400 * dp)
        val card = CardLayer.build(state, cardW, CardStyle(dp = dp, lines = 3, arrows = 2), CardControls(auto = true),
            m, collapsed = false)
        val cx = (screenW - cardW) / 2
        val cy = boardTop - card.height - 6 * dp
        g.translate(cx.toDouble(), cy.toDouble())
        AwtRenderer.draw(g, card.shapes)
        g.dispose()
        for (s in card.shapes) if (s is Shape.Text) {
            assertTrue("'${s.text}' runs past the card", s.left >= 0f && s.left + s.width <= cardW + 0.5f)
        }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    /** A dark app screen with a green board and Unicode pieces. */
    private fun mockApp(g: Graphics2D, b: Board, blackAtBottom: Boolean) {
        g.color = Color(0x30, 0x2E, 0x2B)
        g.fillRect(0, 0, screenW, screenH)
        val sq = screenW / 8f
        val font = Font("DejaVu Sans", Font.PLAIN, (sq * 0.82f).toInt())
        for (row in 0 until 8) for (col in 0 until 8) {
            g.color = if ((row + col) % 2 == 0) Color(0xEB, 0xEC, 0xD0) else Color(0x73, 0x95, 0x52)
            g.fill(java.awt.geom.Rectangle2D.Float(col * sq, boardTop + row * sq, sq + 1, sq + 1))
            val square = if (blackAtBottom) row * 8 + (7 - col) else (7 - row) * 8 + col
            val p = b[square]
            if (p == '.') continue
            val glyph = "♚♛♜♝♞♟"["kqrbnp".indexOf(p.lowercaseChar())].toString()
            val gv = font.createGlyphVector(g.fontRenderContext, glyph)
            val bounds = gv.visualBounds
            val shape = gv.getOutline((col * sq + (sq - bounds.width.toFloat()) / 2 - bounds.x.toFloat()),
                (boardTop + row * sq + (sq - bounds.height.toFloat()) / 2 - bounds.y.toFloat()))
            g.color = if (p.isUpperCase()) Color(0xF9, 0xF9, 0xF9) else Color(0x3A, 0x38, 0x36)
            g.fill(shape)
            g.stroke = BasicStroke(sq * 0.03f)
            g.color = if (p.isUpperCase()) Color(0x40, 0x40, 0x40) else Color(0x10, 0x10, 0x10)
            g.draw(shape)
        }
    }

    class AwtMeasure : TextMeasure {
        private val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        private val g = img.createGraphics()
        override fun width(text: String, size: Float, bold: Boolean): Float {
            g.font = font(size, bold)
            return g.fontMetrics.getStringBounds(text, g).width.toFloat()
        }
    }

    object AwtRenderer {
        fun draw(g: Graphics2D, shapes: List<Shape>) {
            for (s in shapes) when (s) {
                is Shape.Poly -> {
                    val p = Path2D.Float()
                    p.moveTo(s.pts[0], s.pts[1])
                    for (i in 2 until s.pts.size step 2) p.lineTo(s.pts[i], s.pts[i + 1])
                    p.closePath()
                    g.color = Color(s.fill, true); g.fill(p)
                    if (s.strokeWidth > 0 && s.stroke != 0) {
                        g.stroke = BasicStroke(s.strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                        g.color = Color(s.stroke, true); g.draw(p)
                    }
                }
                is Shape.Circle -> {
                    val e = Ellipse2D.Float(s.cx - s.r, s.cy - s.r, 2 * s.r, 2 * s.r)
                    g.color = Color(s.fill, true); g.fill(e)
                    if (s.strokeWidth > 0 && s.stroke != 0) {
                        g.stroke = BasicStroke(s.strokeWidth); g.color = Color(s.stroke, true); g.draw(e)
                    }
                }
                is Shape.Box -> {
                    val r = RoundRectangle2D.Float(s.x0, s.y0, s.x1 - s.x0, s.y1 - s.y0, 2 * s.radius, 2 * s.radius)
                    g.color = Color(s.fill, true); g.fill(r)
                    if (s.strokeWidth > 0 && s.stroke != 0) {
                        g.stroke = BasicStroke(s.strokeWidth); g.color = Color(s.stroke, true); g.draw(r)
                    }
                }
                is Shape.Text -> {
                    g.font = font(s.size, s.bold)
                    g.color = Color(s.color, true)
                    g.drawString(s.text, s.left, s.y)
                }
            }
        }
    }

    companion object {
        fun font(size: Float, bold: Boolean): Font =
            Font("DejaVu Sans", if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)
    }
}
