# Felix — Voice-controlled calling

Say **“Call Mom”** and Felix finds the contact and opens your phone's dialer.

No accounts, no backend, no tracking. Two ways to use it:

| Edition | Best for | Voice | Calling |
|---|---|---|---|
| **Android app** (recommended) | Daily use on your phone | One-tap voice + experimental hands-free wake word | Places the call (with your permission) or opens the dialer |
| **Website** | Trying Felix without installing anything | Tap-to-listen in Chrome on Android, or type a command anywhere | Opens the dialer via `tel:` (you tap Call to confirm) |

## Get Felix

### Option A — Android app (GitHub Releases)

1. Go to [**Releases**](https://github.com/JustABeing00/Felix/releases).
2. Download the latest APK (e.g. `felix-v0.4.0.apk`, ~85 MB — size is the offline voice model).
3. Copy it to your phone (USB, Drive, or download directly on the phone).
4. Tap the APK → **Install**. If prompted, allow **Install unknown apps** for your browser/file manager.
5. Open **Felix** and grant **Microphone** and **Contacts** when asked. Grant **Phone** only if you turn on automatic calling (see below).

Updates work the same way: download the newer APK from Releases and install it over the old one. Felix is sideload-only and is not on the Play Store.
Release APKs are signed with the developer's personal release key, so updates install seamlessly. (If you still have a pre-0.4.0 debug build installed, uninstall it first — one time only — then install the release.)

Requirements: **Android 8.0+**. No Google account, API key, or sign-in needed.

### Option B — Website (no install)

Host the static files in this repo on any HTTPS host (e.g. Cloudflare Pages, GitHub Pages), then open the URL in **Chrome on Android**. Or run it locally — see [Run the website locally](#run-the-website-locally).

## How to use

### One-tap voice (stable, both editions)

1. Make sure at least one contact is set up (pick from phone, or add manually).
2. Tap **Activate Felix**.
3. Say **“Call \<name\>”** (e.g. “Call Priya”).
4. Felix shows what it heard and what it found:
   - One match → a green **Call \<name\>** button (the app can also dial directly if you allow it).
   - Two people with the same name → Felix lists both and asks you to choose. It never guesses.
   - No match → “I couldn't find that contact.” Nothing is dialed.

### Hands-free mode (Android app only, experimental)

1. Tap **Activate Hands-Free Mode**. A *Felix hands-free is on* notification appears.
2. Say **“Felix”**, pause for the beep, then say **“Call \<name\>”**.
3. With automatic calling **off** (default), Felix shows the match plus an **Open dialer** notification. Tap it to dial.
4. Turn on **Allow automatic calling** (and grant the **Phone** permission at the prompt) to let Felix place the call after a match.

It is two steps by design: the offline wake-word engine that runs on the phone can't reliably recognize names, and Android doesn't let apps feed audio to Google's recognizer. So Felix listens for “Felix” offline, then hands the mic to the phone's speech recognizer for the actual command. Saying “Felix, call Priya” in one breath is not supported.

## Safety rules

- Ambiguous names are never guessed — Felix asks instead of calling.
- Numbers are never invented. A contact with no valid number cannot be called.
- A failed call is never retried automatically.
- Emergency numbers are never auto-dialed.
- The website never auto-dials silently: if the browser blocks the automatic `tel:` handoff, Felix tells you to tap the green **Call** button instead.

## Privacy

- **Android app:** has **no INTERNET permission**. The wake word is recognized fully offline on the phone. Commands go to the phone's own speech service (Google's recognizer on most phones), which may process audio on its servers depending on device settings. Contacts are read on the phone and held in memory only. The only stored data is your auto-call choice and a remembered number for contacts with several numbers.
- **Website:** contacts live in page JS memory only — reloading clears them. Nothing is uploaded anywhere (no `fetch` calls in the code). The mic is requested only after you tap Activate. Note: the browser's speech-recognition provider (e.g. Google's speech service in Chrome) may process audio remotely depending on your browser.

Permissions the Android app requests, and why:

| Permission | Used for |
|---|---|
| Microphone (`RECORD_AUDIO`) | Listening for the wake word and the voice command |
| Contacts (`READ_CONTACTS`) | Finding the person you asked to call |
| Phone (`CALL_PHONE`) | Only if you enable automatic calling |
| Foreground service + microphone + notification (`FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK`) | Keeping hands-free listening alive with a visible notification |

## Limitations (honest)

