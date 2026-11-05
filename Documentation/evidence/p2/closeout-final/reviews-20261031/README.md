# P2 — revizuiri și corecții de referință, 31 octombrie 2026

Toate acțiunile de mai jos au fost autorizate explicit de utilizator la 2026-10-31.
Sursele sunt răspunsuri HTTP originale (bytes + SHA-256) indexate în
[source-files.json](source-files.json); momentul capturii nu este antedatat.
Scripturile sunt idempotente, verifică hashurile surselor și pasajele citate, nu șterg
date și nu modifică prețuri, acțiuni corporative sau timestampuri existente.

## Revizuiri individuale (39 de probleme, 12 instrumente)

[review-cases.py](review-cases.py) compară fiecare acțiune a furnizorului cu revizia
canonică exactă din DB și cu un pasaj din sursa primară, apoi adaugă o revizuire
`ACCEPT` legată de acea problemă. [reviews-applied.json](reviews-applied.json) conține
cele 39 de revizuiri, aplicate și verificate ca idempotente. Manifestele sunt în
[manifests/](manifests).

| Instrument | Probleme | Dovadă și interpretare |
|---|---:|---|
| ACN, ASML, FCX, NI, VLO | 6 | Comunicatele emitenților confirmă datele/sumele; toate ex-date-urile preced fereastra de 253 de ședințe (de la 2025-10-29). FCX: o declarație de 0,15 = 0,075 bază + 0,075 variabil, purtată de furnizor în două rânduri. ACN/ASML: sume nete după reținerea la sursă |
| DOC | 14 | 13 dividende lunare Healthpeak de 0,10167 USD, identice cu 8-K-urile; CUSIP 71943U104 aparține Physicians Realty Trust, deci schimbarea de identificator este o mapare veche a altui emitent |
| XOM | 6 | Redomiciliere NJ → Texas (holding nou, CIK 2115436), CUSIP 30233Q108, același simbol; 5 dividende confirmate |
| OKE | 6 | Reorganizare în holding (2026-09-10), schimb 1:1, CUSIP 30609A109; 5 dividende confirmate |
| CCL | 4 | Unificarea DLC și redomicilierea în Bermuda (2026-05-07), CUSIP G2004J103; 3 dividende de 0,15 USD |
| BDX | 1 | Raportul SEC 0,135343148384084 față de 0,135343142914 la furnizor: diferență relativă de 4e-8, sub rotunjirea de 1e-6 a metricilor; toleranța este explicită |
| TRI | 1 | Returnare de capital de 1,435518 USD și consolidare 0,984560 de la 2026-05-04; data plății nu afectează prețurile ajustate |
| SPCX | 1 | Fondul SPCX a devenit SPCK la 2026-04-07; SpaceX (CIK 1181412) are SPCX pe Nasdaq; `valid_from` 2026-08-27 exclude prețurile fondului |

## Corecții de referință

- [reference-corrections.py](reference-corrections.py), rezultat în
  [reference-corrections-applied.json](reference-corrections-applied.json):
  WBD are `valid_to=2026-10-06` (ultima tranzacționare la 5 octombrie, Nasdaq ECA2026-710),
  iar PSKY Class B devine SKYD/XNYS din 2026-10-06 (8-K Skydance). Rândul PSKY se
  închide la 2026-10-06, iar rândul SKYD are `available_at` = momentul corecției.
- [continuity-corrections.py](continuity-corrections.py): XOM, DOC, CCL și OKE erau
  valide numai de la data snapshotului (2026-08-27). Pe baza revizuirilor de mai sus,
  validitatea începe la 2025-08-01, ca la celelalte instrumente reconciliate, cu un
  rând de simbol datat pentru intervalul 2025-08-01..2026-08-27.

Limitare: `valid_from`, `valid_to` și `effective_to` nu au propriul timestamp de
disponibilitate. Publicațiile existente se reproduc din documentele stocate; o
interogare live pentru un cutoff vechi vede ciclul de viață corectat.

## Recolectare

[collect-raw-skyd.py](../../v15/collect-raw-skyd.py)
rulează un `force-refresh` RAW pentru 2026-10-06..2026-10-30, cu request ID
`p2-20261031-psky-skyd-symbol-correction`. Încercările originale rămân păstrate.
Seria ajustată din vintage-ul 2026-10-31, aflată deja în colectare, folosește
SKYD pentru ședințele procesate după corecție.
