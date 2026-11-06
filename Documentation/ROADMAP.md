# Plan de îmbunătățire a platformei

Actualizat: 2026-11-05. Document de lucru, reordonat pe măsură ce discutăm cerințele.
Taskurile de mai jos sunt planificate; acest document nu reprezintă implementarea lor.

## Obiective

- Afișarea întregului univers, cu scoruri și motive explicite pentru lipsa lor.
- Creșterea acoperirii prin îmbunătățirea datelor și a profilurilor suportate.
- Expunerea datelor financiare și a indicatorilor care explică scorurile.
- Interfață web pentru explorarea universului, explicarea scorurilor și dețineri.
- Evaluarea unei formule mai flexibile pe dovezi reproductibile.
- Extinderea în Europa după validarea colectării și calculului actual.

## Situația verificată

Ultima publicație verificată, din 2026-11-02: run `bead056b-eb58-4e18-aa5b-6e7815d6f8e9`,
model 1.5.0, pregătire `canonical-preparation-v4`, data economică 2026-10-30,
cutoff `2026-11-01T20:00:00Z`
([dovezi](evidence/p2/closeout-final/publication-20261102/README.md)). Valorile de mai
jos descriu această publicație, nu o verificare live ulterioară.

- Univers: reuniunea S&P 500 și Nasdaq-100, 518 instrumente unice.
- Rezultate: 515 scoruri complete sau parțiale și 3 excluderi documentate: WBD,
  instrument dispărut; HONA și SPCX, listări recente fără 10-K.
- Modelul 1.5.0 are statut de cercetare; validarea predictivă rămâne în P5.
- API-ul suportă `eligibility=ALL`, `EXCLUDED`, `ELIGIBLE` (implicit `ELIGIBLE`).
  Filtrele se aplică publicației selectate, fără recalcularea rangului. Endpointul
  financiar (P2b) adaugă RSI și EBITDA informative, care nu intră în scor.

Punctul de plecare a fost publicația din 2026-10-01 (model 1.2.0): 49 eligibile și
469 excluse, acoperire de 9,46%. Motivele, care se suprapun pe același instrument,
sunt analizate în [raportul P0](P0-EXCLUSION-AUDIT.md).

## Ordinea de lucru

### P0 — Diagnostic reproductibil al excluderilor

Încheiat la 2026-10-22: [raport final, dovezi și reproducere](P0-EXCLUSION-AUDIT.md).
518 instrumente reconciliate cu DB, 469 de excluderi explicate și 515 legături
către surse păstrate. Cazurile reprezentative includ raportările complete AAPL
și LULU, sursele JPM/O, filingurile goale LII/VLTO și conflictul de unități PEP.
Recalculările diagnostice recuperează LII și VLTO prin politica de profil și
AAPL prin reconcilierea revizuită a capitalurilor: 52 eligibile, fără pierderi.
Publicația reală rămâne la 49; remediile de producție și republicarea sunt P2.

- [x] Generare raport pe instrument: profil, sector, date brute necesare,
  metrici disponibile/lipsă, surse, perioade și toate blocajele.
- [x] Separare între lipsa sursei, mapare incompletă, perioade TTM incompatibile,
  date vechi, profil nesuportat și filtre economice/de identitate.
- [x] Verificare a unor cazuri reprezentative, inclusiv AAPL, până la raportarea
  sursă; audit al mapărilor SEC, capitalurilor proprii, EBIT/EV și ROIC.
- [x] Analiză a suprapunerii motivelor și a companiilor recuperabile integral
  prin fiecare remediere; prioritizare după impact verificat.
- [x] Verificare a integrității exportului: CSV-ul inspectat conține `GE/NERAL`
  la LULU. Comparare cu publicația înainte de a atribui problema modelului.

Criteriu de finalizare: toate cele 469 de excluderi au explicații trasabile;
raportul reconciliază 518 = 49 + 469 și identifică intervențiile cu impact maxim.

### P1 — Vizibilitatea întregului univers în listă și export

Încheiat la 2026-10-22: [utilitar, utilizare și dovezi](UNIVERSE-EXPORT.md).
Lista HTML locală și CSV-ul au 518 instrumente unice, 49 cu rang și 469 cu
motive explicite; verificare prin Gateway cu pagini de 100 și 137 de rânduri,
comparată cu publicația păstrată și rezultatele DB. Formula și publicația rămân
neschimbate. Interfața web integrată cu login rămâne P2c.

- [x] Folosire explicită a `eligibility=ALL` pentru vizualizarea universului,
  păstrând opțiunea de a vedea numai clasamentul eligibil.
- [x] Export complet, paginat cu același `runId`, cu un rând per instrument:
  simbol, profil, sector, eligibilitate, scor/rang dacă există, motive, acoperire,
  data scorului și versiunea modelului.
- [x] Pentru instrumentele excluse, scorul compozit și rangul rămân neatribuite;
  valorile parțiale se afișează numai dacă sunt disponibile și identificabile.
- [x] Afișare a totalului universului, eligibililor, exclușilor și acoperirii;
  explicarea populației față de care sunt calculate rangul și percentila.
- [x] Mutare a exportului într-un utilitar întreținut în repo, cu verificarea
  duplicatelor, numărului de rânduri și concordanței cu publicația.

Criteriu de finalizare: pentru publicația existentă, lista/exportul complet are
518 instrumente unice, 49 cu rang și 469 cu motive explicite. Această schimbare
de prezentare nu necesită modificarea formulei.

### P2 — Refactorizarea colectării și pregătirii datelor

Încheiat la 2026-11-02: [publicația de închidere și excluderile documentate](evidence/p2/closeout-final/publication-20261102/README.md).
Run `bead056b-eb58-4e18-aa5b-6e7815d6f8e9`, model 1.5.0, data economică
2026-10-30, cutoff `2026-11-01T20:00:00Z`: **518 = 515 scoruri + 3 excluderi
documentate**. SKYD (fost PSKY) este recuperat, fără pierderi față de 4e60be7c
(514/4). Paritatea Java/Python este 1e-9, nu există prețuri ulterioare cutoffului,
iar Gateway a fost verificat cu pagini de 100/137. Suita Java Scoring (103 teste,
2 omise) și 240 de teste Python cu Ruff au trecut. Validarea predictivă rămâne în P5.

Etapele intermediare, cu dovezile lor:

