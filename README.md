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
  of 125 MB. (A build without them downloads the originals once.) The
  locator's corners are rarely exact, and now and then they miss a file or
  a rank of the board, so a located board is sharpened to its own edges
  first (`BoardEdges`: on lines across each side, where the picture stops
  being the page around the board; a side that was missed is looked for
  further out, and one with no page beyond it, as on a phone, is taken
  from the board's being square). On 200 boards as lichess shows them
  (`tools/bench`), that reads 197 where the locator's own corners read
  180; of the other three, one's orientation the model gets wrong at exact
  corners too, and two had a tilted box from the locator.
- **Reading speed, measured on the phone**: most of the time of a read is
  the board model's, and which way of running it is fastest differs per
  phone. The first time the overlay starts, it times a read of a board
  with one, two and four threads of ONNX Runtime's own CPU code, with the
  engine searching as it will be then, and keeps the fastest that reads
  the board as the default does. The tools' Test recognition also
  tries XNNPACK and NNAPI (other code: a provider's own, a phone's
  driver), shows the table and keeps the fastest that reads it right;
  should the app then die while reading, it goes back to the default.
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
  33 times a second it compares 49 points per square with the last read,
  with the overlay showing (points under its own drawing are skipped). When
  at least two squares changed and the board has been still for 80 ms it
  reads the board (hiding the arrows for one frame, once per move); a
  capture that no longer matches the still board (a piece started gliding)
  is thrown away, so pieces are never read in flight. It works out the
  exact move played, or both moves when the reply came first, and so the
  side to move. A small change that no move explains must be read the same
  way twice; misreads never replace the analysis. The game is kept while the
  board is away (a notification, another app) and goes on when it is back.
- **Fast enough for bullet**: the squares that changed, and the legal moves,
  usually leave exactly one move (or a move and the reply that came with
  it, a premove) that explains them, including the site's last-move
  highlight going away and a check highlight. That move is analysed the
  moment the board is still, within a few milliseconds and before the board
  recognizer runs; the arrows come back as soon as the clean capture is
  taken, and the recognizer then checks the move on it and corrects it if
  it was wrong (logged). When no single move explains the change, the
  recognizer decides, as before. In a fast game (four moves timed and a
  median 1.5 s or less between them) the board is checked every 20 ms
  instead of 30 and read once it has been still for 55 ms instead of
  80, and the first lines are shown from depth 4, or shallower ones
  after 40 ms.
- **A game is remembered**: in a game the overlay follows move by move,
  the moves are kept. A king or rook that has moved cannot castle any
  more (a rook taken on its square, or one that arrives there later,
  does not bring the right back), so the engine is never offered
  castling it does not have; after a pawn's double step it is told the
  square a capture en passant could land on. Castling out of check, or
  across an attacked square, is not taken for a legal move, so a
  checkmate with the king and a rook still on their squares is said on
  the card instead of leaving it on "Analysing…".
- **Positions to take along**: a finger kept on the floating card copies
  the position's FEN. The app's position card shows the last position the
  overlay saw, its FEN and the engine's line: copy either, open the
  position in lichess's analysis, or keep it; kept positions can be
  shared as text.
- **Steady display**: engine lines are shown as whole iterations (every
  line at one depth), from depth 8 (shallower ones after 150 ms, so an
  engine whose depth is capped by a strength limit is shown too); a change
  of the arrows is shown once two iterations in a row agree on it, at any
  depth, or when it has waited 700 ms (an engine often switches for one
  iteration and back; and its iterations grow long, so a change that stays
  must still arrive). When the move played began one of the engine's lines
  for the position before, the rest of that line is shown at once, dimmed,
  until the new search's own lines come. The same position's updates reach
  the screen about twice a second; next to the depth the card says how often the position
  came up and the number of the move (when the game was followed from its
  start), as far as the card is wide; the card keeps its size from move
  to move, showing the
  last lines faded until the new ones arrive; while a move is read the
  arrows stay away, so the old ones never flash back before the new ones.
  Results of an old position can never land on a new one.
- **Stability**: the engine runs under a supervisor that restarts it if it
  stops (crash, or killed by Android), resends its options and resumes the
  analysis; a position that crashes the engine twice is skipped. An engine
  that runs but says nothing for 6 seconds after being asked to analyse (or
  ends an analysis without a single line) is logged with everything it
  printed, restarted, and if it stays silent run with one thread, then also
  without the smothered-mate prover; the card says so. Errors are shown in
  the overlay and written to a debug log (the tools: Copy the debug log).

## Using it

1. Install the APK (see Builds below). It targets Android API 28 on purpose
   (see Notes), so Android may warn that it was built for an older version.
2. The app has one screen, in four parts. At the top, chips for what the
   overlay needs: **Models** (board recognition: built into CI builds; a
   local build downloads it once), **Overlay** (permission to draw over
   other apps) and **Engine**; tap a chip for what it needs. Under them the
   start button, and the engine with its buttons: options, change, + to
   add one (an arm64 UCI engine file; every UCI option it has can be
   edited, grouped into Strength, Smotherfish, Performance and Other).
3. The **position card** shows the last position the overlay saw, with the
   engine's arrows: copy its FEN or its line, open it in lichess's
   analysis, or keep it; kept positions are listed below it, to share as
   text or to remove.
4. The **settings** are named only; what each does is in the Help dialog
   (the question mark at the top right), and the board above the settings
   shows the drawing with your settings as you change them. They take
   effect the next time the overlay starts.
5. Tap **Start overlay** and allow screen capture, then open the app with
   the board. The floating card: ▶ analyse now · A auto mode · ⇅ side to
   move (auto / White / Black) · ◐ show/hide the arrows · ✕ close; drag it
   by any part; tap the lines to fold them; keep a finger on it to copy
   the position (its FEN) for pasting anywhere.
6. The **tools**: if a read goes wrong, Copy the debug log, and Share the
   last misread screen (the screen a read failed on, as the app saw it).
   If no lines appear: Test the engine runs it with your options for a
   few seconds and says what it did (and logs it). To see how long a read
   takes on this phone: Test recognition.

## Builds

GitHub Actions builds the APK (workflow "Build APK", artifact
`chessoverlay-apk`) each time a pull request changes, on every push to
`main`, and by hand. Take the APK of a run on `main`: it is signed with
the project key, so a newer one installs over an older one. The APK of a
pull request is signed with a debug key made on the build machine, a new
one each run, so those do not install over one another.

It bundles Smotherfish built from one commit of the
[Smotherfish](https://github.com/Demonic1594/Smotherfish) repository, named
as `ENGINE_REF` in `.github/workflows/build.yml` (the build log names it,
and `THIRD_PARTY_NOTICES.md` links its source). To bundle another branch,
tag or commit, run the workflow by hand and give it as `engine_ref`. To
move the app to a newer engine, change the commit in the workflow and in
the notices together.

Local build: Android SDK (platform 35), JDK 17+, then `./gradlew assembleRelease`.
Put an arm64 engine at `app/src/main/jniLibs/arm64-v8a/libsmotherfish.so`
to bundle it. Such a build is signed with the debug key of the build
machine. To sign with the project key, give the keystore and its passwords
(`-PsigningStoreFile=… -PsigningStorePassword=… -PsigningKeyAlias=… -PsigningKeyPassword=…`,
or the environment variables `SIGNING_STORE_FILE` and so on).

The app icon is made by `tools/icon.py`: it writes the adaptive icon's three
vector drawables (background, foreground, and the monochrome layer that
themed icons use) and the status bar's icon from one description, and with
Pillow draws a preview (`python3 tools/icon.py --preview build/icon.png`).

## Tests

The code is split in two modules: `core` (plain Kotlin: chess rules, UCI
parsing, game tracking, reading boards in the light of the game, and the
overlay's layout as plain shapes) and `app` (Android: screen capture,
recognizer, engine processes, views).

`./gradlew :core:test` runs chess logic (legal moves, notation), game
tracking and change detection (including that the overlay's own drawing is
never taken for a move, and a simulation of gliding pieces: read soon after
they stop, never in flight), orientation and game-aware decoding (misread
squares, boards turned round, undo), the game's memory (a step back is
exact; repetitions; the move's number; castling rights, the en passant
square and the halfmove clock across a game, as python-chess counts them),
what `play` refuses, steady lines, the card's layout (nothing runs past it
at any width), the engine watchdog (a fake engine that only answers on one
thread gets restarted until it does; "bestmove (none)" is passed on), the
debug log, and renders
previews of the overlay on a phone-sized board to `core/build/preview/*.png`.
With `ENGINE_BIN=/path/to/engine` (and optionally `ENGINE_THREADS`) it also
runs the app's engine code (EngineHost, UciEngine) against a real engine on
three positions, the way the overlay does.

