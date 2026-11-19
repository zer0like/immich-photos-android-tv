#!/usr/bin/env python3
"""Banners (across all Android TV densities) and fallback icons (192/288/384) for launcher appearance variants,
with straight 0-degree photo cards without jagged slanted edges.
Run (requires PIL / Pillow):
    python3 banners.py [src/main/res]
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/res"
FONT = os.path.join(RES, "font/overpass.ttf")

FONT_CJK = "/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc"
FONT_HI = "/usr/share/fonts/google-noto-vf/NotoSansDevanagari[wght].ttf"

TITLES = {
    "": {"photos": "Photos", "album": "Photo album", "memories": "Memories", "family": "Family photos"},
    "nl": {"photos": "Foto’s", "album": "Fotoalbum", "memories": "Herinneringen", "family": "Familiefoto’s"},
    "de": {"photos": "Fotos", "album": "Fotoalbum", "memories": "Erinnerungen", "family": "Familienfotos"},
    "fr": {"photos": "Photos", "album": "Album photo", "memories": "Souvenirs", "family": "Photos de famille"},
    "es": {"photos": "Fotos", "album": "Álbum de fotos", "memories": "Recuerdos", "family": "Fotos familiares"},
    "ru": {"photos": "Фото", "album": "Фотоальбом", "memories": "Воспоминания", "family": "Семейные фото"},
    "zh": {"photos": "照片", "album": "相册", "memories": "回忆", "family": "家庭照片"},
    "ja": {"photos": "フォト", "album": "フォトアルバム", "memories": "思い出", "family": "家族の写真"},
    "ko": {"photos": "사진", "album": "사진 앨범", "memories": "추억", "family": "가족 사진"},
    "hi": {"photos": "फ़ोटो", "album": "फ़ोटो एल्बम", "memories": "यादें", "family": "पारिवारिक फ़ोटो"},
}

# Language -> (font_path, ttc_index, base_size_on_3840, center_by_ink_bbox)
LANG_STYLE = {
    "zh": (FONT_CJK, 2, 432, True),
    "ja": (FONT_CJK, 0, 432, True),
    "ko": (FONT_CJK, 1, 432, True),
    "hi": (FONT_HI, 0, 428, True),
}

MIPMAPS = (("mipmap-xhdpi", 192), ("mipmap-xxhdpi", 288), ("mipmap-xxxhdpi", 384))
# Official Android TV banner resolutions for 160x90 dp across all TV UI densities
BANNER_SIZES = (
    ("mdpi", (160, 90)),
    ("tvdpi", (213, 120)),
    ("hdpi", (240, 135)),
    ("xhdpi", (320, 180)),
    ("xxhdpi", (480, 270)),
    ("xxxhdpi", (640, 360)),
)


def load_font(px, lang=""):
    path, idx, _, _ = LANG_STYLE.get(lang, (FONT, 0, 504, False))
    try:
        f = ImageFont.truetype(path, px, index=idx)
        f.set_variation_by_axes([700])
        return f
    except Exception:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", px)


def radial_gradient(w, h, left, right, cx_frac=0.30, cy_frac=0.42):
    # Render smooth radial gradient at base grid then bicubic-scale to target size
    gw, gh = min(w, 640), min(h, 360)
    im = Image.new("RGB", (gw, gh))
    px = im.load()
    cx, cy = gw * cx_frac, gh * cy_frac
    max_d = math.hypot(gw * 0.85, gh * 0.85)
    for y in range(gh):
        dy = y - cy
        for x in range(gw):
            d = min(1.0, math.hypot(x - cx, dy) / max_d)
            t = d * d * (3.0 - 2.0 * d)
            px[x, y] = tuple(int(left[i] * (1.0 - t) + right[i] * t) for i in range(3))
    if (gw, gh) != (w, h):
        im = im.resize((w, h), Image.BICUBIC)
    return im.convert("RGBA")


def vgrad(w, h, top, bot):
    im = Image.new("RGBA", (w, h))
    px = im.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(int(top[i] * (1.0 - t) + bot[i] * t) for i in range(3)) + (255,)
        for x in range(w):
            px[x, y] = c
    return im


def round_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w, h], radius=r, fill=255)
    return m


def shadow_box(base, x0, y0, x1, y1, r, blur, alpha, dy):
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([x0, y0 + dy, x1, y1 + dy], radius=r, fill=(4, 8, 22, alpha))
    sh = sh.filter(ImageFilter.GaussianBlur(radius=blur))
    return Image.alpha_composite(base, sh)


def straight_photo_card(base, cx, cy, fw, fh, s, sky_top, sky_bot, mtn1, mtn2, bd, sun=True):
    fx, fy = int(cx - fw / 2), int(cy - fh / 2)
    ro, ri = int(24 * s), int(16 * s)
    base = shadow_box(base, fx, fy, fx + fw, fy + fh, ro, blur=max(2, int(16 * s)), alpha=140, dy=max(2, int(9 * s)))
    ImageDraw.Draw(base).rounded_rectangle([fx, fy, fx + fw, fy + fh], radius=ro, fill=(242, 246, 252, 255))
    iw, ih = fw - 2 * bd, fh - 2 * bd
    inner = vgrad(iw, ih, sky_top, sky_bot)
    idraw = ImageDraw.Draw(inner)
    if sun:
        sr = int(ih * 0.14)
        idraw.ellipse([int(iw * 0.73) - sr, int(ih * 0.26) - sr, int(iw * 0.73) + sr, int(ih * 0.26) + sr], fill=(255, 246, 206, 255))
    idraw.polygon([(0, ih), (int(iw * 0.35), int(ih * 0.42)), (int(iw * 0.70), ih)], fill=mtn1 + (255,))
    idraw.polygon([(int(iw * 0.40), ih), (int(iw * 0.72), int(ih * 0.54)), (iw, ih)], fill=mtn2 + (255,))
    base.paste(inner, (fx + bd, fy + bd), round_mask(iw, ih, ri))
    return base


def draw_photos(base, cx, cy, s):
    # Straight 0-degree stack with colored top tabs (no slanted jagged edges), vertically centered around cy
    w1, h1 = int(170 * s), int(120 * s)
    x0, y0 = int(cx - w1 / 2), int(cy - h1 / 2 - 29.5 * s)
    base = shadow_box(base, x0, y0, x0 + w1, y0 + h1, int(20 * s), blur=max(2, int(10 * s)), alpha=90, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w1, y0 + h1], radius=int(20 * s), fill=(235, 92, 82, 255))

    w2, h2 = int(196 * s), int(134 * s)
    x0, y0 = int(cx - w2 / 2), int(cy - h2 / 2 - 15.5 * s)
    base = shadow_box(base, x0, y0, x0 + w2, y0 + h2, int(22 * s), blur=max(2, int(10 * s)), alpha=100, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w2, y0 + h2], radius=int(22 * s), fill=(248, 185, 62, 255))

    return straight_photo_card(
        base, cx, cy + 8.5 * s, int(222 * s), int(162 * s), s,
        (112, 174, 248), (186, 218, 255), (29, 41, 74), (49, 66, 108), int(10 * s), sun=True,
    )


def draw_album(base, cx, cy, s):
    pw, ph = int(108 * s), int(146 * s)
    gap = int(8 * s)
    for side in (-1, 1):
        px0 = int(cx + (gap if side > 0 else -gap - pw))
        py0 = int(cy - ph / 2)
        base = shadow_box(base, px0, py0, px0 + pw, py0 + ph, int(14 * s), blur=max(2, int(10 * s)), alpha=110, dy=max(1, int(6 * s)))
        ImageDraw.Draw(base).rounded_rectangle([px0, py0, px0 + pw, py0 + ph], radius=int(14 * s), fill=(250, 244, 232, 255))
        ltop, lbot = ((112, 174, 248), (186, 218, 255)) if side < 0 else ((250, 186, 120), (254, 224, 188))
        base = straight_photo_card(
            base, px0 + pw / 2, py0 + ph * 0.43, int(pw * 0.80), int(pw * 0.64), s * 0.45,
            ltop, lbot, (32, 42, 68), (52, 66, 98), max(2, int(4 * s)), sun=side > 0,
        )
    ImageDraw.Draw(base).rounded_rectangle(
        [int(cx - 5 * s), int(cy - ph * 0.48), int(cx + 5 * s), int(cy + ph * 0.48)],
        radius=int(4 * s), fill=(120, 60, 30, 255),
    )
    return base


def draw_memories(base, cx, cy, s):
    base = straight_photo_card(
        base, cx, cy + 6 * s, int(212 * s), int(156 * s), s,
        (168, 138, 238), (218, 200, 252), (52, 28, 86), (78, 46, 118), int(10 * s), sun=True,
    )
    d = ImageDraw.Draw(base)
    for scx, scy, sr, col in ((cx + 88 * s, cy - 58 * s, 26 * s, (255, 214, 102)), (cx + 114 * s, cy - 22 * s, 12 * s, (255, 236, 180))):
        pts = []
        for i in range(8):
            a = math.pi / 4 * i - math.pi / 2
            rr = sr if i % 2 == 0 else sr * 0.38
            pts.append((scx + rr * math.cos(a), scy + rr * math.sin(a)))
        d.polygon(pts, fill=col)
    return base


def draw_family(base, cx, cy, s):
    # Straight back card + straight foreground card with heart
    w1, h1 = int(196 * s), int(140 * s)
    x0, y0 = int(cx - w1 / 2 - 12 * s), int(cy - h1 / 2 - 16 * s)
    base = shadow_box(base, x0, y0, x0 + w1, y0 + h1, int(22 * s), blur=max(2, int(10 * s)), alpha=100, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w1, y0 + h1], radius=int(22 * s), fill=(154, 216, 178, 255))
    base = straight_photo_card(
        base, cx + 10 * s, cy + 12 * s, int(206 * s), int(150 * s), s,
        (124, 194, 238), (188, 224, 250), (26, 62, 70), (44, 88, 98), int(10 * s), sun=True,
    )
    d = ImageDraw.Draw(base)
    hcx, hcy = cx + 82 * s, cy + 52 * s
    for hr, col, dy in ((26 * s, (226, 56, 78), 0), (20 * s, (248, 86, 108), -2 * s)):
        pts = []
        for i in range(200):
            t = 2 * math.pi * i / 200
            hx = 16 * math.sin(t) ** 3
            hy = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
            pts.append((hcx + hx * hr / 16, hcy + dy + hy * hr / 16))
        d.polygon(pts, fill=col)
    return base


def title_layer(w, h, title, x, lang=""):
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    _, _, size, ink_mid = LANG_STYLE.get(lang, (FONT, 0, 504, False))
    while True:
        f = load_font(int(size), lang)
        if d.textlength(title, font=f) <= w - 150 - x or size < 180:
            break
        size *= 0.94
    if ink_mid:
        b = d.textbbox((0, 0), title, font=f, anchor="ls")
        mid_offset = -(b[1] + b[3]) / 2.0
    else:
        mid_offset = -d.textbbox((0, 0), "H", font=f, anchor="ls")[1] / 2.0
    d.text((x, h / 2 + mid_offset), title, font=f, fill=(255, 255, 255, 255), anchor="ls")
    return layer


def make_variant(name, key, banner_name, left, right, motif, title_x=1720):
    # Supersample both graphics and text at 3840x2160 (6x xxxhdpi / 12x xhdpi) before LANCZOS downsampling
    bw, bh = 3840, 2160
    base = radial_gradient(bw, bh, left, right, cx_frac=0.28, cy_frac=0.46)
    base = motif(base, 940, 1080, 4.35)
    for lang, titles in TITLES.items():
        im = base.copy()
        im.alpha_composite(title_layer(bw, bh, titles[key], title_x, lang))
        rgb = im.convert("RGB")
        for dpi, size in BANNER_SIZES:
            folder = os.path.join(RES, "drawable-%s%s" % (lang + "-" if lang else "", dpi))
            os.makedirs(folder, exist_ok=True)
            rgb.resize(size, Image.LANCZOS).save(os.path.join(folder, banner_name), optimize=True)

    # Fallback mipmap PNGs (for launchers without anydpi-v26 support)
    iw = 768
    ic = radial_gradient(iw, iw, left, right, cx_frac=0.35, cy_frac=0.38)
    ic = motif(ic, iw / 2, iw / 2, 2.05).convert("RGB")
    for folder_name, px_size in MIPMAPS:
        out = os.path.join(RES, folder_name)
        os.makedirs(out, exist_ok=True)
        ic.resize((px_size, px_size), Image.LANCZOS).save(os.path.join(out, "ic_%s.png" % name), optimize=True)


make_variant("photos", "photos", "banner.png", (36, 52, 112), (10, 14, 28), draw_photos, title_x=1750)
make_variant("album", "album", "banner_album.png", (122, 52, 18), (14, 8, 4), draw_album, title_x=1660)
make_variant("memories", "memories", "banner_memories.png", (62, 30, 110), (10, 6, 20), draw_memories, title_x=1680)
make_variant("family", "family", "banner_family.png", (18, 76, 48), (5, 20, 12), draw_family, title_x=1660)
print("done")
