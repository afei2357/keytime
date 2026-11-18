# KeyTime

**English** | [简体中文](README.md)

> Announce the time without opening your eyes: press a volume key and hear “13:51” spoken aloud.

KeyTime is a fully offline Android utility. A foreground service and a `MediaSession`
take over the volume key while the phone is locked, then speak the current time with the
system TTS engine — no accessibility permission, no screen reading, and no network access.
The project has been heavily adapted and tested on Xiaomi HyperOS, and also works on other
Android 8.0+ devices.

- Version: **3.7.0** (versionCode 24)
- Stack: Kotlin / Android SDK 35 / Gradle 8.9
- Package: `com.example.keytime`
- License: MIT

## Features

- **Lock-screen announcement**: while locked or with the screen off, press Volume Down to hear
  the time (e.g. “13:51”). Volume Up or both keys can be selected.
- **No interference when unlocked**: with the screen on and the device unlocked, volume keys
  adjust media volume normally and never trigger speech. Notifications or the power key do not
  cause false announcements either.
- **Alarm announcement**: when a system alarm starts ringing, KeyTime speaks the current time
  once after a short delay.
- **Adjustable speech volume**: a 0–100% slider (default 80%) plus an optional “maximum volume”
  switch. This only changes the TTS gain, not any system volume stream.
- **Scheduled control**: automatically enable or pause announcements by time, with weekday
  repetition and cross-midnight rules (e.g. on at 22:00, off at 07:00), plus a preview of the
  next trigger.
- **Boot start and keep-alive**: foreground service, `START_STICKY`, and an AlarmManager
  watchdog; the service is restored after reboot, charging, or an app update.
- **Honest status**: the UI reports the real service state from `ActivityManager`, distinguishing
  stopped, running, and running-but-session-broken states.
- **Fully offline**: no `INTERNET` permission, no analytics, no ads, and no data collection.

## How it works

```text
Volume key pressed while locked / screen off
        │
        ▼
Persistent MediaSession in REMOTE + ABSOLUTE mode takes over the key
        │
        ▼
Foreground service receives onAdjustVolume(direction)
        │
        ├─ Device unlocked ──→ hand back to system volume, no speech
        └─ Still locked ─────→ TextToSpeech speaks the current time
```

Key design points:

1. **MediaSession remote volume**: the system dispatches volume keys to the active session's
   `VolumeProvider.onAdjustVolume()`. The app receives the key event itself and does not depend
   on any volume-value change.
2. **Silent media keep-alive**: a muted `AudioTrack` keeps the app registered as a media player,
   so the first key press on the lock screen is treated as a media-volume key instead of being
   consumed to wake the display.
3. **Lock-state check**: `KeyguardManager.isKeyguardLocked` is used instead of merely checking
   whether the screen is on.
4. **TTS self-healing**: the TTS binding is rebuilt after 60 seconds of idleness. If no
   `onStart` callback arrives within 1.5 seconds after speaking, the engine is considered stale;
   it is rebuilt and the utterance is retried once.
5. **Watchdog**: `AlarmManager.setExactAndAllowWhileIdle` checks every minute whether the service
   and its session are truly alive and restarts them if needed. A user-requested stop is respected.

> For the full version history and real-device debugging notes, see
> [DEVELOPMENT_GUIDE.md](DEVELOPMENT_GUIDE.md) and [HANDOVER.md](HANDOVER.md) (both in Chinese).

## Requirements

- Android 8.0 (API 26) or later; targetSdk 35.
- A Chinese TTS voice installed on the device (HyperOS / MIUI provides one by default).
- To build: JDK 17, Android SDK Platform 35, and Build-Tools 35.0.0.

## Installation and usage

### Build from source

