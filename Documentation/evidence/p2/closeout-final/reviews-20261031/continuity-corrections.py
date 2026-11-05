"""Extend instrument validity for securities whose continuity was proven on 2026-10-31.

XOM, DOC, CCL and OKE were imported from the 2026-08-27 snapshot with validity starting
on that date because the provider showed identifier changes. The primary-source reviews
in manifests/ (redomiciliations, a holding-company reorganization and a stale
cross-issuer mapping) establish that each is the same listed common stock throughout the
price window. Like the 509 instruments reconciled on 2026-09-26, validity starts on
2025-08-01, and a dated symbol row covers 2025-08-01..2026-08-27. Authorized by the user
on 2026-10-31.

Run as quant_ingestion; idempotent; never deletes rows or changes prices.

    research-engine/.venv/Scripts/python.exe \
        Documentation/evidence/p2/closeout-final/reviews-20261031/continuity-corrections.py [--apply]
"""

import argparse
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCE = 'P2_PRIMARY_SOURCE_REVIEW_20261031'
INSTRUMENTS = {
    'XOM': '88873835-cbb0-4542-8e98-f1f9228b6a27',
    'DOC': 'c7311e9d-497f-42c6-b5d8-a1acd3baad43',
    'CCL': 'afca0986-9479-4431-8da5-d94010011514',
    'OKE': 'ee986a8e-e5b6-4f24-bf24-48af043a8c26',
}


def psql(sql):
    return subprocess.run(['docker', 'exec', '-i', 'quant-platform-timescaledb-1', 'psql', '-U', 'postgres',
                           '-d', 'quant_platform', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'], input=sql,
                          text=True, encoding='utf-8', capture_output=True, check=True).stdout.strip()


def state():
    ids = ','.join(f"'{v}'" for v in INSTRUMENTS.values())
    return json.loads(psql(f"""BEGIN READ ONLY; SELECT jsonb_object_agg(i.instrument_id, jsonb_build_object(
        'validFrom', i.valid_from, 'symbols', (SELECT jsonb_agg(jsonb_build_object('symbol',s.symbol,
          'mic',s.exchange_mic,'from',s.effective_from,'to',s.effective_to,'source',s.source,
          'availableAt',s.available_at) ORDER BY s.effective_from)
          FROM reference.instrument_symbols s WHERE s.instrument_id=i.instrument_id)))
        FROM reference.instruments i WHERE i.instrument_id IN ({ids}); COMMIT;"""))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    applied = json.loads((ROOT / 'reviews-applied.json').read_text())
    reviews = {}
    for entry in applied['reviews']:
        if entry['symbol'] in INSTRUMENTS and entry['applied']:
            reviews.setdefault(entry['symbol'], []).append(entry['reviewId'])
    if set(reviews) != set(INSTRUMENTS):
        raise ValueError('Continuity requires applied primary-source reviews for every instrument')
    before = state()
    statements = ['BEGIN; SET LOCAL ROLE quant_ingestion;']
    for symbol, instrument in INSTRUMENTS.items():
        current = [s for s in before[instrument]['symbols'] if s['to'] is None]
        if len(current) != 1 or current[0]['symbol'] != symbol:
            raise ValueError(symbol + ': unexpected current symbol state')
        mic = current[0]['mic']
        statements.append(f"""
        INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,effective_to,
            source,available_at,observed_at)
        SELECT '{instrument}','{symbol}','{mic}','2025-08-01','2026-08-27','{SOURCE}',
            clock_timestamp(),clock_timestamp()
        WHERE NOT EXISTS (SELECT 1 FROM reference.instrument_symbols WHERE instrument_id='{instrument}'
            AND symbol='{symbol}' AND effective_from='2025-08-01');
        UPDATE reference.instruments SET valid_from='2025-08-01', updated_at=clock_timestamp()
            WHERE instrument_id='{instrument}' AND valid_from='2026-08-27';""")
    statements.append('COMMIT;')
    if args.apply:
        psql('\n'.join(statements))
    after = state()
    report = dict(applied=args.apply, authorizedBy='user, 2026-10-31', reviews=reviews,
                  before=before, after=after)
    if args.apply:
        for symbol, instrument in INSTRUMENTS.items():
            if after[instrument]['validFrom'] != '2025-08-01':
                raise ValueError(symbol + ': correction not applied')
        target = ROOT / 'continuity-corrections-applied.json'
        if not target.exists():
            target.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({s: after[i]['validFrom'] for s, i in INSTRUMENTS.items()}))


if __name__ == '__main__':
    main()
