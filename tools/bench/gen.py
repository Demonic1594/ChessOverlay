"""Makes the benchmark of board reading: boards as lichess shows them.

Positions come from seeded random games (the same every time). Each is
rendered in one of lichess's board themes and piece sets (the images lichess
itself uses, fetched from its repository) with Chrome, which is needed for
the SVG pieces: as the board alone, or as a phone screen with the board in
it. `expected.json` says what each image shows.

    python3 tools/bench/gen.py OUTDIR [--boards 200] [--chrome google-chrome]

The build workflow runs this before the unit tests and points BenchTest at
the directory, so that the benchmark needs no room in the repository.
"""
import argparse
import concurrent.futures
import dataclasses
import json
import pathlib
import random
import subprocess
import urllib.request

import chess

LILA = "https://raw.githubusercontent.com/lichess-org/lila/master/public/"
# lichess's board themes: its name there, the image, and the colour of its coordinates on dark and on light squares.
THEMES = [
    ("brown", "brown.png", "#f0d9b5", "#946f51"),
    ("blue", "blue.png", "#dee3e6", "#788a94"),
    ("green", "green.png", "#ffffdd", "#6d8753"),
    ("grey", "grey.jpg", "#b8b8b8", "#7d7d7d"),
    ("purple", "purple.png", "#9f90b0", "#7d4a8d"),
    ("ic", "ic.png", "#ececec", "#c1c18e"),
    ("wood", "wood.jpg", "#d8a45b", "#9b4d0f"),
    ("maple", "maple.jpg", "#e8ceab", "#bc7944"),
    ("marble", "marble.jpg", "#93ab91", "#4f644e"),
    ("canvas", "canvas2.jpg", "#d7daeb", "#547388"),
    ("green-plastic", "green-plastic.png", "#f2f9bb", "#59935d"),
    ("blue3", "blue3.jpg", "#d9e0e6", "#315991"),
]
PIECE_SETS = ["cburnett", "merida", "alpha", "chessnut", "pirouetti", "maestro", "fresca", "gioco", "companion", "staunty"]
PIECES = ["wK", "wQ", "wR", "wB", "wN", "wP", "bK", "bQ", "bR", "bB", "bN", "bP"]
FILES = "abcdefgh"
# What is around a board on a phone: lichess's dark app with bars above and below, or a light app.
DARK, LIGHT = "#262421", "#ebebeb"
OPENINGS = [
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
    "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R",
    "r1bq1rk1/pp2bppp/2n1pn2/2pp4/3P1B2/2PBPN2/PP1N1PPP/R2Q1RK1",
    "rnbqkb1r/pppp1ppp/8/4p3/5PP1/8/PPPPP2P/RNBQKBNR",
    "8/8/6R1/3P4/1kP1N3/pP5P/P2B2P1/5K2",
    "4k3/8/8/8/8/8/8/4K2R",
    "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8",
    "r1bqkb1r/pppp1ppp/5n2/4p3/2B1P3/8/PPPP1PPP/RNBQK1NR",
    "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1",
    "8/PPPk4/8/8/8/8/4Kppp/8",
]


@dataclasses.dataclass
class Board:
    file: str
    fen: str            # the placement
    bottom: str         # "white" or "black": the side at the bottom of the picture
    box: list           # the board's rectangle in the picture: x0, y0, x1, y1
    theme: str
    pieces: str
    coords: bool
    phone: bool         # a phone screen with the board in it (else the board alone)
    size: list          # the picture's width and height


def positions(count):
    """`count` placements: some openings and endgames, and positions from seeded random games (captures, checks and
    promotions preferred, so that the material varies)."""
    rng = random.Random(20261124)
    out = list(OPENINGS)
    while len(out) < count:
        board = chess.Board()
        for _ in range(rng.randint(4, 80)):
            moves = list(board.legal_moves)
            if not moves:
                break
            loud = [m for m in moves if board.is_capture(m) or board.gives_check(m) or m.promotion]
            board.push(rng.choice(loud) if loud and rng.random() < 0.4 else rng.choice(moves))
        out.append(board.board_fen())
    return out[:count]


def fetch(url, target):
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(urllib.request.urlopen(url, timeout=60).read())
    return target


def assets(where):
    themes = [(name, fetch(LILA + "images/board/" + f, where / "board" / f), on_dark, on_light) for name, f, on_dark, on_light in THEMES]
    sets = {s: {p: fetch(LILA + f"piece/{s}/{p}.svg", where / "piece" / s / f"{p}.svg") for p in PIECES} for s in PIECE_SETS}
    return themes, sets


