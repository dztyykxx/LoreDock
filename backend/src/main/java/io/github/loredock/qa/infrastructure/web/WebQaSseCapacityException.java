package io.github.loredock.qa.infrastructure.web;

import io.github.loredock.platform.web.ApplicationException;
import io.github.loredock.platform.web.ErrorCode;

/** SSE 专用执行器容量已满；不退化占用 Web、Agent 或通用任务线程。 */
final class WebQaSseCapacityException extends ApplicationException {
    WebQaSseCapacityException() {
        super(ErrorCode.QA_SSE_CAPACITY_EXCEEDED, "web QA SSE executor capacity exceeded");
    }
}
