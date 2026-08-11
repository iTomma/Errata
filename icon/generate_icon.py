from PIL import Image, ImageDraw

G = 32
T = (0, 0, 0, 0)
BG_A    = (40, 42, 51, 255)
BG_B    = (26, 27, 34, 255)
OUT     = (28, 19, 13, 255)
COVER_D = (74, 45, 24, 255)
COVER_M = (116, 71, 35, 255)
COVER_L = (146, 92, 47, 255)
PAGE_L  = (247, 241, 224, 255)
PAGE_M  = (228, 218, 193, 255)
PAGE_S  = (198, 186, 156, 255)
INK     = (122, 112, 96, 255)
RED_M   = (206, 46, 46, 255)
RED_L   = (240, 92, 92, 255)

px = [[T] * G for _ in range(G)]
px = [[T for _ in range(G)] for _ in range(G)]
def put(x, y, c):
    if 0 <= x < G and 0 <= y < G:
        px[y][x] = c

# open book: outer top corners sit lower than the centre, so the pages fan
rows = {9: (13, 18), 10: (10, 21), 11: (7, 24)}
for y in range(12, 22):
    rows[y] = (5, 26)
rows[22] = (5, 26)
rows[23] = (6, 25)

SPINE = (15, 16)
for y, (x0, x1) in rows.items():
    for x in range(x0, x1 + 1):
        if y >= 22:                                  # cover board along the bottom
            c = COVER_D if y == 23 else COVER_M
            if y == 22 and x in SPINE: c = COVER_D
        elif x in SPINE:                             # gutter
            c = COVER_D
        else:
            d = min(abs(x - SPINE[0]), abs(x - SPINE[1]))
            c = PAGE_M if d <= 2 else PAGE_L         # shade into the gutter
            if x in (x0, x1) and y >= 12: c = PAGE_S
        put(x, y, c)

# a couple of page edges peeking under the outer margins
for y in (20, 21):
    put(5, y, PAGE_S); put(26, y, PAGE_S)
put(4, 21, COVER_L); put(27, 21, COVER_L)
put(4, 22, COVER_M); put(27, 22, COVER_M)

# ---- text lines ----
LEFT  = range(7, 14)
RIGHT = range(18, 25)
for i, y in enumerate((13, 15, 17, 19)):
    for x in LEFT:
        if x < 13 - (i % 2): put(x, y, INK)
# right page leaves a clean gap under the struck line for the caret
for i, y in enumerate((13, 19)):
    for x in RIGHT:
        if x < 24 - (i % 2): put(x, y, INK)

# ---- the correction ----
# A caret dissolves into noise at the ~96px Modrinth renders at, so the mark is a single
# struck line, overhanging the grey text on both sides so it reads as a strike rather than
# just another line that happens to be red.
for x in range(17, 25):
    put(x, 15, RED_M)
put(17, 15, RED_L)
put(24, 15, RED_L)
for x in RIGHT:
    if x < 24: put(x, 17, INK)

# ---- outline ----
body = {(x, y) for y in range(G) for x in range(G) if px[y][x] != T}
for (x, y) in list(body):
    for dx, dy in ((1,0),(-1,0),(0,1),(0,-1)):
        if (x+dx, y+dy) not in body:
            put(x+dx, y+dy, OUT)

def render(with_bg, size):
    img = Image.new("RGBA", (G, G), T)
    d = ImageDraw.Draw(img)
    if with_bg:
        for y in range(G):
            t = y / (G - 1)
            d.line([(0, y), (G-1, y)],
                   fill=tuple(int(BG_A[i] + (BG_B[i]-BG_A[i])*t) for i in range(4)))
        for (cx, cy) in [(0,0),(G-1,0),(0,G-1),(G-1,G-1)]:
            for dx in range(3):
                for dy in range(3):
                    if dx + dy < 3:
                        img.putpixel((cx + (dx if cx == 0 else -dx),
                                      cy + (dy if cy == 0 else -dy)), T)
    for y in range(G):
        for x in range(G):
            if px[y][x] != T:
                img.putpixel((x, y), px[y][x])
    return img.resize((size, size), Image.NEAREST)

render(True, 512).save("errata_icon_512.png")
render(True, 128).save("errata_icon_128.png")
render(False, 512).save("errata_icon_transparent_512.png")
render(True, 512).resize((96, 96), Image.LANCZOS).resize((384, 384), Image.NEAREST).save("errata_icon_96_preview.png")
print("ok")
