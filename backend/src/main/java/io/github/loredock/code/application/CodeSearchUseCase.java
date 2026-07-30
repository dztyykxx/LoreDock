package io.github.loredock.code.application;

/** Web、后续 MCP 与内部 Agent 共用的活动代码搜索端口。 */
public interface CodeSearchUseCase {

    /**
     * 在服务端解析出的唯一活动 snapshot/generation 内搜索；query 长度 1～200，limit 默认 10、最大 50。
     * 一次调用固定同一 commit，空结果不扩大范围，索引不可用时明确失败且不回退。
     *
     * @param query 业务范围与纯关键词输入，不允许原始 Lucene 查询语法
     * @return 稳定按 score 降序、path 升序排列的有限结果
     */
    CodeSearchResponse search(CodeSearchQuery query);
}
