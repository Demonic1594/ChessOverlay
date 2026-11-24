"""Makes the app icon.

The icon is an adaptive icon in three vector drawables (a background, a
foreground and a monochrome layer for themed icons) that this script writes
from one description of the shapes: a chessboard with the best-move arrow
across it. With Pillow it also draws a preview of the result in a round
launcher mask, so the icon can be judged without a device.

    python3 tools/icon.py                          # the drawables only
    python3 tools/icon.py --preview build/icon.png # and a preview
"""
import argparse
import math
import pathlib
import sys

RES = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"

# The adaptive icon's layers are 108 x 108. A launcher shows the middle of them
# (about 86 of the 108 across, the shape of its mask); what matters must be
# inside the circle of diameter 66 about the centre.
BOARD = (24.0, 24.0, 60.0, 18.0)       # x, y, side, corner radius
SQUARE = BOARD[2] / 4
LIGHT, DARK = "#F2F3D8", "#5E9C4C"
ARROW, ARROW_EDGE = "#3B8BEB", "#0B0F16"
TAIL, TIP = (32.0, 76.0), (76.0, 32.0)
SHAFT, HEAD_LEN, HEAD = 4.0, 16.0, 9.5
EDGE = 2.4                            # the arrow's outline, on each side
BG_TOP, BG_BOTTOM = "#1B2433", "#0F1113"


def rounded(x, y, side, r):
    x1, y1 = x + side, y + side
    return (f"M{x + r:g},{y:g} H{x1 - r:g} A{r:g},{r:g} 0 0 1 {x1:g},{y + r:g} "
            f"V{y1 - r:g} A{r:g},{r:g} 0 0 1 {x1 - r:g},{y1:g} H{x + r:g} "
            f"A{r:g},{r:g} 0 0 1 {x:g},{y1 - r:g} V{y + r:g} A{r:g},{r:g} 0 0 1 {x + r:g},{y:g} Z")


def squares():
    """The 16 squares of the board, as two lists of (x, y)."""
    light, dark = [], []
    for row in range(4):
        for col in range(4):
            (light if (row + col) % 2 == 0 else dark).append((BOARD[0] + SQUARE * col, BOARD[1] + SQUARE * row))
    return light, dark


def squares_path(cells):
    return " ".join(f"M{x:g},{y:g} h{SQUARE:g} v{SQUARE:g} h-{SQUARE:g} Z" for x, y in cells)


def arrow(grow=0.0):
    """The best-move arrow from TAIL to TIP, an arrow outline grown by `grow` on every side."""
    dx, dy = TIP[0] - TAIL[0], TIP[1] - TAIL[1]
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy, ux
    shaft, head, head_len = SHAFT + grow, HEAD + grow, HEAD_LEN + grow
    bx, by = TIP[0] - ux * head_len, TIP[1] - uy * head_len
    return [
        (TAIL[0] + nx * shaft, TAIL[1] + ny * shaft),
        (bx + nx * shaft, by + ny * shaft),
        (bx + nx * head, by + ny * head),
        TIP,
        (bx - nx * head, by - ny * head),
        (bx - nx * shaft, by - ny * shaft),
        (TAIL[0] - nx * shaft, TAIL[1] - ny * shaft),
    ]


def polygon_path(points):
    return "M" + " L".join(f"{x:g},{y:g}" for x, y in points) + " Z"


def vector(name, body):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Made by tools/icon.py: change it there, not here. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            + body + '</vector>\n')


def background():
    return vector("ic_launcher_bg", (
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        f'            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="0" android:endY="108"\n'
        f'                android:startColor="{BG_TOP}" android:endColor="{BG_BOTTOM}" />\n'
        '        </aapt:attr>\n'
        '    </path>\n'
    ))


def foreground():
    light, dark = squares()
    x, y, side, r = BOARD
    return vector("ic_launcher_fg", (
        '    <!-- The board, lifted a little off the background. -->\n'
        f'    <path android:pathData="{rounded(x, y + 2.5, side, r)}" android:fillColor="#4D000000" />\n'
        '    <group>\n'
        f'        <clip-path android:pathData="{rounded(x, y, side, r)}" />\n'
        f'        <path android:pathData="{squares_path(light)}" android:fillColor="{LIGHT}" />\n'
        f'        <path android:pathData="{squares_path(dark)}" android:fillColor="{DARK}" />\n'
        '    </group>\n'
        '    <!-- The best move. -->\n'
        f'    <path android:pathData="{polygon_path(arrow())}" android:fillColor="{ARROW}" android:strokeColor="{ARROW_EDGE}"\n'
        f'        android:strokeWidth="{EDGE * 2:g}" android:strokeLineJoin="round" />\n'
    ))


