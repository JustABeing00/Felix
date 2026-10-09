"use strict";

const { test, describe } = require("node:test");
const assert = require("node:assert/strict");

const L = require("../logic.js");

describe("parseCommand", () => {
  test("“Felix, call Yash” extracts Yash", () => {
    assert.deepEqual(L.parseCommand("Felix, call Yash"), { ok: true, name: "Yash" });
  });

  test("“Call Yash.” extracts Yash", () => {
    assert.deepEqual(L.parseCommand("Call Yash."), { ok: true, name: "Yash" });
  });

  test("“Felix call Mom.” extracts Mom", () => {
    assert.deepEqual(L.parseCommand("Felix call Mom."), { ok: true, name: "Mom" });
  });

  test("“Please call Rahul.” extracts Rahul", () => {
    const r = L.parseCommand("Please call Rahul.");
    assert.equal(r.ok, true);
    assert.equal(r.name, "Rahul");
  });

  test("“Felix, can you call Dad?” extracts Dad", () => {
    const r = L.parseCommand("Felix, can you call Dad?");
    assert.equal(r.ok, true);
    assert.equal(r.name, "Dad");
  });

  test("different capitalization still works", () => {
    assert.equal(L.parseCommand("FELIX, CALL YASH").name, "YASH");
    assert.equal(L.parseCommand("please CALL rahul").name, "rahul");
  });

  test("empty command is rejected", () => {
    assert.equal(L.parseCommand("").ok, false);
    assert.equal(L.parseCommand("   ").ok, false);
    assert.equal(L.parseCommand("Felix, please").ok, false);
  });

  test("unsupported action is rejected", () => {
    const r = L.parseCommand("Felix, text Yash");
    assert.equal(r.ok, false);
    assert.equal(r.reason, "unsupported");
  });

  test("multi-word names preserved", () => {
    const r = L.parseCommand("Call Rahul Sharma");
    assert.equal(r.ok, true);
    assert.equal(r.name, "Rahul Sharma");
  });
});

describe("validatePhone + buildTelUrl", () => {
  test("valid Indian number passes", () => {
    const v = L.validatePhone("+911234567890");
    assert.equal(v.ok, true);
    assert.equal(L.buildTelUrl(v.normalized), "tel:+911234567890");
  });

  test("invalid number rejected, no tel: built", () => {
    assert.equal(L.validatePhone("abc").ok, false);
    assert.equal(L.validatePhone("123").ok, false);
    assert.equal(L.buildTelUrl("abc"), null);
    assert.equal(L.buildTelUrl(""), null);
  });
});

describe("matchContact", () => {
  const contacts = [
    { id: "1", name: "Yash Kumar", phones: ["+911111111111"] },
    { id: "2", name: "Rahul Sharma", phones: ["+912222222222"] },
    { id: "3", name: "Rahul Verma", phones: ["+913333333333"] },
    { id: "4", name: "Mom", phones: [] },
    { id: "5", name: "Dad", phones: ["+914444444444", "+915555555555"] },
  ];

  test("unique exact match is selected", () => {
    const m = L.matchContact("Rahul Sharma", contacts);
    assert.equal(m.status, "found");
    assert.equal(m.phone, "+912222222222");
  });

  test("exact match is case-insensitive", () => {
    const m = L.matchContact("yash kumar", contacts);
    assert.equal(m.status, "found");
  });

  test("unique first-name match is selected", () => {
    const m = L.matchContact("Yash", contacts);
    assert.equal(m.status, "found");
    assert.equal(m.contact.name, "Yash Kumar");
  });

  test("multiple first-name matches produce disambiguation", () => {
    const m = L.matchContact("Rahul", contacts);
    assert.equal(m.status, "ambiguous");
    assert.equal(m.matches.length, 2);
  });

  test("no match produces not-found", () => {
    const m = L.matchContact("Nobody", contacts);
    assert.equal(m.status, "not-found");
  });

  test("contact without a valid phone number cannot be called", () => {
    const m = L.matchContact("Mom", contacts);
    assert.equal(m.status, "no-number");
    assert.equal(L.buildTelUrl("not-a-number"), null);
  });

  test("contact with multiple numbers needs a choice", () => {
    const m = L.matchContact("Dad", contacts);
    assert.equal(m.status, "need-choice");
    assert.equal(m.phones.length, 2);
  });
});
