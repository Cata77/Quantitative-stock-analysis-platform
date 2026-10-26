# P0 — Diagnostic încheiat al excluderilor

Încheiat la 2026-10-22 pentru runul `c2cf1551-a8fb-47ff-95eb-c4cb186778c9`.
Toate cele **469 de excluderi** au explicații legate de input, perioadă, profil
și sursă; **518 = 49 + 469** se reconciliază cu DB și cu modelul reprodus.
Au fost inventariate 515 legături către artefacte pentru toate cele 518 instrumente.
P0 închide diagnosticul; remediile de producție, versionarea și republicarea sunt P2.

## Rezultatul și ordinea recomandată pentru P2

Fiecare experiment recalculează **toată cohorta**, inclusiv normalizarea. Nu
numără drept recuperat un instrument care păstrează alte blocaje. Modelul 1.2.0,
datele originale și publicația nu au fost modificate.

| Intervenție diagnostică | Instrumente modificate | Eligibili după recalculare | Recuperate integral | Pierdute |
|---|---:|---:|---|---:|
| Evaluarea acoperirii GENERAL independent de ultimul filing gol | 50 | 51 | LII, VLTO | 0 |
| Reconciliere AAPL pe baza structurii capitalurilor din raportarea completă | 1 | 50 | AAPL | 0 |
| Izolarea unității invalide în selecția datoriei PEP | 1 | 49 | niciunul | 0 |
| Cost = venituri − profit brut, cu aceleași filinguri/perioade | 5 | 49 | niciunul | 0 |
| Combinația celor patru | 56 distincte | **52** | **AAPL, LII, VLTO** | **0** |

Acestea sunt recuperări **în experimente offline**, condiționate de politicile
explicite de mai jos, nu rezultate deja publicate. Nu sunt estimări ale
randamentelor și nici limita maximă a recuperării prin toate remediile posibile.

1. **Prioritate demonstrată: starea profilului GENERAL.** La 50 de instrumente,
   `NO_MAPPED_FACTS` provine din ultimul filing cu zero fapte parsate, deși
   inputul canonic conține date utilizabile. `FundamentalDataStore` emite starea
   per filing, iar SQL selectează ultima observație pentru întreaga companie.
   Pentru LII (`0001069202-26-000087`) și VLTO (`0001967680-26-000044`),
   verificarea artefactelor confirmă zero fapte în ultimul filing; celelalte
   date trec toate porțile existente. Experimentul elimină exclusiv blocajul
   `MODEL_NOT_SUPPORTED:NO_MAPPED_FACTS` pentru GENERAL cu date și filing gol
   dovedite. Celelalte 48 rămân excluse. P2 trebuie să versioneze această
   politică de acoperire, fără activarea profilurilor financiare.
2. **Reconcilierea capitalurilor pe dovezi explicite.** AAPL devine eligibil
   prin book/market; EBIT/EV și ROIC rămân lipsă. Valorile numerice sunt deja
   în arhivă; revizuirea semantică a raportării s-a făcut la 2026-10-22.
   Nu se atribuie preferred/minority zero și nu se modifică timestampurile.
   Experimentul nu pretinde că noua revizuire era disponibilă la cutofful vechi.
   Extinderea regulii la alte companii cere dovezi per emitent; nu se extrapolează
   recuperarea AAPL la cele 267 de instrumente excluse cu această categorie de lipsuri.
3. **Selecție pe unități și proveniență.** PEP are în același filing/perioadă
   `LongTermDebtCurrent`: 500 milioane EUR cu `INVALID_UNIT` și 1,6 miliarde USD
   cu `VALID`. Gruparea SQL pe metrică/perioadă aplică `bool_and(quality_state='VALID')`
   și respinge și valoarea USD. Experimentul izolează intrarea invalidă; PEP
   rămâne blocată de Value. P2 trebuie să păstreze respingerea conflictelor reale.
4. **Derivări contabile compatibile.** MTD, SOLV, DRI, GEHC și LITE au suficiente
   dovezi pentru cost din venituri minus profit brut, cu exact aceleași perioade,
   filinguri și unități. Repararea izolată nu aduce eligibili; nu justifică
   prioritizarea acestei intervenții înaintea celei de profil.

