"use strict";

/* Felix MVP — UI wiring. Vanilla JS, no dependencies.
 * Contacts stay in memory only. No analytics, no network calls.
 */

(function () {
  const L = window.FelixLogic;
  const $ = (id) => document.getElementById(id);

  const els = {};
  ["activateBtn", "stopBtn", "status", "heard", "result", "contactSupport",
   "pickContactsBtn", "clearContactsBtn", "contactList", "contactCount",
   "manualName", "manualPhone", "manualAddBtn", "manualMsg",
   "textCommand", "textGoBtn", "textForm", "setupHint"
  ].forEach((id) => { els[id] = document.getElementById(id); });

  let contacts = [];
  let recognition = null;
  let listening = false;

  function getRecognitionCtor() {
    if (typeof window === "undefined") return null;
    return window.SpeechRecognition || window.webkitSpeechRecognition || null;
  }

  function recognitionSupported() {
    return Boolean(getRecognitionCtor());
  }

  function contactPickerSupported() {
    try {
      return Boolean(
        navigator.contacts &&
        typeof navigator.contacts.select === "function" &&
        window.isSecureContext !== false
      );
    } catch (e) {
      return false;
    }
  }

  function setStatus(msg) {
    els.status.textContent = msg;
  }

  function setHeard(msg) {
    els.heard.textContent = msg || "";
    els.heard.hidden = !msg;
  }

  function clearResult() {
    els.result.innerHTML = "";
    els.result.hidden = true;
  }

  function showResult(node) {
    els.result.innerHTML = "";
    els.result.appendChild(node);
    els.result.hidden = false;
  }

  function el(tag, cls, text) {
    const n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  // ---------- Contacts UI ----------

  function renderContacts() {
    const list = els.contactList;
    list.innerHTML = "";
    els.contactCount.textContent =
      contacts.length === 0
        ? "No contacts configured yet."
        : contacts.length + (contacts.length === 1 ? " contact ready." : " contacts ready.");

    if (contacts.length === 0) {
      const li = el("li", "contact-empty", "Pick contacts above or add one manually to test calling.");
      list.appendChild(li);
    } else {
      contacts.forEach((c) => {
        const li = document.createElement("li");
        li.className = "contact-item";
        const info = el("div", "contact-info");
        const name = el("div", "contact-name", c.name);
        const nums = el("div", "contact-nums", c.phones.length > 0 ? c.phones.join("  ·  ") : "No valid number");
        info.appendChild(name);
        info.appendChild(nums);
        const rm = el("button", "btn-ghost btn-small", "Remove");
        rm.type = "button";
        rm.setAttribute("aria-label", "Remove " + c.name);
        rm.addEventListener("click", () => {
          contacts = contacts.filter((x) => x.id !== c.id);
          renderContacts();
          updateSetupHint();
          setStatus("Ready when you are.");
        });
        li.appendChild(info);
        li.appendChild(rm);
        list.appendChild(li);
      });
    }
    els.clearContactsBtn.disabled = contacts.length === 0;
    updateSetupHint();
  }

  function updateSetupHint() {
    if (contacts.length === 0) {
      els.setupHint.hidden = false;
    } else {
      els.setupHint.hidden = true;
    }
  }

  async function pickContacts() {
    if (!contactPickerSupported()) {
      setStatus("Contact Picker isn't supported here. Add a contact manually below.");
      els.manualName.focus();
      return;
    }
    try {
      setStatus("Opening contact picker…");
      const picked = await navigator.contacts.select(["name", "tel"], { multiple: true });
      if (!picked || picked.length === 0) {
        setStatus("No contacts selected. Ready when you are.");
        return;
      }
      let added = 0;
      let skipped = 0;
      for (const p of picked) {
        const names = Array.isArray(p.name) ? p.name : (p.name ? [p.name] : []);
        const tels = Array.isArray(p.tel) ? p.tel : (p.tel ? [p.tel] : []);
        const displayName = (names[0] || "").trim();
        if (!displayName || tels.length === 0) { skipped++; continue; }
        const c = L.createContact(displayName, tels);
        if (c.name && c.phones.length > 0) {
          contacts.push(c);
          added++;
        } else {
          skipped++;
        }
      }
      renderContacts();
      if (added > 0 && skipped === 0) setStatus("Ready when you are.");
      else if (added > 0) setStatus("Added " + added + " contact(s). " + skipped + " skipped (missing name or number).");
      else setStatus("Selected contacts had no usable name + number. Add one manually below.");
    } catch (err) {
      if (err && err.name === "NotAllowedError") {
        setStatus("Contact access was denied. You can still add a contact manually.");
      } else if (err && err.name === "AbortError") {
        setStatus("Contact picker cancelled. Ready when you are.");
      } else {
        setStatus("Couldn't read contacts. Add one manually below.");
      }
    }
  }

  function addManualContact() {
    const name = els.manualName.value.trim().replace(/\s+/g, " ");
    const phoneRaw = els.manualPhone.value.trim();
    els.manualMsg.textContent = "";
    if (!name) {
      els.manualMsg.textContent = "Enter a contact name, e.g. Yash.";
      els.manualName.focus();
      return;
    }
    const v = L.validatePhone(phoneRaw);
    if (!v.ok) {
      els.manualMsg.textContent = v.message;
      els.manualPhone.focus();
      return;
    }
    contacts.push(L.createContact(name, [v.normalized]));
    els.manualName.value = "";
    els.manualPhone.value = "";
    renderContacts();
    setStatus("Ready when you are.");
  }

  // ---------- Command handling (shared by voice + typed input) ----------

  function handleCommandText(rawText) {
    clearResult();
    if (!rawText || String(rawText).trim() === "") {
      setStatus("I didn't catch a command. Try “Call Yash”.");
      return;
    }
    setHeard("I heard: “" + String(rawText).trim() + "”");

    const parsed = L.parseCommand(rawText);
    if (!parsed.ok) {
      setStatus(parsed.message);
      return;
    }
    if (contacts.length === 0) {
      setStatus("No contacts configured yet. Add a contact below first.");
      return;
    }

    const m = L.matchContact(parsed.name, contacts);

    if (m.status === "not-found") {
      setStatus("I couldn't find that contact.");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "No match for “" + parsed.name + "”."));
      wrap.appendChild(el("p", "result-sub", "Check the spelling or add this contact below, then try again."));
      const retry = el("button", "btn-secondary", "Activate Felix again");
      retry.type = "button";
      retry.addEventListener("click", startListening);
      wrap.appendChild(retry);
      showResult(wrap);
      return;
    }

    if (m.status === "ambiguous") {
      setStatus("Multiple contacts matched. Please choose one.");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "Which “" + parsed.name + "” did you mean?"));
      const btns = el("div", "choice-row");
      m.matches.forEach((c) => {
        const b = el("button", "btn-choice", c.name + (c.phones[0] ? " · " + c.phones[0] : ""));
        b.type = "button";
        b.addEventListener("click", () => resolveContactForCall(c));
        btns.appendChild(b);
      });
      wrap.appendChild(btns);
      wrap.appendChild(el("p", "result-sub", "I never guess when several contacts match."));
      showResult(wrap);
      return;
    }

    if (m.status === "no-number") {
      setStatus("That contact has no valid phone number.");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", m.contact.name + " has no usable number."));
      wrap.appendChild(el("p", "result-sub", "Remove this entry and re-add it with a valid number."));
      showResult(wrap);
      return;
    }

    if (m.status === "need-choice") {
      setStatus("Found " + m.contact.name + ". Which number?");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "Found " + m.contact.name + "."));
      wrap.appendChild(el("p", "result-sub", "This contact has more than one number — pick one:"));
      const btns = el("div", "choice-row");
      m.phones.forEach((p) => {
        const b = el("button", "btn-choice", p);
        b.type = "button";
        b.addEventListener("click", () => offerCall(m.contact, p));
        btns.appendChild(b);
      });
      wrap.appendChild(btns);
      showResult(wrap);
      return;
    }

    // found
    setStatus("Found " + m.contact.name + ".");
    offerCall(m.contact, m.phone);
  }

  function resolveContactForCall(contact) {
    const phones = (contact.phones || []).filter((p) => /^\+?\d{7,15}$/.test(p));
    if (phones.length === 0) {
      setStatus("That contact has no valid phone number.");
      return;
    }
    if (phones.length === 1) {
      offerCall(contact, phones[0]);
    } else {
      setStatus("Found " + contact.name + ". Which number?");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "Found " + contact.name + "."));
      const btns = el("div", "choice-row");
      phones.forEach((p) => {
        const b = el("button", "btn-choice", p);
        b.type = "button";
        b.addEventListener("click", () => offerCall(contact, p));
        btns.appendChild(b);
      });
      wrap.appendChild(btns);
      showResult(wrap);
    }
  }

  function offerCall(contact, phone) {
    const telUrl = L.buildTelUrl(phone);
    if (!telUrl) {
      setStatus("That number isn't valid, so I can't open the phone app.");
      return;
    }
    const wrap = el("div", "result-block");
    wrap.appendChild(el("p", "result-title", "Found " + contact.name + "."));
    wrap.appendChild(el("p", "result-sub", phone));
    wrap.appendChild(el("p", "result-note",
      "Opening your phone app… If nothing happens, use the button below. You may still need to tap Call in your dialer."));

    const callBtn = document.createElement("a");
    callBtn.className = "btn-call";
    callBtn.href = telUrl;
    callBtn.textContent = "Call " + contact.name;
    callBtn.setAttribute("aria-label", "Call " + contact.name + " at " + phone);
    wrap.appendChild(callBtn);

    const again = el("button", "btn-secondary", "Activate Felix again");
    again.type = "button";
    again.addEventListener("click", startListening);
    wrap.appendChild(again);

    showResult(wrap);
    setStatus("Opening your phone app…");

    // Attempt automatic handoff once. Keep details + button visible regardless,
    // because browsers may block it or just open the dialer.
    try {
      window.location.href = telUrl;
    } catch (e) {
      setStatus("Couldn't open the phone app automatically. Tap “Call " + contact.name + "” below.");
    }
  }

  // ---------- Speech recognition ----------

  function setListeningUI(on) {
    listening = on;
    document.body.classList.toggle("is-listening", on);
    els.activateBtn.disabled = on;
    els.stopBtn.hidden = !on;
    els.activateBtn.querySelector(".btn-label").textContent = on ? "Listening…" : "Activate Felix";
  }

  function startListening() {
    clearResult();
    setHeard("");

    if (contacts.length === 0) {
      setStatus("Add at least one contact below first, then tap Activate Felix.");
      els.manualName.focus();
      return;
    }
    if (!recognitionSupported()) {
      setStatus("Speech recognition isn't available in this browser.");
      els.textCommand.focus();
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "Voice input isn't supported here."));
      wrap.appendChild(el("p", "result-sub", "Type a command below instead, e.g. “Call Yash”. Chrome on Android supports voice."));
      showResult(wrap);
      return;
    }

    const Ctor = getRecognitionCtor();
    try {
      if (recognition) { try { recognition.abort(); } catch (e) {} recognition = null; }
      recognition = new Ctor();
    } catch (e) {
      setStatus("Couldn't start speech recognition. Type your command below instead.");
      return;
    }

    recognition.lang = "en-IN";
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    recognition.continuous = false;

    let gotResult = false;

    recognition.onresult = (event) => {
      gotResult = true;
      let transcript = "";
      try {
        transcript = event.results[0][0].transcript || "";
      } catch (e) { transcript = ""; }
      stopRec();
      if (!transcript.trim()) {
        setStatus("I didn't catch that. Try again.");
        return;
      }
      handleCommandText(transcript);
    };

    recognition.onerror = (event) => {
      const err = (event && event.error) || "";
      stopRec();
      if (err === "not-allowed" || err === "service-not-allowed") {
        setStatus("Microphone access was denied.");
      } else if (err === "no-speech") {
        setStatus("I didn't catch that. Try again.");
      } else if (err === "audio-capture") {
        setStatus("No microphone found. Type your command below instead.");
      } else if (err === "network") {
        setStatus("Recognition service failed (network). Try again or type the command.");
      } else if (err === "aborted") {
        setStatus("Stopped. Tap Activate Felix to try again.");
      } else {
        setStatus("Recognition failed. Try again or type the command below.");
      }
    };

    recognition.onend = () => {
      const wasListening = listening;
      stopRec();
      if (wasListening && !gotResult) {
        if (!els.status.textContent.startsWith("Found")) {
          setStatus("I didn't catch that. Try again.");
        }
      }
    };

    recognition.onnomatch = () => {
      stopRec();
      setStatus("I didn't catch that. Try again.");
    };

    try {
      recognition.start();
      setListeningUI(true);
      setStatus("Listening…");
      setHeard("");
    } catch (e) {
      stopRec();
      setStatus("Couldn't start listening. Try again or type the command below.");
    }
  }

  function stopRec() {
    if (recognition) {
      try { recognition.onresult = null; recognition.onerror = null; recognition.onend = null; } catch (e) {}
      // If still listening, abort triggers onend; detach first so we control UI.
      try { recognition.abort(); } catch (e) {}
      recognition = null;
    }
    if (listening) setListeningUI(false);
    else { els.activateBtn.disabled = false; els.stopBtn.hidden = true; document.body.classList.remove("is-listening"); }
  }

  function userStop() {
    // Detach handlers so abort doesn't overwrite the "Stopped" message.
    if (recognition) {
      try { recognition.onresult = null; recognition.onerror = null; recognition.onend = null; } catch (e) {}
      try { recognition.abort(); } catch (e) {}
      recognition = null;
    }
    setListeningUI(false);
    setStatus("Stopped. Tap Activate Felix to try again.");
  }

  // ---------- Init ----------

  function init() {
    renderContacts();

    if (!recognitionSupported()) {
      setStatus("Speech recognition isn't available in this browser.");
      const wrap = el("div", "result-block");
      wrap.appendChild(el("p", "result-title", "Voice input isn't supported in this browser."));
      wrap.appendChild(el("p", "result-sub", "Type a command below to test, e.g. “Call Yash”. Use Chrome on Android for voice."));
      showResult(wrap);
    } else {
      setStatus("Ready when you are.");
    }

    if (!contactPickerSupported()) {
      els.contactSupport.textContent =
        "Contact Picker isn't supported in this browser — add test contacts manually below. (Chrome on Android over HTTPS supports picking.)";
      els.pickContactsBtn.disabled = true;
    } else {
      els.contactSupport.textContent =
        "Your phone's contacts never leave this page. Picks stay in this tab's memory only — nothing is uploaded.";
    }

    els.activateBtn.addEventListener("click", startListening);
    els.stopBtn.addEventListener("click", userStop);
    els.pickContactsBtn.addEventListener("click", pickContacts);
    els.manualAddBtn.addEventListener("click", addManualContact);
    els.clearContactsBtn.addEventListener("click", () => {
      contacts = [];
      renderContacts();
      setStatus("Contacts cleared. Ready when you are.");
    });
    els.textForm.addEventListener("submit", (e) => {
      e.preventDefault();
      handleCommandText(els.textCommand.value);
    });

    // Escape closes listening affordance cleanly.
    document.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && listening) userStop();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  // Expose for debugging without leaking contact contents.
  window.FelixApp = {
    getContactCount: () => contacts.length,
    isListening: () => listening,
  };
})();
