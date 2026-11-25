# Review brief

For an outside reviewer (a person or a model) asked to find what is wrong
with ChessOverlay. It says what the app is, where the risk is, what is
decided on purpose, and what kind of finding is wanted. Read the code at
<https://github.com/Demonic1594/ChessOverlay> (branch `main`); this brief
is kept with it.

## What the app is

An Android app (Kotlin, minSdk 26, targetSdk 28) that reads a chess board
from the screen of any app (MediaProjection), follows the game move by
move, runs a UCI engine on the position and draws the engine's best moves
as arrows over the board, with a floating card for the evaluation and the
lines. The bundled engine is Smotherfish, a Stockfish fork, started as a
child process. It is meant for analysis, learning and games against
computers; the app shows a fair-play warning and has no other limits.

The users who matter most play blitz and bullet: a move must be seen,
read and analysed within a few hundred milliseconds, every move, for a
whole game, without the overlay freezing, misreading or losing the game.

## How it works (follow this path through the code)

1. `overlay/OverlayService` (foreground service) owns the capture
   (`ScreenSource`, an ImageReader on a VirtualDisplay), the worker thread
   and the auto-mode loop. `OverlayWindows` has the two windows: the board
   layer (`DrawView`, touch-through) and the card (`CardView`).
2. Auto mode polls about 1600 points of the known board every 20-30 ms
   (`vision/ChangeWatcher`). When squares changed and the board has been
   still for 55-80 ms, it reads. Before the capture, the changed squares
   alone often name the move (`BoardTracker.explainChange`, "seen at
   once"): the engine starts on it at once, and the recognizer then
   confirms or corrects it.
3. `vision/Recognizer` (ONNX Runtime): a locator model finds the board
   (two passes), `BoardEdges` sharpens the corners to the board's own
   edges, a board model classifies the 64 squares (int8). Where edges and
   locator disagree, both are read and the surer reading is kept.
4. `vision/BoardTracker` turns readings into a game:
   - `PositionDecoder` finds the position the game can reach (no move, a
     move, a move each, the board turned round) that the model finds most
     likely;
   - `LastMoveHighlight` reads the site's last-move highlight for the side
     to move;
   - it keeps the history (repetitions, a step back to an earlier
     position), castling rights, en passant and the pace of the game;
   - a reading no move explains is held until read the same way twice
     (small changes, or any change read unsurely);
   - `TrackerLog` records each decision.
5. `overlay/Analyst` ties reading and engine together: it starts analyses,
   gates the first lines (`FirstLinesGate`), steadies them
   (`LineStabilizer`), grades moves, and publishes `OverlayState` to the
   windows.
6. `engine/EngineHost` runs the engine on its own thread. It restarts a
   dead engine and steps down settings for a silent one (watchdog). It
   switches from the dot-product build to the plain one on SIGILL, and
   sends `ucinewgame` on a new game. `UciEngine` is the process and its
   reader thread; `PvCollector` assembles whole iterations of MultiPV
   lines and flushes incomplete ones after 120 ms.
7. `chess/Board` is the rules: move generation (checked by perft), FEN,
   SAN.

The `core` module is plain Kotlin with JVM tests (117). The `app` module
has JVM tests that need the models or an engine (skipped otherwise), and
device tests on an emulator. CI also benchmarks the recognizer on 240
rendered boards and runs the engine on real arm64 hardware.

## Decided on purpose (do not report these)

- targetSdk 28: it lets the app run engine binaries it copied itself
  (imported engines, Open Exchange engine apps). It cannot be on Google
  Play because of this, and that is accepted.
- No limits or guards on use beyond the fair-play warning.
- The engine options the user sets (for example `UCI_LimitStrength`,
  `UCI_Elo`) are sent as set, never overridden.
- Smotherfish itself is out of scope (a separate repository).
- Hardcoded English strings (no translation planned), the deprecated
  `startActivityForResult`, the APK size (two engine builds of about 140
  MB each; known).
- The writing style of comments and docs.

## What to look for

Ranked by what hurts users most:

1. **The game lost or wrong.** States `BoardTracker` can get stuck in,
   where the overlay freezes on a position while the game goes on. Wrong
   side to move, wrong castling or en passant reaching the engine. A
   misread accepted as a move. A real move rejected forever.
2. **Concurrency.** Races between the worker thread, the main thread, the
   engine host's thread, the UCI reader and flush threads, and
   ImageReader callbacks. Stale engine lines shown for a new position.
   Deadlocks (locks held while calling out). `@Volatile` used where a lock
   is needed.
3. **Lifecycle and resources.** The service stopped mid-read, the
   projection revoked, rotation, low memory. ImageReader images not
   closed. ONNX sessions or the engine process leaked. Handlers posting
   after destroy.
4. **Engine handling.** UCI protocol mistakes (stop/bestmove ordering,
   isready, options sent at the wrong time). Watchdog misfires. A restart
   loop. Processes left running.
5. **Speed in fast games.** Work on the hot path (the 20-30 ms poll, the
   read, the decoder) that is wasted or blocking; allocations per frame.
6. **Android version quirks** from API 26 to 35 that the code does not
   handle (foreground service types, MediaProjection rules, overlay
   permissions).

## How to report

For each finding:

- **Where:** file and line (or function).
- **What goes wrong:** the concrete inputs, sequence or timing that
  produce the failure, and what the user sees. "Could be a problem" is not
  a finding; a scenario that fails is.
- **Severity:** crash or lost game / wrong analysis / slow / minor.
- **Confidence:** certain (traced through the code) or suspected (needs
  checking), said honestly.
- **Fix:** a suggestion, briefly.

Leave out style, naming, formatting and "consider adding tests" unless a
specific missing test hides a specific bug. Ten real findings are worth
more than fifty guesses. Each finding will be checked against the code
and tests before anything is changed.
