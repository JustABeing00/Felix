"use strict";

/* Felix shared logic: command parser + contact matching + phone utils.
 * Works in the browser (window.FelixLogic) and in Node (module.exports).
 * No dependencies. No network. No logging of personal data.
 */

function normalizeWhitespace(s) {
  return String(s).replace(/\s+/g, " ").trim();
}

/**
 * Parse an English voice command into a contact name.
 * Supported: "Felix, call Yash", "Call Yash", "Please call Rahul", etc.
 * @param {unknown} raw
 * @returns {{ok:true, name:string} | {ok:false, reason:'empty'|'unsupported', message:string}}
 */
function parseCommand(raw) {
  if (typeof raw !== "string" || normalizeWhitespace(raw) === "") {
    return { ok: false, reason: "empty", message: "I didn't catch a command. Try “Call Yash”." };
  }

  let cleaned = normalizeWhitespace(raw);

  // Strip optional filler words (case-insensitive, whole words only).
  // Order matters: multi-word phrases first.
  cleaned = cleaned
    .replace(/\bcan you\b/gi, " ")
    .replace(/\bcould you\b/gi, " ")
    .replace(/\bwould you\b/gi, " ")
    .replace(/\bplease\b/gi, " ")
    .replace(/\bfelix\b/gi, " ")
    .replace(/\bhey\b/gi, " ");

  cleaned = normalizeWhitespace(cleaned);
  // Strip leading/trailing punctuation left over after removals (e.g. "Felix, ...").
  cleaned = cleaned.replace(/^[,\.\?\!\:\;\-]+/, "").trim();
  cleaned = normalizeWhitespace(cleaned);

  // Must now start with the call action.
  const callMatch = cleaned.match(/^call\b\s*/i);
  if (!callMatch) {
    return {
      ok: false,
      reason: "unsupported",
      message: "I only understand call commands yet. Try “Call Yash”.",
    };
  }

  let name = cleaned.slice(callMatch[0].length);
  name = normalizeWhitespace(name);
  // Remove trailing sentence punctuation: "Yash.", "Dad?", "Mom!"
  name = name.replace(/[\.\?\!\,]+$/g, "").trim();
  // Remove leading punctuation leftovers.
  name = name.replace(/^[\,\.\?\!\:\;]+/, "").trim();
  name = normalizeWhitespace(name);

  if (name === "") {
    return { ok: false, reason: "empty", message: "Who should I call? Try “Call Yash”." };
  }

  // Reject commands that are clearly not names (digits only, etc.).
  // Names must contain at least one letter.
  if (!/[A-Za-z\u00C0-\u024F]/.test(name)) {
    return { ok: false, reason: "unsupported", message: "That doesn't look like a contact name. Try “Call Yash”." };
  }

  return { ok: true, name };
}

/**
 * Validate a manually entered / stored phone number.
 * Accepts digits with optional leading +, spaces, dashes, parentheses.
 * Requires 7–15 digits after stripping formatting.
 * @param {unknown} raw
 * @returns {{ok:true, normalized:string} | {ok:false, message:string}}
 */
function validatePhone(raw) {
  if (typeof raw !== "string" || raw.trim() === "") {
    return { ok: false, message: "Enter a phone number, e.g. +911234567890." };
  }
  const trimmed = raw.trim();
  // Only allow sane phone characters.
  if (!/^[+\d][\d\s\-\(\)]*$/.test(trimmed)) {
    return { ok: false, message: "That phone number has invalid characters. Use digits and an optional leading +." };
  }
  const digits = trimmed.replace(/\D/g, "");
  const hasPlus = trimmed.trim().startsWith("+");
  if (digits.length < 7 || digits.length > 15) {
    return { ok: false, message: "Enter a valid phone number with 7–15 digits." };
  }
  return { ok: true, normalized: (hasPlus ? "+" : "") + digits };
}

/**
 * Build a safe tel: URL from an already-validated number.
 * Returns null for invalid input (never build tel: with arbitrary text).
 * @param {string} normalizedPhone digits with optional leading +
 */
function buildTelUrl(normalizedPhone) {
  if (typeof normalizedPhone !== "string") return null;
  const p = normalizedPhone.trim();
  if (!/^\+?\d{7,15}$/.test(p)) return null;
  return "tel:" + p;
}

/**
 * Normalize a contact shape from Contact Picker or manual entry.
 * @param {string} name
 * @param {string[]} phones
 */
function createContact(name, phones) {
  const cleanName = normalizeWhitespace(String(name || ""));
  const list = Array.isArray(phones) ? phones : [];
  const valid = [];
  for (const p of list) {
    const v = validatePhone(p);
    if (v.ok) valid.push(v.normalized);
  }
  return {
    id: "c" + Math.random().toString(36).slice(2, 10),
    name: cleanName,
    phones: valid,
  };
}

function firstNameOf(fullName) {
  return normalizeWhitespace(String(fullName || "")).split(" ")[0].toLowerCase();
}

/**
 * Safe, predictable contact matching. Never guesses.
 * @param {string} queryName already-extracted name from parseCommand
 * @param {Array<{id?:string,name:string,phones:string[]}>} contacts
 * @returns
 *  {status:'not-found', query:string} |
 *  {status:'ambiguous', matches:Array} |
 *  {status:'no-number', contact:Object} |
 *  {status:'need-choice', contact:Object, phones:string[]} |
 *  {status:'found', contact:Object, phone:string}
 */
function matchContact(queryName, contacts) {
  const q = normalizeWhitespace(String(queryName || "")).toLowerCase();
  const list = Array.isArray(contacts) ? contacts : [];

  if (q === "" || list.length === 0) {
    return { status: "not-found", query: String(queryName || "") };
  }

  // 1. Case-insensitive exact full-name match.
  const exact = list.filter(
    (c) => normalizeWhitespace(String(c && c.name || "")).toLowerCase() === q
  );
  if (exact.length === 1) {
    return withPhoneResolution(exact[0]);
  }
  if (exact.length > 1) {
    return { status: "ambiguous", matches: exact.slice() };
  }

  // 2. First-name match, single token queries only, and only if unique.
  const isSingleToken = !/\s/.test(q);
  if (isSingleToken) {
    const firstMatches = list.filter((c) => firstNameOf(c && c.name) === q);
    if (firstMatches.length === 1) {
      return withPhoneResolution(firstMatches[0]);
    }
    if (firstMatches.length > 1) {
      return { status: "ambiguous", matches: firstMatches.slice() };
    }
  }

  return { status: "not-found", query: String(queryName || "") };
}

function withPhoneResolution(contact) {
  const phones = Array.isArray(contact.phones)
    ? contact.phones.filter((p) => typeof p === "string" && /^\+?\d{7,15}$/.test(p))
    : [];
  if (phones.length === 0) {
    return { status: "no-number", contact };
  }
  if (phones.length === 1) {
    return { status: "found", contact, phone: phones[0] };
  }
  return { status: "need-choice", contact, phones: phones.slice() };
}

const FelixLogic = {
  parseCommand,
  validatePhone,
  buildTelUrl,
  createContact,
  matchContact,
};

if (typeof module !== "undefined" && module.exports) {
  module.exports = FelixLogic;
}
if (typeof window !== "undefined") {
  window.FelixLogic = FelixLogic;
}
