package io.github.loredock.qa.application;

import java.util.List;

/** 有界问答历史页及可空下一页不透明游标。 */
public record WebQaQuestionPage(List<WebQaQuestionSnapshot> items, String nextCursor) {
    public WebQaQuestionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
