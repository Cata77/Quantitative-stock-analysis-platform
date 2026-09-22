package com.quantplatform.gateway;
import java.net.*;
import java.util.UUID;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.quantplatform.security.SecurityTokens;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationTest {
    static final HttpServer STUB=start();
    static HttpServer start() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String identity=exchange.getRequestHeaders().getFirst("X-Authenticated-User-Id");
                byte[] body=(identity==null?"public":identity).getBytes();
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });server.start();return server;
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry properties) {
        for(String name:new String[]{"AUTH_SERVICE_URI","PORTFOLIO_SERVICE_URI","SCREENER_SERVICE_URI"})
            properties.add(name,()->"http://127.0.0.1:"+STUB.getAddress().getPort());
    }
    @AfterAll static void close(){STUB.stop(0);}
    @LocalServerPort int port;
    WebTestClient client(){return WebTestClient.bindToServer().baseUrl("http://127.0.0.1:"+port).build();}
    @Test void anonymousAndMalformedTokensCannotReachProtectedRoutes() {
        client().get().uri("/portfolio").header("X-Authenticated-User-Id",UUID.randomUUID().toString()).exchange().expectStatus().isUnauthorized();
        client().get().uri("/screener/rankings").header("Authorization","Bearer invalid").exchange().expectStatus().isUnauthorized();
        client().get().uri("/auth/jwks").exchange().expectStatus().isOk();
    }
    @Test void validTokensPropagateWhileSpoofedIdentityIsReplaced() {
        UUID user=UUID.randomUUID();
        client().get().uri("/portfolio").header("Authorization",SecurityTokens.token(user))
            .header("X-Authenticated-User-Id",UUID.randomUUID().toString()).exchange().expectStatus().isOk()
            .expectBody(String.class).isEqualTo(user.toString());
        client().get().uri("/portfolio").header("Authorization",SecurityTokens.token(user,"research:read"))
            .exchange().expectStatus().isForbidden();
    }
    @Test void corsAllowsOnlyConfiguredOrigins() {
        client().options().uri("/portfolio").header("Origin","http://localhost:3000")
            .header("Access-Control-Request-Method","GET").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Access-Control-Allow-Origin","http://localhost:3000");
        client().options().uri("/portfolio").header("Origin","https://untrusted.example")
            .header("Access-Control-Request-Method","GET").exchange().expectStatus().isForbidden();
    }
}
