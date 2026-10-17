# Endpointurile aplicației

Baza URL locală prin Gateway: **http://localhost:18080**.
Pentru POST/PUT se trimite `Content-Type: application/json`.
Pentru rutele protejate: `Authorization: Bearer <accessToken>`.
Porturile de mai jos sunt valorile implicite din Docker Compose.

## Autentificare — fără token

| Metodă și rută | Descriere | Input | Rezultat |
|---|---|---|---|
| `POST /auth/register` | Creează cont | JSON: `username`, `password` | 201, utilizator cu `id`, `username`, `createdAt` |
| `POST /auth/login` | Autentifică și emite JWT | JSON: `username`, `password` | 200, `accessToken`, `tokenType`, `expiresAt` |
| `GET /auth/jwks` | Cheile publice de verificare JWT | Fără body sau parametri | Setul public JWKS |

La înregistrare, username-ul are 3–50 caractere, numai litere ASCII, cifre,
punct, underscore sau cratimă; parola are 12–72 caractere.
La login, ambele sunt obligatorii, cu maximum 50, respectiv 72 de caractere.
Username-ul se normalizează în litere mici.

Exemplu de body pentru register și apoi login, înlocuind valorile cu ale tale:

```json
{
  "username": "utilizator_demo",
  "password": "inlocuieste-cu-parola-ta"
}
```

Înregistrarea nu emite token; se apelează apoi login. JWT-ul expiră implicit după
15 minute și include `research:read` și `portfolio:write`.
Nu există endpointuri de refresh, logout sau citire `/auth/users/{id}`.

## Screener — token cu research:read

| Metodă și rută | Descriere | Input |
|---|---|---|
| `GET /screener/rankings` | Ultimul clasament complet publicat; scoruri, factori și excluderi | Query opțional, descris mai jos; fără body |
| `GET /screener/search` | Caută instrumente; returnează și `instrumentId` | `q` obligatoriu, nevid, max. 100 caractere; `page=0` (0–100000), `size=20` (1–100) |
| `GET /screener/search/status` | Ultimul checkpoint de reconstruire a căutării sau `NOT_BUILT` | Fără body sau parametri |

### Parametrii rankings

| Parametru | Input acceptat / valoare implicită |
|---|---|
| `asOf` | Dată `YYYY-MM-DD`; implicit azi UTC. Acceptă și timestamp ISO convertit la dată UTC; selectează scoruri cu data cel mult egală cu aceasta |
| `universe` | `UNION` (implicit), `SP500`, `NASDAQ100` |
| `modelVersion` | UUID-ul versiunii din DB, nu textul `1.2.0`; implicit orice versiune compatibilă |
| `runId` | UUID-ul unui run publicat; implicit ultima publicație compatibilă |
| `instrumentId` | UUID-ul instrumentului, de exemplu cel întors de search |
| `profile` | Valoare exactă, de exemplu `GENERAL`; implicit toate |
| `sector`, `peerGroup` | Valori exacte din run; implicit toate |
| `eligibility` | `ELIGIBLE` (implicit), `EXCLUDED`, `ALL` |
| `warning` | Textul exact al avertismentului; implicit fără filtru |
| `sort` | `COMPOSITE` (implicit), `VALUE`, `QUALITY`, `MOMENTUM`, `CONTRIBUTION` |
| `direction` | `DESC` (implicit) sau `ASC` |
| `metric` | Obligatoriu la `sort=CONTRIBUTION`; nume canonic precum `ebit_ev` sau `fcf_yield` |
| `page` | Număr întreg 0–100000; implicit 0 |
| `size` | Număr întreg 1–200; implicit 50 |

Filtrele se aplică după alegerea runului și nu determină căutarea automată a unui
run mai vechi. Reutilizează `runId` la paginare. Search acceptă o fereastră maximă
de 10.000 de rezultate; o pagină dincolo de această fereastră produce 400.

Exemple de rute, toate cu Bearer token:

