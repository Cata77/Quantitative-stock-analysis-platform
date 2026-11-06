# Market Data Producer (colectorul)

Colectează prețuri zilnice, acțiuni corporative și raportări financiare pentru
universul datat S&P 500 / Nasdaq-100 și, din P2s (etapa B, 2026-11-06), le transformă
în date canonice în același proces. Port local: **18084** (8084 în container).

## Flow

1. Încarcă identitățile, listele datate ale indicilor și calendarul de tranzacționare.
2. Planifică în PostgreSQL colectările lipsă și reia lucrările întrerupte.
3. Cere prețuri/calendar/acțiuni corporative de la Alpaca și raportări de la SEC.
4. Într-o singură tranzacție pe pagină de furnizor: salvează sursa și checksumul,
   jurnalul de observații (`operations.outbox_events`), checkpointul și **datele
   canonice** (inbox, prețuri, acțiuni corporative, filinguri, profiluri).
5. Reconciliază acoperirea (`operations.reconcile_ingestion_coverage`): un element
   devine `COMPLETE` numai când toate observațiile lui sunt în datele canonice.

Prețurile brute servesc lichidității și capitalizării; seria ajustată servește
momentumului și păstrează data reală a ajustării. Reviziile se adaugă cu proveniență.
Raportările SEC păstrează datele de publicare și observare; valorile lipsă sau
profilurile nesuportate rămân explicite. Suplimentele FFIEC se pot importa din
fișier și manifest verificat, dar nu completează toate profilurile financiare.

## Procesarea canonică (fostul `scoring-service`)

Până la etapa B, un al doilea proces (`scoring-service`) prelua evenimentele din outbox
și scria datele canonice. Acum codul lui este pachetul
[`canonical`](../market-data-producer/src/main/java/com/quantplatform/marketdata/canonical)
al colectorului, iar garanțiile au rămas:

- **Idempotență.** Inbox-ul (`operations.kafka_inbox`, nume istoric) cu același nume de
  consumator, `scoring-service-v2`, deduplică după cheia observației. O pagină reluată
  sau o observație identică nu produce a doua scriere; o corecție se adaugă ca revizie.
- **Atomicitate.** Pagina, jurnalul și efectul canonic se confirmă împreună
  (`OutboxDeliveryWorker.deliverStaged`). O eroare tranzitorie (de exemplu DB) anulează
  toată pagina; elementul trece în `WAITING_RETRY`, cu încercarea auditată în
  `operations.ingestion_attempts`, și se reia de la checkpoint.
- **Carantină.** O observație invalidă (monedă, calendar, identitate, hash) se izolează
  printr-un savepoint: `operations.dead_letter_records` păstrează octeții, hashul și
  motivul, evenimentul devine `FAILED_TERMINAL`, iar restul paginii se confirmă.
  Elementul rămâne `STAGED` și nu certifică acoperirea.
- **Ordine pe instrument.** Dacă un eveniment anterior al aceluiași instrument este
  încă `PENDING`/în retry, cel nou nu se procesează imediat; bucla de livrare
  (`canonical.delivery-*`) le preia în ordinea creării, cu lease și `SKIP LOCKED`.
  Bucla tratează numai ce a rămas `PENDING`: evenimente de dinaintea etapei B și
  replay-uri auditate.

Proiecția veche `public.market_bars` / `public.fundamental_snapshots` (citită doar de
CLI-ul de cercetare vechi) este scrisă tot aici, în aceeași tranzacție.
Calculatorul demonstrativ Java care scria `public.factor_scores` (fără scheduler din
P2s) a fost eliminat; scorurile se publică numai din [Python](SCORE-PUBLICATION.md).

| `CANONICAL_OPERATION` / `--canonical.command.operation=` | Input suplimentar |
|---|---|
| `serve` (implicit) | — |
| `dlq-inspect` | `--canonical.command.limit=20` |
| `dlq-replay` | `--canonical.command.dead-letter-id`, `--canonical.command.replay-id` (UUID stabil), `--canonical.command.operator`, `--canonical.command.reason` |

O comandă DLQ rulează o dată, fără colectare și fără bucla de livrare, apoi se oprește.
Replay-ul readuce evenimentul la `PENDING`, auditat în `operations.dead_letter_replays`;
bucla de livrare a unui colector pornit îl procesează.

## Configurare și import

