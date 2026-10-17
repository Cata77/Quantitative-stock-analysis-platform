# Plan de îmbunătățire a platformei

Actualizat: 2026-10-17. Document de lucru, reordonat pe măsură ce discutăm cerințele.
Taskurile de mai jos sunt planificate; acest document nu reprezintă implementarea lor.

## Obiective

- Afișarea întregului univers, cu scoruri și motive explicite pentru lipsa lor.
- Creșterea acoperirii prin îmbunătățirea datelor și a profilurilor suportate.
- Expunerea datelor financiare și a indicatorilor care explică scorurile.
- Interfață web pentru explorarea universului, explicarea scorurilor și dețineri.
- Evaluarea unei formule mai flexibile pe dovezi reproductibile.
- Extinderea în Europa după validarea colectării și calculului actual.

## Situația verificată

Surse locale: `build/local-step5/current-20261001/rankings.csv`,
`exclusions.json`, `publication-verification.json`; manifestul modelului și codul
adaptorului canonic. Acestea descriu publicația din 2026-10-01, cu data economică
2026-09-30, nu o verificare live a bazei de date la data acestui document.

- Univers: reuniunea S&P 500 și Nasdaq-100, 518 instrumente unice.
- Rezultate: 49 eligibile și 469 excluse; acoperire de aproximativ 9,46%.
- Model: 1.2.0, ponderi Value/Quality/Momentum de 50/30/20.
- GENERAL are trei metrici Value, trei Quality și una Momentum. Cere minimum
  două metrici valide în fiecare dintre primele două familii și Momentum valid,
  inclusiv verificările de normalizare și celelalte condiții de eligibilitate.
- 48 de rânduri din CSV au două metrici lipsă, iar unul are o metrică lipsă.
  Prin urmare, formula nu cere toate cele șapte metrici.
- După îndeplinirea acoperirii minime, componenta normalizată lipsă contribuie cu
  zero, păstrând numitorul familiei la trei. Asta nu înseamnă că datele financiare
  brute lipsă sunt considerate zero.
- API-ul suportă deja `eligibility=ALL`, `EXCLUDED`, `ELIGIBLE`; implicit este
  `ELIGIBLE`. Filtrele se aplică publicației selectate, fără recalcularea rangului.

Principalele motive din `exclusions.json`, numărând instrumente distincte la
stadiul ELIGIBILITY și grupând codurile cu detalii după prefix:

| Motiv | Instrumente afectate |
|---|---:|
| INSUFFICIENT_VALUE_COVERAGE | 363 |
| INSUFFICIENT_QUALITY_COVERAGE | 200 |
| MODEL_NOT_SUPPORTED | 156 |
| CORPORATE_ACTION_REVIEW | 69 |
| MISSING_INSTRUMENT_SHARES | 59 |
| INVALID_MARKET_CAP | 59 |
| STALE_SHARES | 19 |

Motivele se suprapun: același instrument poate avea mai multe blocaje. Numerele
nu se adună și nu reprezintă câte companii ar deveni eligibile printr-o singură
remediere. `MODEL_NOT_SUPPORTED` trebuie investigat pe profil și pe ramura care
îl produce; nu echivalează automat cu numărul companiilor financiare. Similar,
`NOT_REPORTED` nu dovedește singur că informația lipsește din raportarea originală.

## Ordinea de lucru

### P0 — Diagnostic reproductibil al excluderilor

- [ ] Generare raport pe instrument: profil, sector, date brute necesare,
  metrici disponibile/lipsă, surse, perioade și toate blocajele.
- [ ] Separare între lipsa sursei, mapare incompletă, perioade TTM incompatibile,
  date vechi, profil nesuportat și filtre economice/de identitate.
- [ ] Verificare a unor cazuri reprezentative, inclusiv AAPL, până la raportarea
  sursă; audit al mapărilor SEC, capitalurilor proprii, EBIT/EV și ROIC.
- [ ] Analiză a suprapunerii motivelor și a companiilor recuperabile integral
  prin fiecare remediere; prioritizare după impact verificat.
- [ ] Verificare a integrității exportului: CSV-ul inspectat conține `GE/NERAL`
  la LULU. Comparare cu publicația înainte de a atribui problema modelului.

Criteriu de finalizare: toate cele 469 de excluderi au explicații trasabile;
raportul reconciliază 518 = 49 + 469 și identifică intervențiile cu impact maxim.

### P1 — Vizibilitatea întregului univers în listă și export

- [ ] Folosire explicită a `eligibility=ALL` pentru vizualizarea universului,
  păstrând opțiunea de a vedea numai clasamentul eligibil.
