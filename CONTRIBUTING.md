# Contributing

Issues and pull requests are welcome. This page says how to report a problem and what a pull request should contain. Setting up a build, running the tests and the benchmark is in [docs/development.md](docs/development.md).

## Reporting a problem

- Say which device and Android version you have, which app showed the board, what you did and what you expected.
- For a position that was read wrong, attach what the app's tools give: **Share the last misread screen** (the screen as the app saw it) and **Copy the debug log** (every read with its timing).
- For a crash, the debug log usually says what led to it.

## Pull requests

- One change per pull request where possible. Its description says what changed, why, and how it was checked.
- Each commit leaves the build and the tests passing, and its message says the same three things.
- Keep `core` free of Android: what can be tested on the JVM goes there.
- A change to the recognition says what the benchmark did before and after.
- CI builds the APK and runs the unit tests, the benchmark, the emulator tests and the engine on arm64 hardware. It should pass before a change is merged.

## Licence

ChessOverlay is under the GNU General Public License, version 3 (see [LICENSE](LICENSE)). By sending a pull request you agree that your contribution is shared under the same licence, and the copyright and credit notices stay as they are.
