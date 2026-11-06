# P2b.1 — audit al comparațiilor pe sector, 2026-11-06

Primele două puncte P2b.1 (auditul clasificărilor și raportul pe sector și metrică), pe
publicația reală `bead056b-eb58-4e18-aa5b-6e7815d6f8e9` (model 1.5.0, data scorului
2026-10-30, cutoff de cunoaștere 2026-11-01T20:00Z, 518 = 515 + 3). Nu s-a schimbat
modelul, publicația sau API-ul.

Instrumentul [`quant_research.sector_audit`](../../../research-engine/src/quant_research/sector_audit.py)
citește numai tabelele publicate (`stock_scores`, `factor_observations`, `score_lineage`),
clasificarea versiunii runului la data scorului și la cutoff, și SIC-ul din răspunsurile SEC
observate înainte de cutoff. Recalculează din rândurile publicate cohorta pe care regula
1.4/1.5 trebuia s-o aleagă și o reconciliază cu cea stocată.

```powershell
$env:QUANT_DB_DSN = '<dsn quant_research>'   # numai citire
.\research-engine\.venv\Scripts\python.exe -m quant_research.sector_audit --run-id bead056b-eb58-4e18-aa5b-6e7815d6f8e9 --output <director nou>
```

Rezultat: [sector-audit.md](bead056b/sector-audit.md) (tabelele de mai jos) și
[sector-audit.json](bead056b/sector-audit.json) (totaluri, sectoare, metrici și profilul,
sectorul și grupul fiecărui instrument). Două rulări au dat același raport.

## Reconciliere cu publicația

**RECONCILED**: 517 rânduri (toate în afară de WBD, blocat înainte de normalizare),
1.432 de valori brute de metrică verificate (1.427 normalizate), 0 diferențe. Verificările:

| Verificare | Ce compară |
|---|---|
| `peer_group` = sector | Grupul stocat este sectorul clasificării pentru GENERAL și lipsește pentru celelalte profiluri |
| O etichetă, o cohortă | Toți membrii unei etichete (`PRIMARY:<sector>`, `PROFILE:<profil>`, `PRICE_UNIVERSE`) au aceeași dimensiune, medie, abatere și limite de winsorizare |
| Cohorta aleasă | Recalculată cu regula: minimum 10 observații compatibile în grup, altfel minimum 20 în profil (momentum: în tot universul de preț), câte un rând pe emitent |
| Avertismentul | `PEER_FALLBACK:<metrică>` există exact când cohorta nu este cea primară |
| Fără sector | Orice rând neclasificat are `MISSING_SECTOR` |

Ca să verific că verificările prind erori, am modificat datele în memorie: o dimensiune +1,
un sector schimbat, un avertisment eliminat și o observație lipsă în Utilities/FCF (la
pragul de 10). Fiecare modificare a produs diferențele așteptate; ultima mută toți cei 10
membri pe fallback. Testele unitare sunt în
[test_sector_audit.py](../../../research-engine/tests/test_sector_audit.py).

## Patru concepte distincte

| Concept | Sursa | `bead056b` |
|---|---|---|
| Sector | `reference.issuer_classifications.mapped_sector`, versiunea `local-sectors-2026-09-26` (`PUBLIC_SECTOR_LOCAL_REVIEW`) | 518/518 clasificate, 11 sectoare cu nume GICS, 15 revizuiri locale (`LOCAL_REVIEW:`) |
| Profil | SIC-ul raportărilor SEC (6798 → `EQUITY_REIT`, 63xx → `INSURER`, 60–69 → `FINANCIAL`, fără date → `UNKNOWN`) | GENERAL 411, FINANCIAL 49, EQUITY_REIT 27, INSURER 23, UNKNOWN 8 |
| Grup de normalizare (`peer_group`) | Sectorul, numai pentru GENERAL | 411 rânduri; celelalte 107 nu au grup |
| Cohorta efectivă | `factor_observations.cohort`, per metrică | vezi tabelul pe metrică |
| Industrie | Nu există în referințe; SIC numai în răspunsurile SEC | 511/518 rânduri cu SIC, 183 de coduri; nefolosit de model |

Clasificarea are o singură versiune, cu toate rândurile valabile de la 2026-09-26 și
disponibile la 2026-09-26 11:13Z, fără istoric. Este o hartă publică plus revizuiri locale,
nu un istoric GICS licențiat. Pentru o dată a scorului anterioară, fiecare instrument ar
primi `MISSING_SECTOR`. Backtestingul pe sector cere deci o sursă istorică (P4/P5).

## Constatări

