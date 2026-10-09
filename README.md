# Felix — Minimal Voice-Controlled Calling Website (MVP v1)

Say **“Call Yash”**, Felix finds the contact and opens your phone's dialer.

Static site. No backend, no database, no accounts, no tracking, no AI API.
HTML + CSS + vanilla JS + Web Speech API + Contact Picker API + `tel:` handoff.

## Project structure

```
index.html   — UI
styles.css   — dark mobile-first styling
logic.js     — parser + matcher + phone utils (shared by UI and tests)
app.js       — UI wiring: recognition, contacts, tel: handoff
tests/
  parser.test.js — Node built-in test runner, no dependencies
```

## 1. Open in VS Code (Windows)

```powershell
cd D:\Felix
code .
```

## 2. Run locally (Windows)

Any static server works. Do **not** rely on `file://` — mic, speech, and Contact Picker need `http(s)`.

```powershell
cd D:\Felix
python -m http.server 4173
```

Then open:

```
http://localhost:4173
```

Node alternative:

```powershell
npx --yes serve -l 4173 .
```

## 3. Test on desktop

1. `http://localhost:4173` should show Felix, “Activate Felix”, “Ready when you are.”
2. Contact Picker will likely be unavailable on desktop → use **Add a test contact**:
   - Name: `Yash`, Phone: `+911234567890` → Add contact.
   - Add `Rahul Sharma` / `Rahul Verma` to test disambiguation.
3. Parser test without voice: type `Call Yash` in **Try without voice** → Go.
   - Exact match → “Found Yash.” + green **Call Yash** button (`tel:+911234567890`).
   - Type `Call Rahul` with two Rahuls → “Multiple contacts matched. Please choose one.”
   - Type `Call Nobody` → “I couldn't find that contact.”
4. Run automated tests:

```powershell
node --test tests/parser.test.js
```

5. Desktop `tel:` links typically do nothing or prompt for an app — that is expected. Real dialer handoff must be tested on Android.

## 4. Deploy to Cloudflare Pages

Static HTTPS hosting is **required** for mic + Contact Picker on Android.

Option A — dashboard:

1. Push this folder to a GitHub repo.
2. Cloudflare dashboard → Workers & Pages → Create → Pages → Connect repo.
3. Build settings: framework preset **None**, build command **empty**, output directory **`.`** (repo root).
4. Deploy. You get `https://felix.pages.dev` (or your name).

Option B — CLI:

```powershell
npm install -g wrangler
wrangler login
wrangler pages deploy . --project-name felix
```

No API keys, no env vars, no backend.

## 5. Test on your Android phone

1. Open the deployed **HTTPS** URL in **Chrome on Android**.
2. Grant **microphone** permission when prompted.
3. Tap **Pick from phone** → select 1–2 test contacts (names + numbers only).
   - Or use **Add a test contact** manually.
4. Tap **Activate Felix** → say **“Call Yash”** clearly.
5. Expect: `I heard: …` → `Found Yash.` → `Opening your phone app…` → dialer opens.
6. Android/Chrome may only open the dialer pre-filled and require you to tap **Call** — that is a platform limitation, and the page keeps a **Call Yash** button for exactly that case.

## 6. Privacy

- Contacts live in page JS memory only. Reloading clears them.
- Nothing is uploaded anywhere. No fetch calls in the code.
- Mic is requested only after you tap **Activate Felix**. No continuous listening, no wake-word detection.
- Note: the browser's speech-recognition provider may process audio remotely (e.g. Chrome uses Google's speech service). That depends on your browser, not on Felix.

## 7. Browser limitations (honest)

- Web pages cannot silently read your full address book. The Contact Picker only returns contacts you explicitly select, and only where supported (Chrome Android over HTTPS). Hence the manual fallback.
- Web pages cannot guarantee a direct call. `tel:` opens the dialer; the user typically confirms. Auto-handoff may be blocked → the manual **Call …** link is the fallback.
- SpeechRecognition availability varies: Chrome Android (`en-IN`) works; Firefox/Safari/iOS may lack it → the typed-command box is the fallback.
- `file://` URLs break mic/picker/secure-context features. Use localhost or HTTPS.

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Stuck on “Listening…” | Fixed in code: every `onerror`/`onend` resets UI. If mic denied → “Microphone access was denied.” |
| “Speech recognition isn't available” | Browser lacks Web Speech API. Type the command instead, or use Chrome Android. |
| “No contacts configured” | Add a contact first (picker or manual). Voice never starts without one. |
| “Multiple contacts matched” | By design — pick one. Felix never guesses. |
| Dialer doesn't open | Browser blocked auto-handoff. Tap the green **Call …** button. |
