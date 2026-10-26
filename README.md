# GhostGuard

An Android app that drops phantom button presses from USB-C headphones while
letting your real presses through.

Phantom presses come from the headset, not from Android: a damp or flexing
cable, a worn contact, or a cheap adapter closes a switch for a few
milliseconds and the phone dutifully pauses your music. The presses are real
electrical events, so nothing in Android filters them. GhostGuard sits between
the headset and your music app and throws out the ones no finger could have
made.

## How it decides

Every press goes through `filter-core`, a plain-Kotlin engine with no Android
dependencies, so the rules can be unit tested on a laptop. A press is dropped
when it is:

| Check | Default | Why |
| --- | --- | --- |
| Too short | under 45 ms from press to release | Contact bounce is sub-20 ms; a finger is 60–150 ms |
| Repeated | same button inside 450 ms | One press arriving several times over, the classic symptom |
| Cross-talk | a different button inside 150 ms | A ghost skip riding along with a real play press |
| Chatter | 4 presses inside 1.2 s | Faster than a person; the guard then stays shut for 2.5 s |
| Right after a plug change | 1.5 s | Most phantom bursts land the moment the plug moves |
| Stuck | held past 4 s | A shorted contact, not a long press |

Deliberate presses at human speed, and genuine long presses, pass through
untouched. Every threshold is a slider in the app.

**Start in observe-only mode.** It forwards everything and records what it
*would* have dropped, so you can look at a day of your own presses in the log
before you let it block anything.

## How it catches the presses

Two paths, because which one works depends on your headphones.

**USB (deterministic).** Digital USB-C headsets — the ones with a DAC in the
plug — report their buttons as a USB HID device. When an app claims a HID
interface with `force`, Android detaches its own driver from it, so the system
stops seeing those buttons entirely. GhostGuard then reads the raw reports,
filters them, and passes the survivors to your player. Nothing races; a ghost
simply never exists as far as Android is concerned. If GhostGuard cannot decode
the headset's button layout it hands the interface straight back rather than
leaving you with no working buttons.

**Media keys (best effort).** Analog USB-C headphones, the kind that work
through a passive adapter, have no USB device at all — the buttons are detected
in hardware on the mic line and arrive as ordinary media keys. For those,
GhostGuard holds a media session and filters the keys Android routes to it.
This works with anything, but Android decides which app gets media buttons, so
another player that starts after us can take the routing back. GhostGuard
re-claims it when it notices, which is reliable in practice but not guaranteed.

**Volume keys** reach neither path. Filtering them needs the accessibility key
hook, which is why that permission is optional and off by default.

Pick USB, media keys, or both on the first screen. Both is the sensible default.

## Permissions

- **Notification access** — the only way to hand a real press to whatever is
  playing. Without it, accepted presses are dispatched to the system instead,
  which works but is less precise.
- **USB access** — prompted the first time you plug the headset in.
- **Accessibility** — only if you want volume keys filtered. GhostGuard reads
  key presses and nothing else.
- **Notifications** — for the ongoing status notification Android requires of a
  background service.

## Building

```
./gradlew :filter-core:test     # the detection rules
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

Needs JDK 17 and an Android SDK with API 35. The GitHub Actions workflow in
`.github/workflows/build.yml` does both on every push and uploads the APK as a
build artifact, so you can install it without a local Android setup.

Install with `adb install app-debug.apk`, or copy the APK to the phone and open
it (you will have to allow installing from that source).

## Layout

```
filter-core/     pure Kotlin: the detection rules and a HID descriptor parser, with tests
app/
  core/          keycode and HID usage translation
  data/          settings, event log
  service/       guard service, USB capture, media capture, forwarder
  ui/            Compose screens: status, tuning, log
```

## Known limits

- The media-key path cannot promise it sees a press before the player does.
  The USB path can; prefer it if your headphones are digital.
- A headset whose buttons use multi-bit HID fields falls back to the media-key
  path rather than being taken over.
- Nothing here needs root, and nothing here can filter presses the headset
  sends over Bluetooth.