`./gradlew testDebugUnitTest` runs (on the build machine):
- UCI parsing, and with `ENGINE=/path/to/engine` a real analysis;
- the recognizer against chessimg2pos (Python) on the same images: Pillow's
  resize reproduced to within 1 level, and with `MODELS=/folder/with/onnx`
  the same positions, orientation and board corners (within 2 px), and with
  `QMODELS` the bundled int8 model reading every image as the original does;
- the engine supervisor (a killed engine restarts and the analysis
  finishes; a position that kills it is skipped);
- the board's edges on two phone screenshots (`BoardEdgesScreensTest`; in
  core, `BoardEdgesTest` does the same on drawn boards), and a move seen
  at once with the real recognizer (`FastReadTest`);
- the benchmark of board reading (`BenchTest`; `BENCH` = boards as lichess
  shows them, which the build workflow renders with `tools/bench/gen.py`):
  how many are read right with the locator's corners, with them sharpened
  as the app reads, with exact corners, and with corners off by up to
  2.5 % of a side; the table is the artifact `bench-report`;
- the tuner against the real models (`TunerTest`): every way that runs
  reads the board as the default does, and a way that is not available is
  reported.

CI runs that engine test on a real arm64 machine too (`engine-arm64`: the
engine from the APK, 4 threads as on a phone), and
`connectedDebugAndroidTest` on an x86_64 emulator with an x86_64
build of Smotherfish: the main screen, the engine on Android, recognition and
the whole pipeline on a screenshot, recognition measured each way of
running the models, and renderings of the overlay, of the app icon and of
the Positions card (`device-test-output` artifact). The emulator is
Android 14.

