package com.datamaster.app.service;

import com.datamaster.app.domain.ProviderUpdate;
import com.datamaster.app.domain.ProviderView;
import com.datamaster.app.domain.ModelOption;
import com.datamaster.app.domain.ProviderSyncPayload;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Collections;

@Service
public class ProviderConfigService {
    private static final Map<String, Preset> PRESETS = presets();
    private final CryptoService crypto;
    private final Path configFile;

    public ProviderConfigService(CryptoService crypto) {
        this.crypto = crypto;
        this.configFile = crypto.home().resolve("providers.properties");
    }

    public synchronized List<ProviderView> list() {
        Properties properties = load();
        return PRESETS.keySet().stream().map(id -> view(id, properties)).toList();
    }

    public synchronized ProviderView get(String id) {
        requirePreset(id);
        return view(id, load());
    }

    public synchronized ProviderView update(String id, ProviderUpdate update) {
        Preset preset = requirePreset(id);
        if (update == null) throw new IllegalArgumentException("配置内容不能为空");
        String baseUrl = cleanOrDefault(update.baseUrl(), preset.baseUrl());
        validateBaseUrl(baseUrl);
        String model = cleanOrDefault(update.model(), preset.model());
        Properties properties = load();
        properties.setProperty(key(id, "baseUrl"), baseUrl);
        properties.setProperty(key(id, "model"), model);
        properties.setProperty("selectedProvider", id);
        if (Boolean.TRUE.equals(update.clearApiKey())) {
            properties.remove(key(id, "apiKey.enc"));
        } else if (update.apiKey() != null && !update.apiKey().isBlank()) {
            properties.setProperty(key(id, "apiKey.enc"), crypto.encrypt(update.apiKey().strip()));
        }
        save(properties);
        return view(id, properties);
    }

    public synchronized ProviderSyncPayload exportForSync() {
        Properties properties = load();
        List<ProviderSyncPayload.ProviderSyncItem> items = PRESETS.keySet().stream().map(id -> {
            Preset preset = requirePreset(id);
            String encrypted = properties.getProperty(key(id, "apiKey.enc"), "");
            String apiKey = encrypted.isBlank() ? "" : crypto.decrypt(encrypted);
            return new ProviderSyncPayload.ProviderSyncItem(
                    id,
                    properties.getProperty(key(id, "baseUrl"), preset.baseUrl()),
                    properties.getProperty(key(id, "model"), preset.model()),
                    apiKey,
                    !apiKey.isBlank()
            );
        }).toList();
        return new ProviderSyncPayload(selectedProvider(properties), items);
    }

    public synchronized List<ProviderView> importFromSync(ProviderSyncPayload payload) {
        if (payload == null || payload.providers() == null) {
            throw new IllegalArgumentException("云端配置内容为空");
        }
        Properties properties = load();
        for (ProviderSyncPayload.ProviderSyncItem item : payload.providers()) {
            if (item == null || !PRESETS.containsKey(item.id())) continue;
            Preset preset = requirePreset(item.id());
            String baseUrl = cleanOrDefault(item.baseUrl(), preset.baseUrl());
            validateBaseUrl(baseUrl);
            properties.setProperty(key(item.id(), "baseUrl"), baseUrl);
            properties.setProperty(key(item.id(), "model"), cleanOrDefault(item.model(), preset.model()));
            if (item.configured() && item.apiKey() != null && !item.apiKey().isBlank()) {
                properties.setProperty(key(item.id(), "apiKey.enc"), crypto.encrypt(item.apiKey().strip()));
            } else if (!item.configured()) {
                properties.remove(key(item.id(), "apiKey.enc"));
            }
        }
        if (payload.selectedProvider() != null && PRESETS.containsKey(payload.selectedProvider())) {
            properties.setProperty("selectedProvider", payload.selectedProvider());
        }
        save(properties);
        return PRESETS.keySet().stream().map(id -> view(id, properties)).toList();
    }

    public synchronized String selectedProvider() {
        return selectedProvider(load());
    }

