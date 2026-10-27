# Native crash symbols

Read Assist 0.2.5 adds Firebase Crashlytics native reporting when the owner supplies
the app's Firebase configuration and the user opts in. Native symbol generation
and upload are build/publishing operations; they do not upload reading content.

## Misaki frontend

`scripts/prepare_misaki.py` retains an **unstripped** Android library at
`app/build/generated/misaki/jniLibs/<abi>/libreadflow_misaki.so`. Go is invoked with
`-B gobuildid`, which derives an ELF GNU build ID from Go's build ID. The script
requires a GNU ID, symbol table and DWARF before accepting an Android artifact.
The generated `.build.json` records its SHA-256 and GNU ID. Script/toolchain/source
changes invalidate the cache for both `arm64-v8a` and `x86_64`; existing files from
the old `-s -w -buildid=` build must be rebuilt.

AGP strips the **packaged copy** during `stripReleaseDebugSymbols`. Keep the
generated unstripped library and its build stamp with each release. Do not use an
unstripped library rebuilt from a later source revision to symbolize an older APK.
The packaged library's GNU ID must match its retained original. The library's
runtime ABI, model files, source licenses and 16 KB linker alignment are unchanged.

After the normal build, verify both copies with the pinned NDK's `llvm-readelf`:

```sh
llvm-readelf -n app/build/generated/misaki/jniLibs/arm64-v8a/libreadflow_misaki.so
llvm-readelf -SW app/build/generated/misaki/jniLibs/arm64-v8a/libreadflow_misaki.so
llvm-readelf -n app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/libreadflow_misaki.so
llvm-readelf -SW app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/arm64-v8a/libreadflow_misaki.so
```

The unstripped copy must retain `.symtab` and `.debug_info`; the packaged copy
should omit both, and the GNU build IDs must match. Repeat for `x86_64` when building
the emulator variant. `python3 scripts/prepare_misaki.py --host` also retains debug
symbols for host smoke tests; it is never packaged in the Android app.

## Upload and limits

With the owner's valid `app/google-services.json` in place, run the release build
and upload symbols from that **same build**:

```sh
./gradlew :app:assembleRelease :app:uploadCrashlyticsSymbolFileRelease
```

The configured symbol input is
`app/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib`,
before AGP's strip step. This includes Misaki and the project's unstripped TeleOCR
library. `assembleRelease` alone does not upload native symbols. Mapping uploads
are disabled for ordinary review builds; the release is currently not obfuscated.

Some vendor libraries, including the supplied ONNX Runtime binaries, are already
stripped. Their GNU build IDs can identify the module, but full internal names and
source lines require matching symbols from their suppliers. Native stack unwinding
and symbolication must be verified with controlled test crashes on a real device;
successful symbol upload alone does not prove complete native/Go crash coverage.

Sources: [Firebase native setup](https://firebase.google.com/docs/crashlytics/android/get-started-ndk)
and [Go linker flags](https://pkg.go.dev/cmd/link).
