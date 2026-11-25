# How it works

This page follows a position from the screen to the arrows: what each step does, why it is built the way it is, and where the numbers come from. The code it names is in `core/src/main/java/io/github/chessoverlay/` and `app/src/main/java/io/github/chessoverlay/`.

## The pipeline

```
Screen capture (MediaProjection)                         app: ScreenSource
      |
      v
Locate the board          two passes of a small CNN     app: Recognizer.locate
      |
      v
Refine the edges          the board's own edge          core: BoardEdges
      |
      v
Classify                  64 squares, 13 classes,       app: Recognizer.read
                          and which side is at the bottom
      |
      v
Read in the light of the game                           core: PositionDecoder, BoardTracker
      |
      v
Analyse                  UCI engine, supervised         core: EngineHost, UciEngine
      |
      v
Draw                     arrows on the board,           core: BoardLayer, CardLayer
                          a floating card               app: DrawView, CardView
```

## Modules

The code is split so that everything that can be tested on an ordinary JVM is:

| Module | Package | Contents |
|---|---|---|
| `core` | `chess` | `Board`: the rules (legal moves, castling, en passant), FEN, notation |
| | `engine` | UCI parsing, `UciEngine` (the engine process), `EngineHost` (its supervisor) |
| | `vision` | Reading a board: `BoardEdges`, `Orientation`, `Legality`, `PositionDecoder`, `BoardTracker`, `ChangeWatcher` |
| | `overlay` | What the overlay shows, as plain shapes in pixels: `BoardLayer`, `CardLayer`, `OverlayState` |
| | | `DebugLog` |
| `app` | `overlay` | `OverlayService` (the loop), `Analyst` (screen image to engine request), the two windows, `ScreenSource` |
| | `vision` | `Recognizer` (the models on ONNX Runtime), `Tuner`, `ModelStore` |
| | `engine` | `EngineRegistry`: built-in, imported and Open Exchange engines |
| | | `MainActivity`, `EngineOptionsActivity`, `Prefs`, `KeptPositions`, `DebugCapture`, `ui` |

`core` has no Android in it. The layout of the overlay is computed there as a list of shapes, which is what the unit tests check and what CI renders as previews.

## Reading a board

### Locating

`Recognizer.locate` runs the locator model twice. First the whole screen, padded to a square and resized to 320 x 320, gives a rough box. Then a crop with a margin of 10 % around that box is run through the same model, which sharpens the result. The locator's corners are rarely exact, and now and then the box misses a file or a rank of the board.

### Refining the edges

`BoardEdges.refine` replaces the located box with the board's own edges, found in the picture:

1. The colour of the page around the board is read from a band beyond each side, beyond the range the edge is looked for in. A band that is not one colour (it lies on the board, or on a control) is no page.
2. For each side, 64 lines across it are followed from outside the located edge inwards, each to the first place where the picture differs from the page and goes on differing.
3. The edge is where the largest group of lines agree. Lines whose first pixel already differs say the edge is further off than was looked for, and do not count.
4. The board is square: a side that could not be found lies a side's length from its opposite when that one was found; when neither of two opposite sides has page beyond it (the board spans the width of a phone), both are taken about where the locator had them.
5. A board that reaches both sides of the picture (its first and last pixel columns show squares, not one colour: a phone in portrait) is exactly as wide as the picture. A top or bottom edge found a different distance apart was fooled by something next to the board, such as chess.com's dark strip between the board and the bar above it, and the one further from the locator's edge gives way to the other one plus the picture's width.

When the located box is not square, the locator missed part of the board, and the edges are looked for further out (20 % of a side instead of 7 %). Only a board that stands square in the picture is refined; a tilted one keeps the locator's corners, and what is found must still be about the board the locator gave, or the locator's corners stand.

This step is what makes the difference between the locator's corners and a correct reading: on the CI benchmark the classifier reads 199 of 200 boards with exact corners, but 179 with the locator's.

### Classifying

