# Research Engine

Modul Python pentru formule, reproducerea scorurilor Java și diagnosticarea
modelului. Rulează prin CLI; nu are server HTTP sau container permanent.

## Flow

1. Primește inputuri pregătite sau exportul canonic al inputurilor Scoring.
2. Verifică proveniența și elimină informațiile ulterioare cutoffului.
3. Calculează factorii, normalizarea, contribuțiile, rangurile și excluderile.
4. Compară aceleași fixture-uri cu Java; separat, poate evalua panouri de randamente.

`quant-model` folosește modelul versionat **1.2.0**.
`quant-validation` generează diagnostice istorice din CSV și dovezi de proveniență.
Comenzile vechi `quant-research` rămân exploratorii și folosesc modelul demonstrativ.

## Model și verificare

Candidatul principal are ponderi **50/30/20** pentru Value/Quality/Momentum.
Candidații de cercetare sunt `primary`, `pure_value`, `value_quality`, `balanced`.
Manifestul, formulele și pragurile sunt în
[manifest.json](../research-engine/src/quant_research/model/manifest.json).

Fixture-urile active sunt în [model-v1](../research-engine/fixtures/model-v1);
`freeze.json` fixează hashurile fișierelor. Arhivele
[1.0.0](../research-engine/fixtures/model-v1.0-archive) și
[1.1.0](../research-engine/fixtures/model-v1.1-archive) păstrează fișierele
originale indicate de freeze-urile lor. Nu se modifică formula fără versionare
și regenerarea intenționată a dovezilor.

Paritatea verifică valorile nerotunjite la toleranță absolută 1e-9 și rezultatele
serializate la șase zecimale. Ea demonstrează consistența implementărilor,
nu performanța predictivă. Profilurile BANK, PC_INSURER, LIFE_INSURER și EQUITY_REIT
există pentru inputuri pregătite; adaptorul canonic activează deocamdată GENERAL.

## Rulare din rădăcina repo-ului

```powershell
.\.venv\Scripts\uv.exe sync --project research-engine --extra dev --locked
.\research-engine\.venv\Scripts\python.exe -m quant_research.model.cli --input research-engine/fixtures/model-v1/prepared.json
.\research-engine\.venv\Scripts\python.exe -m pytest research-engine/tests
.\research-engine\.venv\Scripts\python.exe -m ruff check research-engine
```

Prima comandă presupune uv instalat în mediul virtual local al repo-ului.
[pyproject.toml](../research-engine/pyproject.toml) și `uv.lock` definesc mediul.
Accesul la DB, când este necesar, folosește `RESEARCH_DB_DSN` și rolul doar-citire
`quant_research`.

## Ce mai trebuie validat

Evaluarea istorică are nevoie de componența reală a indicilor, date cunoscute la
momentul respectiv și randamente care includ delistări/acțiuni corporative.
Se păstrează separarea 60/20/20 între dezvoltare, validare și testul final;
deschiderea testului final este explicită. Se evaluează și costurile tranzacțiilor.

Datele locale și fixture-urile sintetice nu acoperă un istoric complet de 15–20 ani.
Rapoartele de diagnostic nu aprobă modelul; starea rămâne
`FROZEN_RESEARCH_HYPOTHESIS` / `NOT_EVALUATED`.
