package io.github.loredock.qa.infrastructure.web;

import java.io.IOException;

/** 隔离轮询规则与 Spring MVC 发射器，心跳接口刻意不接受业务序号。 */
interface WebQaSseSink {
    /** 发送带持久化序号 ID 的业务事件。 */
    void send(WebQaSsePublicEvent event) throws IOException;

    /** 发送不带 ID 的注释心跳。 */
    void heartbeat() throws IOException;

    /** 正常结束连接，不改变 Agent 运行终态。 */
    void complete();

    /** @return 客户端断开、超时或已完成时为 true */
    boolean isClosed();
}
