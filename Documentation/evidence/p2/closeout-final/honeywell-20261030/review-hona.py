"""Apply individually checked symbol transitions, bound to retained original sources."""

import hashlib
import json
import subprocess
from pathlib import Path
from uuid import NAMESPACE_URL, UUID, uuid5

import psycopg
from quant_research.input_review import apply, validate

ROOT = Path(__file__).resolve().parent
CHECKED = {
    'HONA': ('2089271', 'HONAV', '2026-06-29', '43849R105', 'HONA-exchange'),
}
RATIONALES = {
    'HONA': 'The finalized Nasdaq corporate action notice identifies Honeywell Aerospace '
            'CUSIP 43849R105, when-issued HONAV trading from June 15, 2026 and the transition '
            'to regular-way HONA on June 29. The Honeywell completion filing confirms the '
            'separation on June 29. Review only this same-security symbol transition. '
            'Do not join parent HON prices, manufacture pre-listing observations, or waive '
            'history, price, identity or metric-coverage checks.',
}



def main():
    sources = {r['symbol']: r for r in json.loads((ROOT / 'source-files.json').read_text())}
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
        for symbol, (cik, old, date, cusip, source_key) in CHECKED.items():
            source = sources[source_key]
            evidence_file = ROOT / 'source-files.json'
            for retained in sources.values():
                if hashlib.sha256((ROOT / retained['file']).read_bytes()).hexdigest() != retained['sourceSha256']:
                    raise ValueError('Retained source changed')
            row = rows[symbol]
            if len(row['issues']) != 1:
                raise ValueError('Expected only the individually reviewed symbol transition')
            issue = row['issues'][0]
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
            if (str(actual_cik).lstrip('0') != cik or action != issue['provider_content']
                    or action['type'] != 'name_changes' or action['old_symbol'] != old
                    or action['new_symbol'] != symbol or action['process_date'] != date
                    or action['old_cusip'] != cusip or action['new_cusip'] != cusip
                    or json.loads(exact) != issue['evidence']):
                raise ValueError('Canonical action does not match the individual source review')
            manifest = {
                'reviewId': str(uuid5(NAMESPACE_URL, 'p2-symbol-transition:' + str(UUID(issue['data_quality_issue_id'])))),
                'kind': 'QUALITY_ISSUE', 'instrumentId': row['instrument_id'], 'factId': None,
                'issueId': issue['data_quality_issue_id'], 'decision': 'ACCEPT',
                'reviewer': 'codex-p2-primary-source-identity-review',
                'evidence': {
                    'scope': 'EXACT_SYMBOL_TRANSITION_ISSUE_ONLY', 'sourceUri': source['sourceUri'],
                    'sourceSha256': hashlib.sha256(evidence_file.read_bytes()).hexdigest(), 'sourceObservedAt': source['observedAt'],
                    'captureFormat': 'HASHED_MANIFEST_OF_ORIGINAL_HTTP_RESPONSES', 'issueEvidence': exact,
                    'providerAction': action, 'providerSourceArtifactId': artifact,
                    'issuerName': issuer, 'issuerCik': actual_cik,
                    'rationale': RATIONALES[symbol],
                },
            }
            validate(manifest, evidence_file)
            path = ROOT / f'review-{symbol}.json'
            if path.exists():
                if json.loads(path.read_text()) != manifest:
                    raise ValueError('Existing manifest differs')
            else:
                with path.open('x', encoding='utf-8') as stream:
                    stream.write(json.dumps(manifest, indent=2) + '\n')
            recorded = apply(connection, manifest)
            if apply(connection, manifest) != recorded:
                raise ValueError('Idempotency mismatch')
            report.append({'symbol': symbol, 'reviewId': manifest['reviewId'],
                           'recordedAt': recorded, 'applied': True, 'idempotent': True})
    with (ROOT / 'reviews-applied.json').open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
