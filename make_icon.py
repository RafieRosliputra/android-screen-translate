#!/usr/bin/env python3
"""Buat icon Screen Translate daripada icon asal (Desktop/ai/icon/print processor.webp).
Tukar dua label 'PRINT' / 'PROCESSOR' -> 'SCREEN' / 'TRANSLATE', baki (logo JohorStudio, bingkai) kekal."""
import os
from PIL import Image, ImageDraw, ImageFont

HOME = os.path.expanduser("~")
SRC = f"{HOME}/Desktop/ai/icon/print processor.webp"
PROJ = f"{HOME}/Desktop/ai/claude/projects/screen-translate"
FONT = "/System/Library/Fonts/Supplemental/Arial Black.ttf"

im = Image.open(SRC).convert("RGBA")
assert im.size == (1000, 1000), im.size
d = ImageDraw.Draw(im)

# 1) padam dua label lama (kekalkan bingkai di tepi)
d.rectangle([24, 500, 976, 822], fill=(255, 255, 255, 255))

# 2) lukis label baru
def fit_size(text, max_w):
    lo, hi = 20, 300
    while lo < hi:
        mid = (lo + hi + 1) // 2
        w = ImageFont.truetype(FONT, mid).getlength(text)
        if w <= max_w:
            lo = mid
        else:
            hi = mid - 1
    return lo

size = fit_size("TRANSLATE", 850)
font = ImageFont.truetype(FONT, size)

def label(text, y0, y1, x0=None, x1=None, pad=40):
    w = font.getlength(text)
    if x0 is None:
        cx = 500
        x0, x1 = cx - w / 2 - pad, cx + w / 2 + pad
    d.rectangle([x0, y0, x1, y1], fill=(0, 0, 0, 255))
    bb = d.textbbox((0, 0), text, font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    tx = (x0 + x1) / 2 - tw / 2 - bb[0]
    ty = (y0 + y1) / 2 - th / 2 - bb[1]
    d.text((tx, ty), text, font=font, fill=(255, 255, 255, 255))

label("SCREEN", 520, 654)
label("TRANSLATE", 672, 806, x0=35, x1=965)

# 3) simpan
im.save(f"{PROJ}/icon-512.png")
im.resize((512, 512), Image.LANCZOS).save(f"{PROJ}/icon-512.png")
for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    out = f"{PROJ}/app/src/main/res/mipmap-{name}"
    os.makedirs(out, exist_ok=True)
    im.resize((px, px), Image.LANCZOS).save(f"{out}/ic_launcher.png")
print("icon siap, font size", size)
