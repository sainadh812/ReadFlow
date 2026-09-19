"""Generate original CC0 PDF/image fixtures. No downloaded books or model weights."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets/fixtures"
ROOT.mkdir(parents=True, exist_ok=True)
LINES = ["Reading opens a quiet space for thought.", "Every word has a place on the page.", "Read the same word again and again.", "Dr. Green recorded twelve kilograms."]

def draw_native(c, lines=LINES, x=48, y=730):
    c.setFont("Helvetica", 14)
    for i, line in enumerate(lines):
        c.drawString(x, y - i * 24, line)

def scan():
    im = Image.new("RGB", (1275, 1650), "white")
    draw = ImageDraw.Draw(im)
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 32)
    for i, line in enumerate(LINES):
        draw.text((90, 140 + i * 65), line, fill="black", font=font)
    return im

im = scan()
im.save(ROOT / "scan.png")
im.rotate(90, expand=True).save(ROOT / "rotated.png")
for kind in ["selectable", "scanned", "mixed", "blank", "two-column", "page-number-only", "rotated"]:
    c = canvas.Canvas(str(ROOT / f"{kind}.pdf"), pagesize=(612, 792))
    if kind == "selectable": draw_native(c)
    elif kind == "scanned": c.drawImage(ImageReader(im), 0, 0, width=612, height=792)
    elif kind == "mixed":
        draw_native(c, ["Native heading"])
        c.drawImage(ImageReader(im), 24, 50, width=560, height=600)
    elif kind == "page-number-only":
        c.drawImage(ImageReader(im), 0, 0, width=612, height=792)
        draw_native(c, ["1"], y=20)
    elif kind == "two-column":
        draw_native(c, ["Left column first.", "Left column next.", "Left column last."], x=40)
        draw_native(c, ["Right column first.", "Right column next.", "Right column last."], x=330)
    elif kind == "rotated": c.drawImage(ImageReader(im.rotate(90, expand=True)), 0, 0, width=612, height=792)
    c.showPage(); c.save()
(ROOT / "corrupt.pdf").write_bytes(b"%PDF-1.7\ncorrupt test file\n")
(ROOT / "article.html").write_text("<html><head><title>A reading fixture</title><script>throw new Error('must not run')</script></head><body><article><h1>A reading fixture</h1>" + "".join(f"<p>{' '.join(LINES)}</p>" for _ in range(4)) + "</article><iframe src='https://example.com'></iframe></body></html>")
print("Generated fixtures at", ROOT)
