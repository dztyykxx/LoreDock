package io.github.loredock.qa.application;

/** 仅在当前操作者仍可访问启用项目时查询其问答历史与详情。 */
public interface QueryWebQaQuestionUseCase {
    /** @return 有界历史页 */
    WebQaQuestionPage history(QueryWebQaHistoryCommand command);

    /** @return 单条问答详情，不存在或越权统一抛出问答不存在错误 */
    WebQaQuestionSnapshot detail(QueryWebQaDetailCommand command);
}
