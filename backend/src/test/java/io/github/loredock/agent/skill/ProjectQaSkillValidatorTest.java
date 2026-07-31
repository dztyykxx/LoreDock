package io.github.loredock.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectQaSkillValidatorTest {

    private ProjectQaSkillValidator validator;
    private String markdown;
    private String schema;

    @BeforeEach
    void loadBuiltinSkill() throws IOException {
        validator = new ProjectQaSkillValidator(new ObjectMapper());
        markdown = resource("agent-skills/project_qa/SKILL.md");
        schema = resource("agent-skills/project_qa/output-schema.json");
    }

    /**
     * 业务目的：内置 Skill 必须完整声明任务、三个只读工具、证据规则、拒答与公开模拟验收示例。
     */
    @Test
    void builtinProjectQaSkillContainsCompleteControlledContract() {
        ProjectQaSkillDefinition definition = validator.validate(markdown, schema);

        assertThat(definition.name()).isEqualTo("project_qa");
        assertThat(definition.outputSchemaVersion()).isEqualTo("project-qa-v1");
        assertThat(definition.maxSteps()).isEqualTo(8);
        assertThat(definition.markdown()).contains(
                "knowledge_search", "code_search", "code_snippet_read",
                "BUSINESS_RULE", "CURRENT_IMPLEMENTATION", "MIXED",
                "当前知识库没有足够依据", "无活动代码快照", "不得重复", "公开模拟验收示例");
        System.out.printf("测试证据：场景=Agent定义结构校验，名称=%s，输出结构=%s，最大步骤=%d，只读工具数=3%n",
                definition.name(), definition.outputSchemaVersion(), definition.maxSteps());
    }

    /**
     * 业务目的：如果 Skill 遗漏任一只读工具或禁止发布规则，引导必须失败而不能降低执行边界。
     */
    @Test
    void missingToolOrNoPublishRuleMakesSkillInvalid() {
        assertThatThrownBy(() -> validator.validate(markdown.replace("  - code_snippet_read\n", ""), schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code_snippet_read");
        assertThatThrownBy(() -> validator.validate(
                markdown.replace("不得创建、修改、归档、索引或发布正式知识", "可以直接发布"), schema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("关键规则");
        System.out.println("测试证据：场景=Skill边界缺失，缺少工具和禁止发布规则均被拒绝");
    }

    /**
     * 业务目的：输出 schema 必须要求引用、拒答和冲突字段，防止模型返回无法做服务端校验的自由文本。
     */
    @Test
    void outputSchemaMissingCitationContractIsRejected() {
        String invalid = schema.replace(", \"citations\"", "");

        assertThatThrownBy(() -> validator.validate(markdown, invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("citations");
        System.out.println("测试证据：场景=输出schema缺少引用必填约束，发布结果=拒绝");
    }

    private String resource(String path) throws IOException {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("resource missing: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
