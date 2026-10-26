# Database Migrations

Procesul central Flyway deține schema comună a aplicației. Aplică migrările și se
închide; un container cu `Exited (0)` este comportamentul normal. Nu expune HTTP.

## Flow

1. Așteaptă PostgreSQL / TimescaleDB.
2. Verifică istoricul și checksumurile migrărilor.
3. Aplică versiunile SQL noi și migrările repetabile modificate.
4. Se oprește cu succes; serviciile dependente pot porni.

Schema sursă ajunge la **V020**, care introduce revizuiri imuabile ale inputurilor
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
Elasticsearch se reconstruiește ulterior din PostgreSQL.

Backupul local din 2026-09-24 și probele restaurării se păstrează în
`build/local-upgrade-20260924/`; acestea sunt artefacte locale, ignorate de Git.
Backupul de dinaintea V019, inventarul și verificările cleanup-ului se păstrează
în `build/db-cleanup-20261003/`, cu acces local restricționat.
