package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.identity.application.WebSessionContinuityPort;

import java.util.UUID;

/** 建连时已授权并固定的 SSE 消费身份、问答、运行和续读位置。 */
record WebQaSseStreamRequest(
        String operatorId,
        String projectIdentifier,
        UUID questionId,
        UUID runId,
        long afterSequence,
        WebSessionContinuityPort.Lease sessionLease
) {
}
