package io.github.loredock.project.model.result;

import io.github.loredock.project.model.enums.ProjectStatus;
import java.time.Instant;

/**
 * 项目仓储端口传递的持久化无关状态，避免应用层依赖 MyBatis 实体。
 *
 * @param id 项目 Long
 * @param identifier 全局唯一业务标识
 * @param name 项目名称
 * @param description 简介
 * @param technologyStack 主要技术栈
 * @param status 项目状态
 * @param createdAt UTC 创建时间
 * @param updatedAt UTC 更新时间
 * @param createdBy 创建操作者
 * @param updatedBy 更新操作者
 */
public record ProjectData(
        Long id,
        String identifier,
        String name,
        String description,
        String technologyStack,
        ProjectStatus status,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
