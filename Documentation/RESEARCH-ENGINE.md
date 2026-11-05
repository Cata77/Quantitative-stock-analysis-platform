# Research Engine

Revizia [P2 1.4](P2-MODEL-1.4.md) se compară prin `quant_research.flexible_comparison`
cu `--before-version 1.3.0 --version 1.4.0`. Implicit rămâne comparația 1.2 → 1.3.
`publication_audit` reproduce automat 1.4 după versiunea runului. Sursele 1.3
sunt păstrate în `research-engine/fixtures/model-v1.3-archive/`.

Modul Python pentru formule, publicarea scorurilor, reproducerea publicațiilor și diagnosticarea
modelului. Rulează prin CLI; nu are server HTTP sau container permanent.

Decizia utilizatorului din 2026-11-05 (P2s): modelul se calculează și se publică
numai în Python. `quant_research.publication` reproduce publicațiile Java numai
prin citire (`reproduce`, `compare`). Publică runuri noi prin
`python -m quant_research.publication.cli current`, cu certificare, cu ciclul de
viață din Java și cu verificările triggerelor DB. Pentru `bead056b`, documentul de
input și toate tabelele de rezultate Java sunt reproduse identic. O publicație Python
pe o copie izolată, pe aceleași inputuri, are aceleași rezultate
([dovezi](evidence/p2s/README.md)). Conexiunea vine din `QUANT_DB_DSN`, cu rolul
`quant_scoring`.

Uneltele de audit de o singură folosință din P0/P2 (`exclusion_audit`, `source_audit`,
`p0_*`, `p2_*`, `input_review_audit`) sunt arhivate în
[`archive/p0_p2_audits`](../research-engine/archive/p0_p2_audits/README.md), cu testele lor
(51, rulate și în CI).

`python -m quant_research.universe_export` oferă [lista și exportul P1](UNIVERSE-EXPORT.md)
prin Gateway: univers complet, CSV, HTML local și verificări de integritate.
Nu calculează scoruri și nu necesită acces DB.

## Flow

1. Primește inputuri pregătite sau exportul canonic al inputurilor Scoring.
2. Verifică proveniența și elimină informațiile ulterioare cutoffului.
3. Calculează factorii, normalizarea, contribuțiile, rangurile și excluderile.
4. Certifică modelul pe fixture înaintea publicării; separat, poate evalua panouri de randamente.

`quant-model` folosește modelul versionat **1.2.0**.
`quant-validation` generează diagnostice istorice din CSV și dovezi de proveniență.
Comenzile vechi `quant-research` rămân exploratorii și folosesc modelul demonstrativ.

`python -m quant_research.exclusion_audit` generează diagnosticul offline al unei
publicații arhivate, cu reconciliere, proveniență și suprapunerea excluderilor.
Comanda și limitele auditului sunt în [diagnosticul P0](P0-EXCLUSION-AUDIT.md).

`quant_research.p0_sources` capturează inventarul SEC numai-citire pentru run;
`quant_research.p0_report` produce explicațiile complete și experimentele offline
cu recuperări măsurate. Studiile AAPL/PEP sunt specifice publicației auditate;
nu sunt reguli noi ale modelului de producție.

## Model și verificare

`quant_research.flexible_comparison --prepared <fișier> --output <director-nou>`
compară 1.2.0 și 1.3.0 pe aceleași date și păstrează scorurile, ponderile,
acoperirea și blocajele fiecărui instrument. [Formula flexibilă](P2-FLEXIBLE-MODEL.md)
are manifest și fixture-uri separate; `publication_audit` selectează modelul
după versiunea publicației, fără schimbarea reproducerilor 1.2.

Pregătirea P2, utilitarul de revizuire și comparația cu publicația păstrată sunt
descrise în [P2-DATA-PREPARATION.md](P2-DATA-PREPARATION.md).
`quant_research.input_review_audit` verifică selecția capitalurilor revizuite
înainte/după înregistrare, reutilizând SQL-ul Scoring; nu generează scoruri și
păstrează erorile pentru cutoffuri incompatibile.

`quant_research.publication_audit --run-id <UUID> --output <director-nou>`
verifică o publicație explicită: hash, reproducerea pregătirii și scorurilor în
Python, numerele reconciliate, sursele păstrate și cutofful provenienței prețurilor.
`quant_research.p2_snapshot_comparison --before <arhivă> --after <arhivă> --output <json>`
descrie toate schimbările dintre două publicații verificate, separat de efectul
cauzal al pregătirii. `quant_research.p2_period_audit` inventariază construcțiile
TTM și bilanțurile selectate, cu fapte și perioade. Comenzile complete și dovezile
sunt în raportul P2; comparațiile între date diferite nu înlocuiesc experimentul controlat.

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