1. **Sectorul și grupul de comparație diferă mult în Financials și Real Estate.** Financials
   are 76 de membri, dar grupul său de normalizare are doar cele 13 companii GENERAL:
   plăți și date financiare (V, MA, SPGI, MCO, MSCI, PYPL, FIS, FISV, GPN, JKHY, CPAY, FDS,
   XYZ). Băncile (45 FINANCIAL) și asigurătorii (18) nu sunt în el. Real Estate are 30 de
   membri și un singur GENERAL (CSGP), normalizat numai pe fallback sau deloc.
2. **Profilurile non-GENERAL nu sunt comparate în sector deloc.** Cele 107 rânduri sunt
   scorate numai pe momentum (`PRICE_ONLY_UNSUPPORTED_ACCOUNTING_PROFILE`), față de tot
   universul de preț (510 emitenți). Printre ele sunt și companii din alte sectoare decât
   cel financiar: UNH, ELV, CI, CNC, HUM (Health Care, profil INSURER), TPL (Energy,
   FINANCIAL), MSTR (IT, FINANCIAL) și 8 profiluri UNKNOWN (AZO, PDD, CCEP, FER, TRI, ARM,
   ASML, NBIS).
3. **Fallbackul este frecvent.** Pe lângă momentum (108, adică cele 107 de mai sus plus
   CSGP), avertismentul apare la book/market 30 (27% din cele 111 normalizate),
   gross profitability 24, FCF yield 20, cash earnings 10. Sectoare fără cohortă primară
   pentru o metrică: Communication Services (book, FCF, gross profitability), Consumer
   Staples (book), Energy (book, FCF, gross profitability), Financials (toate metricile
   contabile), Materials (book), Utilities (book, gross profitability), Real Estate (tot).
4. **Unele cohorte primare sunt la prag.** Utilities FCF yield are exact 10 (o observație în
   minus mută sectorul pe fallback), Consumer Discretionary book/market 11, Financials
   momentum 13, Materials FCF 13, Communication Services cash earnings 14 (15 rânduri; trei
   emitenți au două clase, numărate o singură dată).
5. **EBIT/EV și ROIC nu intră în nicio comparație.** În toate sectoarele nu sunt normalizate
   pentru niciun instrument: `NOT_REPORTED` la 363 și 373 din 411, plus `STALE_FILING` 36,
   `STALE_SHARES` 10 și 2 fără cohortă compatibilă. Cauza este cunoscută din
   [P0](../../P0-EXCLUSION-AUDIT.md): EV și capitalul investit cer `PREFERRED_EQUITY`,
   `MINORITY_INTEREST` și dobânzi raportate, iar regula datelor interzice să fie
   considerate zero. În practică, Value folosește FCF yield și book/market, iar Quality
   folosește gross profitability și cash earnings. O remediere este o schimbare de
   pregătire sau model versionată, în afara P2b.1.

## Tabelele raportului

| Sector | Membri | Eligibili | Profiluri | GENERAL | Cea mai mică cohortă primară |
|---|---|---|---|---|---|
| Communication Services | 23 | 22 | GENERAL 23 | 23 | 14 |
| Consumer Discretionary | 49 | 49 | GENERAL 47, UNKNOWN 2 | 47 | 11 |
| Consumer Staples | 34 | 34 | GENERAL 33, UNKNOWN 1 | 33 | 15 |
| Energy | 21 | 21 | FINANCIAL 1, GENERAL 20 | 20 | 18 |
| Financials | 76 | 76 | FINANCIAL 45, GENERAL 13, INSURER 18 | 13 | 13 |
| Health Care | 61 | 61 | GENERAL 56, INSURER 5 | 56 | 20 |
| Industrials | 87 | 85 | GENERAL 85, UNKNOWN 2 | 85 | 20 |
| Information Technology | 81 | 81 | FINANCIAL 1, GENERAL 77, UNKNOWN 3 | 77 | 30 |
| Materials | 25 | 25 | GENERAL 25 | 25 | 13 |
| Real Estate | 30 | 30 | EQUITY_REIT 27, FINANCIAL 2, GENERAL 1 | 1 | — |
| Utilities | 31 | 31 | GENERAL 31 | 31 | 10 |

Rânduri GENERAL pe metrică: normalizate în sector / pe fallback / nenormalizate (mărimea
cohortei primare).

