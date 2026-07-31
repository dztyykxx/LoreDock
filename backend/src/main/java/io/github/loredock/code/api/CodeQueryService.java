package io.github.loredock.code.api;

/** 向其他业务模块提供活动代码快照、范围搜索与片段读取的统一契约。 */
public interface CodeQueryService {

    /**
     * @param projectIdentifier 已启用项目标识
     * @param branch 分支；为空时由 project 契约解析为 main
     * @return 当前活动快照状态，不暴露 generation 或物理目录
     */
    ActiveCodeState getActiveSnapshot(String projectIdentifier, String branch);

    /**
     * @param query 固定活动快照的有界代码查询
     * @return 当前范围内的代码命中；无命中时列表为空
     * @throws CodeSnapshotVersionChangedException 固定快照在检索前或检索期间失效
     */
    CodeMatches search(CodeQuery query);

    /**
     * @param query 固定活动快照的仓库相对路径与行范围
     * @return 有界代码片段
     * @throws CodeSnapshotVersionChangedException 固定快照在读取前或读取期间失效
     */
    CodeExcerpt read(CodeExcerptQuery query);
}
