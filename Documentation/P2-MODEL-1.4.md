# P2 — Continuare cu modelul 1.4.0

La 2026-10-27, comparația pe aceleași 518 inputuri produce **442 scoruri parțiale
și 76 excluderi**, față de 438/80 în 1.3.0 și 49/469 în 1.2.0. Sunt recuperate
**AZO, CCEP, FER și NBIS**, fără pierderea vreunui instrument calculabil în 1.3.
Ținta de 518 scoruri nu este atinsă; P2 rămâne deschis conform criteriului din roadmap.

## Reguli și justificare

1. `STALE_OR_MISSING_FILING` devine o limitare a contabilității în 1.4, la fel ca
   `STALE`: toate metricile Value/Quality sunt dezactivate, chiar dacă există
   valori numerice în input. Momentum poate rămâne valid. În 1.3, codul provenit
   din SQL bloca întregul instrument, contrar intenției regulii de filing vechi.
2. Momentum măsoară același randament ajustat t−252/t−21, fără formule contabile.
   Normalizarea sa folosește grupul sectorial cu minimum 10 membri valizi, apoi
   universul de preț cu minimum 20, independent de profilul contabil. Fallbackul
   este păstrat explicit ca `PRICE_UNIVERSE`. Profilul `UNKNOWN` nu primește
   formule industriale și nici scoruri contabile. AZO avea Momentum valid, dar
   nu avea o cohortă contabilă suficientă în 1.3.
3. Value/Quality păstrează cohortele în același profil. Se păstrează winsorizarea,
   limitele z, pragurile de preț/lichiditate și cerința istoricului Momentum complet.
   Lipsurile de istoric și informațiile observate după cutoff nu intră în cohortă.
4. Identitatea, acțiunile corporative nerevizuite, clasele secundare și tipurile
   de instrument nesuportate rămân blocante. Nu se șterg probleme sau observații,
   nu se retrodatează revizuiri și nu se inventează prețuri ori valori contabile.

Schimbarea cohortelor modifică și scoruri/ranguri deja calculabile. Comparația
păstrează rezultatul înainte/după pentru fiecare instrument, inclusiv blocajele.
Scorul parțial exclusiv Momentum nu echivalează cu evaluarea Value/Quality.
Creșterea acoperirii nu demonstrează performanță predictivă; statutul rămâne
`FROZEN_RESEARCH_HYPOTHESIS` / `NOT_EVALUATED`, iar testul istoric final rămâne închis.

## Versionare și reproducere

