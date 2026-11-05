# P2 — publicație verificată la 2026-10-30, ținta completă încă deschisă

## Criteriu acceptat la 2026-10-31

Utilizatorul acceptă excluderi economice documentate pentru instrumentele
dispărute de la tranzacționare. [Decizia și reconcilierea](lifecycle-20261031/README.md)
separă WBD de celelalte 20 de cazuri încă deschise. 497 scoruri + 21 excluderi
rămâne rezultatul publicației; decizia nu aplică modificări în DB și nu
echivalează cu finalizarea P2. Afirmațiile istorice despre ținta obligatorie
de 518 scoruri de mai jos descriu criteriul anterior acestei decizii.

## Reverificare 2026-10-31

[Auditul live](remaining-audit-20261031.json) confirmă 497/518 și cele 21 de
excluderi. [Verificarea publicației](verification-20261031.json) reproduce
pregătirea și scorurile Java/Python la 1e-9, cu zero observații de preț după
cutoff. Au trecut 223 teste Python și Ruff; suitele Java nu au fost rerulate.

[Investigația WBD/PSKY](lifecycle-20261031/README.md) separă retragerea de la
tranzacționare a WBD de schimbarea PSKY → SKYD/NYSE. Recolectarea aceluiași
simbol nu remediază aceste două cauze. Referințele trebuie corectate cu date
efective și dovezi; publicațiile existente rămân imuabile. P2 rămâne deschis.

Auditul raportează acum toate acțiunile necesare per instrument în
`next_actions` și numărul instrumentelor per acțiune în `allNextActions`.
Păstrează câmpurile anterioare pentru compatibilitate. O revizuire corporativă
acceptată nu mai poate ascunde lipsa prețului, identitatea neverificată sau
o altă problemă publicată fără dovezi. Numerele pe acțiuni se pot suprapune.

## Rezultatul publicat și verificat

Runul `f41d3a8d-10f6-4bf7-a606-33dc838fa7ff`, model 1.4.0, data economică
2026-10-29 și cutoff `2026-10-30T13:44:35Z`, reconciliază **518 = 497 scoruri
parțiale + 21 excluderi**. [Rezumatul verificării](publication-20261030-summary.json)
păstrează hashurile artefactelor, paritatea pregătirii și scorurilor Java/Python
la 1e-9, zero rânduri de preț observate după cutoff și proveniența păstrată.
Gateway returnează aceleași 518 instrumente și valori la paginări de 100 și 137.

Față de publicația din 27 octombrie sunt recuperate **APTV, BNY, CMCSA, DD,
ECHO, FDX, HON, P, SPGI și VMRK**, fără pierderi de eligibilitate. Comparația
este între două snapshoturi cu date și cutoffuri diferite, nu un experiment
controlat asupra formulei. Formula nu s-a schimbat în această continuare.

[Inventarul celor 21 de excluderi](remaining-audit-publication-20261030.json)
separă 11 instrumente cu acțiuni corporative, două certificate de depozit,
trei clase secundare, două cazuri fără metrici normalizabile, două fără prețul
zilei și o identitate nerevizuită. Q și HONA au revizuirile de identitate
aplicate, dar [diagnosticul metricilor](unavailable-metrics-20261030.json) arată
`NOT_REPORTED` pentru contabilitate și `INSUFFICIENT_HISTORY` pentru Momentum.
Aceste coduri descriu pregătirea disponibilă; nu demonstrează absența informației
din raportările originale. Necesită urmărirea separată a mapărilor și perioadelor.

[Urmărirea inputurilor Q/HONA](metric-input-trace-20261030.json) confirmă această
distincție: ambele au `STOCKHOLDERS_EQUITY` și `TOTAL_ASSETS` la data bilanțului,
dar nu au `COMMON_EQUITY` utilizabil în pregătire. Q are și `PREFERRED_EQUITY`;
derivarea capitalurilor comune necesită reconcilierea semantică a raportării,
nu simpla redenumire a tagului. HONA nu are numărul de acțiuni selectat în acest
snapshot. Istoricul folosit de pregătire este de 249 observații pentru Q și 45
pentru HONA, sub cerința Momentum. Nu toate excluderile sunt dovedite imposibil
de remediat; unele reprezintă investigații și schimbări de suport încă deschise.

