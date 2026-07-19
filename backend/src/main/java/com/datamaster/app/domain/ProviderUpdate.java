package com.datamaster.app.domain;

public record ProviderUpdate(String baseUrl, String model, String apiKey, Boolean clearApiKey) {
}
