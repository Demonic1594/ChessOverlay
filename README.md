# ChessOverlay

An Android app that reads a chess board from your screen, in any app, runs a
chess engine on it, and draws the result over the board: arrows for the best
moves, the best line's next moves, a grade for the move just played, and a
floating card with the evaluation and the engine's lines.

Typical use: analyse your own games wherever they are (game archives in
other apps, videos, puzzles, books, screenshots), and collect positions.

## Fair play: your responsibility

ChessOverlay is an analysis tool and has no built-in limits on where it
runs. How you use it is up to you, and so are the consequences.

Using engine assistance during a live game against another person is
cheating. It breaks the fair-play rules of Chess.com, Lichess and every
other chess site and over-the-board event, and they detect it and close
accounts for it. The authors do not support that use and are not
responsible for any penalty, ban or other consequence of misusing this app.

## Features (v1)

- **Board recognition** from the screen: finds the board anywhere on screen,
  reads all 64 squares and which side is at the bottom. Kotlin port of
  [chessimg2pos](https://github.com/mdicio/chessimg2pos) v2 (MIT) on ONNX
  Runtime. The models are built into the APK, the board model quantized to
  int8 (`tools/quantize_models.py`): the same reading as the original on
  every square of 300 benchmark boards, about twice as fast, 38 MB instead
  of 125 MB. (A build without them downloads the originals once.)
- **Reading in the light of the game**: during a game a new position can
  only follow the last one by a move, or a move each, so the most likely
  position the game can reach is taken when the picture allows it (a piece
  hidden under a site's arrow, or misread, cannot vanish or turn into
  another piece). Which way round the board is drawn comes from the model
  checked against the position (pawns and kings stay near their own side);
  a board turned round, by hand or by a site that turns it after every
  move, is the same game; the start position has White to move whichever
  side is at the bottom; turning the phone finds the board again at the new
  screen size. A new board theme mid-game (every square changes colour,
  no piece moves) is read once and the game goes on; a rematch with the
  sides swapped starts a new game the right way round. On benchmarks of 30
  games each: with site arrows and highlights over pieces 930 of 930
  positions right (904 without this); with themes switched mid-game, games
  in brown, blue, grey and purple, and rematches with sides swapped, 930 of
  930.
- **Engines**: Smotherfish built in (Stockfish-based); any arm64 UCI engine
  binary you add (Stockfish, Komodo, lc0 with a Maia network set as its
  `WeightsFile`, …); and engine apps that offer engines to chess GUIs (the
  Open Exchange protocol that DroidFish uses).
- **Engine options**: every UCI option the engine declares can be edited,
  including `UCI_LimitStrength` / `UCI_Elo` and Smotherfish's
  `Analysis Elo` and `Smother*` options.
- **On the board, only arrows**: the best move as one bold blue arrow, the
  next best lines as thin ones in their own colours (knight moves bend like
  an L); the best line's next moves as slim arrows in the colour of the side
  that plays them, numbered in a small disc in a corner of the destination
  square, each side counted on its own (White's 1, 2, 3 … in white discs,
  Black's in dark ones); the last move graded in the opposite corner (best
  ★, good ✓, inaccuracy ?!, mistake ?, blunder ??, Lichess's thresholds).
- **Floating card** with everything else: the buttons, the score (White's
  view) and an evaluation bar, the side to move and depth, and one row per
  engine line in short algebraic notation (checks, mates, disambiguation)
  whose colour chip matches its arrow. It sits next to the board on its own;
  drag it anywhere, tap the lines to fold them.
- **Auto mode** (on by default) follows the screen without flicker: about
  16 times a second it compares 49 points per square with the last read,
  with the overlay showing (points under its own drawing are skipped). When
  at least two squares changed and the board has been still for 120 ms it
  reads the board (hiding the arrows for one frame, once per move); a
  capture that no longer matches the still board (a piece started gliding)
  is thrown away, so pieces are never read in flight. It works out the
  exact move played, or both moves when the reply came first, and so the
  side to move. A small change that no move explains must be read the same
  way twice; misreads never replace the analysis.
- **Steady display**: engine lines are shown as whole iterations (every
  line at one depth), from depth 8; below depth 18 a change of the arrows is
  shown only when two iterations agree (an engine often switches for one
  iteration and back); the same position's updates reach the screen about
  twice a second; the card keeps its size from move to move, showing the
  last lines faded until the new ones arrive; while a move is read the
  arrows stay away, so the old ones never flash back before the new ones.
  Results of an old position can never land on a new one.
- **Stability**: the engine runs under a supervisor that restarts it if it
  stops (crash, or killed by Android), resends its options and resumes the
  analysis; a position that crashes the engine twice is skipped. Errors are
  shown in the overlay and written to a debug log (Settings → Copy debug log).

## Using it

1. Install the APK (see Builds below). It targets Android API 28 on purpose
   (see Notes), so Android may warn that it was built for an older version.
2. Builds from CI carry the recognition models. A local build without them
   asks to **Download** them once (about 170 MB).
3. Pick an engine; **Options** edits its settings.
4. Tap **Initialize**: allow drawing over other apps, then screen capture.
5. Open the app with the board. The card: ▶ analyse now · A auto mode ·
   ⇅ side to move (auto / White / Black) · ◐ show/hide the arrows · ✕ close;
   drag it by any part; tap the lines to fold them.

## Builds

GitHub Actions builds the APK on every push (workflow "Build APK"; artifact
`chessoverlay-apk`). It bundles Smotherfish from the
[Smotherfish](https://github.com/Demonic1594/Smotherfish) branch
`smotherfish` by default; run the workflow by hand to bundle another branch.

Local build: Android SDK (platform 35), JDK 17+, then `./gradlew assembleRelease`.
Put an arm64 engine at `app/src/main/jniLibs/arm64-v8a/libsmotherfish.so`
to bundle it.

## Tests

The code is split in two modules: `core` (plain Kotlin: chess rules, UCI
parsing, game tracking, reading boards in the light of the game, and the
overlay's layout as plain shapes) and `app` (Android: screen capture,
recognizer, engine processes, views).

`./gradlew :core:test` runs chess logic (legal moves, notation), game
tracking and change detection (including that the overlay's own drawing is
never taken for a move, and a simulation of gliding pieces: read soon after
they stop, never in flight), orientation and game-aware decoding (misread
squares, boards turned round, undo), steady lines, the card's layout
(nothing runs past it at any width), and renders previews of the overlay on
a phone-sized board to `core/build/preview/*.png`.

`./gradlew testDebugUnitTest` runs (on the build machine):
- UCI parsing, and with `ENGINE=/path/to/engine` a real analysis;
- the recognizer against chessimg2pos (Python) on the same images: Pillow's
  resize reproduced to within 1 level, and with `MODELS=/folder/with/onnx`
  the same positions, orientation and board corners (within 2 px), and with
  `QMODELS` the bundled int8 model reading every image as the original does;
- the engine supervisor (a killed engine restarts and the analysis
  finishes; a position that kills it is skipped).

CI also runs `connectedDebugAndroidTest` on an x86_64 emulator with an x86_64
build of Smotherfish: the main screen, the engine on Android, recognition and
the whole pipeline on a screenshot, and a rendering of the overlay
(`device-test-output` artifact).

## Notes

- **targetSdk 28**: from API 29 on, Android does not let an app run a file
  it wrote itself, and loading your own engines depends on that (DroidFish
  does the same). Such apps can be sideloaded but not published on Google
  Play.
- Recognition reads the pieces, not the move history: castling rights are
  inferred from king and rook positions, en passant is not known, and in
  manual mode the side to move is the side at the bottom unless a king is
  in check (⇅ overrides it).

## Credits and licences

- chessimg2pos v2 (Mattia Dicio, MIT): recognition models and the method
  ported in `vision/`.
- ONNX Runtime (Microsoft, MIT).
- Smotherfish / Stockfish (GPL-3.0), bundled as a separate executable.
- Open Exchange engine discovery follows the protocol of Kalab's chess
  engine support library (Apache-2.0); the code here is a new implementation.

The repository has no licence file yet. Because the APK bundles a GPL-3.0
engine, distributing the APK means providing that engine's source (the
Smotherfish repository).
