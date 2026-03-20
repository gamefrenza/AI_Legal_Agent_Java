package com.legalai.agent.config;

import com.legalai.agent.entity.Document;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
public class EncryptionConfig {

    private static final Logger logger = LoggerFactory.getLogger(EncryptionConfig.class);

    @Value("${encryption.aes-key}")
    private String aesKeyString;

    @PostConstruct
    public void init() {
        byte[] keyBytes = aesKeyString.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                "encryption.aes-key must be exactly 32 bytes for AES-256, but got " + keyBytes.length + " bytes. " +
                "Set ENCRYPTION_KEY environment variable or update application.yml."
            );
        }
        Document.setEncryptionKey(keyBytes);
        logger.info("AES-256 encryption key configured ({} bytes)", keyBytes.length);
    }
}