Colectările selectate au 130.710 rezultate COMPLETE și 344 absențe explicite,
pe 253 de ședințe. Procesele de publicare și verificare s-au încheiat cu starea
`VERIFIED` în `build/p2-final-20261030/current-recovery-2/status.json`.
Cele **221 teste Python**, Ruff și verificarea whitespace au trecut.
Continuarea detectează acum erorile terminale de colectare și salvează
`collection-failures.json` înainte de oprire; testul confirmă că nu creează
o cerere de publicare în această situație.

**P2 rămâne deschis:** cele 21 de excluderi nu sunt scoruri zero și ținta tuturor
scorurilor nu este atinsă. Secțiunile următoare păstrează etapele anterioare.

## Continuare 2026-10-30, după verificarea colectării

Colectarea inițială s-a oprit cu trei erori `BULK_REQUEST_FAILED`, distincte
de absențele `MISSING_SESSION`. Au fost lansate cereri `force-refresh` pentru
2026-02-25, 2026-02-26 și 2026-03-03, cu request ID și motiv explicit. Pentru
26 februarie a fost necesară încă o cerere, cu loturi de 50 de simboluri.
Încercările originale și checkpointurile rămân păstrate; nu sunt resetate.
[Continuarea auditată](recover-20261030.py) selectează exact cererile înlocuitoare
pentru aceste date și colectările programate pentru celelalte 250 de ședințe.
Starea activă este `build/p2-final-20261030/current-recovery-2/status.json`;
procesele anterioare de așteptare au fost oprite înainte de publicare, iar
directoarele lor conțin `superseded.json`.

Au fost aplicate și verificate ca idempotente încă trei revizuiri:

- [HONA](honeywell-20261030/reviews-applied.json): tranziția HONAV → HONA,
  cu același CUSIP, confirmată în anunțul Nasdaq păstrat în
  [inventarul surselor](honeywell-20261030/source-files.json).
- [HON](hon-spins-20261030/reviews-applied.json): cele două distribuții către
  SOLS și HONA, cu raporturile, identificatorii și datele confirmate în
  [sursele păstrate](hon-spins-20261030/source-files.json). Distribuția HONA
  precede reverse split-ul separat al HON; seriile părintelui și copiilor nu
  sunt unite. Descărcarea comunicatului SOLS a primit HTTP 403; revizuirea se
  leagă de anunțul Nasdaq capturat, nu de o captură inexistentă.

[Auditul înaintea republicării](remaining-audit-20261030-honeywell.json) arată
12 instrumente cu revizuiri încă nepublicate și alte 19 excluderi. Rezultatul
487/518 rămâne cel al publicației vechi, nu o estimare a noii publicații.
218 teste Python și Ruff au trecut; cele nouă teste ale continuării au fost
reluate după adăugarea celei de-a doua cereri pentru 26 februarie.

## Continuare 2026-10-30: revizuiri și publicare după colectare

Au fost aplicate 12 revizuiri individuale pentru trei instrumente:

- [Q și VMRK](identity-20261030/reviews-applied.json): trecerea Q.WI → Q și EQR → VMRK,
  confruntate cu raportările originale păstrate în
  [inventarul surselor](identity-20261030/source-files.json). Nu se unesc seriile
  DuPont/Qnity sau AvalonBay/EQR și nu se elimină cerințele istoricului.
- [BNY](bny/reviews-applied.json): schimbarea BK → BNY, dividendul comun al băncii
  și opt atribuiri greșite ale evenimentelor fondului BlackRock New York Municipal
  Income Trust. CUSIP-ul fondului este 09248L106, diferit de 064058100 al băncii;
  originalele sunt păstrate în [inventarul surselor BNY](bny/source-files.json).
  ACCEPT rezolvă aceste probleme punctuale; nu declară evenimentele fondului
  drept evenimente ale băncii. Prețurile și evenimentele originale nu se modifică.

