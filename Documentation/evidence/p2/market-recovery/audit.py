import json
import subprocess
from pathlib import Path

sql = """
BEGIN READ ONLY;
SET LOCAL statement_timeout='60s';
WITH runs AS (
 SELECT r.*,j.configuration FROM operations.ingestion_runs r
 JOIN operations.job_definitions j USING(job_definition_id)
 WHERE j.configuration->>'symbolAsOf'='2026-10-23'
 AND j.configuration->>'adjustment'='RAW'
 AND r.window_start BETWEEN '2026-10-01' AND '2026-10-22'
), items AS (
 SELECT i.* FROM operations.ingestion_run_items i JOIN runs r USING(ingestion_run_id)
), bars AS (
 SELECT b.* FROM market_data.daily_bar_observations b
 WHERE b.session_date BETWEEN '2026-10-01' AND '2026-10-22'
 AND b.adjustment_mode='RAW' AND b.feed='sip' AND b.quality_state='VALID'
 AND b.dataset_id IN (SELECT dataset_id FROM runs)
)
SELECT jsonb_build_object(
 'observedAt',clock_timestamp(), 'scope','RAW_GAP_RECOVERY_NOT_A_SCORING_RUN',
 'runs',(SELECT jsonb_agg(jsonb_build_object('runId',ingestion_run_id,'date',window_start,
 'status',status,'expected',expected_count,'accepted',accepted_count,'configuration',configuration)
 ORDER BY window_start) FROM runs),
 'itemStates',(SELECT jsonb_object_agg(status,n) FROM (SELECT status,count(*) n FROM items GROUP BY status) s),
 'failures',(SELECT jsonb_agg(jsonb_build_object('instrumentId',i.instrument_id,
 'date',r.window_start,'status',i.status,'attempts',i.attempt_count,'failure',i.failure)
 ORDER BY r.window_start,i.instrument_id) FROM items i JOIN runs r USING(ingestion_run_id)
 WHERE i.failure IS NOT NULL),
 'barsBySession',(SELECT jsonb_agg(s ORDER BY session_date) FROM (
 SELECT session_date,count(*) observations,count(DISTINCT instrument_id) instruments,
 min(observed_at) first_observed,max(observed_at) last_observed FROM bars GROUP BY session_date) s),
 'missingCanonicalItems',(SELECT count(*) FROM items i JOIN runs r USING(ingestion_run_id)
 WHERE NOT EXISTS (SELECT 1 FROM bars b WHERE b.instrument_id=i.instrument_id AND b.session_date=r.window_start)),
 'duplicateInstrumentSessions',(SELECT count(*) FROM (SELECT instrument_id,session_date FROM bars
 GROUP BY instrument_id,session_date HAVING count(*)>1) s),
 'retrodatedObservations',(SELECT count(*) FROM bars WHERE observed_at<'2026-10-23T00:00:00Z'),
 'adjustedHistoryCurrentVintage',(SELECT count(*) FROM market_data.daily_bar_observations
 WHERE adjustment_mode='SPLIT_DIVIDEND' AND adjustment_as_of='2026-10-23')
);
COMMIT;
"""
result = subprocess.run(['docker','exec','-i','quant-platform-timescaledb-1','psql',
 '-U','postgres','-d','quant_platform','-X','-qAt','-v','ON_ERROR_STOP=1'],
 input=sql,text=True,capture_output=True,check=True)
report=json.loads(result.stdout)
output=Path('build/p2-market-20261023/recovery-audit.json')
output.parent.mkdir(parents=True,exist_ok=True)
output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ('runs','barsBySession','failures')},indent=2))
