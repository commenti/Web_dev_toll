/**
 * NetForge Inspector — Capture WebExtension (Firefox / GeckoView, Manifest v2).
 *
 * Responsibilities:
 *  - Observe every request via browser.webRequest (blocking + request/response headers).
 *  - Capture request bodies (raw bytes decoded as UTF-8) when available.
 *  - Capture response bodies ONLY for text/API-like MIME types (RAM protection on mobile)
 *    using browser.webRequest.filterResponseData streams.
 *  - Parse Request "Cookie" headers and Response "Set-Cookie" headers.
 *  - Forward every completed packet to the Kotlin side over a native messaging port:
 *    browser.runtime.connectNative("netforge_bridge").
 */

"use strict";

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const NATIVE_PORT_NAME = "netforge_bridge";
const MAX_BODY_CHARS = 2000000; // 2 MB guard rail against OOM on mobile.

// Text/API formats whose bodies are safe (and useful) to capture in full.
const TEXT_LIKE_MIME_FRAGMENTS = [
  "application/json",
  "application/x-www-form-urlencoded",
  "application/graphql",
  "application/xml",
  "application/javascript",
  "application/x-javascript",
  "application/vnd.api+json",
  "text/plain",
  "text/html",
  "text/xml",
  "text/css",
  "text/javascript",
];

const API_HINT_PATHS = ["/api/", "/graphql", "/v1/", "/v2/", "/v3/", "/rest/", "/ajax/", "/_api/"];

// ---------------------------------------------------------------------------
// Native messaging port
// ---------------------------------------------------------------------------

let nativePort = null;
let nativeQueue = [];

function ensureNativePort() {
  if (nativePort) {
    return nativePort;
  }
  try {
    const port = browser.runtime.connectNative(NATIVE_PORT_NAME);
    port.onDisconnect.addListener((p) => {
      nativePort = null;
      // Proton runtime restarts the extension process gracefully; tolerate drops.
      if (p && p.error) {
        console.error("NetForge native port disconnected:", p.error);
      }
    });
    port.onMessage.addListener((msg) => {
      // Control channel reserved for future native->web commands.
      if (msg && msg.type === "ping") {
        port.postMessage({ type: "pong" });
      }
    });
    nativePort = port;
  } catch (err) {
    console.error("Failed to open native port:", err);
    return null;
  }
  return nativePort;
}

function sendToNative(packet) {
  const port = ensureNativePort();
  if (!port) {
    // Queue until the runtime acknowledges a connection attempt.
    if (nativeQueue.length < 500) {
      nativeQueue.push(packet);
    }
    return;
  }
  if (nativeQueue.length > 0) {
    const backlog = nativeQueue.splice(0, nativeQueue.length);
    for (const stale of backlog) {
      port.postMessage(stale);
    }
  }
  port.postMessage(packet);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function headerArrayToObject(headerArray) {
  const obj = {};
  if (!Array.isArray(headerArray)) {
    return obj;
  }
  for (const h of headerArray) {
    if (!h || !h.name) continue;
    const name = h.name;
    const value = h.value !== undefined
      ? h.value
      : (h.binaryValue ? String.fromCharCode.apply(null, h.binaryValue) : "");
    // Last occurrence wins; keep header names case-sensitive as sent.
    obj[name] = value;
  }
  return obj;
}

/** Parse "session_id=abc123xyz; Path=/; HttpOnly" style header values into {k: v}. */
function parseCookiePairs(cookieString) {
  const jar = {};
  if (!cookieString) {
    return jar;
  }
  const pairs = String(cookieString).split(/;\s*/);
  for (const pair of pairs) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    const key = pair.substring(0, eq).trim();
    if (!key || key === "Path" || key === "Domain") continue;
    const value = pair.substring(eq + 1).trim();
    if (value === "") continue;
    jar[key] = value;
  }
  return jar;
}

function cookieHeaderFromRequestHeaders(headerObj) {
  for (const name in headerObj) {
    if (name.toLowerCase() === "cookie") {
      return headerObj[name];
    }
  }
  return null;
}

