package com.stefo.revolut_trading_bot.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefo.revolut_trading_bot.config.RevolutApiConfig;
import com.stefo.revolut_trading_bot.exception.ApiException;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RevolutApiClient {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");
    private static final String HEADER_API_KEY = "X-Revx-API-Key";
    private static final String HEADER_TIMESTAMP = "X-Revx-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Revx-Signature";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final RevolutApiConfig apiConfig;
    private final Ed25519SigningService signingService;

    public RevolutApiClient(RevolutApiConfig apiConfig, Ed25519SigningService signingService,
                            ObjectMapper objectMapper) {
        this.apiConfig = apiConfig;
        this.signingService = signingService;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public <T> T getPublic(String path, String queryString, TypeReference<T> typeRef) {
        String url = buildUrl(path, queryString);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return execute(request, typeRef);
    }

    public <T> T getAuthenticated(String path, String queryString, TypeReference<T> typeRef) {
        String url = buildUrl(path, queryString);
        long timestamp = System.currentTimeMillis();

        String fullPath = fullPath(path);
        String message = signingService.buildSignatureMessage(timestamp, "GET", fullPath, queryString, null);
        String signature = signingService.sign(message);

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader(HEADER_API_KEY, apiConfig.getApiKey())
                .addHeader(HEADER_TIMESTAMP, String.valueOf(timestamp))
                .addHeader(HEADER_SIGNATURE, signature)
                .build();
        return execute(request, typeRef);
    }

    public <T> T postAuthenticated(String path, Object body, TypeReference<T> typeRef) {
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            long timestamp = System.currentTimeMillis();

            String message = signingService.buildSignatureMessage(timestamp, "POST", fullPath(path), null, bodyJson);
            String signature = signingService.sign(message);

            Request request = new Request.Builder()
                    .url(buildUrl(path, null))
                    .post(RequestBody.create(bodyJson, JSON_MEDIA_TYPE))
                    .addHeader(HEADER_API_KEY, apiConfig.getApiKey())
                    .addHeader(HEADER_TIMESTAMP, String.valueOf(timestamp))
                    .addHeader(HEADER_SIGNATURE, signature)
                    .build();
            return execute(request, typeRef);
        } catch (IOException e) {
            throw new ApiException("Failed to serialize request body", e);
        }
    }

    public void deleteAuthenticated(String path) {
        long timestamp = System.currentTimeMillis();

        String message = signingService.buildSignatureMessage(timestamp, "DELETE", fullPath(path), null, null);
        String signature = signingService.sign(message);

        Request request = new Request.Builder()
                .url(buildUrl(path, null))
                .delete()
                .addHeader(HEADER_API_KEY, apiConfig.getApiKey())
                .addHeader(HEADER_TIMESTAMP, String.valueOf(timestamp))
                .addHeader(HEADER_SIGNATURE, signature)
                .build();
        executeVoid(request);
    }

    private <T> T execute(Request request, TypeReference<T> typeRef) {
        log.info("API request: {} {}", request.method(), request.url());
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody responseBody = response.body();
            String bodyString = responseBody != null ? responseBody.string() : "";

            if (!response.isSuccessful()) {
                log.error("API error {}: {}", response.code(), bodyString);
                throw new ApiException("API call failed: " + bodyString, response.code());
            }

            log.debug("API response {}: {} chars", response.code(), bodyString.length());

            // All Revolut X API responses are wrapped: { "data": <payload> }
            // Unwrap before deserializing so callers work directly with the payload type.
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(bodyString);
            com.fasterxml.jackson.databind.JsonNode payload = root.has("data") ? root.get("data") : root;
            return objectMapper.convertValue(payload, typeRef);
        } catch (IOException e) {
            throw new ApiException("API call failed: " + e.getMessage(), e);
        }
    }

    private void executeVoid(Request request) {
        log.debug("API request: {} {}", request.method(), request.url());
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String bodyString = response.body() != null ? response.body().string() : "";
                log.error("API error {}: {}", response.code(), bodyString);
                throw new ApiException("API call failed: " + bodyString, response.code());
            }
        } catch (IOException e) {
            throw new ApiException("API call failed: " + e.getMessage(), e);
        }
    }

    /** Returns the path component of the base URL + the endpoint path, e.g. /api/1.0/balance */
    private String fullPath(String path) {
        return URI.create(apiConfig.getBaseUrl()).getPath() + path;
    }

    private String buildUrl(String path, String queryString) {
        String url = apiConfig.getBaseUrl() + path;
        if (queryString != null && !queryString.isEmpty()) {
            url += "?" + queryString;
        }
        return url;
    }
}
