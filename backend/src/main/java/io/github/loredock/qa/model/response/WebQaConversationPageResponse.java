package io.github.loredock.qa.model.response;

import java.util.List;

/** 最近会话游标分页响应。 */
public record WebQaConversationPageResponse(
        List<WebQaConversationSummaryResponse> items,
        String nextCursor
) {
    /** 复制当前页数据，避免 Controller 外部修改响应内容。 */
    public WebQaConversationPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
