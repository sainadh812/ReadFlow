# Read Assist store artwork

`scripts/build_store_images.py` creates the Play icon and feature graphic from the shipped app's vector, Warm Library palette and bundled fonts. It can also frame actual app captures at 1080×1920. It does not generate app screens, record video or upload anything.

The icon preserves `app/src/main/res/drawable/ic_readflow.xml`, including its blue background. The feature graphic uses the default theme's cream and green with “Read Assist” and “Read. Listen. Understand.” Its book illustration is editorial artwork, not an app screenshot. Supply `--feature-capture` to use a real app screen instead.

## Generate

Use an isolated Python environment with Pillow installed. The generated manifest records the Pillow version and hashes of the script, source vector, colors, fonts, captures and outputs.

```bash
python3 -m venv build/store-media-venv
build/store-media-venv/bin/python -m pip install Pillow==12.3.0
build/store-media-venv/bin/python scripts/build_store_images.py
```

Outputs go to ignored `build/store-media/assets/`:

- `read-assist-icon-512.png`: 512×512 RGBA PNG, faithful shipped icon.
- `read-assist-feature-1024x500.png`: 1024×500 RGB PNG, no alpha.
- `manifest.json`: dimensions, file sizes, SHA-256, source provenance and screenshot readiness.

## Add actual screenshots

Capture the running release app with original or licensed demonstration documents. Verify the release version and remove unrelated notifications before capture. Keep the original captures as evidence; the generator preserves each complete image and scales proportionally within a neutral frame. It does not erase, replace or fabricate controls.

```bash
build/store-media-venv/bin/python scripts/build_store_images.py \
  --screenshots build/store-media/captures \
  --captions build/store-media/captions.json \
  --feature-capture build/store-media/captures/02-reader.png
```

`--captions` and `--feature-capture` are optional. Input images are processed in filename order, up to eight. Without captions, frames say only “Read Assist”; no feature claims are inferred from filenames. Example caption file:

```json
{
  "02-reader.png": {
    "title": "Read at your pace",
    "subtitle": "Text and PDF views with playback controls",
    "alt_text": "Read Assist document reader with the Text/PDF switch and playback controls."
  }
}
```

Use captions only when the supplied capture supports them. Review every image at normal phone size before upload. The manifest's screenshot count is not proof of capture authenticity, device testing or production readiness.

[Google Play asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151) specify at least two screenshots and recommend four 1080×1920 portrait screenshots for app promotional eligibility. Screenshots and feature graphics must be RGB without alpha; the store icon uses RGBA. A store preview video requires a public or unlisted, embeddable YouTube URL. The separate foreground-service demonstration must show actual user-started playback; an artwork slideshow does not satisfy that evidence.

The generator neither changes app code nor updates Play Console. Upload only reviewed files listed in the current manifest; older files may remain in the output directory after regenerating with fewer screenshots.
