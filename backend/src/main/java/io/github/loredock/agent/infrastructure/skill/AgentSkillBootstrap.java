package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillCatalog;
import io.github.loredock.agent.infrastructure.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动后引导内置 Skill。失败只会关闭 Agent 可用性，不影响文档、搜索和 readiness。
 */
@Component
@Slf4j
public class AgentSkillBootstrap implements ApplicationRunner {

    private final AgentProperties properties;
    private final BuiltinProjectQaSkillPublisher publisher;
    private final AgentSkillCatalog catalog;
    private volatile boolean available;

    /** @param properties Agent 配置 @param publisher 内置 Skill 发布器 @param catalog Skill 目录 */
    public AgentSkillBootstrap(
            AgentProperties properties,
            BuiltinProjectQaSkillPublisher publisher,
            AgentSkillCatalog catalog
    ) {
        this.properties = properties;
        this.publisher = publisher;
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            available = false;
            log.info("agent_skill_bootstrap skipped reason=agent_disabled");
            return;
        }
        try {
            var metadata = publisher.publishBuiltin();
            available = catalog.findEnabled("project_qa").isPresent();
            log.info("agent_skill_bootstrap completed skillName={} skillVersion={} available={}",
                    metadata.name(), metadata.version(), available);
        } catch (RuntimeException exception) {
            available = false;
            // 不记录对象键、路径或底层存储异常原文，避免引导失败泄漏部署信息。
            log.warn("agent_skill_bootstrap failed skillName=project_qa available=false");
        }
    }

    /** @return 本实例最近一次引导后 Skill 是否可用 */
    public boolean isAvailable() {
        return available;
    }
}
