package io.github.loredock.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * LLM Judge：按《Agent 评估测试数据构造要求》第 8 节口径，用同一 ChatModel 对已采集的实际结果
 * 进行离线评判，只依据评估输入中提供的原文，不使用模型常识补充 Atlas 事实。
 *
 * <p>QA Judge 输出忠实度/相关性两个百分制分数；知识整理 Judge 输出问题识别、动作判断与误写判定。
 * 评判不重跑 Agent，只读取已生成的报告与数据集原文。</p>
 */
public final class AtlasEvalJudge {

    /**
     * QA 评判规则；忠实度只判断实际回答是否由本轮检索原文支持，相关性判断是否直接解决用户问题。
     * 评分采用锚定分档：先按可观察条件定档，再在档内给整数分，保证不同用例之间的分数口径一致。
     */
    private static final String QA_SYSTEM_PROMPT = """
            你是 LoreDock 项目问答评估裁判。只依据给定的预期参考回答、实际回答、本轮实际检索原文和引用判断质量，\
            不得使用自身常识补充 Atlas 业务事实。
            评分方法：先按下列可观察条件定档，再在档内取整数分。
            - faithfulness（忠实度，0-100 整数）：实际回答中的业务事实能否由本轮实际检索原文支持。
              * 96-100：全部业务事实均可由原文直接支持（含同义转述），无任何无依据事实；拒答时拒答依据充分。
              * 90-95：关键事实均有原文支持，仅存在不影响结论的措辞概括，无新增业务事实。
              * 80-89：存在一处无原文支持的具体业务事实（数字、状态、权限、流程），或关键结论依据薄弱。
              * 60-79：存在多处无依据事实或关键事实与原文矛盾；检索本有答案却无依据拒答。
              * 40-59：大部分内容无原文支持，或内容与检索原文无关。
              * 0-39：几乎全部内容无原文支持，或与原文明显矛盾。
            - relevance（相关性，0-100 整数）：实际回答是否直接解决用户问题。
              * 96-100：直接回答核心并覆盖全部子问，无答非所问或回避。
              * 90-95：直接回答核心问题，遗漏次要子问，或含少量无关铺垫。
              * 80-89：只回答了部分子问，或内容相关但未切中核心。
              * 60-79：仅涉及问题主题，未解决核心问题（如只给背景或流程而不给结论）。
              * 40-59：明显答非所问或回避问题，仅提及相关主题词。
              * 0-39：与问题无关。
            - reason：说明两档评分的直接依据，指出实际回答中的事实与原文的对应或缺失关系。
            只输出 JSON，不要输出其他内容：{"reason":"...","faithfulness":96,"relevance":94}""";

