Read Assist — Firebase and related runtime notice additions, 0.2.5

Firebase Android BoM 35.1.0 resolves Crashlytics and Crashlytics NDK 21.0.1,
Sessions 4.0.1 and the related modules listed in the generated
licenses/dependencies/resolved-runtime.json inventory.

The generated dependency directory retains the original third_party_licenses
files from each resolved AAR. This directory supplements those files; it does
not replace upstream license terms or the existing runtime notices.

Native source reference
Official Firebase release mergeback 5b34dd98780b50f0f07d4381042ad35230d69b57
identifies latestReleasedVersion=21.0.1 for the NDK SDK. Its native build pins:
- Crashpad: 21a20ef8adf3949de8dd65758a16f83aab344b3c (Apache-2.0).
- mini_chromium: 7477036e238e54f220bed206f71036db8064dd34 (BSD-3-Clause),
  including the ICU UTF conversion subset and its Unicode/ICU notices.
- linux-syscall-support: ed31caa60f20a4f6569883b2d752ef7522de51e0 (BSD-3-Clause).
- Android NDK 27.2.12479018, static C++ runtime and cpu-features support.

Included supplemental notices
- firebase-Apache-2.0.txt and Crashpad-LICENSE.txt: full upstream licenses.
- mini-chromium-LICENSE.txt: upstream Chromium copyright and BSD terms.
- mini-chromium-ICU-LICENSE.txt: complete notice accompanying the pinned ICU
  conversion subset, including its retained older ICU terms.
- linux-syscall-support-LICENSE.txt: complete leading license/author comment
  from the pinned linux_syscall_support.h; source code is not reproduced.
- NDK27.2-cpufeatures-LICENSE.txt: complete leading BSD license comment from
  cpu-features.c in the matching NDK distribution.
- NDK27.2-libcxx-LICENSE.txt, NDK27.2-libcxxabi-LICENSE.txt,
  NDK27.2-libunwind-LICENSE.txt and NDK27.2-compiler-rt-LICENSE.txt:
  full licenses from Android LLVM d8003a456d14a3deb8054cdaa529ffbf02d9b262,
  including LLVM exceptions and the retained legacy notices. These accompany
  the upstream static C++/compiler support configuration, not a separately
  bundled compiler or development toolchain.

The native implementation in this release uses Crashpad. It is not described
here as a separate Breakpad runtime. Native makefiles link Android's system
zlib; this addition does not distribute another standalone zlib library.

sources.json records exact upstream URLs, extraction notes and SHA-256 values.
pom/ retains declared-license evidence for the 35 new or upgraded resolved
modules, including transitive DataStore, Kotlin, transport and Google modules.
Most declare Apache-2.0; relocated DataStore protobuf uses the existing
licenses/runtime/protobuf-LICENSE.txt (BSD-3-Clause). Google Play services
basement/tasks and Firebase measurement-connector are also subject to their
declared Android SDK terms; Apache notices do not relicense those binaries.

This is a bounded notice supplement. Public release-reference source and
Maven artifacts were inspected; the upstream binaries were not rebuilt for
bit-for-bit source verification. Publisher review of applicable service/SDK
terms and the final distribution remains required. No legal clearance or
endorsement is asserted.
