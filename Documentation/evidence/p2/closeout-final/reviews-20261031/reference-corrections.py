"""Dated reference corrections for WBD (delisted) and PSKY -> SKYD (2026-10-31).

Both corrections are bound to retained original HTTP responses (source-files.json) and
run as the reference-owning role quant_ingestion. The script is idempotent: a second
run verifies the state and changes nothing. It never deletes rows, never touches prices
and records the real observation time (available_at = observed_at = now).

    research-engine/.venv/Scripts/python.exe \
        Documentation/evidence/p2/closeout-final/reviews-20261031/reference-corrections.py [--apply]

Limitation: reference.instruments.valid_to and the closing effective_to of the PSKY row
have no availability timestamp of their own. Already published runs are reproduced from
their stored input documents and are unaffected; re-querying an earlier cutoff from the
live tables after this correction reflects the corrected lifecycle.
"""

import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
WBD = '77f24b58-a12d-4483-b86f-9d695eb4324a'
PSKY = '8e26439e-70a8-4c8f-a52d-efdc67e4567e'
SOURCE = 'P2_PRIMARY_SOURCE_REVIEW_20261031'
PASSAGES = {
    'WBD-nasdaq-eca2026-710': 'Symbol: WBD Last Trading Date: October 5, 2026 Marketplace Effective Date for Suspension: October 7, 2026',
    'PSKY-skydance-8k': 'transferred the listing of the Class B Common Stock from The Nasdaq Stock Market LLC to the New York '
                        'Stock Exchange and changed the ticker symbol for the Class B Common Stock from “PSKY” to “SKYD”',
}


def psql(sql):
    result = subprocess.run(
        ['docker', 'exec', '-i', 'quant-platform-timescaledb-1', 'psql', '-U', 'postgres', '-d', 'quant_platform',
         '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=sql, text=True, encoding='utf-8',
        capture_output=True, check=True)
    return result.stdout.strip()


def state():
    return json.loads(psql(f"""BEGIN READ ONLY; SELECT jsonb_build_object(
        'wbd',(SELECT to_jsonb(i) - 'created_at' FROM reference.instruments i WHERE instrument_id='{WBD}'),
        'pskySymbols',(SELECT jsonb_agg(to_jsonb(s) - 'created_at' ORDER BY effective_from)
                       FROM reference.instrument_symbols s WHERE instrument_id='{PSKY}'),
        'wbdSymbols',(SELECT jsonb_agg(to_jsonb(s) - 'created_at' ORDER BY effective_from)
                      FROM reference.instrument_symbols s WHERE instrument_id='{WBD}'),
        'xnys',(SELECT count(*) FROM reference.exchanges WHERE exchange_mic='XNYS')); COMMIT;"""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    index = {r['key']: r for r in json.loads((ROOT / 'source-files.json').read_text())}
    import html
    import re
    for key, passage in PASSAGES.items():
        raw = (ROOT / index[key]['file']).read_bytes()
        if hashlib.sha256(raw).hexdigest() != index[key]['sourceSha256']:
            raise ValueError('Retained source changed: ' + key)
        text = re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]+>', ' ', raw.decode('utf-8', 'replace'))))
        if passage not in text:
            raise ValueError('Passage missing from ' + key)
    before = state()
    if before['xnys'] != 1:
        raise ValueError('XNYS reference exchange missing')
    sql = f"""BEGIN; SET LOCAL ROLE quant_ingestion;
    -- WBD: last trade 2026-10-05, merger closed before the open of 2026-10-06; valid_to is exclusive.
    UPDATE reference.instruments SET valid_to='2026-10-06', active=false, updated_at=clock_timestamp()
      WHERE instrument_id='{WBD}' AND valid_to IS NULL;
    -- PSKY Class B: Nasdaq PSKY until 2026-10-05, NYSE SKYD from 2026-10-06 (same instrument and CIK).
    UPDATE reference.instrument_symbols SET effective_to='2026-10-06'
      WHERE instrument_id='{PSKY}' AND symbol='PSKY' AND exchange_mic='XNAS' AND effective_to IS NULL;
    INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
      SELECT '{PSKY}','SKYD','XNYS','2026-10-06','{SOURCE}',clock_timestamp(),clock_timestamp()
      WHERE NOT EXISTS (SELECT 1 FROM reference.instrument_symbols WHERE instrument_id='{PSKY}' AND symbol='SKYD');
    UPDATE reference.instruments SET primary_exchange_mic='XNYS', updated_at=clock_timestamp()
      WHERE instrument_id='{PSKY}' AND primary_exchange_mic='XNAS';
    COMMIT;"""
    if args.apply:
        psql(sql)
    after = state()
    current = [s for s in after['pskySymbols'] if s['effective_to'] is None]
    report = dict(applied=args.apply, before=before, after=after,
                  sources={k: dict(sourceUri=index[k]['sourceUri'], sourceSha256=index[k]['sourceSha256'],
                                   passage=p) for k, p in PASSAGES.items()},
                  verified=dict(wbdValidTo=after['wbd']['valid_to'], wbdActive=after['wbd']['active'],
                                pskyCurrent=current))
    if args.apply:
        if after['wbd']['valid_to'] != '2026-10-06' or len(current) != 1 or current[0]['symbol'] != 'SKYD':
            raise ValueError('Correction not in expected state')
        target = ROOT / 'reference-corrections-applied.json'
        if not target.exists():
            target.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(json.dumps(report['verified'], indent=2))


if __name__ == '__main__':
    main()
