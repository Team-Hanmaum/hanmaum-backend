package com.hanmaum.backend.global.security;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(
    @NotNull List<String> allowedOrigins, @NotNull URI loginSuccessUrl, boolean publicDocs) {}
