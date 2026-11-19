#!/usr/bin/env python3
"""Banners (1280×720 xxxhdpi & 960×540 xxhdpi) en fallback-iconen (192/288/384) voor de uiterlijk-varianten,
met rechte 0°-fotokaarten zonder gekartelde schuine randen.
Draaien (PIL nodig):
    python3 banners.py [src/main/res]
"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/res"
FONT = os.path.join(RES, "font/overpass.ttf")

TITELS = {
    "": {"fotos": "Photos", "album": "Photo album", "herinneringen": "Memories", "familie": "Family photos"},
    "nl": {"fotos": "Foto’s", "album": "Fotoalbum", "herinneringen": "Herinneringen", "familie": "Familiefoto’s"},
    "de": {"fotos": "Fotos", "album": "Fotoalbum", "herinneringen": "Erinnerungen", "familie": "Familienfotos"},
    "fr": {"fotos": "Photos", "album": "Album photo", "herinneringen": "Souvenirs", "familie": "Photos de famille"},
    "es": {"fotos": "Fotos", "album": "Álbum de fotos", "herinneringen": "Recuerdos", "familie": "Fotos familiares"},
    "ru": {"fotos": "Фото", "album": "Фотоальбом", "herinneringen": "Воспоминания", "familie": "Семейные фото"},
}

MIPMAPS = (("mipmap-xhdpi", 192), ("mipmap-xxhdpi", 288), ("mipmap-xxxhdpi", 384))
# Officiële Android TV-bannerresoluties voor 160×90 dp across all TV UI densities
BANNER_SIZES = (
    ("mdpi", (160, 90)),
    ("tvdpi", (213, 120)),
    ("hdpi", (240, 135)),
    ("xhdpi", (320, 180)),
    ("xxhdpi", (480, 270)),
    ("xxxhdpi", (640, 360)),
)


def font(px):
    try:
        f = ImageFont.truetype(FONT, px)
        f.set_variation_by_axes([700])
        return f
    except Exception:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", px)


def verloop(w, h, links, rechts, cx_frac=0.30, cy_frac=0.42):
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
            px[x, y] = tuple(int(links[i] * (1.0 - t) + rechts[i] * t) for i in range(3))
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


def rechte_fotokaart(base, cx, cy, fw, fh, s, lucht_top, lucht_bot, berg1, berg2, bd, zon=True):
    fx, fy = int(cx - fw / 2), int(cy - fh / 2)
    ro, ri = int(24 * s), int(16 * s)
    base = shadow_box(base, fx, fy, fx + fw, fy + fh, ro, blur=max(2, int(16 * s)), alpha=140, dy=max(2, int(9 * s)))
    ImageDraw.Draw(base).rounded_rectangle([fx, fy, fx + fw, fy + fh], radius=ro, fill=(242, 246, 252, 255))
    iw, ih = fw - 2 * bd, fh - 2 * bd
    inner = vgrad(iw, ih, lucht_top, lucht_bot)
    idraw = ImageDraw.Draw(inner)
    if zon:
        sr = int(ih * 0.14)
        idraw.ellipse([int(iw * 0.73) - sr, int(ih * 0.26) - sr, int(iw * 0.73) + sr, int(ih * 0.26) + sr], fill=(255, 246, 206, 255))
    idraw.polygon([(0, ih), (int(iw * 0.35), int(ih * 0.42)), (int(iw * 0.70), ih)], fill=berg1 + (255,))
    idraw.polygon([(int(iw * 0.40), ih), (int(iw * 0.72), int(ih * 0.54)), (iw, ih)], fill=berg2 + (255,))
    base.paste(inner, (fx + bd, fy + bd), round_mask(iw, ih, ri))
    return base


def m_fotos(base, cx, cy, s):
    # Rechte 0°-stapel met kleurtabjes bovenop (geen schuine kartelranden), verticaal gecentreerd rond cy
    w1, h1 = int(170 * s), int(120 * s)
    x0, y0 = int(cx - w1 / 2), int(cy - h1 / 2 - 29.5 * s)
    base = shadow_box(base, x0, y0, x0 + w1, y0 + h1, int(20 * s), blur=max(2, int(10 * s)), alpha=90, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w1, y0 + h1], radius=int(20 * s), fill=(235, 92, 82, 255))

    w2, h2 = int(196 * s), int(134 * s)
    x0, y0 = int(cx - w2 / 2), int(cy - h2 / 2 - 15.5 * s)
    base = shadow_box(base, x0, y0, x0 + w2, y0 + h2, int(22 * s), blur=max(2, int(10 * s)), alpha=100, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w2, y0 + h2], radius=int(22 * s), fill=(248, 185, 62, 255))

    return rechte_fotokaart(base, cx, cy + 8.5 * s, int(222 * s), int(162 * s), s,
                            (112, 174, 248), (186, 218, 255), (29, 41, 74), (49, 66, 108), int(10 * s), zon=True)


def m_album(base, cx, cy, s):
    pw, ph = int(108 * s), int(146 * s)
    gap = int(8 * s)
    for kant in (-1, 1):
        px0 = int(cx + (gap if kant > 0 else -gap - pw))
        py0 = int(cy - ph / 2)
        base = shadow_box(base, px0, py0, px0 + pw, py0 + ph, int(14 * s), blur=max(2, int(10 * s)), alpha=110, dy=max(1, int(6 * s)))
        ImageDraw.Draw(base).rounded_rectangle([px0, py0, px0 + pw, py0 + ph], radius=int(14 * s), fill=(250, 244, 232, 255))
        ltop, lbot = ((112, 174, 248), (186, 218, 255)) if kant < 0 else ((250, 186, 120), (254, 224, 188))
        base = rechte_fotokaart(base, px0 + pw / 2, py0 + ph * 0.43, int(pw * 0.80), int(pw * 0.64), s * 0.45,
                                ltop, lbot, (32, 42, 68), (52, 66, 98), max(2, int(4 * s)), zon=kant > 0)
    ImageDraw.Draw(base).rounded_rectangle([int(cx - 5 * s), int(cy - ph * 0.48), int(cx + 5 * s), int(cy + ph * 0.48)],
                                           radius=int(4 * s), fill=(120, 60, 30, 255))
    return base


def m_herinneringen(base, cx, cy, s):
    base = rechte_fotokaart(base, cx, cy + 6 * s, int(212 * s), int(156 * s), s,
                            (168, 138, 238), (218, 200, 252), (52, 28, 86), (78, 46, 118), int(10 * s), zon=True)
    d = ImageDraw.Draw(base)
    for scx, scy, sr, col in ((cx + 88 * s, cy - 58 * s, 26 * s, (255, 214, 102)), (cx + 114 * s, cy - 22 * s, 12 * s, (255, 236, 180))):
        pts = []
        for i in range(8):
            a = math.pi / 4 * i - math.pi / 2
            rr = sr if i % 2 == 0 else sr * 0.38
            pts.append((scx + rr * math.cos(a), scy + rr * math.sin(a)))
        d.polygon(pts, fill=col)
    return base


def m_familie(base, cx, cy, s):
    # Rechte achterkaart + rechte voorgrondkaart met hart
    w1, h1 = int(196 * s), int(140 * s)
    x0, y0 = int(cx - w1 / 2 - 12 * s), int(cy - h1 / 2 - 16 * s)
    base = shadow_box(base, x0, y0, x0 + w1, y0 + h1, int(22 * s), blur=max(2, int(10 * s)), alpha=100, dy=max(1, int(5 * s)))
    ImageDraw.Draw(base).rounded_rectangle([x0, y0, x0 + w1, y0 + h1], radius=int(22 * s), fill=(154, 216, 178, 255))
    base = rechte_fotokaart(base, cx + 10 * s, cy + 12 * s, int(206 * s), int(150 * s), s,
                            (124, 194, 238), (188, 224, 250), (26, 62, 70), (44, 88, 98), int(10 * s), zon=True)
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


def titel_laag(w, h, titel, x):
    laag = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(laag)
    maat = 504
    while True:
        f = font(int(maat))
        if d.textlength(titel, font=f) <= w - 150 - x or maat < 180:
            break
        maat *= 0.94
    cap_h = -d.textbbox((0, 0), "H", font=f, anchor="ls")[1]
    d.text((x, h / 2 + cap_h / 2), titel, font=f, fill=(255, 255, 255, 255), anchor="ls")
    return laag


def maak_variant(naam, sleutel, banner_naam, links, rechts, motief, titel_x=1720):
    # Supersample both graphics and text at 3840×2160 (6× xxxhdpi / 12× xhdpi) before LANCZOS downsampling
    bw, bh = 3840, 2160
    basis = verloop(bw, bh, links, rechts, cx_frac=0.28, cy_frac=0.46)
    basis = motief(basis, 940, 1080, 4.35)
    for taal, titels in TITELS.items():
        im = basis.copy()
        im.alpha_composite(titel_laag(bw, bh, titels[sleutel], titel_x))
        rgb = im.convert("RGB")
        for dpi, size in BANNER_SIZES:
            map_ = os.path.join(RES, "drawable-%s%s" % (taal + "-" if taal else "", dpi))
            os.makedirs(map_, exist_ok=True)
            rgb.resize(size, Image.LANCZOS).save(os.path.join(map_, banner_naam), optimize=True)

    # Fallback mipmap PNGs (voor launchers zonder anydpi-v26 ondersteuning)
    iw = 768
    ik = verloop(iw, iw, links, rechts, cx_frac=0.35, cy_frac=0.38)
    ik = motief(ik, iw / 2, iw / 2, 2.05).convert("RGB")
    for map_naam, px_maat in MIPMAPS:
        uit = os.path.join(RES, map_naam)
        os.makedirs(uit, exist_ok=True)
        ik.resize((px_maat, px_maat), Image.LANCZOS).save(os.path.join(uit, "ic_%s.png" % naam), optimize=True)


maak_variant("fotos", "fotos", "banner.png", (36, 52, 112), (10, 14, 28), m_fotos, titel_x=1750)
maak_variant("album", "album", "banner_album.png", (122, 52, 18), (14, 8, 4), m_album, titel_x=1660)
maak_variant("herinneringen", "herinneringen", "banner_herinneringen.png", (62, 30, 110), (10, 6, 20), m_herinneringen, titel_x=1680)
maak_variant("familie", "familie", "banner_familie.png", (18, 76, 48), (5, 20, 12), m_familie, titel_x=1660)
print("klaar")