- [ ] Export complet, paginat cu același `runId`, cu un rând per instrument:
  simbol, profil, sector, eligibilitate, scor/rang dacă există, motive, acoperire,
  data scorului și versiunea modelului.
- [ ] Pentru instrumentele excluse, scorul compozit și rangul rămân neatribuite;
  valorile parțiale se afișează numai dacă sunt disponibile și identificabile.
- [ ] Afișare a totalului universului, eligibililor, exclușilor și acoperirii;
  explicarea populației față de care sunt calculate rangul și percentila.
- [ ] Mutare a exportului într-un utilitar întreținut în repo, cu verificarea
  duplicatelor, numărului de rânduri și concordanței cu publicația.

Criteriu de finalizare: pentru publicația existentă, lista/exportul complet are
518 instrumente unice, 49 cu rang și 469 cu motive explicite. Această schimbare
de prezentare nu necesită modificarea formulei.

### P2 — Refactorizarea colectării și pregătirii datelor

Ordinea intervențiilor din această etapă va urma raportul P0.

- [ ] Îmbunătățire mapări SEC și derivări contabile justificate, cu proveniență.
- [ ] Audit al selecției perioadelor, TTM, bilanțurilor comparabile și faptelor
  ambigue în adaptoarele Java/Python; păstrarea echivalenței dintre implementări.
- [ ] Remediere a datelor lipsă/vechi despre numărul de acțiuni și capitalizare,
  ținând cont de clase, splituri și data disponibilității informației.
- [ ] Flux explicit de verificare și rezolvare pentru acțiuni corporative și
  identitatea instrumentelor, fără eliminarea globală a blocajelor.
- [ ] Recuperare a sesiunilor/prețurilor lipsă, unde istoricul există efectiv.
- [ ] Motive de eroare suficient de precise pentru a distinge lipsurile reale
  de problemele de colectare, mapare sau calcul.

Criteriu de finalizare: comparație înainte/după pe date controlate, cu numărul
de instrumente recuperate și explicația fiecărei schimbări. Nu se fixează acum
o țintă arbitrară de eligibilitate de 100%. Publicațiile vechi rămân reproductibile;
corecțiile se publică în execuții noi, cu versiunile relevante actualizate.

### P2b — Endpoint pentru date financiare și explicarea scorului

Funcționalitate nouă planificată în Screener, după stabilizarea mapărilor și
pregătirii datelor din P2. Contractul poate fi definit în timpul diagnosticului.

Rută propusă, încă neimplementată:
`GET /screener/instruments/{instrumentId}/financials?runId={runId}`.
Identificatorul și runId provin din rankings; accesul folosește `research:read`.

- [ ] Definirea unui DTO explicit și documentarea contractului în ENDPOINTS.md
  odată cu implementarea. Prima versiune cere `runId` pentru a lega datele de
  publicația consultată; nu combină implicit date curente cu un scor istoric.
- [ ] Expunerea datelor disponibile relevante pentru scor: venituri, profit net,
  profit operațional, active, capitaluri proprii, datorii, numerar, flux de numerar
  operațional, CAPEX, număr de acțiuni și capitalizare.
- [ ] Expunerea indicatorilor derivați disponibili, precum FCF, EBIT/EV,
  FCF yield, book/market și ROIC, cu unitate, formulă/versiune și proveniență.
  Separarea valorilor contabile raportate, derivărilor și scorurilor normalizate.
- [ ] Pentru fiecare valoare: monedă/unitate, început/sfârșit de perioadă sau
  dată de bilanț, tipul perioadei (trimestrial/anual/TTM), data publicării,
  sursele și starea disponibilității. Returnarea numai a perioadelor efectiv
  existente sau derivabile justificat, fără a promite situații financiare complete.
- [ ] Citirea inputurilor/provenienței păstrate pentru runul publicat și
  reutilizarea derivărilor versionate din scoring; evitarea unei formule paralele
  în Screener. Audit al permisiunilor DB și migrare dacă sunt necesare drepturi noi.
- [ ] Suport pentru instrumentele excluse: datele disponibile rămân accesibile,
  iar lipsurile sunt `null` cu motiv explicit, inclusiv când scorul nu există.
- [ ] Contract de erori: 400 pentru parametri invalizi; 404 pentru instrument,
  publicație sau apartenență la run inexistente; 200 cu lipsuri explicite pentru
  un instrument prezent în publicație, dar fără toate datele financiare.
- [ ] Teste de integrare pentru concordanța cu runul, unități/perioade, absența
  informațiilor ulterioare cutoffului, instrument exclus și controlul accesului.