| Etapă | Rezultat | Raport |
|---|---|---|
| `canonical-preparation-v3` (2026-10-25) | LII/VLTO recuperate în comparația controlată: 49 → 51 | [P2-DATA-PREPARATION](P2-DATA-PREPARATION.md) |
| Model flexibil 1.3.0 (2026-10-26) | 49 → 438 instrumente calculabile, cu scoruri parțiale | [P2-FLEXIBLE-MODEL](P2-FLEXIBLE-MODEL.md) |
| Model 1.4.0 (2026-10-27) | 438 → 442 pe aceleași inputuri; publicația din 27 octombrie: 487/31 | [P2-MODEL-1.4](P2-MODEL-1.4.md) |
| Revizuiri individuale și recolectări (2026-10-29…31) | publicația `f41d3a8d` (29 octombrie): 497/21 | [closeout-final](evidence/p2/closeout-final/README.md) |
| Model 1.5.0, politica titlurilor (2026-10-31) | clase secundare și certificate de depozit, versionate | [P2-MODEL-1.5](P2-MODEL-1.5.md) |
| Publicația de închidere (2026-11-02) | 515/3 | [publication-20261102](evidence/p2/closeout-final/publication-20261102/README.md) |

Decizii acceptate de utilizator:

- 2026-10-31: sunt permise **excluderi economice documentate pentru instrumentele
  dispărute de la tranzacționare** (WBD). Instrumentele active cu probleme de
  colectare, mapare, identitate sau suport rămân de remediat. Universul și
  publicațiile istorice se păstrează.
- 2026-10-31: clasele secundare GOOG, FOX și NWS primesc scor din raportările
  comune ale emitentului, cu prețul și lichiditatea proprii clasei și capitalizarea
  pe toate clasele, într-o politică versionată. Certificatele de depozit ARM/PDD cer
  suport versionat, cu raportul ADR/acțiune, moneda raportării și conversia valutară
  explicite. Credentialul Scoring se folosește numai în memorie, pentru revizuiri
  individuale susținute de surse, recolectare și o publicație nouă.
- 2026-11-02: HONA și SPCX sunt excluse ca listări recente (numai 10-Q, sub 252 de
  ședințe proprii). Se reevaluează la primul raport anual.

Activarea profilurilor continuă în P3; istoricul mai lung, componențele și
succesorii instrumentelor continuă în P4.

- [x] Îmbunătățire mapări SEC și derivări contabile justificate, cu proveniență.
- [x] Audit al selecției perioadelor, TTM, bilanțurilor comparabile și faptelor
  ambigue în adaptoarele Java/Python; păstrarea echivalenței dintre implementări.
- [x] Remediere a datelor lipsă/vechi despre numărul de acțiuni și capitalizare,
  ținând cont de clase, splituri și data disponibilității informației.
- [x] Flux explicit de verificare și rezolvare pentru acțiuni corporative și
  identitatea instrumentelor, fără eliminarea globală a blocajelor.
- [x] Recuperare a sesiunilor/prețurilor lipsă, unde istoricul există efectiv.
- [x] Motive de eroare suficient de precise pentru a distinge lipsurile reale
  de problemele de colectare, mapare sau calcul.
- [x] Implementarea unei formule mai flexibile, cu obiectivul de a obține
  scoruri pentru instrumentele calculabile și excluderi economice documentate
  pentru instrumentele dispărute, reconciliind întregul univers (518 instrumente
  în publicația de referință). Evaluarea redistribuirii ponderilor
  între metricile disponibile și a alternativelor justificate pe profil; definirea
  acoperirii minime și distingerea scorurilor parțiale de cele complete.
  Remedierea separată a lipsei totale de date și a blocajelor de identitate,
  fără valori inventate sau înlocuirea datelor lipsă cu zero. Versiune nouă de
  model, manifest și fixture-uri, paritate Java/Python, comparație pe întregul
  univers și publicație nouă; publicațiile existente rămân imuabile.
  Implementarea acestei cerințe este adusă în P2; validarea istorică din P5
  rămâne necesară înainte de a afirma performanța predictivă a formulei.

Criteriu de finalizare: comparație înainte/după pe date controlate, cu numărul
de instrumente recuperate și explicația fiecărei schimbări. Pentru cerința nouă,
raportul trebuie să reconcilieze întregul univers cu scorurile complete/parțiale
și excluderile economice documentate pentru instrumentele dispărute. Fiecare
asemenea excludere identifică instrumentul exact, evenimentul, data efectivă și
sursele păstrate; nu folosește zero sau prețul succesorului în locul datelor lipsă.
Problemele încă nerezolvate ale instrumentelor active țin P2 deschis. Referințele
corectate și rezultatele trebuie verificate într-o publicație nouă, inclusiv
paritatea Java/Python și reconcilierea Gateway. Publicațiile vechi rămân
reproductibile; corecțiile se publică în execuții noi, cu versiunile relevante actualizate.

### P2b — Endpoint pentru date financiare și explicarea scorului

Încheiat la 2026-11-02: [verificarea live și prin Gateway](evidence/p2b/README.md).
Ruta `GET /screener/instruments/{instrumentId}/financials?runId={runId}` este
documentată în [ENDPOINTS.md](ENDPOINTS.md). Răspunsul are inputuri raportate sau
TTM cu proveniență, derivările modelului, metricile normalizate, scorul și
excluderile aceleiași publicații. Accesul folosește `research:read`. Cerința
utilizatorului din 2026-11-02 a adăugat RSI: `RSI_14_WILDER_V1`, informativ,
neinclus în scor. Se calculează din închiderile ajustate ale datasetului și
vintage-ului publicației, cu aceleași cutoffuri ca scoringul. Pe publicația
`bead056b`, RSI coincide cu un calcul independent pentru 8 instrumente, iar
valorile coincid cu lineage-ul păstrat. Rolul `quant_screener` primește numai
`SELECT` pe `market_data.daily_bar_observations` (migrare repetabilă, după backup).
Testele Screener (24, dintre care 4 RSI și 3 noi de integrare) și cele de migrare
(19) au trecut. Continuarea din 2026-11-05 adaugă D&A/EBITDA din sursa păstrată a
publicației; toate punctele P2b sunt bifate (33 de teste Screener, 19 de migrare).

- [x] Definirea unui DTO explicit și documentarea contractului în ENDPOINTS.md
  odată cu implementarea. Prima versiune cere `runId` pentru a lega datele de
  publicația consultată; nu combină implicit date curente cu un scor istoric.
- [x] Expunerea datelor disponibile relevante pentru scor: venituri, profit net,
  profit operațional, active, capitaluri proprii, datorii, numerar, flux de numerar
  operațional, CAPEX, număr de acțiuni și capitalizare.