Manifestul și fixture-urile 1.4 sunt separate de cele 1.3. Sursele vechi și
freeze-ul verificat sunt păstrate în `research-engine/fixtures/model-v1.3-archive/`.
Implementările Java/Python selectează explicit versiunea; apelurile fără versiune
ale modelului flexibil continuă să reproducă 1.3. Schedulerul păstrează 1.2 implicit.
V022 înregistrează manifestul nou și extinde verificarea publicării pentru hashul
exact al modelului; V021 și manifestele vechi rămân neschimbate.

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.flexible_comparison --prepared build/p2-close-20261025/prepared.json --output build/p2-v14-new-comparison --before-version 1.3.0 --version 1.4.0
$env:FLEXIBLE_COHORT_DIR = (Resolve-Path build/p2-v14-new-comparison).Path
.\gradlew.bat :scoring-service:test --tests '*FlexibleModelParityTest'
Remove-Item Env:FLEXIBLE_COHORT_DIR
```

> Notă 2026-11-05 (P2s): codul Java al modelului a fost eliminat. Comanda Gradle de mai sus nu mai rulează. Sursele sunt în `research-engine/fixtures/model-v1.5-java-final-archive/`; reproducerea se face din Python (`quant_research.publication_audit`, `quant_research.publication.reproduce`).

Utilitarele refuză suprascrierea dovezilor. Selectarea producției folosește
`--scoring.model.version=1.4.0`; `publication_audit` reproduce versiunea din run.

## Ce împiedică închiderea P2

Cele 76 de excluderi se împart fără suprapunere astfel:

| Categorie | Instrumente | Ce trebuie rezolvat |
|---|---:|---|
| Acțiuni corporative | 68 | Revizuiri individuale cu surse, identitate și termeni verificați; 50 au numai dividende, 18 au alte acțiuni |
| Certificate de depozit | 2 | ARM/PDD sunt `DEPOSITARY_RECEIPT` în datele canonice; politica actuală acceptă common stock |
| Clase secundare | 3 | FOX/GOOG/NWS; politică explicită pe instrument, fără confundarea claselor sau a numărului de acțiuni |
| Prețul zilei și lichiditate lipsă | 2 | PSKY/WBD; răspunsurile păstrate confirmă absența, iar succesorii necesită identitate și serii distincte |
| Identitate nerevizuită | 1 | SPCX; revizuirea surselor pentru instrumentul exact |

În special, `INACTIVE_OR_UNSUPPORTED_SECURITY` nu înseamnă că ARM/PDD sunt inactive:
inventarul canonic confirmă `active=true`, dar tipul lor este nesuportat. Formula
nu elimină acest cod combinat, deoarece ar elimina și protecția instrumentelor
inactive. Pentru BNY, evenimentele fondului cu același simbol nu devin evenimente
ale băncii. Aceste cazuri nu se închid printr-o acceptare globală a dividendelor.

O revizuire efectuată acum nu poate fi introdusă în cutofful din 24 octombrie.
Publicarea efectelor unor revizuiri noi necesită un cutoff nou și colectări
compatibile cu acesta, inclusiv vintage-ul seriei ajustate. Compararea formulelor
pe inputurile vechi rămâne un experiment separat și reproductibil.

## Publicația nouă și rezultatul verificat

Runul `b05e813d-d0e0-4993-8777-f05e90fa7fee`, data economică **2026-10-26**,
cutoff **2026-10-27T14:27:01Z**, este `PUBLISHED`: **487 PARTIAL / 31 UNAVAILABLE**
din 518 instrumente. Sunt 45 de recuperări și nicio pierdere față de publicația
1.4 de comparație. Acest rezultat folosește inputuri noi, nu măsoară izolat formula.
Cele 20 de revizuiri din 26 octombrie și 25 din 27 octombrie intră numai în noul
cutoff. Istoricul ajustat colectat cu vintage 27 octombrie conține 130.707
observații pentru 253 de ședințe, între 23 octombrie 2025 și 26 octombrie 2026.

- [Rezultatul și instrumentele recuperate](evidence/p2/v14/current/summary.json).
- [Recalcularea Python față de Java și proveniența](evidence/p2/v14/current/publication-verification.json).
- [Inventarul celor 31 de excluderi](evidence/p2/v14/current/remaining-audit.json).
- [Verificarea Gateway și a paginării](evidence/p2/v14/current/gateway-verification.json).
  Exportul local complet este `build/p2-v14-20261027/current/gateway/universe.html`
  (CSV în același director); toate cele 518 instrumente sunt prezente.
- [Cele 25 de revizuiri noi](evidence/p2/v14/recovery/reviews-applied.json), cu
  manifestele individuale și capturile surselor în același director.
- [Cele 20 de revizuiri precedente](evidence/p2/v14/recovery/previous-20261026/reviews-applied.json),
  cu sursele originale comprimate și hashurile verificate în `source-files.json`.

Rămân cinci cazuri numai cu dividende: **ACN, ASML, FCX, NI, VLO**. Pentru ACN,
suma furnizorului diferă de suma anunțată; pentru ASML trebuie reconciliate moneda
și termenii plății. FCX conține atât totalul cât și o componentă distinctă pentru
aceeași dată. Pentru NI/VLO există diferențe între ex-date din istoricul emitentului
și furnizor. Aceste discrepanțe nu au fost aprobate automat. Celelalte 18 cazuri
corporative necesită reconcilierea identității și a termenilor evenimentului.
Cele opt blocaje fără acțiuni corporative sunt cele enumerate mai sus.

Validare locală: **202 teste Python**, Ruff, paritate Java/Python pe fixture-urile
1.3 și 1.4 și cohorta reală, teste de integrare pentru publicare/rollback și
migrații. Freeze-ul activ verifică 54 de fișiere. Auditul publicației noi reproduce
inputurile și toate scorurile; nu găsește prețuri observate după cutoff.
Publicațiile precedente rămân imuabile; dovezile comparației controlate și ale
reproducerii versiunilor anterioare sunt păstrate în `evidence/p2/v14/`.

**P2 nu este închis:** cerința tuturor celor 518 scoruri nu este îndeplinită.
Lipsa prețurilor PSKY/WBD și reconcilierea evenimentelor/identităților rămase
necesită date verificabile; schimbarea politicii pentru ADR și clase secundare
necesită o versiune explicită a modelului, cu teste și comparație separate.
