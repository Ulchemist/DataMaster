package com.datamaster.app.domain;

import java.util.List;

public record ProviderView(
        String id,
        String name,
        String baseUrl,
        String model,
        boolean configured,
        List<ModelOption> models,
        boolean customModelAllowed
) {
}