- [x] Expunerea indicatorilor derivați disponibili, precum FCF, EBIT/EV,
  FCF yield, book/market și ROIC, cu unitate, formulă/versiune și proveniență.
  Separarea valorilor contabile raportate, derivărilor și scorurilor normalizate.
- [x] Pentru fiecare valoare: monedă/unitate, început/sfârșit de perioadă sau
  dată de bilanț, tipul perioadei (trimestrial/anual/TTM), data publicării,
  sursele și starea disponibilității. Returnarea numai a perioadelor efectiv
  existente sau derivabile justificat, fără a promite situații financiare complete.
  Limită: începutul perioadei TTM nu este păstrat separat în lineage; TTM înseamnă
  12 luni care se încheie la `periodEnd`.
- [x] Citirea inputurilor/provenienței păstrate pentru runul publicat și
  reutilizarea derivărilor versionate din scoring; evitarea unei formule paralele
  în Screener. Audit al permisiunilor DB și migrare dacă sunt necesare drepturi noi.
- [x] Suport pentru instrumentele excluse: datele disponibile rămân accesibile,
  iar lipsurile sunt `null` cu motiv explicit, inclusiv când scorul nu există.
- [x] Contract de erori: 400 pentru parametri invalizi; 404 pentru instrument,
  publicație sau apartenență la run inexistente; 200 cu lipsuri explicite pentru
  un instrument prezent în publicație, dar fără toate datele financiare.
- [x] Teste de integrare pentru concordanța cu runul, unități/perioade, absența
  informațiilor ulterioare cutoffului, instrument exclus și controlul accesului.
- [x] RSI(14) Wilder pentru instrument, legat de aceeași publicație, cu fereastra,
  datasetul, vintage-ul și motivele de lipsă explicite; nu modifică scorul.
- [x] Vedere implicită pentru utilizator (`view=SUMMARY`, `DISPLAY_METRICS_V1`):
  P/E, P/S, P/B, P/FCF, EV/EBIT, randamente FCF/dividend, marje, ROA/ROE, datorie
  netă, preț, RSI, randament pe 1 an, maxim/minim pe 52 de săptămâni și
  volatilitate, fără câmpuri goale. Dosarul complet rămâne disponibil prin `view=FULL`.
- [x] **Continuare de date, încheiată la 2026-11-05:** D&A și EBITDA informative
  (`EBITDA_FROM_SOURCE_V1`, `DISPLAY_METRICS_V2`), fără efect pe scor. Decizia
  utilizatorului: fără maparea `sec-us-gaap-v3`, recolectare sau publicație nouă.
  Acestea ar fi cerut un vintage nou de prețuri ajustate (~131.000 de itemi Alpaca),
  reaplicarea revizuirilor legate de faptele v2 (inclusiv GOOG/FOX/NWS) și pregătirea
  v5. D&A se citește din răspunsul SEC păstrat în spatele faptelor
  `OPERATING_INCOME_TTM`, pe aceleași filinguri și perioade. Pe `bead056b`: 288 de
  instrumente cu EBITDA, 0 diferențe față de un calcul independent;
  [dovezi](evidence/p2b/README.md). Limite: 101 instrumente nu au un concept
  standard acceptat pe aceleași perioade (de ex. MSFT raportează numai componente
  separate). Sumele de componente și conceptele cu acrețiune cer o regulă nouă,
  versionată. Constatări AAPL din 2026-11-05: `InterestExpense` apare numai până la
  exercițiul 2023. Lipsa este reală la sursă și rămâne documentată (decizia
  utilizatorului). `PaymentsOfDividendsCommonStock` nu există, iar conceptul general
  `PaymentsOfDividends` nu este mapat. `COMMON_EQUITY` nu se derivă, deoarece regula
  v2 cere o raportare explicită zero a acțiunilor preferențiale. Ambele rămân lipsuri
  documentate. Orice mapare nouă ar schimba inputurile modelului și cere versionare.

Criteriu de finalizare: pornind de la un rând din rankings, clientul poate obține
datele financiare disponibile și indicatorii aferenți aceleiași publicații,
inclusiv explicațiile lipsurilor. Nu sunt declanșate descărcări de la furnizori
în timpul cererii. Istoricul paginat al raportărilor, vizualizarea celor mai
recente date independent de run și seria istorică a RSI rămân extensii de discutat.
RSI nu a fost evaluat ca semnal; orice utilizare în model ar cere versionare și P5.
Din 2026-11-05, [exportul universului](UNIVERSE-EXPORT.md) afișează RSI 14 ca
coloană sortabilă în `universe.html` și în CSV, preluat prin endpointul financiar
al aceleiași publicații (o cerere per instrument, fără endpoint nou).

### P2b.1 — Verificarea comparațiilor pe sector și industrie

Constatare din cod: pentru GENERAL, `scoring-inputs.sql` setează `peerGroup` la
`mapped_sector`. Normalizarea folosește minimum 10 observații compatibile pentru
metrica respectivă în grupul primar; când nu sunt suficiente, folosește minimum
20 din același profil, cu avertisment `PEER_FALLBACK`. Cohorta este construită
după filtrele inițiale de eligibilitate/acoperire, înaintea eligibilității finale.
Filtrul API `sector` există, dar nu recalculează scorurile sau rangul global.

- [x] Audit al clasificărilor și al provenienței (2026-11-06,
  [dovezi](evidence/p2b1/README.md)): sectorul vine dintr-o singură versiune locală
  (`local-sectors-2026-09-26`, nume GICS, 15 revizuiri locale, fără istoric înainte de
  2026-09-26); profilul vine din SIC-ul SEC; grupul de normalizare este sectorul numai
  pentru GENERAL; industria nu este stocată (SIC există doar în răspunsurile SEC, 511/518).
  Cele 107 rânduri non-GENERAL (inclusiv UNH, ELV, CI, CNC, HUM ca INSURER) sunt scorate
  numai pe momentum, față de tot universul, deci nu sunt comparate în sector.
- [x] Raport pe sector și metrică pe `bead056b` (`quant_research.sector_audit`, numai
  citire): reconciliat cu publicația, 517 rânduri și 1.432 de valori, 0 diferențe. Fallback
  frecvent (book/market 27%; Financials și Real Estate fără cohortă primară contabilă),
  cohorte la prag (Utilities FCF exact 10), iar EBIT/EV și ROIC nu sunt normalizate nicăieri
  (`NOT_REPORTED`, cauza din P0, fără zero pentru componentele lipsă).
