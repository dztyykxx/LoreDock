package io.github.loredock.qa.application;

/** 事务性创建或幂等复用一次独立 Web 项目问答。 */
public interface CreateWebQaQuestionUseCase {
    /**
     * @param command 已规范化创建命令
     * @return 已原子提交问答、用户消息、运行及首事件的快照
     */
    WebQaQuestionSnapshot create(CreateWebQaQuestionCommand command);
}
