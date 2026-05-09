# readIt - Android EPUB Reader

A privacy-focused, offline-first Android EPUB reader built with Kotlin and the Readium 3.1.0 toolkit. Features three text-to-speech engines (System TTS, Piper, and Kokoro), sentence-level highlighting, bookmarks, full-text search, and a clean Material 3 UI.

---

## Download

Get the latest Android APK from the [GitHub Releases](https://github.com/AndreasME53/ReadIT/releases) page.

Recommended file for most phones:

```text
readit-1.0-arm64-v8a-release.apk
```

Release APK options:

| APK | Use for |
|---|---|
| `readit-1.0-arm64-v8a-release.apk` | Most modern Android phones |
| `readit-1.0-armeabi-v7a-release.apk` | Older 32-bit Android phones |
| `readit-1.0-x86_64-release.apk` | Android emulators or x86_64 devices |
| `readit-1.0-universal-release.apk` | Unknown architecture; larger download |

After installing, launch **readIt**, import an EPUB, then open **Voice** settings to download optional offline voices.

Known first-release issue: offline TTS may pause before long paragraphs while audio is generated. This will be improved in a follow-up release.

---

## Features

### Library
- Import EPUB files from device storage
- Grid or list view with cover art
- Drag-to-reorder via handle
- Background metadata extraction (title, author, cover)
- Long-press to delete

### Reader
- Full EPUB rendering via Readium (WebView-based)
- Table of Contents navigation
- Reading position auto-saved and restored
- Full-text search (up to 200 results)
- Bookmarks: add, view, navigate, delete
- Display settings: font family, font size, theme (Light/Sepia/Dark)

### Text-to-Speech (3 Engines)

| Engine | Quality | Speed | Offline | Voices |
|--------|---------|-------|---------|--------|
| **System** | Varies | Real-time | No | Device voices |
| **Piper** | Good | Fast (0.5x RTF) | Yes | 7 English |
| **Kokoro** | Near-human | Slower (3-7x RTF) | Yes | 11-103 voices |

**Common TTS features:**
- Sentence-block highlighting (current + next sentence preview)
- Auto-scroll and auto-page-turn
- Automatic chapter advancement
- Speed control (0.5x-3.0x)
- Foreground service with media notification controls
- Error recovery: resume from where it stopped

**Piper TTS (Offline):**
- 7 English voices (US + GB, male + female)
- ~63MB per voice, download in-app
- Default engine with auto-download on first play
- DC offset removal and fade-in/out for clean audio

**Kokoro TTS (Offline, Near-Human Quality):**
- 3 downloadable model packs:
  - **Kokoro English v0.19** (~340MB) -- 11 English voices
  - **Kokoro Multi-lang v1.0** (~333MB) -- 53 voices (English + Chinese + others)
  - **Kokoro Multi-lang v1.1** (~348MB) -- 103 voices (English + Chinese)
- Per-pack download/delete with speaker selection
- 24kHz sample rate output
- Streaming playback via `generateWithCallback` -- audio starts within ~200ms
- Paragraph-based generation (sentences grouped into ~400 char chunks) for seamless transitions
- Parallel pre-generation of next paragraph while current one plays
- Adaptive thread count (up to 8 threads on capable devices)

---

## Technology Stack

| Component | Technology | Version |
|-----------|------------|---------|
| Language | Kotlin | 1.9.x |
| Min SDK | 21 (Android 5.0) | |
| Target SDK | 34 (Android 14) | |
| EPUB Rendering | Readium Kotlin Toolkit | 3.1.0 |
| Offline TTS | sherpa-onnx (Piper VITS + Kokoro) | 1.12.6 |
| Database | Room | 2.6.1 |
| UI | Material Design 3 | 1.12.0 |
| Image Loading | Glide | 4.16.0 |

## Download & Install From GitHub

Download the APK from the latest GitHub release:

1. Open the [Releases](https://github.com/AndreasME53/ReadIT/releases) page.
2. Open the latest release.
3. Download `readit-1.0-arm64-v8a-release.apk` for most modern Android phones.
4. On the phone, open the downloaded APK and allow installation from the browser or file manager if Android prompts for it.
5. Launch **readIt**, import an EPUB, then open **Voice** settings to download optional offline voices.

Use `readit-1.0-universal-release.apk` only if the phone's CPU architecture is unknown. It is much larger because it contains all native ABIs.

---

## Build & Run

### Phone Smoke Test Before Publishing

Before attaching an APK to a public GitHub release, install the signed APK on a phone and check:

- Import an EPUB, open it, close it, and reopen it.
- Start TTS, wait for audio, then stop and start again.
- Press back while the preparing-audio indicator is visible.
- Use notification stop while audio is generating or playing.
- Confirm the preparing-audio indicator disappears when audio starts.
- Confirm no artificial delay was added before playback.

### Prerequisites
- Android Studio Hedgehog or later
- JDK 17+
- Android SDK 35

### Build

```bash
cd ReadIT

# Debug APK
./gradlew assembleDebug

# Release APKs (per-ABI splits + universal)
./gradlew assembleRelease
```

Release APKs are written to `app/build/outputs/apk/release/`:
- `readit-1.0-arm64-v8a-release.apk` (~13MB) -- most modern phones
- `readit-1.0-armeabi-v7a-release.apk` (~12MB) -- older 32-bit phones
- `readit-1.0-universal-release.apk` (~40MB) -- all architectures
- `readit-1.0-x86_64-release.apk` (~14MB) -- emulators

Release signing is configured from ignored local `signing.properties` or `READIT_RELEASE_*` environment variables. Do not commit keystores or signing passwords.

### Install

```bash
# Via ADB
adb install app/build/outputs/apk/release/readit-1.0-arm64-v8a-release.apk

# Or copy APK to phone and install manually
```

### Voice Setup
All TTS voices are downloaded in-app from the Voice Settings panel. No manual setup needed. Select an engine (Piper or Kokoro), browse available voices, and tap download.

---

## Project Structure

```
app/src/main/java/com/example/booklibrary/
  MainActivity.kt              # Library screen
  ReaderActivity.kt            # Reader screen + UI wiring
  BookRVAdapter.kt             # Library RecyclerView adapter
  TtsSettingsSheet.kt          # TTS engine/voice/speed settings
  DisplaySettingsSheet.kt      # Font, theme, highlight settings
  TocBottomSheetDialogFragment.kt
  BookmarksBottomSheet.kt
  SearchBottomSheet.kt
  reader/
    ReaderTtsController.kt     # TTS orchestration (3 engines)
    ReaderPreferences.kt       # SharedPreferences wrapper
    ReaderBookmarkController.kt
    LocatorSerializer.kt
  data/
    AppDatabase.kt             # Room database (v4)
    BookDao.kt / BookEntity.kt
    BookmarkDao.kt / BookmarkEntity.kt
  tts/
    PiperTtsPlayer.kt          # Offline TTS player (Piper + Kokoro)
    TtsModelManager.kt         # Voice catalog, download, extraction
    TtsForegroundService.kt    # Background playback service
    SentenceSplitter.kt        # BreakIterator sentence segmentation
  work/
    BookMetadataWorker.kt      # Background cover/metadata extraction
```

---

## TTS Architecture

The offline TTS pipeline uses sherpa-onnx's `generateWithCallback` API for streaming audio generation. Key design decisions:

- **Paragraph chunking**: Sentences are grouped into ~400 character paragraph blocks. Each block is a single `generateWithCallback` call, so sherpa-onnx generates continuous speech with natural prosody across sentence boundaries. This eliminates the gaps that occur with per-sentence generation.
- **Hybrid streaming + pre-generation**: The first paragraph streams directly to `AudioTrack` for fast startup (~200ms to first audio). While it plays, the next paragraph is pre-generated in parallel. Subsequent paragraphs play from the pre-generated buffer while the next one generates.
- **Safe native lifecycle**: All JNI calls use `generateWithCallback` (not `generate`) so the `isStopping` flag can interrupt native code via the callback returning 0. `release()` waits for all native calls to complete before calling `tts.free()`.
- **Parallel startup**: Model loading and sentence extraction run concurrently. The first sentence highlight appears immediately after extraction, before audio generation begins.

## Known Issues

1. **Piper audio pops on some voices** -- Partially mitigated with DC offset removal and fade-in/out. More noticeable on the Amy (low quality) voice.

2. **Kokoro is slow on low-end devices** -- RTF of 3-7x means 3-7 seconds to generate 1 second of audio. Paragraph pre-generation hides latency on mid-range and above phones.

3. **Highlight color updates on next sentence** -- Changing highlight color mid-playback takes effect on the next sentence, not immediately.

## Development Notes

### Resuming TTS Work

Key files for the TTS pipeline:
- `tts/PiperTtsPlayer.kt` -- Core playback engine. Manages `OfflineTts` lifecycle, `AudioTrack` streaming, paragraph-level generation loop with pre-gen, and safe native memory cleanup.
- `tts/SentenceSplitter.kt` -- `BreakIterator`-based sentence splitting + `groupIntoParagraphs()` for chunking.
- `reader/ReaderTtsController.kt` -- Orchestrates TTS: model loading, sentence extraction with locators, highlight decorations, chapter advancement, foreground service.
- `app/proguard-rules.pro` -- Critical R8 keep rules for `Function1` implementations used by JNI callbacks.

### Native Crash Prevention (SIGSEGV)

The sherpa-onnx JNI layer requires careful lifecycle management:
1. **Never call `job.cancel()`** on a coroutine running a blocking JNI call. Cancellation unwinds Kotlin code but the native thread keeps running.
2. **Always use `generateWithCallback`** (not `generate`) so the callback can check `isStopping` and return 0 to stop native code gracefully.
3. **Never null `offlineTts`** until `job.join()` confirms the native call has fully returned.
4. **`release()` must wait** for any pending `stop()` coroutine before freeing resources.
5. **R8/ProGuard** strips synthetic lambda invoke methods needed by JNI. Use explicit `object : Function1<FloatArray, Int>` instead of lambdas for callbacks.

---

## License

This project uses the following open-source components:
- [Readium Kotlin Toolkit](https://github.com/nicorevin/kotlin-toolkit) (BSD-3-Clause)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0)
- [Piper TTS models](https://github.com/rhasspy/piper) (MIT)
- [Kokoro TTS](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0)
