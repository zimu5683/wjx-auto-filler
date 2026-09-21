// tools/wjx-probe/stage3-submit.mjs
// Stage 3: ONE end-to-end pure-HTTP submission attempt against the sample survey.
// Mirrors exactly what the real page JS (jqmobo2.js) sends on first submit.
//
// Evidence written to evidence/: 03-submit-request.txt / 03-submit-response.txt / 03-result.json
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
fs.mkdirSync(EV, { recursive: true });

const PAGE_URL = process.argv[2] || "https://www.wjx.cn/vm/Q0DQewW.aspx";
const WAIT_MS = Number(process.argv[3] || 15000);
const KTIMES = Number(process.argv[4] || 4);

const UA =
  "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

// ---- 1. GET page with a fresh cookie jar -----------------------------------
const jar = new CookieJar("submit");
const page = await request(PAGE_URL, {
  headers: {
    "User-Agent": UA,
    Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Upgrade-Insecure-Requests": "1",
  },
  jar,
  timeoutMs: 30000,
});
const html = page.body;
fs.writeFileSync(path.join(EV, "03-page.html"), html, "utf8");
fs.writeFileSync(path.join(EV, "03-page.headers.txt"), page.responseRaw.split("\n\n")[0] + "\n", "utf8");

const pick = (re) => {
  const m = html.match(re);
  return m ? m[1] : null;
};
const action = pick(/<form id="form1"[^>]*action="([^"]+)"/i);
const jqnonce = pick(/var\s+jqnonce\s*=\s*"([^"]+)"/);
const captchaType = pick(/var\s+captchaType\s*=\s*'([^']*)'/);
const useAliVerify = pick(/var\s+useAliVerify\s*=\s*(\d+)/);
const needLoadAliVerify = pick(/var\s+needLoadAliVerify\s*=\s*(\d+)/);
const starttime = pick(/<input[^>]*id="starttime"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="starttime"/i);
const source = pick(/<input[^>]*id="source"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="source"/i);
const shortId = pick(/shortid=([A-Za-z0-9]+)/);
const needVerifyCode = pick(/var\s+NeedVerifyCode\s*=\s*([^;]+);/);
const oneQPerPage = pick(/var\s+IsOneQuestionPerPage\s*=\s*([^;]+);/);

// question list straight out of the rendered form (server-rendered, no XHR needed)
const questions = [];
const fieldRe = /<div class='field[^']*'\s+topic='(\d+)'\s+id='div(\d+)'\s+req='(\d+)'[^>]*type='(\d+)'>([\s\S]*?)(?=<div class='field |<\/fieldset>)/g;
let fm;
while ((fm = fieldRe.exec(html)) !== null) {
  const [, topic, , req, type, body] = fm;
  const title = (body.match(/<div class='topichtml'>([\s\S]*?)<\/div>/) || [])[1] || "";
  const options = [...body.matchAll(/<input[^>]*type='(?:radio|checkbox)'[^>]*value='([^']*)'[^>]*>/g)].map((m) => m[1]);
  questions.push({ topic: Number(topic), type: Number(type), required: req === "1", title: title.replace(/<[^>]+>/g, "").trim(), options });
}

// ---- 2. codec (verbatim from jqmobo2.js) -----------------------------------
const SP = ["$", "}", "^", "|", "!", "<"];
const SP_TO = ["ξ", "｝", "ˆ", "¦", "！", "＜"];
function replaceSpecialChar(s) {
  if (!s) return s;
  for (let i = 0; i < SP.length; i++) {
    s = s.split(SP[i]).join(SP_TO[i]);
  }
  return s.replace(/[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD\uD800-\uDBFF\uDC00-\uDFFF]/gi, "").trim();
}
function dataenc(str, ktimes) {
  let key = ktimes % 10;
  if (key === 0) key = 1;
  let out = "";
  for (let i = 0; i < str.length; i++) out += String.fromCharCode(str.charCodeAt(i) ^ key);
  return out;
}
function encodeSubmitData(pairs) {
  const sorted = [...pairs].sort((a, b) => a.topic - b.topic);
  return sorted.map((p) => p.topic + SP[0] + p.value).join(SP[1]);
}

