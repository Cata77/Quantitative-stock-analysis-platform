package com.quantplatform.security;
import java.time.Instant;
import java.util.*;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.interfaces.RSAPrivateKey;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
public final class SecurityTokens {
    public static String token(UUID user) { return token(user,"portfolio:write research:read"); }
    public static String token(UUID user,String scope) {
        try {
            String pem=new String(SecurityTokens.class.getResourceAsStream("/security/test-private.pem").readAllBytes());
            var key=(RSAPrivateKey)KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder()
                .decode(pem.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","").replaceAll("\\s",""))));
            Instant now=Instant.now();
            var claims=new JWTClaimsSet.Builder().issuer("quant-platform-local").audience("quant-platform").subject(user.toString())
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(600))).claim("scope",scope).build();
            var token=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local-development-only").build(),claims);
            token.sign(new RSASSASigner(key));return "Bearer "+token.serialize();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
