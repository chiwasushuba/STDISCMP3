package edu.stdiscm.consumer;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("media")
public record MediaSettings(
    @Min(1) @Max(65535) int grpcPort,
    @Min(1) @Max(128) int consumers,
    @Min(0) @Max(10000) int queueCapacity,
    @NotNull Path output,
    @Min(1) long maxBytes,
    @Min(1) @Max(3600) int idleSeconds) {}
