# Database Migrations

V025 (P2s, 2026-11-05) elimină `operations.search_rebuild_checkpoints`, checkpointul
rebuild-urilor Elasticsearch. Tabelul nu mai avea consumatori; `DROP ... RESTRICT`
refuză dependențele neașteptate. Singurul rând local, al ultimului index
`companies-read`, din 2026-11-02, cu 520 de documente, este păstrat în
[dovezile P2s](evidence/p2s/README.md). Rolul `quant_search` este la nivel de cluster,
iar Flyway rulează ca `quant_migrator` (`NOCREATEROLE`), deci nu îl poate șterge.
`R__security_grants` îi revocă toate drepturile, iar testul de roluri verifică asta.
Bootstrap-ul și rolurile locale nu îl mai creează, dar V014 îl creează în continuare pe
instalările noi. Un administrator îl poate șterge cu `DROP ROLE quant_search`. Pe baza
locală s-a aplicat după backupul verificat `build/p2s-cleanup-20261105/backup/`, iar
rolul a fost șters. Readiness-ul Screener verifică acum `research.stock_scores`.

Drepturile P2b sunt în migrarea repetabilă `R__security_grants.sql`; schema rămâne
la V024. `quant_screener` primește `USAGE` pe `market_data` și `SELECT` numai pe
`market_data.daily_bar_observations`. Dreptul este folosit pentru RSI-ul unei
publicații din endpointul financiar. Rolul nu poate scrie și nu poate citi alte
tabele `market_data`.
Testul de roluri verifică ambele limite. Pe baza locală s-a aplicat la 2026-11-02,
după backupul verificat `build/p2b-20261102/backup/` (artefact local, ignorat de Git).
La 2026-11-05, pentru `EBITDA_FROM_SOURCE_V1`, rolul a primit `USAGE` pe
`fundamentals` și `SELECT` pe `fundamentals.fundamental_facts`, `fundamentals.filings`
și `operations.source_artifacts`, fără scriere și fără alte tabele din aceste scheme.
Testul de roluri verifică și aceste limite. S-a aplicat pe baza locală după backupul
verificat `build/p2b-ebitda-20261105/backup/`.

V024 permite revizuirea `QUALITY_ISSUE` și pentru problemele `MISSING_SESSION`, numai
în două cazuri pe care triggerul le verifică în DB: simbol retras, cu un bar `VALID`
al aceluiași instrument, dataset și sesiune, ingerat după detectarea problemei
(`SYMBOL_RETIRED_SESSION_RECOVERED_UNDER_SUCCESSOR_SYMBOL`), sau sesiune anterioară
primului simbol al instrumentului, fără niciun bar valid
(`SESSION_BEFORE_INSTRUMENT_LISTING`). Orice altă lipsă de sesiune rămâne blocantă.
Problema nu se modifică și nu se rezolvă; revizuirea este vizibilă numai după
`recorded_at`. Pe baza locală s-a aplicat la 2026-11-01, după backupul verificat
`build/p2-psky-20261101/backup/` (artefact local, ignorat de Git).

V023 înregistrează [modelul 1.5.0](P2-MODEL-1.5.md) și adaugă hashul său la
verificările publicării flexibile. Pe baza locală s-a aplicat la 2026-10-31, după
backupul verificat `build/p2-v15-20261031/backup/` (artefact local, ignorat de Git).

V022 înregistrează [modelul 1.4.0](P2-MODEL-1.4.md) și adaugă explicit hashul său
la verificările publicării flexibile. Manifestele și publicațiile vechi rămân imuabile.

V021 adaugă modelul flexibil 1.3.0 și verificările de publicare pentru familii
absente, ponderi efective și contribuții. Modelele anterioare păstrează cerințele
lor de acoperire; publicațiile rămân imuabile. Vezi [raportul 1.3](P2-FLEXIBLE-MODEL.md).

Procesul central Flyway deține schema comună a aplicației. Aplică migrările și se
închide; un container cu `Exited (0)` este comportamentul normal. Nu expune HTTP.

## Flow

