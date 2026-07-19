package com.datamaster.app.domain;

import java.util.List;

/**
 * Private desktop-to-site synchronization payload.
 *
 * <p>The API keys in this record are intentionally available only to the
 * bearer-token synchronization endpoint. They must never be returned by the
 * ordinary provider settings endpoints.</p>
 */
public record ProviderSyncPayload(
        String selectedProvider,
        List<ProviderSyncItem> providers
) {
    public record ProviderSyncItem(
            String id,
            String baseUrl,
            String model,
            String apiKey,
            boolean configured
    ) {
    }
}