```text
/screener/rankings?universe=SP500&size=20
/screener/rankings?eligibility=EXCLUDED&size=100
/screener/rankings?sort=CONTRIBUTION&metric=ebit_ev
/screener/search?q=AAPL&page=0&size=20
/screener/rankings?instrumentId=<UUID-din-search>&eligibility=ALL
```

## Portfolio — token cu portfolio:write

| Metodă și rută | Descriere | Input | Rezultat |
|---|---|---|---|
| `GET /portfolio` | Listează deținerile utilizatorului | Fără body sau parametri | 200, listă |
| `POST /portfolio` | Creează deținere | JSON-ul de mai jos | 201, deținere cu `id` |
| `GET /portfolio/{holdingId}` | Citește deținere | `holdingId`: UUID din răspunsul Portfolio | 200, deținere |
| `PUT /portfolio/{holdingId}` | Înlocuiește datele deținerii | UUID în rută și toate câmpurile JSON | 200, deținere actualizată |
| `DELETE /portfolio/{holdingId}` | Șterge deținere | UUID în rută; fără body | 204, fără body |

La POST și PUT toate câmpurile sunt obligatorii:

| Câmp JSON | Regulă |
|---|---|
| `ticker` | 1–10 caractere; începe cu literă/cifră, apoi litere, cifre, punct sau cratimă |
| `quantity` | Număr ≥ 0.0001; max. 11 cifre întregi și 4 zecimale |
| `entryPrice` | Număr ≥ 0.0001; max. 11 cifre întregi și 4 zecimale |
| `purchasedAt` | Instant ISO-8601, de exemplu UTC cu `Z`; în trecut sau prezent |

```json
{
  "ticker": "AAPL",
  "quantity": 10,
  "entryPrice": 180.50,
  "purchasedAt": "2026-09-01T14:00:00Z"
}
```

Utilizatorul este dedus din JWT; nu se trimite `userId`.
`holdingId` este ID-ul deținerii, nu `instrumentId` din Screener.

## Health și operațiuni

Aceste rute se accesează direct pe serviciul vizat. Pe portul 18080 descriu
Gateway; acesta nu le redirecționează către celelalte servicii.

| Metodă și rută | Unde | Input și acces | Descriere |
|---|---|---|---|
| `GET /actuator/health` | Oricare dintre cele 6 servicii HTTP | Fără input, public | Stare generală |
| `GET /actuator/health/liveness` | Oricare dintre cele 6 servicii HTTP | Fără input, public | Procesul răspunde |
| `GET /actuator/health/readiness` | Oricare dintre cele 6 servicii HTTP | Fără input, public | Serviciul este pregătit conform verificărilor sale |
| `GET /actuator/prometheus` | Oricare dintre cele 6 servicii HTTP | Fără input; Bearer cu `operations:read` | Metrici pentru monitorizare |
| `GET /internal/ingestion/status` | Producer, `http://localhost:18084` | Fără input; Bearer cu `operations:read` | Starea colectării, erori și ultima sesiune acoperită |

Porturi directe: Gateway **18080**, Auth **18081**, Screener **18082**,
Portfolio **18083**, Producer **18084**, Scoring **18085**.
Tokenul primit la login nu are `operations:read`; identitatea operațională este
provisionată separat. Un serviciu oprit nu răspunde pe portul său.

Nu există endpoint HTTP pentru lansarea calculului, importul de date, replay DLQ
sau reconstruirea indexului. Acestea sunt procese/CLI descrise în documentele modulelor.

## Erori uzuale

| Cod | Semnificație |
|---|---|
| 400 | Input invalid, UUID/enum greșit sau paginare neacceptată |
| 401 | Token lipsă/invalid/expirat sau credențiale de login greșite |
| 403 | Token valid fără permisiunea cerută |
| 404 | Deținere inexistentă sau care nu aparține utilizatorului |
| 409 | Username deja folosit |
| 429 | Limita de cereri a Gateway a fost depășită |
| 503 | O dependență necesară cererii este indisponibilă |

Fără un run publicat, rankings răspunde cu `run: null` și
`freshness: UNAVAILABLE`; aceasta nu este o publicație cu univers gol.
[Plan și componente](Application.md).
