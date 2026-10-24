# PSBDx Subtitle Editor (Android)

Professional subtitle editing and live preview for Android. Package: `com.subtitle.psbdx`.

## Features
- Import/export: SubRip `.srt`, WebVTT `.vtt`, YouTube `.sbv`, SubStation Alpha `.ass`, MicroDVD/SubViewer `.sub`, raw text `.txt`
- Prominent **Export** button to save to any supported format
- Two-way sync: playback auto-scrolls/highlights the active cue; tapping a cue seeks the video
- Media3 ExoPlayer preview with live subtitle overlay
- Cue editor with live text editing, editable timestamps, nudge buttons, and one-tap **Mark** to create cues from scratch
- Room-backed projects with debounced auto-save (optional write-through to the opened subtitle file)

## Stack
Kotlin, Jetpack Compose (Material 3), MVVM + StateFlow, Media3, Room, Storage Access Framework.

## Build
Builds run on GitHub Actions (`.github/workflows/build_apk.yml`) and produce a debug-signed APK artifact.
Locally: `./gradlew assembleDebug`.
