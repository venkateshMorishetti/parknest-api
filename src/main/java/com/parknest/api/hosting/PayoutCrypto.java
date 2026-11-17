package com.parknest.api.hosting;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM for bank account numbers. Stored as IV (12 bytes) + ciphertext. The key never goes in the database. */
@Component
public class PayoutCrypto {

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PayoutCrypto(@Value("${parknest.payouts.encryption-key}") String base64Key) {
        byte[] bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != 32) throw new IllegalStateException("PAYOUT_ENCRYPTION_KEY must be 32 bytes, base64 encoded");
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt", e);
        }
    }

    public String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt", e);
        }
    }
}
