// tools/wjx-probe/stage1-fetch.mjs
// Stage 1: GET the survey page with a browser-like UA, dump raw HTML + headers + cookies.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
fs.mkdirSync(EV, { recursive: true });

const TARGET = process.argv[2] || "https://www.wjx.cn/vm/Q0DQewW.aspx";
const UA =
  "Mozilla/5.0 (Linux; Android 16; ELN2-W09 Build/HONORELN2-W09; wv) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36";

const jar = new CookieJar("page");
const t0 = Date.now();
const res = await request(TARGET, {
  method: "GET",
  headers: {
    "User-Agent": UA,
    Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Upgrade-Insecure-Requests": "1",
  },
  jar,
  timeoutMs: 30000,
});

fs.writeFileSync(path.join(EV, "01-page.html"), res.body, "utf8");
fs.writeFileSync(path.join(EV, "01-page.headers.txt"), res.responseRaw.split("\n\n")[0] + "\n", "utf8");
fs.writeFileSync(path.join(EV, "01-page.request.txt"), res.requestRaw + "\n", "utf8");
fs.writeFileSync(path.join(EV, "01-cookies.json"), JSON.stringify(jar.toJSON(), null, 2), "utf8");
fs.writeFileSync(
  path.join(EV, "01-meta.json"),
  JSON.stringify(
    {
      target: TARGET,
      finalUrl: res.finalUrl,
      status: res.status,
      bytes: res.bodyBytes,
      elapsedMs: Date.now() - t0,
      redirects: res.redirects,
      title: (res.body.match(/<title>([\s\S]*?)<\/title>/i) || [])[1] || null,
      setCookieCount: jar.history.length,
    },
    null,
    2
  ),
  "utf8"
);

console.log("status=", res.status, "bytes=", res.bodyBytes, "ms=", Date.now() - t0);
console.log("finalUrl=", res.finalUrl, "redirects=", JSON.stringify(res.redirects));
console.log("title=", (res.body.match(/<title>([\s\S]*?)<\/title>/i) || [])[1]);
console.log("cookies=", JSON.stringify(Object.keys(Object.fromEntries(jar.cookies))));
console.log("htmlHead:", res.body.slice(0, 400).replace(/\s+/g, " "));