- **Web edition:** pages can't silently read your full address book (Contact Picker returns only contacts you explicitly select, Chrome on Android over HTTPS only), and pages can't guarantee a direct call — `tel:` opens the dialer and you typically confirm. Speech recognition varies by browser; Firefox/Safari/iOS may lack it, so a typed-command box is built in as fallback.
- **Hands-free wake word** uses an offline Vosk keyword match (free, no account). It is less accurate than paid alternatives: it can miss “Felix” or false-trigger on similar-sounding words. It cannot place a call on its own — a wrong wake-up still needs a valid matched command.
- **Background restrictions:** Android and vendor battery managers (notably on some Oppo/ColorOS, Xiaomi/MIUI, Samsung devices) can stop the foreground service. If hands-free stops, allow Felix unrestricted background activity / auto-launch in system settings and restart it.
- **“Call request sent” is not “connected.”** The system accepts the dial request; the phone then shows its call screen. Felix can't know whether the call connected or was answered.
- **Battery use is real.** Hands-free runs the mic plus the wake-word model continuously. Measure a 30-minute screen-off session before leaving it on all day.
- Very short similar-sounding names can collide on sound matching (e.g. “Sam”/“Sum”). With unique contacts this just works; with both saved, Felix asks instead of guessing.
- Full native test guide, troubleshooting dialog states, and tuning notes: see [`android/README.md`](android/README.md).

## Run the website locally

Any static server works. Do **not** use `file://` — mic, speech, and Contact Picker need `http(s)`.

```sh
git clone https://github.com/JustABeing00/Felix.git
cd Felix
python -m http.server 4173
# open http://localhost:4173
```

Node alternative:

```sh
npx --yes serve -l 4173 .
```

Notes:

- Desktop `tel:` links typically do nothing or prompt for an app — expected. Real dialer handoff must be tested on Android over HTTPS.
- On desktop, Contact Picker is usually unavailable → use **Add a test contact**, or type e.g. `Call Priya` in **Try without voice**.
- Deploy: push to a repo, point any static host (Cloudflare Pages, GitHub Pages, Netlify) at the repo root. Framework preset **None**, no build command, no env vars.

## Build from source

### Website tests

```sh
node --test tests/parser.test.js
```

18 tests, no dependencies: command parser, phone validation, `tel:` building, contact matching.

### Android APK

The offline speech model (~54 MB unpacked) is excluded from git. Restore it once:

1. Download `https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip` (~36 MB, Apache 2.0).
2. Unzip it into `android/app/src/main/assets/` so the folder `vosk-model-small-en-in-0.4/` (containing `am/`, `conf/`, `graph/`, `ivector/`) sits there. Without it the app shows “Wake-word model not configured”.

Then build (Windows example from `android/`):

```powershell
.\build-windows.ps1 testDebugUnitTest, assembleRelease   # unit tests + release APK
.\build-windows.ps1 lintDebug                            # Android Lint
```

The release APK lands at `android/app/build/outputs/apk/release/app-release.apk`.
Release builds are signed with a personal key read from `android/keystore.properties`
(gitignored, never committed — see the Android README for setup and backup).
On other systems use `./gradlew testDebugUnitTest assembleRelease` with JDK 17 and
Android SDK 34. Last run: 91 Android unit tests, 0 failures; Lint clean.

APKs and the model folder are gitignored (`android/.gitignore`) — release APKs are published manually under **Releases**, not committed.

## Project structure

```
index.html   — UI
styles.css   — dark mobile-first styling
logic.js     — parser + matcher + phone utils (shared by UI and tests)
app.js       — UI wiring: recognition, contacts, tel: handoff
tests/
  parser.test.js — Node built-in test runner, no dependencies
android/     — native app (see android/README.md)
  app/src/main/java/com/felix/app/ — activities, service, engines, pure logic
  app/src/test/                    — JVM unit tests
```

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Stuck on “Listening…” (website) | Every `onerror`/`onend` resets the UI. If the mic was denied → “Microphone access was denied.” |
| “Speech recognition isn't available” | Browser lacks Web Speech API. Type the command instead, or use Chrome on Android. |
| “No contacts configured” | Add a contact first (picker or manual). Voice never starts without one. |
| “Multiple contacts matched” | By design — pick one. Felix never guesses. |
| Dialer doesn't open after voice (website) | Chrome blocks automatic `tel:` once the Activate tap gesture expires during listening. Tap the green **Call …** button — that tap is a fresh gesture, so it works. |
| “Wake-word model not configured” (app) | Model folder missing from assets. Restore it (see Build from source) and rebuild. |
| Hands-free stops when the screen locks (app) | Battery manager killed the service. Allow unrestricted background activity for Felix, then restart hands-free. |
| “Felix” never triggers / triggers wrongly (app) | Offline wake-word limit. Speak closer, pause after “Felix”, note false-trigger words. It can't call on its own. |
| “Speech service is unreachable” (app) | No network for the phone's recognizer. Check data/Wi-Fi. |
| “Phone permission is missing” (app) | Needed only for automatic calling. Settings → Apps → Felix → Permissions → Phone. |

## Reporting issues

Include: app version (from Releases) or website URL, Android version + device model, exact steps and what Felix displayed (“Heard …” / status text), and whether it happens on one-tap voice, hands-free, or typed commands. Never paste private phone numbers into issues — use placeholder contacts.

## Versioning

Versions follow `versionName` in `android/app/build.gradle` (current: `0.4.0`, `versionCode` 4). Each GitHub Release publishes the matching release-signed APK built from that tag.
