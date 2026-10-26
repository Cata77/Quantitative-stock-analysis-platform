# P2 — Pregătire canonică versionată

P2 încheiat la 2026-10-25. Raportul de închidere este la finalul documentului;
secțiunile datate păstrează istoricul intervențiilor prioritare din [P0](P0-EXCLUSION-AUDIT.md).

## Reguli implementate

Noile execuții folosesc `canonical-preparation-v3`. Versiunea intră în cheia
logică a runului, în `preprocessing_version` și în inputul pregătit păstrat.
Formula și manifestul modelului rămân 1.2.0. Certificarea Java include noua clasă
de pregătire și hashul SQL; freeze-ul activ include sursele relevante.

- GENERAL cu ultima observație `NO_MAPPED_FACTS` poate utiliza acoperirea
  canonică existentă, dacă are `TOTAL_ASSETS` într-o raportare recentă.
  Verificările de perioadă, metrici, normalizare și eligibilitate rămân active.
  Profilurile financiare nu sunt activate prin această regulă.
- Reviziile faptelor sunt selectate separat pe unitate. O intrare EUR invalidă
  nu invalidează valoarea USD; conflictele sau stările invalide în unitatea
  canonică rămân blocante.
- `COST_OF_REVENUE_TTM = REVENUE_TTM − GROSS_PROFIT_TTM` se derivează numai
  pentru GENERAL, cu aceleași perioade, filinguri și unități în toate
  componentele selectate. O valoare directă existentă nu este suprascrisă.
  Proveniența reține toate faptele și numele derivării.
- `preparation_diagnostics` distinge lipsa faptelor canonice selectate,
  imposibilitatea construirii TTM și lipsa unui bilanț comparabil unic.
  Aceste stări nu afirmă că informația lipsește din raportarea originală.

SQL-ul vechi este păstrat în `scoring-inputs-profiles-v1.sql`, iar adaptorul
implicit al API-ului Python de pregătire rămâne `profiles-v1` pentru reproducere.
Serviciul de scoring cere explicit versiunea nouă. Sursele și freeze-ul anterior
sunt arhivate în `research-engine/fixtures/model-v1.2-profiles-v1-archive/`,
cu căile originale relative la acel director. Fixture-urile formulei nu se schimbă.

## Revizuiri explicite, fără retrodatare

V020 adaugă `operations.input_reviews`, cu înregistrări imuabile și decizii
`ACCEPT` / `REVOKE`. Revocarea este o înregistrare nouă. Timestampul este impus
de server; selecția folosește ultima decizie cunoscută la cutoff.

Tipuri disponibile:

| Tip | Dovezi și efect |
|---|---|
| `COMMON_EQUITY` | Fapt instant `STOCKHOLDERS_EQUITY`, USD, același emitent și scope `TOTAL_EQUITY_IS_COMMON_ONLY`; nu înlocuiește un fapt direct COMMON_EQUITY existent |
| `SHARE_CLASS` | Fapt instant `SHARES_OUTSTANDING`, unitate `shares`, scope `EXACT_INSTRUMENT_SHARES` și clasa explicită; respinge alocarea aceluiași fapt altui instrument |
| `QUALITY_ISSUE` | Problemă de identitate/acțiune corporativă a instrumentului, cu copia exactă a dovezii problemei; nu elimină global blocajele |

