#!/usr/bin/env python3
"""Banners (640×360) en iconen (192×192 / 288×288 / 384×384) voor de uiterlijk-varianten (activity-aliassen in het manifest),
in één stijl: vloeiend verloop, strakke anti-aliased fotokaartjes met zachte schaduw, grote witte titel per taal.
Draaien (PIL nodig):
    python3 banners.py [src/main/res]
Eigen ontwerpen (geen Immich-logo of ander merk)."""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

S = 6  # supersampling voor scherpe randen zonder kartels
RES = sys.argv[1] if len(sys.argv) > 1 else "src/main/res"
FONT = os.path.join(RES, "font/overpass.ttf")

# titels per taal; "" = de standaardmap (Engels), de rest = taalcode in de mapnaam (drawable-<taal>-nodpi)
TITELS = {
    "": {"fotos": "Photos", "album": "Photo album", "herinneringen": "Memories", "familie": "Family photos"},
    "nl": {"fotos": "Foto’s", "album": "Fotoalbum", "herinneringen": "Herinneringen", "familie": "Familiefoto’s"},
    "de": {"fotos": "Fotos", "album": "Fotoalbum", "herinneringen": "Erinnerungen", "familie": "Familienfotos"},
    "fr": {"fotos": "Photos", "album": "Album photo", "herinneringen": "Souvenirs", "familie": "Photos de famille"},
    "es": {"fotos": "Fotos", "album": "Álbum de fotos", "herinneringen": "Recuerdos", "familie": "Fotos familiares"},
    "ru": {"fotos": "Фото", "album": "Фотоальбом", "herinneringen": "Воспоминания", "familie": "Семейные фото"},
}

MIPMAPS = (("mipmap-xhdpi", 192), ("mipmap-xxhdpi", 288), ("mipmap-xxxhdpi", 384))


def font(px):
    try:
        f = ImageFont.truetype(FONT, px)
        f.set_variation_by_axes([700])
        return f
    except Exception:
        return ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", px)


def verloop(w, h, links, rechts, cx_frac=0.35, cy_frac=0.45):
    """Vloeiende radiale kleurovergang zonder harde verticale overgangen."""
    im = Image.new("RGB", (w, h))
    px = im.load()
    cx, cy = w * cx_frac, h * cy_frac
    max_d = math.hypot(w * 0.85, h * 0.85)
    for y in range(h):
        dy = y - cy
        for x in range(w):
            d = min(1.0, math.hypot(x - cx, dy) / max_d)
            t = d * d * (3.0 - 2.0 * d)
            px[x, y] = tuple(int(links[i] * (1.0 - t) + rechts[i] * t) for i in range(3))
    return im


def fotokaart(w, h, hoek, lucht_top=(148, 192, 248), lucht_bot=(192, 218, 252),
              berg1=(28, 38, 64), berg2=(48, 62, 92), rand=10 * S, zon=True,
              r_buiten=14 * S, r_binnen=7 * S):
    """Witomrand fotokaartje met luchtverloop, zon, bergen en zachte schaduw, supersampled geroteerd."""
    cw, ch = w + 2 * rand, h + 2 * rand
    pad = 20 * S
    kaart = Image.new("RGBA", (cw + 2 * pad, ch + 2 * pad), (0, 0, 0, 0))

    sh = Image.new("RGBA", kaart.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.rounded_rectangle([pad + int(1 * S), pad + int(3 * S), pad + cw + int(1 * S), pad + ch + int(3 * S)],
                         radius=r_buiten, fill=(4, 8, 22, 130))
    sh = sh.filter(ImageFilter.GaussianBlur(radius=4 * S))
    kaart = Image.alpha_composite(kaart, sh)

    d = ImageDraw.Draw(kaart)
    d.rounded_rectangle([pad, pad, pad + cw, pad + ch], radius=r_buiten, fill=(246, 248, 252, 255))

    binnen = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ipx = binnen.load()
    for yy in range(h):
        t = yy / max(1, h - 1)
        c = tuple(int(lucht_top[i] * (1.0 - t) + lucht_bot[i] * t) for i in range(3)) + (255,)
        for xx in range(w):
            ipx[xx, yy] = c
    idraw = ImageDraw.Draw(binnen)
    if zon:
        r = int(h * 0.125)
        sx, sy = int(w * 0.72), int(h * 0.25)
        idraw.ellipse([sx - r, sy - r, sx + r, sy + r], fill=(255, 247, 214, 255))
    idraw.polygon([(0, h), (int(w * 0.34), int(h * 0.42)), (int(w * 0.68), h)], fill=berg1 + (255,))
    idraw.polygon([(int(w * 0.42), h), (int(w * 0.72), int(h * 0.54)), (w, h)], fill=berg2 + (255,))

    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w, h], radius=r_binnen, fill=255)
    kaart.paste(binnen, (pad + rand, pad + rand), mask)
    return kaart.rotate(hoek, resample=Image.BICUBIC, expand=True)


