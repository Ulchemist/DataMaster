package com.datamaster.app.service;

import com.datamaster.app.domain.ProviderSyncPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

@Service
public class SyncService {
    private static final String TOKEN_KEY = "token.enc";

    private final ProviderConfigService providers;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final RemoteTransport remoteTransport;
    private final String remoteBaseUrl;
    private final Path configFile;

    @Autowired
    public SyncService(ProviderConfigService providers,
                       CryptoService crypto,
                       ObjectMapper objectMapper,
                       @Value("${datamaster.sync.remote-base-url:https://datamaster-analysis.odozidahe433.chatgpt.site}")
                       String remoteBaseUrl) {
        this(providers, crypto, objectMapper, remoteBaseUrl,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build());
    }

    SyncService(ProviderConfigService providers,
                CryptoService crypto,
                ObjectMapper objectMapper,
                String remoteBaseUrl,
                HttpClient httpClient) {
        this(providers, crypto, objectMapper, remoteBaseUrl, request -> {
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new RemoteResponse(response.statusCode(), response.body());
        });
    }

    SyncService(ProviderConfigService providers,
                CryptoService crypto,
                ObjectMapper objectMapper,
                String remoteBaseUrl,
                RemoteTransport remoteTransport) {
        this.providers = providers;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.remoteBaseUrl = validateRemoteBaseUrl(remoteBaseUrl);
        this.remoteTransport = remoteTransport;
        this.configFile = crypto.home().resolve("sync.properties");
    }

    public synchronized SyncStatus status() {
        boolean connected = hasToken(load());
        return new SyncStatus(connected, remoteBaseUrl,
                connected ? "已连接 DataMaster 网页账户" : "尚未连接网页账户");
    }

    public synchronized SyncStatus connect(String token) {
        String clean = token == null ? "" : token.strip();
        if (clean.length() < 24 || clean.length() > 512 || clean.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("同步令牌格式不正确，请从网页端重新生成并完整粘贴");
        }
        // Verify the token before persisting it so the UI never reports a false connection.
        fetchRemoteConfiguration(clean);
        Properties properties = load();
        properties.setProperty(TOKEN_KEY, crypto.encrypt(clean));
        save(properties);
        return new SyncStatus(true, remoteBaseUrl, "同步令牌已验证并安全保存，可以拉取或推送配置");
    }

    public synchronized SyncStatus disconnect() {
        Properties properties = load();
        properties.remove(TOKEN_KEY);
        save(properties);
        return new SyncStatus(false, remoteBaseUrl, "已断开网页账户，本机 AI 配置仍然保留");
    }

    public SyncActionResult pull() {
        String token = requireToken();
        ProviderSyncPayload payload = fetchRemoteConfiguration(token);
        List<?> imported = providers.importFromSync(payload);
        long configured = payload.providers() == null ? 0 : payload.providers().stream()
                .filter(item -> item != null && item.configured() && item.apiKey() != null && !item.apiKey().isBlank())
                .count();
        return new SyncActionResult(true,
                "已从网页端同步 " + imported.size() + " 个平台配置，其中 " + configured + " 个包含 API Key",
                providers.selectedProvider());
    }

    private ProviderSyncPayload fetchRemoteConfiguration(String token) {
        HttpRequest request = HttpRequest.newBuilder(syncUri())
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        RemoteResponse response = send(request);
        ensureSuccess(response);
        try {
            ProviderSyncPayload payload = objectMapper.readValue(response.body(), ProviderSyncPayload.class);
            if (payload == null || payload.providers() == null) {
                throw new IllegalArgumentException("missing providers");
            }
            return payload;
        } catch (RuntimeException ex) {
            throw new RemoteSyncException("云端返回了无法识别的配置，请更新 DataMaster 后重试", ex);
        }
    }

    public SyncActionResult push() {
        String token = requireToken();
        ProviderSyncPayload payload = providers.exportForSync();
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(syncUri())
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        RemoteResponse response = send(request);
        ensureSuccess(response);
        long configured = payload.providers().stream().filter(ProviderSyncPayload.ProviderSyncItem::configured).count();
        return new SyncActionResult(true,
                "已将本机平台配置同步到网页端（" + configured + " 个 API Key）",
                payload.selectedProvider());
    }

    Path configFile() {
        return configFile;
    }

    private synchronized String requireToken() {
        String encrypted = load().getProperty(TOKEN_KEY, "");
        if (encrypted.isBlank()) {
            throw new IllegalArgumentException("尚未连接网页账户，请先粘贴同步令牌");
        }
        return crypto.decrypt(encrypted);
    }

    private RemoteResponse send(HttpRequest request) {
        try {
            return remoteTransport.send(request);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RemoteSyncException("同步已中断，请重试", ex);
        } catch (IOException ex) {
            throw new RemoteSyncException("无法连接 DataMaster 网页站点，请检查网络后重试", ex);
        }
    }

    private static void ensureSuccess(RemoteResponse response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) return;
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RemoteSyncException("同步令牌无效或已撤销，请在网页端重新生成");
        }
        throw new RemoteSyncException("云端同步失败（HTTP " + response.statusCode() + "），请稍后重试");
    }

    private URI syncUri() {
        return URI.create(remoteBaseUrl + "/api/sync/config");
    }

    private Properties load() {
        Properties properties = new Properties();
        if (!Files.exists(configFile)) return properties;
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("读取账户同步配置失败", ex);
        }
    }

    private void save(Properties properties) {
        try {
            Files.createDirectories(configFile.getParent());
            Path temp = Files.createTempFile(configFile.getParent(), "sync-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "DataMaster account sync token (AES-GCM encrypted)");
            }
            CryptoService.restrictFile(temp);
            try {
                Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            CryptoService.restrictFile(configFile);
        } catch (IOException ex) {
            throw new IllegalStateException("保存账户同步配置失败", ex);
        }
    }

    private static boolean hasToken(Properties properties) {
        return !properties.getProperty(TOKEN_KEY, "").isBlank();
    }

    private static String validateRemoteBaseUrl(String value) {
        String clean = value == null ? "" : value.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(clean);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("无效的同步站点地址", ex);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new IllegalStateException("同步站点必须使用有效的 HTTPS 地址");
        }
        return clean;
    }

    public record SyncStatus(boolean connected, String siteUrl, String message) {
    }

    public record SyncActionResult(boolean success, String message, String selectedProvider) {
    }

    record RemoteResponse(int statusCode, String body) {
    }

    @FunctionalInterface
    interface RemoteTransport {
        RemoteResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    public static final class RemoteSyncException extends RuntimeException {
        public RemoteSyncException(String message) {
            super(message);
        }

        public RemoteSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
