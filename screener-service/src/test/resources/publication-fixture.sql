
INSERT INTO operations.data_providers(provider_id,code,name,license_notes)
VALUES('10000000-0000-0000-0000-000000000001','screener-fixture','Fixture','Fixture');
INSERT INTO operations.datasets(dataset_id,provider_id,code,version,license_notes)
VALUES('10000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','fixture','1','Fixture');
INSERT INTO operations.source_artifacts(source_artifact_id,dataset_id,request_key,source_uri,retrieved_at,content_hash,media_type,parser_version,inline_content)
VALUES('10000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000002','fixture','test://fixture','2026-06-01',repeat('a',64),'application/json','test','{}');
INSERT INTO reference.issuers(issuer_id,legal_name,domicile,sic)
SELECT ('20000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,'Fixture company '||n,'US','3571' FROM generate_series(1,3) n;
INSERT INTO reference.instruments(instrument_id,issuer_id,security_type,currency,primary_exchange_mic,valid_from)
SELECT ('30000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,('20000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,
    'COMMON_STOCK','USD','XNAS','2026-01-01' FROM generate_series(1,3) n;
INSERT INTO reference.instrument_symbols(instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
SELECT instrument_id,'NEW'||right(instrument_id::text,1),'XNAS','2026-08-01','test','2026-08-01','2026-08-01' FROM reference.instruments;
INSERT INTO reference.universe_snapshots(universe_snapshot_id,universe_id,effective_date,observed_at,source,source_checksum,import_mode,completeness_status,expected_member_count,imported_member_count)
SELECT ('40000000-0000-0000-0000-00000000000'||CASE code WHEN 'SP500' THEN '1' ELSE '2' END)::uuid,
    universe_id,'2026-06-01','2026-06-01','test',repeat('a',64),'CURRENT_SNAPSHOT_FORWARD','COMPLETE',
    CASE code WHEN 'SP500' THEN 3 ELSE 1 END,CASE code WHEN 'SP500' THEN 3 ELSE 1 END FROM reference.universes;
INSERT INTO reference.universe_memberships(universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic)
SELECT '40000000-0000-0000-0000-000000000001',instrument_id,'OLD'||right(instrument_id::text,1),'XNAS' FROM reference.instruments;
INSERT INTO reference.universe_memberships(universe_snapshot_id,instrument_id,source_symbol,source_exchange_mic)
VALUES('40000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000002','OLD2','XNAS');
INSERT INTO research.model_parity_certifications(certification_id,model_version_id,manifest_sha256,implementation_version,absolute_tolerance)
SELECT repeat('a',64),model_version_id,manifest_sha256,'fixture',0.000000001 FROM research.model_versions WHERE semantic_version='1.0.0';
INSERT INTO research.scoring_runs(score_run_id,logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
    sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request)
SELECT ('50000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,lpad(n::text,64,'0'),
    CASE n WHEN 1 THEN '2026-06-30'::date WHEN 2 THEN '2026-07-31'::date ELSE '2026-08-31'::date END,
    '2026-08-31T20:00Z','2026-08-31T20:00Z','2026-09-01T13:30Z',
    '40000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000002',
    model_version_id,repeat('a',64),'primary','prepared-canonical-v1',CASE n WHEN 2 THEN
        -- P2b financials: run 2 records its adjusted price dataset and vintage.
        '{"inputs":{"classificationVersion":"60000000-0000-0000-0000-000000000001","mappingVersion":"sec-us-gaap-v2","adjustedDataset":"10000000-0000-0000-0000-000000000002","adjustmentBasis":"2026-08-31"}}'
        ELSE '{"inputs":{"classificationVersion":"60000000-0000-0000-0000-000000000001"}}' END::jsonb
FROM research.model_versions CROSS JOIN generate_series(1,4) n WHERE semantic_version='1.0.0';
UPDATE research.scoring_runs SET state='FAILED' WHERE score_run_id='50000000-0000-0000-0000-000000000004';
INSERT INTO research.score_lineage(score_run_id,instrument_id,issuer_id,symbol,canonical_input,prepared_input)
SELECT r.score_run_id,i.instrument_id,i.issuer_id,'OLD'||right(i.instrument_id::text,1),
    jsonb_build_object('instrumentId',i.instrument_id,'symbol','OLD'||right(i.instrument_id::text,1),
        'sector','Technology','inSp500',true,'inNasdaq100',right(i.instrument_id::text,1)='2'),
    jsonb_build_object('instrument_id',i.instrument_id)
FROM research.scoring_runs r CROSS JOIN reference.instruments i
WHERE r.score_run_id IN ('50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002');
-- P2b financials: prepared inputs of instrument 1 in run 2, captured in the input document below.
UPDATE research.score_lineage SET prepared_input=jsonb_build_object('instrument_id',instrument_id,
    'period_end','2026-06-30','filing_date','2026-07-15',
    'values',jsonb_build_object('REVENUE_TTM',1000,'CASH',50,'RAW_CLOSE',45.64,'SHARES_OUTSTANDING',10,'TOTAL_ASSETS_PRIOR',900),
    'provenance',jsonb_build_object(
        'REVENUE_TTM',jsonb_build_object('unit','USD','period_end','2026-06-30','filed_date','2026-07-15',
            'available_at','2026-07-15T12:00:00Z','observed_at','2026-07-15T12:00:00Z','source_ids',jsonb_build_array('f1','f2','f3')),
        'CASH',jsonb_build_object('unit','USD','period_end','2026-06-30','filed_date','2026-07-15',
            'available_at','2026-07-15T12:00:00Z','observed_at','2026-07-15T12:00:00Z','source_ids',jsonb_build_array('f4')),
        'RAW_CLOSE',jsonb_build_object('unit','USD','period_end','2026-07-31','available_at','2026-08-31T20:00:00+00:00',
            'observed_at','2026-08-31T20:00:00+00:00','availability_basis','PHASE6_CUTOFF_UPPER_BOUND','source_ids',jsonb_build_array('k1')),
        'SHARES_OUTSTANDING',jsonb_build_object('unit','shares','period_end','2026-07-10','filed_date','2026-07-15',
            'available_at','2026-07-15T12:00:00Z','observed_at','2026-07-15T12:00:00Z','source_ids',jsonb_build_array('f5')),
        'TOTAL_ASSETS_PRIOR',jsonb_build_object('unit','USD','period_end','2025-03-31','filed_date','2025-04-30',
            'available_at','2025-04-30T12:00:00Z','observed_at','2025-04-30T12:00:00Z','source_ids',jsonb_build_array('f6'))),
    'preparation_diagnostics',jsonb_build_object('COMMON_EQUITY','NO_SELECTED_CANONICAL_FACT'))
WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
-- P2b EBITDA_FROM_SOURCE_V1: OPERATING_INCOME_TTM 300 = annual 250 + YTD 120 - prior YTD 70. The retained
-- SEC response holds D&A 40/22/18 for the same filings and periods (=44, EBITDA 344). The 10-K fact comes
-- from a reprocessed artifact pointing to the original response; other filings/periods must be ignored.
INSERT INTO operations.source_artifacts(source_artifact_id,dataset_id,request_key,source_uri,retrieved_at,content_hash,media_type,parser_version,inline_content)
VALUES('10000000-0000-0000-0000-000000000010','10000000-0000-0000-0000-000000000002','sec:fixture','sec://company/0000000001','2026-07-20',repeat('d',64),'application/json','sec-companyfacts-v2',
    '{"companyFacts":{"facts":{"us-gaap":{"DepreciationDepletionAndAmortization":{"units":{"USD":[
        {"accn":"0000000001-26-000001","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":40},
        {"accn":"0000000001-26-000002","form":"10-Q","start":"2026-01-01","end":"2026-06-30","val":22},
        {"accn":"0000000001-26-000002","form":"10-Q","start":"2025-01-01","end":"2025-06-30","val":18},
        {"accn":"0000000001-26-000002","form":"10-Q","start":"2026-04-01","end":"2026-06-30","val":11},
        {"accn":"0000000001-25-000009","form":"10-Q","start":"2025-01-01","end":"2025-06-30","val":17}]}},
      "DepreciationAndAmortization":{"units":{"USD":[
        {"accn":"0000000001-26-000001","form":"10-K","start":"2025-01-01","end":"2025-12-31","val":99}]}}}}}}'),
    ('10000000-0000-0000-0000-000000000011','10000000-0000-0000-0000-000000000002','reprocess-v2:fixture','internal://source-artifact/10000000-0000-0000-0000-000000000010','2026-07-21',repeat('e',64),'application/json','sec-companyfacts-v2',
    '{"originalArtifactId":"10000000-0000-0000-0000-000000000010","filing":{}}');
INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,source_artifact_id,observed_at,ingested_at)
VALUES(repeat('1',64),'10000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000001','FILING_FACTS','2025-12-31','NONE','{}','10000000-0000-0000-0000-000000000011','2026-07-21','2026-07-21'),
    (repeat('2',64),'10000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000001','FILING_FACTS','2026-06-30','NONE','{}','10000000-0000-0000-0000-000000000010','2026-07-20','2026-07-20');
INSERT INTO fundamentals.filings(filing_id,issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,accepted_at,published_at,
    available_at,observed_at,primary_document,parser_version,mapping_version,source_artifact_id,observation_key)
VALUES('11000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','0000000001-26-000001',repeat('1',64),'10-K',
        '2025-12-31','2026-02-15','2026-02-15T12:00Z','2026-02-15T12:00Z','2026-07-21','2026-07-21','k.htm','sec-companyfacts-v2','sec-us-gaap-v2',
        '10000000-0000-0000-0000-000000000011',repeat('1',64)),
    ('11000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001','0000000001-26-000002',repeat('2',64),'10-Q',
        '2026-06-30','2026-07-15','2026-07-15T12:00Z','2026-07-15T12:00Z','2026-07-20','2026-07-20','q.htm','sec-companyfacts-v2','sec-us-gaap-v2',
        '10000000-0000-0000-0000-000000000010',repeat('2',64));
