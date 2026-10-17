# Auth Service

Gestionează conturile și emite tokenuri de acces. Port Docker local: **18081**
(8081 în container); accesul obișnuit se face prin Gateway, pe **18080**.

## Flow

1. `POST /auth/register` validează datele și normalizează username-ul în litere mici.
2. Parola este stocată ca hash BCrypt în `public.users`.
3. `POST /auth/login` verifică parola și întoarce `accessToken`, `tokenType`, `expiresAt`.
4. Clientul trimite `Authorization: Bearer <accessToken>` la cererile protejate.

JWT-ul folosește **RS256**. Implicit expiră după **15 minute** și acordă
`research:read` și `portfolio:write`. Loginul obișnuit nu acordă `operations:read`.
După expirare se face din nou login; nu există endpoint de refresh sau logout.

## Date și configurare

Tabelul `public.users` păstrează UUID-ul utilizatorului, username-ul unic,
hashul parolei și data creării. Username existent: 409; credențiale greșite: 401.

Auth este singurul serviciu cu cheia privată:
`JWT_PRIVATE_KEY` (PEM PKCS8) și `JWT_KEY_ID`. Toate serviciile folosesc
`JWT_ISSUER`, `JWT_AUDIENCE` și `JWT_PUBLIC_JWKS`.
`GET /auth/jwks` returnează numai cheile publice. Configurarea DB folosește rolul
`quant_auth`; parolele și cheile private se păstrează în afara documentației/Git.

Inputurile și exemplele JSON sunt în [ENDPOINTS.md](ENDPOINTS.md).
Surse: [controller](../auth-service/src/main/java/com/quantplatform/auth/api/AuthController.java),
[emiterea tokenurilor](../auth-service/src/main/java/com/quantplatform/auth/security/JwtService.java).
