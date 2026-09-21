import com.wjx.autofill.wjx.*;
import java.util.*;

/** 用 T3 实测请求的同一组输入，打印 Kotlin 引擎构造出的 URL 与 body（用于与 Node 实测逐字段对照）。 */
public class ParityCheck {
    public static void main(String[] args) throws Exception {
        List<SurveyQuestion> qs = new ArrayList<>();
        qs.add(new SurveyQuestion(1, "姓名（例：XXX）", QuestionType.TEXT, new ArrayList<Option>()));
        qs.add(new SurveyQuestion(2, "学号(例：XXXXXXXXXXX）", QuestionType.TEXT, new ArrayList<Option>()));
        qs.add(new SurveyQuestion(3, "班级（例：生物XX）", QuestionType.TEXT, new ArrayList<Option>()));
        SurveyModel m = new SurveyModel(
            "https://www.wjx.cn/vm/Q0DQewW.aspx", "Q0DQewW", "测试", qs,
            "https://www.wjx.cn/joinnew/processjq.ashx?shortid=Q0DQewW",
            "2bc94024-a360-4f80-9d7b-54130a7cbb63", 4, "2026/9/22 1:10:54", 2,
            new HashMap<String, String>(), true, null);
        List<kotlin.Pair<Integer, String>> pairs = new ArrayList<>();
        pairs.add(new kotlin.Pair<>(1, WjxSubmitCodec.INSTANCE.escape("接口测试")));
        pairs.add(new kotlin.Pair<>(2, WjxSubmitCodec.INSTANCE.escape("20260000001")));
        pairs.add(new kotlin.Pair<>(3, WjxSubmitCodec.INSTANCE.escape("测试班级")));
        String url = WjxSubmitRequest.INSTANCE.buildSubmitUrl(m);
        String body = WjxSubmitRequest.INSTANCE.buildSubmitBody(m, pairs, null);
        // 去掉不可比的 t=<ms>，保留其余参数便于逐字段 diff
        System.out.println("ENGINE_URL=" + url.replaceAll("&t=\\d+", "&t=<ms>"));
        System.out.println("ENGINE_BODY=" + body);
        System.out.println("ENGINE_JQSIGN_RAW=" + WjxSubmitCodec.INSTANCE.jqSign(m.getJqnonce(), m.getKtimes()));
    }
}