    public synchronized ProviderSecret requireConfigured(String id) {
        Preset preset = requirePreset(id);
        Properties properties = load();
        String encrypted = properties.getProperty(key(id, "apiKey.enc"), "");
        if (encrypted.isBlank()) {
            throw new ProviderNotConfiguredException(id);
        }
        String baseUrl = properties.getProperty(key(id, "baseUrl"), preset.baseUrl());
        String model = properties.getProperty(key(id, "model"), preset.model());
        return new ProviderSecret(id, preset.name(), baseUrl, model, crypto.decrypt(encrypted));
    }

    public synchronized boolean isConfigured(String id) {
        requirePreset(id);
        return !load().getProperty(key(id, "apiKey.enc"), "").isBlank();
    }

    Path configFile() {
        return configFile;
    }

    private ProviderView view(String id, Properties properties) {
        Preset preset = requirePreset(id);
        return new ProviderView(id, preset.name(),
                properties.getProperty(key(id, "baseUrl"), preset.baseUrl()),
                properties.getProperty(key(id, "model"), preset.model()),
                !properties.getProperty(key(id, "apiKey.enc"), "").isBlank(),
                preset.models(),
                true);
    }

    private Properties load() {
        Properties properties = new Properties();
        if (!Files.exists(configFile)) return properties;
        try (InputStream input = Files.newInputStream(configFile)) {
            properties.load(input);
            return properties;
        } catch (IOException ex) {
            throw new IllegalStateException("读取 AI 配置失败", ex);
        }
    }

    private void save(Properties properties) {
        try {
            Files.createDirectories(configFile.getParent());
            Path temp = Files.createTempFile(configFile.getParent(), "providers-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "DataMaster local AI provider settings (API keys are AES-GCM encrypted)");
            }
            CryptoService.restrictFile(temp);
            try {
                Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
            CryptoService.restrictFile(configFile);
        } catch (IOException ex) {
            throw new IllegalStateException("保存 AI 配置失败", ex);
        }
    }

    private static void validateBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("API 地址必须是有效的 HTTPS 地址");
            }
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("API 地址必须是有效的 HTTPS 地址");
        }
    }

    private static String cleanOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip().replaceAll("/+$", "");
    }

    private static String key(String id, String property) {
        return id + "." + property;
    }

    private static String selectedProvider(Properties properties) {
        String selected = properties.getProperty("selectedProvider", "deepseek");
        return PRESETS.containsKey(selected) ? selected : "deepseek";
    }

    private static Preset requirePreset(String id) {
        Preset preset = PRESETS.get(id);
        if (preset == null) throw new IllegalArgumentException("未知 AI 服务商：" + id);
        return preset;
    }

    private static Map<String, Preset> presets() {
        Map<String, Preset> presets = new LinkedHashMap<>();
        presets.put("deepseek", new Preset(
                "DeepSeek",
                "https://api.deepseek.com",
                "deepseek-v4-flash",
                List.of(
                        new ModelOption("deepseek-v4-flash", "DeepSeek V4 Flash", "高速度、低延迟，适合日常表格分析", true),
                        new ModelOption("deepseek-v4-pro", "DeepSeek V4 Pro", "更强推理，适合复杂经营归因", false)
                )));
        presets.put("bailian", new Preset(
                "阿里云百炼",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                List.of(
                        new ModelOption("qwen3.7-plus", "Qwen3.7 Plus", "质量与速度均衡，推荐经营分析", true),
                        new ModelOption("qwen3.7-max", "Qwen3.7 Max", "更适合复杂推理和长表摘要", false),
                        new ModelOption("qwen3-max", "Qwen3 Max", "稳定通用的大模型选项", false),
                        new ModelOption("qwen-plus", "Qwen Plus", "成本友好的通用模型", false),
                        new ModelOption("qwen-turbo", "Qwen Turbo", "低延迟批量摘要", false)
                )));
        return Collections.unmodifiableMap(presets);
    }

    private record Preset(String name, String baseUrl, String model, List<ModelOption> models) {
    }

    public record ProviderSecret(String id, String name, String baseUrl, String model, String apiKey) {
    }

    public static final class ProviderNotConfiguredException extends RuntimeException {
        public ProviderNotConfiguredException(String id) {
            super("尚未配置 " + id + " 的 API Key");
        }
    }
}
