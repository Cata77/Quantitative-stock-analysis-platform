package com.quantplatform.security;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
class JwtTrustTest {
    @TempDir Path directory;
    RSAKey key,second;
    JwtTrust trust;
    @BeforeEach void setup() throws Exception {
        key=new RSAKeyGenerator(2048).keyID("key-1").generate();
        second=new RSAKeyGenerator(2048).keyID("key-2").generate();
        trust=trust(List.of(key.toPublicJWK(),second.toPublicJWK()));
    }
    JwtTrust trust(List<JWK> keys) throws Exception {
        Path file=directory.resolve(UUID.randomUUID()+".json");Files.writeString(file,new JWKSet(keys).toString(false));
        return new JwtTrust(new MockEnvironment().withProperty("security.jwt.issuer","test-issuer")
            .withProperty("security.jwt.audience","test-audience").withProperty("security.jwt.jwks",file.toUri().toString()));
    }
    JWTClaimsSet.Builder claims() {
        Instant now=Instant.now();
        return new JWTClaimsSet.Builder().subject("70000000-0000-0000-0000-000000000001")
            .issuer("test-issuer").audience("test-audience").issueTime(Date.from(now.minusSeconds(60)))
            .notBeforeTime(Date.from(now.minusSeconds(60))).expirationTime(Date.from(now.plusSeconds(600)));
    }
    String signed(RSAKey signer,String kid,JWSAlgorithm alg,JWTClaimsSet claims) throws Exception {
        var jwt=new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(),claims);
        jwt.sign(new RSASSASigner(signer));return jwt.serialize();
    }
    @Test void verifiesBothRotationKeysAndRejectsRetiredKey() throws Exception {
        for(RSAKey signer:List.of(key,second))
            assertThat(trust.decoder().decode(signed(signer,signer.getKeyID(),JWSAlgorithm.RS256,claims().build())).getSubject()).isNotBlank();
        var rotated=trust(List.of(second.toPublicJWK()));
        assertThatThrownBy(()->rotated.decoder().decode(signed(key,"key-1",JWSAlgorithm.RS256,claims().build())))
            .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
    @Test void rejectsWrongIssuerAudienceExpiredFutureAndMissingClaims() throws Exception {
        for(JWTClaimsSet claims:List.of(claims().issuer("wrong").build(),claims().audience("wrong").build(),
            claims().expirationTime(Date.from(Instant.now().minusSeconds(120))).build(),
            claims().notBeforeTime(Date.from(Instant.now().plusSeconds(300))).build(),
            claims().expirationTime(null).build(),claims().notBeforeTime(null).build(),claims().issueTime(null).build(),
            claims().subject("not-a-user").build())) {
            assertThatThrownBy(()->trust.decoder().decode(signed(key,"key-1",JWSAlgorithm.RS256,claims)))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
    }
    @Test void rejectsWrongAlgorithmKeyUnknownKidMissingKidAndUnsignedTokens() throws Exception {
        var stranger=new RSAKeyGenerator(2048).keyID("key-1").generate();
        for(String token:List.of(signed(key,"key-1",JWSAlgorithm.RS512,claims().build()),
            signed(stranger,"key-1",JWSAlgorithm.RS256,claims().build()),
            signed(key,"unknown",JWSAlgorithm.RS256,claims().build()),signed(key,null,JWSAlgorithm.RS256,claims().build()),
            new PlainJWT(claims().build()).serialize())) {
            assertThatThrownBy(()->trust.decoder().decode(token)).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
        }
        var confusion=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key-1").build(),claims().build());
        confusion.sign(new MACSigner(key.getModulus().decode()));
        assertThatThrownBy(()->trust.decoder().decode(confusion.serialize())).isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }
    @Test void rejectsPrivateWeakDuplicateAndDevelopmentPublicKeysInDeployment() throws Exception {
        assertThatThrownBy(()->trust(List.of(key,key.toPublicJWK()))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->trust(List.of(key.toPublicJWK(),key.toPublicJWK()))).isInstanceOf(IllegalStateException.class);
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(1024);
        var weak=(java.security.interfaces.RSAPublicKey)generator.generateKeyPair().getPublic();
        assertThatThrownBy(()->trust(List.of(new RSAKey.Builder(weak).keyID("weak").build())))
            .isInstanceOf(IllegalStateException.class);
        String json=new String(getClass().getResourceAsStream("/security/local-public.jwks.json").readAllBytes());
        RSAKey local=(RSAKey)JWKSet.parse(json).getKeys().getFirst();
        assertThatThrownBy(()->trust(List.of(new RSAKey.Builder(local).keyID("disguised-production-id").build())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Development");
    }
}
