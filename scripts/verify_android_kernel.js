"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const crypto = require("node:crypto");
const path = require("node:path");
const source = fs.readFileSync(path.resolve(__dirname, "../android/app/src/main/assets/browser_kernel.js"), "utf8");
let sequence = 0;

function makeRealm() {
    const messages = [];
    const eventHandlers = new Map();
    const documentHandlers = new Map();
    const results = new Map();
    const document = {
        addEventListener(name, callback) {
            if (!documentHandlers.has(name)) documentHandlers.set(name, []);
            documentHandlers.get(name).push(callback);
        }
    };
    document.testElement = {nodeType: 1, ownerDocument: document, isConnected: true, id: "fixture-element"};
    const native = {
        postMessage(raw) {
            const message = JSON.parse(raw);
            messages.push(message);
            if (message.kind === "result" && results.has(message.request)) {
                results.get(message.request)(message);
                results.delete(message.request);
            }
        }
    };
    const context = vm.createContext({
        document,
        location: {href: "https://fixture.invalid/course"},
        __unipusNative: native,
        crypto: {getRandomValues(array) { crypto.randomFillSync(array); return array; }},
        setTimeout, clearTimeout,
        NodeList: class NodeList extends Array {},
        HTMLCollection: class HTMLCollection extends Array {},
        addEventListener(name, callback) {
            if (!eventHandlers.has(name)) eventHandlers.set(name, []);
            eventHandlers.get(name).push(callback);
        }
    });
    context.window = context;
    vm.runInContext(source, context, {filename: "browser_kernel.js"});
    function frameId() { return messages.filter(message => message.kind === "hello").at(-1).frame; }
    function command(script, args = [], frame = frameId(), timeoutMs = 1000) {
        const id = "test-" + (++sequence);
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error("No result for " + id)), timeoutMs + 500);
            results.set(id, message => { clearTimeout(timer); resolve(message); });
            native.onmessage({data: JSON.stringify({kind: "command", request: id, frame, script, args, timeoutMs})});
        });
    }
    function dispatch(name, event) { for (const fn of eventHandlers.get(name) || []) fn(event); }
    return {context, messages, document, eventHandlers, command, frameId, dispatch};
}

(async () => {
    const main = makeRealm(), child = makeRealm();
    assert.notEqual(main.frameId(), child.frameId());
    let response = await main.command("return arguments[0] + arguments[1]", [3, 4]);
    assert.equal(response.ok, true); assert.equal(response.value, 7);
    response = await main.command("return new Promise(resolve => setTimeout(() => resolve({done: true, count: 4}), 15));");
    assert.deepEqual(response.value, {done: true, count: 4});

    response = await main.command("return document.testElement;");
    const handle = response.value;
    assert.equal(typeof handle.__unipus_element__, "string");
    assert.equal(handle.frame, main.frameId());
    response = await main.command("return arguments[0] === document.testElement", [handle]);
    assert.equal(response.value, true);
    response = await main.command("return {node: document.testElement, list: [document.testElement, undefined]};");
    assert.deepEqual(response.value, {node: handle, list: [handle, null]});

    response = await child.command("return arguments[0]", [handle]);
    assert.equal(response.ok, false);
    assert.equal(response.error.name, "StaleElementReferenceException");
    main.document.testElement.isConnected = false;
    response = await main.command("return arguments[0].id", [handle]);
    assert.equal(response.error.name, "StaleElementReferenceException");
    main.document.testElement.isConnected = true;

    response = await main.command("const cyclic={}; cyclic.self=cyclic; return cyclic;");
    assert.equal(response.ok, false); assert.equal(response.error.name, "JavascriptException");
    response = await main.command("throw new TypeError('fixture-error');");
    assert.equal(response.ok, false); assert.equal(response.error.name, "TypeError");
    response = await main.command("return 3;", [], "frame-does-not-exist");
    assert.equal(response.error.name, "NoSuchFrameException");
    response = await main.command("return new Promise(() => {});", [], main.frameId(), 100);
    assert.equal(response.error.name, "TimeoutException");

    const probeReplies = [];
    child.dispatch("message", {data: {__unipus_frame_probe__: "probe-nonce"}, source: {postMessage(data, origin) { probeReplies.push({data, origin}); }}});
    assert.equal(probeReplies.length, 1);
    assert.equal(probeReplies[0].data.__unipus_frame_reply__, "probe-nonce");
    assert.equal(probeReplies[0].data.frame, child.frameId());
    assert.equal(probeReplies[0].origin, "*");

    const oldFrame = main.frameId();
    const waiting = main.command("return new Promise(() => {});", [], oldFrame, 300);
    main.dispatch("pagehide", {});
    response = await waiting;
    assert.equal(response.error.name, "StaleElementReferenceException");
    main.dispatch("pageshow", {persisted: true});
    assert.notEqual(main.frameId(), oldFrame);
    response = await main.command("return arguments[0];", [handle]);
    assert.equal(response.error.name, "StaleElementReferenceException");
    vm.runInContext(source, main.context);
    assert.equal(main.eventHandlers.get("message").length, 1);
    console.log(JSON.stringify({passed: true, checks: ["value/args", "Promise", "element registry", "nested DOM values", "cross-frame handles rejected", "detached handles rejected", "cyclic result", "JavaScript errors", "frame mismatch", "timeout", "cross-origin probe protocol", "navigation cancellation", "bfcache fresh identity", "bootstrap idempotence"]}));
})().catch(error => { console.error(error); process.exitCode = 1; });
