from PIL import Image
import pixelfont

GW, GH, S = 240, 135, 8           # logical grid -> 1920x1080
W, H = GW * S, GH * S

ns = {}
exec(open("draw6.py").read().split("def render(")[0], ns)
book = ns["px"]                   # 32x32 icon art, transparent bg

BG_TOP  = (33, 36, 43)
BG_BOT  = (15, 16, 21)
GLOW    = (58, 88, 54)
SLIP_L  = (238, 236, 226)
SLIP_D  = (146, 145, 138)
RED     = (206, 46, 46)
RED_D   = (118, 58, 58)
WORD    = (240, 242, 238)
SHADOW  = (14, 22, 15)
TAG     = (126, 150, 122)

grid = [[None] * GW for _ in range(GH)]
def put(x, y, c):
    if 0 <= x < GW and 0 <= y < GH and c is not None:
        grid[y][x] = c

for y in range(GH):
    t = y / (GH - 1)
    col = tuple(int(BG_TOP[i] + (BG_BOT[i] - BG_TOP[i]) * t) for i in range(3))
    for x in range(GW):
        put(x, y, col)

# book: 3x the icon art, left third
BSC = 3
BW = 32 * BSC
BX, BY = 16, (GH - BW) // 2
BCX, BCY = BX + BW // 2, BY + BW // 2

for y in range(GH):
    for x in range(GW):
        d = ((x - BCX) ** 2 + ((y - BCY) * 1.3) ** 2) ** 0.5
        if d < 74:
            k = (1 - d / 74) ** 2 * 0.55
            b = grid[y][x]
            put(x, y, tuple(int(b[i] + (GLOW[i] - b[i]) * k) for i in range(3)))

def slip(ox, oy, w=8, h=6, faded=False):
    base = SLIP_D if faded else SLIP_L
    for y in range(h):
        for x in range(w):
            put(ox + x, oy + y, base)
    for x in range(w):
        put(ox + x, oy - 1, SHADOW); put(ox + x, oy + h, SHADOW)
    for y in range(h):
        put(ox - 1, oy + y, SHADOW); put(ox + w, oy + y, SHADOW)
    for x in range(1, w - 2):
        put(ox + x, oy + 1, RED_D if faded else RED)
    for x in range(1, w - 3):
        put(ox + x, oy + 3, (112, 110, 106) if faded else (176, 172, 160))

# slips drifting up and away from the book
for (sx, sy, fade) in [(70, 14, False), (100, 30, True), (10, 24, True),
                       (86, 110, False), (204, 20, True), (54, 120, True)]:
    slip(sx, sy, faded=fade)

for y in range(32):
    for x in range(32):
        c = book[y][x]
        if c and c[3] != 0:
            for dy in range(BSC):
                for dx in range(BSC):
                    put(BX + x*BSC + dx, BY + y*BSC + dy, c[:3])

TX = 128
pts, w = pixelfont.text_pixels("ERRATA", scale=3)
wy = 44
for (x, y) in pts: put(TX + x + 1, wy + y + 2, SHADOW)
for (x, y) in pts: put(TX + x, wy + y, WORD)

for i, line in enumerate(["RECIPES YOUR", "MODS FORGOT"]):
    pts, w = pixelfont.text_pixels(line, scale=1)
    for (x, y) in pts:
        put(TX + 2 + x, 78 + i * 12 + y, TAG)

img = Image.new("RGB", (GW, GH))
for y in range(GH):
    for x in range(GW):
        img.putpixel((x, y), grid[y][x])
img.resize((W, H), Image.NEAREST).save("errata_gallery_1.png")
print("saved", W, H)
