# Cum se calculează clasamentul

Modelul actual este **1.2.0**. Pentru fiecare acțiune eligibilă, calculează un scor
din trei perspective: cât de ieftină pare compania (**Value**), cât de solidă este
activitatea ei (**Quality**) și cum a evoluat prețul (**Momentum**).

## Formula, pe scurt

```text
Scor final = 0,50 × Value + 0,30 × Quality + 0,20 × Momentum
```

Aceste trei valori sunt **scoruri normalizate**, nu sume în dolari sau procente.
Pentru fiecare indicator, modelul compară compania cu firme dintr-un grup comparabil:
limitează valorile extreme, calculează distanța față de media grupului și o limitează
la intervalul −3…+3. Value și Quality sunt fiecare media a trei indicatori
normalizați. Dacă unul lipsește, poate conta ca zero numai după ce ceilalți doi
au date valide. Momentum are un singur indicator și este obligatoriu.

| Componentă | Indicatori folosiți pentru profilul GENERAL | Ce urmăresc |
|---|---|---|
| **Value — 50%** | EBIT / valoarea întreprinderii; flux de numerar liber / capitalizare; capital propriu comun / capitalizare | Câtă activitate, numerar și valoare contabilă primește investitorul față de preț |
| **Quality — 30%** | Profit brut / active medii; randamentul capitalului investit (ROIC); (flux de numerar operațional − profit net) / active medii | Profitabilitatea și calitatea numerarului generat |
| **Momentum — 20%** | Preț ajustat la sesiunea t−21 / preț ajustat la t−252 − 1 | Evoluția aproximativă pe 12 luni, excluzând ultima lună |

Un scor final mai mare înseamnă o poziție mai sus în clasament. Ordinea se
calculează din scorurile nerotunjite; valorile afișate pot avea același număr
de zecimale fără să reprezinte un scor identic.

## Ce date intră în calcul

- **Raportări financiare SEC:** venituri, costul veniturilor, profit, dobânzi,
  flux de numerar operațional, investiții de capital, active, datorii,
  numerar și capital propriu. Pentru indicatorii anuali se folosesc valorile
  disponibile pe ultimele 12 luni și bilanțuri comparabile.
- **Date de piață Alpaca:** prețuri zilnice brute și ajustate pentru splituri și
  dividende, volume tranzacționate și acțiuni corporative. Prețul brut și
  numărul de acțiuni în circulație determină capitalizarea; prețurile ajustate
  intră în Momentum.
- **Date de referință:** apartenența datată la S&P 500 / Nasdaq-100, identificatori
  stabili ai instrumentelor, sector, grup comparabil și calendar bursier.

Modelul folosește doar informațiile disponibile până la momentul-limită al
runului. Verifică și istoricul prețurilor, lichiditatea, vechimea raportărilor și
datele necesare fiecărei componente. O companie cu date insuficiente sau profil
nesuportat apare cu motivul excluderii, fără scor final.

În fluxul canonic actual este activ profilul **GENERAL**. Formula include și
profiluri pentru bănci, asigurători și REIT-uri, dar acestea cer date suplimentare
care nu sunt încă suficiente pentru publicația curentă. Scorul este o ipoteză de
cercetare, cu acceptarea performanței istorice încă nefinalizată.

Formulele exacte și pragurile sunt în [manifestul modelului](../research-engine/src/quant_research/model/manifest.json).
Pentru execuția Java vezi [Scoring Service](SCORING-SERVICE.md), iar pentru
rezultat și filtre vezi [endpointurile](ENDPOINTS.md).
