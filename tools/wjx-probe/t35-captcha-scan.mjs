// tools/wjx-probe/t35-captcha-scan.mjs
// T3.5: READ-ONLY sampling of public wjx surveys. Never touches the submit endpoint.
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence", "t35");
fs.mkdirSync(EV, { recursive: true });

const UA =
  "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const seed = process.argv.slice(2);
const links = new Set(seed);

for (const page of [1, 2, 3]) {
  const url = "https://www.wjx.cn/newsurveys.aspx?pagenumber=" + page;
  try {
    const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar("dir"), timeoutMs: 25000 });
    const found = [...res.body.matchAll(/https?:\/\/www\.wjx\.cn\/vm\/([A-Za-z0-9]+)\.aspx/g)].map((m) => m[0]);
    for (const f of found) links.add(f);
    console.log("directory p" + page + ": status=" + res.status + " links+=" + found.length);
    fs.writeFileSync(path.join(EV, "dir-p" + page + ".html"), res.body, "utf8");
  } catch (e) {
    console.log("directory p" + page + " ERROR " + e.message);
  }
}

const targets = [...links].slice(0, 14);
console.log("targets:", targets.length);

const rows = [];
for (const url of targets) {
  const row = { url };
  try {
    const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar("s"), timeoutMs: 25000 });
    const html = res.body;
    const shortId = (url.match(/\/vm\/([A-Za-z0-9]+)\.aspx/) || [])[1];
    fs.writeFileSync(path.join(EV, shortId + ".html"), html, "utf8");
    const pick = (re) => { const m = html.match(re); return m ? m[1] : null; };
    const questions = [];
    const fieldRe = /<div class='field[^']*'\s+topic='(\d+)'[^>]*type='(\d+)'/g;
    let fm;
    while ((fm = fieldRe.exec(html)) !== null) questions.push({ topic: Number(fm[1]), type: Number(fm[2]) });
    row.shortId = shortId;
    row.status = res.status;
    row.bytes = res.bodyBytes;
    row.title = ((html.match(/<title>([\s\S]*?)<\/title>/i) || [])[1] || "").trim();
    row.captchaType = pick(/var\s+captchaType\s*=\s*'([^']*)'/);
    row.useAliVerify = pick(/var\s+useAliVerify\s*=\s*(\d+)/);
    row.needLoadAliVerify = pick(/var\s+needLoadAliVerify\s*=\s*(\d+)/);
    row.needVerifyCode = pick(/var\s+NeedVerifyCode\s*=\s*([^;]+);/);
    row.hasCaptchaDiv = /id="captchaWrap"|id="captcha"/.test(html);
    row.oneQPerPage = pick(/var\s+IsOneQuestionPerPage\s*=\s*([^;]+);/);
    row.questionCount = questions.length;
    row.questionTypes = [...new Set(questions.map((q) => q.type))].sort((a, b) => a - b);
    row.hasChoice = questions.some((q) => [3, 4, 5, 6, 7].includes(q.type));
    row.hasText = questions.some((q) => [1, 2].includes(q.type));
    row.isPaged = row.oneQPerPage !== "0" || /fieldset2/.test(html);
    row.notFound = /问卷不存在|问卷已关闭|该问卷不存在|已停止/.test(html);
  } catch (e) {
    row.error = String(e && e.message);
  }
  rows.push(row);
  console.log(JSON.stringify(row));
}

fs.writeFileSync(path.join(EV, "t35-summary.json"), JSON.stringify(rows, null, 2), "utf8");

const dist = {};
for (const r of rows) {
  const k = r.error ? "ERROR" : (r.captchaType === null ? "(undefined)" : "'" + r.captchaType + "'");
  dist[k] = (dist[k] || 0) + 1;
}
console.log("=== captchaType distribution ===");
console.log(JSON.stringify(dist, null, 2));
console.log("with choice questions:", rows.filter((r) => r.hasChoice).map((r) => r.shortId).join(",") || "none");
