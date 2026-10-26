# Scoring Service

Transformă evenimentele Kafka în date canonice și calculează clasamente reproductibile.
Port local **18085** (8085 în container), folosit pentru health și metrici.

## Flow

1. Consumă `market-data-observations-v2` și validează schema, cheia și sursa.
2. Salvează inbox-ul și datele canonice în aceeași tranzacție; duplicatele nu repetă efectele.
3. Mesajele invalide sunt auditate și trimise în `market-data-observations-v2-dlq`.
4. La calcul verifică universul, calendarul, acoperirea și momentul-limită al informațiilor.
5. Capturează și hashuiește inputurile, apoi calculează întregul univers.
6. Publică atomic scorurile, factorii, excluderile și proveniența; Screener le poate citi.

Un run trece prin `PENDING → RUNNING → PUBLISHED` sau `FAILED`.
Reluările folosesc inputurile deja capturate. O publicație completă este imuabilă:
numărul așteptat trebuie să fie egal cu eligibili + excluși, cu minimum doi eligibili.

## Modelul curent

Modelul **1.2.0** combină **50% Value + 30% Quality + 20% Momentum**.
GENERAL folosește EBIT/EV, FCF/market cap, book/market, gross profitability, ROIC,
cash earnings și momentum între sesiunile t-252 și t-21.

Valorile sunt normalizate în grupuri comparabile, cu limitarea extremelor și a
z-score-urilor. Sunt necesare minimum două componente Value, două Quality și Momentum.
Lipsurile, acoperirea slabă și profilurile nesuportate sunt raportate explicit.
Adaptorul canonic activează GENERAL; celelalte profiluri necesită date suplimentare.

Politica lunară `NEXT_OPEN_MINUS_30M_V1` fixează decizia cu 30 de minute înaintea
următoarei deschideri, folosind prețurile până la închiderea de final de lună.
`CURRENT_SNAPSHOT_V1` permite separat un snapshot curent, cu un cutoff explicit
după colectare. Descărcările târzii nu repară retroactiv un cutoff lunar ratat.

## Operare

Codul curent folosește pregătirea `canonical-preparation-v3` pentru runuri noi,
cu schema V020. Vezi [regulile P2 și comparația controlată](P2-DATA-PREPARATION.md).
Reproducerile istorice păstrează `profiles-v1`; formula rămâne 1.2.0.

Snapshoturile curente folosesc `CURRENT_COLLECTION_OUTCOMES_V1`: acceptă și
absența unei sesiuni confirmată prin tentativa, checkpointul și răspunsul
furnizorului păstrate înainte de cutoff, cu excluderea explicită a instrumentului.
Aceste dovezi sunt înghețate în `input_document.collectionCoverage`. Erorile
tehnice și colectările neterminate rămân blocante. Publicarea lunară cere în
continuare acoperirea completă; joburile cu lipsuri nu sunt marcate artificial complete.

Se configurează `SCORING_MODEL_RAW_DATASET`, `SCORING_MODEL_ADJUSTED_DATASET`,
`SCORING_MODEL_CLASSIFICATION_VERSION` și `SCORING_MODEL_MAPPING_VERSION`.
UUID-urile provin din importurile bazei respective. `SCORING_MONTH_END_ENABLED`
controlează calculul lunar automat.

| Argument `--scoring.command.operation=` | Input suplimentar |
|---|---|
| `score-and-exit` | `--scoring.command.score-date=YYYY-MM-DD` (final de lună) |
| `current-score-and-exit` | `--scoring.command.score-date` și `--scoring.command.knowledge-cutoff` UTC |
| `dlq-inspect` | `--scoring.command.limit=20` |
| `dlq-replay` | `--scoring.command.dead-letter-id`, `--scoring.command.replay-id` (UUID), `--scoring.command.operator`, `--scoring.command.reason` |

Comenzile rulează prin JAR/Compose, fără endpoint HTTP de declanșare.
Snapshotul curent trebuie să folosească ultima sesiune închisă la cutoff și aceeași
dată New York pentru întreaga istorie ajustată. Cutofful este reutilizat la retry.
Pentru universul local complet, calculul manual a necesitat
`JAVA_TOOL_OPTIONS=-Xms64m -Xmx2g`.

## Rezultatul local păstrat

La **2026-10-25** s-a publicat și verificat runul P2
`76e93093-54e7-4fa8-9b44-760857d69ae1`: data economică **2026-10-23**, cutoff
**2026-10-24T23:59:59Z**, pregătire `canonical-preparation-v3`, **518** instrumente,
**49** eligibile și **469** excluse. Dovezile sunt în
[raportul P2](P2-DATA-PREPARATION.md) și `build/p2-close-20261025/publication/`.
Publicația anterioară de mai jos rămâne imuabilă și accesibilă prin `runId`.

La **2026-10-01** s-a publicat runul `c2cf1551-a8fb-47ff-95eb-c4cb186778c9`:
data economică **2026-09-30**, cutoff **2026-10-01T19:07:09Z**,
**518** instrumente, **49** eligibile și **469** excluse.
Este `CURRENT_RESEARCH_SNAPSHOT`; aprobarea modelului rămâne
`FROZEN_RESEARCH_HYPOTHESIS`, iar acceptarea istorică `NOT_EVALUATED`.
Evidențele locale sunt în `build/local-step5/current-20261001/`.

Tabelele `research.scoring_runs`, `stock_scores`, `factor_observations`,
`score_exclusions` și `score_lineage` păstrează rezultatul și explicațiile.
[Manifestul](../research-engine/src/quant_research/model/manifest.json) definește formulele;
[testele de paritate](../.github/workflows/scoring-parity.yml) compară Java cu Python.
Vezi [Research Engine](RESEARCH-ENGINE.md) și [flow-ul general](Application.md).