Manifestul cere `reviewId`, `kind`, `instrumentId`, `factId`, `issueId`,
`decision`, `reviewer`, `evidence`. Identificatorul neaplicabil este `null`.
`evidence` conține `rationale`, `sourceUri` HTTPS, `sourceSha256`, plus scope/clasă
sau `issueEvidence`, după tip. Fișierul sursă trebuie păstrat local, cu hashul
declarat; utilitarul îl verifică înainte de scriere. Responsabilitatea verificării
semantice a sursei rămâne la autorul revizuirii.

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.input_review --manifest <review.json> --evidence-file <filing.html>
# Aplicare explicită prin SCORING_DB_DSN, folosind rolul quant_scoring:
.\research-engine\.venv\Scripts\python.exe -m quant_research.input_review --manifest <review.json> --evidence-file <filing.html> --apply
```

Repetarea aceluiași UUID cu același conținut este idempotentă; alt conținut este
respins. Nu se introduc revizuiri AAPL sau rezolvări de acțiuni corporative doar
pentru creșterea numărului de scoruri. Revizuirea făcută azi nu devine disponibilă
la cutofful din 1 octombrie.

## Comparație controlată și reproducere

Prima recalculare pe 518 instrumente, cu data economică 2026-09-30 și cutofful
2026-10-01T19:07:09Z: **49 → 51 eligibili**, recuperate **LII și VLTO**, fără
instrumente eligibile pierdute. AAPL nu este recuperată printr-o revizuire
ulterioară cutoffului. Acesta este un rezultat diagnostic, nu o publicație nouă.

Sunt 55 de instrumente cu inputuri modificate: 50 cu blocajul filingului gol
eliminat, cinci cu cost derivat (MTD, SOLV, DRI, GEHC, LITE) și PEP cu datoria
curentă USD recuperată. Grupurile se suprapun. Toate cele 518 rezultate ale
publicației originale au fost comparate din nou cu DB și sunt neschimbate.

Dovezi păstrate în repo: [comparația completă pe instrumente](evidence/p2/summary.json)
și [verificarea publicației originale](evidence/p2/publication-preserved.json).

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.p2_comparison --publication build/local-step5/current-20261001 --output build/p2-comparison-verified-20261023
```

Utilitarul verifică hashul inputului publicat, reproduce modelul cu adaptorul
vechi din arhivă, apoi recalculează întreaga
cohortă cu noua pregătire. Refuză duplicatele și schimbarea universului; raportează
valorile adăugate, schimbate sau eliminate, blocajele și recuperările complete.
Interogările sunt numai-citire, cu rolul `quant_scoring`, memorie de sortare
limitată și fără paralelism, pentru verificarea pe stackul local.

Opțiunea `--verify-legacy-database` recalculează suplimentar și selecția SQL
veche din DB. Această verificare suplimentară nu a putut fi încheiată local:
interogarea veche a provocat epuizarea memoriei în timpul testelor de integrare.
Reproducerea din inputul arhivat este verificată separat și exactă; raportul
marchează verificarea DB neefectuată cu `legacyDatabaseReplayExact: null`.

Fișierele rezultate sunt `canonical.json`, `prepared.json`, `model.json` și
`summary.json`. Pentru paritatea Java/Python pe aceleași 518 instrumente:

```powershell
$env:P2_COMPARISON_DIR = (Resolve-Path build/p2-comparison-verified-20261023).Path
.\gradlew.bat :scoring-service:test --tests '*P2CohortParityTest'
Remove-Item Env:P2_COMPARISON_DIR
```

Variabila activează și un heap de test de 2 GB, necesar pentru captura canonică
de aproximativ 170 MB. Testul compară inputurile pregătite și toate rezultatele
serializate, inclusiv normalizarea și rangurile, la toleranța 1e-9.

Validare: 161 de teste Python și Ruff; paritate Java/Python pe fixture-uri și pe
toate cele 518 instrumente; 19 teste de publicare Scoring trecute la rerulare.
Toate cele 19 teste Database Migrations trec, inclusiv upgrade, backup/restaurare
și repararea proprietarilor obiectelor V020 create inițial de administrator.
Rularea inițială a suitei Scoring a avut 91/92 teste trecute și un timeout de
conexiune în timpul presiunii pe memorie; clasa afectată a trecut integral separat.

V020 a fost aplicată local după verificarea SHA-256 a backupului
`build/p2-backup/before-v020.dump`. Publicația veche nu a fost modificată;
serviciile Docker existente nu au fost reconstruite pentru activarea P2.

## Lucrări rămase la 2026-10-23

- Extinderea revizuirilor reale la alte cazuri dovedite și evaluarea la un cutoff
  de scoring compatibil; revizuirile AAPL de mai jos sunt înregistrate, dar nu
  reprezintă o publicație nouă și nu echivalează cu experimentul P0 retroactiv.
