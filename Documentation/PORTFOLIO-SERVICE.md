# Portfolio Service

Gestionează deținerile declarate de fiecare utilizator: simbol, cantitate, preț de
intrare și data achiziției. Port Docker local: **18083** (8083 în container).

## Flow

1. Gateway transmite cererea și JWT-ul către Portfolio.
2. Serviciul verifică tokenul și permisiunea `portfolio:write`.
3. Extrage UUID-ul utilizatorului din `sub` și operează numai pe deținerile sale.
4. Salvează modificarea în `public.portfolios` și returnează rezultatul.

Lista este ordonată după data achiziției, descrescător. Tickerul este normalizat
în majuscule. ID-ul unei dețineri este un UUID, diferit de ticker și de instrumentId
din clasament. O deținere absentă sau aparținând altui utilizator produce 404.

## Operații și date

| Operație | Rută |
|---|---|
| Listare / creare | `GET /portfolio`, `POST /portfolio` |
| Citire / înlocuire / ștergere | `GET`, `PUT`, `DELETE /portfolio/{holdingId}` |

`public.portfolios.user_id` leagă deținerea de `public.users.id`.
La creare și actualizare se trimit toate câmpurile:
`ticker`, `quantity`, `entryPrice`, `purchasedAt`.
Răspunsul include aceleași câmpuri plus `id`; ștergerea reușită răspunde cu 204.

Modulul este o evidență CRUD a deținerilor. Nu calculează profit/pierdere și nu
plasează ordine. Conexiunea DB folosește rolul `quant_portfolio`.

Vezi [inputuri și exemple](ENDPOINTS.md) și
[controllerul](../portfolio-service/src/main/java/com/quantplatform/portfolio/api/PortfolioController.java).