Criteriu de finalizare: pornind de la un rând din rankings, clientul poate obține
datele financiare disponibile și indicatorii aferenți aceleiași publicații,
inclusiv explicațiile lipsurilor. Nu sunt declanșate descărcări de la furnizori
în timpul cererii. Istoricul paginat al raportărilor și vizualizarea celor mai
recente date independent de run rămân extensii de discutat.

### P2b.1 — Verificarea comparațiilor pe sector și industrie

Constatare din cod: pentru GENERAL, `scoring-inputs.sql` setează `peerGroup` la
`mapped_sector`. Normalizarea folosește minimum 10 observații compatibile pentru
metrica respectivă în grupul primar; când nu sunt suficiente, folosește minimum
20 din același profil, cu avertisment `PEER_FALLBACK`. Cohorta este construită
după filtrele inițiale de eligibilitate/acoperire, înaintea eligibilității finale.
Filtrul API `sector` există, dar nu recalculează scorurile sau rangul global.

- [ ] Audit al clasificărilor și al provenienței: sector, industrie, profil și
  grup efectiv de normalizare sunt concepte distincte. Etichetele locale nu
  reprezintă automat un istoric GICS licențiat.
- [ ] Raport pe sector și metrică: total membri, acoperire, cohortă efectivă,
  număr de observații și frecvența fallbackului; verificarea publicației reale.
- [ ] Definirea comparației în cadrul sectorului în API/UI: valori brute,
  mediana metricii, scoruri publicate și numărul de companii comparate, folosind
  același run și perioade/unități comparabile. Separarea profilurilor incompatibile.
- [ ] Evaluarea unui rang/percentile în sector, distinct de rangul global și de
  normalizarea factorilor. Definirea populației și a egalităților; calcul pe
  întreaga populație selectată, înainte de paginare, fără schimbarea scorului publicat.
- [ ] Afișarea grupului real de comparație și avertizarea pentru eșantioane mici;
  filtrarea pe sector nu trebuie etichetată drept recalculare sectorială.
- [ ] În P5, compararea normalizării pe sector cu cea pe industrie unde există
  clasificări și suficiente date. Evaluarea acoperirii, stabilității rangurilor
  și performanței istorice înaintea unei schimbări de model versionate.

Criteriu de finalizare: comparația pe sector este explicabilă și reconciliată cu
publicația. Nivelul de industrie rămâne o alternativă de evaluat, fără a fi
presupus superior sau înlocuit automat. Implementarea API/UI se coordonează cu P2b/P2c.

### P2c — Interfață web: prima versiune utilizabilă

Planificată după lista completă P1 și endpointul financiar P2b, înaintea
deploymentului. Designul și integrarea API-urilor existente pot începe mai devreme;
prima versiune nu depinde de finalizarea pilotului european sau a backtestingului.
Alegerea frameworkului și a stilului vizual se stabilește la începutul etapei.

- [ ] Definirea navigației și a ecranelor: autentificare, clasament, detaliu
  companie și portofoliu. Layout adaptat pentru desktop și mobil, controale
  accesibile din tastatură, contrast și etichete clare.
- [ ] Înregistrare/login prin Gateway, rute protejate și gestionarea expirării
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
- [ ] Integrare numai prin Gateway, configurare CORS pentru originile permise,
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

Decizie deschisă: formula nu se relaxează automat pentru a obține 518 scoruri.
Propunerile se compară după corectarea principalelor probleme de date.

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
  a Gateway/UI este o etapă separată, cu TLS și controlul accesului.

Criteriu de finalizare: cicluri zilnice automate și o publicație lunară verificată
cap-coadă, plus o probă de oprire/repornire cu recuperare și alertare la cutoff ratat.
Găzduirea continuă reduce întreruperile, dar nu rezolvă singură lipsurile de date.

## Pas extra — Refactorizare și simplificarea codului

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

P0 și P1 au prioritate imediată. Verificarea resurselor, alegerea serverului și deploymentul rămân la final, în P7, după rezolvarea problemelor prioritare.
P2b expune datele financiare, iar P2c construiește UI-ul pe contractele P1/P2b;
UI-ul inițial poate fi folosit local înaintea validării istorice complete.
P2 și P3 pot fi reordonate după numărul de
instrumente recuperabile și disponibilitatea surselor. Inventarul și evaluarea
surselor din P4 încep devreme; P5 depinde de diagnosticul datelor și de infrastructura
de backtesting. Amploarea P6 se stabilește după lecțiile din universul actual.
Noile cerințe discutate se adaugă cu dependențe și criterii de finalizare.
