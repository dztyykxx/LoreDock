package io.github.loredock.agent.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 本地知识整理定义目录。目录由部署环境固定，模型和 HTTP 请求不能修改。
 *
 * @param skillsDirectory `FileSystemSkillRegistry` 扫描根目录
 * @param agentSpecsDirectory `AgentSpecLoader` 扫描根目录
 */
@Validated
@ConfigurationProperties("loredock.agent.curation")
public record KnowledgeAgentProperties(
        @NotBlank String skillsDirectory,
        @NotBlank String agentSpecsDirectory
) {
}
