# Free Song Recorder (Android)

**Free song title from recorded reel** — record your screen, then identify the song
that's playing, for free. Companion app to **Free Song from Reel**
(https://freesongfromreel.github.io), the free song-identification tool.

## The funnel (two ways)

```
app ──record──▶ result + Spotify link (via the Free Song from Reel backend)
app ──"Open site"──▶ freesongfromreel.github.io (identify ANY video by URL or file)
site ──error tip──▶ "save the video / screen-record it" ──▶ this app's Play Store page
```

- Site gets traffic → sends people who hit URL-blocked errors to **this app** (record + identify).
- App users can identify **any** video (not just ones they recorded) via the site.

## What it does

- **⏺ Record screen** — Android MediaProjection, foreground service, saves MP4 to
  app storage (`recordings/`). Banner ad hidden while recording.
- **⏹ Stop & save** — stops the service.
- **🎵 Identify song** — uploads the last recording to the backend
  (`reel2song-backend.onrender.com/api/detect-file`) → shows title, artist, Spotify link.
- **🌐 Open site** — deep link to the website (URL/file identification).
- **Banner ads (AdMob)** — only on idle / editing / processing screens, never during recording.

## Build

```bash
# Android Studio: open this folder, let Gradle sync, Run on a device/emulator (API 26+).
# CLI: ./gradlew assembleDebug  → app/build/outputs/apk/debug/app-debug.apk
```

Requires Android Studio / JDK 17. **No Android toolchain is available in the dev
container** — this is scaffolded to build on a machine with Android Studio.

## Before release (replace placeholders)

1. **AdMob app ID + banner unit** — `AndroidManifest.xml` (`APPLICATION_ID` meta) +
   `activity_main.xml` (`ads:adUnitId`). Current values are Google's **test** ad IDs
   (valid, show test banners).
2. **Real `applicationId`** if you want a different package.
3. **Review**: Android 13+ needs `POST_NOTIFICATIONS`; RECORD_AUDIO requested at runtime
   (needed for mic capture; internal-app audio capture is API 31+ — see `RecorderService`
   `ponytail:` note).
4. **Signing** — generate a keystore, configure `signingConfigs` for release.

## Known simplification

- MVP records **mic** audio (not internal app audio). On Android 11+ the reel's own
  audio needs `AudioPlaybackCapture` — the difference between "records the room" and
  "records the reel". Wire it when this becomes a real product.

## Repo remotes

```
origin   https://github.com/freesongfromreel/freesongfromreel-recorder.git
mirror   https://github.com/afrowalkmanstudios/songfromreel-recorder.git  (if created)
```

Both should be kept in sync (same pattern as the main site repo).