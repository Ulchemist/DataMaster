package com.datamaster.app.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void storesPairingTokenEncryptedAndCanDisconnectWithoutDeletingProviderSettings() throws Exception {
        CryptoService crypto = new CryptoService(tempDir.resolve("store"));
        ProviderConfigService providers = new ProviderConfigService(crypto);
        providers.update("deepseek", new com.datamaster.app.domain.ProviderUpdate(null, null, "provider-key", false));
        String cloudPayload = """
                {"selectedProvider":"deepseek","providers":[
                  {"id":"deepseek","baseUrl":"https://api.deepseek.com","model":"deepseek-v4-flash","apiKey":"","configured":false},
                  {"id":"bailian","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","model":"qwen3.7-plus","apiKey":"","configured":false}
                ]}
                """;
        SyncService sync = new SyncService(providers, crypto, JsonMapper.builder().build(),
                "https://example.com", request -> new SyncService.RemoteResponse(200, cloudPayload));
        String token = "dms_0123456789abcdefghijklmnopqrstuvwxyz";

        assertThat(sync.status().connected()).isFalse();
        assertThat(sync.connect(token).connected()).isTrue();
        String persisted = Files.readString(sync.configFile(), StandardCharsets.ISO_8859_1);
        assertThat(persisted).contains("token.enc", "v1\\:").doesNotContain(token);

        assertThat(sync.disconnect().connected()).isFalse();
        assertThat(providers.requireConfigured("deepseek").apiKey()).isEqualTo("provider-key");
    }

    @Test
    void rejectsShortOrWhitespacePairingTokens() {
        CryptoService crypto = new CryptoService(tempDir.resolve("invalid"));
        SyncService sync = new SyncService(new ProviderConfigService(crypto), crypto,
                JsonMapper.builder().build(), "https://example.com",
                request -> new SyncService.RemoteResponse(200, "{\"providers\":[]}"));

        assertThatThrownBy(() -> sync.connect("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sync.connect("dms_0123456789abcdefghijkl mnopqrstuvwxyz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotPersistTokenWhenCloudRejectsIt() {
        CryptoService crypto = new CryptoService(tempDir.resolve("rejected"));
        SyncService sync = new SyncService(new ProviderConfigService(crypto), crypto,
                JsonMapper.builder().build(), "https://example.com",
                request -> new SyncService.RemoteResponse(401, "{}"));

        assertThatThrownBy(() -> sync.connect("dms_0123456789abcdefghijklmnopqrstuvwxyz"))
                .isInstanceOf(SyncService.RemoteSyncException.class)
                .hasMessageContaining("无效");
        assertThat(sync.status().connected()).isFalse();
    }
}
