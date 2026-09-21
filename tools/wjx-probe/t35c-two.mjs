
import { request, CookieJar } from "./lib/http.mjs";
const UA = "Mozilla/5.0 (Linux; Android 16; ELN2-AN00 Build/HONORELN2-AN00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
for (const url of ["https://www.wjx.cn/vm/OPi1TlY.aspx", "https://www.wjx.cn/vm/mpPKpP7.aspx"]) {
  try {
    const res = await request(url, { headers: { "User-Agent": UA, Accept: "text/html" }, jar: new CookieJar("s"), timeoutMs: 25000 });
    const h = res.body;
    const pick = (re) => { const m = h.match(re); return m ? m[1] : null; };
    const qs = [];
    const fr = /<div class='field[^']*'\s+topic='(\d+)'[^>]*type='(\d+)'/g;
    let fm; while ((fm = fr.exec(h)) !== null) qs.push(Number(fm[2]));
    console.log(JSON.stringify({ url, status: res.status, bytes: res.bodyBytes,
      title: ((h.match(/<title>([\s\S]*?)<\/title>/i)||[])[1]||"").trim().slice(0,50),
      useAliVerify: pick(/var\s+useAliVerify\s*=\s*(\d+)/),
      captchaType: pick(/var\s+captchaType\s*=\s*'([^']*)'/),
      needLoadAliVerify: pick(/var\s+needLoadAliVerify\s*=\s*(\d+)/),
      qCount: qs.length, qTypes: [...new Set(qs)].sort((a,b)=>a-b) }));
  } catch (e) { console.log(JSON.stringify({ url, error: String(e.message) })); }
}
