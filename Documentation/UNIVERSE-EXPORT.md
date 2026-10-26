# Lista și exportul întregului univers — P1

Utilitarul întreținut în repo este
[`quant_research.universe_export`](../research-engine/src/quant_research/universe_export.py).
Accesează API-ul Screener prin Gateway, cu `research:read`, și generează o listă
HTML locală plus CSV. Nu necesită acces la baza de date sau modificarea modelului.

## Rulare

Din rădăcina repo-ului, cu serviciile locale pornite și un cont existent:

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.universe_export --username utilizator_demo --output build/universe-export
```

Parola se introduce la prompt, fără afișare. Pentru automatizare se poate folosi
variabila `QUANT_ACCESS_TOKEN` sau `--username` împreună cu `QUANT_PASSWORD`.
Credențialele nu se pun în argumentele comenzii sau în fișierele exportate.
Utilitarul nu creează conturi. `--gateway` are implicit `http://localhost:18080`;
în afara localhost este obligatoriu HTTPS. Redirecturile HTTP sunt respinse.

Selectarea publicației auditate în P0/P1:

```powershell
.\research-engine\.venv\Scripts\python.exe -m quant_research.universe_export --username utilizator_demo --run-id c2cf1551-a8fb-47ff-95eb-c4cb186778c9 --page-size 100 --output build/universe-20260930
```

Fără `--run-id`, prima pagină selectează ultima publicație compatibilă.
`--as-of YYYY-MM-DD` limitează data economică a publicației. Toate paginile
ulterioare păstrează `runId` și `requestedAsOf` din prima pagină, inclusiv dacă
între timp apare o publicație nouă. Mărimea paginii poate fi 1–200.

Directorul de ieșire trebuie să fie nou; un export existent nu este suprascris.
La eroare comanda se încheie cu cod 1. Lipsa unei publicații, expirarea tokenului,
o pagină incompletă sau o nepotrivire de număr/rang nu produc un export valid.
`verification.json`, scris ultimul, semnalează finalizarea. Dacă scrierea pe disc
eșuează, directorul poate conține fișiere parțiale fără acest certificat.

## Fișiere și vizualizare

- `universe.html`: lista completă, deschisă local în browser. Implicit arată
  toate instrumentele; selectorul permite numai eligibile sau numai excluse.
  Căutarea filtrează simbolul. Totalurile publicației rămân vizibile, distinct de
  numărul rândurilor afișate. Detaliile fiecărui instrument conțin factorii,
  excluderile și avertismentele primite de la API. Nu face cereri de rețea.
- `universe.csv`: un rând per instrument, implicit întregul univers. Conține
  identitatea, simbolul, profilul, sectorul, grupul de comparație, apartenența la
  indici, eligibilitatea, rangul, percentila, scorul compozit, scorurile și
  contribuțiile familiilor, numărul metricilor disponibile/lipsă, fracția de
  acoperire, motivele, factorii și metadatele publicației.
- `pages.json`: toate paginile API din univers, în ordinea recepționării.
- `verification.json`: runul, numerele reconciliate, selecția CSV, verificările
  efectuate și SHA-256 pentru CSV, HTML și paginile păstrate.

`--eligibility ELIGIBLE` sau `--eligibility EXCLUDED` restrânge numai CSV-ul.
Colectarea și validarea folosesc întotdeauna explicit `eligibility=ALL` și
`universe=UNION`. HTML și paginile păstrează universul complet, astfel încât se
poate reveni la toate instrumentele fără o nouă descărcare.

CSV este UTF-8 cu BOM, cu citarea standard a virgulelor, ghilimelelor și liniilor
noi. Listele și obiectele sunt JSON în celule. Valorile numerice API sunt citite
ca `Decimal`, fără conversie intermediară la float: CSV păstrează precizia,
iar JSON-urile exportate reprezintă numerele zecimale prin șiruri exacte.
Booleanurile și întregii rămân tipizate în JSON. Importă CSV-ul cu coloanele
textuale ca text dacă îl deschizi într-un program de calcul tabelar.

