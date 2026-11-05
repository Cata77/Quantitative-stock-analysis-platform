# P2 — Formula flexibilă 1.3.0

Continuare 2026-10-27: [versiunea 1.4.0](P2-MODEL-1.4.md) produce 442 scoruri
pe aceleași inputuri. Cifrele de mai jos descriu publicația 1.3.0 păstrată.

Implementarea adăugată la 2026-10-26 urmărește scoruri pentru întregul univers.
Comparația controlată pe inputurile publicației din 23 octombrie produce
**438 scoruri parțiale și 80 instrumente încă excluse**, față de 49 scoruri în
modelul 1.2.0. Cele 49 rămân calculabile; 389 instrumente primesc acum scor.
Ținta tuturor celor 518 scoruri nu este încă atinsă. Formula nu poate rezolva
identitatea incertă a unui instrument sau inventa date pentru acesta.

## Reguli

- Derivările contabile și verificările unităților, perioadelor și cutoffului
  se reutilizează din 1.2.0. Nicio valoare financiară lipsă nu devine zero.
- Fiecare metrică se normalizează în propria cohortă cu date valide, după
  verificările obligatorii. Se păstrează minimum 10 membri în grupul primar
  sau 20 în același profil, winsorizarea 2,5%/97,5% și limita z de ±3.
- O familie este media metricilor normalizate disponibile, cu minimum una.
  Dacă nu există nicio metrică validă, familia și contribuția ei sunt `null`.
- Ponderile de bază 50/30/20 se redistribuie între familiile disponibile.
  De exemplu, fără Value, Quality/Momentum primesc 60/40; pentru Momentum
  singur, ponderea efectivă este 100%. Ponderile și contribuțiile fiecărei
  metrici se păstrează în rezultatul publicat.
- Acțiunile vechi dezactivează Value; vechimea filingului dezactivează
  metricile contabile. Golurile istoricului de preț dezactivează Momentum.
  Erorile care nu sunt explicit tratate ca limitări rămân blocante.
- Pentru profilurile contabile nesuportate se poate calcula exclusiv Momentum,
  etichetat explicit. Aceasta nu reprezintă activarea formulelor de bancă,
  asigurător sau REIT și nu afirmă evaluarea solvabilității lor; P3 rămâne necesar.
- Identitatea, acțiunile corporative nerevizuite, clasele neprimare și celelalte
  filtre obligatorii rămân blocante. Nu se șterg probleme sau checkpointuri.

`scoreStatus` distinge `COMPLETE` (toate cele șapte metrici normalizate),
`PARTIAL` (una până la șase) și `UNAVAILABLE`. În această cohortă toate cele
438 de scoruri sunt parțiale. Criteriul `COMPLETE` este mai strict decât
eligibilitatea veche, care admitea cinci metrici; etichetele nu se transferă
retroactiv publicațiilor 1.2.0.

Rangul și percentila se calculează între instrumentele calculabile din run.
Scorurile bazate pe familii diferite au expuneri diferite: un scor exclusiv
Momentum nu reprezintă o evaluare Value/Quality completă. API-ul expune
`scoreCoverage` și `effectiveWeights`; avertismentul de scor parțial este păstrat.
Acoperirea crescută și paritatea nu demonstrează performanța predictivă.
Statutul rămâne `FROZEN_RESEARCH_HYPOTHESIS` / `NOT_EVALUATED`.

## Versiuni și reproducere

Manifestul 1.3 este separat de manifestul 1.2 în
`research-engine/src/quant_research/model/flexible/`. Sursele și freeze-ul
anterior sunt păstrate în `research-engine/fixtures/model-v1.2-preparation-v3-archive/`.
Fixture-ul nou include lipsuri, date viitoare, istoric incomplet și blocaje de
identitate. Certificarea Java verifică fixture-ul Python la pornirea unui run 1.3.

Migrarea V021 înregistrează noul model și permite familii absente pentru
publicațiile sale. Verificările vechi de acoperire rămân obligatorii pentru
modelele anterioare. Publicarea 1.3 verifică existența metricilor, suma
ponderilor, contribuțiile și proveniența; publicarea este în continuare atomică.

