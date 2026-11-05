"""Apply individually verified P2 corporate-action and identity reviews (2026-10-31).

Each provider issue is matched to the exact canonical issue revision in the DB and to a
retained original HTTP response (see source-files.json). The script asserts that the
quoted source passage is present in the retained bytes and that every provider term
listed for the case equals the expected value before appending an ACCEPT review.

Reviews never change prices, provider actions, symbol history or timestamps; the
server assigns recorded_at. Re-running is idempotent (same UUID, identical content).

    research-engine/.venv/Scripts/python.exe \
        Documentation/evidence/p2/closeout-final/reviews-20261031/review-cases.py [--apply]

Without --apply only the read-only checks run. The Scoring credential is read from the
running container environment in memory and never printed or persisted.
"""

import argparse
import hashlib
import html
import json
import re
import subprocess
import sys
from decimal import Decimal
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[5] / 'research-engine' / 'src'))
from quant_research.input_review import apply, validate  # noqa: E402

ROOT = Path(__file__).resolve().parent
RUN = 'f41d3a8d-10f6-4bf7-a606-33dc838fa7ff'
SCORE_DATE = '2026-10-30'
REVIEWER = 'claude-p2-primary-source-review-20261031'

# Each case: provider id prefix -> (expected provider terms, source key, required passage, note)
WINDOW = 'Ex-date precedes the first session of the 253-session price window ending 2026-10-30; '\
         'the action cannot change any adjusted close, history count or liquidity input used.'

def div(symbol, cusip, ex, rate, pay, source, passage, note=''):
    return dict(terms=dict(type='cash_dividends', symbol=symbol, cusip=cusip, ex_date=ex,
                           record_date=ex, rate=rate, payable_date=pay, special=False),
                source=source, passage=passage, note=note)

CASES = {
    'ACN': {'642bd24e': div('ACN', 'G1151C101', '2025-07-10', 1.11, '2025-08-15', 'ACN-8k-q3fy25',
            'quarterly cash dividend of $1.48 per share for shareholders of record at the close of business on July 10, 2025',
            'Provider rate 1.11 equals the declared USD 1.48 net of the 25% Irish dividend withholding '
            'tax (1.48 x 0.75); the gross amount is not rewritten. ' + WINDOW)},
    'ASML': {'d6df31b6': div('ASML', 'N07059210', '2025-07-29', 1.569767, '2025-08-06', 'ASML-6k-q2-2025',
             'An interim dividend of €1.60 per ordinary share will be made payable on August 6, 2025',
             'Provider rate is a USD amount derived from the EUR 1.60 interim dividend after foreign '
             'withholding; currency conversion is not re-derived here. ' + WINDOW)},
    'FCX': {'9190a42f': None, 'aa692c16': None},  # filled below (two provider rows, one declaration)
    'NI': {'2e263b6f': div('NI', '65473P105', '2025-07-31', 0.28, '2025-08-20', 'NI-issuer-dividend',
           'payable August 20, 2025, to stockholders of record at the close of business on July 31, 2025',
           'Under T+1 settlement the ex-date equals the record date; an older history table showing '
           'one day earlier does not change any input. ' + WINDOW)},
    'VLO': {'2ef9e794': div('VLO', '91913Y100', '2025-07-31', 1.13, '2025-09-02', 'VLO-8k-q2-2025',
            'payable on September 2, 2025 to holders of record at the close of business on July 31, 2025',
            'Under T+1 settlement the ex-date equals the record date. ' + WINDOW)},
}

FCX_PASSAGE = ('declared cash dividends totaling $0.15 per share on its common stock (including a $0.075 per '
               'share quarterly base cash dividend and a $0.075 per share quarterly variable, performance-based '
               'cash dividend), which will be paid on August 1, 2025, to shareholders of record as of July 15, 2025')

def fcx(rate):
    return div('FCX', '35671D857', '2025-07-15', rate, '2025-08-01', 'FCX-8k-q2-2025', FCX_PASSAGE,
               'One declaration of USD 0.15 = 0.075 base + 0.075 variable; the provider carries both the '
               'total and a USD 0.075 component on the same dates. Neither row is re-dated or rescaled. ' + WINDOW)

CASES['FCX'] = {}

