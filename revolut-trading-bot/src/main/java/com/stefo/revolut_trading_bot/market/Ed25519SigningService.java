package com.stefo.revolut_trading_bot.market;

import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.exception.SigningException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.openssl.PEMParser;
import org.springframework.stereotype.Service;

import java.io.FileReader;
import java.io.IOException;
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
        String keyPath = apiConfig.getPrivateKeyPath();
        if (keyPath == null || keyPath.isBlank()) {
            log.warn("No private key path configured — authenticated endpoints will not work");
            return;
        }
        try {
            loadPrivateKey(keyPath);
            log.info("Ed25519 private key loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load Ed25519 private key from {}", keyPath, e);
        }
    }

    private void loadPrivateKey(String path) throws IOException {
        try (PEMParser parser = new PEMParser(new FileReader(path))) {
            Object parsed = parser.readObject();
            PrivateKeyInfo keyInfo;
            if (parsed instanceof PrivateKeyInfo info) {
                keyInfo = info;
            } else {
                throw new IOException("Unexpected PEM object type: " + parsed.getClass().getName());
            }
            privateKey = (Ed25519PrivateKeyParameters) PrivateKeyFactory.createKey(keyInfo);
        }
    }

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
}
