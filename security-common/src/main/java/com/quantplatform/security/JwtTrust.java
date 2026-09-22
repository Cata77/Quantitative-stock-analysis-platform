package com.quantplatform.security;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

/** Pinned public JWKS; untrusted token headers never choose a URL or filesystem path. */
public final class JwtTrust {
    private final JWKSet keys;
    private final NimbusJwtDecoder decoder;
    private final String issuer,audience;
    public JwtTrust(Environment env) throws Exception {
        boolean local=SecurityPolicy.local(env);
        issuer=local?env.getProperty("JWT_ISSUER",env.getProperty("security.jwt.issuer","quant-platform-local")):required(env,"JWT_ISSUER","security.jwt.issuer");
        audience=local?env.getProperty("JWT_AUDIENCE",env.getProperty("security.jwt.audience","quant-platform")):required(env,"JWT_AUDIENCE","security.jwt.audience");
        String location=local?env.getProperty("JWT_PUBLIC_JWKS",env.getProperty("security.jwt.jwks","classpath:security/local-public.jwks.json")):
            required(env,"JWT_PUBLIC_JWKS","security.jwt.jwks");
        keys=load(location);
        Set<String> ids=new HashSet<>();
        var localModulus=((RSAKey)load("classpath:security/local-public.jwks.json").getKeys().getFirst()).getModulus();
        if(keys.getKeys().isEmpty()) throw new IllegalStateException("Public JWKS cannot be empty");
        for(JWK key:keys.getKeys()) {
            if(!(key instanceof RSAKey rsa) || rsa.isPrivate() || rsa.size()<2048 || key.getKeyID()==null
                || key.getKeyID().isBlank() || !ids.add(key.getKeyID())
                || (key.getAlgorithm()!=null&&!JWSAlgorithm.RS256.equals(key.getAlgorithm()))
                || (key.getKeyUse()!=null&&!KeyUse.SIGNATURE.equals(key.getKeyUse())))
                throw new IllegalStateException("JWKS requires unique kid values and public RSA signing keys of at least 2048 bits");
            if(!local && rsa.getModulus().equals(localModulus)) throw new IllegalStateException("Development signing keys are forbidden outside local");
        }
        var processor=new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256,new ImmutableJWKSet<>(keys)));
        processor.setJWTClaimsSetVerifier((claims,context)->{
            // Check original claims before Spring's converter can synthesize an issued-at value.
            if(claims.getExpirationTime()==null||claims.getNotBeforeTime()==null||claims.getIssueTime()==null)
                throw new com.nimbusds.jwt.proc.BadJWTException("Required registered claims are missing");
        });
        decoder=new NimbusJwtDecoder(processor);
        var timestamps=new JwtTimestampValidator(Duration.ofSeconds(30));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps,new JwtIssuerValidator(issuer),jwt->{
            boolean valid=jwt.getAudience().contains(audience) && jwt.getExpiresAt()!=null
                && jwt.getNotBefore()!=null && jwt.getIssuedAt()!=null
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && !jwt.getNotBefore().isAfter(jwt.getExpiresAt())
                && jwt.getHeaders().get("kid") instanceof String kid && ids.contains(kid);
            try { UUID.fromString(jwt.getSubject()); } catch(Exception e) { valid=false; }
            return valid?OAuth2TokenValidatorResult.success():OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token","Required token claims are invalid",null));
        }));
    }
    private static String required(Environment env,String variable,String property) {
        String value=env.getProperty(variable,env.getProperty(property,""));
        if(value.isBlank()) throw new IllegalStateException("Required JWT configuration: "+variable);
        return value;
    }
    private static JWKSet load(String location) throws Exception {
        if(!location.startsWith("file:")&&!location.startsWith("classpath:"))
            throw new IllegalStateException("JWKS must be a pinned file or classpath resource");
        try(var stream=new DefaultResourceLoader().getResource(location).getInputStream()) {
            return JWKSet.parse(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    public JwtDecoder decoder() { return decoder; }
    public JWKSet publicKeys() { return keys.toPublicJWKSet(); }
    public String issuer() { return issuer; }
    public String audience() { return audience; }
}