DOC_DIVS = [  # record (= ex under T+1), payment, source
    ('2025-08-18', '2025-08-29', 'DOC-8k-2025q2', 'August 18, 2025 August 29, 2025 $0.10167'),
    ('2025-09-19', '2025-09-30', 'DOC-8k-2025q2', 'September 19, 2025 September 30, 2025 $0.10167'),
    ('2025-10-17', '2025-10-30', 'DOC-8k-2025q3', 'October 17, 2025 October 30, 2025 $0.10167'),
    ('2025-11-14', '2025-11-26', 'DOC-8k-2025q3', 'November 14, 2025 November 26, 2025 $0.10167'),
    ('2025-12-19', '2025-12-30', 'DOC-8k-2025q3', 'December 19, 2025 December 30, 2025 $0.10167'),
    ('2026-01-16', '2026-01-30', 'DOC-8k-2025q4', 'January 16, 2026 January 30, 2026 $0.10167'),
    ('2026-02-13', '2026-02-27', 'DOC-8k-2025q4', 'February 13, 2026 February 27, 2026 $0.10167'),
    ('2026-03-17', '2026-03-31', 'DOC-8k-2025q4', 'March 17, 2026 March 31, 2026 $0.10167'),
    ('2026-04-17', '2026-04-30', 'DOC-8k-2026q1', 'April 17, 2026 April 30, 2026 $0.10167'),
    ('2026-05-18', '2026-05-29', 'DOC-8k-2026q1', 'May 18, 2026 May 29, 2026 $0.10167'),
    ('2026-06-15', '2026-06-26', 'DOC-8k-2026q1', 'June 15, 2026 June 26, 2026 $0.10167'),
    ('2026-07-20', '2026-07-31', 'DOC-8k-2026q2', 'July 20, 2026 July 31, 2026 $0.10167'),
    ('2026-08-17', '2026-08-28', 'DOC-8k-2026q2', 'August 17, 2026 August 28, 2026 $0.10167'),
]
XOM_DIVS = [
    ('30231G102', '2025-08-15', 0.99, '2025-09-10', 'XOM-8k-2q25',
     'payable on September 10, 2025, to shareholders of record of Common Stock at the close of business on August 15, 2025'),
    ('30231G102', '2025-11-14', 1.03, '2025-12-10', 'XOM-8k-3q25',
     'payable on December 10, 2025, to shareholders of record of Common Stock at the close of business on November 14, 2025'),
    ('30231G102', '2026-02-12', 1.03, '2026-03-10', 'XOM-8k-4q25',
     'payable on March 10, 2026, to shareholders of record of Common Stock at the close of business on February 12, 2026'),
    ('30231G102', '2026-05-15', 1.03, '2026-06-10', 'XOM-8k-1q26',
     'payable on June 10, 2026, to shareholders of record of Common Stock at the close of business on May 15, 2026'),
    ('30233Q108', '2026-08-17', 1.03, '2026-09-10', 'XOM-8k-2q26',
     'payable on September 10, 2026, to shareholders of record of common stock at the close of business on August 17, 2026'),
]
OKE_DIVS = [
    ('2025-08-01', 1.03, '2025-08-14', 'OKE-8k-20250716', 'The dividend is payable Aug. 14, 2025, to shareholders of record at the close of business Aug. 1, 2025.'),
    ('2025-11-03', 1.03, '2025-11-14', 'OKE-dividend-20251015', 'The dividend is payable Nov. 14, 2025, to shareholders of record at the close of business Nov. 3, 2025.'),
    ('2026-02-02', 1.07, '2026-02-13', 'OKE-dividend-20260121', 'The dividend is payable Feb. 13, 2026, to shareholders of record at the close of business Feb. 2, 2026.'),
    ('2026-05-04', 1.07, '2026-05-15', 'OKE-dividend-20260423', 'The dividend is payable May 15, 2026 , to shareholders of record at the close of business May 4, 2026'),
    ('2026-08-03', 1.07, '2026-08-14', 'OKE-dividend-20260715', 'The dividend is payable Aug. 14, 2026 , to shareholders of record at the close of business Aug. 3, 2026'),
]
CCL_DIVS = [
    ('143658300', '2026-02-13', '2026-02-27', 'CCL-8k-q4-2025', 'record date of February 13, 2026 and a payment date of February 27, 2026'),
    ('G2004J103', '2026-05-18', '2026-05-29', 'CCL-dividend-20260508', 'record date for the quarterly dividend of May 18, 2026, and a payment date of May 29, 2026'),
    ('G2004J103', '2026-08-07', '2026-08-28', 'CCL-dividend-20260709', 'record date for the quarterly dividend of August 7, 2026, and a payment date of August 28, 2026'),
]
CONTINUITY = {
    'CCL': (dict(type='name_changes', old_symbol='CCL', new_symbol='CCL', old_cusip='143658300',
                 new_cusip='G2004J103', process_date='2026-05-07'), 'CCL-plc-form25',
            'Carnival Corporation ("old CCL") (CUSIP: 143658300) and Carnival plc ("CUK") will be converted into a single '
            'publicly traded corporate entity, to be renamed Carnival Corporation Ltd. (CUSIP: G2004J103)',
            'Redomiciliation of the same Carnival Corporation common stock (CIK 815097 unchanged) from Panama to '
            'Bermuda on May 7, 2026; each old share represents the same number of Carnival Corporation Ltd. common '
            'shares, still NYSE: CCL (8-K of May 7, 2026 retained as CCL-8k). Carnival plc ADS holders received new '
            'shares one-for-one; that exchange concerns CUK, not this instrument. No price series are joined.'),
    'OKE': (dict(type='name_changes', old_symbol='OKE', new_symbol='OKE', old_cusip='682680103',
                 new_cusip='30609A109', process_date='2026-09-11'), 'OKE-form25',
            'ONEOK, Inc. (Old, CUSIP: 682680103) receiving one share of ONEOK, Inc. (New, CUSIP: 30609A109), for each share',
            'Holding-company reorganization effective September 10, 2026 under OGCA 1081.G: one-for-one automatic '
            'conversion, uninterrupted NYSE: OKE trading, successor issuer under Rule 12g-3(a) (8-K12B retained as '
            'OKE-8k12b). Economic continuity of the instrument; no ratio or cash term affects adjusted prices.'),
    'XOM': (dict(type='name_changes', old_symbol='XOM', new_symbol='XOM', old_cusip='30231G102',
                 new_cusip='30233Q108', process_date='2026-07-02'), 'XOM-s8pos',
            "Upon effectiveness of the Redomiciliation, the Registrant’s CUSIP number relating to its common stock changed to 30233Q 108",
            'Redomiciliation New Jersey -> Texas through a new holding company (ExxonMobil Holdings Corp, CIK '
            '2115436); each share exchanged one-for-one and continues to trade on NYSE as XOM. Filings after the '
            'redomiciliation are made under the successor CIK; this review does not merge issuer filing histories.'),
    'DOC': (dict(type='name_changes', old_symbol='DOC', new_symbol='DOC', old_cusip='71943U104',
                 new_cusip='42250P103', process_date='2026-01-30'), 'DOC-physicians-13g',
            'PHYSICIANS REALTY TRUST (Name of Issuer) COMMON SHARES, PAR VALUE $0.01 PER SHARE (Title of Class of Securities) 71943U104 (CUSIP Number)',
            'CUSIP 71943U104 identifies Physicians Realty Trust (CIK 1574540), the former holder of the DOC ticker '
            'acquired by Healthpeak in March 2024. Healthpeak common stock remains 42250P103 throughout the window '
            '(monthly dividend releases retained as DOC-8k-*). The provider row is a stale cross-issuer symbol '
            'mapping, not an event of this instrument; no Physicians Realty history is attributed to Healthpeak.'),
    'BDX': (dict(type='spin_offs', source_symbol='BDX', source_cusip='075887109', new_symbol='WAT',
                 new_cusip='941848103', ex_date='2026-02-10', record_date='2026-02-05', source_rate=1),
            'BDX-form4-ratio', 'in exchange for 0.135343148384084 shares of Waters common stock',
            'SEC Form 4 (Augusta SpinCo, Feb 11, 2026) gives 0.135343148384084 Waters shares per distributed SpinCo '
            'share; BD distributed one SpinCo share per BD share (record date February 5, 2026), i.e. about 0.135 '
            'Waters shares per BD share. Provider new_rate 0.135343142914 differs by 4.0e-8 relative; with a WAT/BDX '
            'value ratio below 0.3 the resulting change of the backward adjustment factor is below 2e-8 relative, '
            'below the 1e-6 rounding of published metrics. The provider rate is not rewritten; the tolerance is explicit.'),
    'TRI': (dict(type='stock_and_cash_mergers', acquiree_symbol='TRI', acquirer_symbol='TRI', acquiree_cusip='884903808',
                 acquirer_cusip='884903881', acquiree_rate=1, acquirer_rate=0.98456, cash_rate=1.435518,
                 effective_date='2026-05-04'), 'TRI-6k',
            'The new CUSIP number for the post-consolidated common shares is 884903881',
            'Thomson Reuters return of capital (USD 1.435518 per share) and share consolidation (1 -> 0.984560) '
            'effective May 4, 2026, trading TSX/Nasdaq: TRI post-consolidation from the open of May 4 (6-K of May 1, '
            '2026). Cash, ratio, CUSIP and effective date match the provider. The payment-on-presentation date is '
            'not confirmed by this source and is not used by any adjusted-price input.'),
}


