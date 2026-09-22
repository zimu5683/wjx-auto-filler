import com.wjx.autofill.wjx.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** T4 引擎的 JVM 冒烟测试（api-debug 自测用；正式单测由 qa-build 在 src/test 写）。 */
public class SmokeTest {
    static int pass = 0, fail = 0;
    static void eq(String name, Object actual, Object expected) {
        if (Objects.equals(actual, expected)) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + "\n   expected: " + expected + "\n   actual:   " + actual); }
    }
    static void check(String name, boolean cond, String detail) {
        if (cond) { pass++; System.out.println("PASS " + name); }
        else { fail++; System.out.println("FAIL " + name + " :: " + detail); }
    }
    static List<kotlin.Pair<Integer, String>> pairs(Object... kv) {
        List<kotlin.Pair<Integer, String>> out = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) out.add(new kotlin.Pair<>((Integer) kv[i], (String) kv[i + 1]));
        return out;
    }
    static String read(String p) throws Exception { return new String(Files.readAllBytes(Paths.get(p)), StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        // ---------- §3.4 codec 测试向量（逐字照抄契约） ----------
        eq("escape.mix", WjxSubmitCodec.INSTANCE.escape("a$b}c^d|e!f<g"), "aξb｝cˆd¦e！f＜g");
        eq("escape.cn", WjxSubmitCodec.INSTANCE.escape("正常文本，含$和}与|"), "正常文本，含ξ和｝与¦");
        eq("escape.idempotent", WjxSubmitCodec.INSTANCE.escape("已转义ξ｝ˆ¦！＜不应二次转义"), "已转义ξ｝ˆ¦！＜不应二次转义");
        eq("escape.ctrl", WjxSubmitCodec.INSTANCE.escape("a\u0001b"), "ab");
        String N = "e2a8439c-aa7b-41d7-b0c7-9c10852d66d6";
        eq("jqSign.ktimes0", WjxSubmitCodec.INSTANCE.jqSign(N, 0), "d3`9528b,``6c,50e6,c1b6,8b01943e77e7");
        eq("jqSign.ktimes7", WjxSubmitCodec.INSTANCE.jqSign(N, 7), "b5f?34>d*ff0e*36c0*e7d0*>d67?25c11c1");
        eq("jqSign.mod0", WjxSubmitCodec.INSTANCE.jqSign("abc", 10), "`cb");
        eq("jqSign.3", WjxSubmitCodec.INSTANCE.jqSign("abc", 3), "ba`");
        eq("encode.basic", WjxSubmitCodec.INSTANCE.encodeSubmitData(pairs(1, "张三", 2, "2024001", 3, "生物1班")), "1$张三}2$2024001}3$生物1班");
        eq("encode.multi", WjxSubmitCodec.INSTANCE.encodeSubmitData(pairs(2, "1|3")), "2$1|3");
        eq("encode.sorted", WjxSubmitCodec.INSTANCE.encodeSubmitData(pairs(3, "C", 1, "A", 2, "B")), "1$A}2$B}3$C");
        eq("encode.dupTopic", WjxSubmitCodec.INSTANCE.encodeSubmitData(pairs(1, "A", 1, "B")), "1$B");
        eq("encode.empty", WjxSubmitCodec.INSTANCE.encodeSubmitData(new ArrayList<>()), "");
        // 与 T3 实测请求逐字节对齐：真实 jqsign(ktimes=4) + submitdata
        // 与 T3 实测请求逐字节对齐（evidence/03-result.json 里的真实 jqsign，ktimes=4）
        eq("t3.jqsign.ktimes4", WjxSubmitCodec.INSTANCE.jqSign("2bc94024-a360-4f80-9d7b-54130a7cbb63", 4), "6fg=0460)e724)0b<4)=`3f)10574e3gff27");

        // ---------- §8.4 分类器（真实响应向量） ----------
        SubmitResult r1 = WjxResponseClassifier.INSTANCE.classify(200, "7〒需要安全校验，请重新提交！");
        check("classifier.7.captcha", !r1.getOk() && SubmitErrorCode.CAPTCHA.equals(r1.getErrorCode()), r1.toString());
        check("classifier.7.msg", r1.getMessage().contains("安全校验"), r1.getMessage());
        check("classifier.7.raw", r1.getRaw() != null && r1.getRaw().contains("需要安全校验"), String.valueOf(r1.getRaw()));
        SubmitResult r2 = WjxResponseClassifier.INSTANCE.classify(200, "10〒");
        check("classifier.10.ok", r2.getOk() && r2.getErrorCode() == null && "提交成功".equals(r2.getMessage()), r2.toString());
        SubmitResult r3 = WjxResponseClassifier.INSTANCE.classify(200, "5〒请输入正确的学号");
        check("classifier.5.rejected", !r3.getOk() && SubmitErrorCode.REJECTED.equals(r3.getErrorCode()) && r3.getMessage().contains("请输入正确的学号"), r3.toString());
        check("classifier.waf", SubmitErrorCode.CAPTCHA.equals(WjxResponseClassifier.INSTANCE.classify(200, "<html><script>aliyunwaf</script>").getErrorCode()), "waf");
        check("classifier.empty", SubmitErrorCode.PARSE.equals(WjxResponseClassifier.INSTANCE.classify(200, "").getErrorCode()), "empty");
        check("classifier.html", SubmitErrorCode.PARSE.equals(WjxResponseClassifier.INSTANCE.classify(200, "<html><body>x</body></html>").getErrorCode()), "html");
        check("classifier.unknown", SubmitErrorCode.UNKNOWN.equals(WjxResponseClassifier.INSTANCE.classify(200, "随便什么").getErrorCode()), "unknown");
        check("classifier.http500", SubmitErrorCode.HTTP.equals(WjxResponseClassifier.INSTANCE.classify(500, "boom").getErrorCode()), "http500");
        check("classifier.11.ok", WjxResponseClassifier.INSTANCE.classify(200, "11〒").getOk(), "11");
        check("classifier.22.captcha", SubmitErrorCode.CAPTCHA.equals(WjxResponseClassifier.INSTANCE.classify(200, "22〒请验证").getErrorCode()), "22");

        // ---------- 解析 fixture ----------
        String fx = args[0];
        SurveyModel sample = WjxPageParser.INSTANCE.parse("https://www.wjx.cn/vm/Q0DQewW.aspx", read(fx + "/Q0DQewW-3q-text-captcha-enabled.html"), new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("parse.sample.title", sample.getTitle(), "测试");
        eq("parse.sample.qcount", sample.getQuestions().size(), 3);
        eq("parse.sample.q1type", sample.getQuestions().get(0).getType(), QuestionType.TEXT);
        eq("parse.sample.q1title", sample.getQuestions().get(0).getTitle(), "姓名（例：XXX）");
        check("parse.sample.useAliVerify", sample.getUseAliVerify(), "useAliVerify=" + sample.getUseAliVerify());
        check("parse.sample.captchaType", Integer.valueOf(2).equals(sample.getCaptchaType()), "captchaType=" + sample.getCaptchaType());
        check("parse.sample.jqnonce", sample.getJqnonce() != null && sample.getJqnonce().length() > 10, "jqnonce=" + sample.getJqnonce());
        check("parse.sample.starttime", sample.getStartTime() != null && sample.getStartTime().contains("/"), "start=" + sample.getStartTime());
        check("parse.sample.submitUrl", sample.getSubmitUrl().contains("processjq.ashx?shortid=Q0DQewW"), sample.getSubmitUrl());
        check("parse.sample.sceneId.null", sample.getSceneId() == null, "sceneId=" + sample.getSceneId());

        SurveyModel choice = WjxPageParser.INSTANCE.parse("https://www.wjx.cn/vm/rRESgvn.aspx", read(fx + "/rRESgvn-22q-choice.html"), new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("parse.choice.qcount", choice.getQuestions().size(), 22);
        check("parse.choice.allChoice", choice.getQuestions().stream().allMatch(q -> q.getType() == QuestionType.SINGLE || q.getType() == QuestionType.MULTI), "types=" + choice.getQuestions().stream().map(SurveyQuestion::getType).distinct().toList());
        SurveyQuestion cq = choice.getQuestions().stream().filter(q -> q.getType() == QuestionType.SINGLE).findFirst().orElse(null);
        check("parse.choice.options", cq != null && !cq.getOptions().isEmpty(), "q=" + cq);
        check("parse.choice.labels", cq != null && cq.getOptions().stream().anyMatch(o -> o.getLabel() != null && !o.getLabel().isEmpty() && !o.getLabel().equals(o.getValue())), "opts=" + (cq == null ? null : cq.getOptions()));
        check("parse.choice.values", cq != null && cq.getOptions().get(0).getValue().matches("\\d+"), "v=" + (cq == null ? null : cq.getOptions().get(0).getValue()));
        check("parse.choice.useAliVerify.false", !choice.getUseAliVerify(), "useAliVerify=" + choice.getUseAliVerify());

        SurveyModel mixed = WjxPageParser.INSTANCE.parse("https://www.wjx.cn/vm/hPyt0iq.aspx", read(fx + "/hPyt0iq-37q-text-single-multi.html"), new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("parse.mixed.qcount", mixed.getQuestions().size(), 37);
        check("parse.mixed.hasText", mixed.getQuestions().stream().anyMatch(q -> q.getType() == QuestionType.TEXT), "types");
        check("parse.mixed.hasSingle", mixed.getQuestions().stream().anyMatch(q -> q.getType() == QuestionType.SINGLE), "types");
        check("parse.mixed.hasMulti", mixed.getQuestions().stream().anyMatch(q -> q.getType() == QuestionType.MULTI), "types");
        SurveyQuestion mq = mixed.getQuestions().stream().filter(q -> q.getType() == QuestionType.MULTI).findFirst().orElse(null);
        check("parse.mixed.multi.options", mq != null && mq.getOptions().size() >= 2, "multi=" + mq);
        eq("parse.mixed.sorted", mixed.getQuestions().get(0).getTopic(), 1);

        // ---------- 匹配器 ----------
        List<AnswerPair> ans = Arrays.asList(new AnswerPair("姓名", "接口测试"), new AnswerPair("2", "20260000001"), new AnswerPair("班级", "测试班级"));
        MatchOutcome mo = WjxAnswerMatcher.INSTANCE.match(sample, ans);
        check("match.ok", mo instanceof MatchOutcome.Ok, String.valueOf(mo));
        if (mo instanceof MatchOutcome.Ok) eq("match.pairs", ((MatchOutcome.Ok) mo).getPairs().size(), 3);
        MatchOutcome bad = WjxAnswerMatcher.INSTANCE.match(sample, Arrays.asList(new AnswerPair("不存在的字段", "x")));
        check("match.unmatched", bad instanceof MatchOutcome.Fail && SubmitErrorCode.UNMATCHED.equals(((MatchOutcome.Fail) bad).getCode()), String.valueOf(bad));
        MatchOutcome dup = WjxAnswerMatcher.INSTANCE.match(sample, Arrays.asList(new AnswerPair("1", "a"), new AnswerPair("姓名", "b")));
        check("match.dup", dup instanceof MatchOutcome.Fail && ((MatchOutcome.Fail) dup).getMessage().contains("指向同一题"), String.valueOf(dup));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 3001; i++) longText.append('x');
        MatchOutcome lim = WjxAnswerMatcher.INSTANCE.match(sample, Arrays.asList(new AnswerPair("1", longText.toString())));
        check("match.limit", lim instanceof MatchOutcome.Fail && SubmitErrorCode.LIMIT.equals(((MatchOutcome.Fail) lim).getCode()), String.valueOf(lim));
        // R3：选项文本作为字段，空 value 取 option.value
        SurveyQuestion single = cq;
        if (single != null && !single.getOptions().isEmpty()) {
            Option o = single.getOptions().get(0);
            MatchOutcome r3m = WjxAnswerMatcher.INSTANCE.match(choice, Arrays.asList(new AnswerPair(o.getLabel(), "")));
            check("match.r3.option", r3m instanceof MatchOutcome.Ok, String.valueOf(r3m));
            if (r3m instanceof MatchOutcome.Ok) {
                List<kotlin.Pair<Integer, String>> ps = ((MatchOutcome.Ok) r3m).getPairs();
                eq("match.r3.value", ps.get(0).getSecond(), o.getValue());
            }
        }
        // MULTI 的 | 拼接
        if (mq != null && mq.getOptions().size() >= 2) {
            String v1 = mq.getOptions().get(0).getValue();
            String v2 = mq.getOptions().get(1).getValue();
            MatchOutcome mr = WjxAnswerMatcher.INSTANCE.match(mixed, Arrays.asList(new AnswerPair(String.valueOf(mq.getTopic()), v1 + "|" + v2)));
            check("match.multi", mr instanceof MatchOutcome.Ok, String.valueOf(mr));
            if (mr instanceof MatchOutcome.Ok) eq("match.multi.value", ((MatchOutcome.Ok) mr).getPairs().get(0).getSecond(), v1 + "|" + v2);
        }

        // ---------- 下拉题（qa-build 报告的偏差回归） ----------
        String synth = "<html><head><title>下拉测试</title></head><body>"
            + "<script>var jqnonce = \"11111111-2222-3333-4444-555555555555\"; var ktimes = 0;</script>"
            + "<form id=\"form1\" method=\"post\" action=\"https://www.wjx.cn/joinnew/processjq.ashx?shortid=AAAABBBB\">"
            + "<div id=\"divQuestion\"><fieldset class='fieldset' pg='1' id='fieldset1'>"
            + "<div class='field ui-field-contain' topic='1' id='div1' req='1' type='10'>"
            + "<div class='field-label'><span class='req'>*</span><div class='topicnumber'>1.</div><div class='topichtml'>下拉题</div></div>"
            + "<select name='q1'><option value='1'>A</option><option value='2'>B</option></select>"
            + "</div></fieldset></div>"
            + "<input type=\"hidden\" id=\"starttime\" value=\"2026/9/22 1:00:00\" />"
            + "</form></body></html>";
        SurveyModel dd = WjxPageParser.INSTANCE.parse("https://www.wjx.cn/vm/AAAABBBB.aspx", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("dropdown.count", dd.getQuestions().size(), 1);
        eq("dropdown.type", dd.getQuestions().get(0).getType(), QuestionType.DROPDOWN);
        eq("dropdown.opts", dd.getQuestions().get(0).getOptions().size(), 2);
        eq("dropdown.v1", dd.getQuestions().get(0).getOptions().get(0).getValue(), "1");
        eq("dropdown.l1", dd.getQuestions().get(0).getOptions().get(0).getLabel(), "A");
        MatchOutcome ddm = WjxAnswerMatcher.INSTANCE.match(dd, Arrays.asList(new AnswerPair("1", "B")));
        check("dropdown.match.label", ddm instanceof MatchOutcome.Ok && ((MatchOutcome.Ok) ddm).getPairs().get(0).getSecond().equals("2"), String.valueOf(ddm));

        // ---------- 请求构造接缝（§13.10） ----------
        List<SurveyQuestion> qs = new ArrayList<>();
        qs.add(new SurveyQuestion(1, "姓名", QuestionType.TEXT, new ArrayList<Option>()));
        List<kotlin.Pair<Integer, String>> submitPairs = pairs(1, "张三");
        SurveyModel noScene = new SurveyModel("https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW", "nonce-abc", 4, "2026/9/22 1:00:00", 2,
            new HashMap<String, String>(), false, null, null, null, false);
        String bodyPlain = WjxSubmitRequest.INSTANCE.buildSubmitBody(noScene, submitPairs, null);
        eq("body.plain", bodyPlain, "submitdata=" + java.net.URLEncoder.encode("1$张三", "UTF-8"));
        check("body.plain.noToken", !bodyPlain.contains("captchaVerifyParam") && !bodyPlain.contains("sceneId"), bodyPlain);
        String bodyTok = WjxSubmitRequest.INSTANCE.buildSubmitBody(noScene, submitPairs, "tok-123");
        check("body.token", bodyTok.contains("captchaVerifyParam=tok-123"), bodyTok);
        check("body.token.noScene", !bodyTok.contains("sceneId="), bodyTok);
        SurveyModel withScene = new SurveyModel("https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW", "nonce-abc", 4, "2026/9/22 1:00:00", 2,
            new HashMap<String, String>(), false, "q0hcfsca", SceneIdSource.CAPTCHA_JS, null, false);
        String bodyBoth = WjxSubmitRequest.INSTANCE.buildSubmitBody(withScene, submitPairs, "tok-123");
        check("body.token.scene", bodyBoth.contains("captchaVerifyParam=tok-123") && bodyBoth.contains("sceneId=q0hcfsca"), bodyBoth);
        String url = WjxSubmitRequest.INSTANCE.buildSubmitUrl(noScene);
        check("url.params", url != null && url.contains("starttime=") && url.contains("&ktimes=4") && url.contains("&jqnonce=nonce-abc") && url.contains("&jqsign=") && url.contains("&capt=2") && url.contains("&t="), String.valueOf(url));
        check("url.jqsign", url.contains("jqsign=" + java.net.URLEncoder.encode(WjxSubmitCodec.INSTANCE.jqSign("nonce-abc", 4), "UTF-8").replace("+", "%20")), String.valueOf(url));
        SurveyModel badUrl = new SurveyModel("x", "Q0DQewW", "t", qs, "http://evil.example.com/x", "n", 0, "", null, new HashMap<String, String>(), false, null, null, null, false);
        check("url.invalid", WjxSubmitRequest.INSTANCE.buildSubmitUrl(badUrl) == null, "invalid url");

        // ---------- sceneId 解析（页面 / JS / 失败 / 不启用 四分支，纯函数） ----------
        eq("scene.extract.js", WjxSceneId.INSTANCE.extract("var captchaSceneid=\"q0hcfsca\";"), "q0hcfsca");
        eq("scene.extract.single", WjxSceneId.INSTANCE.extract("captchaSceneid='abc123'"), "abc123");
        eq("scene.extract.none", WjxSceneId.INSTANCE.extract("<html>nothing here</html>"), null);
        final boolean[] loaderCalled = { false };
        kotlin.Pair<String, SceneIdSource> sc1 = WjxSceneId.INSTANCE.resolve("fromPage", true, () -> {
            loaderCalled[0] = true;
            return "var captchaSceneid=\"fromJs\";";
        });
        eq("scene.branch.page", sc1.getFirst(), "fromPage");
        eq("scene.branch.page.source", sc1.getSecond(), SceneIdSource.PAGE);
        check("scene.branch.page.noJsFetch", !loaderCalled[0], "页面已有值却仍去抓 JS");
        kotlin.Pair<String, SceneIdSource> sc2 = WjxSceneId.INSTANCE.resolve(null, true,
            () -> "var captchaSceneid=\"q0hcfsca\";");
        eq("scene.branch.js", sc2.getFirst(), "q0hcfsca");
        eq("scene.branch.js.source", sc2.getSecond(), SceneIdSource.CAPTCHA_JS);
        kotlin.Pair<String, SceneIdSource> sc3 = WjxSceneId.INSTANCE.resolve(null, true, () -> null);
        eq("scene.branch.fetchFail", sc3.getFirst(), null);
        eq("scene.branch.fetchFail.source", sc3.getSecond(), null);
        kotlin.Pair<String, SceneIdSource> sc4 = WjxSceneId.INSTANCE.resolve(null, true, () -> {
            throw new RuntimeException("network down");
        });
        eq("scene.branch.throw", sc4.getFirst(), null);
        eq("scene.branch.throw.source", sc4.getSecond(), null);
        final boolean[] loader2 = { false };
        kotlin.Pair<String, SceneIdSource> sc5 = WjxSceneId.INSTANCE.resolve(null, false, () -> {
            loader2[0] = true;
            return "var captchaSceneid=\"x\";";
        });
        check("scene.branch.disabled", sc5.getFirst() == null && !loader2[0], "useAliVerify=0 不应抓 JS");

        // ---------- ktimes 下限 4（T11 实测：ktimes=0 → 裸码 22；ktimes=4 → 成功码 10；1 未验证） ----------
        SurveyModel zeroK = new SurveyModel("https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW", "nonce-abc", 0, "2026/9/22 1:00:00", 2,
            new HashMap<String, String>(), false, null, null, null, false);
        String urlZero = WjxSubmitRequest.INSTANCE.buildSubmitUrl(zeroK);
        check("url.ktimes.min4", urlZero != null && urlZero.contains("&ktimes=4") && !urlZero.contains("&ktimes=0"), String.valueOf(urlZero));
        check("url.ktimes.min4.sign", urlZero != null && urlZero.contains("jqsign=" + java.net.URLEncoder.encode(WjxSubmitCodec.INSTANCE.jqSign("nonce-abc", 4), "UTF-8").replace("+", "%20")), String.valueOf(urlZero));
        SurveyModel threeK = new SurveyModel("https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW", "nonce-abc", 3, "2026/9/22 1:00:00", 2,
            new HashMap<String, String>(), false, null, null, null, false);
        String urlThree = WjxSubmitRequest.INSTANCE.buildSubmitUrl(threeK);
        check("url.ktimes.floor4", urlThree != null && urlThree.contains("&ktimes=4") && !urlThree.contains("&ktimes=3"), String.valueOf(urlThree));
        SurveyModel sevenK = new SurveyModel("https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW", "nonce-abc", 7, "2026/9/22 1:00:00", 2,
            new HashMap<String, String>(), false, null, null, null, false);
        String urlSeven = WjxSubmitRequest.INSTANCE.buildSubmitUrl(sevenK);
        check("url.ktimes.passthrough", urlSeven != null && urlSeven.contains("&ktimes=7"), String.valueOf(urlSeven));

        // ---------- URL 正则：允许 wjx.cn 任意子域（契约 T14） ----------
        SurveyModel subV = WjxPageParser.INSTANCE.parse("https://v.wjx.cn/vm/AAAABBBB.aspx", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("url.subdomain.v", subV.getShortId(), "AAAABBBB");
        SurveyModel subBare = WjxPageParser.INSTANCE.parse("https://wjx.cn/vm/AAAABBBB.aspx", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("url.bare.domain", subBare.getShortId(), "AAAABBBB");
        SurveyModel subDash = WjxPageParser.INSTANCE.parse("https://my-survey.wjx.cn/vm/AAAABBBB.aspx?q1=x#f", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("url.subdomain.dash.query", subDash.getShortId(), "AAAABBBB");
        try {
            WjxPageParser.INSTANCE.parse("http://v.wjx.cn/vm/AAAABBBB.aspx", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
            check("url.http.rejected", false, "http 未被拒绝");
        } catch (Exception ex) {
            check("url.http.rejected", ex instanceof WjxException && SubmitErrorCode.URL.equals(((WjxException) ex).getCode()), String.valueOf(ex));
        }
        try {
            WjxPageParser.INSTANCE.parse("https://evil.example.com/vm/AAAABBBB.aspx", synth, new HashMap<>(), WjxTimeAdapter.INSTANCE);
            check("url.foreign.rejected", false, "外域未被拒绝");
        } catch (Exception ex) {
            check("url.foreign.rejected", ex instanceof WjxException && SubmitErrorCode.URL.equals(((WjxException) ex).getCode()), String.valueOf(ex));
        }

        // ---------- T16：开放时间适配器 + E_NOT_OPEN + needsCaptchaHint ----------
        String notOpenHtml = read(fx + "/tfGAWU4-notopen.html");
        String openHtml = read(fx + "/P2M09FG-open.html");
        long nowMs = 1790034767873L; // 2026-09-22 前后
        OpenTime tNotOpen = WjxTimeAdapter.INSTANCE.parse(notOpenHtml, nowMs);
        check("time.notopen.known", tNotOpen instanceof OpenTime.Known, String.valueOf(tNotOpen));
        if (tNotOpen instanceof OpenTime.Known) {
            // 真实开放时间 = 2026-09-23 09:33 +08:00（文案 / left+nowTime 双证；qBeginDate 不是开放时间）
            eq("time.notopen.value", ((OpenTime.Known) tNotOpen).getOpenAtMillis(), 1790127180000L);
        }
        check("time.notopen.closed", !WjxTimeAdapter.INSTANCE.isOpen(tNotOpen, nowMs), "应判未开放");
        check("time.notopen.msg", WjxTimeAdapter.INSTANCE.notOpenMessage(tNotOpen).contains("2026-09-23 09:33"),
            WjxTimeAdapter.INSTANCE.notOpenMessage(tNotOpen));
        OpenTime tOpen = WjxTimeAdapter.INSTANCE.parse(openHtml, nowMs);
        check("time.open.known", tOpen instanceof OpenTime.Known, String.valueOf(tOpen));
        check("time.open.isOpen", WjxTimeAdapter.INSTANCE.isOpen(tOpen, nowMs), "应判已开放");
        String textOnly = "<div id='divstarttime' left='1'>很抱歉，此问卷将于2026-09-23 09:33（北京时间）开放，请到时再进入此页面进行填写！</div>";
        OpenTime tText = WjxTimeAdapter.INSTANCE.parse(textOnly, nowMs);
        check("time.text.known", tText instanceof OpenTime.Known, String.valueOf(tText));
        if (tText instanceof OpenTime.Known) {
            eq("time.text.value", ((OpenTime.Known) tText).getOpenAtMillis(), 1790127180000L);
        }
        eq("time.beijing.format", WjxTimeAdapter.INSTANCE.beijingTextOf(1790127180000L), "2026-09-23 09:33");
        // qBeginDate 单独存在时必须"不覆盖"未开放判定：仅时间戳 → 判为已开放（这是有意的兜底语义）
        long afterOpen = 1790127180000L + 60_000L;
        check("time.timestamp.only", WjxTimeAdapter.INSTANCE.isOpen(
            WjxTimeAdapter.INSTANCE.parse("qBeginDate=\"1790040856347\"", afterOpen), afterOpen), "仅有时间戳时按其时间判定");
        // left + nowTime 路径（无文案）
        String leftOnly = "<div id='divstarttime' left='85054'><div id='countdownHtml'></div></div><script>var nowTime = \"2026-09-22 09:55:25\";</script>";
        OpenTime tLeft = WjxTimeAdapter.INSTANCE.parse(leftOnly, nowMs);
        check("time.left.known", tLeft instanceof OpenTime.Known, String.valueOf(tLeft));
        if (tLeft instanceof OpenTime.Known) {
            // left 与 nowTime 在页面渲染上有 1s 级误差，容差 ±2s
            long delta = Math.abs(((OpenTime.Known) tLeft).getOpenAtMillis() - 1790127180000L);
            check("time.left.value", delta <= 2000L, "left+nowTime 偏差 " + delta + "ms");
        }
        check("time.empty.unknown", WjxTimeAdapter.INSTANCE.parse("", nowMs) == OpenTime.Unknown.INSTANCE, "空 HTML 应 Unknown");
        check("time.none.unknown", WjxTimeAdapter.INSTANCE.parse("<html>no date</html>", nowMs) == OpenTime.Unknown.INSTANCE, "无日期应 Unknown");
        check("time.garbage.unknown", WjxTimeAdapter.INSTANCE.parse("qBeginDate=\"9999999999999999\"", nowMs) == OpenTime.Unknown.INSTANCE, "脏数据应 Unknown");
        check("time.unknown.isOpen", WjxTimeAdapter.INSTANCE.isOpen(OpenTime.Unknown.INSTANCE, nowMs), "Unknown 必须视为可尝试");
        try {
            WjxPageParser.INSTANCE.parse("https://v.wjx.cn/vm/tfGAWU4.aspx", notOpenHtml, new HashMap<>(), WjxTimeAdapter.INSTANCE);
            check("parser.notopen", false, "未抛 E_NOT_OPEN");
        } catch (Exception ex) {
            check("parser.notopen",
                ex instanceof WjxException && SubmitErrorCode.NOT_OPEN.equals(((WjxException) ex).getCode())
                    && ((WjxException) ex).getMessage().contains("2026-09-23 09:33"),
                String.valueOf(ex));
        }
        SurveyModel openModel = WjxPageParser.INSTANCE.parse("https://v.wjx.cn/vm/P2M09FG.aspx", openHtml, new HashMap<>(), WjxTimeAdapter.INSTANCE);
        eq("parser.open.openAt", openModel.getOpenAtMillis(), 1790034767873L);
        eq("parser.open.hint", openModel.getNeedsCaptchaHint(), false);
        eq("parser.sample.hint", sample.getNeedsCaptchaHint(), true);

        System.out.println("\n===== PASS=" + pass + " FAIL=" + fail + " =====");
        if (fail > 0) System.exit(1);
    }
}
