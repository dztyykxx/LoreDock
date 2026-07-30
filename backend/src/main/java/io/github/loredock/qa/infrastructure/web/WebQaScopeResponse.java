package io.github.loredock.qa.infrastructure.web;

/** 固定到问答运行时的公开项目范围，不包含数据库 ID、对象键或内部 generation。 */
public record WebQaScopeResponse(
        String projectIdentifier,
        String branch,
        String commit,
        boolean codeSnapshotAvailable
) {
}