def text(key, index):
    entry = index[key]
    raw = (ROOT / entry['file']).read_bytes()
    if hashlib.sha256(raw).hexdigest() != entry['sourceSha256']:
        raise ValueError('Retained source changed: ' + key)
    if entry['file'].endswith('.pdf'):
        out = subprocess.run(['pdftotext', str(ROOT / entry['file']), '-'], capture_output=True, check=True).stdout
        body = out.decode('utf-8', 'replace')
    else:
        body = re.sub(r'<(script|style)[^>]*>.*?</\1>', ' ', raw.decode('utf-8', 'replace'), flags=re.S)
        body = html.unescape(re.sub(r'<[^>]+>', ' ', body))
    return re.sub(r'\s+', ' ', body.replace(' ', ' '))


def expected(audit_rows):
    """Expand the case table into per-issue expectations keyed by provider action id."""
    plans = {}
    by_symbol = {r['symbol']: r for r in audit_rows}
    for symbol, cases in CASES.items():
        for prefix, plan in cases.items():
            plans[(symbol, prefix)] = plan
    for issue in by_symbol['FCX']['issues']:
        rate = issue['provider_content']['rate']
        plans[('FCX', issue['affected_key'][:8])] = fcx(rate)
    for ex, pay, source, passage in DOC_DIVS:
        plan = div('DOC', '42250P103', ex, 0.10167, pay, source, passage,
                   'Healthpeak monthly common dividend; ex-date equals record date under T+1.'
                   + (' ' + WINDOW if ex < '2025-10-29' else ''))
        plans[('DOC', 'ex:' + ex)] = plan
    for cusip, ex, rate, pay, source, passage in XOM_DIVS:
        plans[('XOM', 'ex:' + ex)] = div('XOM', cusip, ex, rate, pay, source, passage,
            'ExxonMobil quarterly dividend; ex-date equals record date under T+1.'
            + (' ' + WINDOW if ex < '2025-10-29' else ''))
    for ex, rate, pay, source, passage in OKE_DIVS:
        plans[('OKE', 'ex:' + ex)] = div('OKE', '682680103', ex, rate, pay, source, passage,
            'ONEOK quarterly dividend; ex-date equals record date under T+1.'
            + (' ' + WINDOW if ex < '2025-10-29' else ''))
    for cusip, ex, pay, source, passage in CCL_DIVS:
        plans[('CCL', 'ex:' + ex)] = div('CCL', cusip, ex, 0.15, pay, source, passage,
            'Carnival quarterly dividend of USD 0.15; ex-date equals record date under T+1.')
    for symbol, (terms, source, passage, note) in CONTINUITY.items():
        plans[(symbol, 'type:' + terms['type'])] = dict(terms=terms, source=source, passage=passage, note=note)
    return plans


