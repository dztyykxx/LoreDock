package io.github.loredock.qa.model.snapshot;

/** 稳定 SSE 事件名及 v1 数据体。 */
public record WebQaSsePublicEvent(String name, WebQaSseEventV1 data) {
}
