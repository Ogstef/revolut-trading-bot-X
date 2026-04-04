package com.stefo.revolut_trading_bot.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "revolut.api")
public class RevolutApiConfig {

    @NotNull
    private String baseUrl;

    private String apiKey;

    private String privateKeyHex;

    @Positive
    private int rateLimitPerMinute;
}
