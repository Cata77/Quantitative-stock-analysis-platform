# Screener Service

Expune clasamentele publicate și căutarea de companii. Port local **18082**
(8082 în container); prin Gateway se accesează pe **18080**, cu `research:read`.

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

## Căutare și indexer

`GET /screener/search?q=...` caută după simbol, nume, sector și industrie.
Returnează identificatorul stabil `instrumentId` și informații curente.
Descrierea companiei poate lipsi; nu este completată artificial.

Procesul Docker **search-indexer** folosește același modul, cu rolul DB
`quant_search`. Citește PostgreSQL, construiește o generație Elasticsearch,
o verifică și mută aliasul `companies-read` numai după finalizare.
Screener API folosește rolul `quant_screener`.

`GET /screener/search/status` arată ultimul checkpoint complet sau `NOT_BUILT`;
nu este o verificare live a Elasticsearch. O căutare indisponibilă răspunde cu 503.
Clasamentele pot funcționa în continuare deoarece citesc PostgreSQL.

Configurare: [application.yml](../screener-service/src/main/resources/application.yml).
API și toate filtrele: [ENDPOINTS.md](ENDPOINTS.md).
