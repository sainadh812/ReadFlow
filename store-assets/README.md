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

## Record and publish the current release

The `capture_demo` operation in `.github/workflows/read-assist-release.yml` runs
the actual release UI on an isolated API 35 x86_64 emulator. It creates original
PDFs, installs verified speech/dictionary packs and records real UI actions and
PulseAudio output. Its APKs use local debug signing for instrumentation and have
no configured Firebase; they are not the signed ARM64 Play artifacts. Capture
reports retain source/build provenance, errors and media hashes. Review those
reports before selecting images or footage. [Workflow details](../docs/GITHUB_RELEASE_BUILD.md).

Keep selected original PNGs and caption JSON under `store-assets/release-0.3.1/`
before running the image generator with that directory as `--output`. This gives
the Play uploader reproducible source paths in its checkout. Inspect every final
PNG; a generated manifest alone is not visual approval.

For video assembly, install `imageio-ffmpeg==0.8.1` alongside Pillow in the media
environment, then use the downloaded raw capture directory:

```bash
build/store-media-venv/bin/python scripts/build_demo_video.py \
  --capture build/store-capture-RUN/build/store-media/capture \
  --output build/store-media/rendered \
  --recipe store-assets/release-0.3.1/video-recipe.json
```

The recipe selects actual clip times and captions. Footage retains its original
speed and full app frame. The separate foreground-service output preserves the
original encoded video stream and synchronizes the recorded host audio; it does
not substitute narration. Review the start action, background playback,
notification pause and audio timing before setting the recipe's
`foregroundService.reviewedAudioSyncAndControls` flag and a factual `reviewNote`.
The tool additionally checks PLAYING/PAUSED evidence, non-silent audio and decoding.

Once the render report is complete, `scripts/publish_demo_site.py --render-dir
build/store-media/rendered` can publish only the demo page, two MP4s and
[media credits](MEDIA_CREDITS.md) to the established `gh-pages` site. It preserves
the privacy page, homepage, other files and hosting settings. A push is followed
by public HTTP/hash/range verification before claiming the videos are available.
Store preview placement still requires a YouTube URL and channel authorization.
GitHub release uploads and Play store publication are separate operations; neither
is established merely by building these files.
