package io.github.loredock.knowledgegap.application;

import io.github.loredock.knowledgegap.domain.KnowledgeGapStatus;

import java.util.UUID;

/** @param actor 服务端认证的管理员账号 @param feedbackId 反馈标识 @param targetStatus 目标状态 */
public record UpdateKnowledgeGapStatusCommand(String actor, UUID feedbackId, KnowledgeGapStatus targetStatus) {
}
