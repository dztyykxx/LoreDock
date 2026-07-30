package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillContentStore;
import io.github.loredock.agent.application.AgentSkillVersionMetadata;
import io.github.loredock.agent.application.AgentSkillVersionRepository;
import io.github.loredock.platform.time.TimeProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 从只读 classpath 加载内置 Skill，先校验内容，再按内容哈希幂等登记 ObjectStorage 和数据库。
 */
@Component
public class BuiltinProjectQaSkillPublisher {

    private static final String MARKDOWN_RESOURCE = "agent-skills/project_qa/SKILL.md";
    private static final String SCHEMA_RESOURCE = "agent-skills/project_qa/output-schema.json";
    private final ProjectQaSkillValidator validator;
    private final AgentSkillBundleCodec codec;
    private final AgentSkillContentStore contentStore;
    private final AgentSkillVersionRepository versions;
    private final TimeProvider timeProvider;

    /**
     * @param validator Skill 结构校验器
     * @param codec 稳定内容包编码器
     * @param contentStore ObjectStorage 内容端口
     * @param versions Skill 元数据仓储
     * @param timeProvider UTC 时间源
     */
    public BuiltinProjectQaSkillPublisher(
            ProjectQaSkillValidator validator,
            AgentSkillBundleCodec codec,
            AgentSkillContentStore contentStore,
            AgentSkillVersionRepository versions,
            TimeProvider timeProvider
    ) {
        this.validator = validator;
        this.codec = codec;
        this.contentStore = contentStore;
        this.versions = versions;
        this.timeProvider = timeProvider;
    }

    /** @return classpath 内置版本的发布元数据 */
    public AgentSkillVersionMetadata publishBuiltin() {
        return publish(validator.validate(read(MARKDOWN_RESOURCE), read(SCHEMA_RESOURCE)));
    }

    /**
     * 按内容哈希幂等发布一个已校验定义。同名同版本不同内容必须显式失败。
     *
     * @return 新建或已有的版本元数据
     */
    public synchronized AgentSkillVersionMetadata publish(ProjectQaSkillDefinition definition) {
        ProjectQaSkillDefinition checked = validator.validate(definition.markdown(), definition.outputSchema());
        if (!checked.name().equals(definition.name()) || !checked.version().equals(definition.version())) {
            throw new IllegalArgumentException("Skill 声明与内容不一致");
        }
        byte[] bundle = codec.encode(checked.markdown(), checked.outputSchema());
        String hash = sha256(bundle);
        var existing = versions.findByNameAndVersion(checked.name(), checked.version());
        if (existing.isPresent()) {
            if (!hash.equals(existing.get().contentHash())) {
                throw new IllegalStateException("Skill 同版本内容冲突");
            }
            return existing.get();
        }
        String objectKey = contentStore.put(hash, bundle);
        AgentSkillVersionMetadata metadata = new AgentSkillVersionMetadata(
                UUID.randomUUID(), checked.name(), checked.version(), hash, objectKey,
                checked.outputSchemaVersion(), "ENABLED", timeProvider.now());
        try {
            versions.publish(metadata);
            return metadata;
        } catch (DataIntegrityViolationException exception) {
            // 多实例同时引导时，以已提交的数据库版本为事实；内容不同仍拒绝。
            AgentSkillVersionMetadata raced = versions.findByNameAndVersion(checked.name(), checked.version())
                    .orElseThrow(() -> exception);
            if (!hash.equals(raced.contentHash())) {
                throw new IllegalStateException("Skill 同版本内容冲突", exception);
            }
            return raced;
        }
    }

    private String read(String path) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("内置 Skill 资源读取失败", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
        }
    }
}
