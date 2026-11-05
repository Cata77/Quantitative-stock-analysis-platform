# P2s — publicarea în Python: reproducerea publicației `bead056b`, 2026-11-05

Decizia utilizatorului din 2026-11-05: modelul este calculat și publicat numai în Python.
Condiția înaintea eliminării formulei Java este reproducerea identică a ultimei
publicații Java, `bead056b-eb58-4e18-aa5b-6e7815d6f8e9` (model 1.5.0, pregătire
`canonical-preparation-v4`, 518 = 515 + 3).

Pachetul nou [`quant_research.publication`](../../../research-engine/src/quant_research/publication)
reproduce etapele publicării Java:

| Etapă | Modul | Rezultat pe `bead056b` |
|---|---|---|
| Cererea (`ScoringInputRequest`) și validarea cutoffurilor | `inputs.py` | aceeași cerere; cheia logică a runului identică (`654df803…`) |
| Secțiunea canonică (aceeași interogare `scoring-inputs.sql`) | `inputs.py` | identică octet cu octet (118.264.528 octeți) |
| Dovezile de acoperire a colectării | `document.py` | identice, cu fusul orar al sesiunii Java |
| Pregătirea (`canonical-preparation-v4`) | `document.py` | identică octet cu octet |
| Documentul complet și SHA-256 | `document.py`, `canonical_json.py` | identic: `94200f16…164d1c`, 127.311.670 octeți |
| Scorurile modelului 1.5.0 și rândurile persistate | `persist.py`, `compare.py` | identice exact în toate tabelele: 518 lineage, 21.352 surse, 518 scoruri, 2.984 factori, 1.558 excluderi |

Fișiere: [bead056b-input-document.json](bead056b-input-document.json) și
[bead056b-result-tables.json](bead056b-result-tables.json), regenerate după
reformatarea finală a codului. Ambele verificări sunt numai citire (`READ ONLY`,
rolul `quant_scoring`); nu s-a scris nimic în DB.

## Constatări

- Semantica numerică Java este reprodusă explicit: valorile faptelor se adună ca
  `double`, câmpurile de piață rămân zecimale, iar `double`-urile se scriu ca
  `Double.toString` (`7.164E9`). Pregătirea Python existentă nu a fost modificată;
  adaptarea este în stratul de publicare.
- Documentul Java depindea de fusul orar al JVM-ului: dovezile de acoperire
  conțin timestamp-uri formatate de PostgreSQL în fusul sesiunii (`Europe/Bucharest`
  pe mașina de publicare). Publicațiile Python fixează sesiunea în UTC. Reproducerea
  runurilor vechi primește fusul original (`--session-time-zone Europe/Bucharest`).
- Interogările SQL (`scoring-inputs.sql`, `current-collection-outcomes.sql`) sunt
  aceleași fișiere ca în Java. Se mută în `research-engine` odată cu eliminarea
  formulei Java.

## Reproducere

```powershell
$env:QUANT_DB_DSN = '<dsn pentru rolul quant_scoring>'   # nu se salvează în fișiere
.\research-engine\.venv\Scripts\python.exe -m quant_research.publication.reproduce --run-id bead056b-eb58-4e18-aa5b-6e7815d6f8e9 --session-time-zone Europe/Bucharest
.\research-engine\.venv\Scripts\python.exe -m quant_research.publication.compare --run-id bead056b-eb58-4e18-aa5b-6e7815d6f8e9
```

Fiecare comandă durează ~4 minute pe baza locală. Testele unitare sunt în
[test_publication.py](../../../research-engine/tests/test_publication.py). Suita
Python are 269 de teste trecute, iar Ruff e curat.

## Prima publicație Python, pe o copie izolată

`publish.py` reproduce ciclul de viață Java în tranzacții separate:
1. certificarea și înregistrarea runului după cheia logică;
2. starea `RUNNING`;
3. înghețarea documentului de input, o singură dată;
4. scorarea, scrierea în loturi și publicarea atomică.

Un run publicat nu se mai atinge, iar o eroare marchează încercarea `FAILED`.
`certification.py` scorează fixture-ul 1.5 și îl compară cu `expected.json`
(toleranță 1e-9) înainte de orice publicare (`python-flexible-1.5.0`).
`requests.py` construiește cererea pentru snapshotul curent
(port al `calculateCurrent`). Comanda `cli` are operațiile `current`,
`republish` (refuzată pe `quant_platform`) și `compare-runs`.

Verificarea s-a făcut fără să se atingă baza principală. Pașii:
1. Copia `quant_p2s_verify` a fost restaurată cu `infrastructure/backup/restore.sh`,
   din backupul verificat din 2026-11-05.
2. Pe copie s-a publicat din Python snapshotul din 2026-10-30, cu cutoff
   `2026-11-01T21:00:00Z`. Runul Java `bead056b` are cutoff 20:00. Între cele două
   ore nu există observații, filinguri, simboluri, acțiuni corporative sau revizuiri.
