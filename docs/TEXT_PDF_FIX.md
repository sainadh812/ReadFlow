# 0.1.3: text preparation and PDF viewing

## Report and causes

The user's 0.1.2 diagnostics report a handled `IllegalArgumentException`, no recorded process exit, on Samsung SM-S938B, Android 16/API 36. They report basic Kokoro speech working but a PDF and technical webpage failing to read.

- The old normalizer rejected ordinary punctuation, font ligatures, identifiers containing digits/underscores, and Sphinx heading-link icons. The supplied [Ara documentation page](https://pulp-platform.github.io/ara/modules/vlsu/addrgen.html) contains those forms.
- The playback coordinator eagerly normalized every chunk on a page before honoring the selected word. One unsupported sentence could prevent earlier audio and later-word jumps alike.
- Reflowed text was the default for PDFs. The Original viewer was gated on extraction, so even viewing a page waited for the reading layer.

## Implemented

- Explicit, source-mapped normalization of smart quotes, brackets, spacing, common punctuation/symbols, ligatures, quoted numbers, number compounds, ordinals, decades, and technical identifiers. Underscores/slashes are pronounced literally. Source/display text and UTF-16 word offsets are unchanged.
- Heading permalink controls are removed before HTML sanitization/Readability. The known Sphinx icon is also tolerated in previously saved article text. Scripts, privileged bridges and external resource loading remain prohibited in the extraction context.
- Superscript digits immediately after closing quotes or sentence punctuation have a literal "superscript" pronunciation. No footnote lookup or equation interpretation is inferred. Other mathematical expressions, unknown symbols and unsupported scripts still fail explicitly.
- Lazy, sentence-context-preserving preparation starts at the requested sentence. A blocked sentence records its own continuation, independent of the currently audible chunk. Skip sentence advances to the next sentence/page or ends the document; queued earlier audio may finish unless the user chooses to skip immediately.
- Speech normalization has its own cache revision. Existing document IDs/positions and model installations remain valid. Audio and its timings are regenerated together under the new cache key.
- Original PDF/image is the default view, fitted to width, with pan/zoom, fit-whole-page, rotation, previous/next page and direct page-number entry. Word selection uses extracted geometry when ready. Rendering and OCR have separate bounded work locks and separately owned native resources. Cancelled/failed previews release their bitmaps.
- The APK uses package `app.readflow`, version code 4, version name `0.1.3-prototype`, ARM64, minimum API 35. No runtime/model/dependency upgrades or model redownloads are required.

## Checks

The initial five new normalization regressions failed against the old implementation. They now pass, including source mapping and equation rejection. Added tests cover lazy preparation, jumping past invalid text, retained sentence context, normalized-size limits, exact skip targets, cache invalidation, HTML sanitation, PDF preview and saved article processing.

Final build on 2026-09-20:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --console=plain
cp app/build/outputs/apk/debug/app-debug.apk dist/ReadFlow-arm64-debug.apk
cp dist/ReadFlow-arm64-debug.apk dist/ReadFlow-0.1.3-arm64-debug.apk
python3 scripts/verify_delivery.py
$ANDROID_HOME/build-tools/34.0.0/apksigner verify --verbose --print-certs dist/ReadFlow-0.1.3-arm64-debug.apk
$ANDROID_HOME/build-tools/34.0.0/aapt dump badging dist/ReadFlow-0.1.3-arm64-debug.apk
```

- Gradle: exit 0, **BUILD SUCCESSFUL in 2m 22s**. Application and instrumentation APKs built.
- **51 JVM tests passed**, no failures/errors/skips; **lint: 0 errors, 15 existing warnings**. This includes real-PCM alignment regression fixtures for both models, not new audible/device acceptance.
- Delivery inspection: ARM64 only, no speech/alignment weights bundled. APK size 130,579,053 bytes, SHA-256 `b1f425f89c1197346a45c780644183a1742fa9ff4b17cd553524c54e48d72424`.
- APK Signature Scheme v2 verifies. Certificate SHA-256 `f7313f98e9414c166f3fb2d2a9329ba1bb5dd9d76b068254522d20d1f8b109f9`, matching the previous released APK. Package/version/minimum/target/ABI match the stated configuration.
- `adb devices -l`: no attached devices. Android instrumentation tests were compiled but **not executed**. No new emulator attempt, phone benchmark or UI screenshot is claimed.

The supplied PDF was inspected locally with Poppler: 253 pages, unencrypted PDF 1.4, 612 x 792-point pages. A host text probe uses only pages 8-10. Neither the book nor its extracted text is included in Git or release artifacts. The added checked-in HTML fixture is original CC0 content.

Host probe reproducer, after a Gradle build and with locally downloaded input HTML/text:

```bash
java --class-path "app/build/tmp/kotlin-classes/debug:$(rg --files "$HOME/.gradle/caches/modules-2/files-2.1" -g '*.jar' | rg '/(kotlin-stdlib/2.1.20|kotlinx-serialization-(core|json)-jvm/1.8.0|jsoup/1.18.3)/' | paste -sd :)" scripts/CheckImportedText.java .deps/addrgen.html .deps/reported-book-pages.txt
```

This runs production HTML sanitation, document mapping and speech normalization. It does **not** execute Android WebView Readability, Android PDF/OCR, JNI synthesis, audible alignment or Compose rendering. The probe prints counts and fixed failure categories, not document content. Initial probe: webpage 757 words / 70 chunks / no rejected sentences; PDF sample 815 words / 59 prepared chunks / two blocked sentences, motivating the additional book-typography fixes. **Final probe: exit 0; webpage 757 words / 70 chunks, PDF sample 815 words / 64 chunks, zero rejected sentences in both samples.** This is not a full-book test.

## Remaining device checks

No physical Android device is attached. Do not treat this patch as proven smooth book playback or full core acceptance. Both-engine timing accuracy, thermal/memory behavior and the broader gaps in STATUS.md remain unverified.

1. Install the update over 0.1.2 without clearing data. Open the existing PDF; verify Original view, page navigation, fit/rotation, readable rendering and word taps after extraction.
2. Use the page counter to jump to a prose page. Start Kokoro, then check real-audio highlights and a tapped word at 1x/2x. Verify that superscript references do not cause invented equation speech.
3. Open the supplied saved webpage, and separately import it again to exercise the updated sanitizer. Check headings, identifiers, word seeking and sentence transitions.
4. In the original test fixture containing an actual equation, confirm the explicit error and Skip sentence behavior, including while earlier buffered audio is playing, at a page boundary and at document end.
5. Repeat with Pocket TTS, rapid jumps, manual pan/scroll, background playback and interrupts. Copy local diagnostics for any failure.
