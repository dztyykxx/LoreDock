package io.github.loredock.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.loredock.agent.model.context.ContextBudget;
import io.github.loredock.agent.model.context.WorkflowContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 六条压缩评估样例及其契约；共用的真实措辞干扰轮次展开后随报告保存。 */
final class ContextCompressionEvalFixture {
    private ContextCompressionEvalFixture() { }

    /** 数据版本、测试预算、共享干扰轮次和样例；不包含任何模型凭据。 */
    record Dataset(String version, ContextBudget budget, List<History> sharedDiscussion, List<Case> cases) { }

    /** 消息 ID 为 Judge 溯源服务，不拼入被测模型消息。 */
    record History(String id, String role, String text) { }

    /** 一项人工定义的业务要求；sourceIds 可指向消息、originalGoal 或 workflowState。 */
    record Check(String id, String requirement, List<String> sourceIds) { }

    /** protectionSource 仅用于报告解释；不得向被测模型暴露用例分类和判定条件。 */
    record Case(String id, String protectionSource, String originalGoal, List<History> history,
                String currentInstruction, WorkflowContext workflowState, List<Check> checks) { }

    /** 读取仓库内唯一数据源；共享讨论展开为每条样例的独立完整历史。 */
    static Dataset load(ObjectMapper mapper) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("docs/quality/context-compression-eval-cases.json"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("找不到 context-compression-eval-cases.json，请从仓库运行");
        }
        Dataset raw = mapper.readValue(root.resolve("docs/quality/context-compression-eval-cases.json").toFile(), Dataset.class);
        List<Case> cases = raw.cases().stream().map(item -> {
            List<History> history = new ArrayList<>(item.history());
            history.addAll(raw.sharedDiscussion());
            return new Case(item.id(), item.protectionSource(), item.originalGoal(), List.copyOf(history),
                    item.currentInstruction(), item.workflowState(), item.checks());
        }).toList();
        Dataset expanded = new Dataset(raw.version(), raw.budget(), List.of(), cases);
        validate(expanded);
        return expanded;
    }

    /** 防止半轮、无原文判分以及重复 ID 使评估失真；不对答案语义做硬编码。 */
    static void validate(Dataset data) {
        data.budget().validateInvariants();
        if (data.version() == null || data.cases().size() != 6) {
            throw new IllegalArgumentException("评估需要版本号和六条固定样例");
        }
        Set<String> caseIds = new HashSet<>();
        for (Case item : data.cases()) {
            if (!caseIds.add(item.id()) || item.history().size() % 2 != 0 || item.history().size() < 16
                    || item.originalGoal().isBlank() || item.currentInstruction().isBlank() || item.workflowState() == null) {
                throw new IllegalArgumentException("样例基本字段或完整轮次不合法：" + item.id());
            }
            Set<String> sources = new HashSet<>(Set.of("originalGoal", "workflowState"));
            for (int i = 0; i < item.history().size(); i++) {
                History message = item.history().get(i);
                if (!sources.add(message.id()) || message.text().isBlank()
                        || !message.role().equals(i % 2 == 0 ? "USER" : "ASSISTANT")) {
                    throw new IllegalArgumentException("消息 ID 或角色配对不合法：" + item.id());
                }
            }
            Set<String> checkIds = new HashSet<>();
            if (item.checks().isEmpty()) {
                throw new IllegalArgumentException("样例没有判定条件：" + item.id());
            }
            for (Check check : item.checks()) {
                if (!checkIds.add(check.id()) || check.requirement().isBlank() || check.sourceIds().isEmpty()
                        || !sources.containsAll(check.sourceIds())) {
                    throw new IllegalArgumentException("判定条件缺少有效原文：" + item.id());
                }
            }
        }
    }
}