| Sector | book_market | cash_earnings | ebit_ev | fcf_yield | gross_profitability | momentum | roic |
|---|---|---|---|---|---|---|---|
| Communication Services | 0 / 4 / 19 | 15 / 0 / 8 (14) | 0 / 0 / 23 | 0 / 6 / 17 | 0 / 8 / 15 | 22 / 0 / 1 (19) | 0 / 0 / 23 |
| Consumer Discretionary | 11 / 0 / 36 (11) | 43 / 0 / 4 (43) | 0 / 0 / 47 | 33 / 0 / 14 (33) | 26 / 0 / 21 (26) | 47 / 0 / 0 (47) | 0 / 0 / 47 |
| Consumer Staples | 0 / 7 / 26 | 26 / 0 / 7 (26) | 0 / 0 / 33 | 15 / 0 / 18 (15) | 24 / 0 / 9 (24) | 33 / 0 / 0 (33) | 0 / 0 / 33 |
| Energy | 0 / 4 / 16 | 18 / 0 / 2 (18) | 0 / 0 / 20 | 0 / 8 / 12 | 0 / 7 / 13 | 20 / 0 / 0 (20) | 0 / 0 / 20 |
| Financials | 0 / 6 / 7 | 0 / 9 / 4 | 0 / 0 / 13 | 0 / 5 / 8 | 0 / 6 / 7 | 13 / 0 / 0 (13) | 0 / 0 / 13 |
| Health Care | 20 / 0 / 36 (20) | 47 / 0 / 9 (47) | 0 / 0 / 56 | 40 / 0 / 16 (40) | 43 / 0 / 13 (43) | 56 / 0 / 0 (56) | 0 / 0 / 56 |
| Industrials | 20 / 0 / 65 (20) | 64 / 0 / 21 (64) | 0 / 0 / 85 | 44 / 0 / 41 (44) | 45 / 0 / 40 (45) | 82 / 0 / 3 (82) | 0 / 0 / 85 |
| Information Technology | 30 / 0 / 47 (30) | 70 / 0 / 7 (70) | 0 / 0 / 77 | 56 / 0 / 21 (56) | 66 / 0 / 11 (66) | 76 / 0 / 1 (76) | 0 / 0 / 77 |
| Materials | 0 / 6 / 19 | 16 / 0 / 9 (16) | 0 / 0 / 25 | 13 / 0 / 12 (13) | 20 / 0 / 5 (20) | 25 / 0 / 0 (25) | 0 / 0 / 25 |
| Real Estate | 0 / 0 / 1 | 0 / 1 / 0 | 0 / 0 / 1 | 0 / 1 / 0 | 0 / 1 / 0 | 0 / 1 / 0 | 0 / 0 / 1 |
| Utilities | 0 / 3 / 28 | 15 / 0 / 16 (15) | 0 / 0 / 31 | 10 / 0 / 21 (10) | 0 / 2 / 29 | 31 / 0 / 0 (31) | 0 / 0 / 31 |

## Ce înseamnă pentru comparația în API/UI (punctele 3–5)

- O „comparație pe sector” trebuie să spună ce populație folosește: membrii sectorului
  (toate profilurile) sau grupul de normalizare (numai GENERAL). În Financials și Real
  Estate cele două diferă de la 76 la 13 și de la 30 la 1.
- Scorul unui rând non-GENERAL e numai momentum; un rang în sector care l-ar amesteca cu
  rânduri GENERAL ar compara scoruri definite diferit. Rangul în sector trebuie calculat
  pe (sector, profil) sau numai între rânduri cu aceleași familii normalizate, cu
  populația afișată.
- Pentru fiecare metrică, răspunsul trebuie să arate dacă valoarea a fost normalizată în
  sector sau pe fallback (eticheta cohortei există deja în `factor_observations`).
- Avertizarea pentru eșantioane mici (sub 20 de membri comparabili) ar afecta acum
  Financials (13), Real Estate (1) și mai multe cohorte primare de 10–15.
- Alegerea contractului (câmpuri în `/screener/rankings` sau endpoint separat) rămâne
  pentru punctele următoare; scorul publicat nu se schimbă.

## Implementarea comparației (punctele 3 și 4)

`GET /screener/sectors?runId=` și `GET /screener/sectors/{sector}/comparison?runId=`
(contractul în [ENDPOINTS.md](../../ENDPOINTS.md)), în pachetul `screener.sectors` al
backend-ului web. Respectă regulile de mai sus: populația sectorului și grupul de
normalizare sunt raportate separat, rândurile `PRICE_ONLY` se clasează separat de cele
GENERAL, fiecare metrică are eticheta normalizării (`SECTOR`/`FALLBACK`/`NONE`), iar
grupurile sub 20 de eligibili au `SMALL_SAMPLE`. Nu se scrie nimic și nu se schimbă scorul.

Verificare pe baza locală, cu backend-ul reconstruit
([api-verification.json](bead056b/api-verification.json)): pentru toate cele 11 sectoare ale
`bead056b`, populațiile, profilurile și numărătorile de normalizare din API sunt identice cu
raportul de audit; rangul în sector este ordinea rangului global în fiecare grup, iar
exclușii nu au rang. Cele 12 cereri au durat 1,5 s în total. Teste: 2 de integrare (contract,
departajare, erori 400/401/403/404) și 3 unitare (grupuri, egalități, mediană pe emitent);
`web-backend` are 47 de teste, toate trecute.

Afișarea în UI (punctul 5) rămâne în P2c; comparația sector/industrie, în P5.