- [x] Comparația în cadrul sectorului în API (2026-11-06):
  `GET /screener/sectors/{sector}/comparison?runId=` și `GET /screener/sectors?runId=`.
  Valori brute, mediana pe metrică (eligibili GENERAL, un rând pe emitent), scoruri
  publicate, numărul de companii comparate și eticheta cohortei, toate din același run.
  Profilurile incompatibile sunt separate: `GENERAL` față de `PRICE_ONLY` (numai momentum).
- [x] Rang și percentilă în sector, distincte de rangul global și de normalizare: pe toți
  eligibilii grupului de comparație, înainte de paginare, în ordinea globală publicată;
  percentila cu rangul mediu al egalităților pe compozitul nerotunjit. Scorul publicat nu
  se schimbă. Verificat pe `bead056b`: toate cele 11 sectoare reconciliate cu auditul.
- [ ] Afișarea grupului real de comparație și avertizarea pentru eșantioane mici:
  API-ul le expune (`population`, `groups`, `warnings`, inclusiv
  `PUBLISHED_SCORES_NOT_RECALCULATED` și `SMALL_SAMPLE`); afișarea în UI rămâne în P2c.
- [ ] În P5, compararea normalizării pe sector cu cea pe industrie unde există
  clasificări și suficiente date. Evaluarea acoperirii, stabilității rangurilor
  și performanței istorice înaintea unei schimbări de model versionate.

Criteriu de finalizare: comparația pe sector este explicabilă și reconciliată cu
publicația. Nivelul de industrie rămâne o alternativă de evaluat, fără a fi
presupus superior sau înlocuit automat. Implementarea API/UI se coordonează cu P2b/P2c.

Pentru punctele rămase, auditul arată că o comparație trebuie să numească populația
(membrii sectorului sau grupul GENERAL: Financials 76 față de 13, Real Estate 30 față de 1),
să nu amestece scoruri numai-momentum cu cele GENERAL într-un rang de sector și să arate
pentru fiecare metrică dacă normalizarea a fost în sector sau pe fallback.

### P2s — Simplificarea arhitecturii, înaintea interfeței web

Adăugat la 2026-11-05, la cererea utilizatorului; **încheiat la 2026-11-06**
([dovezi](evidence/p2s/README.md)). Rulează 4 containere în loc de 17.
Constatare din analiza proiectului din aceeași zi: pentru un singur utilizator,
~518 instrumente și un scor lunar, aplicația rulează 17 servicii Docker. Sunt 7
servicii Spring, Kafka, Zookeeper, Elasticsearch, TimescaleDB și un indexer. Modelul
este implementat de două ori (Java și Python), cu certificare de paritate. Codul
de producție are ~16.000 de linii; testele (~370) sunt proporționale cu codul, dar
multe verifică infrastructura (roluri, Kafka, rate limiting, autentificarea între
servicii, paritate), nu regulile de date. Costul s-a văzut concret în P2b: limita
Gateway de 100 de cereri/minut, JWT-ul de 15 minute care a forțat paralelizarea
exportului, granturi DB per tabel. O mapare SEC nouă ar fi cerut pregătirea v5 în
ambele limbaje.

Se păstrează, ca reguli de date și nu ca infrastructură: `available_at`/`observed_at`,
cutoffurile, proveniența, publicațiile imuabile cu `runId`, excluderile cu motiv
explicit (nu zero), PostgreSQL/Timescale și migrările versionate. Fără rescriere
completă: aplicația funcționează și fiecare pas se face separat, cu verificare.
Simplificarea vine înaintea P2c, ca interfața să nu fie construită peste serviciile
care urmează să fie comasate.

- [x] **Decizia utilizatorului din 2026-11-05: Python** calculează și publică
  modelul; Java păstrează colectarea (`market-data-producer`) și servirea datelor
  (backend-ul web). Motive: backtestingul și variantele de formulă din P4/P5 cer
  formula, nu numai rezultatele; pregătirea, modelele 1.4/1.5, reproducerea și
  backtestul există deja în Python; modelul se schimbă des; performanța nu
  contează la ~518 instrumente. Condiție: publicația `bead056b` este reprodusă
  identic de publicarea Python înainte de eliminarea formulei Java. Publicațiile
  existente rămân reproductibile din arhivele lor (`research-engine/fixtures/*-archive`),
  care nu se rescriu.
- [x] Reproducerea ultimei publicații Java în Python (2026-11-05,
  [dovezi](evidence/p2s/README.md)). Pachetul `quant_research.publication` reface,
  numai prin citire, cererea, secțiunea canonică, acoperirea și pregătirea pentru
  `bead056b`. Documentul de input (127 MB) e identic octet cu octet, cu același
  SHA-256 și aceeași cheie logică. Toate tabelele de rezultate coincid exact.
- [x] Publicarea din Python: crearea runului, stările, certificarea, inserarea în
  loturi și publicarea atomică, cu aceleași reguli de imutabilitate (2026-11-05,
  [dovezi](evidence/p2s/README.md)). Verificarea s-a făcut pe o copie izolată,
  restaurată din backup. Runul Python `26f3c95a` pe aceleași inputuri ca `bead056b`
  are aceleași scoruri, factori, excluderi și surse. Singura diferență sunt
  timestamp-urile derivate din cutoff. Prima publicație Python pe baza principală
  rămâne pentru următoarea dată cu date colectate.
- [x] Testele de integrare ale regulilor de date portate în Python (2026-11-05):
  18 teste pentru interogarea de input (cutoffuri, revizuiri, calendar, clase de
  acțiuni, snapshoturi) și 21 pentru publicare (ciclul de viață, concurență, reluare,
  triggerele DB, politicile `CURRENT`/`PRE_OPEN`, acoperirea colectării). Rulează pe
  un container TimescaleDB temporar (`QUANT_DB_TESTS=1`). S-a portat și coordonatorul
  lunar (`month_end_request`). Nu s-au portat testele specifice modelelor Java
  1.2–1.4, aserțiunile pe interogarea veche `profiles-v1` și testul de plan de execuție
  la 600 de instrumente.
