# Third-party notices

The app's own code is Copyright (C) 2026 Demonic1594, under the GNU General
Public License, version 3 (see [LICENSE](LICENSE)). This page names what the APK contains that is not the app's own
code, and the licence of each.

| Component | Used for | Licence |
|---|---|---|
| chessimg2pos v2 | Recognition: the models, and the method ported to Kotlin | MIT |
| ONNX Runtime | Running the models | MIT |
| Smotherfish (Stockfish) | The built-in engine | GPL-3.0 |
| AndroidX, Material Components | The app's interface | Apache-2.0 |
| Kotlin, kotlinx.coroutines | The app's code | Apache-2.0 |

## chessimg2pos

Board recognition: the method ported to Kotlin in `vision/`, and the models
`board.onnx` and `locator.onnx` in the APK (the board model quantized to int8
by `tools/quantize_models.py`). chessimg2pos v2 by Mattia Dicio,
<https://github.com/mdicio/chessimg2pos>, release v0.2.0.

```
MIT License

Copyright (c) 2025 Marcodicio

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## ONNX Runtime

The models run on ONNX Runtime 1.20.0 for Android
(`com.microsoft.onnxruntime:onnxruntime-android`), Microsoft, under the MIT
License, <https://github.com/microsoft/onnxruntime>. Its `ThirdPartyNotices.txt`
lists the components it is built from.

## Smotherfish and Stockfish

The APK bundles Smotherfish, a fork of Stockfish
(<https://stockfishchess.org>), both under the GNU General Public License,
version 3 (<https://www.gnu.org/licenses/gpl-3.0.html>). CI builds the binary
from <https://github.com/Demonic1594/Smotherfish> at the commit named as
`ENGINE_REF` in `.github/workflows/build.yml`. The source of that commit is
the source of the binary in the APK; when the commit there is changed, change
it here too:

- <https://github.com/Demonic1594/Smotherfish/commit/33bbe36148e42b08e496027a48dc227212845ebd>
  (the head of the branch `smotherfish` on 2026-11-23)

## Android libraries

- AndroidX and Material Components for Android, Apache License 2.0:
  <https://github.com/androidx/androidx>,
  <https://github.com/material-components/material-components-android>.
- The Kotlin standard library and kotlinx.coroutines, Apache License 2.0:
  <https://github.com/JetBrains/kotlin>.

Each of these ships its own notices inside its archive.

The Open Exchange engine discovery follows the protocol of Kalab's chess
engine support library (Apache-2.0); none of its code is in the APK.
