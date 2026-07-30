package io.github.loredock.qa.infrastructure.web;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Spring MVC `SseEmitter` 适配器；业务事件 ID 来自数据库序号，心跳只发送无 ID 注释。 */
final class SseEmitterWebQaSink implements WebQaSseSink {
    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean();

    SseEmitterWebQaSink(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
    }

    @Override
    public void send(WebQaSsePublicEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.data().sequence()))
                .name(event.name())
                .data(event.data(), MediaType.APPLICATION_JSON));
    }

    @Override
    public void heartbeat() throws IOException {
        emitter.send(SseEmitter.event().comment("heartbeat"));
    }

    @Override
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            emitter.complete();
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }
}
