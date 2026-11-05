# P2 — publicația de închidere, 2 noiembrie 2026

Run `bead056b-eb58-4e18-aa5b-6e7815d6f8e9`, model **1.5.0**, pregătire
`canonical-preparation-v4`, data economică **2026-10-30**, cutoff
`2026-11-01T20:00:00Z`, vintage ajustat 2026-11-01. Rezultat: **518 instrumente
= 515 scoruri + 3 excluderi documentate**. Publicațiile anterioare, inclusiv
`4e60be7c-77d0-481e-ab99-040069f0c099` (514/4), rămân neschimbate.

## Verificări

| Verificare | Rezultat | Fișier |
|---|---|---|
| Reproducere Python a inputurilor pregătite și a scorurilor | paritate, toleranță 1e-9 | [publication-verification.json](publication-verification.json) |
| Prețuri cu linie de proveniență după cutoff | 0 | idem |
| Gateway, pagini de 100 și 137 cu același `runId` | 518 instrumente, câmpuri identice | [gateway-cross-check.json](gateway-cross-check.json) |
| Comparație cu 4e60be7c | recuperat SKYD, zero pierderi | [comparison-summary.json](comparison-summary.json) |
| Triaj excluderi | 3 instrumente | [remaining-audit.json](remaining-audit.json) |
| `:scoring-service:test` (JDK 25) | 103 teste, 0 eșecuri, 2 omise | `build/p2-psky-20261101/scoring-test-2.log` |
| Python `pytest research-engine/tests`, Ruff | 240 trecute, Ruff curat | — |

Cele două teste omise (`FlexibleModelParityTest.retainedPublicationMatchesPythonAtEveryStage`,
`P2CohortParityTest.retainedCohortMatchesPythonPreparationAndScores`) cer artefacte
locale păstrate și nu au rulat. Paritatea acestei publicații este cea din reproducerea
Python de mai sus.

Comparația este între două snapshoturi cu cutoffuri diferite, nu un experiment
controlat. Singura diferență intenționată sunt revizuirile ședințelor PSKY/SKYD
([psky-20261101](../psky-20261101/reviews-applied.json)): 68 de ședințe după
mutarea pe NYSE, recuperate sub SKYD, și 4 ședințe anterioare listării.

## Cutoff explicit

Colectarea vintage-ului 2026-11-01 s-a încheiat la `2026-11-01T19:17:45Z`
(131.054 itemi: 130.730 COMPLETE și 324 absențe explicite, fără erori terminale).
Procesul de publicare a fost întrerupt de oprirea mașinii, înainte de cutoff.
Publicarea a rulat pe 2 noiembrie cu un cutoff explicit `2026-11-01T20:00:00Z`.
Data acestuia în New York este egală cu vintage-ul. [Cererea](request.json)
păstrează verificarea: zero observații, filinguri, simboluri, acțiuni corporative
sau itemi de colectare după cutoff. Nu au fost modificate timestampuri.
`decidedAt` și momentul creării runului rămân cele reale, din 2 noiembrie.
O primă încercare (`build/p2-psky-20261101/current-2`) a eșuat înainte de
publicare din cauza unui JDK vechi în PATH (UnsupportedClassVersionError). Nu a
creat niciun run. Încercarea reușită este `current-3`, cu JDK 25 și același JAR
(`fc7ee3c0…c838`) ca publicația 4e60be7c.

## Excluderi documentate

Decizii acceptate de utilizator: la 2026-10-31 pentru instrumentele dispărute de
la tranzacționare și la 2026-11-02 pentru listările recente, fără istoric raportat
suficient. O excludere nu este scor zero. Nu s-a folosit prețul succesorului,
istoricul altui titlu sau o valoare inventată.

| Instrument | Eveniment și dată efectivă | Cauză | Surse păstrate | Reintrare |
|---|---|---|---|---|
| **WBD** `77f24b58-a12d-4483-b86f-9d695eb4324a` | fuziune; ultima tranzacționare 2026-10-05, `valid_to` 2026-10-06 | instrument dispărut; nu există preț la data scorului | [Nasdaq ECA2026-710, ciclul de viață](../lifecycle-20261031/README.md), [revizuiri](../reviews-20261031/README.md) | niciuna; prețul cumpărătorului nu se atribuie |
| **HONA** `97e6932f-7dca-4a6f-b955-5bd469481ee6`, CIK 0002089271 | spin-off din Honeywell, finalizat 2026-06-29; when-issued din 2026-06-15 | 97 de ședințe (<252 pentru Momentum); numai 10-Q (2026-06-27), fără 10-K, deci fără TTM; `COMMON_EQUITY` lipsește, iar `STOCKHOLDERS_EQUITY` = −5,697 mld | [8-K Honeywell 2026-06-25, Nasdaq ECA2026-399](../hon-spins-20261030/source-files.json), [10-Q și acțiuni](../remaining-20261030/source-files.json) | primul 10-K anual și/sau 252 de ședințe proprii |
| **SPCX** `0485b5ed-85a6-4e40-a978-65919a0f89bd`, CIK 0001181412 | SpaceX Class A pe Nasdaq; instrument valid din 2026-08-27; fondul anterior SPCX a devenit SPCK la 2026-04-07 | barele anterioare validității aparțin fondului și sunt excluse; numai 10-Q (2026-06-30, 2026-09-30), fără 10-K, deci fără TTM; număr de acțiuni pe clasă nealocat (`MISSING_INSTRUMENT_SHARES`) | [8-K SpaceX, 497 SPCK](../reviews-20261031/source-files.json) | primul 10-K anual, alocarea acțiunilor pe clasă și 252 de ședințe proprii |

La HONA și SPCX, `NOT_REPORTED` descrie ce poate deriva pregătirea actuală.
Nu înseamnă că raportările originale ar fi goale. Book/market este singura metrică
teoretic recuperabilă înainte de 10-K. La HONA, capitalul propriu raportat este
negativ. La SPCX, ar necesita o revizuire a claselor de acțiuni. Utilizatorul a
ales excluderea documentată în locul unui scor cu o singură metrică (1/7).
Cazurile se reevaluează la primul raport anual, fără reducerea cerințelor modelului.

## Reproducere

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-25'; $env:PATH="$env:JAVA_HOME\bin;$env:PATH"
$env:PYTHONPATH='research-engine/src'
.\research-engine\.venv\Scripts\python.exe Documentation/evidence/p2/closeout-final/psky-20261101/finish-20261101.py --check-only
# publicare (deja efectuată; un director nou pentru fiecare încercare):
# ... finish-20261101.py --out build/p2-psky-20261101/current-3 --cutoff 2026-11-01T20:00:00Z
```

Artefactele complete (exportul Gateway, documentul inputurilor și comparația pe
instrument) sunt locale, în `build/p2-psky-20261101/current-3/`.