3. Triggerele DB de timing și de publicare au acceptat runul Python
   `26f3c95a-bd26-4139-acba-fe543ae638a2`.

| Comparație cu `bead056b` | Rezultat |
|---|---|
| Numărători (518 / 515 / 3), model | identice |
| Coloanele scorurilor: scoruri, contribuții, compozit, percentilă, rang, metrici | identice la toate cele 518 |
| `output` al scorurilor | diferă numai prin `available_at`/`observed_at` ale inputurilor de piață, egale cu cutoffului |
| Lineage | identic la toate cele 518 după înlocuirea cutoffului; timestamp-ul maxim diferă cu 0 sau 3600 s |
| Factori (2.984), surse (21.352), excluderi (1.558) | identice |

Fișiere: [python-publication-compare-runs.json](python-publication-compare-runs.json) și
[python-publication-cutoff-diff.json](python-publication-cutoff-diff.json).

**Incident de memorie.** La prima încercare, procesul PostgreSQL care scria
rezultatele a fost oprit de sistem (`signal 9`). VM-ul Docker are 7,5 GB, iar
containerele ocupau ~5 GB. PostgreSQL a repornit automat, cu recuperare din WAL.
Baza principală a fost verificată după incident: `bead056b` este neschimbată, cu
același SHA și 518 rânduri, și nu există runuri noi. Runul Python a rămas `RUNNING`,
cu inputurile înghețate. După oprirea temporară a Elasticsearch, search-indexer,
Kafka, Zookeeper și scoring-service, reluarea a continuat de la ultima etapă
și a publicat. Containerele au fost repornite. Concluzie pentru P7: publicarea
are nevoie de memorie suplimentară; simplificarea din P2s reduce containerele
care o concurează.

## Testele de integrare portate

`research-engine/tests/integration/` portează testele Java
`ScoringInputIntegrationTest` (18) și `ScoringRunIntegrationTest` (21, pe modelul 1.5).
`conftest.py` pornește un container `timescale/timescaledb:2.29.1-pg18` temporar și
aplică migrările în ordinea Flyway, cu `search_path` `operations, public`, într-o bază
șablon. Fiecare test primește o copie nouă a acesteia, iar containerul se șterge la final.
Testele rulează numai cu `QUANT_DB_TESTS=1`; altfel sunt sărite.

```powershell
$env:QUANT_DB_TESTS='1'; .\research-engine\.venv\Scripts\python.exe -m pytest research-engine/tests
```

Rezultat la 2026-11-05: 311 teste trecute (272 existente + 39 noi), în ~90 de secunde.

Diferențe față de Java, intenționate:
- testele parametrizate pe modelele Java 1.3/1.4 rulează pe 1.5, singurul model
  publicat din Python;
- numărătorile scorurilor se compară cu raportul modelului Python, nu cu valorile
  fixe ale modelului 1.2;
- testul de excluderi folosește un motiv blocant explicit, deoarece 1.5 scorează
  toate rândurile fixture-ului.

Nu s-au portat: aserțiunile pe `profiles-v1`, deoarece publicațiile vechi se reproduc
din documentul stocat, și testul de plan de execuție la 600 de instrumente.

## Eliminarea formulei Java (2026-11-05)

- **Arhivă:** `research-engine/fixtures/model-v1.5-java-final-archive/` conține
  sursele, testele și resursele Java eliminate, inclusiv `FlexibleModel.java` și
  `PreparationV4.java`, care nu fuseseră niciodată comise. `archive.json` are SHA-256
  pentru 48 de fișiere: 29 copiate, 19 identice cu fixture-uri existente (este
  indicată calea lor).
- **Interogările SQL** sunt în `research-engine/src/quant_research/publication/sql/`
  (mutate cu `git mv`). Copia `profiles-v1` rămâne în arhiva 1.2.
- **Freeze-ul modelului:** revizia `relocation` mută 26 de căi spre arhivă sau
  noua locație. Toate hash-urile de fișier sunt neschimbate, iar `verify_freeze` trece.
- **`scoring-service`** păstrează ingestia, datele canonice și DLQ. Comenzile de
  scorare, configurația `scoring.model.*` și variabilele de compose aferente au fost
  eliminate.
- **Verificări:**
  - `:scoring-service:compileTestJava`, plus `ScoringApplicationTest` și testele
    unitare rămase (10), au trecut.
  - Suita Python cu testele DB: 311 teste trecute.
  - Validarea Compose a trecut.
  - Testele de integrare Java rămase (Kafka + PostgreSQL în Testcontainers) nu au
    rulat local, din cauza memoriei VM-ului Docker. Codul lor nu a fost modificat;
    rulează în CI.

## Căutarea în PostgreSQL (2026-11-05)

