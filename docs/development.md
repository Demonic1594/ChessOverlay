# Development guide

Building, testing and changing ChessOverlay. What the code does is in [how-it-works.md](how-it-works.md).

## Prerequisites

| Need | For |
|---|---|
| JDK 17 or later | Everything |
| Android SDK: platform 35, build-tools | Building the app, the app's tests |
| Gradle 8.11.1 | Downloaded by `./gradlew` (the wrapper) |
| The chessimg2pos v0.2.0 models (`board.onnx`, `locator.onnx`) | The recognizer's tests and the benchmark |
| Chrome or Chromium, and Python 3 with `python-chess` | Rendering the benchmark boards |
| Python 3 with `onnx` and `onnxruntime` | Quantizing the board model |
| An engine binary | The built-in engine; the engine's tests |

The models come from the chessimg2pos release: `chessimg2pos-v2-onnx.zip` on <https://github.com/mdicio/chessimg2pos/releases/tag/v0.2.0>. Unpack it into a folder and point `MODELS` at it.

## Building

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. Such a build differs from the one CI makes:

- **Recognition models.** CI quantizes the board model and puts both models in `app/src/main/assets/models/`:
  ```sh
  python3 tools/quantize_models.py models app/src/main/assets/models
  ```
  Without them the APK is smaller, and the app downloads the original models, about 170 MB, the first time the **Models** chip is tapped.
- **Engine.** CI builds Smotherfish and copies it to `app/src/main/jniLibs/arm64-v8a/libsmotherfish.so`. Engines ship as `lib*.so` so that Android installs them as executable files. Without one, the app has no engine until an engine file is added.
- **Another ABI.** `-PextraAbi=x86_64` builds for an x86_64 emulator as well; it needs an x86_64 engine at `app/src/main/jniLibs/x86_64/libsmotherfish.so`.

Models, engines and build output are not in the repository (`.gitignore`).

### Signing

A local build is signed with the debug key of the build machine. To sign with the project key, give the keystore and its passwords:

```sh
./gradlew assembleRelease \
  -PsigningStoreFile=keystore.p12 -PsigningStorePassword=... -PsigningKeyAlias=... -PsigningKeyPassword=...
```

