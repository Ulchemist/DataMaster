package com.datamaster.app.domain;

import java.util.List;
import java.util.Map;

/** Compatible with both the desktop context envelope and the simpler web request shape. */
public record AnalysisChatRequest(
        String providerId,
        String view,
        String message,
        Map<String, List<String>> filters,
        List<ChatMessage> history,
        ChatContext context,
        boolean allowSchemaReview
) {
    public AnalysisChatRequest {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
        history = history == null ? List.of() : List.copyOf(history);
    }

    /** Backward-compatible constructor used by hosted clients and existing integrations. */
    public AnalysisChatRequest(String providerId, String view, String message,
                               Map<String, List<String>> filters, List<ChatMessage> history,
                               ChatContext context) {
        this(providerId, view, message, filters, history, context, false);
    }

    public record ChatMessage(String role, String content) {
    }

    public record ChatContext(
            Map<String, List<String>> filters,
            String metric,
            String quantityMetric,
            String costMetric,
            String groupBy,
            String selectedCustomer,
            String selectedChannel,
            String selectedGroup,
            String focusDimension,
            String periodMode,
            List<String> years,
            List<String> months
    ) {
        public ChatContext {
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            years = years == null ? List.of() : List.copyOf(years);
            months = months == null ? List.of() : List.copyOf(months);
        }

        public ChatContext(Map<String, List<String>> filters, String metric,
                           String quantityMetric, String costMetric) {
            this(filters, metric, quantityMetric, costMetric, null, null, null, null, null,
                    null, List.of(), List.of());
        }

        /** Backwards-compatible constructor matching the pre-period-scope context. */
        public ChatContext(Map<String, List<String>> filters, String metric,
                           String quantityMetric, String costMetric, String groupBy,
                           String selectedCustomer, String selectedChannel, String selectedGroup,
                           String focusDimension) {
            this(filters, metric, quantityMetric, costMetric, groupBy, selectedCustomer,
                    selectedChannel, selectedGroup, focusDimension, null, List.of(), List.of());
        }
    }
}