INSERT INTO fundamentals.fundamental_facts(fact_id,issuer_id,filing_id,metric_code,mapping_version,taxonomy,source_concept,period_start,period_end,
    source_value,numeric_value,unit,dimensions,source_context,source_fact_hash,priority,quality_state,available_at,observed_at,source_artifact_id)
VALUES('12000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000001','OPERATING_INCOME',
        'sec-us-gaap-v2','us-gaap','OperatingIncomeLoss','2025-01-01','2025-12-31',250,250,'USD','{}','{}',repeat('3',64),1,'VALID','2026-07-21','2026-07-21',
        '10000000-0000-0000-0000-000000000011'),
    ('12000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000002','OPERATING_INCOME',
        'sec-us-gaap-v2','us-gaap','OperatingIncomeLoss','2026-01-01','2026-06-30',120,120,'USD','{}','{}',repeat('4',64),1,'VALID','2026-07-20','2026-07-20',
        '10000000-0000-0000-0000-000000000010'),
    ('12000000-0000-0000-0000-000000000003','20000000-0000-0000-0000-000000000001','11000000-0000-0000-0000-000000000002','OPERATING_INCOME',
        'sec-us-gaap-v2','us-gaap','OperatingIncomeLoss','2025-01-01','2025-06-30',70,70,'USD','{}','{}',repeat('5',64),1,'VALID','2026-07-20','2026-07-20',
        '10000000-0000-0000-0000-000000000010');
UPDATE research.score_lineage SET prepared_input=jsonb_set(jsonb_set(prepared_input,'{values,OPERATING_INCOME_TTM}','300'),
    '{provenance,OPERATING_INCOME_TTM}',jsonb_build_object('unit','USD','period_end','2026-06-30','filed_date','2026-07-15',
        'available_at','2026-07-21T00:00:00Z','observed_at','2026-07-21T00:00:00Z','source_ids',jsonb_build_array(
        '12000000-0000-0000-0000-000000000001','12000000-0000-0000-0000-000000000002','12000000-0000-0000-0000-000000000003')))
WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
UPDATE research.scoring_runs r SET input_document=d.doc,input_sha256=encode(sha256(convert_to(d.doc,'UTF8')),'hex'),expected_count=3
FROM (SELECT score_run_id,jsonb_build_object('canonical',jsonb_build_object('inputs',jsonb_agg(canonical_input)),
    'prepared',jsonb_build_object('rows',jsonb_agg(prepared_input)))::text doc FROM research.score_lineage GROUP BY score_run_id) d
WHERE d.score_run_id=r.score_run_id;
INSERT INTO research.stock_scores(score_run_id,instrument_id,scoring_profile,peer_group,eligible,value_score,quality_score,momentum_score,
    value_contribution,quality_contribution,momentum_contribution,composite_z,composite_percentile,ordinal_rank,
    available_metric_count,missing_metric_count,warnings,output)
SELECT score_run_id,instrument_id,CASE WHEN right(instrument_id::text,1)='3' THEN 'UNSUPPORTED' ELSE 'GENERAL' END,'Technology',
    right(instrument_id::text,1)<>'3',
    CASE WHEN right(instrument_id::text,1)<>'3' THEN right(instrument_id::text,1)::integer END,
    CASE WHEN right(instrument_id::text,1)<>'3' THEN 1 END,CASE WHEN right(instrument_id::text,1)<>'3' THEN 1 END,
    0.5,0.3,0.2,CASE WHEN right(instrument_id::text,1)<>'3' THEN 1 END,
    CASE WHEN right(instrument_id::text,1)<>'3' THEN 50 END,
    CASE WHEN right(instrument_id::text,1)<>'3' THEN right(instrument_id::text,1)::integer END,
    CASE WHEN right(instrument_id::text,1)<>'3' THEN 7 ELSE 0 END,
    CASE WHEN right(instrument_id::text,1)<>'3' THEN 0 ELSE 7 END,
    CASE WHEN right(instrument_id::text,1)='2' THEN '["NO_DISPERSION"]'::jsonb ELSE '[]'::jsonb END,'{}'
FROM research.score_lineage;
INSERT INTO research.factor_observations(score_run_id,instrument_id,metric,family,cohort_count,clipped_score,weight,contribution,raw_value)
SELECT score_run_id,instrument_id,'metric'||n,CASE WHEN n<=3 THEN 'value' WHEN n<=6 THEN 'quality' ELSE 'momentum' END,
    10,1,0.1,right(instrument_id::text,1)::integer*0.1,12345678901234567890.123456789
FROM research.stock_scores CROSS JOIN generate_series(1,7) n WHERE eligible;
INSERT INTO research.score_exclusions(score_run_id,instrument_id,stage,reason_code,detail)
SELECT score_run_id,instrument_id,'ELIGIBILITY','MODEL_NOT_SUPPORTED','No supported profile' FROM research.stock_scores WHERE NOT eligible;
INSERT INTO research.score_source_artifacts(score_run_id,instrument_id,source_artifact_id)
SELECT score_run_id,instrument_id,'10000000-0000-0000-0000-000000000003' FROM research.stock_scores WHERE eligible;
-- P2b financials: instrument 1 of run 2 has prepared inputs and derivations; a second dataset must be ignored.
INSERT INTO operations.datasets(dataset_id,provider_id,code,version,license_notes)
VALUES('10000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000001','fixture-other','1','Fixture');
UPDATE research.stock_scores SET output=jsonb_build_object('score_status','PARTIAL',
    'derived',jsonb_build_object('market_cap',456.4,'ev',NULL,'REVENUE_TTM',1000,'metric1',0.5),
    'input_reasons',jsonb_build_object('ev','NOT_REPORTED','COMMON_EQUITY','NOT_REPORTED','TOTAL_ASSETS_PRIOR','INSUFFICIENT_HISTORY'))
WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
-- 20 consecutive sessions ending at the score date (StockCharts RSI example closes) plus one earlier
-- session without a bar; a late observation and another dataset must both be ignored.
INSERT INTO reference.trading_sessions(exchange_mic,session_date,opens_at,closes_at,timezone,source,available_at,observed_at)
SELECT 'XNYS',d,d+time '13:30',d+time '20:00','America/New_York','fixture','2026-01-01','2026-01-01'
FROM unnest(ARRAY['2026-07-02','2026-07-06','2026-07-07','2026-07-08','2026-07-09','2026-07-10','2026-07-13','2026-07-14',
    '2026-07-15','2026-07-16','2026-07-17','2026-07-20','2026-07-21','2026-07-22','2026-07-23','2026-07-24','2026-07-27',
    '2026-07-28','2026-07-29','2026-07-30','2026-07-31']::date[]) d;
CREATE TEMP TABLE fixture_bars ON COMMIT DROP AS
SELECT s.session_date,encode(sha256(convert_to('bar'||s.session_date||d.dataset_id,'UTF8')),'hex') observation_key,
    d.dataset_id,c.close*d.factor close,timestamptz '2026-08-31T12:00Z' seen
FROM (SELECT session_date,row_number() OVER (ORDER BY session_date) n FROM reference.trading_sessions
      WHERE exchange_mic='XNYS' AND session_date>'2026-07-02') s
JOIN unnest(ARRAY[44.34,44.09,44.15,43.61,44.33,44.83,45.10,45.42,45.84,46.08,45.89,46.03,45.61,46.28,46.28,46.00,46.03,46.41,46.22,45.64])
    WITH ORDINALITY c(close,n) USING(n)
CROSS JOIN (VALUES('10000000-0000-0000-0000-000000000002'::uuid,1.0),('10000000-0000-0000-0000-000000000004'::uuid,2.0)) d(dataset_id,factor)
UNION ALL SELECT '2026-07-31',repeat('c',64),'10000000-0000-0000-0000-000000000002',999,'2026-09-01T12:00Z';
INSERT INTO market_data.observations(observation_key,dataset_id,instrument_id,event_type,economic_time,adjustment_mode,payload,
    source_artifact_id,observed_at,ingested_at)
SELECT observation_key,dataset_id,'30000000-0000-0000-0000-000000000001','DAILY_BAR',session_date+time '20:00','SPLIT_DIVIDEND',
    '{}','10000000-0000-0000-0000-000000000003',seen,seen FROM fixture_bars;
INSERT INTO market_data.daily_bar_observations(session_date,observation_key,instrument_id,provider_id,dataset_id,exchange_mic,feed,
    adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,close,volume,trade_count,source_revision,source_artifact_id,
    available_at,observed_at,ingested_at)
SELECT session_date,observation_key,'30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001',dataset_id,
    'XNYS','sip','SPLIT_DIVIDEND','2026-08-31','USD',session_date+time '20:00',close,close,close,close,1000,10,repeat('b',64),
    '10000000-0000-0000-0000-000000000003',seen,seen,seen
FROM fixture_bars;
UPDATE research.scoring_runs SET scored_count=2,eligible_count=2,excluded_count=1,state='PUBLISHED',published_at='2026-08-01',finished_at='2026-08-01'
WHERE score_run_id IN ('50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002');

-- A newer run has one stored score but is deliberately incomplete and unpublished.
INSERT INTO research.score_lineage
SELECT (jsonb_populate_record(NULL::research.score_lineage,to_jsonb(l)||jsonb_build_object('score_run_id','50000000-0000-0000-0000-000000000003'))).*
FROM research.score_lineage l WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
INSERT INTO research.stock_scores
SELECT (jsonb_populate_record(NULL::research.stock_scores,to_jsonb(s)||jsonb_build_object('score_run_id','50000000-0000-0000-0000-000000000003'))).*
FROM research.stock_scores s WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