- Audit extins al selecției TTM/bilanțurilor și al tagurilor contabile candidate.
- Recuperarea datelor vechi/lipsă despre acțiuni, cu splituri și clase verificate.
- Rezolvarea individuală a blocajelor de identitate/acțiuni corporative.
- Inventarierea și recuperarea sesiunilor/prețurilor efectiv disponibile.
- O execuție nouă publicată și verificată cap-coadă după validarea datelor.

Existența fluxului de revizuire nu înseamnă că toate lipsurile au fost remediate.

## Continuare: reconcilierea AAPL înregistrată

La 2026-10-23 au fost înregistrate două revizuiri `COMMON_EQUITY`, cu timestamp
impus de server, pentru faptele originale deja existente. Raportarea completă
[SEC AAPL Q3 2026, paginile 3–4](https://www.sec.gov/Archives/edgar/data/320193/000032019326000020/aapl-20260627.htm)
a fost descărcată și păstrată în `build/p2-aapl-review/aapl-20260627.htm`.
Hashul și data observării sunt în [source.json](evidence/p2/aapl-review/source.json).

Reconcilierea din situația capitalurilor, în USD:

| Perioadă | Common stock/APIC | Rezultat reportat | OCI | Total reconciliat |
|---|---:|---:|---:|---:|
| 2026-06-27 | 100.702.000.000 | 11.326.000.000 | −4.508.000.000 | 107.520.000.000 |
| 2025-06-28 | 89.806.000.000 | −17.607.000.000 | −6.369.000.000 | 65.830.000.000 |

Au fost verificate CIK-ul, perioadele instant, dimensiunile componentelor,
unitatea, scala și semnul faptelor inline XBRL, plus concordanța cu faptele
canonice din DB. Scope-ul common-only rezultă din revizuirea situației complete;
nu este dedus doar din absența unui tag. Preferred și minority nu primesc zero.

Manifestele păstrează ID-urile faptelor inline și contextelor, valorile, sursa
și hashul: [2026-06-27](evidence/p2/aapl-review/review-2026-06-27.json),
[2025-06-28](evidence/p2/aapl-review/review-2025-06-28.json). Aplicarea a folosit
rolul `quant_scoring`, o singură tranzacție și verificarea repetării idempotente.

[Auditul selecției](evidence/p2/aapl-review/selection-audit.json) confirmă:

- La cutofful publicației, cele două revizuiri nu produc COMMON_EQUITY.
- După înregistrarea din 2026-10-23T11:55:17Z, SQL-ul de producție produce cele
  două valori, cu `available_at` și `observed_at` cel puțin egale cu data revizuirii.
- Faptele numerice originale și publicația de 518 instrumente sunt neschimbate.
- Se păstrează `INVALID_CURRENT_SNAPSHOT_CUTOFF`: prețurile locale ajung numai
  până la 2026-09-30. Auditul este `DATA_SELECTION_ONLY_NOT_A_SCORING_RUN`;
  nu calculează și nu publică scoruri la un cutoff incompatibil.

Utilitarul întreținut extrage aceleași CTE-uri de selecție din SQL-ul Scoring,
limitează lucrul la emitentul instrumentului și păstrează erorile de validare.
Refuză schimbarea structurii SQL fără revizuirea extracției. Rulează într-o
tranzacție `REPEATABLE READ, READ ONLY`, cu `SCORING_DB_DSN` și rolul `quant_scoring`:

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.input_review_audit --publication build/local-step5/current-20261001 --instrument-id 85ca0ea2-4a0a-422a-8475-3c1eca7d2ace --output build/p2-aapl-review/new-selection-audit.json
```

Validarea acestei continuări: 13 teste Python pentru revizuiri/audit, Ruff și un
test de integrare pe SQL-ul real pentru acceptare, cutoff exact, revocare,
reacceptare și prioritatea unui fapt COMMON_EQUITY direct. Formula, adaptoarele
și freeze-ul nu s-au schimbat în această continuare.

[Verificarea pe întreaga cohortă după aplicare](evidence/p2/aapl-review/historical-preservation.json)
confirmă egalitatea inputurilor canonice, pregătirii și rezultatelor P2 pentru
toate cele 518 instrumente la cutofful vechi: 51 eligibile în diagnostic,
49 în publicația originală. Revizuirile noi nu au contaminat istoricul.

## Continuare: recuperarea prețurilor brute din octombrie

La 2026-10-23, colectorul existent a recuperat intervalul 2026-10-01–2026-10-22,
cu feed SIP, ajustare RAW și universul selectat la 2026-10-23. Calendarul
furnizorului confirmă 16 sesiuni închise. Se folosesc snapshoturile complete
disponibile în baza locală; aceasta nu este o verificare nouă a componenței indicilor.

[Auditul final](evidence/p2/market-recovery/recovery-audit.json) reconciliază
8.288 de elemente planificate = 8.262 acceptate canonic + 26 lipsuri:

- 1, 2 și 5 octombrie: câte 518 instrumente cu preț.
- Cele 13 sesiuni din 6–22 octombrie: câte 516 instrumente cu preț.
- PSKY și WBD: câte 13 lipsuri `MISSING_SESSION`, confirmate și printr-o
  [cerere separată la furnizor](evidence/p2/market-recovery/preflight.json).
  Răspunsul HTTP 200 conține prețuri numai până la 5 octombrie; cauza economică
  nu este stabilită de acest răspuns și cere verificarea acțiunilor corporative.
- Zero perechi instrument/sesiune duplicate și zero observații retrodatate.

Au rulat doi lucrători locali și o reluare pe aceleași joburi persistente.
Limita de retry a oprit procesul înainte de a procesa toate elementele;
reluarea a recuperat restul disponibil fără ștergerea checkpointurilor sau a
erorilor. Starea finală este 8.262 `COMPLETE`, 10 `FAILED`, 16 `WAITING_RETRY`,
fără `PLANNED`, `RUNNING` sau `STAGED`. Nu mai rulează colectorii lansați aici.
Nu s-a modificat politica de retry și nu s-au consumat intenționat încercările
rămase pentru date deja confirmate absente la furnizor.

Configurarea folosită: `backfill`, start `2026-10-01`, end `2026-10-22`,
`price-history-universe-date=2026-10-23`, 1.000 elemente/ciclu, 200 simboluri/cerere,
timeout 20 minute, colectare RAW activă; adjusted, corporate actions, SEC,
OVERVIEW, importul referințelor și programarea periodică dezactivate.
JAR-ul verificat prin `:market-data-producer:bootJar` are SHA-256
`e1cb3e7a1563ca15bed7ff88e3f100324524a12b894a673fc83522afdb63d4b6`.
Joburile, configurațiile și timestampurile observării sunt în audit.
[Scriptul de audit](evidence/p2/market-recovery/audit.py) poate fi reluat din
rădăcina repository-ului, cu containerul local pornit; scrie raportul în
`build/p2-market-20261023/recovery-audit.json`.

[Verificarea publicației](evidence/p2/market-recovery/historical-preservation.json)
confirmă din nou toate cele 518 rezultate originale și hashul inputurilor.
Această continuare nu schimbă formula sau codul serviciilor și nu publică scoruri.
La final sunt zero observații ajustate cu baza 2026-10-23: rămân de recuperat
cele 253 de sesiuni cerute de adaptor pentru un scor curent, împreună cu
verificarea cazurilor PSKY/WBD. P2 rămâne deschis.

## Continuare: colectare independentă a istoricului ajustat

La 2026-10-24, producerul permite recuperarea numai a seriei `SPLIT_DIVIDEND`,
prin `ingestion.prices.raw-enabled=false` și `ingestion.prices.adjusted-enabled=true`.
Anterior, orice recuperare ajustată planifica și RAW. Acum joburile RAW și
încercările lor restante pot rămâne în afara execuției de recuperare Momentum.
Configurația implicită continuă să colecteze ambele serii; opțiunea este expusă
și prin `DAILY_PRICE_RAW_ENABLED` în Compose. Configurația și cheia fiecărui job
selectat rămân neschimbate, pentru a reutiliza progresul persistent.

Argumente pentru JAR-ul producerului, adăugate configurației locale cu DB,
Kafka, relay și consumator Scoring funcționale:

```text
--ingestion.mode=backfill
--ingestion.start-date=<prima-sesiune-din-fereastra-necesara>
--ingestion.end-date=<ultima-sesiune-inchisa>
--ingestion.price-history-universe-date=<data-universului-selectat>
--ingestion.prices.raw-enabled=false
--ingestion.prices.adjusted-enabled=true
--ingestion.prices.corporate-actions-enabled=false
--market-data.enabled=true
--market-data.latest-bars-enabled=true
--market-data.fundamentals-enabled=false
--fundamentals.enabled=false
--fundamentals.ffiec-file=
--fundamentals.ffiec-manifest=
--reference-data.import.enabled=false
--ingestion.schedules-enabled=false
```

Intervalul trebuie ales din calendarul real pentru cele 253 de sesiuni cerute;
data universului trebuie să fie cel puțin egală cu sfârșitul intervalului și
cel mult data curentă. Acest mod recuperează istoric pentru universul selectat,
nu reconstruiește componențe istorice pentru backtesting.

Data ajustării este ziua efectivă a descărcării în New York, independentă de
data sesiunii și de data mapării simbolurilor. Nu se forțează baza 2026-10-23
la o descărcare ulterioară. Răspunsurile care traversează miezul nopții sunt
respinse; o reluare în ziua următoare planifică o versiune nouă a seriei ajustate.
Selecția fără nicio serie, când colectarea prețurilor este activă, este respinsă
înainte de planificare sau cereri către furnizor.

Validare: 14 teste Java trecute (`DailyPricePropertiesTest`,
`AlpacaDailyDataClientTest`, `MarketDataProducerApplicationTest`), inclusiv
bindingul valorilor implicite, selecția independentă, respingerea selecției goale
înainte de I/O și traversarea miezului nopții. Configurația Compose trece
`config --quiet`. Nu s-au modificat modelul, pregătirea canonică sau freeze-ul.

Recuperarea efectivă, testele cu PostgreSQL și noua publicație rămân de executat:
Docker Desktop nu era pornit în această sesiune. Această modificare nu adaugă
prețuri și nu schimbă numărul de instrumente eligibile raportat mai sus.

## Închidere P2 — 2026-10-25

Publicația nouă **`76e93093-54e7-4fa8-9b44-760857d69ae1`** este `PUBLISHED`,
cu **518 instrumente, 49 eligibile și 469 excluse**. Data economică este
2026-10-23, cutofful 2026-10-24T23:59:59Z și baza ajustărilor 2026-10-24.
Datele erau deja observate la acel cutoff; publicarea efectivă s-a făcut pe
25 octombrie. Data de execuție teoretică a politicii este 26 octombrie, 13:30 UTC.
Formula rămâne 1.2.0, cu statutul `FROZEN_RESEARCH_HYPOTHESIS` / `NOT_EVALUATED`.

[Verificarea publicației](evidence/p2/closeout/publication-verification.json)
reproduce în Python inputurile pregătite și toate rezultatele Java, la toleranța
1e-9, verifică hashul documentului și sursele păstrate. Sunt zero prețuri din
proveniență ulterioare cutoffului. [Publicația originală](evidence/p2/closeout/historical-preservation.json)
are același hash și aceleași 518 rezultate.

### Comparații și acoperire

Comparația controlată din [summary.json](evidence/p2/summary.json), pe aceeași
dată și aceleași informații istorice, rămâne **49 → 51**, prin recuperarea
LII/VLTO, fără pierderi. [Comparația între publicații](evidence/p2/closeout/snapshot-comparison.json)
descrie separat toate cele 518 instrumente: valori schimbate, fapte pentru
numărul de acțiuni, motive înainte/după și eligibilitate. Aceasta compară două
date economice și nu atribuie toate schimbările pregătirii P2.

- Aceleași 49 de instrumente sunt eligibile în cele două publicații. LII/VLTO
  au depășit între timp vechimea permisă a numărului de acțiuni. Remediul pentru
  filingul gol funcționează, dar nu anulează `STALE_SHARES`.
- Recolectarea SEC a schimbat numeric numărul de acțiuni pentru **67** de
  instrumente; **CCL** are acum un fapt atribuit instrumentului, iar **DOW** nu
  mai are `STALE_SHARES`. Proveniența și perioadele fiecărui fapt sunt în raport.
  Rămân 58 instrumente fără acțiuni selectate și 39 cu acțiuni vechi la noua dată.
  Nu se distribuie numărul total al emitentului arbitrar între clase și nu se
  ajustează manual pentru splituri fără dovezi.
- AAPL are o versiune de filing recolectată. Revizuirile din 23 octombrie
  vizează UUID-urile faptelor vechi și nu se transferă automat celor noi, chiar
  dacă valorile coincid. AAPL rămâne exclusă pentru acoperire Value insuficientă.
- [Auditul perioadelor](evidence/p2/closeout/period-audit-summary.json) acoperă
  518 instrumente: 4.055 construcții annual + YTD − prior YTD, 309 valori anuale
  directe, 87 construcții din patru trimestre, 2.599 bilanțuri curente, 2.587
  comparative și șase derivări cost = venit − profit brut. Raportul integral
  local păstrează fiecare fapt, unitatea, perioada și timestampurile; hashul
  permite verificarea lui. Testele Java/Python includ an fiscal de 53 săptămâni,
  trimestre suprapuse, perioade ambigue și limite exacte de cutoff.
- Tagul candidat `InterestExpenseOperating` identificat la JPM/O nu este
  activat automat ca dobândă GENERAL. Echivalența pe profil și recuperarea
  metricilor de bancă/REIT continuă în P3; nu se revendică un câștig de acoperire
  din simpla prezență a tagului.

### Istoric recuperat și revizuiri individuale

[Auditul colectării](evidence/p2/closeout/recovery-audit.json) confirmă **130.706**
observații ajustate cu baza 24 octombrie, fără duplicate sau retrodatare.
Din 518 × 253 = 131.054 sesiuni potențiale, lipsesc 348: FDXF 151, HONA 161,
Q 8, PSKY 14 și WBD 14. Acesta este inventarul observațiilor furnizorului;
filtrele de identitate și valabilitate ale modelului se aplică ulterior.
Prețurile brute din 1–23 octombrie însumează **8.778**, cu 28 lipsuri PSKY/WBD.
Nu s-au inventat prețuri pentru sesiunile fără răspuns și nu s-au șters joburi.

Revizuirile [FISV](evidence/p2/closeout/review-FISV.json) și
[MRSH](evidence/p2/closeout/review-MRSH.json), înregistrate pe 24 octombrie,
acceptă exclusiv schimbările de simbol cu continuitatea aceleiași acțiuni,
verificate în sursele emitentului și în acțiunile furnizorului cu CUSIP neschimbat.
[Manifestul surselor](evidence/p2/closeout/review-sources.json) fixează hashurile
fișierelor locale din `build/p2-finish-20261024/sources/`. FISV nu mai are acel
blocaj, dar nu îndeplinește Quality; MRSH păstrează un alt blocaj corporativ și
profilul nesuportat. Nu s-a aprobat global categoria `CORPORATE_ACTION_REVIEW`.

Raportarea Skydance păstrată descrie tranzacția din 6 octombrie și schimbarea
identității/simbolului. Aceasta explică necesitatea unei reconcilieri distincte
PSKY/WBD; nu justifică lipirea seriei succesorului pe vechile instrumente sau
modificarea retroactivă a universului. Reconstrucția succesorilor/componențelor
pentru cercetare istorică rămâne în P4.

### Publicare cu absențe confirmate

`CURRENT_COLLECTION_OUTCOMES_V1` permite numai snapshoturilor curente să
publice rezultate/excluderi când fiecare membru are fie o observație acceptată,
fie absența sesiunii confirmată de pipeline. Pentru absență se cer împreună:
`MISSING_SESSION`, tentativa eșuată datată, checkpoint cu paginare încheiată și
zero bare, problema individuală, răspunsul furnizorului păstrat și lipsa
evenimentelor în așteptarea livrării. Dovezile absenței trebuie să preceadă cutofful.
Configurația trebuie să corespundă datasetului, sesiunii, SIP, ajustării și
ambelor snapshoturi de univers. Acoperirea invalidată sau alte probleme
blocante nu sunt acceptate de această cale.

Politica intră în identitatea logică a runului. [Dovezile PSKY/WBD](evidence/p2/closeout/missing-session-evidence.json)
sunt înghețate în `input_document.collectionCoverage`, împreună cu rezultatele
celor 516 membri acceptați în fiecare serie. Stările joburilor și lipsurile
rămân vizibile; nu primesc acoperire completă fictivă. Colectările neterminate,
erorile HTTP și paginile incomplete nu satisfac această condiție. Publicarea
lunară păstrează cerința existentă de acoperire completă.

Colectorul continuă acum sesiunile recuperabile când un alt element a epuizat
încercările. Returnează eșec după terminarea lucrului recuperabil, păstrând
încercările și checkpointurile; nu revine la succes doar prin omiterea erorilor.

Prima încercare de publicare a întâlnit epuizarea memoriei Docker când rula
concomitent cu testele de integrare. După oprirea temporară a serviciilor
auxiliare, reluarea aceluiași run a folosit inputul deja capturat și a publicat
atomic. [Comanda reușită](evidence/p2/closeout/publication-command.json) fixează
JAR-ul și cutofful. Nu s-a modificat schema sau resetat baza pentru această reluare.

### Reproducere

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.publication_audit --run-id 76e93093-54e7-4fa8-9b44-760857d69ae1 --output build/p2-publication-recheck
.\research-engine\.venv\Scripts\python.exe -m quant_research.p2_snapshot_comparison --before build/local-step5/current-20261001 --after build/p2-publication-recheck --output build/p2-snapshot-recheck.json
.\research-engine\.venv\Scripts\python.exe -m quant_research.p2_period_audit --canonical build/p2-close-20261025/canonical.json --output build/p2-period-recheck.json
```

Utilitarele refuză suprascrierea dovezilor. `publication_audit` citește numai
runul explicit, verifică freeze-ul și reproduce ambele versiuni de pregătire.
Arhivele mari rămân local, iar rapoartele și hashurile sunt păstrate în repo.
P2 închide intervențiile demonstrate și comparația verificată; nu declară
remediate toate cele 469 de excluderi. P2b, P2c, P3 și P4 rămân etape separate.

### Reverificare la 2026-10-26

Închiderea a fost confirmată din nou pe baza locală, prin `publication_audit`,
pentru ambele runuri explicite. Pregătirea și toate cele 518 rezultate ale
fiecărei publicații se reproduc în Python la toleranța 1e-9; hashurile coincid
cu dovezile de închidere, iar proveniența nu conține prețuri ulterioare cutoffului.
Rapoartele complete sunt în `build/p2-recheck-20261026` și
`build/p2-original-recheck-20261026`. Nu a fost necesară o nouă publicare.

Validări executate în această reverificare:

- 183 de teste Python trecute și Ruff fără erori.
- 19 teste Java trecute: `CanonicalModelInputsTest`, `FrozenModelParityTest`,
  `P2CohortParityTest`, `P2PeriodAuditTest`, `DailyPricePropertiesTest`,
  `IngestionRuntimeTest` și `AlpacaDailyDataClientTest`. Paritatea cohortei folosește
  captura controlată din `build/p2-comparison-verified-20261023`.
- Configurația Compose de bază împreună cu overlay-ul local trece `config --quiet`.

Testele de integrare cu migrare/publicare nu au fost rerulate în această
reverificare; auditul celor două publicații a citit baza existentă fără modificări.
