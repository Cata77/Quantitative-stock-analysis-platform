"""Apply individually checked symbol transitions, bound to retained original sources."""

import hashlib
import json
import subprocess
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid5

import psycopg
from quant_research.input_review import apply, validate

ROOT = Path(__file__).resolve().parent


def main():
    sources = {r['name']: r for r in json.loads((ROOT / 'source-files.json').read_text())}
    audit = json.loads(Path('build/p2-final-20261030/remaining-audit.json').read_text())
    rows = {r['symbol']: r for r in audit['rows']}
    # Authorized local credential use: never print, persist, or pass it as a CLI argument.
    process = subprocess.run(
        ['docker', 'inspect', '--format', '{{json .Config.Env}}', 'quant-platform-scoring-service-1'],
        capture_output=True, text=True, check=True,
    )
    settings = dict(item.split('=', 1) for item in json.loads(process.stdout) if '=' in item)
    report = []
    with psycopg.connect(host='127.0.0.1', port=6543, dbname='quant_platform',
                        user='quant_scoring', password=settings['SCORING_DB_PASSWORD'],
                        connect_timeout=10) as connection, connection.transaction(), connection.cursor() as cursor:
        row = rows['BNY']
        if len(row['issues']) != 10:
            raise ValueError('Expected exactly the ten inventoried BNY issues')
        for issue in row['issues']:
            action = issue['provider_content']
            if action['type'] == 'name_changes':
                if (action['old_symbol'], action['new_symbol'], action['old_cusip'],
                    action['new_cusip'], action['process_date']) != (
                        'BK', 'BNY', '064058100', '064058100', '2026-05-21'):
                    raise ValueError('Unexpected bank rename')
                source_key = 'bank-rename'
                scope = 'EXACT_BANK_SYMBOL_CHANGE'
                rationale = ('Issuer confirms BK to BNY effective May 21, 2026 without a change '
                             'in legal name, capital structure, CUSIPs or holder rights. '
                             'Accept only this exact bank rename issue.')
            elif action.get('cusip', action.get('acquiree_cusip')) == '09248L106':
                if action['type'] not in {'cash_dividends', 'stock_mergers'}:
                    raise ValueError('Unexpected fund event type')
                if action.get('symbol', action.get('acquiree_symbol')) != 'BNY':
                    raise ValueError('Unexpected fund symbol')
                if action['process_date'] >= '2026-05-21':
                    raise ValueError('Fund event overlaps the bank symbol period')
                source_key = 'fund-identity'
                scope = 'MISATTRIBUTED_OTHER_ISSUER_EVENT_NOT_BANK_ACTION'
                rationale = ('The retained SEC Schedule 13D identifies CUSIP 09248L106 as '
                             'BlackRock New York Municipal Income Trust, a different issuer from '
                             'Bank of New York Mellon, CUSIP 064058100. This exact event predates '
                             'the bank ticker change. Accept resolution of the misattribution '
                             'issue, not this event as a bank dividend or merger. Preserve the '
                             'original event for audit; do not merge fund and bank prices.')
            else:
                if (action['type'], action['symbol'], action['cusip'], action['rate'],
                    action['record_date'], action['ex_date'], action['payable_date']) != (
                        'cash_dividends', 'BK', '064058100', .53, '2025-07-25',
                        '2025-07-25', '2025-08-07'):
                    raise ValueError('Unexpected bank dividend')
                source_key = 'bank-dividend'
                scope = 'EXACT_BANK_COMMON_CASH_DIVIDEND'
                rationale = ('Bank issuer announcement confirms the common dividend of USD 0.53, '
                             'record July 25, 2025 and payment August 7, 2025 for the BK common '
                             'stock. Provider dates and amount agree. Accept only this dividend '
                             'issue, not any preferred dividend or fund event.')
            source = sources[source_key]
            evidence_file = ROOT / 'source-files.json'
            for retained in sources.values():
                if hashlib.sha256((ROOT / retained['file']).read_bytes()).hexdigest() != retained['sourceSha256']:
                    raise ValueError('Retained source changed')
            cursor.execute('''
                SELECT q.evidence::text, iss.legal_name, iss.cik, a.provider_content,
                       a.source_artifact_id::text
                FROM operations.data_quality_issues q
                JOIN reference.instruments i USING(instrument_id)
                JOIN reference.issuers iss USING(issuer_id)
                JOIN market_data.corporate_actions a ON a.instrument_id=q.instrument_id
                  AND a.provider_action_id=q.affected_key
                  AND a.revision_hash=q.evidence->>'revision'
                WHERE q.data_quality_issue_id=%s AND q.instrument_id=%s
                  AND q.issue_type='CORPORATE_ACTION_REVIEW'
            ''', (issue['data_quality_issue_id'], row['instrument_id']))
            matches = cursor.fetchall()
            if len(matches) != 1:
                raise ValueError('Expected one exact canonical issue revision')
            exact, issuer, actual_cik, action, artifact = matches[0]
            if (str(actual_cik).lstrip('0') != '1390777' or action != issue['provider_content']
                    or json.loads(exact) != issue['evidence']):
                raise ValueError('Bank issuer or exact action evidence changed')
            manifest = {
                'reviewId': str(uuid5(NAMESPACE_URL, 'p2-bny-identity:' + str(UUID(issue['data_quality_issue_id'])))),
                'kind': 'QUALITY_ISSUE', 'instrumentId': row['instrument_id'], 'factId': None,
                'issueId': issue['data_quality_issue_id'], 'decision': 'ACCEPT',
                'reviewer': 'codex-p2-primary-source-identity-review',
                'evidence': {
                    'scope': scope, 'sourceUri': source['sourceUri'],
                    'sourceSha256': hashlib.sha256(evidence_file.read_bytes()).hexdigest(), 'sourceObservedAt': source['observedAt'],
                    'captureFormat': 'HASHED_MANIFEST_OF_ORIGINAL_HTTP_RESPONSES', 'issueEvidence': exact,
                    'providerAction': action, 'providerSourceArtifactId': artifact,
                    'issuerName': issuer, 'issuerCik': actual_cik,
                    'rationale': rationale,
                },
            }
            validate(manifest, evidence_file)
            path = ROOT / f"review-{issue['data_quality_issue_id']}.json"
            if path.exists():
                if json.loads(path.read_text()) != manifest:
                    raise ValueError('Existing manifest differs')
            else:
                with path.open('x', encoding='utf-8') as stream:
                    stream.write(json.dumps(manifest, indent=2) + '\n')
            recorded = apply(connection, manifest)
            if apply(connection, manifest) != recorded:
                raise ValueError('Idempotency mismatch')
            report.append({'symbol': 'BNY', 'issueId': issue['data_quality_issue_id'], 'scope': scope, 'reviewId': manifest['reviewId'],
                           'recordedAt': recorded, 'applied': True, 'idempotent': True})
    with (ROOT / 'reviews-applied.json').open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