function collectSetCookies(responseHeaderObj) {
  // Multiple Set-Cookie headers collapse into one object-keyed value, so we
  // pre-scan the raw array instead of the collapsed map.
  return responseHeaderObj; // replaced at call site with array-aware variant
}

function setCookieJarFromResponseArray(headerArray) {
  const jar = {};
  if (!Array.isArray(headerArray)) {
    return jar;
  }
  for (const h of headerArray) {
    if (!h || !h.name) continue;
    if (h.name.toLowerCase() !== "set-cookie") continue;
    const value = h.value !== undefined ? h.value : "";
    const parsed = parseCookiePairs(value);
    for (const k in parsed) {
      jar[k] = parsed[k];
    }
  }
  return jar;
}

function contentTypeOf(headerObj) {
  const key = Object.keys(headerObj).find(
    (n) => n.toLowerCase() === "content-type" || n.toLowerCase() === "contenttype"
  );
  return key ? String(headerObj[key]).toLowerCase() : "";
}

function isTextLikeMime(contentType) {
  if (!contentType) {
    return false;
  }
  return TEXT_LIKE_MIME_FRAGMENTS.some((frag) => contentType.indexOf(frag) !== -1);
}

function isApiCall(request) {
  const url = request.url || "";
  const lowerPath = url.split("?")[0].toLowerCase();
  if (API_HINT_PATHS.some((p) => lowerPath.indexOf(p) !== -1)) {
    return true;
  }
  const method = (request.method || "").toUpperCase();
  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    // Non-GET usually indicates an XHR/fetch or form action, not document nav.
    const type = request.type || "";
    if (type && type !== "main_frame" && type !== "sub_frame") {
      return true;
    }
  }
  const accept = (request.requestHeaders || []).find((h) => h.name.toLowerCase() === "accept");
  if (accept && accept.value && accept.value.indexOf("application/json") !== -1) {
    return true;
  }
  return false;
}

function decodeRawBody(requestBody) {
  if (!requestBody) {
    return "";
  }
  try {
    if (requestBody.formData) {
      const parts = [];
      for (const key in requestBody.formData) {
        parts.push(key + "=" + String(requestBody.formData[key]));
      }
      return parts.join("&").slice(0, MAX_BODY_CHARS);
    }
    if (requestBody.raw && requestBody.raw.length > 0) {
      const dec = new TextDecoder("utf-8", { fatal: false });
      let out = "";
      for (const chunk of requestBody.raw) {
        if (chunk instanceof ArrayBuffer) {
          out += dec.decode(chunk);
        } else if (chunk && chunk.bytes) {
          out += dec.decode(chunk.bytes);
        }
        if (out.length > MAX_BODY_CHARS) break;
      }
      return out.slice(0, MAX_BODY_CHARS);
    }
    if (typeof requestBody === "string") {
      return requestBody.slice(0, MAX_BODY_CHARS);
    }
  } catch (err) {
    console.warn("Failed to decode request body:", err);
  }
  return "";
}

// ---------------------------------------------------------------------------
// In-flight request tracking  (requestId -> record)
// ---------------------------------------------------------------------------

const pending = new Map();

function sweepStale() {
  const now = Date.now();
  for (const [id, rec] of pending) {
    if (now - rec.createdAt > 5 * 60 * 1000) {
      pending.delete(id);
    }
  }
}

setInterval(sweepStale, 60000);

// ---------------------------------------------------------------------------
// webRequest listeners
// ---------------------------------------------------------------------------

browser.webRequest.onBeforeRequest.addListener(
  (details) => {
    const rec = {
      createdAt: Date.now(),
      requestId: details.requestId,
      url: details.url,
      method: details.method,
      type: details.type,
      userAgentOrigin: details.originUrl || "",
      requestBody: decodeRawBody(details.requestBody),
      responseChunks: [],
      responseText: null,
      filterFailed: false,
      statusCode: 0,
    };
    pending.set(details.requestId, rec);

    // RAM protection: only stream text-like response bodies.
    // We decide at onHeadersReceived when the content-type is known; reserve the
    // capability here by storing nothing extra.
    return; // no blocking decision
  },
  { urls: ["<all_urls>"] },
  ["requestBody"]
);