The board is cropped and resized to 256 x 256 (a perspective warp for a board that is not axis-aligned) and run through the board model. It returns, for each square, a score for each of 13 classes (empty and the twelve kinds of piece), and a score for which side is at the bottom.

`Legality.fix`, a port of chessimg2pos's `legality.py`, then repairs impossible boards on the squares the model is least sure of: a pawn on the first or last rank, a second king, a ninth pawn or a seventeenth piece for one side. The confidence of a reading is the probability of its least certain square.

### Orientation

`Orientation.decide` does not rely on the model's guess alone, which was wrong on 6 of 300 boards on an earlier local benchmark, always when it was unsure. The position is a better clue: pawns and kings stay near their own side, so a board read the wrong way round shows "strain". Clear evidence from the position overrides the model; otherwise a model that is at least 97 % sure is trusted; otherwise the game so far decides; otherwise the position's lean.

### Reading in the light of the game

During a game, the next position can only be the last one, or the last one after a move, or after a move and the reply (a quick reply), on a board that may have been turned round. `PositionDecoder.decode` scores each of those candidates by how likely the model finds each of its squares, under both orientations. The best candidate is the answer unless the plain reading is clearly more likely (by a factor of `e^12` while following a game, `e^6` for a read asked for by hand), and unless the model is sure of disagreeing on more than one square, so that an undo, which really changes two squares, is not taken for "no move".

This is how a piece hidden under a site's arrow or highlight, which reads as an empty square with full confidence, cannot make a piece vanish.

## Following a game

`BoardTracker` keeps the current position and links each new one to it.

### Change detection

For every square the colours at 49 points (a 7 x 7 grid over the inner 70 % of the square) are kept from the last clean read. The overlay compares them with the screen about 33 times a second, skipping the points under its own drawing and under the card. A square counts as *changed* when the average change of its visible points, in the colour channel that changed most, is above 18 levels of 255; it is *blind* when fewer than 5 of its points are visible.

### Moves seen at once

`explainChange` looks at which squares changed and at the legal moves of the position. A move must change its squares, and every changed square must belong to it, to the last move (whose highlight goes away) or to a king (a check highlight). Promotions are left to the recognizer. Usually exactly one move, or a move and the reply that came with it, is left. It is applied at once and analysed before the recognizer runs; the reading of the capture is then judged from the position before it, and corrects the guess if it was wrong.

### Accepting a reading

`accept` passes the reading through `PositionDecoder` when there was a position before, and otherwise takes the plain reading with the orientation from `Orientation.decide`. From the linked positions come the move just played and the side to move. In auto mode (`strict`), a small change that no move explains is most likely a misread, and counts only when the next read sees the same.

### The game's memory

A linked position is the last one after its move, so it keeps the castling rights and the en passant square. The positions of the line followed so far are kept (up to 1,024): a reading that no move explains but that is one of them, a step back in the move list or an undo, is taken as it was then, side to move and all. From the history come the number of the move (when the game was followed from its start position) and how often the position came up. The times of the last moves give the pace of the game: a median of 1.5 s or less between moves makes it a fast game.

When the board leaves the screen, the tracker is reset but keeps the game.

## Auto mode

`OverlayService` runs the loop on its own thread:

