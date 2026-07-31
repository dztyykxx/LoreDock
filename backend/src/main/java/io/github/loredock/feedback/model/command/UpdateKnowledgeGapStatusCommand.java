package io.github.loredock.feedback.model.command;

import io.github.loredock.feedback.model.enums.KnowledgeGapStatus;

/** @param actor 服务端认证的管理员账号 @param feedbackId 反馈标识 @param targetStatus 目标状态 */
public record UpdateKnowledgeGapStatusCommand(String actor, Long feedbackId, KnowledgeGapStatus targetStatus) {
}
