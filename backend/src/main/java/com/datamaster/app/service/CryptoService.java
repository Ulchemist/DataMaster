package com.datamaster.app.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Service
public class CryptoService {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path home;
    private final Path keyFile;
    private volatile SecretKey masterKey;

    @Autowired
    public CryptoService(@Value("${datamaster.home:${user.home}/.datamaster}") String home) {
        this(Path.of(home));
    }

    public CryptoService(Path home) {
        this.home = home;
        this.keyFile = home.resolve("master.key");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);
            return "v1:" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("API Key 加密失败", ex);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        if (!encoded.startsWith("v1:")) throw new IllegalStateException("不支持的密钥格式");
        try {
            byte[] payload = Base64.getDecoder().decode(encoded.substring(3));
            if (payload.length <= IV_LENGTH) throw new IllegalStateException("密钥数据已损坏");
            byte[] iv = java.util.Arrays.copyOfRange(payload, 0, IV_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("API Key 解密失败，本机主密钥可能已变化", ex);
        }
    }

    public Path home() {
        return home;
    }

    private SecretKey key() {
        SecretKey current = masterKey;
        if (current != null) return current;
        synchronized (this) {
            if (masterKey != null) return masterKey;
            try {
                Files.createDirectories(home);
                restrictDirectory(home);
                if (Files.exists(keyFile)) {
                    byte[] decoded = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).strip());
                    if (decoded.length != 32) throw new IllegalStateException("主密钥长度无效");
                    masterKey = new SecretKeySpec(decoded, "AES");
                } else {
                    KeyGenerator generator = KeyGenerator.getInstance("AES");
                    generator.init(256);
                    SecretKey generated = generator.generateKey();
                    Files.writeString(keyFile, Base64.getEncoder().encodeToString(generated.getEncoded()),
                            StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                    restrictFile(keyFile);
                    masterKey = generated;
                }
                restrictFile(keyFile);
                return masterKey;
            } catch (IOException | GeneralSecurityException | IllegalArgumentException ex) {
                throw new IllegalStateException("无法初始化本机密钥存储：" + keyFile, ex);
            }
        }
    }

    static void restrictFile(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            java.io.File file = path.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
        }
    }

    private static void restrictDirectory(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            java.io.File file = path.toFile();
            file.setReadable(false, false);
            file.setWritable(false, false);
            file.setExecutable(false, false);
            file.setReadable(true, true);
            file.setWritable(true, true);
            file.setExecutable(true, true);
        }
    }
}
