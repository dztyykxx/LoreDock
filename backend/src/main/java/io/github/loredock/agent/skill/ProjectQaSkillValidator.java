package io.github.loredock.agent.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 对内置 Skill 和输出 schema 执行确定性结构校验，防止不完整的提示被标记为可用版本。
 */
@Component
public class ProjectQaSkillValidator {

    private static final Pattern FIELD = Pattern.compile("(?m)^%s:\\s*([^\\r\\n]+)$");
    private static final List<String> REQUIRED_TOOLS =
            List.of("knowledge_search", "code_search", "code_snippet_read");
    private final ObjectMapper objectMapper;

    /** @param objectMapper JSON 解析器 */
    public ProjectQaSkillValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return 从 front matter 解析的受控 Skill 定义
     * @throws IllegalArgumentException 缺少必要规则或 schema 不完整时拒绝发布
     */
    public ProjectQaSkillDefinition validate(String markdown, String outputSchema) {
        require(markdown != null && markdown.startsWith("---\n"), "Skill 缺少 front matter");
        String name = field(markdown, "name");
        String schemaVersion = field(markdown, "output_schema_version");
        int maxSteps = integer(field(markdown, "max_steps"), "max_steps");
        require("project_qa".equals(name), "Skill 名称必须为 project_qa");
        require(maxSteps >= 1 && maxSteps <= 20, "Skill 步骤上限越界");
        REQUIRED_TOOLS.forEach(tool -> require(markdown.contains("- " + tool), "Skill 缺少只读工具 " + tool));
        for (String section : List.of("适用场景", "必要输入", "推荐步骤", "答案与引用规则", "拒答与冲突", "公开模拟验收示例")) {
            require(markdown.contains("## " + section), "Skill 缺少章节 " + section);
        }
        for (String rule : List.of("BUSINESS_RULE", "CURRENT_IMPLEMENTATION", "MIXED", "INSUFFICIENT_EVIDENCE",
                "当前知识库没有足够依据", "不得创建、修改、归档、索引或发布正式知识")) {
            require(markdown.contains(rule), "Skill 缺少关键规则 " + rule);
        }
        validateSchema(outputSchema, schemaVersion);
        return new ProjectQaSkillDefinition(name, schemaVersion, maxSteps, markdown, outputSchema);
    }

    private void validateSchema(String schema, String version) {
        try {
            JsonNode root = objectMapper.readTree(schema);
            require(version.equals(root.path("$id").asText()), "Skill 与输出 schema 版本不一致");
            require(!root.path("additionalProperties").asBoolean(true), "输出 schema 必须拒绝未知字段");
            JsonNode required = root.path("required");
            for (String field : List.of("resultType", "answerBasis", "text", "citations", "refusalReason", "sourceConflict")) {
                require(contains(required, field), "输出 schema 缺少必填字段 " + field);
                require(root.path("properties").has(field), "输出 schema 缺少字段 " + field);
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("输出 schema 不是合法 JSON", exception);
        }
    }

    private boolean contains(JsonNode array, String expected) {
        if (!array.isArray()) {
            return false;
        }
        for (JsonNode value : array) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private String field(String markdown, String name) {
        Matcher matcher = Pattern.compile(FIELD.pattern().formatted(Pattern.quote(name))).matcher(markdown);
        require(matcher.find(), "Skill 缺少字段 " + name);
        return matcher.group(1).trim();
    }

    private int integer(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Skill 字段不是整数 " + name, exception);
        }
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
