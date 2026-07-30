package io.github.loredock.knowledgegap.application;

/** 成员和管理员共用的知识缺口创建能力。 */
public interface CreateKnowledgeGapUseCase {
    /** @return 新建或幂等复用的反馈快照 */
    KnowledgeGapFeedbackSnapshot create(CreateKnowledgeGapCommand command);
}
