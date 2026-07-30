package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillCatalog;
import io.github.loredock.agent.application.AgentSkillContentStore;
import io.github.loredock.agent.application.AgentSkillSnapshot;
import io.github.loredock.agent.application.AgentSkillVersionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** 以数据库 ENABLED 元数据为事实来源，并在每次固定 Skill 前校验 ObjectStorage 内容哈希。 */
@Component
@Slf4j
public class DatabaseAgentSkillCatalog implements AgentSkillCatalog {

    private final AgentSkillVersionRepository versions;
    private final AgentSkillContentStore contentStore;
    private final AgentSkillBundleCodec codec;
    private final ProjectQaSkillValidator validator;

    /**
     * @param versions Skill 元数据仓储
     * @param contentStore Skill 内容存储
     * @param codec 内容包解码器
     * @param validator Skill 结构校验器
     */
    public DatabaseAgentSkillCatalog(
            AgentSkillVersionRepository versions,
            AgentSkillContentStore contentStore,
            AgentSkillBundleCodec codec,
            ProjectQaSkillValidator validator
    ) {
        this.versions = versions;
        this.contentStore = contentStore;
        this.codec = codec;
        this.validator = validator;
    }

    @Override
    public Optional<AgentSkillSnapshot> findEnabled(String name) {
        return versions.findEnabled(name).flatMap(metadata -> {
            try {
                byte[] bytes = contentStore.get(metadata.objectKey());
                if (!metadata.contentHash().equals(sha256(bytes))) {
                    throw new IllegalStateException("Skill 内容哈希不一致");
                }
                AgentSkillBundleCodec.Content content = codec.decode(bytes);
                ProjectQaSkillDefinition checked = validator.validate(content.markdown(), content.outputSchema());
                if (!metadata.name().equals(checked.name())
                        || !metadata.version().equals(checked.version())
                        || !metadata.outputSchemaVersion().equals(checked.outputSchemaVersion())) {
                    throw new IllegalStateException("Skill 元数据与内容不一致");
                }
                return Optional.of(new AgentSkillSnapshot(metadata.id(), metadata.name(), metadata.version(),
                        metadata.contentHash(), metadata.objectKey(), metadata.outputSchemaVersion(),
                        content.markdown(), content.outputSchema()));
            } catch (RuntimeException exception) {
                log.warn("agent_skill unavailable skillName={} skillVersion={} reason=content_validation_failed",
                        metadata.name(), metadata.version());
                return Optional.empty();
            }
        });
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }
}
