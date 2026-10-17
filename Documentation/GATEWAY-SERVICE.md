# Gateway Service

Punctul de intrare în API este `http://localhost:18080` în Docker Compose.
Serviciul ascultă pe 8080 în container și folosește Spring Cloud Gateway / WebFlux.

## Flow

1. Primește cererea și aplică regulile CORS, limita de cereri și verificarea JWT.
2. Cere permisiunea corespunzătoare rutei.
3. Direcționează cererea spre Auth, Screener sau Portfolio și întoarce răspunsul.

| Rută | Destinație | Acces |
|---|---|---|
| `/auth/register`, `/auth/login`, `/auth/jwks` | Auth | Public |
| `/screener/**` | Screener | `research:read` |
| `/portfolio`, `/portfolio/**` | Portfolio | `portfolio:write` |

Tokenurile sunt RS256, validate cu chei publice fixate în configurație, issuer,
audience și expirare. Header-ele `X-Authenticated-*` și cele de forwarding trimise
de client sunt eliminate; serviciile destinație verifică și ele JWT-ul.
Limita implicită este 100 de cereri/minut per adresă client; depășirea produce 429.

## Configurare și operare

`AUTH_SERVICE_URI`, `SCREENER_SERVICE_URI` și `PORTFOLIO_SERVICE_URI` definesc
destinațiile. `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_JWKS` și
`GATEWAY_ALLOWED_ORIGINS` configurează securitatea și accesul din browser.

Gateway nu are tabele proprii. Health-ul său nu confirmă automat sănătatea tuturor
serviciilor din spate. Nu expune ingestia sau comenzi de scoring prin rutare publică.

Surse: [rute și setări](../gateway-service/src/main/resources/application.yml),
[catalog API](ENDPOINTS.md), [flow general](Application.md).
