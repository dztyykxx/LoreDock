package io.github.loredock.qa.model.response;

import java.util.List;

/** 有界历史页及不透明下一页游标。 */
public record WebQaQuestionPageResponse(List<WebQaQuestionResponse> items, String nextCursor) {
    public WebQaQuestionPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
