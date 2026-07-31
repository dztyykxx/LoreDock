package io.github.loredock.qa.api;

import java.util.List;

/** @param items 当前页问答 @param nextCursor 下一页游标；没有更多时为空 */
public record QaQuestionPage(List<QaQuestion> items, String nextCursor) {
    public QaQuestionPage {
        items = List.copyOf(items);
    }
}
