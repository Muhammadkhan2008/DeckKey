# DeckKey — A PC Keyboard for Android

A full desktop-style keyboard IME for Android: real **Ctrl, Alt, Shift, Meta(Win), Caps Lock, Esc, F1–F12**, arrow keys, and a navigation cluster (Home/End/PgUp/PgDn/Ins/Del), with desktop-feel touch handling — multitouch chording, key auto-repeat, key-preview popups and haptics.

## Who it's for

DeckKey shines in apps that listen for **hardware key events**:

- terminals (**Termux**), shells over SSH
- code editors (Acode, Spck, Vim/Emacs in a terminal)
- VNC/RDP clients, some browsers and IDEs

In those apps, chords like **Ctrl+C, Ctrl+V, Alt+Tab, Shift+Arrow, Ctrl+Shift+Z** work as expected. In ordinary text fields (e.g. a notes app), letters/symbols/arrows/backspace all work, but app-specific shortcuts only fire if that app handles key events — exactly like a USB keyboard would behave.

## How the "PC feel" works

| Feature | Mechanism |
|---|---|
| Ctrl/Alt/Meta chords | A real `KeyEvent` is sent via `InputConnection.sendKeyEvent()` with the assembled meta-state mask (`META_CTRL_ON`, ...), so apps see a genuine modified keystroke. |
| Plain typing | Goes through `commitText()` for reliability in normal fields. |
| Esc / F-keys / arrows / nav | Mapped to Android `KEYCODE_*` and sent as key events. |
| Modifier behavior | Tri-state machine: **tap = latch** (next key), **double-tap = lock**, **press-and-hold = chord**. See `ModifierStateManager`. |
| Caps Lock | Independent sticky toggle; `Caps XOR Shift` decides letter case. |
| Auto-repeat | Held arrows/backspace/space repeat with desktop timing (initial delay → fast interval). See `KeyRepeatController`. |
| Multitouch | Raw `onTouchEvent` tracks each pointer→key, so you can physically hold Shift and tap another key. |

## Architecture

```
DeckKeyService (InputMethodService)   ← owns everything, holds InputConnection
├── view/
│   ├── KeyboardView         Canvas-drawn, multitouch, low latency
│   └── KeyPreviewPopup      the pop-up bubble over a pressed key
├── input/
│   ├── ModifierStateManager tri-state Ctrl/Alt/Shift/Meta + Caps  ← the brain
│   ├── KeyDispatcher        text vs. key-event routing + meta mask
│   └── KeyRepeatController  desktop-style auto-repeat
├── layout/
│   ├── LayoutManager        loads assets/layouts/*.json
│   └── assets: qwerty.json, symbols.json   ← layouts are data, not code
├── feedback/FeedbackController  haptics + key sound
└── core/  models (Key, Row, KeyboardLayout) + Settings (DataStore)
```

Layouts are plain JSON, so you can add pages (compact, programmer, numpad) without touching Kotlin.

## Build & install

Requires Android Studio (Koala+) or the Android command-line SDK.

```bash
# from the project root
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> Note: this repo ships source only — the Gradle **wrapper jar** is not included.
> Open the project once in Android Studio (it regenerates the wrapper), or run
> `gradle wrapper --gradle-version 8.7` if you have a system Gradle.

### Enable it

1. Launch **DeckKey** → tap **Enable in Settings** → toggle DeckKey on.
2. Tap **Choose Keyboard** → pick DeckKey.
3. Open **Termux** (or the in-app test field) and try `ls` then **Ctrl+C**, arrows, **Esc**, **Tab**.

## Settings

Haptics, key sound, key-preview popup, key height, and auto-repeat interval — all live-applied to the running keyboard via a DataStore `Flow`.

## Roadmap / extension points

- More layouts (programmer layout with full symbol row, numpad) — just drop a JSON in `assets/layouts/`.
- Long-press alternate characters (popup grid) — `Key.longPress` already parsed.
- Gesture/swipe delete, theme switching (light/dark/custom).
- Physical-keyboard-style key labels per locale.

## Known limitations

- App shortcuts only fire in apps that consume hardware key events (by design of Android IME).
- F-keys / Esc do nothing in apps that ignore them — same as a real keyboard.
