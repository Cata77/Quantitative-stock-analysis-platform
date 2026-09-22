package com.quantplatform.auth.security;
import com.quantplatform.auth.config.JwtProperties;
import com.quantplatform.auth.user.User;
import com.quantplatform.security.JwtTrust;
import com.nimbusds.jose.jwk.RSAKey;
import io.jsonwebtoken.Jwts;
import java.time.*;
import java.util.*;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Service;
@Service
public class JwtService {
    private final JwtProperties properties;
    private final JwtTrust trust;
    private final RSAPrivateKey key;
    public JwtService(JwtProperties properties,JwtTrust trust) throws Exception {
        this.properties=properties;this.trust=trust;
        try(var input=new DefaultResourceLoader().getResource(properties.privateKey()).getInputStream()) {
            String pem=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            key=(RSAPrivateKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                .decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s",""))));
        }
        var publicKey=trust.publicKeys().getKeyByKeyId(properties.keyId());
        if(!(publicKey instanceof RSAKey rsa)||!rsa.toRSAPublicKey().getModulus().equals(key.getModulus()))
            throw new IllegalStateException("Signing key must match the public JWKS key ID");
    }
    public IssuedToken issue(User user) {
        Instant now=Instant.now(),expires=now.plus(properties.expiration());
        String token=Jwts.builder().header().keyId(properties.keyId()).and().subject(user.getId().toString())
            .issuer(trust.issuer()).audience().add(trust.audience()).and()
            .issuedAt(Date.from(now)).notBefore(Date.from(now)).expiration(Date.from(expires))
            .claim("username",user.getUsername()).claim("scope","portfolio:write research:read")
            .signWith(key,Jwts.SIG.RS256).compact();
        return new IssuedToken(token,expires);
    }
    public record IssuedToken(String value,Instant expiresAt) {}
}
