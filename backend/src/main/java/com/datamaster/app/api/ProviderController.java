package com.datamaster.app.api;

import com.datamaster.app.domain.ProviderUpdate;
import com.datamaster.app.domain.ProviderView;
import com.datamaster.app.service.AiInsightService;
import com.datamaster.app.service.ProviderConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/providers")
public class ProviderController {
    private final ProviderConfigService providers;
    private final AiInsightService ai;

    public ProviderController(ProviderConfigService providers, AiInsightService ai) {
        this.providers = providers;
        this.ai = ai;
    }

    @GetMapping
    public ProviderListResponse list() {
        return new ProviderListResponse(providers.selectedProvider(), providers.list());
    }

    @PutMapping("/{id}")
    public ProviderView update(@PathVariable String id, @RequestBody ProviderUpdate request) {
        return providers.update(id, request);
    }

    @PostMapping("/{id}/test")
    public AiInsightService.TestResult test(@PathVariable String id) {
        return ai.test(id);
    }

    public record ProviderListResponse(String selectedProvider, List<ProviderView> providers) {
    }
}
