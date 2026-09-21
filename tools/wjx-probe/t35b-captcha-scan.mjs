// tools/wjx-probe/t35b-captcha-scan.mjs
// T3.5 (expanded): READ-ONLY. Harvest public surveys from wjx.cn/newsurveys.aspx and
// record useAliVerify / captchaType / question structure. NEVER touches submit.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence", "t35b");
fs.mkdirSync(EV, { recursive: true });

const UA =
  "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const links = new Set(process.argv.slice(2));
for (const p of [1, 2, 3, 4]) {
  const url = "https://www.wjx.cn/newsurveys.aspx?pagenumber=" + p;
  try {
    const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar("d"), timeoutMs: 25000 });
    const found = [...res.body.matchAll(/https:\/\/www\.wjx\.cn\/(?:xz|vm|jq)\/[A-Za-z0-9]+\.aspx/g)].map((m) => m[0]);
    for (const f of found) links.add(f);
    console.log("directory p" + p + ": status=" + res.status + " links+=" + found.length);
    fs.writeFileSync(path.join(EV, "dir-p" + p + ".html"), res.body, "utf8");
  } catch (e) { console.log("directory p" + p + " ERROR " + e.message); }
}

const targets = [...links].slice(0, 16);
console.log("targets:", targets.length);

const rows = [];
for (const url of targets) {
  const row = { url };
  try {
    const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar("s"), timeoutMs: 25000 });
    const html = res.body;
    const shortId = (url.match(/\/(?:xz|vm|jq)\/([A-Za-z0-9]+)\.aspx/) || [])[1];
    fs.writeFileSync(path.join(EV, shortId + ".html"), html, "utf8");
    const pick = (re) => { const m = html.match(re); return m ? m[1] : null; };
    const questions = [];
    const fieldRe = /<div class='field[^']*'\s+topic='(\d+)'[^>]*type='(\d+)'/g;
    let fm;
    while ((fm = fieldRe.exec(html)) !== null) questions.push({ topic: Number(fm[1]), type: Number(fm[2]) });
    Object.assign(row, {
      shortId, status: res.status, bytes: res.bodyBytes,
      finalUrl: res.finalUrl, redirects: res.redirects.length,
      title: ((html.match(/<title>([\s\S]*?)<\/title>/i) || [])[1] || "").trim().slice(0, 60),
      useAliVerify: pick(/var\s+useAliVerify\s*=\s*(\d+)/),
      captchaType: pick(/var\s+captchaType\s*=\s*'([^']*)'/),
      needLoadAliVerify: pick(/var\s+needLoadAliVerify\s*=\s*(\d+)/),
      needVerifyCode: pick(/var\s+NeedVerifyCode\s*=\s*([^;]+);/),
      hasCaptchaBlock: /var\s+useAliVerify/.test(html),
      questionCount: questions.length,
      questionTypes: [...new Set(questions.map((q) => q.type))].sort((a, b) => a - b),
      hasChoice: questions.some((q) => [3, 4, 5, 6, 7].includes(q.type)),
      hasText: questions.some((q) => [1, 2].includes(q.type)),
      notFound: /问卷不存在|问卷已关闭|该问卷不存在|已停止|已结束/.test(html),
    });
  } catch (e) { row.error = String(e && e.message); }
  rows.push(row);
  console.log(JSON.stringify(row));
}

fs.writeFileSync(path.join(EV, "t35b-summary.json"), JSON.stringify(rows, null, 2), "utf8");
const dist = {};
for (const r of rows) {
  const k = r.error ? "ERROR" : r.notFound ? "NOT_FOUND" : "useAliVerify=" + r.useAliVerify;
  dist[k] = (dist[k] || 0) + 1;
}
console.log("=== useAliVerify distribution ===");
console.log(JSON.stringify(dist, null, 2));
const ct = {};
for (const r of rows) { const k = String(r.captchaType); ct[k] = (ct[k] || 0) + 1; }
console.log("=== captchaType distribution ===", JSON.stringify(ct));
console.log("=== choice-question surveys ===", rows.filter((r) => r.hasChoice).map((r) => r.shortId + "(t=" + r.questionTypes.join("/") + ")").join(" ") || "none");
console.log("=== usable forms ===", rows.filter((r) => r.questionCount > 0).length, "/", rows.length);
