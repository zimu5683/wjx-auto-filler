// tools/wjx-probe/t11-submit-variant.mjs
// T11：业务码 22 深挖 —— 每次只改一个变量，最多 5 次提交，全部留原始证据。
// 用法：node t11-submit-variant.mjs <tag>
// 变体定义见 VARIANTS（每次运行只发 1 次 POST）。
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
fs.mkdirSync(EV, { recursive: true });

// 第 3 个参数可指定页面 URL（默认用户样本 P2M09FG）；第 4 个参数指定证据前缀（默认=tag）
const PAGE_URL = process.argv[3] || "https://v.wjx.cn/vm/P2M09FG.aspx";
const PAGE_ORIGIN = new URL(PAGE_URL).origin;
const MOBILE_UA = "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
const DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

// capt=2 是浏览器在 captchaType==2 时必带的 query 参数
const VARIANTS = {
  // V1（已跑，返回 22）：与 Lead 的 S0 只差 body 的两个字段
  v1: { body: "captScene", rn: false, cst: false, source: false, ktimes: 0, ua: "mobile", note: "S0 + captchaVerifyParam=(空) + sceneId=q0hcfsca" },
  // V2：只补 &rn=<rndnum>（浏览器在 window.rndnum 非空时必带；我们所有测试都没带）
  v2: { body: "plain", rn: true, cst: false, source: false, ktimes: 0, ua: "mobile", note: "S0 + &rn=<rndnum>（单变量）" },
  // V3：完整浏览器 URL 对齐（rn + cst + source）+ ktimes=4 + 两个 body 字段
  v3: { body: "captScene", rn: true, cst: true, source: true, ktimes: 4, ua: "mobile", note: "完整浏览器对齐（rn+cst+source+ktimes=4+body 两字段）" },
  // V4：V3 去掉 cst（隔离「22 → 7」是哪个变量造成的）
  v4: { body: "captScene", rn: true, cst: false, source: true, ktimes: 4, ua: "mobile", note: "V3 去掉 cst（其余同 V3）" },
  // V5：V3 去掉 source（若 V4 仍为 7，则继续隔离 source）
  v5: { body: "captScene", rn: true, cst: true, source: false, ktimes: 4, ua: "mobile", note: "V3 去掉 source（其余同 V3）" },
  // V6：Lead 批准的干净单变量 A/B —— S0 基线只改 ktimes（0 → 4）
  v6: { body: "plain", rn: false, cst: false, source: false, ktimes: 4, ua: "mobile", note: "S0 + 仅 ktimes=4（干净单变量 A/B，Lead 批准）" },
};

const tag = process.argv[2];
const evTag = process.argv[4] || tag;
const cfg = VARIANTS[tag];
if (!cfg) {
  console.error("未知变体：" + tag + "，可选：" + Object.keys(VARIANTS).join(","));
  process.exit(2);
}

const UA = cfg.ua === "desktop" ? DESKTOP_UA : MOBILE_UA;
const jar = new CookieJar(tag);
const page = await request(PAGE_URL, {
  headers: { "User-Agent": UA, Accept: "text/html,application/xhtml+xml", "Accept-Language": "zh-CN,zh;q=0.9" },
  jar, timeoutMs: 30000,
});
const html = page.body;
const pick = (re) => { const m = html.match(re); return m ? m[1] : null; };

const action = pick(/<form id="form1"[^>]*action="([^"]+)"/i);
const jqnonce = pick(/var\s+jqnonce\s*=\s*"([^"]+)"/);
const starttime = pick(/<input[^>]*id="starttime"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="starttime"/i);
const source = pick(/<input[^>]*id="source"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="source"/i);
const captchaType = pick(/var\s+captchaType\s*=\s*'([^']*)'/);
const useAliVerify = pick(/var\s+useAliVerify\s*=\s*(\d+)/);

const SP = ["$", "}", "^", "|", "!", "<"];
const SP_TO = ["ξ", "｝", "ˆ", "¦", "！", "＜"];
function esc(s) { for (let i = 0; i < SP.length; i++) s = s.split(SP[i]).join(SP_TO[i]); return s; }
function dataenc(str, kt) { let k = kt % 10; if (k === 0) k = 1; let o = ""; for (let i = 0; i < str.length; i++) o += String.fromCharCode(str.charCodeAt(i) ^ k); return o; }

const submitdata = [1, 2, 3].map((t, i) => t + SP[0] + esc(["测试A", "2024001", "1班"][i])).join(SP[1]);
const jqsign = dataenc(jqnonce, cfg.ktimes);
const now = Date.now();

const rndnum = pick(/var\s+rndnum\s*=\s*"([^"]*)"/);

let url = action + "&starttime=" + encodeURIComponent(starttime);
if (cfg.cst) url += "&cst=" + now;
if (cfg.source) url += "&source=" + encodeURIComponent(source);
url += "&ktimes=" + cfg.ktimes;
if (!cfg.nocapt && captchaType) url += "&capt=" + captchaType;
if (cfg.rn && rndnum) url += "&rn=" + encodeURIComponent(rndnum);
url += "&t=" + now + "&jqnonce=" + encodeURIComponent(jqnonce) + "&jqsign=" + encodeURIComponent(jqsign);

let body = "submitdata=" + encodeURIComponent(submitdata);
if (cfg.body === "captScene" || cfg.body === "captOnly") body += "&captchaVerifyParam=";
if (cfg.body === "captScene") body += "&sceneId=q0hcfsca";

const meta = {
  tag, note: cfg.note, cfg,
  page: { status: page.status, title: pick(/<title>([\s\S]*?)<\/title>/i), action, jqnonce, starttime, source, captchaType, useAliVerify, rndnum },
  url, body, submitdata, jqsign, cookiesAfterPage: Object.fromEntries(jar.cookies),
};

const t0 = Date.now();
const res = await request(url, {
  method: "POST",
  headers: {
    "User-Agent": UA,
    Accept: "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
    "X-Requested-With": "XMLHttpRequest",
    Origin: PAGE_ORIGIN,
    Referer: PAGE_URL,
  },
  body, jar, timeoutMs: 40000,
});
const elapsed = Date.now() - t0;

fs.writeFileSync(path.join(EV, "11-" + evTag + "-request.txt"), res.requestRaw + "\n", "utf8");
fs.writeFileSync(path.join(EV, "11-" + evTag + "-response.txt"), res.responseRaw + "\n", "utf8");
const out = { ...meta, httpStatus: res.status, elapsedMs: elapsed, responseRaw: res.body, responseParts: res.body.split("〒").slice(0, 5) };
fs.writeFileSync(path.join(EV, "11-" + evTag + "-result.json"), JSON.stringify(out, null, 2), "utf8");

console.log("=== " + evTag + " · " + cfg.note + " · " + PAGE_URL + " ===");
console.log("HTTP " + res.status + " in " + elapsed + "ms");
console.log("RESPONSE: " + JSON.stringify(res.body));
