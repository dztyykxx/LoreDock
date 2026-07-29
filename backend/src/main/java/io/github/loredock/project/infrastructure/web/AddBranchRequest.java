package io.github.loredock.project.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员添加分支请求；危险路径样式由领域值对象统一拒绝。
 *
 * @param name 保留大小写的 Git 风格分支名
 */
public record AddBranchRequest(@NotBlank @Size(max = 128) String name) {
}