- [x] Eliminarea formulei Java și a parității ca etapă de publicare (2026-11-05).
  Au fost eliminate modelele 1.2–1.5, pregătirea, `ScoringRunService`,
  `ScoringInputRepository`, coordonatorul lunar, comenzile `score-and-exit`/
  `current-score-and-exit` și testele lor. Sursele (inclusiv cele niciodată comise)
  sunt arhivate în `research-engine/fixtures/model-v1.5-java-final-archive/`, cu
  manifest SHA-256. Interogările SQL au fost mutate în
  `research-engine/src/quant_research/publication/sql/`. Freeze-ul modelului are o
  revizie `relocation`: căi mutate, hash-uri de fișiere neschimbate. `scoring-service`
  păstrează ingestia Kafka, datele canonice și DLQ. Publicarea lunară automată nu mai
  există; era dezactivată în Java și rămâne pentru P7.
- [x] **Etapa A, 2026-11-05: fără Kafka/Zookeeper.** Outbox-ul se livrează direct din
  PostgreSQL de `OutboxDeliveryWorker` (Scoring). Livrarea folosește lease,
  `SKIP LOCKED`, ordinea pe instrument, același inbox și același nume de consumator,
  retry cu backoff, DLQ în DB și replay auditat. Înainte de eliminare, toate cele
  1.294.435 de evenimente erau livrate, fără pierderi. Au fost eliminate relay-ul,
  configurațiile Kafka, publisher-ul legacy (`MarketDataIngestionJob`,
  `HistoricalBackfillRunner`), serviciile `kafka`/`zookeeper` și certificatele lor din
  compose. Lockfile-urile sunt actualizate. Teste: Scoring 52 și producer 47 (doar
  PostgreSQL), migrări 19. Rulează 5 containere.
- [x] **Etapa B, 2026-11-06:** procesarea canonică este în colector, în aceeași
  tranzacție cu staging-ul paginii (`OutboxDeliveryWorker.deliverStaged`), iar
  `scoring-service` a fost eliminat. Inbox-ul, cu același consumator `scoring-service-v2`,
  păstrează idempotența; checkpointurile, încercările auditate și artefactele sursă sunt
  neschimbate. O observație invalidă intră în DLQ printr-un savepoint, fără să piardă
  restul paginii; o eroare tranzitorie anulează pagina și elementul se reia. Bucla de
  livrare a rămas numai pentru evenimentele `PENDING` (ordine pe instrument, replay DLQ).
  Comenzile `dlq-inspect`/`dlq-replay` sunt în colector (`CANONICAL_OPERATION`).
  Verificare: test de colectare întreruptă și reluată de la checkpoint, fără duplicate
  sau pierderi, plus rollback complet la eroare tranzitorie și rularea întregului flux
  cu rolul `quant_ingestion`. Pe baza reală: 0 evenimente în așteptare la trecere,
  `dlq-inspect` rulat cu rolul nou. Calculatorul demonstrativ Java (`public.factor_scores`,
  fără scheduler) a fost eliminat; proiecția legacy `market_bars`/`fundamental_snapshots`
  e păstrată în colector.
- [x] Căutarea companiilor în PostgreSQL, fără Elasticsearch și `search-indexer`
  (2026-11-05). Cuvintele se potrivesc toate, fără diferență de majuscule, cu
  relevanță după vechile boost-uri (simbol > nume > sector/SIC). `GET /screener/search`
  păstrează forma răspunsului. `/screener/search/status` răspunde `LIVE`/`POSTGRESQL`.
  Diferență: fără potrivire aproximativă. Rulează 7 containere în loc de 9.
  Curățat ulterior, tot la 2026-11-05: V025 elimină tabelul
  `operations.search_rebuild_checkpoints`, rolul `quant_search` nu mai are drepturi și
  a fost șters local, iar indexarea dezactivată din Scoring (`CompanyMetadataIndexer`,
  `ElasticsearchConfiguration`, `scoring.elasticsearch.*`) a fost eliminată. Volumul
  `quant-platform_elasticsearch-data` rămâne de șters manual. Constatare: întârzierea constantă de ~2 s a clienților Python venea din
  `localhost` → IPv6 pe Windows, nu de la servicii; exportul folosește acum `127.0.0.1`.
- [x] Un singur backend web (`web-backend`, 2026-11-06) în locul auth, portfolio,
  screener și gateway; modulele sunt pachete (`com.quantplatform.auth`, `.portfolio`,
  `.screener`), fiecare cu tratarea erorilor proprie. Autentificarea rămâne obligatorie;
  JWT-ul se validează o singură dată în proces. Au fost eliminate limita de 100 de
  cereri/minut (429) și header-ul de identitate dintre servicii; expunerea publică rămâne
  în P7. Portul 18080 și contractele din ENDPOINTS.md sunt neschimbate: exportul
  universului (pagini, RSI, CSV, HTML) e identic octet cu octet cu cel din Screenerul vechi,
  la fel 49/49 de cereri comparate (filtre, căutare, financiare `SUMMARY`/`FULL`, erori).
- [x] Scripturile de audit de o singură folosință sunt arhivate (2026-11-05) în
  `research-engine/archive/p0_p2_audits/`: 9 module și 8 fișiere de test (51 de teste,
  rulate separat, inclusiv în CI). `quant_research.source_audit` păstrează numai
  `query`, folosit de `publication_audit` (din freeze). Dovezile nu au fost
  modificate; rapoartele P0/P2 au o notă cu noile comenzi.
- [x] Rolurile DB reduse prin V026 (2026-11-06), după backupul verificat
  `local-backups/p2s-final-20261106/` (restaurat complet într-o bază nouă, cu numărători
  identice): `quant_ingestion` (colector + canonic), `quant_scoring` (publicare Python),
  `quant_web` (backend) și `quant_research`. `quant_auth`, `quant_portfolio` și
  `quant_screener` nu mai au granturi; ștergerea lor rămâne pentru administrator. Aplicat
  întâi pe copia restaurată, apoi pe baza locală, cu drepturi efective identice.
- [x] Testele infrastructurii eliminate au dispărut odată cu ea: rate limiting și
  header-ul de identitate (Gateway), parserul Kafka, calculatorul demonstrativ și
  verificările Kafka/Elasticsearch din politica de securitate. Testele regulilor de date
  au fost mutate în colector (livrare, prețuri, filinguri, TTM, cutoffuri, calendar),
  plus 8 teste noi pentru etapa B. Formula, excluderile și exportul au testele Python.
  Java: 179 de teste înainte, 177 după (toate trecute); Python: 260 + 51 arhivate.

Riscuri: pierderea garanțiilor de idempotență la eliminarea outboxului, rescrierea
accidentală a publicațiilor vechi, schimbarea contractelor API. Fiecare pas are
backup verificat, comparație înainte/după pe ultima publicație și actualizarea
documentației. Nu se amestecă schimbări de formulă cu restructurarea.