def monochrome():
    """The icon in one colour for themed icons: the light squares solid, the dark ones fainter, and the arrow
    cut out of both so it stays apart from them."""
    light, dark = squares()
    x, y, side, r = BOARD
    hole = polygon_path(arrow(EDGE + 1.2))
    return vector("ic_launcher_mono", (
        '    <group>\n'
        f'        <clip-path android:pathData="{rounded(x, y, side, r)}" />\n'
        f'        <path android:pathData="{squares_path(light)} {hole}" android:fillColor="#FFFFFFFF" android:fillType="evenOdd" />\n'
        f'        <path android:pathData="{squares_path(dark)} {hole}" android:fillColor="#99FFFFFF" android:fillType="evenOdd" />\n'
        f'        <path android:pathData="{polygon_path(arrow())}" android:fillColor="#FFFFFFFF" />\n'
        '    </group>\n'
    ))


ADAPTIVE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Made by tools/icon.py: change it there, not here. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
'''


def write():
    (RES / "drawable/ic_launcher_bg.xml").write_text(background())
    (RES / "drawable/ic_launcher_fg.xml").write_text(foreground())
    (RES / "drawable/ic_launcher_mono.xml").write_text(monochrome())
    (RES / "mipmap-anydpi-v26/ic_launcher.xml").write_text(ADAPTIVE)


def preview(path):
    from PIL import Image, ImageDraw

    scale = 12
    def box(x, y, w, h):
        return [x * scale, y * scale, (x + w) * scale, (y + h) * scale]

    canvas = Image.new("RGBA", (108 * scale, 108 * scale), (0, 0, 0, 0))
    c = ImageDraw.Draw(canvas)
    top, bottom = [tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) for h in (BG_TOP, BG_BOTTOM)]
    for row in range(108 * scale):
        t = row / (108 * scale - 1)
        c.line([(0, row), (108 * scale, row)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)))
    x, y, side, r = BOARD
    c.rounded_rectangle(box(x, y + 2.5, side, side), radius=round(r * scale), fill=(0, 0, 0, 77))
    board = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    b = ImageDraw.Draw(board)
    light, dark = squares()
    for cells, colour in ((light, LIGHT), (dark, DARK)):
        for cx, cy in cells:
            b.rectangle(box(cx, cy, SQUARE, SQUARE), fill=colour)
    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(box(x, y, side, side), radius=round(r * scale), fill=255)
    canvas.alpha_composite(Image.composite(board, Image.new("RGBA", canvas.size), mask))
    c = ImageDraw.Draw(canvas)
    pts = [(px * scale, py * scale) for px, py in arrow()]
    c.line(pts + [pts[0]], fill=ARROW_EDGE, width=round(EDGE * 2 * scale), joint="curve")
    c.polygon(pts, fill=ARROW)

    # As a launcher shows it: a round mask over the middle 86.4 of the 108, on a
    # light and on a dark wallpaper at three sizes; and the monochrome layer as a themed icon.
    def masked(layer: Image.Image) -> Image.Image:
        icon = layer.resize((108 * 4, 108 * 4), Image.LANCZOS)
        mask = Image.new("L", icon.size, 0)
        d = round(86.4 * 4)
        ImageDraw.Draw(mask).ellipse([(icon.width - d) // 2, (icon.height - d) // 2,
                                      (icon.width + d) // 2, (icon.height + d) // 2], fill=255)
        out = Image.new("RGBA", icon.size, (0, 0, 0, 0))
        out.paste(icon, mask=mask)
        return out

    # The monochrome layer in a tint, as a themed icon shows it, on a pale background.
    tint = (0x2A, 0x33, 0x40)
    mono = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    m = ImageDraw.Draw(mono)
    holes = [(px * scale, py * scale) for px, py in arrow(EDGE + 1.2)]
    light, dark = squares()
    for cells, alpha in ((light, 255), (dark, 96)):
        for cx, cy in cells:
            m.rectangle(box(cx, cy, SQUARE, SQUARE), fill=tint + (alpha,))
    m.polygon(holes, fill=(0, 0, 0, 0))
    m.polygon(pts, fill=tint + (255,))
    themed = Image.new("RGBA", canvas.size, (0xE3, 0xE7, 0xED, 255))
    themed.paste(mono, mask=mask)

    colour = masked(canvas)
    themed = masked(themed)
    sheet = Image.new("RGB", (1080, 400), "#E9ECEF")
    sheet.paste(Image.new("RGB", (360, 400), "#171A1F"), (360, 0))
    for column, image in ((0, colour), (1, colour), (2, themed)):
        big = image.resize((220, 220), Image.LANCZOS)
        sheet.paste(big, (column * 360 + 70, 24), big)
        left = 76
        for size in (96, 64, 48):
            piece = image.resize((size, size), Image.LANCZOS)
            sheet.paste(piece, (column * 360 + left, 276), piece)
            left += size + 16
    sheet.save(path)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--preview", help="also draw a preview to this PNG (needs Pillow)")
    ap.add_argument("--no-write", action="store_true", help="do not write the drawables")
    args = ap.parse_args()
    if not args.no_write:
        write()
        print("wrote", RES / "drawable/ic_launcher_fg.xml", "(and the monochrome layer and the adaptive icon)")
    if args.preview:
        preview(args.preview)
        print("wrote", args.preview)
