# ChessOverlay

An Android app that reads a chess board from your screen, in any app, runs a
chess engine on it, and draws the result over the board: arrows for the best
moves, the best line as a numbered path, an evaluation bar, the engine's
lines, and a grade for the move just played.

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
  Runtime; its models (about 170 MB) are downloaded once from within the app.
- **Engines**: Smotherfish built in (Stockfish-based); any arm64 UCI engine
  binary you add (Stockfish, Komodo, lc0 with a Maia network set as its
  `WeightsFile`, …); and engine apps that offer engines to chess GUIs (the
  Open Exchange protocol that DroidFish uses).
- **Engine options**: every UCI option the engine declares can be edited,
  including `UCI_LimitStrength` / `UCI_Elo` and Smotherfish's
  `Analysis Elo` and `Smother*` options.
- **Overlay**: arrows for the best 0-5 moves with their scores, the best
  line as numbered moves, evaluation bar, lines panel (1-5 lines), and move
  grades (best ★, good ✓, inaccuracy ?!, mistake ?, blunder ??) in auto mode,
  with Lichess's thresholds.
- **Auto mode**: re-reads the screen every 0.75-5 s and re-analyses when the
  position changes; the side to move follows from which colour just moved.

## Using it

1. Install the APK (see Builds below). It targets Android API 28 on purpose
   (see Notes), so Android may warn that it was built for an older version.
2. Tap **Download** under Recognition models (once, about 170 MB).
3. Pick an engine; **Options** edits its settings.
4. Tap **Initialize**: allow drawing over other apps, then screen capture.
5. Open the app with the board. The bar: ⠿ drag · ▶ analyse now · A auto
   mode · ⇅ side to move (auto / White / Black) · ◐ show/hide · ✕ close.

## Builds

GitHub Actions builds the APK on every push (workflow "Build APK"; artifact
`chessoverlay-apk`). It bundles Smotherfish from the
[Smotherfish](https://github.com/Demonic1594/Smotherfish) branch
`smotherfish` by default; run the workflow by hand to bundle another branch.

Local build: Android SDK (platform 35), JDK 17+, then `./gradlew assembleRelease`.
Put an arm64 engine at `app/src/main/jniLibs/arm64-v8a/libsmotherfish.so`
to bundle it.

## Tests

`./gradlew testDebugUnitTest` runs:
- chess logic (FEN completion, legality checks, move text);
- UCI parsing, and with `ENGINE=/path/to/engine` a real analysis;
- the recognizer against chessimg2pos (Python) on the same images: Pillow's
  resize reproduced to within 1 level, and with `MODELS=/folder/with/onnx`
  the same positions, orientation and board corners (within 2 px).

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
