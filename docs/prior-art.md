# Prior art

Projects that read a board from the screen, or draw over other apps,
looked at for what ChessOverlay could learn from them. Ideas taken are
re-implemented here, not copied.

## Chess, from the screen

| Project | What it is | Taken, or noted |
|---|---|---|
| [chess-hint-panel](https://github.com/botstelegram7-cmyk/chess-hint-panel) | Android hint overlay, Stockfish 11 via JNI | Repairs misreads by flipping the least sure squares; ours decodes with the model's probabilities and the game's legal moves instead. Perft tests of its move generator: ChessOverlay has them too (`BoardTest.perftCounts`). |
| [ChessMonger](https://github.com/brandonmathew/chessmonger-android) | Android tracker, Stockfish 17 | Hash sized by the phone's memory (taken). A ring buffer of the tracker's decisions for "it froze here" reports (taken: `TrackerLog`). |
| [opencheps](https://github.com/Sethdsai/opencheps) | Android, template matching after a calibration on the start position | Side to move from material changes; weaker than following legal moves. |
| [duolingo-chess-copilot](https://github.com/risenh/duolingo-chess-copilot) | Android, YOLOv8 classifier | Parks its bubble away from the board. |
| [xiangqi-vision](https://github.com/rhevorn/xiangqi-vision) | Chinese chess over ADB | "Vision only finds what changed; the rules keep the real state; a reading no move explains is never written into the board." The same principle as `BoardTracker`. |
| [ChessAI](https://github.com/joeljibin-lab/ChessAI) | Android prototype | Asks the user to turn highlights off and animations to fast; ChessOverlay reads boards as sites draw them. |
| [Chessray](https://github.com/chessraygg/chessray) | Browser extension and desktop app, YOLOv11 | Its overlay window is left out of its own capture (`setContentProtection`). Turn from the last-move highlight (ChessOverlay does this too). Says itself that premoves are not handled (ChessOverlay holds them: `GameMemoryTest.aPremoveIsHeldUntilTheReply`). |
| [Chessist](https://github.com/imluri/Chessist) | Windows overlay and extension | Overlay left out of capture (`WDA_EXCLUDEFROMCAPTURE`). Keeps one engine per session and its hash across moves, cleared for a new game (taken: `ucinewgame`). |
| [fenshot](https://github.com/scoriiu/fenshot) | Screenshot to FEN in the browser | Reads at two alignments and keeps the surer (taken). Says when a read is unreliable instead of guessing (taken: unsure reads are read twice). |
| [chessimg2pos](https://github.com/mdicio/chessimg2pos) | The models ChessOverlay runs | Same version (0.2.0); its legality fix-up is replaced here by decoding against the game. |
| [DroidFish](https://github.com/peterosterlund2/droidfish) | Android chess GUI | Ships a dot-product engine build picked at run time from the CPU (taken); copies engines through a temporary file (taken). |

## Overlays that read the screen

| Project | What it is | Taken, or noted |
|---|---|---|
| [overlay-translator](https://github.com/ciddwd/overlay-translator) | Android screen translator (MediaProjection, Shizuku, accessibility, root) | Tried `FLAG_SECURE` to keep its overlay out of its captures: MIUI / HyperOS then refuse the whole capture, so it went back to hiding its windows for the moment of a capture, as ChessOverlay does. Handles `onCapturedContentResize` for a single app shared. |
| [GoIV](https://github.com/farkam135/GoIV) | Pokémon GO overlay, MediaProjection and OCR | Watches a few pixels to know when to read, as ChessOverlay's change watcher does. |

## Leaving the overlay out of what is read

Desktop systems let a window be left out of screen captures (Windows
`WDA_EXCLUDEFROMCAPTURE`, Electron `setContentProtection`). Android has
no such thing for a single window: `FLAG_SECURE` blacks the capture out,
or stops it on some phones. What Android 14 and later offer is sharing a
single app: then other apps' windows, ChessOverlay's among them, are not
in the frames. ChessOverlay checks which it got (a probe square) and
reads without hiding its drawing when it is not in the capture.