Separat, inventarul identifică la GENERAL cu inputul respectiv lipsă:
**192** instrumente cu `InterestExpenseNonoperating` recent, **77** cu
`PaymentsToAcquireProductiveAssets` și **40** cu o alternativă de pretax.
Aceste numere măsoară prezența candidaților în sursă, **nu recuperabilitatea**.
Există diferențe de sferă contabilă: în special dobânda operațională și cea
neoperațională nu sunt aliasuri arbitrare. Regula oficială
[XBRL US DQC_0181](https://xbrl.us/data-rule/dqc_0181/) documentează schimbarea
taxonomiei din 2024. P2 trebuie să trateze migrarea tagurilor și componentele
necesare, cu verificarea TTM și a celorlalte blocaje.

## Artefactele finale

- [Inventarul compact pe 518 instrumente](evidence/p0/instruments.csv), cu toate
  motivele și categoriile cauzelor.
- [Impactul intervențiilor](evidence/p0/impact.csv), cu recuperările complete.
- [Sumarul reproductibil](evidence/p0/summary.json), cu hashuri, suprapuneri,
  categorii și blocajele rămase după fiecare experiment.
- `build/p0-final/instruments.jsonl`: raportul detaliat cu valori, proveniență,
  fapte candidate, perioade și explicații pentru fiecare motiv.
- `build/p0-final/*-scenario.json`: patchurile diagnostice, politicile folosite
  și toate cele 518 de rezultate recalculate pentru fiecare intervenție.
- `build/p0-all-sources/manifest.json` și fișierele UUID asociate: inventarul
  surselor păstrate, hashuri, stările profilurilor și dovezile de respingere.

Pentru reproducerea raportului final, după comenzile inițiale de mai jos:

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.p0_sources --publication build/local-step5/current-20261001 --output build/p0-all-sources
.\research-engine\.venv\Scripts\python.exe -m quant_research.p0_report --publication build/local-step5/current-20261001 --sources build/p0-all-sources --output build/p0-final
```

Captura este numai-citire, reluabilă și legată de hashul publicației. Inventarul
păstrează conceptele mapate și familiile candidate relevante, faptele din ultimii
1100 de zile și data maximă a conceptului. Nu este o copie integrală a tuturor
raportărilor. Raportul final refuză surse modificate, identități neconcordante,
rezultate nereproductibile și motive de eligibilitate neclasificate.
Experimentele AAPL/PEP sunt studii de caz ale acestei publicații, nu reguli
generice care pot fi aplicate oricărei date sau companii.

## Reproducere

Din rădăcina repository-ului, cu mediul Research Engine instalat:

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.exclusion_audit --publication build/local-step5/current-20261001 --output build/p0-exclusion-audit
```

Utilitarul [exclusion_audit.py](../research-engine/src/quant_research/exclusion_audit.py)
citește exclusiv arhiva locală. Verifică SHA-256 al inputului față de run,
unicitatea și egalitatea mulțimilor de instrumente, numerele publicației,
concordanța motivelor ELIGIBILITY și reproduce integral rezultatul modelului
din inputurile pregătite. Refuză dovezile care nu se reproduc cu modelul instalat.
Nu rulează colectări și nu modifică publicația sau formula.

Rezultate generate, locale și ignorate de Git:

- `build/p0-exclusion-audit/summary.json`: run, cutoff, hashurile celor cinci
  fișiere de intrare, reconciliere, suprapuneri și diferențe în export.
- `build/p0-exclusion-audit/instruments.jsonl`: câte un obiect pentru fiecare
  instrument, inclusiv eligibilii; profil, sector, metrici brute/normalizate,
  derivări, inputuri disponibile/lipsă, proveniență, fapte selectate, perioade,
  candidați canonici pentru inputurile lipsă și toate excluderile, cu stadiu.

`ABSENT_CANONICAL_SOURCE_OR_MAPPING_UNRESOLVED` nu dovedește absența în SEC.
`CANONICAL_PRESENT_PERIOD_UNIT_OR_CUTOFF_REVIEW` indică fapte canonice existente
care necesită verificarea selecției; nu afirmă automat o eroare TTM.
Stările originale `NOT_REPORTED`, `STALE` etc. sunt păstrate separat.
Identificatorii surselor permit continuarea auditului în baza de date;
raportul nu pretinde că a deschis artefactele originale.

## Constatări reproductibile

Run: `c2cf1551-a8fb-47ff-95eb-c4cb186778c9`, model 1.2.0, data economică
2026-09-30, cutoff 2026-10-01T19:07:09Z. Reconciliere: **518 = 49 + 469**.
Toate cele 469 de instrumente excluse au motive concordante între rezultatul
modelului reprodus și exportul excluderilor. Verificarea DB descrisă mai jos
a confirmat ulterior egalitatea tuturor celor 518 de rezultate cu arhiva.

| Motiv, grupat după prefix | Instrumente excluse | Singurul prefix ELIGIBILITY |
|---|---:|---:|
| INSUFFICIENT_VALUE_COVERAGE | 363 | 126 |
| INSUFFICIENT_QUALITY_COVERAGE | 200 | 14 |
| MODEL_NOT_SUPPORTED | 156 | 57 |
| CORPORATE_ACTION_REVIEW | 69 | 6 |
| MISSING_INSTRUMENT_SHARES | 59 | 0 |
| INVALID_MARKET_CAP | 59 | 0 |
| STALE_SHARES | 19 | 0 |

182 de instrumente au simultan acoperire Value și Quality insuficientă.
Cele 59 fără acțiuni au și capitalizare invalidă. Cele 156 cu
`MODEL_NOT_SUPPORTED` nu sunt 156 de companii financiare: distribuția tuturor
excluderilor pe profil este GENERAL 363, FINANCIAL 49, EQUITY_REIT 27,
INSURER 23, UNKNOWN 7.

„Singurul prefix” descrie blocajele înregistrate, **nu numărul recuperabil**.
Un prefix poate grupa mai multe cauze, iar normalizarea poate introduce blocaje
suplimentare după repararea inputurilor. Câmpul de recuperabilitate din simpla
analiză de suprapunere rămâne `null`; impactul măsurat este în `scenarios` și
în tabelul final de mai sus.

### Cazuri inițiale

- **AAPL / GENERAL:** singurul motiv ELIGIBILITY este acoperirea Value.
  FCF yield, gross profitability, cash earnings și momentum au valori brute;
  EBIT/EV, book/market și ROIC lipsesc. Există 19 fapte canonice de dobândă,
  dar cel mai recent se încheie la 2023-09-30, față de perioada curentă
  2026-06-27. Profitul operațional TTM există, însă formula versionată EBIT
  cere PRETAX_INCOME_TTM + INTEREST_EXPENSE_TTM. Lipsesc COMMON_EQUITY,
  PREFERRED_EQUITY și MINORITY_INTEREST, inclusiv comparațiile anterioare;
  EV și capitalul investit rămân incomplete. Nu se poate înlocui automat
  dobânda sau soldurile absente cu zero.
- **JPM / FINANCIAL:** motivul detaliat cere profil de business verificat și
  ancoră de reglementare. Simpla redenumire în BANK nu validează inputurile.
- **O / EQUITY_REIT:** lipsa reconcilierii FFO/AFFO coexistă cu lipsa ancorei,
  inputuri de risc și acoperire insuficientă. `NON_POSITIVE_EBITDARE` trebuie
  interpretat împreună cu inputurile lipsă, nu ca dovadă de EBITDARE negativ raportat.
- **LULU:** CSV-ul are `GE/NERAL`, dar rezultatul modelului reprodus are `GENERAL`.
  Exportul are exact cele 49 de identități eligibile și rangurile/simbolurile
  concordă. Singura diferență în cele trei câmpuri comparate este profilul LULU.
  Nu s-a modificat arhiva. Această constatare nu certifică toate câmpurile CSV.

Mapările V010 disting `CommonStockholdersEquity → COMMON_EQUITY` de
`StockholdersEquity → STOCKHOLDERS_EQUITY`. V017 permite reconcilierea numai
cu dovezi explicite de preferred zero și fără conflicte. Absența COMMON_EQUITY
din input nu justifică eliminarea acestor condiții. Următorul audit trebuie să
urmărească tagurile originale, dimensiunile, filingul și dovezile de reconciliere.

## Categorii și limite ale diagnosticului

Raportul separă conceptul mapat absent din Company Facts, contexte neutilizabile,
alte perioade, lipsa combinației TTM, reconcilierea capitalurilor, taguri candidate
nemapate, istoricul fiscal anual, lipsa bilanțului comparabil, identitatea acțiunilor
și suplimentele de profil neimplementate. Lipsa unui tag în sursa păstrată nu este
declarată absență din toate notele companiei. O mapare candidată nu produce valori.
Cele 469 de excluderi sunt explicate la nivelul datelor și regulilor publicației;
P0 nu promite eliminarea tuturor blocajelor și nici 518 scoruri.

## Continuare cu baza de date și sursele SEC păstrate

După pornirea Docker, verificarea din 2026-10-22 a confirmat starea `PUBLISHED`,
hashul inputului, numerele 518/49/469 și **egalitatea integrală a celor 518
obiecte `stock_scores.output` cu arhiva**. A fost verificat același run explicit.

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.source_audit --publication build/local-step5/current-20261001 --output build/p0-source-audit/verified
```

[source_audit.py](../research-engine/src/quant_research/source_audit.py) rulează
interogările în tranzacții `READ ONLY` cu `SET LOCAL ROLE quant_research`.
Folosește conexiunea locală `psql` din container, fără afișarea credențialelor.
Raportul `build/p0-source-audit/verified/source-summary.json` include proveniența,
tagurile SEC, faptele din filingul și perioada selectate, prezența în payloadul
parserului și mapările active. Artefactele integrale citite sunt salvate alături,
sub UUID, pentru inspecție offline; nu sunt introduse în Git.

Pentru fiecare caz s-au verificat lanțul artefactelor, concordanța hashurilor
**înregistrate**, CIK-ul, observarea anterioară cutoffului și egalitatea corpului
JSON SEC original păstrat cu `companyFacts` parsate. SHA-256 al corpului
răspunsului este calculat separat și inclus în raport. Nu se pretinde
recalcularea hashului Java al întregului bundle din serializarea JSONB.

| Instrument | Accession verificat | Perioada curentă | Constatare în sursa păstrată |
|---|---|---|---|
| AAPL | 0000320193-26-000020 | 2026-06-27 | StockholdersEquity = 107.520.000.000 USD; lipsesc tagul direct common equity și dovezile preferred zero |
| LULU | 0001397187-26-000127 | 2026-08-02 | Equity = 4.791.174.000 USD, PreferredStockValue = 0, shares issued/outstanding = 0 |
| JPM | 0001628280-26-054343 | 2026-06-30 | InterestExpenseOperating există pentru YTD și trimestru, dar nu are mapare și nu intră în payloadul parserului |
| O | 0000726728-26-000048 | 2026-06-30 | Aceeași lacună pentru InterestExpenseOperating; lipsurile FFO/AFFO sunt blocaje distincte |

La **AAPL**, `InterestExpense` se oprește și în Company Facts la 2023-09-30;
`InterestExpenseOperating` nu există în răspunsul păstrat. Nu este demonstrată
o pierdere în parser pentru tagul mapat. Capitalurile totale actuale există
atât în sursă, cât și în payloadul parserului, însă regula V017 nu le poate
transforma în COMMON_EQUITY în lipsa dovezilor preferred zero. Este o limită
demonstrată a acoperirii Company Facts/regulii de reconciliere, nu absența
capitalurilor proprii din raportarea companiei.

La **LULU**, aceeași regulă are dovezile explicite necesare: sursa conține
capitaluri și preferred value/shares zero. Cazul oferă un control pozitiv pentru
reconciliere; alte inputuri lipsă nu împiedică satisfacerea acoperirii minime.

La **JPM**, vechiul `InterestExpense` se oprește la 2024-03-31; noul tag
`InterestExpenseOperating` are 48.938.000.000 USD pentru primul semestru și
25.113.000.000 USD pentru trimestrul al doilea din 2026. La **O**, vechiul tag
se oprește la 2024-09-30; cel nou are 604.023.000 USD YTD și 312.083.000 USD
trimestrial. Maparea `sec-us-gaap-v2` nu include acest concept, iar parserul
selectează conceptele mapate (plus dovezile preferred). Aceasta demonstrează o
lacună de colectare/mapare; echivalența contabilă cu metrica modelului și
construirea TTM trebuie validate înaintea implementării în P2. Cele două cazuri
au și profiluri nesuportate, deci nu sunt numărate drept instrumente recuperate.

Au fost inspectate și raportările complete
[AAPL, paginile 3–4](https://www.sec.gov/Archives/edgar/data/320193/000032019326000020/aapl-20260627.htm)
și [LULU, pagina 3](https://www.sec.gov/Archives/edgar/data/1397187/000139718726000127/lulu-20260802.htm).
La AAPL, bilanțul și variația capitalurilor enumeră common stock/APIC, rezultatul
reportat și OCI; sumele concordă cu valorile deja păstrate pentru perioadele
2026-06-27 și 2025-06-28. Aceasta susține studiul de caz, fără inferred zero pentru
celelalte componente. La LULU, bilanțul confirmă explicit lipsa acțiunilor
preferred emise și în circulație, control pozitiv pentru reconcilierea existentă.
JPM și O au fost urmărite până la răspunsurile SEC originale păstrate; nu se
pretinde revizuirea manuală a tuturor filingurilor întregului univers.

Validare finală: **21 de teste**, Ruff, reproducerea modelului pentru 518 membri,
concordanța cu DB, hashurile celor 515 surse și cinci recalculări integrale.
Nu este necesară o nouă paritate Java/Python deoarece formula și adaptoarele de
producție nu au fost schimbate. Implementarea remediilor în P2 va cere paritate
și versionare. Niciuna dintre simulări nu a fost publicată.