Fiecare revizuire verifică emitentul prin CIK, revizia exactă a evenimentului,
hashurile surselor și `issueEvidence`; aplicarea repetată în aceeași tranzacție
confirmă idempotenta. Credentialul Scoring este folosit numai în memorie.
[Auditul ulterior](remaining-audit-20261030-after-reviews.json) identifică acum
**10 instrumente cu revizuiri încă nepublicate**, fără a le număra ca recuperări.
Rămân 21 de excluderi care necesită alte remedieri. Publicația este tot 487/518.

Calendarul arată că intervalul pornit inițial conținea 252 de ședințe, în timp
ce Momentum necesită 253 de prețuri. A fost adăugată colectarea ședinței
**2025-10-28**; intervalul complet este 2025-10-28–2026-10-29, vintage 2026-10-30.
Trei lucrători colectează din aceleași checkpointuri, cu un lucrător separat
pentru limita lipsă. Nu au fost resetate lucrări sau volume.

[Procesul datat de continuare](finish-20261030.py) este pornit local și:

1. Așteaptă toate ședințele din calendar și toate rezultatele colectării;
   acceptă ca terminale numai COMPLETE sau FAILED/MISSING_SESSION.
2. Lansează o singură publicare 1.4.0 cu cutoff nou, păstrat în `request.json`.
   Scoring verifică suplimentar sursele/checkpointurile și restul condițiilor.
3. Reproduce publicația în Python, compară cu publicația veche și exportă prin
   Gateway cu două dimensiuni de pagină, verificând scoruri, componente și motive.
4. Scrie VERIFIED numai după aceste verificări; nu închide automat P2 dacă
   există excluderi. La eroare se oprește fără ștergeri sau retry-uri cu cutoff nou.

Starea este `build/p2-final-20261030/current/status.json`; lansarea și cererea
sunt păstrate în același director. Procesul refuză o a doua lansare în directorul
existent și expiră la 19:00 UTC, înaintea schimbării ședinței economice.
Verificarea fără publicare se face cu:

```powershell
.\research-engine\.venv\Scripts\python.exe Documentation/evidence/p2/closeout-final/finish-20261030.py --check-only
```

Validare: 27 de teste relevante trecute, inclusiv limite lipsă, duplicate,
colectări neterminate și revizuiri; Ruff a trecut pentru procesul nou și testele lui.
La pornirea procesului erau 32.954 rezultate COMPLETE din 131.054 planificate.
Aceasta este o stare intermediară, nu rezultatul publicației următoare.

Investigația BDX a identificat un raport SEC de 0.135343148384084 față de
0.135343142914 în evenimentul furnizorului; nu s-a aplicat o revizuire.
Pentru TRI, [sursa Nasdaq păstrată](tri-20261030/source-files.json) confirmă
factorul și numerarul, însă data de plată din furnizor nu este confirmată de
această sursă (plată la prezentarea acțiunilor); cazul rămâne nerevizuit.

## Reverificare și recuperare la 2026-10-30

[Auditul live](remaining-audit-20261030.json) reconciliază în continuare
**518 = 487 scoruri + 31 excluderi**. [Verificarea](verification-20261030.json)
confirmă reproducerea inputurilor/scorurilor Python–Java la 1e-9 și zero observații
de preț după cutoff. Cele **209 teste Python** și Ruff au trecut. Nu s-au rerulat
suitele Java; limitarea testului de performanță din 29 octombrie rămâne consemnată.

DB conține **șapte**, nu numai cinci, instrumente cu toate problemele corporative
din publicație acoperite de revizuiri ACCEPT care corespund exact dovezilor:
APTV, CMCSA, DD, ECHO, **FDX**, P și **SPGI**. Revizuirile FDX/SPGI sunt înregistrate
la 29 octombrie, 14:07 UTC. Auditul le inventariază fără a le reaplica și fără a
declara instrumentele recuperate înainte de o publicație nouă.

