
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
const UA = "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
const url = "https://v.wjx.cn/vm/P2M09FG.aspx";
const jar = new CookieJar("v");
const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html", "Accept-Language": "zh-CN,zh;q=0.9" }, jar, timeoutMs: 30000 });
fs.writeFileSync(path.join(EV, "10-v-page.html"), res.body, "utf8");
fs.writeFileSync(path.join(EV, "10-v-page.headers.txt"), res.responseRaw.split("\n\n")[0] + "\n", "utf8");
const h = res.body;
const pick = (re) => { const m = h.match(re); return m ? m[1] : null; };
const qs = [];
const fr = /<div class='field[^']*'\s+topic='(\d+)'[^>]*type='(\d+)'/g;
let fm; while ((fm = fr.exec(h)) !== null) qs.push({ topic: +fm[1], type: +fm[2] });
console.log(JSON.stringify({
  status: res.status, bytes: res.bodyBytes, finalUrl: res.finalUrl, redirects: res.redirects,
  title: pick(/<title>([\s\S]*?)<\/title>/i),
  action: pick(/<form id="form1"[^>]*action="([^"]+)"/i),
  jqnonce: pick(/var\s+jqnonce\s*=\s*"([^"]+)"/),
  useAliVerify: pick(/var\s+useAliVerify\s*=\s*(\d+)/),
  captchaType: pick(/var\s+captchaType\s*=\s*'([^']*)'/),
  needLoadAliVerify: pick(/var\s+needLoadAliVerify\s*=\s*(\d+)/),
  needVerifyCode: pick(/var\s+NeedVerifyCode\s*=\s*([^;]+);/),
  oneQPerPage: pick(/var\s+IsOneQuestionPerPage\s*=\s*([^;]+);/),
  starttime: pick(/<input[^>]*id="starttime"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="starttime"/i),
  source: pick(/<input[^>]*id="source"[^>]*value="([^"]*)"/i) || pick(/<input[^>]*value="([^"]*)"[^>]*id="source"/i),
  questions: qs, cookies: Object.fromEntries(jar.cookies),
  hasCaptchaSceneidInline: h.includes("captchaSceneid"),
}, null, 2));
