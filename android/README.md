# Felix for Android

The native Felix app. Sideload the APK from
[**Releases**](https://github.com/JustABeing00/Felix/releases) — no Play Store,
no account, no API key.

Requirements: **Android 8.0+** (minSdk 26). Current version: `0.4.0`.

Two ways to use it:

1. **One-tap voice (stable).** Tap *Activate Felix*, say "Call Priya", and the app places the call (or offers the dialer).
2. **Hands-free mode (experimental).** Felix listens for its wake word even with the screen off. You say "Felix", pause for a beep, then say "Call Priya".

Both use the same safety rules. Ambiguous names are never guessed, numbers are never invented, emergency numbers are never auto-dialed, and a failed call is never retried.

## Status: what hands-free can and can't do

| Behaviour | Status |
|---|---|
| Say "Felix, call Priya" in one breath | **Not supported.** See "Why two steps" below. |
| Say "Felix", pause, beep, then "Call Priya" | Built — verify on your device (see test guide) |
| Wake word works offline with the screen off | Built — verify on your device |
| Automatic call after the command, once you enable it | Built — verify on your device |
| Unit tests (parser, matching, sound-alikes, call policy, wake-word rules, cleanup) | **Passing: 91 of 91** |
| Compiles, packages and passes Android Lint with no errors | **Yes** |

Hands-free is experimental: the decision logic is fully unit-tested, but microphone behaviour, lock-screen behaviour, and vendor battery management vary by phone, so treat the test guide below as required reading on a new device.

### Why two steps

- The offline recognizer that runs on the phone with no account can't reliably produce contact names. In testing with synthetic speech, free-form recognition heard "Yash" as "yours", "Mom" as "worm" and "Rahul" as "colorado". When the names were given as a constrained grammar, Mom, Rahul and Dad came out correctly, but "Yash" was rejected outright (Vosk logged "Ignoring word missing in vocabulary: 'yash'").
- Google's speech recognizer does know names, and it is what makes one-tap voice work. But Android doesn't let an app feed it audio. So the app listens for "Felix" offline first, then hands the microphone to the phone's speech recognizer for the command.

### How Felix handles misheard names

Speech recognition spells what it hears, so "Satvinder" can come back as "satwinder" and "Yash" as "yesh". Felix compares the *sound* of the name, not just the spelling:

- Names are reduced to a canonical sound form first (w/v, ph/f, sh/s, aspirated/unaspirated consonants, doubled letters and vowel spellings are unified).
- After that, at most **one** small sound change is allowed across the whole name: a vowel swap (Yash/Yesh) or one documented consonant confusion (kharb/kharv, kharb/karg).
- Anything else rejects: added or missing letters (Ankit is not Ankita), two errors at once, and consonant swaps that don't sound alike (Dad is not Dan).
- A one-word command only compares against first names. "Kumar" never matches somebody's surname.
- **Exact matches always win.** If "Yash" is saved exactly, it is found even when "Yesh" is saved too.
- **Sound-alike twins ask, never call.** If "yes" could be Yash or Yesh, Felix shows both and places no call.
- Every sound match is shown honestly: "Found Satvinder Kumar (heard “satwinder kumar”)."
- The **Match names that sound alike** switch (on by default, below the auto-call switch) turns this off for exact-matches-only behaviour.

Known edge: very short names can collide on vowels alone ("Sam"/"Sum" count as the same sound). With unique contacts this just works; with two such contacts saved, Felix asks instead of guessing.

### Why no Porcupine

Porcupine (Picovoice) was the planned wake-word engine. Its free tier is discontinued, with no free or paid plan for personal projects. The app therefore uses an offline Vosk keyword match instead. It is free and needs no account, but it is less accurate, and it can false-trigger on words that sound similar. Expect some tuning on your device.

## Install

1. Download the latest APK from [**Releases**](https://github.com/JustABeing00/Felix/releases) (e.g. `felix-v0.4.0.apk`, ~85 MB — most of it is the offline voice model).
2. Copy it to your phone (USB, Drive, or download directly on the phone).
3. Tap it → **Install**. If prompted, allow **Install unknown apps** for your browser/file manager. Installing a newer release over an older one updates in place; no uninstall needed.
   (Coming from a pre-0.4.0 debug build? Uninstall it first — one time only — then install the release. Different signature.)
4. Open Felix. Allow **Microphone** and **Contacts** when asked. Allow **Phone** only if you turn on automatic calling.

### Permissions, and why each is needed

| Permission | Used for |
|---|---|
| Microphone (`RECORD_AUDIO`) | Listening for the wake word and the voice command |
| Contacts (`READ_CONTACTS`) | Finding the person you asked to call |
| Phone (`CALL_PHONE`) | Only if you enable automatic calling |
| Foreground service + microphone + notification (`FOREGROUND_SERVICE*`, `POST_NOTIFICATIONS`, `WAKE_LOCK`) | Keeping hands-free listening alive with a visible notification |

## Use

**One-tap voice:** tap *Activate Felix*, say "Call \<name\>". Confirm the result and the call.

**Hands-free, screen on (first check):**

1. Tap **Activate Hands-Free Mode**. The notification *Felix hands-free is on* appears, and the status reads "Listening for “Felix”. Say it, then pause briefly."
2. Say **"Felix"**, then pause. You should hear a short beep, and the status changes to "Say your command after the beep".
3. Say **"Call \<name\>"**. The status shows *Heard “…”* and then the outcome.
4. With automatic calling **off** (the default), expect "Found \<name\>. Automatic calling is off…" and a notification with *Open dialer*. Tap it to dial.

**Turn on automatic calling:**

1. Switch on **Allow automatic calling**. Read the acknowledgement and tap *I understand*.
2. Allow **Phone** when asked. The hint changes to "On…".
3. Repeat the hands-free steps. Expect "Call request sent for \<name\>. This doesn't mean it has connected." The phone should start dialling.

## Device test guide

Use a contact whose number you control, such as your own second SIM or a friend who agreed. Test with the phone on Wi-Fi or mobile data.

**A. One-tap voice** — tap *Activate Felix*, say "Call \<name\>". Confirm the result and the call.

**B. Hands-free, screen on** — follow the hands-free steps above with automatic calling off. Expect the match plus the *Open dialer* notification.

**C. Automatic calling** — enable it, repeat. Expect the phone to start dialling.

**D. Screen off (the real goal)**

1. With hands-free running, press the power button to lock the phone.
2. Say "Felix", pause, then "Call \<name\>". Record whether the beep sounds, whether the command is heard, and whether the call goes out.

**E. Safety checks (each should place no call)**

- Two contacts with the same first name. Say "Felix", then "Call \<that name\>". Expect "More than one contact matches". No call.
- Say "Felix", then stay silent. Expect "I didn't hear a command" after about 10 seconds.
- Say "Felix" followed by a name that isn't in your contacts. Expect "I couldn't find…". No call.
- A wrong wake or a missed command must never place a call on its own — that is the invariant, even though misfires are a known limitation.

**F. Stop**

1. Tap **Stop listening** in the notification, or in the app. Expect "Listening stopped." and the notification to disappear.
2. Say "Felix" again. Nothing should happen, which confirms the microphone is released.

**G. Battery**

1. Note the battery percentage, run hands-free for 30 minutes with the screen off, and note it again. The mic plus the wake-word model run continuously — measure before leaving it on all day.

## Build from source

### Prerequisites

- JDK 17.
- Android SDK with `platforms;android-34` and `build-tools;34.0.0` (plus `platform-tools`).
- The Gradle wrapper in this folder pins the Gradle version — use `gradlew` / `gradlew.bat`, no separate Gradle install needed.

### 1. Speech model (not in git, required once)

- Model: `vosk-model-small-en-in-0.4` (Indian English, Apache 2.0 licence, from alphacephei.com).
- Location: `app/src/main/assets/vosk-model-small-en-in-0.4/`. It is excluded from git (`.gitignore`).
- Size: about 36 MB to download, about 54 MB unpacked. The APK is about 85 MB.
- If the folder is missing, the app shows "Wake-word model not configured" and won't start listening.
- To restore it: download `https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip` and unzip it into `app/src/main/assets/`. It must contain `am/`, `conf/`, `graph/` and `ivector/`.

No API key, no account and no `local.properties` entry is needed.

### 1b. Release signing key (one-time setup, required for release builds)

Release APKs are signed with a personal key so updates install seamlessly forever.
Debug builds keep working without any of this.

1. Generate the key **outside the repo** (example):
   `keytool -genkeypair -keystore D:\felix-keys\felix-release.jks -alias felix -keyalg RSA -keysize 2048 -validity 10950`
2. Back up the `.jks` file **and both passwords in at least two places**
   (e.g. a USB stick + your password manager). Losing the key means the app can
   never be updated again under the same package name — this file is irreplaceable.
3. Create `android/keystore.properties` (gitignored, never commit it):
   ```
   storeFile=D:/felix-keys/felix-release.jks
   storePassword=<store password>
   keyAlias=felix
   keyPassword=<key password>
   ```
   `app/build.gradle` reads it for `signingConfigs.release` and refuses to build
   a release without it.

### 2. Build

Standard build with the wrapper (works on Windows, macOS, Linux):

```sh
cd android
./gradlew testDebugUnitTest assembleDebug   # unit tests + debug APK
./gradlew lintDebug                         # Android Lint
```

On Windows, `.\gradlew.bat ...` equivalents apply. There is also a `build-windows.ps1` helper that runs the same tasks but assumes a portable toolchain layout via environment variables (see the top of the script) — with a standard Android Studio / SDK install, just use the wrapper commands above.

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Project layout

```
android/
├── build-windows.ps1              Windows build helper (unit tests + debug APK)
├── gradlew / gradlew.bat          Gradle wrapper (pinned version, no install needed)
└── app/
    ├── build.gradle
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── assets/vosk-model-small-en-in-0.4/   offline speech model (not in git)
        │   ├── java/com/felix/app/
        │   │   ├── MainActivity.kt            screen: one-tap voice + hands-free controls
        │   │   ├── FelixListeningService.kt   foreground service: wake word → command → call
        │   │   ├── VoskEngine.kt              model loading + offline wake-word spotter
        │   │   ├── FelixLogic.kt              parser, phone rules, contact matching (pure)
        │   │   ├── WakeWord.kt                wake-word rules (pure)
        │   │   ├── CallPolicy.kt              when a call may happen + dispatcher (pure)
        │   │   ├── HandsFreeModels.kt         states, resource cleanup, setup checks (pure)
        │   │   ├── HandsFreeStatus.kt         status shown in the app
        │   │   ├── ContactsRepository.kt      reads contacts on the phone
        │   │   ├── TelecomCallExecutor.kt     places the call via Telecom
        │   │   ├── FelixNotifications.kt      listening notification and result notifications
        │   │   ├── FelixSettings.kt           auto-call setting and remembered numbers
        │   │   └── HandsFreeSetup.kt          what must be ready before listening
        │   └── res/                           layouts, colors, strings
        └── test/java/com/felix/app/           JVM unit tests (80)
```

### Running tests

```sh
./gradlew testDebugUnitTest
```

Results land in `app/build/test-results/testDebugUnitTest/`. Last run: 91 tests, 0 failures.

## Troubleshooting

| You see | Likely cause | What to do |
|---|---|---|
| "Wake-word model not configured" | Model folder missing from assets | Restore it (Build from source, step 1), then rebuild |
| Hands-free stops after some time or when the screen locks | Vendor battery management or auto-start blocked | Settings → Battery → Felix → unrestricted. Allow auto-launch / background activity in the phone's app-management settings (names differ by vendor: Samsung, Xiaomi/MIUI, Oppo/ColorOS, etc.) |
| "Android stopped hands-free listening" | Android or the battery manager killed the service | Restart hands-free. Check the battery settings above. |
| "Felix" never triggers | Offline wake word missed it | Speak closer and clearly, and pause after "Felix". Check the Troubleshooting dialog. |
| "Felix" triggers when you didn't say it | Offline false positive | Note the words. This is a tuning limit, and it can't call on its own. |
| "Speech service is unreachable" | No network for the phone's recognizer | Check data or Wi-Fi |
| "Phone permission is missing" | CALL_PHONE not granted | Settings → Apps → Felix → Permissions → Phone |
| Call goes out on the lock screen but no ringing | Telecom or SIM choice | Set your default calling SIM in phone settings. The call screen is controlled by the phone. |

The **Troubleshooting** button in the app shows the current state, the permissions and the model status.

## Privacy

- The app has **no INTERNET permission**. Felix doesn't upload anything itself.
- The wake word is recognized entirely on the phone, offline.
- Commands go to the phone's speech service (Google's recognizer on most phones) after you say "Felix". That service can process audio on its servers, depending on your device settings.
- Contacts are read on the phone when a command needs them. They're held in memory only.
- The only saved data is your auto-call choice and any number you picked for a contact with several numbers.

## Known limitations (honest)

- **Hands-free needs real-device verification on each new phone.** Unit tests cover the decision logic; the microphone, wake word, lock-screen behaviour, the recognizer started from a background service, and the Telecom call all depend on the device and OS vendor.
- **Two steps, not one phrase.** See "Why two steps".
- **The wake word is less accurate than a paid engine would be.** In testing it caught "Felix" and ignored unrelated speech, but it can still miss or false-trigger. Expect tuning.
- **Background restrictions.** Android and vendor battery managers can stop a foreground service. The app surfaces the stop, but can't prevent it.
- **"Call request sent" is not "connected".** Telecom accepts the request. The phone then shows its call screen, and Felix can't know whether the call connected or was answered.
- **Battery use is real.** The microphone and the wake-word model run continuously while hands-free is on. Measure it (test G).

## One-tap (MVP) notes

- One-tap calling uses `ACTION_CALL` from the visible app, which Android allows. Hands-free uses Telecom because a background service can't launch the call screen.
- The contact list groups by contact ID, not by name. Two different people with the same name are treated as ambiguous and never guessed.
- Choosing one of a contact's several numbers saves it as that contact's preferred number.

## Reporting issues

Include: app version (from Releases), Android version + device model, exact steps and what Felix displayed, and whether it happens on one-tap voice or hands-free. Never paste private phone numbers into issues — use placeholder contacts.
