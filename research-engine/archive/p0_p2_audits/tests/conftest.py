"""Make the archived package and the shared test helpers (research-engine/tests) importable.

Historical evidence scripts import the tools under their former names (quant_research.<module>);
aliases keep them runnable without editing the evidence.
"""

import importlib
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parents[2] / "tests")]

import quant_research  # noqa: E402

ARCHIVED = ["p0_report", "p0_sources", "exclusion_audit", "source_audit", "p2_comparison",
            "p2_period_audit", "p2_remaining_audit", "p2_snapshot_comparison", "input_review_audit"]
for name in ARCHIVED:
    if name == "source_audit":
        continue  # quant_research.source_audit still exists (query helper of publication_audit)
    module = importlib.import_module(f"quant_research_audits.{name}")
    sys.modules[f"quant_research.{name}"] = module
    setattr(quant_research, name, module)
