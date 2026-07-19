package com.datamaster.app.service;

import com.datamaster.app.domain.ProviderUpdate;
import com.datamaster.app.domain.ProviderView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderConfigServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesCurrentDeepSeekAndBailianPresetsWithoutKeys() {
        ProviderConfigService service = new ProviderConfigService(new CryptoService(tempDir.resolve("presets")));

        assertThat(service.list()).extracting(ProviderView::id).containsExactly("deepseek", "bailian");
        assertThat(service.get("deepseek").model()).isEqualTo("deepseek-v4-flash");
        assertThat(service.get("bailian").model()).isEqualTo("qwen3.7-plus");
        assertThat(service.get("deepseek").models()).extracting("id")
                .containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
        assertThat(service.get("bailian").models()).extracting("id")
                .contains("qwen3.7-plus", "qwen3.7-max", "qwen-plus");
        assertThat(service.list()).allMatch(ProviderView::customModelAllowed);
        assertThat(service.list()).noneMatch(ProviderView::configured);
    }

    @Test
    void persistsBothKeysEncryptedAndNeverReturnsThemInViews() throws Exception {
        String deepseekKey = "sk-deepseek-plain-secret";
        String bailianKey = "sk-bailian-plain-secret";
        CryptoService crypto = new CryptoService(tempDir.resolve(".datamaster"));
        ProviderConfigService service = new ProviderConfigService(crypto);

        ProviderView deepseek = service.update("deepseek",
                new ProviderUpdate("https://api.deepseek.com", "deepseek-chat", deepseekKey, false));
        ProviderView bailian = service.update("bailian",
                new ProviderUpdate("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", bailianKey, false));

        assertThat(deepseek.configured()).isTrue();
        assertThat(bailian.configured()).isTrue();
        assertThat(service.list()).allMatch(ProviderView::configured);
        assertThat(service.list().toString()).doesNotContain(deepseekKey, bailianKey, "apiKey");

        String persisted = Files.readString(service.configFile(), StandardCharsets.ISO_8859_1);
        assertThat(persisted).doesNotContain(deepseekKey, bailianKey);
        assertThat(persisted).contains("deepseek.apiKey.enc", "bailian.apiKey.enc", "v1\\:");
        assertThat(service.requireConfigured("deepseek").apiKey()).isEqualTo(deepseekKey);
        assertThat(service.requireConfigured("bailian").apiKey()).isEqualTo(bailianKey);

        String masterKey = Files.readString(tempDir.resolve(".datamaster/master.key"));
        assertThat(persisted).doesNotContain(masterKey.strip());
    }

    @Test
    void blankApiKeyPreservesExistingSecretAndClearFlagRemovesIt() {
        CryptoService crypto = new CryptoService(tempDir.resolve("store"));
        ProviderConfigService service = new ProviderConfigService(crypto);
        service.update("deepseek", new ProviderUpdate(null, null, "secret", false));

        service.update("deepseek", new ProviderUpdate(null, "deepseek-reasoner", "", false));
        assertThat(service.requireConfigured("deepseek").apiKey()).isEqualTo("secret");
        assertThat(service.get("deepseek").model()).isEqualTo("deepseek-reasoner");

        service.update("deepseek", new ProviderUpdate(null, null, null, true));
        assertThat(service.get("deepseek").configured()).isFalse();
    }

    @Test
    void roundTripsProviderSettingsThroughPrivateSyncPayload() {
        ProviderConfigService source = new ProviderConfigService(new CryptoService(tempDir.resolve("source")));
        source.update("deepseek", new ProviderUpdate(null, "deepseek-v4-pro", "source-secret", false));

        ProviderConfigService target = new ProviderConfigService(new CryptoService(tempDir.resolve("target")));
        target.importFromSync(source.exportForSync());

        assertThat(target.selectedProvider()).isEqualTo("deepseek");
        assertThat(target.get("deepseek").model()).isEqualTo("deepseek-v4-pro");
        assertThat(target.requireConfigured("deepseek").apiKey()).isEqualTo("source-secret");
        assertThat(target.list().toString()).doesNotContain("source-secret");
    }
}