Compiling where the Android Gradle plugin cannot run (on a phone, say):
`python3 tools/compile.py` compiles the Kotlin of core, of the app and of
both of its test sets with the standalone Kotlin compiler and Android's
`android.jar` (about 300 MB under `build/toolchain`, then a few seconds);
`--run` also runs core's tests. It needs JDK 17 or later, and
`tools/compile-classpath.txt`: the app's classpaths as Gradle resolves them.
The build workflow writes them as its artifact `compile-classpath`; copy
that file there when a dependency changes. It checks that the code
compiles: resources are not compiled and nothing of Android runs.

## Notes

- **targetSdk 28**: from API 29 on, Android does not let an app run a file
  it wrote itself, and loading your own engines depends on that (DroidFish
  does the same). Such apps can be sideloaded but not published on Google
  Play.
- Recognition reads the pieces, not the move history. In a game the
  overlay follows move by move, the moves are kept: a king or rook that
  has moved cannot castle any more (the engine is no longer told it can),
  and the engine is told when a capture en passant is possible. A position
  it jumps to (the move list, a game joined late) is known from its pieces
  only: castling is then taken to be possible while king and rook stand on
  their squares, and whether a pawn has just advanced two squares is not
  known. A step back in the move list, or an undo, takes the position as
  the game had it, as far back as the game was followed. In manual mode
  the side to move
  is the side at the bottom unless a king is in check (⇅ overrides it).

## Credits and licences

- chessimg2pos v2 (Mattia Dicio, MIT): recognition models and the method
  ported in `vision/`.
- ONNX Runtime (Microsoft, MIT).
- Smotherfish / Stockfish (GPL-3.0), bundled as a separate executable.
- Open Exchange engine discovery follows the protocol of Kalab's chess
  engine support library (Apache-2.0); the code here is a new implementation.

The repository has no licence file yet. Because the APK bundles a GPL-3.0
engine, distributing the APK means providing that engine's source (the
Smotherfish repository). `THIRD_PARTY_NOTICES.md` lists the licences of
what the APK contains, and the Smotherfish commit each build is made from.
