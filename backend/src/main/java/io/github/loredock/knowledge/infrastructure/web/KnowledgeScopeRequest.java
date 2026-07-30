package io.github.loredock.knowledge.infrastructure.web;

import io.github.loredock.knowledge.domain.KnowledgeScopeType;
import jakarta.validation.constraints.NotNull;

/**
 * 写入请求中的业务范围；项目 identifier 和分支 name 必须先经项目主数据解析，不能直接持久化。
 *
 * @param type 范围层级
 * @param project 项目业务标识
 * @param branch 分支名
 */
public record KnowledgeScopeRequest(@NotNull KnowledgeScopeType type, String project, String branch) {
}
