# Web Backend

Singurul backend HTTP al aplicației (P2s, 2026-11-06). Înlocuiește Gateway, Auth,
Portfolio și Screener, care erau patru procese separate. Punctul de intrare rămâne
`http://127.0.0.1:18080` în Docker Compose (8080 în container), deci clienții și
[contractele API](ENDPOINTS.md) nu se schimbă.

Modulele vechi sunt păstrate ca pachete în `web-backend`:

| Pachet | Rol | Documentație |
|---|---|---|
| `com.quantplatform.auth` | Conturi, login, emiterea JWT, `GET /auth/jwks` | [Auth](AUTH-SERVICE.md) |
| `com.quantplatform.portfolio` | Deținerile utilizatorului | [Portfolio](PORTFOLIO-SERVICE.md) |
| `com.quantplatform.screener` | Clasamente, căutare, date financiare | [Screener](SCREENER-SERVICE.md) |
| `com.quantplatform.web` | Aplicația, regulile de acces și CORS | acest document |

Fiecare pachet își păstrează tratarea erorilor (`@RestControllerAdvice` limitat la
pachetul său), deci răspunsurile de eroare au aceeași formă ca înainte.

## Flow

1. Cererea trece prin CORS și prin validarea JWT, făcută o singură dată în proces.
2. Regula rutei cere permisiunea corespunzătoare:

| Rută | Acces |
|---|---|
| `/auth/register`, `/auth/login`, `/auth/jwks`, `/actuator/health/**` | Public |
| `/screener/**` | `research:read` |
| `/portfolio`, `/portfolio/**` | `portfolio:write` |
| `/actuator/prometheus` | `operations:read` |
| orice altă rută (inclusiv `/internal/**`) | refuzată |

3. Controllerul pachetului răspunde direct; nu mai există un salt prin rețea.

Tokenurile sunt RS256, validate cu chei publice fixate în configurație, issuer,
audience și expirare (`security-common`). Portfolio citește utilizatorul din `sub`;
header-ele `X-Authenticated-*` trimise de client nu au niciun efect.

Eliminate față de Gateway, ca inutile pentru un singur utilizator local: limita de
100 de cereri/minut per client (răspunsul 429) și propagarea identității prin
header între servicii. Expunerea publică, cu TLS și eventual limitare, rămâne în P7.
Autentificarea rămâne obligatorie.

## Configurare și operare

| Variabilă | Rol |
|---|---|
| `WEB_DB_URL`, `WEB_DB_USER` (`quant_web`), `WEB_DB_PASSWORD` | Conexiunea DB |
| `JWT_PRIVATE_KEY`, `JWT_KEY_ID` | Cheia de semnare a tokenurilor (numai aici) |
| `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_PUBLIC_JWKS` | Validarea tokenurilor |
| `JWT_EXPIRATION` | Durata tokenului, implicit 15 minute, maximum o oră |
| `WEB_ALLOWED_ORIGINS` | Originile browser permise; gol = fără acces cross-origin |
| `WEB_HOST_PORT` | Portul host; dacă lipsește se folosește `GATEWAY_HOST_PORT`, apoi 18080 |

Rolul DB `quant_web` (V026) scrie numai `public.users` (fără ștergere) și
`public.portfolios`, și citește publicațiile, referințele, barele zilnice ajustate
(RSI) și faptele/filingurile/răspunsurile SEC necesare pentru EBITDA. Nu are acces la
outbox, inbox sau la datele de colectare. Vezi [roluri](DATABASE-MIGRATIONS.md).

Readiness-ul verifică `public.users`, `public.portfolios` și `research.stock_scores`.
Metricile Prometheus de publicație (`quant_publication_*`, acoperire, excluderi)
sunt expuse de acest proces.

Surse: [aplicația](../web-backend/src/main/java/com/quantplatform/web),
[setări](../web-backend/src/main/resources/application.yml),
[catalog API](ENDPOINTS.md), [flow general](Application.md).
