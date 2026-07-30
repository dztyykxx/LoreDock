package io.github.loredock.qa.infrastructure.web;

import java.time.Instant;

/** 单轮轮询后的续读位置、最后发送时间、关闭标记和业务发送数。 */
record WebQaSsePollResult(long cursor, Instant lastEmissionAt, boolean closed, int sentCount) {
}
