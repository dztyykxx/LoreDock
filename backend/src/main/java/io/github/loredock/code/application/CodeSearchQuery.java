package io.github.loredock.code.application;

/**
 * 活动代码搜索输入。调用方不能指定快照、generation、对象键或服务器目录。
 *
 * @param projectIdentifier 已启用项目标识
 * @param branch 分支名；空值由服务端解析为项目默认 main
 * @param query 去除首尾空白后 1～200 字符的纯关键词
 * @param target 查询字段，空值默认 ALL
 * @param pathPrefix 可选规范化仓库相对路径前缀
 * @param limit 结果上限，空值默认 10，最大 50
 */
public record CodeSearchQuery(
        String projectIdentifier,
        String branch,
        String query,
        CodeSearchTarget target,
        String pathPrefix,
        Integer limit
) {
}