def plaats(basis, laag, cx, cy):
    basis.alpha_composite(laag, (int(cx - laag.width / 2), int(cy - laag.height / 2)))


def ster(d, cx, cy, r, kleur):
    pts = []
    for i in range(8):
        a = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * 0.38
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=kleur)


def hart(d, cx, cy, r, kleur):
    pts = []
    for i in range(200):
        t = 2 * math.pi * i / 200
        x = 16 * math.sin(t) ** 3
        y = -(13 * math.cos(t) - 5 * math.cos(2 * t) - 2 * math.cos(3 * t) - math.cos(4 * t))
        pts.append((cx + x * r / 16, cy + y * r / 16))
    d.polygon(pts, fill=kleur)


def titel_laag(w, h, titel, x):
    """Titel op een transparante laag (supersample), links uitgelijnd op [x]; lettergrootte zó dat hij past."""
    laag = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(laag)
    maat = 78 * S
    while True:
        f = font(int(maat))
        if d.textlength(titel, font=f) <= w - 24 * S - x or maat < 30 * S:
            break
        maat *= 0.94
    d.text((x, h / 2), titel, font=f, fill=(255, 255, 255, 255), anchor="lm")
    return laag.resize((w // S, h // S), Image.LANCZOS)


def bewaar(im, taal, naam):
    map_ = os.path.join(RES, "drawable-%snodpi" % (taal + "-" if taal else ""))
    os.makedirs(map_, exist_ok=True)
    im.convert("RGB").save(os.path.join(map_, naam), optimize=True)


def maak_variant(naam, sleutel, banner_naam, links, rechts, motief, titel_x=295):
    w, h = 640 * S, 360 * S
    basis = verloop(w, h, links, rechts, cx_frac=0.28, cy_frac=0.45).convert("RGBA")
    motief(basis, cx=155 * S, cy=180 * S, schaal=0.85)
    basis_klein = basis.resize((640, 360), Image.LANCZOS)
    for taal, titels in TITELS.items():
        im = basis_klein.copy()
        im.alpha_composite(titel_laag(w, h, titels[sleutel], titel_x * S))
        bewaar(im, taal, banner_naam)

    # Iconen in hoge resolutie (192×S teruggeschaald naar 192/288/384) met nette marges zonder kartels
    iw, ih = 192 * S, 192 * S
    ik_hi = verloop(iw, ih, links, rechts, cx_frac=0.35, cy_frac=0.38).convert("RGBA")
    motief(ik_hi, cx=iw / 2, cy=ih / 2, schaal=0.56)
    ik_hi = ik_hi.convert("RGB")
    for map_naam, px_maat in MIPMAPS:
        uit = os.path.join(RES, map_naam)
        os.makedirs(uit, exist_ok=True)
        ik_hi.resize((px_maat, px_maat), Image.LANCZOS).save(os.path.join(uit, "ic_%s.png" % naam), optimize=True)


def m_fotos(im, cx, cy, schaal=1.0):
    pw, ph, bd = int(156 * S * schaal), int(126 * S * schaal), int(16 * S * schaal)
    ro, ri = int(20 * S * schaal), int(10 * S * schaal)
    c1 = fotokaart(pw, ph, 12, (235, 78, 66), (185, 42, 48), (42, 22, 34), (65, 34, 48), rand=bd, zon=False, r_buiten=ro, r_binnen=ri)
    c2 = fotokaart(pw, ph, 6, (248, 184, 58), (224, 134, 34), (52, 36, 22), (78, 54, 34), rand=bd, zon=False, r_buiten=ro, r_binnen=ri)
    c3 = fotokaart(pw, ph, -4, (148, 192, 248), (192, 218, 252), (28, 38, 64), (48, 62, 92), rand=bd, zon=True, r_buiten=ro, r_binnen=ri)
    plaats(im, c1, cx - 22 * S * schaal, cy - 6 * S * schaal)
    plaats(im, c2, cx + 2 * S * schaal, cy - 2 * S * schaal)
    plaats(im, c3, cx + 26 * S * schaal, cy + 5 * S * schaal)


def m_album(im, cx, cy, schaal=1.0):
    """Opengeslagen album: twee pagina's met elk een foto, ruggetje in het midden."""
    pw, ph = int(146 * S * schaal), int(184 * S * schaal)
    for kant, hoek in ((-1, 6), (1, -6)):
        laag = Image.new("RGBA", (pw + 40 * S, ph + 40 * S), (0, 0, 0, 0))
        ld = ImageDraw.Draw(laag)
        ox = 20 * S
        ld.rounded_rectangle([ox, ox, ox + pw, ox + ph], radius=10 * S, fill=(250, 244, 232, 255))
        laag = laag.rotate(hoek, resample=Image.BICUBIC, expand=True)
        plaats(im, laag, cx + kant * pw * 0.52, cy)
    for kant, hoek, ltop, lbot in (
        (-1, 6, (148, 192, 248), (192, 218, 252)),
        (1, -6, (250, 190, 130), (254, 222, 184)),
    ):
        laag = fotokaart(int(pw * 0.72), int(pw * 0.54), hoek, ltop, lbot,
                         rand=int(7 * S * schaal), zon=kant > 0,
                         r_buiten=int(8 * S * schaal), r_binnen=int(5 * S * schaal))
        plaats(im, laag, cx + kant * pw * 0.52, cy - ph * 0.08)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle([cx - 5 * S * schaal, cy - ph * 0.46, cx + 5 * S * schaal, cy + ph * 0.46],
                        radius=4 * S * schaal, fill=(120, 60, 30, 255))


def m_herinneringen(im, cx, cy, schaal=1.0):
    """Eén fotokaartje, iets gedraaid, met een gouden ster op de hoek."""
    pw, ph = int(190 * S * schaal), int(142 * S * schaal)
    laag = fotokaart(pw, ph, 8, (186, 158, 242), (222, 206, 252), (56, 32, 88), (80, 48, 118),
                     rand=int(14 * S * schaal), r_buiten=int(16 * S * schaal), r_binnen=int(8 * S * schaal))
    plaats(im, laag, cx, cy + 8 * S * schaal)
    d = ImageDraw.Draw(im)
    ster(d, cx + pw * 0.48, cy - ph * 0.46, 34 * S * schaal, (255, 214, 102))
    ster(d, cx + pw * 0.66, cy - ph * 0.14, 16 * S * schaal, (255, 236, 180))


def m_familie(im, cx, cy, schaal=1.0):
    """Twee gestapelde fotokaartjes met een rood hart."""
    pw, ph = int(180 * S * schaal), int(134 * S * schaal)
    c1 = fotokaart(pw, ph, 9, (176, 226, 194), (214, 242, 224), (28, 70, 48), (44, 96, 66),
                   rand=int(14 * S * schaal), zon=False, r_buiten=int(16 * S * schaal), r_binnen=int(8 * S * schaal))
    c2 = fotokaart(pw, ph, -5, (152, 204, 244), (198, 228, 250), (30, 60, 74), (48, 86, 104),
                   rand=int(14 * S * schaal), r_buiten=int(16 * S * schaal), r_binnen=int(8 * S * schaal))
    plaats(im, c1, cx - 14 * S * schaal, cy - 8 * S * schaal)
    plaats(im, c2, cx + 12 * S * schaal, cy + 12 * S * schaal)
    d = ImageDraw.Draw(im)
    hart(d, cx + pw * 0.42, cy + ph * 0.40, 30 * S * schaal, (232, 64, 84))
    hart(d, cx + pw * 0.42, cy + ph * 0.40 - 3 * S * schaal, 24 * S * schaal, (250, 90, 110))


maak_variant("fotos", "fotos", "banner.png", (40, 56, 118), (10, 14, 28), m_fotos, titel_x=325)
maak_variant("album", "album", "banner_album.png", (122, 52, 18), (14, 8, 4), m_album)
maak_variant("herinneringen", "herinneringen", "banner_herinneringen.png", (62, 30, 110), (10, 6, 20), m_herinneringen)
maak_variant("familie", "familie", "banner_familie.png", (18, 76, 48), (5, 20, 12), m_familie)
print("klaar")
