package com.edevlet.lineage.infrastructure.tracing;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

public class MdcTaskDecorator implements TaskDecorator {

    public static void setContext(String traceId, String transactionId, String userId) {
        if (traceId != null) {
            MDC.put(TracingMdcFilter.MDC_TRACE_ID, traceId);
        }
        if (transactionId != null) {
            MDC.put(TracingMdcFilter.MDC_TRANSACTION_ID, transactionId);
        }
        if (userId != null) {
            MDC.put(TracingMdcFilter.MDC_USER_ID, userId);
        }
    }

    public static void clear() {
        MDC.clear();
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    }
}