- **Implementare:** `CompanySearchRepository` construiește aceleași documente ca
  vechiul indexer: instrumentele active, simbolul și sectorul curente, scorul ultimei
  publicații complete. Filtrarea și relevanța se fac direct în SQL. Au fost eliminate
  `SearchRebuildService`, `SearchRebuildJob`, clientul Elasticsearch, dependențele lui
  (lockfile actualizat) și serviciile `elasticsearch`/`search-indexer` din compose.
- **Teste:** `:screener-service:test` are 27 de teste, toate trecute. Sunt 3 teste noi
  de integrare pentru căutare: publicația curentă, ordinea simbol/nume, toate
  cuvintele, paginarea, wildcard-uri literale și modificări vizibile imediat.
- **Verificare prin Gateway pe date reale:** `AAPL` găsește Apple cu relevanță 10;
  `apple` găsește AAPL; `bank of` găsește BAC; `micro` dă 6 rezultate; `zzzz` dă 0.
  Toate rezultatele poartă rangul publicației `bead056b`.
- **Incident:** după repornirea Docker Desktop, portul 18080 de pe Windows răspundea
  cu un server Jetty străin. Repornirea containerului Gateway a refăcut redirecționarea.

## Eliminarea Kafka, etapa A (2026-11-05)

- **Înainte:** `outbox_events` avea 1.294.435 de evenimente `PUBLISHED`, toate
  prezente în inbox-ul `scoring-service-v2`. Nu existau evenimente în tranzit, deci
  eliminarea nu a pierdut date.
- **Livrare:** `OutboxDeliveryWorker` (Scoring) preia evenimentele cu lease și
  `SKIP LOCKED`, în ordine pe instrument, prin același `DurableObservationProcessor`
  și același inbox. Confirmă cu `PUBLISHED`, partiția logică 0 și un offset surogat.
  Coloanele `broker_*` și numele `kafka_inbox` au rămas din schemă, fără migrare de
  schemă. DLQ-ul e doar în DB; replay-ul readuce evenimentul la `PENDING`.
- **Permisiuni:** `quant_scoring` a primit `UPDATE` pe outbox, după backupul verificat
  `build/p2s-kafka-20261105/backup/`. Aplicarea s-a făcut prin serviciul compose
  `database-migrations`: după repornirea Docker Desktop, portul 6543 de pe host nu mai
  redirecționa corect.
- **Teste:** `:scoring-service:test` 52, `:market-data-producer:test` 47,
  `:database-migrations:test` 19, toate trecute, acum numai cu PostgreSQL.
  `DurableDeliveryIntegrationTest` acoperă:
  - livrarea unică;
  - crash după commit și relivrare;
  - lease-uri neîmpărțite;
  - ordinea pe instrument;
  - retry limitat, DLQ și replay auditat;
  - carantina pentru prețuri invalide și hash-uri modificate.
  Testul `UniverseSnapshotImporterIntegrationTest` avea hardcodat 24 de migrări (eșec
  preexistent) și verifică acum că nu rămân migrări neaplicate.
- **Containere:** după reconstruirea `scoring-service` și eliminarea `kafka`/`zookeeper`
  rulează PostgreSQL, Scoring, Screener, Gateway și Auth. Volumele `kafka-data`,
  `zookeeper-data` și `zookeeper-log` sunt păstrate.

## Curățări mici (2026-11-05)

- **Arhivă:** 9 module de audit P0/P2 și 8 fișiere de test au fost mutate în
  `research-engine/archive/p0_p2_audits/`. Rulează 51 de teste, inclusiv în CI.
  Suita principală are acum 221 de teste trecute și 39 sărite (DB).
- **V025:** elimină `operations.search_rebuild_checkpoints`. Singurul rând local, păstrat aici:
  `{"alias_name":"companies-read","schema_version":1,"index_name":"companies-read-v1-949233f4-a65d-4bd5-8023-08e7ccb8d50c","content_sha256":"3f9aee7b7373e1db5c91de7b8c679c5d45aa4a500aec5edbb0eb83bb666d5607","document_count":520,"source_as_of":"2026-11-02T13:52:36.404037+00:00","completed_at":"2026-11-02T13:52:38.869216+00:00"}`.
  Backupul verificat este în `build/p2s-cleanup-20261105/backup/`.
- **Rol:** `quant_search` nu mai are drepturi (`R__security_grants`) și a fost șters local de
  administrator. Avea 0 conexiuni.
- **Scoring:** indexarea Elasticsearch dezactivată a fost eliminată. Scoring trece 52 de teste.
- **Teste:** migrările trec (19, cu numărul de migrări actualizat la 29), la fel Screener,
  producer, observability și testele Python DB (39). Scoring și Screener au fost
  reconstruite; readiness-ul lor e `UP`.

## Ce urmează

Prima publicație Python pe baza principală, la următoarea dată cu date colectate.
Apoi pașii P2s rămași: etapa B (procesarea canonică în colector, fără
`scoring-service`) și un singur backend web.