The same values can be given as the environment variables `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. The keystore is PKCS12.

## Testing

| Command | Runs | Needs |
|---|---|---|
| `./gradlew :core:test` | `core`'s tests on the JVM: the rules, UCI parsing, game tracking, change detection, decoding, steady lines, the card's layout, the engine supervisor, the debug log; renders previews of the overlay to `core/build/preview/` | Nothing more; `ENGINE_BIN=/path/to/engine` (with `ENGINE_THREADS`, `ENGINE_SECS`) adds the engine code against a real engine |
| `CORE_ONLY=1 ./gradlew :core:test` | The same, without the Android SDK | JDK only |
| `./gradlew testDebugUnitTest` | The app's tests on the JVM | See below |
| `./gradlew connectedDebugAndroidTest` | The app's tests on a device or emulator | See below |
| `python3 tools/compile.py --run` | Compiles all the Kotlin (core, the app, both test sets) with the standalone Kotlin compiler and runs `core`'s tests | JDK only; see below |

### The app's tests on the JVM

`./gradlew testDebugUnitTest` always runs what needs neither models nor engine: UCI parsing; the engine watchdog with a fake engine (a position that kills the engine is skipped); the engine options screen's text; the recognizer's image resizing against Pillow's; and the board's edges on two phone screenshots. The rest is switched on by environment variables and skipped without them:

| Variable | Value | Runs |
|---|---|---|
| `MODELS` | The folder with the original models | The recognizer against chessimg2pos's recorded output (`VisionTest`); a move seen at once with the real recognizer (`FastReadTest`); the tuner (`TunerTest`) |
| `ENGINE` | An engine executable | The engine supervisor against a real engine (`EngineHostTest`); a real analysis (`UciTest`); a board that moved, found again (`RelocateTest`, with `MODELS`) |
| `QMODELS` | The folder with the bundled int8 models | The int8 model against the original on every test image (`QuantizedModelTest`, with `MODELS`); the benchmark on the int8 model |
| `BENCH` | The folder with the benchmark boards | The benchmark (`BenchTest`, with `MODELS`) |

### The app's tests on a device

`./gradlew connectedDebugAndroidTest` installs the app and its test APK on a connected device or emulator and runs `DeviceTest`: the main screen, the position card, the engine options screen, the app icon, the bundled engine analysing on Android, recognition and analysis on a screenshot, the recognizer measured each way of running the models, and a rendering of the overlay.

The tests read the models from `/data/local/tmp/models` on the device:

```sh
adb shell mkdir -p /data/local/tmp/models
adb push models/board.onnx models/locator.onnx /data/local/tmp/models/
adb shell chmod 644 /data/local/tmp/models/board.onnx models/locator.onnx
./gradlew connectedDebugAndroidTest -PextraAbi=x86_64
```

The screenshots the tests take are collected under `app/build/outputs/connected_android_test_additional_output/`; CI pulls them and the device's log into `device-out/`.

### What was checked against python-chess

The rules were cross-checked against python-chess on 500 random games played with the app's own move generator: 76,387 positions with 190 castles and 38 captures en passant, and FEN fields (castling, en passant, the halfmove clock), legal moves and outcomes agree on every position. That check is not in the repository.

## Continuous integration

The workflow **Build APK** (`.github/workflows/build.yml`) runs for pull requests, for pushes to `main`, and by hand.

| Job | Runs on | Does |
|---|---|---|
| `apk` | `ubuntu-latest` | Downloads the models and bundles the quantized one; renders the benchmark boards; runs `:core:test` and `testDebugUnitTest` with the real models and the benchmark; builds Smotherfish for arm64; builds and uploads the APK |
| `engine-arm64` | `ubuntu-24.04-arm` | Runs the engine from that APK on arm64 hardware with the app's engine code, four threads as on a phone |
| `device` | `ubuntu-latest`, an x86_64 emulator with Android 14 | Builds Smotherfish for x86_64 and runs the instrumented tests |

The unit tests run before the engine is built, so a change in the Kotlin shows a few minutes sooner.

### Artifacts

| Artifact | Contents | Kept for |
|---|---|---|
| `chessoverlay-apk` | `app-release.apk` | 30 days |
| `test-reports` | Gradle's test reports (HTML) | 7 days |
| `overlay-previews` | Renderings of the overlay on a phone-sized board, from `core/build/preview/` | 14 days |
| `bench-report` | The benchmark's table, `report.txt` | 30 days |
| `bench-samples` | Four of the benchmark's pictures | 7 days |
| `compile-classpath` | The app's classpaths, for `tools/compile.py` | 30 days |
| `device-test-output` | The emulator's screenshots, the log, the test reports | 14 days |
| `engine-arm64-reports` | The test reports of the arm64 engine job | 7 days |

### Signing in CI

For pushes to `main` and runs started by hand, the APK is signed with the project key (the repository's secrets `ANDROID_KEYSTORE_*`), so a newer build installs over an older one. A pull request's APK is signed with a debug key made on the build machine, a new one each run: its code could print the key. Without the secrets the build falls back to the debug key.

## Benchmark

`tools/bench/gen.py` renders 200 boards from seeded random games, in lichess's board themes and piece sets, with the images lichess itself uses. The first twenty are the board alone in several sizes; the others are phone screens with bars above and below the board. Some are turned for Black and half have coordinates. The same boards come out every time, so nothing of it needs room in the repository. Chrome is needed for the SVG pieces.

`BenchTest` reads them and writes `report.txt`: how many boards are read right, by theme, piece set, orientation, coordinates and kind of picture; how far the corners are off; how long a read takes; and where the corners of every wrong board were.

To run it locally:

```sh
python3 -m venv /tmp/bench
/tmp/bench/bin/pip install "chess==1.11.2"
/tmp/bench/bin/python tools/bench/gen.py build/bench --boards 200
BENCH="$PWD/build/bench" MODELS="$PWD/models" ./gradlew testDebugUnitTest --tests '*BenchTest*'
```

The table is printed and written to `build/bench/report.txt`. With `QMODELS` set too, the int8 model is measured as the app uses it.

### Results

Run 45437232123 (24 November 2026, x86 CI runner):

| Corners | Boards read correctly | Corners within 2 px |
|---|---|---|
| As the locator gives them | 179 / 200 | 49 / 200 |
| Located and refined, as the app reads | 197 / 200 | 198 / 200 |
| Exact | 199 / 200 | |
| Off by up to 1 % of a side | 199 / 200 | |
| Off by up to 2.5 % of a side | 199 / 200 | |

A read took 106 ms (median) with the locator's corners and with them refined. Of the three boards still read wrong with refined corners, one is a position of kings and pawns whose orientation the model gets wrong at exact corners too, and on two the locator returned a tilted box for a board that stands square.

The test fails when fewer than half the boards are read right with refined corners, so a change that breaks recognition cannot pass unnoticed.

## Compiling without the Android SDK

`python3 tools/compile.py` compiles the Kotlin of `core`, of the app and of both of its test sets with the standalone Kotlin compiler and Android's `android.jar`, for where the Android Gradle plugin cannot run (on a phone, say). It downloads about 300 MB under `build/toolchain` once, then takes a few seconds. `--run` also runs `core`'s tests, all but the one that renders previews (it needs fonts).

It needs JDK 17 or later and `tools/compile-classpath.txt`, the app's classpaths as Gradle resolves them. The build workflow writes that file as its artifact `compile-classpath`; copy it there when a dependency changes. The script checks that the code compiles: resources are not compiled and nothing of Android runs.

## Bundling another engine

CI builds Smotherfish from the commit named as `ENGINE_REF` in `.github/workflows/build.yml`, so that a build can be repeated and the APK's engine source is known. To try another branch, tag or commit, run the workflow by hand and give it as `engine_ref`. To move the app to a newer engine, change the commit in the workflow and in `THIRD_PARTY_NOTICES.md` together.

## Versions and releases

`versionName` and `versionCode` are in `app/build.gradle.kts`. There are no GitHub Releases yet: APKs are the `chessoverlay-apk` artifact of a workflow run.

## Documentation images

The images under `docs/images/` are made from what CI renders. The three overlay previews are `middlegame.png`, `black.png` and `mate.png` of the `overlay-previews` artifact, cropped to the card and the board and scaled to 600 px wide. The `app-*.png` images are crops of `positions.png` from `device-test-output`, and `icon.png` is its `icon.png` at 128 px.

## Contributing

- Keep `core` free of Android: what can be tested on the JVM goes there.
- A commit message says what changed, why, and how it was checked. Each commit should leave the build and the tests passing.
- A change to the recognition should say what the benchmark did before and after.
- CI runs on every pull request and should pass before a change is merged.
