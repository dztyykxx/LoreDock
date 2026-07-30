package io.github.loredock.agent.infrastructure.skill;

import io.github.loredock.agent.application.AgentSkillContentStore;
import io.github.loredock.storage.application.ObjectStorage;
import io.github.loredock.storage.domain.ObjectMetadata;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** 复用项目 ObjectStorage 保存 Skill 内容，不暴露磁盘路径或扫描工作目录。 */
@Component
public class ObjectStorageAgentSkillContentStore implements AgentSkillContentStore {

    private static final int MAX_SKILL_BYTES = 128 * 1024;
    private final ObjectStorage objectStorage;

    /** @param objectStorage 项目统一对象存储 */
    public ObjectStorageAgentSkillContentStore(ObjectStorage objectStorage) {
        this.objectStorage = objectStorage;
    }

    @Override
    public String put(String contentHash, byte[] content) {
        if (content.length == 0 || content.length > MAX_SKILL_BYTES) {
            throw new IllegalArgumentException("Skill 内容大小越界");
        }
        var stored = objectStorage.put(new ByteArrayInputStream(content),
                new ObjectMetadata("project_qa.skill", "application/vnd.loredock.agent-skill"));
        if (!contentHash.equals(stored.sha256())) {
            throw new IllegalStateException("Skill 对象校验值不一致");
        }
        return stored.objectKey();
    }

    @Override
    public byte[] get(String objectKey) {
        try (InputStream input = objectStorage.get(objectKey)) {
            byte[] value = input.readNBytes(MAX_SKILL_BYTES + 1);
            if (value.length > MAX_SKILL_BYTES) {
                throw new IllegalStateException("Skill 对象超出读取上限");
            }
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("Skill 对象读取失败", exception);
        }
    }
}