Criteriu de finalizare: aproximativ 5 containere (PostgreSQL/Timescale, migrări,
colector, backend web, eventual job de scoring) în loc de 17; ultima publicație
reprodusă identic cu implementarea păstrată; exportul universului și endpointul
financiar returnează aceleași valori ca înainte; build și teste mai rapide, cu
timpii măsurați înainte și după.

Rezultat 2026-11-06 (detalii în [dovezi](evidence/p2s/README.md)): 4 containere
(PostgreSQL, migrări, colector, backend web), publicarea rulând la cerere din Python;
`bead056b` reprodusă din nou cu rolul `quant_scoring` după V026; exportul și endpointul
financiar identice; module Gradle 10 → 6, imagini 7 → 3, procese JVM permanente 6 → 2.
Timpii sunt în dovezi; câștigul de timp la build și teste este mic, iar testele de
integrare ale colectorului au crescut, fiindcă includ acum procesarea canonică.

### P2c — Interfață web: prima versiune utilizabilă

Planificată după lista completă P1, endpointul financiar P2b și simplificarea
P2s, înaintea deploymentului. Designul și integrarea API-urilor existente pot începe mai devreme;
prima versiune nu depinde de finalizarea pilotului european sau a backtestingului.
Alegerea frameworkului și a stilului vizual se stabilește la începutul etapei.

- [ ] Definirea navigației și a ecranelor: autentificare, clasament, detaliu
  companie și portofoliu. Layout adaptat pentru desktop și mobil, controale
  accesibile din tastatură, contrast și etichete clare.
- [ ] Înregistrare/login prin backend-ul web (fostul Gateway, același port), rute protejate și gestionarea expirării
  sesiunii. API-ul actual nu are refresh/logout; deconectarea locală elimină
  sesiunea din client, fără promisiunea revocării tokenului pe server.
- [ ] Pagina de clasament: căutare, filtrele disponibile în API, sortare,
  paginare și selecție între întregul univers, eligibili și excluși. Păstrarea
  aceluiași `runId` la navigarea între pagini și detaliile unei publicații.
- [ ] Afișarea scorului compozit, Value/Quality/Momentum, eligibilității,
  motivelor excluderii și acoperirii. Scorurile absente nu apar ca zero;
  rangul și percentila au explicația populației de comparație.
- [ ] Pagina companiei: datele financiare și indicatorii din P2b, perioade,
  unități/monede și surse, alături de scoruri și contribuțiile disponibile.
  Companiile excluse rămân accesibile, cu lipsurile explicate.
- [ ] Contextul rezultatelor: data economică, publicarea, actualitatea și
  statutul de cercetare al modelului, exprimate pe înțelesul utilizatorului.
- [ ] Portofoliu: listare, adăugare, editare și ștergere a deținerilor prin
  API-ul existent. Profit/pierdere, tranzacții la broker și randamente istorice
  nu sunt funcționalități ale primei versiuni fără backend dedicat.
- [ ] Stări de încărcare, lipsă publicație, rezultate goale, date incomplete,
  sesiune expirată și servicii indisponibile; păstrarea filtrelor în navigație.
- [ ] Export al universului prin utilitarul/contractul stabilizat în P1;
  dacă exportul există numai ca CLI, planificarea unui API sau a unui flux client
  paginat, cu limite explicite, înaintea adăugării butonului de descărcare.
- [ ] Integrare numai prin backend-ul web, configurare CORS pentru originile permise,
  strategie explicită de păstrare a sesiunii și fără secrete de furnizor în browser.
- [ ] Verificări ale fluxurilor principale: login → clasament → companie,
  instrument exclus, portofoliu, expirare sesiune și navigare pe mobil.

Criteriu de finalizare: utilizatorul poate explora datele reale local, poate
înțelege scorurile/excluderile și își poate gestiona deținerile fără Postman sau
interogări manuale. Graficele de istoric și pagina de backtesting se adaugă după
P4/P5, cu un contract API pentru rezultatele validate; backendul actual de
backtesting este CLI. Filtrele europene vin odată cu P6.

### P3 — Acoperirea profilurilor financiare și REIT

- [ ] Audit al clasificărilor GENERAL, FINANCIAL, INSURER, EQUITY_REIT și UNKNOWN.
- [ ] Mapare explicită către profilurile pe care modelul le poate calcula.
- [ ] Colectare și normalizare a datelor suplimentare pentru bănci, asigurători
  și REIT; activare în adaptor numai după validarea datelor necesare.
- [ ] Validare pe exemple reale și verificare de paritate Java/Python.

Criteriu de finalizare: fiecare profil activat are date, formule și cazuri
verificate; profilurile încă nesuportate sunt vizibile și explicate.

### P4 — Istoric și infrastructură pentru backtesting

Constatare: auditul publicației verifică o fereastră de 253 observații ajustate
pentru calculul Momentum și 20 de sesiuni pentru lichiditate. Acesta nu este un
inventar al întregii baze. Configurația vechiului backfill are implicit 365 de zile,
iar colectarea SEC are data inițială implicită 2010-01-01; configurarea nu dovedește
că toate datele au fost descărcate. Validatorul actual cere cel puțin 180 de luni
complete ca parte a dovezilor și rămâne `DIAGNOSTIC_ONLY`.

- [ ] Inventar real al datelor: min/max date, goluri și acoperire pe instrument,
  dataset, raportare și componența indicilor; separarea ferestrei necesare unui
  scor de perioada disponibilă pentru evaluarea performanței.
- [ ] Evaluare a surselor istorice, licențelor și costurilor pentru prețuri,
  raportări originale/revizuite, componențe datate, delistări și acțiuni corporative.
- [ ] Pilot de 3–5 ani pentru verificarea fluxului, apoi țintă de 15–20 ani
  pentru evaluare, condiționată de date verificabile. Pilotul nu aprobă modelul.
- [ ] Date anterioare începutului evaluării pentru inițializarea indicatorilor:
  aproximativ un an de prețuri pentru Momentum, plus istoricul financiar cerut
  de bilanțurile comparative și estimările multianuale ale modelului.
- [ ] Reconstrucție a universului la fiecare dată, inclusiv companii ulterior
  eliminate sau delistate; evitarea folosirii celor 518 instrumente actuale ca
  univers fix pentru întreaga perioadă.
