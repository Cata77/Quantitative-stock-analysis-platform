# Endpointurile aplicației

Baza URL locală, [backend-ul web](WEB-BACKEND.md): **http://127.0.0.1:18080**
(același port și aceleași rute ca vechiul Gateway; P2s, 2026-11-06).
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
| `GET /screener/search/status` | `LIVE` / `POSTGRESQL`: căutarea citește direct baza (fără index) | Fără body sau parametri |
| `GET /screener/instruments/{instrumentId}/financials` | Indicatorii companiei dintr-o publicație (P/E, marje, RSI etc.); cu `view=FULL`, dosarul complet cu proveniență | `instrumentId` în rută și `runId` obligatoriu, ambele UUID din rankings; `view=SUMMARY` (implicit) sau `FULL`; fără body |

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
de 10.000 de rezultate; o pagină dincolo de această fereastră produce 400. Căutarea
cere ca fiecare cuvânt să se potrivească, fără potrivire aproximativă; regulile de
relevanță sunt în [Screener](SCREENER-SERVICE.md#căutare).

Exemple de rute, toate cu Bearer token:

```text
/screener/rankings?universe=SP500&size=20
/screener/rankings?eligibility=ALL&universe=UNION&size=200
/screener/rankings?eligibility=EXCLUDED&size=100
/screener/rankings?sort=CONTRIBUTION&metric=ebit_ev
/screener/search?q=AAPL&page=0&size=20
/screener/rankings?instrumentId=<UUID-din-search>&eligibility=ALL
```

Lista/exportul complet folosește explicit `eligibility=ALL`; toate paginile după
prima trimit `runId` primit. [Utilitarul P1](UNIVERSE-EXPORT.md) implementează acest
flux și verifică exportul fără un endpoint nou. `run.expectedCount`,
`run.scoredCount`, `run.excludedCount` și `run.coverage` sunt totalurile întregii
publicații UNION; `totalElements` și `totalPages` se referă la filtrele cererii.
Scorul compozit, rangul și percentila exclușilor sunt `null`, nu zero. Valorile
parțiale disponibile rămân în `valueScore`, `qualityScore`, `momentumScore` și
`factors`; `exclusions` păstrează stadiul, codul și metrica afectată.

Pentru modelul 1.3, `scoreStatus` este `COMPLETE`, `PARTIAL` sau `UNAVAILABLE`;
`scoreCoverage` conține `normalized_metrics`, `expected_metrics` și
`available_families`, iar `effectiveWeights` conține ponderile efective
`value`, `quality`, `momentum`. Aceste câmpuri sunt `null` pentru publicațiile
anterioare. `eligible=true` în 1.3 include scoruri parțiale; o familie absentă
și contribuția ei rămân `null`. Factorii expun ponderile efectiv utilizate.
Scorurile cu familii diferite nu exprimă aceeași combinație Value/Quality/Momentum;
vezi [regulile formulei flexibile](P2-FLEXIBLE-MODEL.md).

Rangul și percentila nu se recalculează la filtrare: populația lor este formată
din eligibilii publicației UNION. La egalități, rangul ordinal este departajat
prin UUID, iar percentila folosește rangul mediu înainte de rotunjirea scorurilor.

### Date financiare ale unei publicații

`GET /screener/instruments/{instrumentId}/financials?runId={runId}` citește numai
ce a păstrat publicația. Nu combină date curente cu un scor istoric și nu pornește
descărcări de la furnizori. `runId` este obligatoriu și trebuie să fie un run
complet `PUBLISHED`, aceleași condiții ca la rankings.

**`view=SUMMARY` (implicit)** este vederea pentru utilizator. Câmpurile goale sunt
omise. Fiecare indicator este `{value, unit, period}`; dacă lipsește, are doar
`unit` și `reason`. Unitățile sunt `USD`, `shares`, `MULTIPLE` (x), `PERCENT`
(procente, 2 zecimale) și `INDEX`. Indicatorii sunt `DISPLAY_METRICS_V2`:
informativi, nu intră în scor și folosesc numai inputuri acceptate de model,
plus D&A din sursa păstrată a publicației (mai jos). V1 nu avea D&A și raporta
EBITDA mereu lipsă.

| Secțiune | Indicatori |
|---|---|
| `company` | `instrumentId`, `symbol`, `sector`, `profile`, `runId`, `asOfDate`, `financialPeriodEnd`, `filingDate` |
| `valuation` | `marketCap`, `enterpriseValue`, `priceToEarnings`, `priceToSales`, `priceToBook`, `priceToFreeCashFlow`, `evToEbit`, `freeCashFlowYield`, `dividendYield` |
| `profitability` | `grossMargin`, `operatingMargin`, `netMargin`, `returnOnAssets`, `returnOnEquity` |
| `financials` | `revenue`, `grossProfit`, `operatingIncome`, `depreciationAmortization`, `ebitda`, `netIncome`, `operatingCashFlow`, `capex`, `freeCashFlow`, `cash`, `totalDebt`, `netDebt`, `totalAssets`, `sharesOutstanding` |
| `market` | `price`, `rsi14`, `return1y`, `momentum12m1m`, `high52w`, `low52w`, `fromHigh52w`, `volatility1y`, `medianDollarVolume20d` |
| `score` | `eligible`, `status`, `rank`, `rankedInstruments`, `percentile`, `compositeScore`, scorurile familiilor, `exclusionReasons`, `missingMetrics` (metrică → motiv) |

Definiții: P/E = `market_cap / NET_INCOME_TTM` (profitul total TTM, inclusiv
interesele minoritare); P/S, P/B și P/FCF folosesc capitalizarea modelului față de
`REVENUE_TTM`, `COMMON_EQUITY` și `fcf`; EV/EBIT = `ev / ebit` al modelului.
Marjele se raportează la `REVENUE_TTM`; ROA folosește media activelor modelului;
ROE folosește `COMMON_EQUITY`. Un numitor zero sau negativ produce `null` cu motiv
(`NEGATIVE_EARNINGS`, `NON_POSITIVE_DENOMINATOR:...`), nu o valoare negativă
înșelătoare. `return1y` = `ADJUSTED_CLOSE / MOMENTUM_OLD - 1`.
`momentum12m1m` este metrica modelului (t−21 / t−252). Maximul/minimul pe 52 de
săptămâni, distanța față de maxim și volatilitatea anualizată (abaterea standard
a randamentelor logaritmice zilnice × √252) cer 253 de prețuri ajustate consecutive.
`ebitda` = `OPERATING_INCOME_TTM` + `depreciationAmortization`, pe aceleași perioade
(`EBITDA_FROM_SOURCE_V1`, detaliat la `supplemental` în `view=FULL`). Motivele
de lipsă sunt cele ale D&A. Motivele de forma
`COD:MOTIV` provin din inputul sau derivarea respectivă; `MISSING_INPUT:COD`
înseamnă că publicația nu are acel input.

**`view=FULL`** este dosarul de audit, cu secțiuni separate:

| Câmp | Conținut |
|---|---|
| `run` | `id`, `asOfDate`, `marketCutoff`, `knowledgeCutoff`, `publishedAt`, `modelVersion`, `preprocessingVersion`, `mappingVersion`, `timingPolicy` |
| `instrument` | `instrumentId`, `issuerId`, `symbol` la data scorului, `profile`, `sector`, `peerGroup`, `inSp500`, `inNasdaq100`, `financialPeriodEnd`, `filingDate` |
| `score` | `eligible`, `scoreStatus`, `rank`, `percentile`, `compositeScore`, scorurile și contribuțiile familiilor, `coverage`, `effectiveWeights`, `warnings`, `exclusions` (`stage`, `reasonCode`, `affectedMetric`, `detail`) |
| `inputs[]` | Inputurile pregătite ale modelului; vezi tabelul de mai jos |
| `derived[]` | Valori intermediare calculate de model și salvate în publicație: `code` (de ex. `market_cap`, `ev`, `fcf`, `ebit`, `nopat`, `avg_assets`), `value`, `unit`, `formula`, `status`, `reason` (motivul modelului sau `MISSING_INPUT:cod`) |
| `metrics[]` | Metricile modelului: `rawValue` (raport înainte de normalizare), `reason`, valorile transformate/winsorizate, `cohort`, `cohortCount`, limitele, media, abaterea, `zScore`, `clippedScore`, `weight`, `contribution` |
| `technical.rsi` | RSI informativ; nu este input al modelului (`partOfScore=false`) |
| `supplemental.depreciationAmortization` | D&A și EBITDA informative (`partOfScore=false`): `value`, `ebitda`, `periodEnd`, `concept`, `version`, `method`, `components[]`, `status`, `reason` |

Fiecare element din `inputs[]` are:

| Câmp | Valori |
|---|---|
| `code` | Codul canonic, de ex. `REVENUE_TTM`, `CASH`, `TOTAL_ASSETS_PRIOR`, `SHARES_OUTSTANDING`, `RAW_CLOSE` |
| `category` | `INCOME_STATEMENT`, `CASH_FLOW`, `BALANCE_SHEET`, `SHARES`, `MARKET`, `OTHER` |
| `periodType` | `TTM`, `ANNUAL`, `BALANCE_SHEET`, `BALANCE_SHEET_PRIOR_YEAR`, `INSTANT`, `SCORE_DATE_CLOSE`, `CLOSE_21_SESSIONS_BEFORE`, `CLOSE_252_SESSIONS_BEFORE`, `MEDIAN_20_SESSIONS`, `UNCLASSIFIED` |
| `valueKind` | `REPORTED` (un fapt raportat), `DERIVED_TTM` (combinație de perioade raportate), `DERIVED_FROM_REPORTED` (derivare versionată, numită în `derivation`), `MARKET` |
| `value`, `unit` | Valoarea păstrată și unitatea ei (`USD`, `shares`) |
| `periodEnd`, `filedDate`, `availableAt`, `observedAt` | Sfârșitul perioadei sau data bilanțului, data depunerii și momentele de disponibilitate/observare; `availabilityBasis` apare când momentul este o limită superioară conservatoare |
| `sourceIds` | Identificatorii faptelor sau observațiilor folosite |
| `status`, `reason` | `AVAILABLE`; `UNAVAILABLE` cu motiv (de ex. `NOT_REPORTED`, `NO_SELECTED_CANONICAL_FACT`, `TTM_NO_COMPATIBLE_PERIOD_COMBINATION`); `NOT_USED_BY_MODEL` când valoarea există, dar modelul a respins-o (de ex. `INSUFFICIENT_HISTORY`, `FUTURE_INFORMATION`) |

Începutul perioadei TTM nu este păstrat separat: o valoare TTM acoperă 12 luni
care se termină la `periodEnd`. Motivul modelului are prioritate față de diagnosticul
pregătirii. Lipsurile sunt `null` cu motiv, nu zero. `formula` este textul
documentar din `formula_contract` al manifestului publicației; nu este evaluat
de Screener. Este `null` când manifestul nu documentează codul.

`technical.rsi` are `value` (0–100, 4 zecimale), `period=14`, `method`,
`version=RSI_14_WILDER_V1`, `priceBasis=SPLIT_DIVIDEND_ADJUSTED_CLOSE`,
`adjustedDataset`, `adjustmentBasis`, `maximumSessions=253`, `sessionsUsed`,
`firstSession`, `lastSession`, `status` și `reason`. Prețurile sunt închiderile
ajustate din datasetul și vintage-ul publicației, pe ultimele 253 de ședințe XNYS
până la data scorului. Se aplică aceleași filtre de cutoff, de validitate a
instrumentului și de selecție a observației ca la scoring. Fereastra este șirul
continuu de ședințe cu preț care se termină la data scorului; o ședință lipsă îl
întrerupe. Primele 14 variații inițializează mediile prin medie simplă, apoi se
aplică netezirea Wilder. Valoarea depinde ușor de lungimea ferestrei:
`sessionsUsed` o arată. Motivele de lipsă sunt `MISSING_SCORE_DATE_PRICE`,
`INSUFFICIENT_PRICE_HISTORY` (sub 15 prețuri consecutive), `INVALID_PRICE`,
`FLAT_PRICE_WINDOW` și `PRICE_DATASET_NOT_RECORDED`, ultimul pentru runurile
care nu au înregistrat datasetul ajustat.

`supplemental.depreciationAmortization` (`version=EBITDA_FROM_SOURCE_V1`) pornește
de la faptele sursă ale `OPERATING_INCOME_TTM` acceptat de publicație. Combinația
TTM este reconstruită din perioade: un an, patru trimestre sau an + YTD − YTD
anterior. Se acceptă numai dacă reproduce exact valoarea publicată. Pentru fiecare
componentă, D&A se citește din răspunsul SEC companyfacts păstrat în spatele
faptului (urmărind reprocesările până la original). Se folosesc același filing
(`accession`), aceleași date de început/sfârșit și un singur concept us-gaap pentru
toate componentele: `DepreciationDepletionAndAmortization`, apoi
`DepreciationAndAmortization`. Răspunsurile descărcate după cutoff nu se folosesc.
`components[]` are `periodStart`, `periodEnd`, `accession`, `sign`, `value`,
`operatingIncome` și `sourceArtifactId`. Motivele de lipsă:
`OPERATING_INCOME_TTM:<motiv>` sau `MISSING_INPUT:OPERATING_INCOME_TTM`,
`OPERATING_INCOME_FACTS_NOT_FOUND`, `OPERATING_INCOME_PERIODS_UNRESOLVED`,
`SOURCE_ARTIFACT_NOT_RETAINED`, `DEPRECIATION_AMORTIZATION_NOT_REPORTED` (niciun
concept acceptat pe toate perioadele aceluiași filing),
`DEPRECIATION_AMORTIZATION_AMBIGUOUS` și `NEGATIVE_DEPRECIATION_AMORTIZATION`.
Conceptele cu acrețiune, cele numai pentru PP&E și sumele de componente nu sunt
folosite. EBITDA este un indicator de afișare, nu metrică a modelului.

Erori: 400 pentru `runId` lipsă, UUID invalid sau `view` necunoscut; 404 `Publication not found`
pentru un run inexistent, nepublicat sau incomplet; 404 `Instrument is not part
of the publication`. Instrumentele excluse răspund cu 200, iar lipsurile sunt explicite.

```text
/screener/instruments/<instrumentId-din-rankings>/financials?runId=<run.id-din-rankings>
/screener/instruments/<instrumentId>/financials?runId=<runId>&view=FULL
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

Aceste rute se accesează direct pe procesul vizat: backend-ul web (18080) sau colectorul (18084).

| Metodă și rută | Unde | Input și acces | Descriere |
|---|---|---|---|
| `GET /actuator/health` | Backend-ul web sau colectorul | Fără input, public | Stare generală |
| `GET /actuator/health/liveness` | Backend-ul web sau colectorul | Fără input, public | Procesul răspunde |
| `GET /actuator/health/readiness` | Backend-ul web sau colectorul | Fără input, public | Procesul este pregătit conform verificărilor sale |
| `GET /actuator/prometheus` | Backend-ul web sau colectorul | Fără input; Bearer cu `operations:read` | Metrici pentru monitorizare |
| `GET /internal/ingestion/status` | Producer, `http://localhost:18084` | Fără input; Bearer cu `operations:read` | Starea colectării, erori și ultima sesiune acoperită |

Porturi: backend-ul web **18080**, colectorul **18084**. Porturile 18081–18083 și 18085
(Auth, Screener, Portfolio, Scoring) nu mai există din P2s.
Tokenul primit la login nu are `operations:read`; identitatea operațională este
provisionată separat. Un serviciu oprit nu răspunde pe portul său.

Nu există endpoint HTTP pentru lansarea calculului, importul de date sau replay DLQ.
Acestea sunt procese/CLI descrise în documentele modulelor. Limita de 100 de
cereri/minut a vechiului Gateway (răspunsul 429) a fost eliminată în P2s.

## Erori uzuale

| Cod | Semnificație |
|---|---|
| 400 | Input invalid, UUID/enum greșit sau paginare neacceptată |
| 401 | Token lipsă/invalid/expirat sau credențiale de login greșite |
| 403 | Token valid fără permisiunea cerută |
| 404 | Deținere inexistentă sau care nu aparține utilizatorului |
| 409 | Username deja folosit |
| 503 | O dependență necesară cererii este indisponibilă |

Fără un run publicat, rankings răspunde cu `run: null` și
`freshness: UNAVAILABLE`; aceasta nu este o publicație cu univers gol.
[Plan și componente](Application.md).
