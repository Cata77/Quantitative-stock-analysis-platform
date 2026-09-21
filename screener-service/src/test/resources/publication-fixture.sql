
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
SELECT repeat('a',64),model_version_id,manifest_sha256,'fixture',0.000000001 FROM research.model_versions;
INSERT INTO research.scoring_runs(score_run_id,logical_key,as_of_date,market_cutoff,knowledge_cutoff,effective_from,
    sp500_snapshot_id,nasdaq100_snapshot_id,model_version_id,certification_id,candidate,input_version,request)
SELECT ('50000000-0000-0000-0000-'||lpad(n::text,12,'0'))::uuid,lpad(n::text,64,'0'),
    CASE n WHEN 1 THEN '2026-06-30'::date WHEN 2 THEN '2026-07-31'::date ELSE '2026-08-31'::date END,
    '2026-08-31T20:00Z','2026-08-31T20:00Z','2026-09-01T13:30Z',
    '40000000-0000-0000-0000-000000000001','40000000-0000-0000-0000-000000000002',
    model_version_id,repeat('a',64),'primary','prepared-canonical-v1','{"inputs":{"classificationVersion":"60000000-0000-0000-0000-000000000001"}}'
FROM research.model_versions CROSS JOIN generate_series(1,4) n;
UPDATE research.scoring_runs SET state='FAILED' WHERE score_run_id='50000000-0000-0000-0000-000000000004';
INSERT INTO research.score_lineage(score_run_id,instrument_id,issuer_id,symbol,canonical_input,prepared_input)
SELECT r.score_run_id,i.instrument_id,i.issuer_id,'OLD'||right(i.instrument_id::text,1),
    jsonb_build_object('instrumentId',i.instrument_id,'symbol','OLD'||right(i.instrument_id::text,1),
        'sector','Technology','inSp500',true,'inNasdaq100',right(i.instrument_id::text,1)='2'),
    jsonb_build_object('instrument_id',i.instrument_id)
FROM research.scoring_runs r CROSS JOIN reference.instruments i
WHERE r.score_run_id IN ('50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002');
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
UPDATE research.scoring_runs SET scored_count=2,eligible_count=2,excluded_count=1,state='PUBLISHED',published_at='2026-08-01',finished_at='2026-08-01'
WHERE score_run_id IN ('50000000-0000-0000-0000-000000000001','50000000-0000-0000-0000-000000000002');

-- A newer run has one stored score but is deliberately incomplete and unpublished.
INSERT INTO research.score_lineage
SELECT (jsonb_populate_record(NULL::research.score_lineage,to_jsonb(l)||jsonb_build_object('score_run_id','50000000-0000-0000-0000-000000000003'))).*
FROM research.score_lineage l WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
INSERT INTO research.stock_scores
SELECT (jsonb_populate_record(NULL::research.stock_scores,to_jsonb(s)||jsonb_build_object('score_run_id','50000000-0000-0000-0000-000000000003'))).*
FROM research.stock_scores s WHERE score_run_id='50000000-0000-0000-0000-000000000002' AND instrument_id='30000000-0000-0000-0000-000000000001';
