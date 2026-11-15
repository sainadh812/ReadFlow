#!/usr/bin/env python3
"""Build Play artwork from shipped vectors, theme colors, and real captures.

Requires Pillow. No network access, generated app UI, or public upload.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import PIL
from PIL import Image, ImageDraw, ImageFont, ImageOps


ROOT = Path(__file__).resolve().parents[1]
ICON = ROOT / "app/src/main/res/drawable/ic_readflow.xml"
THEME = ROOT / "app/src/main/java/app/readflow/ui/AppThemeUi.kt"
SERIF = ROOT / "app/src/main/res/font/source_serif.ttf"
SANS = ROOT / "app/src/main/res/font/noto_sans.ttf"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SCALE = 3


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def provenance(path: Path, kind: str) -> dict:
    try:
        name = str(path.resolve().relative_to(ROOT))
    except ValueError:
        name = path.name
    return {"path": name, "sha256": sha256(path), "kind": kind}


def theme_colors() -> dict[str, str]:
    block = THEME.read_text().split("AppTheme.WARM_LIBRARY -> lightColorScheme(", 1)[1]
    block = block.split("AppTheme.MODERN_FOCUS", 1)[0]
    colors = dict(re.findall(r"(\w+)\s*=\s*Color\(0xFF([0-9A-F]{6})\)", block))
    return {key: "#" + value for key, value in colors.items()}


def android_color(value: str) -> tuple[int, int, int, int]:
    value = value.removeprefix("#")
    if len(value) == 6:
        value = "FF" + value
    if len(value) != 8:
        raise ValueError("Only literal Android RGB/ARGB colors are supported")
    alpha, red, green, blue = (int(value[i:i + 2], 16) for i in range(0, 8, 2))
    return red, green, blue, alpha


def vector_polygons(data: str) -> list[list[tuple[float, float]]]:
    """Faithfully read the shipped icon's closed straight-line path commands.

    Fail on new curve/arc commands instead of silently approximating a new icon.
    """
    token_re = r"[MmLlHhVvZz]|[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?"
    residue = re.sub(token_re, "", data).replace(",", "").strip()
    if residue:
        raise ValueError("Icon contains unsupported vector path commands")
    tokens = re.findall(token_re, data)
    polygons, points = [], []
    x = y = 0.0
    command = None
    i = 0
    while i < len(tokens):
        if tokens[i].isalpha():
            command = tokens[i]
            i += 1
            if command in "Zz":
                if not points:
                    raise ValueError("Empty closed icon path")
                polygons.append(points)
                x, y = points[0]
                points = []
                command = None
                continue
        if command is None:
            raise ValueError("Missing vector path command")
        count = 2 if command in "MmLl" else 1
        values = [float(token) for token in tokens[i:i + count]]
        if len(values) != count:
            raise ValueError("Incomplete vector path command")
        i += count
        if command in "MmLl":
            if command in "Mm" and points:
                raise ValueError("Open vector subpath is unsupported")
            x, y = (values[0] + x, values[1] + y) if command.islower() else values
            if command in "Mm":
                command = "l" if command == "m" else "L"
        elif command in "Hh":
            x = values[0] + x if command == "h" else values[0]
        elif command in "Vv":
            y = values[0] + y if command == "v" else values[0]
        points.append((x, y))
    if points:
        raise ValueError("Icon must use closed filled paths")
    return polygons


def make_icon(path: Path) -> None:
    vector = ET.parse(ICON).getroot()
    width = float(vector.attrib[ANDROID + "viewportWidth"])
    height = float(vector.attrib[ANDROID + "viewportHeight"])
    size = 512 * SCALE
    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    for element in vector:
        if element.tag != "path" or ANDROID + "strokeColor" in element.attrib:
            raise ValueError("The icon now needs support for additional vector elements")
        color = android_color(element.attrib[ANDROID + "fillColor"])
        for points in vector_polygons(element.attrib[ANDROID + "pathData"]):
            draw.polygon([(x / width * size, y / height * size) for x, y in points], fill=color)
    image.resize((512, 512), Image.Resampling.LANCZOS).save(path, optimize=True)
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("Store icon exceeds Play's 1024 KB limit")


def font(path: Path, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(path), size * SCALE)


def text(draw: ImageDraw.ImageDraw, xy: tuple[int, int], value: str,
         size: int, color: str, serif: bool = False) -> None:
    draw.text(tuple(int(n * SCALE) for n in xy), value,
              font=font(SERIF if serif else SANS, size), fill=color, anchor="lt")


def shape(draw: ImageDraw.ImageDraw, method: str, coords, **kwargs) -> None:
    scaled = [(x * SCALE, y * SCALE) for x, y in coords] if isinstance(coords[0], tuple) else [v * SCALE for v in coords]
    if "width" in kwargs:
        kwargs["width"] *= SCALE
    getattr(draw, method)(scaled, **kwargs)


def paste_capture(image: Image.Image, capture: Path, box: tuple[int, int, int, int]) -> None:
    """Fit the complete capture; never stretch it, replace UI, or cut away controls."""
    with Image.open(capture) as source:
        source = ImageOps.exif_transpose(source).convert("RGB")
        left, top, right, bottom = [value * SCALE for value in box]
        source = ImageOps.contain(source, (right - left, bottom - top), Image.Resampling.LANCZOS)
        image.paste(source, (left + (right - left - source.width) // 2,
                             top + (bottom - top - source.height) // 2))


def make_feature(path: Path, colors: dict, capture: Path | None) -> None:
    image = Image.new("RGB", (1024 * SCALE, 500 * SCALE), colors["background"])
    draw = ImageDraw.Draw(image)
    shape(draw, "ellipse", (640, 30, 1060, 450), fill=colors["primaryContainer"])
    shape(draw, "line", [(80, 91), (136, 91)], fill=colors["primary"], width=3)
    text(draw, (80, 127), "READING & LISTENING", 14, colors["secondary"])
    text(draw, (76, 185), "Read Assist", 72, colors["primary"], serif=True)
    text(draw, (81, 294), "Read. Listen. Understand.", 26, colors["onBackground"])
    if capture:
        paste_capture(image, capture, (693, 40, 956, 460))
    else:
        # Editorial book illustration, not a reconstruction of the app UI.
        shape(draw, "polygon", [(668, 155), (788, 182), (904, 155), (904, 324),
                                  (788, 352), (668, 324)], fill=colors["primary"])
        shape(draw, "polygon", [(678, 140), (788, 170), (788, 333), (678, 305)],
              fill=colors["surface"])
        shape(draw, "polygon", [(788, 170), (894, 140), (894, 305), (788, 333)],
              fill=colors["surfaceContainerLow"])
        shape(draw, "line", [(788, 173), (788, 329)], fill=colors["outlineVariant"], width=2)
        for offset, length in [(0, 62), (21, 62), (42, 49)]:
            shape(draw, "line", [(699, 190 + offset), (699 + length, 206 + offset)],
                  fill=colors["outlineVariant"], width=3)
            shape(draw, "line", [(810, 204 + offset), (810 + length, 188 + offset)],
                  fill=colors["outlineVariant"], width=3)
        for i, height in enumerate([8, 16, 28, 40, 24, 14, 8]):
            x = 743 + i * 14
            shape(draw, "line", [(x, 389 - height // 2), (x, 389 + height // 2)],
                  fill=colors["primary"], width=4)
    image.resize((1024, 500), Image.Resampling.LANCZOS).save(path, optimize=True)


def make_screenshot(path: Path, capture: Path, colors: dict, caption: dict) -> None:
    image = Image.new("RGB", (1080 * SCALE, 1920 * SCALE), colors["background"])
    draw = ImageDraw.Draw(image)
    title = caption.get("title", "Read Assist")
    title_font_size = 48
    while draw.textlength(title, font=font(SERIF, title_font_size)) > 960 * SCALE:
        title_font_size -= 1
        if title_font_size < 28:
            raise ValueError(f"Caption title is too long for {capture.name}")
    text(draw, (60, 58), title, title_font_size, colors["primary"], serif=True)
    subtitle = caption.get("subtitle", "")
    top = 155
    if subtitle:
        if draw.textlength(subtitle, font=font(SANS, 25)) > 960 * SCALE:
            raise ValueError(f"Caption subtitle is too long for {capture.name}")
        text(draw, (63, 123), subtitle, 25, colors["onSurfaceVariant"])
        top = 184
    paste_capture(image, capture, (36, top, 1044, 1884))
    image.resize((1080, 1920), Image.Resampling.LANCZOS).save(path, optimize=True)


def asset_record(path: Path, kind: str, sources: list[dict], alt: str) -> dict:
    with Image.open(path) as image:
        width, height = image.size
        mode = image.mode
    return {"file": path.name, "image_type": kind, "width": width, "height": height,
            "mode": mode, "bytes": path.stat().st_size, "sha256": sha256(path),
            "alt_text": alt, "sources": sources}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/store-media/assets")
    parser.add_argument("--screenshots", type=Path, help="Directory containing actual app captures; first eight images in filename order")
    parser.add_argument("--captions", type=Path, help="JSON object keyed by capture filename, with optional title/subtitle/alt_text")
    parser.add_argument("--feature-capture", type=Path, help="Optional actual app capture for the feature graphic instead of the book illustration")
    args = parser.parse_args()
    output = args.output.resolve()
    if args.screenshots and args.screenshots.resolve() == output:
        parser.error("Capture input directory must differ from generated output")
    captures = []
    if args.screenshots:
        if not args.screenshots.is_dir():
            parser.error("Screenshot directory does not exist")
        captures = sorted(p for p in args.screenshots.iterdir() if p.suffix.lower() in {".png", ".jpg", ".jpeg"})[:8]
        if not captures:
            parser.error("No real capture files found in screenshot directory")
    captions = json.loads(args.captions.read_text()) if args.captions else {}
    if not isinstance(captions, dict):
        parser.error("Caption JSON must be an object keyed by capture filename")
    output.mkdir(parents=True, exist_ok=True)
    colors = theme_colors()
    theme_source = provenance(THEME, "shipped_warm_library_palette")
    font_sources = [provenance(SERIF, "bundled_font"), provenance(SANS, "bundled_font")]
    assets = []
    icon = output / "read-assist-icon-512.png"
    make_icon(icon)
    assets.append(asset_record(icon, "icon", [provenance(ICON, "shipped_android_vector")],
                               "White open book on the blue Read Assist app icon."))
    feature = output / "read-assist-feature-1024x500.png"
    make_feature(feature, colors, args.feature_capture)
    feature_sources = [theme_source, *font_sources]
    if args.feature_capture:
        feature_sources.append(provenance(args.feature_capture, "caller_supplied_app_capture"))
    assets.append(asset_record(feature, "featureGraphic", feature_sources,
                               "Read Assist. Read. Listen. Understand."))
    for index, capture in enumerate(captures, start=1):
        caption = captions.get(capture.name, {})
        if not isinstance(caption, dict):
            parser.error(f"Invalid caption object for {capture.name}")
        alt = caption.get("alt_text", "Read Assist app screen.")
        if len(alt) > 140:
            parser.error(f"Alt text exceeds 140 characters for {capture.name}")
        screenshot = output / f"read-assist-phone-{index:02d}-1080x1920.png"
        make_screenshot(screenshot, capture, colors, caption)
        screenshot_sources = [provenance(capture, "caller_supplied_app_capture"), theme_source, *font_sources]
        if args.captions:
            screenshot_sources.append(provenance(args.captions, "editorial_capture_captions"))
        assets.append(asset_record(screenshot, "phoneScreenshots",
                                   screenshot_sources, alt))
    manifest = {
        "schema_version": 1, "app_package": "app.readflow", "app_name": "Read Assist",
        "generator": "scripts/build_store_images.py", "generator_sha256": sha256(Path(__file__)),
        "pillow_version": PIL.__version__, "theme": "Warm Library",
        "capture_policy": "No app UI is generated. Caller-supplied captures are fitted in full without stretching or cropping. Capture authenticity and release version must be verified separately.",
        "feature_graphic_visual": "actual_app_capture" if args.feature_capture else "editorial_book_illustration",
        "phone_screenshot_count": len(captures),
        "screenshot_requirements": {"minimum_two_present": len(captures) >= 2, "recommended_four_present": len(captures) >= 4},
        "assets": assets,
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Created {len(assets)} assets and manifest in {output}")
    if not captures:
        print("Screenshots pending: no real captures were supplied.")


if __name__ == "__main__":
    main()
