# Screener

Expune clasamentele publicate și căutarea de companii. Din P2s este pachetul
`com.quantplatform.screener` al [backend-ului web](WEB-BACKEND.md), pe **18080**,
cu `research:read`.

## Flow pentru rankings

1. Selectează ultimul run complet `PUBLISHED` al candidatului principal, compatibil
   cu data, modelul sau runId cerut.
2. Aplică filtrele asupra acelui run și citește rezultatele din PostgreSQL.
3. Returnează scoruri, factori, excluderi, proveniență, acoperire și metadatele modelului.

`GET /screener/rankings` sortează implicit după scorul compozit descrescător.
Rangul este calculat din scorul nerotunjit și nu se recalculează după filtrare.
Fără publicație: `run: null`, `freshness: UNAVAILABLE`.
Până la 35 de zile, eticheta este `CURRENT`; după aceea, `STALE`.
Această etichetă de actualitate nu reprezintă aprobarea modelului.

Pentru pagini consistente se reutilizează `runId` din prima pagină.
Pentru întregul univers se cere explicit `eligibility=ALL`; clasamentul eligibil
rămâne disponibil cu `eligibility=ELIGIBLE`. [Utilitarul P1](UNIVERSE-EXPORT.md)
generează o listă HTML locală și un export CSV verificat, cu toate paginile fixate
pe aceeași publicație. Totalurile `run.expectedCount`, `scoredCount`,
`excludedCount` și `coverage` descriu reuniunea completă; `totalElements` descrie
rezultatul filtrelor. Rangul și percentila rămân raportate la eligibilii întregii
publicații, inclusiv când se filtrează pe indice, sector sau eligibilitate.

`asOf` filtrează după data scorului; răspunsul păstrează separat momentul real
al publicării. Snapshoturile curente sunt identificate prin `timingPolicy` și
`publicationType`.

## Date financiare ale unei publicații

`GET /screener/instruments/{instrumentId}/financials?runId=...` pornește de la un
rând din rankings și citește numai ce a păstrat acel run:
- inputurile pregătite și proveniența lor, din `research.score_lineage.prepared_input`;
- derivările și motivele modelului, din `research.stock_scores.output`;
- metricile normalizate, din `research.factor_observations`;
- excluderile, din `research.score_exclusions`;
- textul formulelor, din `formula_contract` al manifestului.

Screener nu reface formulele modelului. Calculează local două valori informative,
care nu intră în scor. Prima este RSI-ul `RSI_14_WILDER_V1`, din închiderile ajustate
ale datasetului și vintage-ului publicației. Selecția acestor prețuri reproduce
regulile din `scoring-inputs.sql`. A doua este D&A/EBITDA `EBITDA_FROM_SOURCE_V1`,
din răspunsul SEC păstrat în spatele faptelor `OPERATING_INCOME_TTM` ale publicației,
pe aceleași filinguri și perioade. Contractul complet este în [ENDPOINTS.md](ENDPOINTS.md).
Codul este în [financials](../web-backend/src/main/java/com/quantplatform/screener/financials).

## Comparația în sector (P2b.1)

`GET /screener/sectors/{sector}/comparison?runId=...` explică un sector al unei publicații,
fără să recalculeze scorurile. Separă membrii sectorului (toate profilurile) de grupul de
normalizare (profil GENERAL) și clasează separat rândurile `PRICE_ONLY` (scorate numai pe
momentum). Pentru fiecare metrică arată mediana brută, câte valori au fost normalizate în
sector sau pe fallback și mărimea cohortei; avertizează pentru eșantioane sub 20. Regulile
vin din [auditul P2b.1](evidence/p2b1/README.md); contractul complet este în
[ENDPOINTS.md](ENDPOINTS.md). Codul: [sectors](../web-backend/src/main/java/com/quantplatform/screener/sectors).

## Căutare

`GET /screener/search?q=...` caută după simbol, nume, sector și industrie
(cod SIC), direct în PostgreSQL (P2s, 2026-11-05). Elasticsearch și procesul
`search-indexer` au fost eliminate. Rezultatele sunt instrumentele active la data
cererii, cu simbolul și sectorul curente și cu scorul ultimei publicații complete.
Returnează identificatorul stabil `instrumentId`. Descrierea companiei poate lipsi;
nu este completată artificial.

Fiecare cuvânt din `q` trebuie să se potrivească cu un câmp. Potrivirea nu ține
cont de majuscule, iar `%` și `_` sunt literale. Relevanța (`relevance`) adună,
pe cuvânt, cea mai bună potrivire:

| Potrivire | Puncte |
|---|---:|
| simbol exact | 10 |
| prefix de simbol | 5 |
| începutul numelui | 4 |
| început de cuvânt în nume | 3 |
| nume care conține termenul | 2 |
| sector care conține termenul sau prefix de cod SIC | 2 |

Ordinea este după relevanță, apoi după `instrumentId`. Spre deosebire de
Elasticsearch, nu există potrivire aproximativă (greșeli de tastare). Modificările
din datele canonice sunt vizibile imediat, fără reindexare.

Screener folosește rolul `quant_web` (până la V026: `quant_screener`), cu citire pe schema `research`,
pe referințe și numai pe `market_data.daily_bar_observations` pentru RSI.
Pentru EBITDA citește și `fundamentals.fundamental_facts`, `fundamentals.filings` și
`operations.source_artifacts`, fără drept de scriere.

`GET /screener/search/status` răspunde `{"status":"LIVE","backend":"POSTGRESQL",
"schema_version":1}`: nu mai există un index de construit. Dacă PostgreSQL nu este
disponibil, căutarea și clasamentele răspund cu 503. Tabelul
`operations.search_rebuild_checkpoints` și rolul `quant_search` au fost eliminate
(V025). Readiness-ul backend-ului web verifică și `research.stock_scores`.

Configurare: [application.yml](../web-backend/src/main/resources/application.yml).
API și toate filtrele: [ENDPOINTS.md](ENDPOINTS.md).
