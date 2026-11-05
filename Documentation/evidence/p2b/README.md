# P2b — endpoint financiar și RSI, 2 noiembrie 2026

## Continuare 2026-11-05: D&A și EBITDA (`EBITDA_FROM_SOURCE_V1`)

Decizia utilizatorului: EBITDA informativ, fără efect pe scor, fără recolectare SEC,
mapare nouă sau publicație nouă. D&A se citește din răspunsul SEC companyfacts
păstrat în spatele faptelor `OPERATING_INCOME_TTM` ale publicației: același filing
(`accn`), aceleași date de început/sfârșit și un singur concept us-gaap standard pentru
toate componentele (`DepreciationDepletionAndAmortization`, apoi
`DepreciationAndAmortization`). Faptele reprocesate (`reprocess-v2`) sunt urmărite
până la răspunsul original. Combinația TTM (anual, 4 trimestre sau anual + YTD −
YTD anterior) se reconstruiește din perioade și este acceptată numai dacă reproduce
exact valoarea publicată. EBITDA = `OPERATING_INCOME_TTM` + D&A.

| Verificare | Rezultat | Fișier |
|---|---|---|
| JAR nou, rol `quant_screener`, toate cele 518 instrumente din `bead056b` față de un calcul SQL independent (rol `postgres`) | 0 diferențe; 288 cu EBITDA; 101 `DEPRECIATION_AMORTIZATION_NOT_REPORTED`; 109 + 20 fără `OPERATING_INCOME_TTM` acceptat | [ebitda-verification.json](ebitda-verification.json) |
| AAPL, calcul manual | D&A 11.698 + 9.973 − 8.571 = 13.100 mil.; EBITDA 154.859 + 13.100 = 167.959 mil. USD, TTM la 2026-06-27 | idem |
| Gateway după reconstruirea containerului, `view=SUMMARY` | `DISPLAY_METRICS_V2`; AAPL cu valori, MSFT și JPM cu motive explicite | [ebitda-gateway-summary.json](ebitda-gateway-summary.json) |
| `:screener-service:test`, `:database-migrations:test` (JDK 25) | 33 și 19 teste, 0 eșecuri | — |

`NOT_REPORTED` înseamnă că răspunsul păstrat nu are un concept standard acceptat pe
aceleași perioade și în același filing, nu că emitentul nu raportează amortizarea.
MSFT, de exemplu, are în răspuns numai componente separate (`Depreciation`,
`AmortizationOfIntangibleAssets` etc.). Totalul D&A nu apare sub un concept us-gaap
standard. Adunarea componentelor sau conceptele cu acrețiune
(`DepreciationAmortizationAndAccretionNet`, 22 de instrumente) cer o regulă nouă,
versionată. Drepturile noi au fost aplicate după backupul verificat
`build/p2b-ebitda-20261105/backup/` (artefact local, ignorat de Git). Scriptul:
[ebitda-verify.py](ebitda-verify.py).

## Verificarea inițială, 2 noiembrie

Endpointul `GET /screener/instruments/{instrumentId}/financials?runId=...` a fost
verificat pe publicația `bead056b-eb58-4e18-aa5b-6e7815d6f8e9`. Contractul este în
[ENDPOINTS.md](../../ENDPOINTS.md#date-financiare-ale-unei-publicații).

| Verificare | Rezultat | Fișier |
|---|---|---|
| JAR nou, rol `quant_screener`, 8 instrumente (AAPL, MSFT, JPM, O, SKYD, HONA, SPCX, WBD) | valorile inputurilor sunt identice cu `prepared_input`, 0 diferențe | [verification.json](verification.json) |
| RSI față de un calcul independent (selecție SQL ca rolul `postgres` + Python) | identic la 1e-4 pentru toate; WBD `MISSING_SCORE_DATE_PRICE` | idem |
| Ultimul preț folosit de RSI = `ADJUSTED_CLOSE` al publicației | da, pentru toate cele 8 | idem |
| Gateway: rând din rankings → financials, eligibil (NBIS) și exclus (SPCX) | același run, rang și eligibilitate; 401 fără token | [gateway-check.json](gateway-check.json) |
| 404 instrument absent, 400 fără `runId` | da | [verification.json](verification.json) |

Pentru SPCX și HONA, RSI folosește 46 de ședințe. Ambele instrumente sunt valide
în referințe din 2026-08-27. Barele anterioare (fondul SPCX, respectiv HONA înainte
de această dată) sunt excluse, ca în scoring. Pentru HONA, data de validitate din
referințe este ulterioară începutului tranzacționării regular-way (2026-06-29).
Aceasta este o limită a referințelor, nu a calculului.

Scripturile ([run-screener.py](run-screener.py), [verify.py](verify.py),
[gateway-check.py](gateway-check.py)) citesc credentialele numai din mediul
containerelor și creează un utilizator de test temporar. Nu salvează secrete.

## Vederea pentru utilizator

[aapl-summary.json](aapl-summary.json) este răspunsul implicit (`view=SUMMARY`)
pentru AAPL prin Gateway: ~210 linii, fără câmpuri goale. Verificări manuale:
P/E 39,01 = 5.029.592.253.400 / 128.930.000.000; marja brută 48,65% =
227.123 / 466.823 mil.; FCF yield 2,72% = 136.683 mil. / capitalizare. EBITDA,
P/B, ROE, EV/EBIT și randamentul dividendului lipsesc cu motive explicite.
