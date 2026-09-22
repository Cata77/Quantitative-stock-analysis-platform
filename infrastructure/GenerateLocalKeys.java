import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
/** Creates explicitly insecure local-development fixtures, never deployment keys. */
class GenerateLocalKeys {
    static String b64(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes[0]==0?java.util.Arrays.copyOfRange(bytes,1,bytes.length):bytes); }
    public static void main(String[] args) throws Exception {
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);var keys=generator.generateKeyPair();
        var pub=(RSAPublicKey)keys.getPublic();
        String jwks="{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"local-development-only\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""+b64(pub.getModulus().toByteArray())+"\",\"e\":\""+b64(pub.getPublicExponent().toByteArray())+"\"}]}";
        Path publicPath=Path.of("security-common/src/main/resources/security/local-public.jwks.json");Files.createDirectories(publicPath.getParent());Files.writeString(publicPath,jwks);
        String pem="-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(keys.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n";
        for(String location:java.util.List.of("auth-service/src/main/resources/security/local-private.pem","security-common/src/testFixtures/resources/security/test-private.pem")) {
            Path path=Path.of(location);Files.createDirectories(path.getParent());Files.writeString(path,pem);
        }
    }
}
