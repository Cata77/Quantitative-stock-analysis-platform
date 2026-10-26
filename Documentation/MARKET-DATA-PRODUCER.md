# Market Data Producer

Colectează prețuri zilnice, acțiuni corporative și raportări financiare pentru
universul datat S&P 500 / Nasdaq-100. Port local: **18084** (8084 în container).

## Flow

1. Încarcă identitățile, listele datate ale indicilor și calendarul de tranzacționare.
2. Planifică în PostgreSQL colectările lipsă și reia lucrările întrerupte.
3. Cere prețuri/calendar/acțiuni corporative de la Alpaca și raportări de la SEC.
4. Salvează sursa, checksumul, progresul și evenimentele în outbox, într-o tranzacție.
5. Relay-ul publică în `market-data-observations-v2`, cu UUID-ul instrumentului drept cheie.
6. Scoring confirmă datele canonice; abia apoi se poate certifica acoperirea completă.

Prețurile brute servesc lichidității și capitalizării; seria ajustată servește
momentumului și păstrează data reală a ajustării. Reviziile se adaugă cu proveniență.
Raportările SEC păstrează datele de publicare și observare; valorile lipsă sau
profilurile nesuportate rămân explicite. Suplimentele FFIEC se pot importa din
fișier și manifest verificat, dar nu completează toate profilurile financiare.

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
Versiunea `SEC_MAPPING_VERSION` trebuie aliniată cu `SCORING_MODEL_MAPPING_VERSION`;
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
Consumatorul Scoring trebuie să ruleze pentru finalizarea livrării. Retry-ul reutilizează
identitatea lucrării; nu se resetează manual starea ca să pară completă.

Epuizarea încercărilor unui element nu abandonează sesiunile încă recuperabile:
runtime-ul continuă până la terminarea acestora sau până la timeout. La final,
erorile persistente produc în continuare eșec. `MISSING_SESSION` păstrează
răspunsurile furnizorului și checkpointul final; nu echivalează cu o eroare HTTP
sau cu o pagină neterminată și nu dovedește singur cauza economică a absenței.

`GET /internal/ingestion/status` raportează progresul, problemele și ultima sesiune
acoperită. Cere `operations:read` și se accesează direct pe portul producerului.
Un container oprit nu mai colectează date noi, dar datele existente rămân în PostgreSQL.

Tabele: `reference.*`, `operations.ingestion_*`, `operations.source_artifacts`,
`operations.outbox_events`, `operations.data_coverage`, `operations.data_watermarks`.
Vezi [API intern](ENDPOINTS.md) și [configurația completă](../market-data-producer/src/main/resources/application.yml).