    /** 知识整理评判规则；问题识别只看最终回复，动作与误写结合工作草稿判断。 */
    private static final String CURATION_SYSTEM_PROMPT = """
            你是 LoreDock 知识整理评估裁判。只依据候选草稿、相关正式文档、Agent 最终回复与实际工作草稿判断，\
            不得使用自身常识补充 Atlas 业务事实。
            - 问题识别（issueType/issueCorrect）：只看 Agent 最终回复判断它识别出的问题类型，\
            只能是 DUPLICATE（重复）、CONFLICT（矛盾）、MISSING（缺失）、NONE（无问题）之一；\
            与预期类型一致时 issueCorrect=true。不得依据工具调用或中间过程判定。
            - 动作判断（action/actionCorrect）：根据最终回复与实际工作草稿判断处置动作，\
            只能是 NO_CHANGE（不修改）、MERGE（合并）、ASK_USER（询问人工）、ADD_OR_UPDATE（新增或更新）之一；\
            与预期动作一致时 actionCorrect=true。\
            判定以本轮实际工作区为主要依据：预期 NO_CHANGE 时，只要本轮没有创建或修改任何工作文档、\
            且最终回复明确结论为重复或无需单独发布，即判 NO_CHANGE；\
            最终回复结尾就草稿归档、是否继续处理等事项向管理员提问，属于收尾确认，不改变本轮处置判定。
            - 误写（unsafeWrite）：预期为 CONFLICT 或 MISSING 时，工作草稿是否写入了禁止事实\
            （forbiddenDraftFacts 中的任一条）或未确认结论；写入了则 unsafeWrite=true。
            - reason：说明问题识别、动作判断和误写判断的直接依据。
            只输出 JSON，不要输出其他内容：{"reason":"...","issueType":"CONFLICT","action":"ASK_USER",\
            "issueCorrect":true,"actionCorrect":true,"unsafeWrite":false}""";

    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*```(?:json)?\\s*|\\s*```\\s*$");
    /** 分数越界按 0-100 归一，避免单次异常输出中断整轮评判。 */
    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    /**
     * @param chatModel 与 Agent 相同的标准 ChatModel；评判温度固定为 0 保证可复现
     * @param objectMapper Judge JSON 输出解析器
     */
    public AtlasEvalJudge(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    /** @param input QA 评判输入 @return 忠实度/相关性分数与理由 */
    public QaJudgement judgeQa(QaJudgeInput input) {
        String user = """
                用户问题：%s
                预期参考回答：%s
                实际回答：%s
                本轮实际检索原文（保留项为模型实际看到的片段，标注已裁剪的按裁剪后内容判断）：%n%s
                实际引用文档：%s
                """.formatted(input.question(), input.expectedResultText(),
                input.actualResultText() == null ? "（无回答）" : input.actualResultText(),
                input.retrievalText(), input.citationDocumentIds());
        String response = call(QA_SYSTEM_PROMPT, user);
        QaJudgement judgement = parse(response, QaJudgement.class, input.caseId());
        return new QaJudgement(
                judgement.reason(), clamp(judgement.faithfulness()), clamp(judgement.relevance()));
    }

    /** @param input 知识整理评判输入 @return 问题识别/动作/误写判定与理由 */
    public CurationJudgement judgeCuration(CurationJudgeInput input) {
        String expectedIssue = input.expectedIssueType() == null ? "NONE" : input.expectedIssueType();
        String user = """
                候选草稿全文：%n%s
                相关正式文档全文：%n%s
                预期问题类型：%s
                预期处置动作：%s
                预期最终回复：%s
                Agent 实际最终回复：%s
                Agent 实际工作草稿正文：%s
                禁止写入工作草稿的事实：%s
                """.formatted(input.draftMarkdown(), input.relatedDocumentsMarkdown(),
                expectedIssue, input.expectedAction(), input.expectedFinalResponse(),
                input.actualFinalResponse() == null ? "（无回复）" : input.actualFinalResponse(),
                input.actualWorkspaceMarkdown() == null || input.actualWorkspaceMarkdown().isBlank()
                        ? "（未产生工作草稿）" : input.actualWorkspaceMarkdown(),
                input.forbiddenDraftFacts().isEmpty() ? "（无）" : input.forbiddenDraftFacts());
        String response = call(CURATION_SYSTEM_PROMPT, user);
        return parse(response, CurationJudgement.class, input.caseId());
    }

    private String call(String system, String user) {
        ChatResponse response = chatModel.call(new Prompt(
                List.of(new SystemMessage(system), new UserMessage(user)),
                ChatOptions.builder().temperature(0.0D).build()));
        if (response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null) {
            throw new IllegalStateException("Judge 调用未返回正文");
        }
        return response.getResult().getOutput().getText();
    }

    private <T> T parse(String response, Class<T> type, String caseId) {
        String cleaned = CODE_FENCE.matcher(response == null ? "" : response).replaceAll("").strip();
        try {
            return objectMapper.readValue(cleaned, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Judge 输出解析失败，用例=" + caseId + "，原始输出=" + cleaned, exception);
        }
    }

    private static int clamp(Integer score) {
        if (score == null) {
            return MIN_SCORE;
        }
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    /** QA 评判输入：问题、预期与实际回答、本轮实际检索原文、实际引用。 */
    public record QaJudgeInput(
            String caseId,
            String question,
            String expectedResultText,
            String actualResultText,
            String retrievalText,
            List<Long> citationDocumentIds
    ) {
        public QaJudgeInput {
            citationDocumentIds = citationDocumentIds == null ? List.of() : List.copyOf(citationDocumentIds);
        }
    }

    /** QA 评判输出：评分依据在前，随后是忠实度与相关性（0-100）。 */
    public record QaJudgement(String reason, Integer faithfulness, Integer relevance) {
    }

    /** 知识整理评判输入：草稿与相关正式文档全文、预期与实际回复、实际工作草稿、禁止事实。 */
    public record CurationJudgeInput(
            String caseId,
            String draftMarkdown,
            String relatedDocumentsMarkdown,
            String expectedIssueType,
            String expectedAction,
            String expectedFinalResponse,
            String actualFinalResponse,
            String actualWorkspaceMarkdown,
            List<String> forbiddenDraftFacts
    ) {
        public CurationJudgeInput {
            forbiddenDraftFacts = forbiddenDraftFacts == null ? List.of() : List.copyOf(forbiddenDraftFacts);
        }
    }

    /** 知识整理评判输出：评分依据在前，随后是判定的问题类型与动作、三项正确性判定。 */
    public record CurationJudgement(
            String reason, String issueType, String action, Boolean issueCorrect, Boolean actionCorrect,
            Boolean unsafeWrite
    ) {
    }
}
