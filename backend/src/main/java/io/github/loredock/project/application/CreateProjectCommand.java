package io.github.loredock.project.application;

/**
 * 创建项目的领域输入；值对象校验在应用服务写入前执行。
 *
 * @param name 项目名称，规范化后长度为 1 至 100
 * @param identifier 全局唯一 kebab-case 标识，长度为 2 至 64
 * @param description 简介，最大 1000 字符
 * @param technologyStack 主要技术栈，最大 255 字符
 */
public record CreateProjectCommand(
        String name,
        String identifier,
        String description,
        String technologyStack
) {
}