- [ ] Politică explicită de disponibilitate istorică: păstrarea distinctă a datei
  publicării și a datei descărcării. Codul actual filtrează și după `observed_at`;
  importul de azi nu permite automat scoruri în trecut. Definirea unui flux de
  reconstrucție cu dovezi istorice, fără falsificarea timestampurilor sau
  eliminarea globală a protecțiilor temporale.
- [ ] Generare de scoruri lunare reproductibile și randamente ulterioare;
  reguli explicite pentru selecție, ponderare, rebalansare, momentul execuției,
  dividende, splituri, delistări, comisioane și slippage.
- [ ] Evaluare a rankingului prin corelația rang–randament și diferența între
  grupele superioare/inferioare; evaluare distinctă a portofoliului prin randament,
  volatilitate, drawdown, turnover și comparații cu benchmarkuri compatibile.
- [ ] Partiționare cronologică 60/20/20, ferestre walk-forward și separarea
  observațiilor cu randamente viitoare care traversează granițele partițiilor;
  păstrarea testului final închis până la fixarea modelului.
- [ ] Raport de acoperire pe lună/profil/sector și teste de integritate temporală,
  pentru a identifica selecția indusă de datele lipsă și perioadele nereprezentative.

Criteriu de finalizare: pilot reproductibil, inventar de lipsuri și apoi istoric
extins cu proveniență și evaluare în afara perioadei de dezvoltare. Durata singură
nu aprobă modelul; gate-ul de 15 ani existent este o cerință a proiectului, nu o
garanție statistică universală. Investigarea surselor începe în timpul P2/P3;
validarea variantelor de formulă depinde de această infrastructură.