Colectarea este activată prin `MARKET_DATA_ENABLED`; prețurile folosesc
`ALPACA_API_KEY_ID`, `ALPACA_API_SECRET_KEY` și feedul `sip`.
`DAILY_PRICE_RAW_ENABLED` și `DAILY_PRICE_ADJUSTED_ENABLED` selectează independent
seriile brute și ajustate; ambele sunt implicit `true`. Pentru recuperarea numai
a istoricului Momentum se setează prima la `false`, a doua la `true`.
Dacă se cere colectarea prețurilor, dezactivarea ambelor este respinsă înainte
de accesul la DB/furnizor. Pentru a opri colectarea prețurilor se folosesc
`MARKET_DATA_LATEST_BARS_ENABLED=false` și `MARKET_DATA_BACKFILL_ENABLED=false`.
SEC folosește `SEC_FUNDAMENTALS_ENABLED` și un `SEC_USER_AGENT` cu identitate/contact real.
Versiunea `SEC_MAPPING_VERSION` trebuie aliniată cu `--mapping-version` al publicării Python
(`SCORING_MODEL_MAPPING_VERSION` în `.env`);
publicația locală din octombrie folosește `sec-us-gaap-v2`.

Fișierele de referință stau în [infrastructure/reference-data](../infrastructure/reference-data)
și sunt montate în container la `/reference-data`.
[CSV-ul exemplu](../infrastructure/reference-data/snapshot-template.csv) include
`symbol`, `legal_name`, `cik`, `exchange_mic`; fiecare instrument trebuie să aibă
CIK sau FIGI după îmbogățire. Pentru mai multe clase ale aceleiași companii se
marchează exact o clasă `primary_liquid_class=true`.
Importul folosește setările `REFERENCE_DATA_IMPORT_*` din
[.env.example](../infrastructure/.env.example) și este idempotent.

## Moduri de lucru

| Argument CLI `--ingestion.mode=` | Utilizare |
|---|---|
| `catch-up-and-serve` | Recuperează datele restante și continuă să ruleze |
| `sync-and-exit` | Reconciliază și se oprește |
| `backfill` | Interval explicit: `--ingestion.start-date`, `--ingestion.end-date` |
| `force-refresh` | Recolectare auditată; cere interval, `--ingestion.request-id`, `--ingestion.reason` |

Argumentele se dau JAR-ului producerului; `INGESTION_MODE` este alternativa din mediu.
Datele canonice se scriu în timpul colectării; nu mai este nevoie de un al doilea proces.
Retry-ul reutilizează identitatea lucrării; nu se resetează manual starea ca să pară completă.

Epuizarea încercărilor unui element nu abandonează sesiunile încă recuperabile:
runtime-ul continuă până la terminarea acestora sau până la timeout. La final,
erorile persistente produc în continuare eșec. `MISSING_SESSION` păstrează
răspunsurile furnizorului și checkpointul final; nu echivalează cu o eroare HTTP
sau cu o pagină neterminată și nu dovedește singur cauza economică a absenței.

La oprirea reconcilierii, logul include `failureType`, `causeType` și `sqlState`
(când cauza este SQL), fără textul excepției sau payloadul furnizorului.
Această clasificare permite diagnosticarea unui CLI oprit fără expunerea
credentialelor; politica de retry și stările persistate rămân neschimbate.

Înregistrarea furnizorului, datasetului și configurației de job este separată
în `IngestionJobRegistry`. Pentru înregistrări existente folosește
`INSERT ... ON CONFLICT DO NOTHING` și citirea ID-ului într-o instrucțiune
următoare, în aceeași tranzacție. Evită actualizările fără modificări care
produceau lockuri în colectarea paralelă; hashul configurației și identitatea
joburilor rămân aceleași. Testele acoperă concurența, cititorii FK și rollbackul.

`GET /internal/ingestion/status` raportează progresul, problemele și ultima sesiune
acoperită. Cere `operations:read` și se accesează direct pe portul producerului.
Un container oprit nu mai colectează date noi, dar datele existente rămân în PostgreSQL.

Tabele: `reference.*`, `operations.ingestion_*`, `operations.source_artifacts`,
`operations.outbox_events`, `operations.kafka_inbox`, `operations.dead_letter_*`,
`operations.data_coverage`, `operations.data_watermarks`, `market_data.*`, `fundamentals.*`.
Rolul DB este `quant_ingestion` (V026 i-a dat și scrierile canonice ale fostului
`quant_scoring`).
Vezi [API intern](ENDPOINTS.md) și [configurația completă](../market-data-producer/src/main/resources/application.yml).