const answers = [
  { topic: 1, value: replaceSpecialChar("接口测试") },
  { topic: 2, value: replaceSpecialChar("20260000001") },
  { topic: 3, value: replaceSpecialChar("测试班级") },
];
const submitdata = encodeSubmitData(answers);
const jqsign = dataenc(jqnonce, KTIMES);

// ---- 3. build URL + body exactly like the page JS --------------------------
const cst = Date.now();
const url =
  action +
  "&starttime=" + encodeURIComponent(starttime) +
  "&cst=" + cst +
  "&source=" + encodeURIComponent(source) +
  "&ktimes=" + KTIMES +
  (captchaType ? "&capt=" + captchaType : "") +
  "&t=" + Date.now() +
  "&jqnonce=" + encodeURIComponent(jqnonce) +
  "&jqsign=" + encodeURIComponent(jqsign);

const body =
  "submitdata=" + encodeURIComponent(submitdata) +
  "&captchaVerifyParam=" +
  "&sceneId=q0hcfsca";

const parsed = {
  pageStatus: page.status,
  action, shortId, jqnonce, captchaType, useAliVerify, needLoadAliVerify,
  needVerifyCode, oneQPerPage, starttime, source, ktimesSent: KTIMES,
  jqsign, submitdata, questions, cookiesAfterPage: Object.fromEntries(jar.cookies),
  answerValues: answers.map((a) => a.value),
};

console.log("=== PARSED ===");
console.log(JSON.stringify(parsed, null, 2));
console.log("=== WAITING " + WAIT_MS + "ms before submit (avoid answer-speed heuristics) ===");
await new Promise((r) => setTimeout(r, WAIT_MS));

// ---- 4. ONE POST ----------------------------------------------------------
const t0 = Date.now();
const res = await request(url, {
  method: "POST",
  headers: {
    "User-Agent": UA,
    Accept: "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "X-Requested-With": "XMLHttpRequest",
    Origin: "https://www.wjx.cn",
    Referer: PAGE_URL,
  },
  body,
  jar,
  timeoutMs: 40000,
});
const elapsed = Date.now() - t0;

fs.writeFileSync(path.join(EV, "03-submit-request.txt"), res.requestRaw + "\n", "utf8");
fs.writeFileSync(path.join(EV, "03-submit-response.txt"), res.responseRaw + "\n", "utf8");
fs.writeFileSync(path.join(EV, "03-cookies-after.json"), JSON.stringify(jar.toJSON(), null, 2), "utf8");

const parts = res.body.split("〒");
const code = parts[0];
const verdict =
  code === "10" ? "SUCCESS (code 10)" :
  code === "22" ? "BLOCKED_BY_CAPTCHA (code 22 need-validate)" :
  code === "7" ? "BLOCKED (code 7)" :
  code === "11" ? "SUCCESS-ish (code 11)" :
  "OTHER (code=" + JSON.stringify(code) + ")";

const out = {
  ...parsed,
  submitUrl: url,
  submitBody: body,
  httpStatus: res.status,
  elapsedMs: elapsed,
  responseFirst2000: res.body.slice(0, 2000),
  responseParts: parts.slice(0, 6),
  responseBytes: res.bodyBytes,
  cookiesAfterSubmit: Object.fromEntries(jar.cookies),
  verdict,
};
fs.writeFileSync(path.join(EV, "03-result.json"), JSON.stringify(out, null, 2), "utf8");

console.log("=== SUBMIT RESULT ===");
console.log("HTTP", res.status, "in", elapsed + "ms");
console.log("VERDICT:", verdict);
console.log("RESPONSE (first 1200 chars):");
console.log(res.body.slice(0, 1200));
