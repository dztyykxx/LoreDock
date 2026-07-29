package io.github.loredock.project.infrastructure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理员创建项目请求；POST 不承诺幂等，重复标识由服务端唯一约束返回 409。
 *
 * @param name 项目名称
 * @param identifier kebab-case 项目标识
 * @param description 简介，可为空字符串但字段必须存在
 * @param technologyStack 主要技术栈，可为空字符串但字段必须存在
 */
public record CreateProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(min = 2, max = 64)
        @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String identifier,
        @NotNull @Size(max = 1000) String description,
        @NotNull @Size(max = 255) String technologyStack
) {
}