## Semantica rezultatelor

Scorul compozit, rangul și percentila instrumentelor excluse sunt neatribuite:
celule goale în CSV, `null` în paginile JSON, „—” în HTML. Zero este o valoare
numerică reală, afișată distinct. Scorurile parțiale și factorii se preiau numai
dacă sunt efectiv disponibili în publicație, fără completări sau recalculări.

Acoperirea universului este `scoredCount / expectedCount`. Acoperirea unui
instrument este `availableMetricCount / (availableMetricCount + missingMetricCount)`;
la numitor zero rămâne neatribuită. Acestea nu sunt măsuri de încredere predictivă.

Rangul și percentila compară toate instrumentele eligibile din reuniunea
indicilor, în aceeași publicație. Filtrele nu schimbă populația clasamentului.
Rangul folosește scorul nerotunjit descrescător, cu UUID pentru egalități.
Percentila folosește rangul mediu al scorurilor egale:
`100 × (N − rang mediu) / (N − 1)`, unde N este numărul eligibililor.
Scorurile afișate sunt rotunjite; egalitatea vizibilă nu implică egalitate înainte
de rotunjire. Rangul și percentila nu reprezintă randamente estimate.

## Verificări

Utilitarul respinge duplicatele de `instrumentId`, schimbarea publicației sau
metadatelor între pagini, paginile lipsă/incomplete, totalurile inconsistente,
rangurile lipsă/duplicate, scorurile compozite atribuite exclușilor și excluderile
fără motiv la stadiul `ELIGIBILITY`. Compară toate celulele CSV recitite cu datele
API din memorie, pentru a detecta și coruperea exportului.

```powershell
.\research-engine\.venv\Scripts\python.exe -m pytest research-engine/tests/test_universe_export.py
.\research-engine\.venv\Scripts\python.exe -m ruff check research-engine/src/quant_research/universe_export.py research-engine/tests/test_universe_export.py
```

Acest flux livrează P1 prin CLI și listă locală. Integrarea într-o interfață web
cu login și navigare rămâne P2c; nu există un nou endpoint de descărcare CSV.
Contractul API existent și valoarea implicită `eligibility=ELIGIBLE` sunt păstrate.

## Rezultatul verificat la 2026-10-22

Publicația `c2cf1551-a8fb-47ff-95eb-c4cb186778c9`, model 1.2.0, data economică
2026-09-30: **518 instrumente unice, 49 eligibile, 469 excluse, acoperire 9,46%**.
Aceste valori descriu această publicație; utilitarul nu le hardcodează.

CLI-ul a rulat autentificat prin Gateway cu șase pagini de câte maximum 100 de
rânduri. O colectare independentă cu patru pagini de maximum 137 a confirmat
același univers. Identitățile, simbolurile, sectoarele, profilurile, scorurile,
rangurile, percentilele, contribuțiile, avertismentele și motivele au fost
comparate cu publicația păstrată. Toate cele 518 rezultate complete din DB sunt
egale cu arhiva modelului. LULU are `GENERAL` în noul export, fără alterarea
CSV-ului istoric corupt.

Cele 17 teste Python și verificarea Ruff trec. Scriptul JavaScript extras din
HTML-ul real a fost executat într-un harness DOM cu Node: ALL=518, eligibile=49,
excluse=469, căutare LULU=1 și căutare fără rezultate=0. Nu este un test vizual
în browser. Backendul și formula nu s-au schimbat.

Dovezi păstrate în repo:
[verificarea exportului](evidence/p1/verification.json) și
[reconcilierea publicației](evidence/p1/publication-cross-check.json).
Fișierele complete generate local sunt în `build/p1-universe-verified/`:
`universe.html`, `universe.csv`, `pages.json` și cele două rapoarte JSON.
