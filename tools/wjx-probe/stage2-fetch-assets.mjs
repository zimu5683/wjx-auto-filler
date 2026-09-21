// tools/wjx-probe/stage2-fetch-assets.mjs
// Stage 2: fetch the JS assets that carry ktimes / spChars / dataenc / submit logic.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
fs.mkdirSync(EV, { recursive: true });

const UA =
  "Mozilla/5.0 (Linux; Android 16; ELN2-W09 Build/HONORELN2-W09; wv) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36";

const assets = [
  ["jqmobo2.js", "https://image.wjx.cn/joinnew/js/jqmobo2.js?v=7558"],
  ["wjx_captch.js", "https://image.wjx.cn/joinnew/js/wjx_captch.js?v=7558"],
  ["matchawardinfmobilenew.js", "https://image.wjx.cn/joinnew/js/matchawardinfmobilenew.js?v=7558"],
  ["hintinfo.js", "https://image.wjx.cn/joinnew/js/hintinfo.js?v=7558"],
];

const report = [];
for (const [name, url] of assets) {
  const jar = new CookieJar(name);
  try {
    const res = await request(url, {
      headers: { "User-Agent": UA, Accept: "*/*", Referer: "https://www.wjx.cn/vm/Q0DQewW.aspx" },
      jar,
      timeoutMs: 30000,
    });
    fs.writeFileSync(path.join(EV, "02-" + name), res.body, "utf8");
    report.push({ name, url, status: res.status, bytes: res.bodyBytes });
    console.log(name, res.status, res.bodyBytes);
  } catch (e) {
    report.push({ name, url, error: String(e && e.message) });
    console.log(name, "ERROR", String(e && e.message));
  }
}
fs.writeFileSync(path.join(EV, "02-assets.json"), JSON.stringify(report, null, 2), "utf8");
