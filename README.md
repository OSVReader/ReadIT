# ReadIT

ReadIT is an Android EPUB reader focused on private, offline reading.

It can import EPUB files from your phone, remember your place, search inside books, save bookmarks, adjust reading appearance, and read books aloud with either Android system voices or downloadable offline voices.

This is the first public test release of our open source text-to-speech enabled eReader app ReadIT: an offline EPUB reader with full eReader features and high-quality neural text-to-speech voices running locally on your smartphone.

## Video Tutorial

Click the video preview below to watch the quick download and usage tutorial.

[![Watch the ReadIT video tutorial](https://img.youtube.com/vi/tCtKfBN5eqE/hqdefault.jpg)](https://youtu.be/tCtKfBN5eqE)

## Download

Get the latest APK from the [Releases](https://github.com/OSVReader/ReadIT/releases) page. On the release page, open the Assets section to download the APK installer package.

For most Android phones, download:

```text
readit-1.0-arm64-v8a-release.apk
```

Other APKs are available if needed:

| APK | Use for |
|---|---|
| `readit-1.0-arm64-v8a-release.apk` | Most modern Android phones |
| `readit-1.0-armeabi-v7a-release.apk` | Older 32-bit Android phones |
| `readit-1.0-x86_64-release.apk` | Android emulators or x86_64 devices |
| `readit-1.0-universal-release.apk` | Unknown architecture; larger download |

After downloading, open the APK on your phone and allow installation from your browser or file manager if Android asks.

To install the app on your Android device, locate the downloaded file in your device's downloads folder, press the file to start installation, then follow Android's on-screen instructions.

## Features

- Import and manage EPUB books on-device
- Grid and list library views
- Automatic title, author, and cover extraction
- Reading position restore
- Bookmarks
- Table of contents navigation
- In-book search
- Light, sepia, and dark reading themes
- Font and display controls
- Android system text-to-speech
- Offline Piper and Kokoro voice options
- Playback notification controls

## Offline Voices

Offline voices are downloaded inside the app from the Voice settings screen. They are stored on your device and can be removed later.

Kokoro voices sound more natural, but they can take longer to prepare audio on some phones. Piper voices are smaller and usually faster.

## Known Issue

Offline TTS may pause before long paragraphs while the next audio is generated. This is the main issue planned for improvement after the first test release.

## Privacy

ReadIT is designed as an offline-first reader. Imported books, bookmarks, reading position, settings, and downloaded voice models stay on your device.

Android backup is disabled for app data in this test release.

## License

ReadIT uses open-source components including Readium Kotlin Toolkit, sherpa-onnx, Piper voices, and Kokoro voices. See the source and dependency licenses for details.
