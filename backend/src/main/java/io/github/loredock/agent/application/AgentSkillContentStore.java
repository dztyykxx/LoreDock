package io.github.loredock.agent.application;

/** Skill Markdown 与输出 schema 的不透明对象存储边界。 */
public interface AgentSkillContentStore {

    /** @return 按内容哈希幂等发布后的不透明对象键 */
    String put(String contentHash, byte[] content);

    /** @return 对象原始字节；对象不可用时抛出稳定 Skill 不可用语义 */
    byte[] get(String objectKey);
}
