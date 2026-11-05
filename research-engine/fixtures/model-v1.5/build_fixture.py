"""Rebuild the 1.5.0 golden fixture from the frozen 1.4.0 prepared rows.

Each row receives its own issuer (the 1.4 fixture reuses one issuer id), then a few
rows exercise the 1.5 security policy: two secondary share classes, a depositary
receipt, an explicit delisting and the legacy undifferentiated security reason.
"""
import copy
import json
import shutil
import sys
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'research-engine/src'))
from quant_research.model.flexible import score  # noqa: E402

prepared = json.loads((ROOT / 'research-engine/fixtures/model-v1.4/prepared.json').read_text())
rows = prepared['rows']
for i, row in enumerate(rows):
    row['issuer_id'] = str(uuid5(NAMESPACE_URL, f'model-v1.5-fixture-issuer:{i}'))
for secondary, primary in ((7, 6), (31, 30)):
    rows[secondary].update(issuer_id=rows[primary]['issuer_id'], primary_class=False,
                           blocking_reasons=['NON_PRIMARY_SHARE_CLASS'])
rows[8].update(profile='UNKNOWN', profile_supported=False, peer_group=None,
               blocking_reasons=['INACTIVE_OR_UNSUPPORTED_SECURITY:DEPOSITARY_RECEIPT',
                                 'MODEL_NOT_SUPPORTED:NO_MAPPED_FACTS'])
rows[9]['blocking_reasons'] = ['INACTIVE_OR_UNSUPPORTED_SECURITY:DELISTED:2026-09-01']
rows[10]['blocking_reasons'] = ['INACTIVE_OR_UNSUPPORTED_SECURITY']
out = Path(__file__).resolve().parent
(out / 'prepared.json').write_text(json.dumps(prepared, indent=2) + '\n')
expected = score(copy.deepcopy(rows), as_of=prepared['as_of'], version='1.5.0')
(out / 'expected.json').write_text(json.dumps(expected, indent=2) + '\n')
target = ROOT / 'scoring-service/src/main/resources/model/flexible_v15'
for name in ('prepared.json', 'expected.json'):
    shutil.copyfile(out / name, target / name)