Referință metodologică pentru evitarea informației din viitor și a selecției
supraviețuitorilor: [QuantConnect Research Guide](https://www.quantconnect.com/docs/v2/writing-algorithms/key-concepts/research-guide).

### P5 — Evaluarea unei politici mai flexibile pentru metricile lipsă

Actualizare 2026-10-26: implementarea unei formule flexibile cu obiectivul
scorurilor pentru întregul univers a fost cerută explicit și adăugată în P2.
P5 păstrează evaluarea istorică și compararea variantelor; creșterea acoperirii
nu demonstrează singură performanța predictivă.

- [ ] Comparare model actual cu variante explicite: ponderi redistribuite între
  metricile disponibile, metrici alternative justificate pe profil și scoruri
  parțiale etichetate separat de scorul compozit complet.
- [ ] Evaluare a efectului asupra rangurilor, sectoarelor, grupurilor de
  normalizare și companiilor cu date puține; analiză de sensibilitate la lipsuri.
- [ ] Separare între indicatorul de acoperire a datelor și o eventuală măsură
  de încredere; acoperirea singură nu demonstrează calitatea predictivă.
- [ ] Validare istorică pe date disponibile la momentul evaluat, când există
  istoric suficient, inclusiv costuri; păstrarea explicită a limitărilor.
- [ ] Dacă adoptăm o variantă: versiune nouă de model, manifest și fixture-uri
  actualizate, paritate Java/Python și comparație documentată cu 1.2.0.

Criteriu de finalizare: alegerea formulei are dovezi privind acoperirea și
comparabilitatea. Mai multe rânduri cu scor nu sunt singurul criteriu de succes.

### P6 — Pilot european

- [ ] Alegerea universului pilot după verificarea surselor, licențelor,
  costurilor și acoperirii istorice; indicele exact rămâne de stabilit.
- [ ] Extinderea identificatorilor, burselor, calendarelor și gestionării
  listărilor multiple; reguli explicite pentru clasa/listarea principală.
- [ ] Furnizori și mapări pentru raportări europene/IFRS și prețuri ajustate.
- [ ] Suport pentru moneda raportării, moneda prețului și conversii FX datate;
  eliminarea presupunerilor USD din adaptoare după definirea regulilor.
- [ ] Filtre regiune/țară/bursă/monedă și clasamente regionale inițiale.
- [ ] Validarea comparabilității înainte de introducerea unui clasament global.

Criteriu de finalizare: pilot cu identitate, date și calcule verificate cap-coadă,
raport de acoperire și reguli explicite de comparație SUA–Europa.

### P7 — Verificarea resurselor, alegerea serverului și rulare continuă

Etapă de final, după remedierea problemelor de date, acoperirea profilurilor și evaluarea modelului prin backtesting. Dimensionarea și alegerea serverului se fac pe aplicația stabilizată. Verificările locale de colectare necesare etapelor anterioare se execută în cadrul acestora. Nu presupune publicarea imediată a API-ului.

Codul existent are recuperare și cicluri periodice în `catch-up-and-serve`,
checkpointuri persistente, outbox și reconciliere lunară condiționată de date.
Activarea efectivă depinde de configurație și de serviciile pornite. Un proces
oprit nu colectează; datele persistate se reutilizează la repornire. Seriile
ajustate și reviziile pot necesita recolectări intenționate, versionate.

- [ ] Verificarea configurației reale: colectori activați, consumator Scoring,
  relay, calendar, snapshoturi de univers, dataseturi și scheduler lunar.
- [ ] Separarea backfillului inițial de actualizările zilnice și recuperarea
  golurilor; audit al volumului de recolectare a seriilor ajustate pentru fiecare
  dată de ajustare, al cotelor furnizorilor și al duratei până la cutoff.
- [ ] Programarea prețurilor după disponibilitatea sesiunii complete, colectarea
  periodică a raportărilor și actualizarea calendarului/componenței indicilor.
  Frecvența se stabilește după datele necesare; modelul lunar nu cere tickuri live.
- [ ] Stări operaționale explicite: sincronizare, date restante, pregătit pentru
  calcul, publicat, blocat și cutoff ratat, cu motive și date de actualizare.
- [ ] Politică pentru cutoff ratat: păstrarea scorului lunar istoric numai dacă
  datele îndeplinesc disponibilitatea cerută. Recuperarea după termen nu schimbă
  `observed_at`. Un snapshot curent se publică separat, cu cutoff și etichetă proprii.
- [ ] Evitarea retry-urilor fără rezultat pentru perioade imposibil de completat
  sub politica temporală curentă; alertare și posibilitate de reluare auditată.
- [ ] Publicare atomică atunci când datele obligatorii sunt pregătite și fiecare
  membru are rezultat sau excludere; nu se așteaptă eligibilitatea tuturor.
  Păstrarea ultimei publicații complete în timpul sincronizării.
- [ ] Verificare locală înainte de alegerea serverului: recuperare automată la
  repornire și programarea colectării, cu dovezi că progresul se păstrează.
- [ ] Măsurare RAM, CPU, spațiu pe disc și I/O pentru fiecare serviciu și pentru
  întregul stack, în repaus, colectare zilnică, backfill și calcul de scoruri;
  înregistrarea vârfurilor și duratei operațiilor, inclusiv când se suprapun.
- [ ] Estimare a creșterii stocării pentru istoric, surse brute, PostgreSQL,
  Kafka, Elasticsearch, loguri și backupuri, cu retenție explicită și rezervă
  de capacitate. Separarea necesarului inițial de cel pentru 15–20 ani de istoric.
- [ ] Raport înainte de închiriere: configurație minimă și recomandată
  (vCPU/RAM/disc), cost lunar estimat inclusiv backup și trafic, limite și
  criterii de redimensionare. Alegerea serverului se face după aceste verificări;
  deploymentul privat începe după rezolvarea problemelor prioritare și verificarea fluxului local.
- [ ] Pregătire deployment privat pe o mașină disponibilă permanent: resurse
  măsurate, volume persistente, configurare/secrete separate, restart automat,
  migrare controlată și monitorizare a restanțelor și ultimului scor publicat.
- [ ] Backup în afara mașinii și probă de restaurare; verificarea recuperării
  după oprire, fără duplicate sau pierderea checkpointurilor.
- [ ] Estimare costuri și alegere găzduire pe baza măsurătorilor. Expunerea publică
  a backend-ului web/UI este o etapă separată, cu TLS și controlul accesului.

Criteriu de finalizare: cicluri zilnice automate și o publicație lunară verificată
cap-coadă, plus o probă de oprire/repornire cu recuperare și alertare la cutoff ratat.
Găzduirea continuă reduce întreruperile, dar nu rezolvă singură lipsurile de date.

## Pas extra — Refactorizare și simplificarea codului

Notă 2026-11-05: comasarea serviciilor și eliminarea Kafka/Elasticsearch sunt în
P2s. Punctele de mai jos care privesc cod eliminat acolo (de ex. outboxul din
`IngestionCoordinator` sau paritatea din adaptoare) se reevaluează după P2s.
Notă 2026-11-06: P2s este încheiat. `CanonicalModelInputs` și `FrozenModel` nu mai
există în Java (modelul e numai în Python); `RankingPage`/`RankingQueryRepository` sunt
acum în `web-backend`.

Etapă transversală, executată incremental împreună cu taskurile funcționale
relevante. Constatările provin din inspecția codului; câștigurile de performanță
nu sunt încă măsurate. Refactorizările păstrează comportamentul, contractele și
rezultatele numerice; schimbările de reguli se tratează separat, cu versionare.

- [ ] **Prioritate 1, alături de P0/P2/P2b:** separarea selecției perioadelor,
  calculului TTM și construirii provenienței în `CanonicalModelInputs`.
  Folosirea directă a inputurilor tipizate în locul conversiei obiect → JSON → map,
  păstrând serializarea canonică la granițele unde este necesară pentru audit/hash.
  Reutilizarea derivărilor în endpointul financiar, fără formule paralele.
- [ ] **Prioritate 2, înaintea integrării UI:** înlocuirea graduală a
  `Map<String, Object>` cu DTO-uri/record-uri pentru publicații, rezultate,
  metrici și proveniență, începând cu `RankingPage` și `RankingQueryRepository`.
  Păstrarea numelor JSON, a preciziei numerice și a semanticii valorilor lipsă.
- [ ] **Prioritate 3, alături de P2:** separarea planificării, execuției
  colectorilor și verificării acoperirii din `IngestionCoordinator`.
  Extragerea responsabilităților pentru identitate, simboluri și clasificări
  din `UniverseSnapshotImporter`, păstrând tranzacțiile și idempotenta.
- [ ] **Diagnostic operațional, alături de P0/P2:** înlocuirea erorilor prea
  generale cu motive distincte pentru furnizor, validare, date lipsă și cutoff
  ratat; păstrarea contextului util fără secrete. Modificarea politicii de retry,
  dacă este necesară, se verifică separat ca schimbare de comportament.
- [ ] **Lizibilitate, pe componentele modificate:** reformatarea codului dens
  din `FrozenModel`, adaptoare și servicii de scoring; extragerea unor metode
  pentru validare, derivare, normalizare și agregare. Evitarea schimbărilor de
  formulă amestecate în același patch cu reformatarea.
- [ ] **Performanță, înaintea backtestingului extins din P4:** măsurarea costului
  normalizării în Java/Python și evaluarea calculării statisticilor o singură
  dată per metrică/profil/grup în cadrul unui run. Păstrarea exactă a selecției
  cohortelor, fallbackurilor, ordinii operațiilor relevante și toleranțelor.
  Comparație înainte/după pentru timp, memorie și rezultate.
- [ ] **Curățare legacy, după stabilizarea fluxului canonic:** inventarierea
  apelanților, configurațiilor, testelor și utilizatorilor Python/DB ai fluxurilor
  vechi. Izolarea lor și eliminarea numai după verificarea lipsei dependențelor;
  eventualele eliminări de schemă se fac prin migrări noi.

Criteriu de finalizare pentru fiecare intervenție: teste relevante și contracte
compatibile; pentru model/adaptoare, paritate Java/Python și verificarea
serializării/hashurilor afectate. Pentru ingestie/import, verificarea reluărilor,
deduplicării și rollbackului. Beneficiile de performanță se raportează numai
după măsurare. Nu este necesară rescrierea întregii aplicații înaintea taskurilor
de produs și nu se creează servicii noi doar pentru separarea unor clase.

## Reguli pentru actualizarea planului

P0, P1, P2, P2b și P2s sunt încheiate; urmează P2c; P2b.1 se poate face în paralel. Verificarea resurselor, alegerea serverului și deploymentul rămân la final, în P7, după rezolvarea problemelor prioritare.
P2b expune datele financiare, iar P2c construiește UI-ul pe contractele P1/P2b;
UI-ul inițial poate fi folosit local înaintea validării istorice complete.
P2 și P3 pot fi reordonate după numărul de
instrumente recuperabile și disponibilitatea surselor. Inventarul și evaluarea
surselor din P4 încep devreme; P5 depinde de diagnosticul datelor și de infrastructura
de backtesting. Amploarea P6 se stabilește după lecțiile din universul actual.
Noile cerințe discutate se adaugă cu dependențe și criterii de finalizare.