```bash
git clone https://github.com/afei2357/keytime.git
cd keytime

# Debug build
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Release build (uses the default debug signature when no keystore is configured)
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

For a properly signed release, generate your own keystore and provide it through environment
variables. The build script never reads a keystore committed to the repository:

```bash
export KEYTIME_KEYSTORE=/path/to/release.jks
export KEYTIME_STORE_PASSWORD=your_store_password
export KEYTIME_KEY_ALIAS=keytime
export KEYTIME_KEY_PASSWORD=your_key_password
```

Install on a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You can also copy the APK to the phone and tap it, allowing “install unknown apps” when prompted.

> The package name is now the neutral `com.example.keytime`. If an older build with the previous
> package name is installed, uninstall it first; Android treats the two as separate apps and
> cannot upgrade one over the other.

### First run

1. Open the app. The announcement service starts automatically; grant the notification permission
   when prompted (it is used for the status notification and the pause/resume action).
2. Tap “Test announcement” to confirm that speech works.
3. On HyperOS, apply these keep-alive settings — missing any one of them may let the system kill
   the service overnight:
   - Settings → Apps → Manage apps → KeyTime → **Battery saver → No restrictions**;
   - On the same page → **Autostart → Allow**;
   - Open Recents and **lock** the KeyTime card.
4. Lock the phone and press Volume Down to hear the current time.

### Scheduled control

In the “Scheduled control” card:

- turn on the master switch;
- set the automatic enable and pause times;
- select repeat days (Sun–Sat), or use the “Every day” / “Weekdays” shortcuts;
- the card shows the current rule, the next trigger, and the running state.

The enable and pause times must differ. Cross-midnight rules (e.g. on at 22:00, off at 07:00)
are calculated against the following day automatically. A manual pause/resume takes effect
immediately and remains in force until the next scheduled event.

## Permissions

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the foreground service alive to receive volume keys |
| `POST_NOTIFICATIONS` | Show the service status notification (Android 13+; speech still works if denied) |
| `WAKE_LOCK` | Keep the CPU awake briefly during speech so it is not cut off |
| `RECEIVE_BOOT_COMPLETED` | Restore the service after reboot |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | Exact alarms for the watchdog and schedule, surviving Doze |
| `INTERNET` | **Not requested** — the app is fully offline |
| Accessibility | **Not requested** — MediaSession is used instead |

## Project structure

```text
app/src/main/java/com/example/keytime/
├── MainActivity.kt               # Settings: service control, keys/stream, volume, schedule
├── TimeAnnouncerService.kt       # Foreground service: key handling, alarm, speech, keep-alive
├── VolumeKeyInterceptor.kt       # MediaSession remote-volume takeover
├── TimeSpeaker.kt                # TTS wrapper: init, rebuild, volume, WakeLock
├── AutoSchedule.kt               # Schedule calculation and AlarmManager scheduling
├── Notifier.kt                   # Persistent notification and channel
├── NotificationActionReceiver.kt # Notification pause/resume action
├── BootCompletedReceiver.kt      # Restore after boot, charging, or app update
├── WatchdogReceiver.kt           # Per-minute liveness check
├── Prefs.kt                      # SharedPreferences settings
├── DebugLog.kt                   # Internal log (files/debug.log)
└── DebugReceiver.kt              # Guarded debug broadcasts
```

## Troubleshooting

**No sound when pressing a volume key?**
Check alarm/media volume, Do Not Disturb, and whether a Chinese voice is selected under
Settings → Accessibility → Text-to-speech output. Then tap “Test announcement” in the app.
If the test works but the lock screen does not, verify the battery, autostart, and Recents-lock
settings described above.

**The UI says the service is running, but lock-screen keys do nothing?**
The UI reflects the system service list. If it reports “running but key takeover is abnormal”,
tap “Stop” and then “Start” once to rebuild the session. If the service was killed, the watchdog
will try to restart it within a minute.

**The volume panel shrinks, but the actual volume does not change?**
This is expected while the MediaSession owns the key: the panel represents the session volume,
and the app does not modify any real audio stream. Once unlocked, the key is handed back to the
system and volume changes normally.

**Why not use an accessibility service?**
HyperOS applies a system-level filter to accessibility services from personally sideloaded apps:
the component is stripped shortly after being enabled and never appears in the settings list.
Since v3.0, KeyTime therefore uses the permission-free MediaSession approach.

## Contributing

Issues and pull requests are welcome:

1. Fork the repository and create a branch from `main`.
2. Match the existing code style. For new logic, comment on **why** it is needed, not just what it does.
3. For changes involving key handling, TTS, or background keep-alive, test on a real device and
   include the model, OS version, and reproduction steps.
4. Run `./gradlew assembleDebug` before opening a pull request.
5. Never commit personal information, device serial numbers, signing files, or secrets.

For HyperOS / MIUI compatibility reports, include the relevant parts of
`adb shell dumpsys media_session`, `adb shell dumpsys alarm`, or `files/debug.log`
after removing any sensitive data.

## License

Released under the [MIT License](LICENSE).

```text
Copyright (c) 2026 KeyTime contributors
```

You may use, modify, and distribute this project, including commercially, provided that the
copyright notice and license text are retained. The software is provided “as is”, without any
express or implied warranty.
