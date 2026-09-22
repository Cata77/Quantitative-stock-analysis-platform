package com.quantplatform.auth.api;
import com.quantplatform.security.JwtTrust;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
@RestController
public class PublicKeysController {
    private final JwtTrust trust;
    public PublicKeysController(JwtTrust trust) { this.trust=trust; }
    @GetMapping("/auth/jwks") public Map<String,Object> keys() { return trust.publicKeys().toJSONObject(); }
}
