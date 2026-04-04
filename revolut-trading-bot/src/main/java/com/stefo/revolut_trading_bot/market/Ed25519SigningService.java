package com.stefo.revolut_trading_bot.market;

import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.exception.SigningException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class Ed25519SigningService {

    private final RevolutApiConfig apiConfig;
    private Ed25519PrivateKeyParameters privateKey;

    @PostConstruct
    public void init() {
        String keyHex = apiConfig.getPrivateKeyHex();
        if (keyHex == null || keyHex.isBlank()) {
            log.warn("No private key configured — authenticated endpoints will not work");
            return;
        }
        try {
            byte[] keyBytes = hexToBytes(keyHex);
            // NaCl sign secret key is 64 bytes: first 32 = seed, last 32 = public key
            // BouncyCastle Ed25519PrivateKeyParameters takes the 32-byte seed
            byte[] seed = keyBytes.length == 64 ? java.util.Arrays.copyOf(keyBytes, 32) : keyBytes;
            privateKey = new Ed25519PrivateKeyParameters(seed, 0);
            log.info("Ed25519 private key loaded successfully from hex");
        } catch (Exception e) {
            log.error("Failed to load Ed25519 private key", e);
        }
    }

    /**
     * Signs the message exactly like the Postman script:
     * message = timestamp + METHOD + PATH + QUERY + BODY
     * signature = base64(ed25519_sign_detached(message, privateKey))
     */
    public String sign(String message) {
        if (privateKey == null) {
            throw new SigningException("Private key not loaded", null);
        }
        try {
            Ed25519Signer signer = new Ed25519Signer();
            signer.init(true, privateKey);
            byte[] messageBytes = message.getBytes(StandardCharsets.UTF_8);
            signer.update(messageBytes, 0, messageBytes.length);
            byte[] signature = signer.generateSignature();
            return Base64.getEncoder().encodeToString(signature);
        } catch (Exception e) {
            throw new SigningException("Failed to sign message", e);
        }
    }

    /**
     * Builds the message string exactly as Postman does:
     * timestamp + METHOD + PATH + QUERY + BODY
     * (no separators, concatenated directly)
     */
    public String buildSignatureMessage(long timestamp, String method, String path,
                                         String queryString, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append(timestamp);
        sb.append(method.toUpperCase());
        sb.append(path);
        if (queryString != null && !queryString.isEmpty()) {
            sb.append(queryString);
        }
        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }
        return sb.toString();
    }

    public boolean isKeyLoaded() {
        return privateKey != null;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
