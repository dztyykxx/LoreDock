package io.github.loredock.qa.application;

/** 为长连接提供不加载消息正文的轻量范围复核和最新运行快照。 */
public interface WebQaStreamAccessUseCase {
    /**
     * @param command 当前操作者、URL 项目与问答 ID
     * @return 固定问答身份和最新运行事实
     * @throws WebQaQuestionNotFoundException 不存在、越权、项目不匹配或项目停用
     */
    WebQaStreamTarget authorize(QueryWebQaDetailCommand command);
}