Selecția este explicită prin `--scoring.model.version=1.3.0`, împreună cu
comanda de scoring existentă. Valoarea implicită rămâne 1.2.0 pentru a nu schimba
implicit schedulerul și reluările existente. Hashul noului manifest intră în
identitatea runului; publicațiile anterioare nu sunt suprascrise.

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.flexible_comparison --prepared build/p2-close-20261025/prepared.json --output build/p2-flexible-comparison-new
$env:FLEXIBLE_COHORT_DIR = (Resolve-Path build/p2-flexible-comparison-new).Path
.\gradlew.bat :scoring-service:test --tests '*FlexibleModelParityTest'
Remove-Item Env:FLEXIBLE_COHORT_DIR
```

> Notă 2026-11-05 (P2s): codul Java al modelului a fost eliminat. Comanda Gradle de mai sus nu mai rulează. Sursele sunt în `research-engine/fixtures/model-v1.5-java-final-archive/`; reproducerea se face din Python (`quant_research.publication_audit`, `quant_research.publication.reproduce`).

Utilitarul refuză suprascrierea dovezilor, compară ambele modele pe aceleași
inputuri și scrie explicațiile pentru fiecare instrument în `summary.json`.
`publication_audit` alege reproducerea după versiunea modelului din DB.

## Publicație locală și dovezi

Runul **`b4d566ac-63c3-4959-902c-1bc76ed12285`** a fost publicat la 26 octombrie,
cu data economică 2026-10-23 și cutofful 2026-10-24T23:59:59Z:
**518 = 438 scoruri parțiale + 80 excluderi**. Hashul documentului de input este
identic cu cel al publicației P2 1.2.0; comparația izolează schimbarea formulei.

[Comparația completă](evidence/p2/flexible/comparison.json) păstrează fiecare
instrument, scorul/rangul înainte și după, acoperirea și blocajele rămase.
[Comanda publicării](evidence/p2/flexible/publication-command.json) și
[aplicarea migrării](evidence/p2/flexible/migration-command.json) păstrează
hashurile JAR-urilor. Backupul local verificat este
`build/p2-flexible-backup-20261026/before-v021.dump`.

Cele 80 de instrumente rămase includ 68 cu revizuiri de acțiuni corporative,
șapte cu filing vechi/absent, trei clase neprimare, două cu identitate/inactivitate
incompatibilă, două fără prețul zilei și lichiditate completă, unul cu revizuire
explicită de identitate și unul fără cohortă pentru o metrică normalizabilă.
Categoriile se suprapun. Nu se elimină aceste blocaje pentru a declara artificial
atingerea țintei. Inventarul individual rămâne în raportul de comparație.

Acoperirea celor 438: 100 instrumente cu o metrică normalizată, 53 cu două,
121 cu trei, 115 cu patru și 49 cu cinci. Scorurile și rangurile 1.2.0 rămân
accesibile prin runurile lor originale.

## Verificări încheiate

- [Auditul publicației](evidence/p2/flexible/publication-verification.json)
  reproduce pregătirea și toate rezultatele Java în Python, la toleranța 1e-9,
  verifică sursele păstrate și confirmă zero prețuri ulterioare cutoffului.
- [Verificarea Gateway](evidence/p2/flexible/gateway-verification.json) compară
  toate cele 518 rezultate cu publicația, inclusiv starea scorului, acoperirea
  normalizată și ponderile. Paginările cu 100 și 137 de rânduri și filtrele
  `ALL`, `ELIGIBLE`, `EXCLUDED` reconciliază aceleași numere.
- [Publicația P2 anterioară](evidence/p2/flexible/previous-publication-preserved.json)
  păstrează același hash și toate cele 518 rezultate; reproducerea 1.2.0 trece.
- 191 de teste Python au trecut în suita completă; după extinderea exportului,
  cele 18 teste ale exportului au trecut, inclusiv noul caz pentru scor parțial.
  Ruff și verificarea freeze-ului trec.
- Două teste de paritate Java trecute (fixture și cohorta reală), 22 de teste
  Scoring pentru publicare/reluare/rollback, 17 teste Screener și nouă teste de
  migrare. Publicarea respinge ponderi inconsistente și păstrează atomicitatea.

Screener a fost reconstruit și repornit local pentru noile câmpuri. Exportul
complet verificat este în `build/p2-flexible-20261026/gateway/universe.html`
și `universe.csv`; scorurile parțiale au etichetă vizibilă și ponderi în detalii.
Nu s-a schimbat automat versiunea folosită de scheduler: runul 1.3 a fost cerut
explicit, iar implicit rămâne 1.2.0.

## Auditul blocajelor rămase — 2026-10-26

[Inventarul executat pe baza locală](evidence/p2/flexible/remaining-audit.json)
reconciliază toate cele 80 de excluderi cu problemele individuale și sursele
acțiunilor corporative. Sunt 105 probleme pe 68 instrumente: 85 dividende în
numerar, zece schimbări de nume/simbol, opt separări de companii și două fuziuni.
50 de instrumente au numai probleme de dividende; 18 au alte acțiuni corporative,
iar 12 nu au acest tip de blocaj. Acestea sunt categorii de verificare, nu
recuperări demonstrate sau aprobări automate.

Istoricul local al simbolului poate începe după eveniment. Raportul păstrează
data de început și data acțiunii pentru fiecare problemă. Simpla coincidență a
simbolului nu justifică extinderea lui retroactivă sau acceptarea tuturor
evenimentelor furnizorului.

Exemplu confirmat în surse primare: banca BNY a anunțat schimbarea BK → BNY
pentru 21 mai 2026, fără schimbarea CUSIP-ului
([anunțul emitentului](https://www.bny.com/corporate/global/en/about-us/newsroom/press-release/bny-announces-planned-change-of-stock-ticker-symbol-to-bny-130465.html)).
Separat, fondul BlackRock New York Municipal Income Trust folosea simbolul BNY
și a fost reorganizat în MYN în februarie 2026
([anunțul BlackRock](https://www.blackrock.com/us/individual/literature/press-release/2-9-2026-pr-muni-merger-completions-muc-myn-mhd.pdf)).
Datele furnizorului păstrate local disting CUSIP-urile `064058100` și `09248L106`,
dar includ evenimentele ambelor sub instrumentul local BNY. Revizuirea trebuie
să distingă evenimentele atribuite greșit de continuitatea reală a băncii;
acceptarea globală a codului `CORPORATE_ACTION_REVIEW` ar ascunde această problemă.

Reproducere numai-citire, cu rolul `quant_scoring` (rolul `quant_research` nu
are acces la tabelul problemelor operaționale):

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.p2_remaining_audit --run-id b4d566ac-63c3-4959-902c-1bc76ed12285 --output build/p2-remaining-audit-new.json
```

Utilitarul refuză suprascrierea raportului și nu aplică revizuiri. Cele două
teste de clasificare și Ruff trec. Publicația rămâne la 438 scoruri; nu s-au
retrodatat revizuiri și nu s-au relaxat filtrele pentru a închide checkboxul.
