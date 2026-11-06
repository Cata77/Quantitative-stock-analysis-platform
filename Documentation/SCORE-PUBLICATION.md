# Publicarea scorurilor (Python)

Scorurile se calculează și se publică numai din Python
([`quant_research.publication`](../research-engine/src/quant_research/publication)),
prin decizia P2s a utilizatorului din 2026-11-05. Procesul Java `scoring-service` a
fost eliminat la 2026-11-06: livrarea datelor canonice este acum în
[colector](MARKET-DATA-PRODUCER.md#procesarea-canonică-fostul-scoring-service), iar
rolul DB `quant_scoring` a rămas identitatea publicării Python (scrieri în `research`,
citirea inputurilor și revizuirile auditate din `operations.input_reviews`).
Vezi [Research Engine](RESEARCH-ENGINE.md) și [dovezile P2s](evidence/p2s/README.md).
Codul Java eliminat al modelului (1.2–1.5, `CanonicalModelInputs`, `ScoringRunService`,
`MonthEndScoringCoordinator`, testele de paritate) este păstrat în
[arhiva finală](../research-engine/fixtures/model-v1.5-java-final-archive/archive.json).
JAR-ul care a publicat `bead056b` era păstrat local în
`build/p2-psky-20261101/current-3/scoring.jar`; directorul `build/` din rădăcină a fost
șters accidental la 2026-11-06 (vezi [Database Migrations](DATABASE-MIGRATIONS.md#backup-și-restaurare)).
Sursele lui sunt în Git și în arhiva de mai sus.

## Ciclul unei publicații

Un run trece prin `PENDING → RUNNING → PUBLISHED` sau `FAILED`. Reluările folosesc
inputurile deja capturate. O publicație completă este imuabilă: numărul așteptat
trebuie să fie egal cu eligibili + excluși, cu minimum doi eligibili. Triggerele DB
(V012–V023) verifică lineage-ul, cutoffurile, contribuțiile și certificarea.

```powershell
$env:QUANT_DB_DSN = '<dsn quant_scoring>'   # numai în mediul procesului
.\research-engine\.venv\Scripts\python.exe -m quant_research.publication.cli current --score-date 2026-10-30 --cutoff 2026-11-01T20:00:00Z --raw-dataset <uuid> --adjusted-dataset <uuid> --classification <uuid> --mapping-version sec-us-gaap-v2
```

UUID-urile sunt cele din `infrastructure/.env` (`SCORING_MODEL_RAW_DATASET`,
`SCORING_MODEL_ADJUSTED_DATASET`, `SCORING_MODEL_CLASSIFICATION_VERSION`,
`SCORING_MODEL_MAPPING_VERSION`). Python publică modelul **1.5.0**
([politica titlurilor](P2-MODEL-1.5.md)) peste `canonical-preparation-v4`.
Publicațiile 1.2–1.4 rămân reproductibile din documentele stocate
(`quant_research.publication_audit`).

`CURRENT_SNAPSHOT_V1` cere ultima sesiune închisă la cutoff și aceeași dată New York
pentru întreaga istorie ajustată; cutofful este reutilizat la retry. Acceptă absența
unei sesiuni confirmată prin tentativa, checkpointul și răspunsul furnizorului păstrate
înainte de cutoff (`CURRENT_COLLECTION_OUTCOMES_V1`), înghețată în
`input_document.collectionCoverage`. Politica lunară `NEXT_OPEN_MINUS_30M_V1`
(`month_end_request`) fixează decizia cu 30 de minute înaintea următoarei deschideri și
cere acoperire completă. Nu există încă un scheduler: publicarea lunară automată
aparține P7.

## Rezultatul local păstrat

La **2026-11-02** s-a publicat și verificat runul de închidere P2
`bead056b-eb58-4e18-aa5b-6e7815d6f8e9`: model **1.5.0**, data economică
**2026-10-30**, cutoff explicit **2026-11-01T20:00:00Z**, vintage ajustat
2026-11-01, **518** instrumente, **515** scoruri și **3** excluderi documentate.
Dovezile sunt în [publicația de închidere](evidence/p2/closeout-final/publication-20261102/README.md).

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
[validarea platformei](../.github/workflows/scoring-parity.yml) rulează testele Python,
inclusiv cele DB (`QUANT_DB_TESTS=1`).
Vezi [Research Engine](RESEARCH-ENGINE.md) și [flow-ul general](Application.md).
