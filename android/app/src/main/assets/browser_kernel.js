(function () {
    "use strict";
    const nativeBridge = window.__unipusNative;
    if (!nativeBridge || typeof nativeBridge.postMessage !== "function") return;

    const slot = "__unipus_browser_kernel_v1__";
    if (window[slot]) {
        window[slot].hello();
        return;
    }

    function randomId() {
        if (window.crypto && typeof window.crypto.getRandomValues === "function") {
            const bytes = new Uint32Array(4);
            window.crypto.getRandomValues(bytes);
            return Array.from(bytes, value => value.toString(16).padStart(8, "0")).join("");
        }
        return Date.now().toString(36) + Math.random().toString(36).slice(2) + Math.random().toString(36).slice(2);
    }

    let frameId = "frame-" + randomId();
    let nextElement = 0;
    let elements = new Map();
    let elementIds = new WeakMap();
    const requests = new Map();

    function fail(name, message) {
        const error = new Error(message);
        error.name = name;
        throw error;
    }

    function post(message) {
        nativeBridge.postMessage(JSON.stringify(message));
    }

    function hello() {
        post({kind: "hello", frame: frameId, href: String(location.href)});
    }

    function errorValue(error) {
        return {
            name: error && error.name ? String(error.name) : "JavascriptException",
            message: String(error && error.message !== undefined ? error.message : error).slice(0, 4096)
        };
    }

    function decode(value, depth) {
        if (depth > 40) fail("InvalidArgumentException", "Argument nesting limit exceeded");
        if (value === null || typeof value !== "object") return value;
        if (Object.prototype.hasOwnProperty.call(value, "__unipus_element__")) {
            if (value.frame !== frameId) {
                fail("StaleElementReferenceException", "Element belongs to another frame or document");
            }
            const element = elements.get(String(value.__unipus_element__));
            if (!element || !element.isConnected || element.ownerDocument !== document) {
                fail("StaleElementReferenceException", "Element is no longer attached to this document");
            }
            return element;
        }
        if (Array.isArray(value)) return value.map(item => decode(item, depth + 1));
        const result = Object.create(null);
        Object.keys(value).forEach(key => { result[key] = decode(value[key], depth + 1); });
        return result;
    }

    function encode(value, seen, depth) {
        if (depth > 40) fail("JavascriptException", "Result nesting limit exceeded");
        if (value === null || value === undefined) return null;
        const type = typeof value;
        if (type === "string" || type === "boolean") return value;
        if (type === "number") return Number.isFinite(value) ? value : null;
        if (type === "bigint" || type === "function" || type === "symbol") {
            fail("JavascriptException", "Script result is not JSON serializable");
        }
        if (value.nodeType === 1 && value.ownerDocument) {
            if (value.ownerDocument !== document) {
                fail("StaleElementReferenceException", "Return elements from their own frame context");
            }
            let id = elementIds.get(value);
            if (!id) {
                id = "element-" + (++nextElement);
                elementIds.set(value, id);
            }
            elements.set(id, value);
            if (elements.size > 4096) {
                for (const [oldId, oldElement] of elements) {
                    if (!oldElement.isConnected) elements.delete(oldId);
                }
            }
            return {__unipus_element__: id, frame: frameId};
        }
        if (value instanceof Date) return value.toJSON();
        if (seen.has(value)) fail("JavascriptException", "Cyclic script result");
        seen.add(value);
        let result;
        if (Array.isArray(value) || value instanceof NodeList || value instanceof HTMLCollection) {
            result = Array.from(value, item => encode(item, seen, depth + 1));
        } else {
            result = Object.create(null);
            Object.keys(value).forEach(key => { result[key] = encode(value[key], seen, depth + 1); });
        }
        seen.delete(value);
        return result;
    }

    function finish(id, message) {
        const pending = requests.get(id);
        if (!pending) return;
        requests.delete(id);
        clearTimeout(pending.timer);
        post(Object.assign({kind: "result", request: id, frame: pending.frame}, message));
    }

    function execute(message) {
        const id = String(message.request || "");
        if (!id || requests.has(id)) return;
        const timeout = Math.max(100, Math.min(120000, Number(message.timeoutMs) || 30000));
        requests.set(id, {
            frame: frameId,
            timer: setTimeout(() => finish(id, {
                ok: false, error: {name: "TimeoutException", message: "JavaScript execution timed out"}
            }), timeout)
        });
        try {
            if (message.frame !== frameId) fail("NoSuchFrameException", "Frame document has changed");
            const args = decode(message.args || [], 0);
            if (!Array.isArray(args)) fail("InvalidArgumentException", "Script args must be an array");
            const fn = new Function(String(message.script || ""));
            Promise.resolve(fn.apply(window, args)).then(value => {
                try {
                    finish(id, {ok: true, value: encode(value, new WeakSet(), 0)});
                } catch (error) {
                    finish(id, {ok: false, error: errorValue(error)});
                }
            }, error => finish(id, {ok: false, error: errorValue(error)}));
        } catch (error) {
            finish(id, {ok: false, error: errorValue(error)});
        }
    }

    nativeBridge.onmessage = function (event) {
        let message;
        try { message = JSON.parse(String(event.data)); } catch (_) { return; }
        if (message && message.kind === "command") execute(message);
    };

    // A parent can discover a child frame without reading cross-origin DOM.
    window.addEventListener("message", event => {
        const data = event.data;
        if (!data || typeof data !== "object" || typeof data.__unipus_frame_probe__ !== "string") return;
        if (!event.source || data.__unipus_frame_probe__.length > 256) return;
        event.source.postMessage({__unipus_frame_reply__: data.__unipus_frame_probe__, frame: frameId}, "*");
    });

    window.addEventListener("pagehide", () => {
        for (const id of Array.from(requests.keys())) finish(id, {
            ok: false, error: {name: "StaleElementReferenceException", message: "Document was navigated away"}
        });
    });
    window.addEventListener("pageshow", event => {
        if (event.persisted) {
            frameId = "frame-" + randomId();
            nextElement = 0;
            elements = new Map();
            elementIds = new WeakMap();
        }
        hello();
    });
    document.addEventListener("DOMContentLoaded", hello, {once: true});
    Object.defineProperty(window, slot, {value: Object.freeze({hello: hello}), configurable: false});
    hello();
})();
