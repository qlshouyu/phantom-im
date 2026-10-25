package im.phantom.infra.observability;

import org.slf4j.MDC;

import java.util.UUID;

/** 日志与异步任务的 trace/requestId 关联工具。 */
public final class TraceContext {

    public static final String REQUEST_ID = "requestId";
    public static final String TRACE_ID = "traceId";

    private TraceContext() {}

    public static String start(String requestId, String traceId) {
        String rid = requestId == null || requestId.isBlank() ? "req_" + random() : requestId;
        String tid = traceId == null || traceId.isBlank() ? random() : traceId;
        MDC.put(REQUEST_ID, rid);
        MDC.put(TRACE_ID, tid);
        return rid;
    }

    public static void clear() {
        MDC.remove(REQUEST_ID);
        MDC.remove(TRACE_ID);
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
