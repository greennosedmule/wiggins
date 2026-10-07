# vad: WebRTC voice activity detector

This module is the `webrtc` module of
[android-vad](https://github.com/gkonovalov/android-vad) by Georgiy Konovalov. It holds
the Kotlin wrapper, the JNI glue and the WebRTC C code. It's built from source with a
pinned NDK, so the app needs no prebuilt `.so` and no JitPack.

- Upstream tag: `2.0.10`
- Upstream commit: `1b006d0aaf8fa269986a8c95a196055550c9b877`
- NDK: `30.0.16248370` (r30), pinned in `build.gradle.kts` and installed by the CI and
  release workflows
- ABIs: `arm64-v8a` and `x86_64`

## Licences

- `LICENSE.md`: MIT, for android-vad (the Kotlin wrapper and `vad_jni.c`).
  Copyright 2023 Georgiy Konovalov.
- `src/main/jni/webrtc_vad/LICENSE`: BSD-3-Clause, for the WebRTC C code.
  Copyright (c) 2011, The WebRTC project authors.
- `src/main/jni/webrtc_vad/PATENTS`: WebRTC's patent grant. android-vad doesn't ship it,
  but the WebRTC source headers refer to it, so it's copied from
  https://webrtc.googlesource.com/src/+/refs/heads/main/PATENTS.

## Changes from upstream

The Kotlin sources (`src/main/java`), the C sources and `src/test` are unchanged.

- `build.gradle.kts` replaces upstream's `build.gradle`: Wiggins' AGP and SDK levels,
  NDK `30.0.16248370` instead of `28.2.13676358`, `arm64-v8a` and `x86_64` only, no
  minification of the library itself, no Maven publishing, no instrumented tests.
- `consumer-rules.pro` keeps only the `-keep` rule from upstream's `proguard-rules.pro`.
- `src/main/jni/Android.mk` gains one `LOCAL_CFLAGS` line that maps the checkout and NDK
  paths out of the debug info. The build ID is a hash taken before stripping, so without
  it the `.so` files differ by those 20 bytes from one checkout path to another. With it,
  clean builds in different directories, and with the NDK at a different path, give
  byte-identical libraries.
- Removed: the sample app, the Silero and Yamnet modules, the instrumented test and its
  WAV file, and the JitPack configuration.

## Updating

1. Clone android-vad at the new release tag and diff its `webrtc/` against this module
   (`src/main/java`, `src/main/jni`, `src/test`, `proguard-rules.pro`).
2. Copy the changed files over, keeping the `Android.mk` flags line above.
3. Update the tag and commit here, and the licence notices on the About screen if they
   changed.
4. If you move to a newer NDK, change `ndkVersion` in `build.gradle.kts` and the
   `ndk;…` package in `.github/workflows/ci.yml` and `release.yml` together.
5. Build with `./gradlew :vad:assembleRelease :vad:testDebugUnitTest` and check that the
   AAR has `jni/arm64-v8a/libvad_jni.so` and `jni/x86_64/libvad_jni.so`.