def plan_for(plans, symbol, issue):
    action = issue['provider_content']
    for key in ((symbol, issue['affected_key'][:8]), (symbol, 'ex:' + str(action.get('ex_date'))),
                (symbol, 'type:' + action['type'])):
        if key in plans and plans[key]['terms']['type'] == action['type']:
            return plans.pop(key)
    raise ValueError(f'No reviewed plan for {symbol} {issue["affected_key"]}')


def credential():
    process = subprocess.run(['docker', 'inspect', '--format', '{{json .Config.Env}}',
                              'quant-platform-scoring-service-1'], capture_output=True, text=True, check=True)
    settings = dict(item.split('=', 1) for item in json.loads(process.stdout) if '=' in item)
    return settings['SCORING_DB_PASSWORD']


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    index = {r['key']: r for r in json.loads((ROOT / 'source-files.json').read_text())}
    audit = json.loads((ROOT.parent / 'remaining-audit-20261031.json').read_text(encoding='utf-8'))
    if audit['runId'] != RUN:
        raise ValueError('Unexpected audit run')
    plans = expected(audit['rows'])
    manifests = []
    texts = {}
    with psycopg.connect(host='127.0.0.1', port=6543, dbname='quant_platform', user='quant_scoring',
                         password=credential(), connect_timeout=10) as connection:
        with connection.cursor() as cursor:
            cursor.execute('''SELECT min(session_date) FROM (SELECT session_date FROM reference.trading_sessions
                WHERE exchange_mic='XNYS' AND NOT holiday AND session_date<=%s ORDER BY session_date DESC LIMIT 253) s''',
                (SCORE_DATE,))
            window_start = cursor.fetchone()[0].isoformat()
            if window_start != '2025-10-29':
                raise ValueError('Unexpected window start ' + window_start)
            for row in audit['rows']:
                symbol = row['symbol']
                if symbol not in {'ACN', 'ASML', 'FCX', 'NI', 'VLO', 'DOC', 'XOM', 'OKE', 'CCL', 'BDX', 'TRI'}:
                    continue
                for issue in row['issues']:
                    plan = plan_for(plans, symbol, issue)
                    cursor.execute('''
                        SELECT q.evidence::text, iss.legal_name, iss.cik, a.provider_content, a.source_artifact_id::text
                        FROM operations.data_quality_issues q
                        JOIN reference.instruments i USING(instrument_id) JOIN reference.issuers iss USING(issuer_id)
                        JOIN market_data.corporate_actions a ON a.instrument_id=q.instrument_id
                          AND a.provider_action_id=q.affected_key AND a.revision_hash=q.evidence->>'revision'
                        WHERE q.data_quality_issue_id=%s AND q.instrument_id=%s
                          AND q.issue_type='CORPORATE_ACTION_REVIEW' AND q.resolved_at IS NULL''',
                        (issue['data_quality_issue_id'], row['instrument_id']))
                    matches = cursor.fetchall()
                    if len(matches) != 1:
                        raise ValueError(f'{symbol}: expected one exact canonical issue revision')
                    exact, issuer, cik, action, artifact = matches[0]
                    if action != issue['provider_content'] or json.loads(exact) != issue['evidence']:
                        raise ValueError(f'{symbol}: live action differs from audited action')
                    for key, value in plan['terms'].items():
                        actual = action.get(key)
                        same = (Decimal(str(actual)) == Decimal(str(value))) if isinstance(value, (int, float)) \
                            and not isinstance(value, bool) else actual == value
                        if not same:
                            raise ValueError(f'{symbol}: provider {key}={actual!r} != reviewed {value!r}')
                    boundary = action.get('ex_date') or action.get('effective_date') or action.get('process_date')
                    if 'Ex-date precedes' in plan['note'] and not boundary < window_start:
                        raise ValueError(f'{symbol}: window rationale used for in-window action')
                    source = index[plan['source']]
                    body = texts.setdefault(plan['source'], text(plan['source'], index))
                    if re.sub(r'\s+', ' ', plan['passage']) not in body:
                        raise ValueError(f'{symbol}: passage not found in {plan["source"]}')
                    manifest = dict(
                        reviewId=str(uuid5(NAMESPACE_URL, 'p2-20261031-review:' + issue['data_quality_issue_id'])),
                        kind='QUALITY_ISSUE', instrumentId=row['instrument_id'], factId=None,
                        issueId=issue['data_quality_issue_id'], decision='ACCEPT', reviewer=REVIEWER,
                        evidence=dict(scope='EXACT_PROVIDER_ACTION_ISSUE_ONLY', sourceUri=source['sourceUri'],
                                      sourceSha256=source['sourceSha256'], sourceObservedAt=source['observedAt'],
                                      captureFormat=source['captureFormat'], sourcePassage=plan['passage'],
                                      issueEvidence=exact, providerAction=action,
                                      providerSourceArtifactId=artifact, issuerName=issuer, issuerCik=cik,
                                      priceWindowStart=window_start, scoreDate=SCORE_DATE,
                                      rationale=plan['note']))
                    manifests.append((symbol, manifest, ROOT / source['file']))
            spcx = identity_case(cursor, index, texts)
            manifests.append(spcx)
        if plans:
            raise ValueError('Unused reviewed plans: ' + ', '.join(map(str, plans)))
        report = []
        for symbol, manifest, evidence_file in manifests:
            validate(manifest, evidence_file)
            path = ROOT / 'manifests' / f'review-{symbol}-{manifest["issueId"][:8]}.json'
            path.parent.mkdir(exist_ok=True)
            if path.exists():
                previous = json.loads(path.read_text(encoding='utf-8'))
                if previous != manifest:
                    raise ValueError('Existing manifest differs: ' + path.name)
            else:
                path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
            entry = dict(symbol=symbol, issueId=manifest['issueId'], reviewId=manifest['reviewId'],
                         manifest=str(path.relative_to(ROOT)), applied=False)
            if args.apply:
                recorded = apply(connection, manifest)
                if apply(connection, manifest) != recorded:
                    raise ValueError('Idempotency mismatch')
                entry.update(applied=True, recordedAt=recorded, idempotent=True)
            report.append(entry)
    summary = dict(runAudited=RUN, reviews=report, count=len(report),
                   instruments=sorted({r['symbol'] for r in report}), applied=args.apply,
                   policy='INDIVIDUAL_PRIMARY_SOURCE; SERVER_RECORDED_AT; NO_PRICE_ACTION_OR_TIMESTAMP_CHANGES')
    if args.apply:
        (ROOT / 'reviews-applied.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps({k: v for k, v in summary.items() if k != 'reviews'}, indent=2))


def identity_case(cursor, index, texts):
    cursor.execute('''SELECT q.data_quality_issue_id::text, q.instrument_id::text, q.evidence::text,
        iss.cik, iss.legal_name, i.valid_from::text
        FROM operations.data_quality_issues q JOIN reference.instruments i USING(instrument_id)
        JOIN reference.issuers iss USING(issuer_id)
        WHERE q.issue_type='SECURITY_IDENTITY_REVIEW' AND q.affected_key='SPCX' AND q.resolved_at IS NULL''')
    rows = cursor.fetchall()
    if len(rows) != 1:
        raise ValueError('SPCX: expected one open identity issue')
    issue, instrument, exact, cik, issuer, valid_from = rows[0]
    if cik != '0001181412' or valid_from != '2026-08-27':
        raise ValueError('SPCX: issuer or instrument validity differs from review')
    fund = 'Effective April 7, 2026, the ticker symbol for The SPAC and New Issue ETF (the “Fund”) will change from SPCX to SPCK.'
    listing = 'Class A common stock, par value $0.001 per share SPCX The Nasdaq Stock Market LLC'
    if fund not in texts.setdefault('SPCK-fund-497', text('SPCK-fund-497', index)):
        raise ValueError('SPCX: fund rename passage missing')
    if listing not in texts.setdefault('SPCX-closing-8k', text('SPCX-closing-8k', index)):
        raise ValueError('SPCX: listing passage missing')
    source = index['SPCX-closing-8k']
    manifest = dict(
        reviewId=str(uuid5(NAMESPACE_URL, 'p2-20261031-review:' + issue)), kind='QUALITY_ISSUE',
        instrumentId=instrument, factId=None, issueId=issue, decision='ACCEPT', reviewer=REVIEWER,
        evidence=dict(scope='EXACT_SECURITY_IDENTITY_ISSUE_ONLY', sourceUri=source['sourceUri'],
                      sourceSha256=source['sourceSha256'], sourceObservedAt=source['observedAt'],
                      captureFormat=source['captureFormat'], sourcePassage=listing,
                      supportingSources=[dict(key='SPCK-fund-497', sourceUri=index['SPCK-fund-497']['sourceUri'],
                                              sourceSha256=index['SPCK-fund-497']['sourceSha256'], passage=fund)],
                      issueEvidence=exact, issuerName=issuer, issuerCik=cik, instrumentValidFrom=valid_from,
                      rationale='The SPAC and New Issue ETF changed its ticker from SPCX to SPCK effective April 7, '
                                '2026 (Collaborative Investment Series Trust 497). Space Exploration Technologies Corp '
                                '(CIK 1181412) registers its Class A common stock as SPCX on Nasdaq (8-K, June 2026). '
                                'The instrument is valid from 2026-08-27, after both events, so preparation already '
                                'excludes every fund-era bar; fund history and fund corporate actions are not '
                                'attributed to SpaceX. Momentum history requirements are not waived.'))
    return 'SPCX', manifest, ROOT / source['file']


if __name__ == '__main__':
    main()
