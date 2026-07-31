package io.github.loredock.qa.model.command;


/** @param operatorId 当前操作者 @param projectIdentifier URL 项目标识 @param questionId 问答 ID */
public record QueryWebQaDetailCommand(String operatorId, String projectIdentifier, Long questionId) {
}