browser.webRequest.onSendHeaders.addListener(
  (details) => {
    const rec = pending.get(details.requestId);
    if (!rec) return;
    const headerObj = headerArrayToObject(details.requestHeaders || []);
    rec.requestHeaders = headerObj;
    rec.cookieHeader = cookieHeaderFromRequestHeaders(headerObj);
    const ct = contentTypeOf(headerObj);
    rec.requestContentType = ct;
    rec.isApiCall = isApiCall({
      url: rec.url,
      method: rec.method,
      type: rec.type,
      requestHeaders: details.requestHeaders || [],
    });
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"]
);

browser.webRequest.onHeadersReceived.addListener(
  (details) => {
    const rec = pending.get(details.requestId);
    if (!rec) return;
    rec.responseHeadersRaw = details.responseHeaders || [];
    const headerObj = headerArrayToObject(rec.responseHeadersRaw);
    rec.responseHeaders = headerObj;
    const statusLine = details.statusCode;
    rec.statusCode = statusLine;
    rec.responseContentType = contentTypeOf(headerObj);

    // Attach a response-body stream only for text/API content.
    if (isTextLikeMime(rec.responseContentType)) {
      try {
        const filter = browser.webRequest.filterResponseData(details.requestId);
        const dec = new TextDecoder("utf-8", { fatal: false });
        let accumulated = "";
        filter.ondata = (event) => {
          accumulated += dec.decode(event.data, { stream: true });
          if (accumulated.length > MAX_BODY_CHARS) {
            accumulated = accumulated.slice(0, MAX_BODY_CHARS);
          }
          try {
            filter.resume(); // let the renderer continue; we already copied bytes
          } catch (e) {
            /* stream already resumed/closed */
          }
        };
        filter.onstop = () => {
          accumulated += dec.decode(); // flush
          rec.responseText = accumulated;
          try {
            filter.disconnect();
          } catch (e) {
            /* noop */
          }
          accumulated = null; // release reference early
        };
        filter.onerror = () => {
          try {
            filter.disconnect();
          } catch (e) {
            /* noop */
          }
        };
      } catch (err) {
        // Cached / service-worker-served responses cannot always be filtered.
        rec.filterFailed = true;
      }
    }
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.webRequest.onCompleted.addListener(
  (details) => {
    finishRequest(details, "completed");
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

browser.webRequest.onErrorOccurred.addListener(
  (details) => {
    finishRequest(details, "error");
  },
  { urls: ["<all_urls>"] }
);

function finishRequest(details, outcome) {
  const rec = pending.get(details.requestId);
  if (!rec) return;
  pending.delete(details.requestId);

  const headerObj = rec.requestHeaders || headerArrayToObject(details.requestHeaders || []);
  const responseHeaderObj = rec.responseHeaders || headerArrayToObject(details.responseHeaders || []);
  const durationMs = Math.max(0, Math.round((details.timeStamp || Date.now()) - rec.createdAt));

  const packet = {
    type: "netforge_packet",
    requestId: "req_" + details.requestId,
    requestTimestamp: rec.createdAt,
    domain: extractDomain(rec.url),
    method: rec.method,
    url: rec.url,
    isApiCall: !!rec.isApiCall,
    requestHeaders: headerObj,
    requestCookies: parseCookiePairs(rec.cookieHeader || ""),
    extractedTokens: [], // filled natively by the Kotlin JWT/token parser layer
    requestBody: rec.requestBody || "",
    statusCode: rec.statusCode || (outcome === "error" ? 0 : 0),
    durationMs: durationMs,
    responseHeaders: responseHeaderObj,
    responseCookies: setCookieJarFromResponseArray(rec.responseHeadersRaw || []),
    responseBody: rec.responseText || "",
    outcome: outcome,
    error: outcome === "error" ? String(details.error || "") : "",
  };

  sendToNative(packet);
}

function extractDomain(url) {
  try {
    return new URL(url).hostname;
  } catch (err) {
    return "";
  }
}

console.info("NetForge Inspector capture extension initialized.");
