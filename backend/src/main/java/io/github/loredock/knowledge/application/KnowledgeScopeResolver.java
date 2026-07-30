package io.github.loredock.knowledge.application;

import io.github.loredock.knowledge.domain.KnowledgeScope;
import io.github.loredock.knowledge.domain.KnowledgeScopeType;

/**
 * 知识范围解析端口，将入口使用的项目标识和分支名转换为主数据 UUID。
 */
public interface KnowledgeScopeResolver {

    /**
     * 解析普通浏览上下文；项目入口仅接受已启用项目，空分支由项目能力解析为默认 main。
     *
     * @param type 入口类型
     * @param projectIdentifier 项目标识，仅项目入口使用
     * @param branchName 可选分支名
     * @return 已解析浏览上下文
     */
    KnowledgeBrowseContext resolveBrowse(
            KnowledgeBrowseContextType type,
            String projectIdentifier,
            String branchName
    );

    /**
     * 解析管理写入范围；允许停用项目，但分支必须真实属于该项目。
     *
     * @param type 范围类型
     * @param projectIdentifier 项目标识
     * @param branchName 分支名
     * @return 已解析领域范围
     * @throws KnowledgeScopeInvalidException 范围字段残留、缺失或主数据不匹配
     */
    KnowledgeScope resolveAdmin(KnowledgeScopeType type, String projectIdentifier, String branchName);
}
