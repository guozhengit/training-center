package com.guoyongzheng.exam.ai.streaming;

import java.util.function.LongFunction;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseSessionService {
    public SseSessionService(long timeoutMillis) {}

    public SseSessionService(
            long timeoutMillis, LongFunction<? extends SseEmitter> emitterFactory) {}

    public SseEmitter open(String sessionId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean send(String sessionId, Object data) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean complete(String sessionId) {
        throw new UnsupportedOperationException("TODO");
    }

    public boolean error(String sessionId, Throwable error) {
        throw new UnsupportedOperationException("TODO");
    }
}