1. **No board yet.** Every second, once the whole screen has been still for 300 ms (a board caught in a site's opening animation would be found in the wrong place), the screen is read.
2. **Board known.** Every 30 ms (20 ms in a fast game) the change-detection points are sampled. When at least two squares changed and the board has been still for 80 ms (55 ms in a fast game), a read starts. Squares hidden under the drawing are looked at every 20 s.
3. **The capture.** The drawing is taken off the board and kept off until the capture is taken, so nothing of the old position is captured or flashes back. The card is hidden for the capture only if it lies over the board.
4. **Before the capture is read**, the move the changed squares show is analysed (`explainChange`).
5. **The capture is checked.** If it no longer shows the board as it was when the read was decided (a piece started moving in between), it is thrown away and read again once the board is still.
6. **The read.** `Analyst.look` reads the screen at the known corners. It locates the board again when 12 or more squares differ from the last read (the board itself moved, zoomed or turned), when the reading is not sure enough, or when the position cannot be one a game can reach.
7. **The outcome.** A new position starts the analysis; the same position changes nothing; a misread keeps the last analysis and is tried again after 0.5 s, then 1, 2, 4 and 8 s; after three reads without a board, the board counts as gone and the overlay looks for it again.

The overlay's own drawing never covers the points that are compared, and a piece in flight is never read.

## Analysis

`Analyst` turns a position into one engine request and engine output into what the overlay shows.

- **Lines as whole iterations.** An engine prints one line per MultiPV rank at the end of each depth. `PvCollector` holds the lines of an iteration until the set is whole (all expected ranks in, the next iteration begun, the search ended, or 120 ms of quiet), so depths are never mixed.
- **First lines.** The shallow iterations of a search change their minds every few milliseconds. `FirstLinesGate` holds lines below depth 8 for at most 150 ms (depth 4 and 40 ms in a fast game). They are never dropped: an engine whose depth is capped by a strength limit is shown too.
- **Steady arrows.** `LineStabilizer` shows a change of the arrows (the first move of each line with one, and the best line's next moves) only once two iterations in a row agree on it, at any depth, or after 700 ms. Scores and depth move with the lines they belong to.
- **What the engine expected.** When the move played began one of the engine's lines for the position before, `Continuation.after` takes that line without its first move, with the score turned round for the other side, and shows it dimmed until the new search's own lines come.
- **The grade of the last move.** Once the new position's best line reaches depth 10, the move played is graded by the win chance the mover gave away: the engine's own move is *best*; a loss below 5 points is *good*, below 10 an *inaccuracy*, below 15 a *mistake*, beyond that a *blunder*.

Each update carries the id of its request, so results of an old position never land on a new one. Further updates for the same position reach the windows at most every 450 ms, about twice a second.

## The engine supervisor

`EngineHost` keeps one engine running and serialises every call to it on one thread.

- If the engine dies (a crash, killed by Android, a broken pipe), it is restarted, its options are sent again and the current analysis resumes. A position that crashes it twice is skipped.
- If it says nothing for six seconds after being asked to analyse, or ends an analysis without a single line, what it printed is logged and it is restarted with safer settings: as configured, then restarted, then with one thread, then also without the smothered-mate prover. The card says which.
- An engine line that contains something that is not a move is refused; the lines stop there.

## Drawing

`BoardLayer` and `CardLayer` turn an `OverlayState` into shapes (polygons, circles, text) in screen pixels, and `ShapeRenderer` paints them.

- The board layer is a full-screen, touch-through window that draws on the board only: arrows, the numbered path and the grade. It redraws only when the drawing really changes, not on every engine update.
- The card is a second window with the buttons, the evaluation bar and the lines. It is laid out from its content and keeps its size from move to move.
- Both say which screen pixels they cover. That cover, and the cover of the drawing before the current one for 500 ms (a captured frame may still show it), is left out of the change detection.

## Measuring the phone

Which way of running the models is fastest differs per phone. At the overlay's first start, `Tuner` times a read of a sample board with the default (ONNX Runtime's CPU code, two threads), then with one and four threads, then the default again. A way is kept only if it reads the board as the default does and is at least 10 % faster than the default's better time. **Test recognition** does the same with every way, XNNPACK and NNAPI included, and shows the table. If the app dies while reading with a chosen way, it is not used again.

## Threads

| Thread | Does |
|---|---|
| Main | The views and the windows |
| `overlay-worker` | Polls the screen, reads the board, starts analyses |
| The engine host's thread | Every call to the engine |
| The engine's reader thread | Receives the engine's output |
| `first-lines` | Releases held first lines and expired arrow changes |

Every handler on the worker is guarded: an error becomes a message on the card and a line in the debug log, not a crash.