1. Așteaptă PostgreSQL / TimescaleDB.
2. Verifică istoricul și checksumurile migrărilor.
3. Aplică versiunile SQL noi și migrările repetabile modificate.
4. Se oprește cu succes; serviciile dependente pot porni.

Schema sursă ajunge la **V025**. V020 introduce revizuiri imuabile ale inputurilor
pentru [P2](P2-DATA-PREPARATION.md). Migrarea centrală folosește rolul `quant_migrator`;
serviciile obișnuite au permisiuni limitate pe domeniul lor.
Tabelele sunt rezumate în [Application.md](Application.md).

V019 elimină tabelele `public.tick_data`, `operations.month_end_score_jobs` și
schemele goale `identity`, `portfolio`. Refuză operația dacă tabelele conțin date;
`RESTRICT` protejează dependențele și obiectele neașteptate din scheme. Orice eroare
anulează întreaga migrare. Conturile și deținerile rămân în `public.users` și
`public.portfolios`. Tabelele legacy `market_bars`, `fundamental_snapshots` și
`factor_scores` sunt păstrate deoarece au încă utilizatori în Scoring și Python.

## Reguli de întreținere

Migrările versionate deja aplicate se păstrează; schimbările de schemă primesc
versiuni noi. Pe o bază existentă se face backup și se aplică upgrade-ul, fără
ștergerea volumului. Scripturile de bootstrap sunt destinate instalării inițiale;
nu se reaplică automat pe date existente.

Configurația folosește `DATABASE_MIGRATION_PASSWORD` și setările DB din
[Compose](../infrastructure/docker-compose.yml).
Schema și funcțiile sunt în [db/migration](../database-migrations/src/main/resources/db/migration).

Dacă view-urile/funcțiile repetabile dintr-o instalare veche au rămas deținute de
administrator, acesta aplică o singură dată
[repair-migration-ownership.sql](../infrastructure/repair-migration-ownership.sql).
Scriptul transferă cele patru view-uri și patru funcții, plus tabelul și funcția
de revizuire V020 dacă există, către `quant_migrator`; apoi se reia Flyway cu
rolul normal. Istoricul Flyway își păstrează ownerul.

## Backup și restaurare

[infrastructure/backup](../infrastructure/backup) conține `backup.sh`,
`restore.sh`, `backup-loop.sh` și scriptul rolului de backup.
[Overlay-ul de backup](../infrastructure/docker-compose.backup.yml) este opțional,
cu `BACKUP_DB_USER`, `BACKUP_DIRECTORY` și `BACKUP_PGPASS_FILE`.

Backupul include dumpul PostgreSQL, checksumul și versiunile serverului/extensiei.
Restaurarea se verifică pe o bază izolată, cu versiuni compatibile, înainte de
folosire; scriptul refuză suprascrierea unei baze deja existente.

P2s (2026-11-05): `quant_scoring` primește `UPDATE` pe `operations.outbox_events`, pentru
livrarea directă din PostgreSQL (fără Kafka). Inserarea evenimentelor rămâne numai la
`quant_ingestion`, iar testul de roluri verifică ambele limite. S-a aplicat prin serviciul
compose `database-migrations`, după backupul verificat `build/p2s-kafka-20261105/backup/`.

Curățare 2026-11-05 (decizia utilizatorului): pe disc rămâne numai dumpul complet cel
mai recent, `build/p2s-cleanup-20261105/backup/` (înainte de V025), verificat cu
`pg_restore --list`. Dumpurile din `p2b-ebitda-20261105` și `p2s-kafka-20261105` au fost
șterse ulterior.
Dumpurile anterioare, menționate mai jos și în rapoartele P2, au fost șterse.
Directoarele lor păstrează metadatele (`backup.json` sau `.sha256`) și
`DUMP-DELETED.txt`.

Backupul local din 2026-09-24 și probele restaurării se păstrează în
`build/local-upgrade-20260924/`; acestea sunt artefacte locale, ignorate de Git.
Backupul de dinaintea V019, inventarul și verificările cleanup-ului se păstrează
în `build/db-cleanup-20261003/`, cu acces local restricționat.
