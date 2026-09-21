// tools/wjx-probe/lib/http.mjs
// Minimal HTTP client with a real cookie jar + full raw request/response capture.
// Node 26 (Termux/Android). No external deps.
import https from "node:https";
import http from "node:http";
import zlib from "node:zlib";
import { URL } from "node:url";

export class CookieJar {
  constructor(label = "jar") {
    this.label = label;
    this.cookies = new Map(); // name -> { value, raw }
    this.history = [];
  }
  absorb(setCookieHeaders, url) {
    if (!setCookieHeaders) return;
    const list = Array.isArray(setCookieHeaders) ? setCookieHeaders : [setCookieHeaders];
    for (const sc of list) {
      const first = String(sc).split(";")[0];
      const eq = first.indexOf("=");
      if (eq <= 0) continue;
      const name = first.slice(0, eq).trim();
      const value = first.slice(eq + 1).trim();
      this.cookies.set(name, value);
      this.history.push({ url, setCookie: String(sc), name, value });
    }
  }
  header() {
    if (this.cookies.size === 0) return null;
    return [...this.cookies.entries()].map(([k, v]) => k + "=" + v).join("; ");
  }
  toJSON() {
    return { label: this.label, cookies: Object.fromEntries(this.cookies), history: this.history };
  }
}

function decompress(buf, encoding) {
  try {
    const enc = String(encoding || "").toLowerCase();
    if (enc.includes("br")) return zlib.brotliDecompressSync(buf);
    if (enc.includes("gzip")) return zlib.gunzipSync(buf);
    if (enc.includes("deflate")) return zlib.inflateSync(buf);
  } catch (e) {
    return buf;
  }
  return buf;
}

/**
 * @returns {Promise<{status:number, headers:object, rawHeaders:string[], body:string, bodyBytes:number,
 *                    requestRaw:string, responseRaw:string, finalUrl:string, redirects:string[]}>}
 */
export function request(url, opts = {}) {
  const {
    method = "GET",
    headers = {},
    body = null,
    jar = null,
    maxRedirects = 5,
    timeoutMs = 30000,
    followRedirects = true,
  } = opts;

  return new Promise((resolve, reject) => {
    const redirects = [];
    let current = url;
    let redirectsLeft = maxRedirects;

    const doOnce = (target, methodNow, bodyNow) => {
      const u = new URL(target);
      const isHttps = u.protocol === "https:";
      const mod = isHttps ? https : http;
      const h = { ...headers };
      h["Host"] = u.host;
      if (jar) {
        const c = jar.header();
        if (c) h["Cookie"] = c;
      }
      if (bodyNow != null && h["Content-Length"] == null) {
        h["Content-Length"] = Buffer.byteLength(bodyNow);
      }
      const reqRawLines = [methodNow + " " + u.pathname + u.search + " HTTP/1.1"];
      for (const [k, v] of Object.entries(h)) reqRawLines.push(k + ": " + v);
      const requestRaw = reqRawLines.join("\n") + "\n\n" + (bodyNow == null ? "" : bodyNow);

      const req = mod.request(
        {
          protocol: u.protocol,
          hostname: u.hostname,
          port: u.port || (isHttps ? 443 : 80),
          path: u.pathname + u.search,
          method: methodNow,
          headers: h,
          timeout: timeoutMs,
        },
        (res) => {
          const chunks = [];
          res.on("data", (c) => chunks.push(c));
          res.on("end", () => {
            const rawBody = Buffer.concat(chunks);
            const bodyText = decompress(rawBody, res.headers["content-encoding"]).toString("utf8");
            if (jar) jar.absorb(res.headers["set-cookie"], target);

            const respRawLines = ["HTTP/" + res.httpVersion + " " + res.statusCode + " " + (res.statusMessage || "")];
            for (let i = 0; i < res.rawHeaders.length; i += 2) {
              respRawLines.push(res.rawHeaders[i] + ": " + res.rawHeaders[i + 1]);
            }
            const responseRaw = respRawLines.join("\n") + "\n\n" + bodyText;

            const loc = res.headers["location"];
            if (followRedirects && loc && res.statusCode >= 300 && res.statusCode < 400 && redirectsLeft > 0) {
              redirectsLeft--;
              const next = new URL(loc, target).toString();
              redirects.push(res.statusCode + " " + target + " -> " + next);
              const keepMethod = res.statusCode === 307 || res.statusCode === 308;
              doOnce(next, keepMethod ? methodNow : "GET", keepMethod ? bodyNow : null);
              return;
            }
            resolve({
              status: res.statusCode,
              headers: res.headers,
              rawHeaders: res.rawHeaders,
              body: bodyText,
              bodyBytes: rawBody.length,
              requestRaw,
              responseRaw,
              finalUrl: target,
              redirects,
            });
          });
        }
      );
      req.on("timeout", () => { req.destroy(new Error("timeout after " + timeoutMs + "ms")); });
      req.on("error", reject);
      if (bodyNow != null) req.write(bodyNow);
      req.end();
    };

    doOnce(current, method, body);
  });
}
