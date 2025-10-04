package com.legalai.agent.entity;

import jakarta.persistence.*;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;

@Entity
@Table(name = "documents")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fileName;

    @Column(columnDefinition = "TEXT")
    private String encryptedContent;

    @Column(nullable = false)
    private String jurisdiction;

    @Version
    private Integer version;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    // Hardcoded AES key for demonstration purposes
    // TODO: In production, use a secure key management solution like:
    // - AWS KMS, Azure Key Vault, or HashiCorp Vault
    // - Environment variables or secure configuration management
    // - Key rotation policies and secure key derivation functions (KDF)
    private static final byte[] AES_KEY = "MySampleAESKey16".getBytes(StandardCharsets.UTF_8); // 128-bit key
    private static final byte[] INIT_VECTOR = "InitVector16byte".getBytes(StandardCharsets.UTF_8); // 128-bit IV

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Encrypts plain text content using AES-256-CBC with BouncyCastle
     * 
     * @param plainText The plain text to encrypt
     * @return Base64 encoded encrypted content
     * @throws Exception if encryption fails
     */
    public String encryptContent(String plainText) throws Exception {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }

        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESEngine())
        );
        
        KeyParameter keyParam = new KeyParameter(AES_KEY);
        ParametersWithIV params = new ParametersWithIV(keyParam, INIT_VECTOR);
        
        cipher.init(true, params);
        
        byte[] input = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        
        int bytesProcessed = cipher.processBytes(input, 0, input.length, output, 0);
        bytesProcessed += cipher.doFinal(output, bytesProcessed);
        
        byte[] result = new byte[bytesProcessed];
        System.arraycopy(output, 0, result, 0, bytesProcessed);
        
        this.encryptedContent = Base64.getEncoder().encodeToString(result);
        return this.encryptedContent;
    }

    /**
     * Decrypts the encrypted content using AES-256-CBC with BouncyCastle
     * 
     * @return Decrypted plain text content
     * @throws Exception if decryption fails
     */
    public String decryptContent() throws Exception {
        if (encryptedContent == null || encryptedContent.isEmpty()) {
            return null;
        }

        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
            new CBCBlockCipher(new AESEngine())
        );
        
        KeyParameter keyParam = new KeyParameter(AES_KEY);
        ParametersWithIV params = new ParametersWithIV(keyParam, INIT_VECTOR);
        
        cipher.init(false, params);
        
        byte[] input = Base64.getDecoder().decode(encryptedContent);
        byte[] output = new byte[cipher.getOutputSize(input.length)];
        
        int bytesProcessed = cipher.processBytes(input, 0, input.length, output, 0);
        bytesProcessed += cipher.doFinal(output, bytesProcessed);
        
        return new String(output, 0, bytesProcessed, StandardCharsets.UTF_8);
    }

    // Getters and Setters

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getEncryptedContent() {
        return encryptedContent;
    }

    public void setEncryptedContent(String encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    public String getJurisdiction() {
        return jurisdiction;
    }

    public void setJurisdiction(String jurisdiction) {
        this.jurisdiction = jurisdiction;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