Colectarea vintage-ului 29 octombrie s-a întrerupt la **57.998 observații ajustate**,
cu ultima sesiune prezentă în observațiile păstrate la 7 aprilie. Nu poate fi
completată astăzi prin etichetarea noilor răspunsuri drept vintage de ieri.
Au fost pornite colectări separate pentru **30 octombrie**, până la ședința
**29 octombrie**, cu istoricul ajustat începând la 29 octombrie 2025.
Lansările, hashurile JAR și progresul sunt în `build/p2-final-20261030/collection/`.
La această verificare colectarea este în curs; nu există o publicație nouă.

Utilitarul `quant_research.p2_remaining_audit` distinge acum următorii pași pentru
identitate, clase secundare, tipuri de instrument și prețuri lipsă. Revizuirile
curente sunt afișate separat de rezultatele imuabile ale publicației, inclusiv
REVOKE și nepotrivirea dovezilor. Un rezultat fără excluderi este acceptat numai
după verificarea stării PUBLISHED și reconcilierea numărului real de rezultate.

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.p2_remaining_audit --run-id b05e813d-d0e0-4993-8777-f05e90fa7fee --output build/p2-audit-new.json
```

**P2 rămâne deschis.** Cele șapte revizuiri nu rezolvă celelalte 24 de excluderi:
5 cazuri cu dividende, 11 cu alte acțiuni corporative, 2 certificate de depozit,
3 clase secundare, 2 instrumente fără prețul zilei și o identitate nerevizuită.
O țintă diferită de 518 scoruri necesită o decizie explicită; lipsurile nu sunt zero.

## Aplicare autorizată la 2026-10-29

Utilizatorul a autorizat explicit folosirea credentialului Scoring numai în
memorie. [Revizuirile ECHO/P](reviews-applied.json) au fost aplicate la 08:22 UTC,
iar [revizuirile APTV/CMCSA/DD](spin-offs/reviews-applied.json) la 14:00 UTC.
Fiecare aplicare a fost repetată în aceeași tranzacție pentru verificarea
idempotentei. Parola nu a fost afișată, scrisă în fișiere sau transmisă în CLI.

Pentru spin-off-uri sunt păstrate răspunsurile HTTP originale, hashurile și
momentul capturii în [inventarul surselor](spin-offs/source-files.json).
Manifeste separate leagă fiecare revizuire de emitentul canonic, problema și
revizia exactă a acțiunii. Datele de distribuție și prima ședință de tranzacționare
sunt distincte; istoricul copilului nu este unit cu istoricul părintelui.

Ultima publicație verificată rămâne 487/518. Cele cinci aprobări nu sunt încă
recuperări publicate. Colectarea prețurilor până la 28 octombrie și a vintage-ului
ajustat din 29 octombrie continuă din checkpointurile existente.

## Reverificare la 2026-10-29, înainte de aplicare

Docker a fost pornit fără recrearea volumelor. Auditul live confirmă că ultima
publicație este tot cea de mai jos: 518 = 487 scoruri + 31 excluderi. Reproducerea
inputurilor și scorurilor Java/Python a trecut la toleranța 1e-9, cu zero rânduri
de proveniență a prețurilor observate după cutoff. Artefactele complete ale
reverificării sunt în `build/p2-final-20261029/publication/`, iar inventarul live
este `build/p2-final-20261029/remaining-audit.json`.
[Rezumatul verificărilor](verification-20261029.json) păstrează hashul inputurilor,
reconcilierea, paritatea și starea validării.

Cele 202 teste Python, Ruff și validarea configurației Compose au trecut.
Testul Java `ScoringInputIntegrationTest.targetScaleUsesOneStatementAndRecordsAnActualQueryPlan`
a depășit limita existentă de 180 de secunde. Această rulare nu constituie o
validare completă verde a suitei Scoring; pragul testului nu a fost relaxat.
Scoring: 99 teste reușite, unul eșuat și două verificări opționale de cohortă
omise. Migrații: 19 teste reușite, după actualizarea numărului așteptat de
migrări de la 24 la 26 pentru V021/V022; reluarea Flyway rămâne verificată ca
no-op. Suita Screener a trecut. Numerele exacte sunt în rezumatul JSON.

Au fost pregătite [review-ECHO.json](review-ECHO.json) și
[review-P.json](review-P.json), validate față de emitentul canonic, acțiunea și
revizia exactă din DB, inclusiv reprezentarea `issueEvidence` cerută de V020.
Sursele emitentului au fost reverificate; captura originală păstrată și hashul
ei rămân neschimbate. Niciun manifest nu stabilește retroactiv `recorded_at`.

[prepare-reviews.py](prepare-reviews.py) reproduce pregătirea prin acces DB
exclusiv read-only, fără citirea credentialelor serviciului. Refuză o sursă,
acțiune sau un manifest existent care diferă de cele verificate.

```powershell
.\research-engine\.venv\Scripts\python.exe Documentation/evidence/p2/closeout-final/prepare-reviews.py
.\research-engine\.venv\Scripts\python.exe -m quant_research.input_review --manifest Documentation/evidence/p2/closeout-final/review-ECHO.json --evidence-file Documentation/evidence/p2/closeout-final/identity-sources.json
.\research-engine\.venv\Scripts\python.exe -m quant_research.input_review --manifest Documentation/evidence/p2/closeout-final/review-P.json --evidence-file Documentation/evidence/p2/closeout-final/identity-sources.json
```

Aplicarea folosește separat `--apply` și `SCORING_DB_DSN`, după autorizarea
accesului la credentialul Scoring. Numărul recuperărilor nu este încă verificat.
Chiar dacă ambele instrumente devin calculabile, celelalte 29 de cazuri necesită
remedieri distincte; aceste două revizuiri nu îndeplinesc singure ținta P2.

## Contextul pregătirii inițiale din 27 octombrie

Publicația verificată rămâne `b05e813d-d0e0-4993-8777-f05e90fa7fee`:
487 scoruri parțiale și 31 excluderi. Fișierele din acest director nu constituie
o publicație nouă și nu închid criteriul celor 518 scoruri din ROADMAP.

`proposed-reviews.json` identifică exact cele două probleme care pot primi o
revizuire individuală. `identity-sources.json` păstrează răspunsul instrumentului
web, nu octeții originali ai paginilor. Hashul capturii este inclus în propuneri.

| Instrument | Schimbare verificată | Data | Domeniul revizuirii |
|---|---|---|---|
| ECHO | SATS → ECHO, CUSIP neschimbat | 2026-06-24 | Numai problema schimbării simbolului |
| P | PSTG → P, CUSIP neschimbat | 2026-04-17 | Numai problema schimbării simbolului |

Surse ale emitenților:

- [EchoStar](https://echostar.gcs-web.com/news-releases/news-release-details/echostar-changing-stocker-ticker-sats-echo-marking-companys-next).
- [Everpure](https://www.everpuredata.com/company/newsroom/press-releases/everpure-to-change-ticker-symbol.html).

La pregătirea inițială, aplicarea nu a avut loc: configurația `.env` nu definea credentialul Scoring,
iar aprobarea automată a respins atât persistarea unei parole implicite, cât și
citirea credentialului din configurația containerului. Este cerut acordul explicit
pentru utilizarea locală, exclusiv în memorie, a credentialului serviciului existent.
Nicio parolă nu este păstrată în aceste dovezi. Autorizarea a fost primită și
aplicarea este consemnată în secțiunea din 29 octombrie de mai sus.

După aplicare rămâne publicarea unui run nou cu cutoff ulterior revizuirilor,
auditul Python/Java și verificarea Gateway. Recuperarea instrumentelor nu se
presupune înaintea acestei verificări.
