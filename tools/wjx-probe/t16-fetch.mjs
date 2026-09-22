
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { request, CookieJar } from "./lib/http.mjs";
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const EV = path.join(__dirname, "evidence");
const UA = "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
for (const [name, url] of [["13-notopen-tfGAWU4", "https://v.wjx.cn/vm/tfGAWU4.aspx"], ["13-open-P2M09FG", "https://v.wjx.cn/vm/P2M09FG.aspx"]]) {
  const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar(name), timeoutMs: 30000 });
  fs.writeFileSync(path.join(EV, name + ".html"), res.body, "utf8");
  const h = res.body;
  const pick = (re) => { const m = h.match(re); return m ? m[1] : null; };
  console.log("### " + name + " status=" + res.status + " bytes=" + res.bodyBytes);
  console.log("  title:", pick(/<title>([\s\S]*?)<\/title>/i));
  console.log("  BeginDate:", pick(/BeginDate\s*=\s*"?([0-9]+)"?/i));
  console.log("  EndDate:", pick(/EndDate\s*=\s*"?([0-9]+)"?/i));
  console.log("  jqnonce:", pick(/var\s+jqnonce\s*=\s*"([^"]+)"/));
  console.log("  useAliVerify:", pick(/var\s+useAliVerify\s*=\s*(\d+)/));
  console.log("  fieldset count:", (h.match(/<fieldset/g) || []).length, " topic count:", (h.match(/topic='/g) || []).length);
  const i = h.indexOf("divstarttime");
  console.log("  divstarttime ctx:", i >= 0 ? h.slice(Math.max(0, i - 120), i + 420).replace(/\s+/g, " ") : "(无)");
  const j = h.indexOf("BeginDate");
  console.log("  BeginDate ctx:", j >= 0 ? h.slice(Math.max(0, j - 160), j + 200).replace(/\s+/g, " ") : "(无)");
}
