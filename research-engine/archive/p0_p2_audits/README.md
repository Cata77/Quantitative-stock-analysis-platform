# Arhivă: unelte de audit P0/P2

Mutate din `quant_research` la 2026-11-05 (P2s). Au produs dovezile din
`Documentation/evidence/p0` și `Documentation/evidence/p2` și rapoartele
[P0](../../../Documentation/P0-EXCLUSION-AUDIT.md) și
[P2](../../../Documentation/P2-DATA-PREPARATION.md). Nu fac parte din fluxul curent.
Sunt păstrate ca să se poată reproduce acele dovezi.

| Modul | Rol |
|---|---|
| `exclusion_audit` | diagnosticul excluderilor dintr-o publicație (P0) |
| `source_audit`, `p0_sources` | legătura fiecărei excluderi cu sursele păstrate (P0) |
| `p0_report` | raportul final P0 |
| `p2_comparison`, `p2_snapshot_comparison` | comparațiile înainte/după din P2 |
| `p2_period_audit`, `p2_remaining_audit` | auditul perioadelor și al blocajelor rămase |
| `input_review_audit` | selecția canonică pentru revizuirile individuale |

În pachet rămâne `quant_research.source_audit.query`, ajutorul SQL read-only folosit de
`publication_audit`, care e acoperit de freeze-ul modelului.

## Rulare

```powershell
$env:PYTHONPATH = 'research-engine/archive/p0_p2_audits'
.\research-engine\.venv\Scripts\python.exe -m quant_research_audits.p0_report --help
# testele (51) rulează și în CI:
.\research-engine\.venv\Scripts\python.exe -m pytest research-engine/archive/p0_p2_audits/tests
```

Comenzile din rapoartele vechi de forma `python -m quant_research.p0_sources ...` devin
`python -m quant_research_audits.p0_sources ...`, cu `PYTHONPATH` de mai sus.
Scripturile din `Documentation/evidence` importă modulele cu numele vechi. Nu au fost
modificate; `tests/conftest.py` arată alias-urile necesare pentru a le rula.
