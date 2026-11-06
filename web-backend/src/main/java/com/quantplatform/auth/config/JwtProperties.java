package com.quantplatform.auth.config;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("security.jwt")
public record JwtProperties(String privateKey,String keyId,Duration expiration) {
    public JwtProperties {
        if(privateKey==null||privateKey.isBlank()||(!privateKey.startsWith("file:")&&!privateKey.startsWith("classpath:")))
            throw new IllegalArgumentException("A private signing key file is required");
        if(keyId==null||keyId.isBlank()) throw new IllegalArgumentException("Signing key ID is required");
        if(expiration==null||expiration.isNegative()||expiration.isZero()||expiration.compareTo(Duration.ofHours(1))>0)
            throw new IllegalArgumentException("Token expiration must be positive and at most one hour");
    }
}