def page(b: Board, theme, piece_files, background):
    """The page for a board, laid out as lichess lays one out: the coordinates in the corners of the edge squares."""
    board = chess.Board(b.fen + " w - - 0 1")
    x0, y0, x1, y1 = b.box
    side = x1 - x0
    sq = side / 8
    font = max(9, round(side / 38))
    black = b.bottom == "black"
    _, image, on_dark, on_light = theme
    out = [f'<!doctype html><meta charset="utf-8"><style>html,body{{margin:0;background:{background};overflow:hidden}}'
           f'body{{width:{b.size[0]}px;height:{b.size[1]}px;position:relative;font-family:Roboto,Arial,sans-serif}}'
           f'#board{{position:absolute;left:{x0}px;top:{y0}px;width:{side}px;height:{side}px;'
           f'background:url("{image.as_uri()}");background-size:100% 100%}}'
           f'#board img{{position:absolute;width:{sq:.3f}px;height:{sq:.3f}px}}'
           f'.c{{position:absolute;font-weight:bold;font-size:{font}px;line-height:1}}',
           f'<div id="board">']
    for square, piece in board.piece_map().items():
        file, rank = chess.square_file(square), chess.square_rank(square)
        col, row = (7 - file, rank) if black else (file, 7 - rank)
        svg = piece_files[("w" if piece.color == chess.WHITE else "b") + piece.symbol().upper()]
        out.append(f'<img src="{svg.as_uri()}" style="left:{col * sq:.3f}px;top:{row * sq:.3f}px">')
    out.append("</div>")
    if b.coords:
        # a1 is a dark square; the top-left square of the picture is always a light one.
        for i, letter in enumerate(FILES[::-1] if black else FILES):
            colour = on_light if (i % 2 == 1) == black else on_dark
            out.append(f'<div class="c" style="left:{x0 + i * sq + 3:.1f}px;top:{y1 - font - 4}px;color:{colour}">{letter}</div>')
        for i, rank in enumerate(range(1, 9) if black else range(8, 0, -1)):
            colour = on_light if i % 2 == 0 else on_dark
            out.append(f'<div class="c" style="left:{x1 - font * 0.7 - 3:.1f}px;top:{y0 + i * sq + 3:.1f}px;color:{colour}">{rank}</div>')
    if b.phone:
        ink, card = ("#bababa", "#383732") if background == DARK else ("#4a4a4a", "#ffffff")
        top, bottom = max(12, y0 - 110), y1 + 22
        over = f"position:absolute;left:{x0}px;top:{top}px"
        under = f"position:absolute;left:{x0}px;top:{bottom}px"
        icon = f"width:44px;height:44px;border-radius:50%;background:{card}"
        clock = f"position:absolute;right:{b.size[0] - x1 - 6}px;padding:4px 10px;border-radius:4px;background:{card};font-size:22px;font-weight:bold"
        out.append(f'<div style="position:absolute;left:0;top:0;width:100%;height:100%;color:{ink}">'
                   f'<div style="{over};{icon}"></div><div style="{over};margin-left:56px;margin-top:4px;font-size:17px;font-weight:bold">'
                   f'{"Adjudicate" if black else "Kasparov_62"}</div><div style="{over};margin-left:56px;margin-top:28px;font-size:13px">1500</div>'
                   f'<div style="{clock};top:{top + 6}px">0:37</div>'
                   f'<div style="{under};{icon}"></div><div style="{under};margin-left:56px;margin-top:4px;font-size:17px;font-weight:bold">'
                   f'{"Kasparov_62" if black else "Adjudicate"}</div><div style="{under};margin-left:56px;margin-top:28px;font-size:13px">1482</div>'
                   f'<div style="{clock};top:{bottom + 10}px">0:12</div></div>')
    out.append("</style>")
    return "".join(out)


def boards(count):
    """What to render: the first twenty the board alone in several sizes, the others as a phone screen."""
    rng = random.Random(1124)
    out = []
    for i, fen in enumerate(positions(count)):
        theme = rng.choice(THEMES)[0]
        bottom = "black" if rng.random() < 0.4 else "white"
        coords = rng.random() < 0.5
        if i < 20:
            side = rng.choice([260, 300, 340, 380, 420, 500, 640])
            box, size, phone = [0, 0, side, side], [side, side], False
        else:
            side = rng.choice([560, 620, 680, 740, 800, 860, 920, 1000])
            w, h = 1080, 1920
            box = [rng.randint(0, w - side), rng.randint(130, h - side - 170), 0, 0]
            box[2], box[3] = box[0] + side, box[1] + side
            size, phone = [w, h], True
        out.append(Board(f"b{i:03d}.png", fen, bottom, box, theme, rng.choice(PIECE_SETS), coords, phone, size))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("out", type=pathlib.Path)
    ap.add_argument("--boards", type=int, default=200)
    ap.add_argument("--chrome", default="google-chrome")
    ap.add_argument("--html-only", action="store_true", help="write the pages and expected.json, render nothing")
    args = ap.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    themes, sets = assets(out / "assets")
    items = boards(args.boards)
    rng = random.Random(7)
    for b in items:
        background = DARK if (not b.phone or rng.random() < 0.7) else LIGHT
        (out / b.file.replace(".png", ".html")).write_text(page(b, next(t for t in themes if t[0] == b.theme), sets[b.pieces], background))
    (out / "expected.json").write_text(json.dumps([dataclasses.asdict(b) for b in items], indent=1))
    if args.html_only:
        print("wrote", len(items), "pages to", out)
        return

    def shot(b):
        subprocess.run([args.chrome, "--headless=new", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
                        "--force-device-scale-factor=1", f"--window-size={b.size[0]},{b.size[1]}", "--virtual-time-budget=3000",
                        f"--screenshot={out / b.file}", (out / b.file.replace('.png', '.html')).as_uri()],
                       check=True, capture_output=True, timeout=180)
        return b.file

    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        for name in pool.map(shot, items):
            print("rendered", name)


if __name__ == "__main__":
    main()
