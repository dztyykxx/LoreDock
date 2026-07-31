package io.github.loredock.qa.model.request;

import io.github.loredock.auth.api.AuthService;

/** 建连时已授权并固定的 SSE 消费身份、问答、运行和续读位置。 */
public record WebQaSseStreamRequest(
        String operatorId,
        String projectIdentifier,
        Long questionId,
        Long runId,
        long afterSequence,
        AuthService.SessionLease sessionLease
) {
}
