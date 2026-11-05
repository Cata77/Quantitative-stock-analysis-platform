# P2 — modelul 1.5.0: politica versionată a titlurilor

Versiunea **1.5.0** (manifest `58a1c2c2…f6de5`, înregistrat prin V023) păstrează
formulele, ponderile, pragurile și cohortele modelului [1.4.0](P2-MODEL-1.4.md).
Schimbarea privește numai ce titluri pot primi scor, conform deciziilor
utilizatorului din 2026-10-31. Pregătirea folosită este
`canonical-preparation-v4`, care derivă capitalurile comune numai din capitalurile
părintelui minus acțiunile preferențiale raportate în același filing și perioadă.

## Politica titlurilor (`security-policy-v2`)

| Situație | 1.4.0 | 1.5.0 |
|---|---|---|
| Clasă secundară (GOOG, FOX, NWS) | `NON_PRIMARY_SHARE_CLASS`, blocant | Scor din prețul și lichiditatea proprii clasei și din raportările emitentului |
| Certificat de depozit (ARM, PDD) | `INACTIVE_OR_UNSUPPORTED_SECURITY`, blocant | Scor din prețul propriu în USD; contabilitatea numai din filinguri mapate în USD |
| Instrument delistat | blocant | `INACTIVE_OR_UNSUPPORTED_SECURITY:DELISTED:<valid_to>`, blocant |
| Instrument încă nelistat / alt tip | blocant | `…:NOT_YET_LISTED:<data>` / `…:<tip>`, blocant |
| Motiv vechi fără detaliu | blocant | blocant |

Interogarea de inputuri adaugă detaliul motivului `INACTIVE_OR_UNSUPPORTED_SECURITY`
(delistare, listare viitoare sau tipul titlului). Codul nu se schimbă, iar modelele
anterioare îl tratează la fel. Publicațiile existente se reproduc din documentele
lor de input stocate, deci nu sunt afectate.

**Cohorte.** Statisticile de normalizare folosesc un singur rând per emitent: clasa
primară sau, dacă emitentul nu o are, singura clasă prezentă. Clasele secundare sunt
evaluate față de aceeași cohortă. Astfel, un emitent cu două clase nu contează de
două ori la medie și dispersie.

**Capitalizare.** Capitalizarea este definită numai la nivelul emitentului, pentru
toate clasele. Pentru un emitent cu mai multe instrumente în univers, numărul de
acțiuni al unei singure clase nu se folosește niciodată. Valorile Value devin
indisponibile, cu limitarea `MULTI_CLASS_ISSUER_CAPITALIZATION_UNAVAILABLE`, până
când există numărul de acțiuni pentru fiecare clasă, inclusiv pentru clasele
nelistate. În datele curente, Alphabet, Fox și News Corp nu au acțiuni pe clasă,
deci nici clasele primare nu aveau Value. Regula face explicit acest comportament.

**Certificate de depozit.** Prețul și lichiditatea sunt ale ADS-ului, în USD.
Parserul SEC acceptă în continuare numai 10-K/10-Q. ARM (20-F/6-K, US-GAAP în USD)
și PDD (20-F, CNY) nu au deci fapte contabile și primesc numai Momentum,
cu `PRICE_ONLY_UNSUPPORTED_ACCOUNTING_PROFILE`. Nu se presupune un raport ADS/acțiune
și nu se convertește implicit valuta.

## Limitări rămase

- Maparea 20-F/6-K, raportul ADS/acțiune și conversia valutară cer o versiune nouă
  de mapare SEC (`sec-us-gaap-v3`). Aceasta ar schimba identitatea faptelor pentru
  toți emitenții și ar invalida legăturile revizuite pe fapte (`COMMON_EQUITY`,
  `SHARE_CLASS`). Rămâne o etapă separată, cu migrarea revizuirilor.
- Acțiunile pe clasă pentru emitenții cu mai multe clase nu sunt colectate
  (companyfacts nu conține contextele dimensionale).
- Validarea istorică din P5 rămâne necesară. Paritatea Java/Python nu demonstrează
  performanță predictivă.

## Verificare

- Fixture adversarial `research-engine/fixtures/model-v1.5`, reconstruibil prin
  `build_fixture.py`, cu două clase secundare, un ADR, o delistare și motivul vechi.
- `FlexibleModel("1.5.0").certify()` reproduce rezultatul Python la 1e-9.

> Notă 2026-11-05 (P2s): codul Java al modelului a fost eliminat. Comanda Gradle de mai sus nu mai rulează. Sursele sunt în `research-engine/fixtures/model-v1.5-java-final-archive/`; reproducerea se face din Python (`quant_research.publication_audit`, `quant_research.publication.reproduce`).
- Teste Python: secundare fără dublarea emitentului în cohortă, fără capitalizare
  pe o singură clasă, ADR cu Momentum, delistări și alte tipuri blocante.
- Hashurile surselor formulei: [source-tree.json](evidence/p2/v15/source-tree.json).
