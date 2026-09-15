CREATE TABLE fundamentals.metric_definitions (
    metric_code VARCHAR(80) PRIMARY KEY,
    statement VARCHAR(30) NOT NULL,
    period_type VARCHAR(10) NOT NULL CHECK (period_type IN ('INSTANT','DURATION')),
    unit VARCHAR(20) NOT NULL,
    aggregation VARCHAR(20) NOT NULL CHECK (aggregation IN ('BALANCE','ADDITIVE','WEIGHTED_AVERAGE','RATIO')),
    definition_version VARCHAR(40) NOT NULL,
    description TEXT NOT NULL
);
CREATE TABLE fundamentals.source_metric_mappings (
    mapping_version VARCHAR(40) NOT NULL,
    taxonomy VARCHAR(40) NOT NULL,
    source_concept VARCHAR(160) NOT NULL,
    metric_code VARCHAR(80) NOT NULL REFERENCES fundamentals.metric_definitions,
    source_unit VARCHAR(20) NOT NULL,
    multiplier NUMERIC NOT NULL DEFAULT 1,
    priority INTEGER NOT NULL CHECK (priority > 0),
    valid_from DATE NOT NULL,
    valid_to DATE CHECK (valid_to > valid_from),
    dimension_policy VARCHAR(30) NOT NULL DEFAULT 'ENTITY_WIDE_ONLY',
    notes TEXT NOT NULL,
    PRIMARY KEY (mapping_version,taxonomy,source_concept)
);
CREATE TABLE fundamentals.filings (
    filing_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id UUID NOT NULL REFERENCES reference.issuers,
    accession TEXT NOT NULL,
    revision_hash CHAR(64) NOT NULL,
    form VARCHAR(20) NOT NULL,
    fiscal_period_end DATE NOT NULL,
    filed_date DATE NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    ingested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    amends_accession TEXT,
    primary_document TEXT NOT NULL,
    parser_version VARCHAR(40) NOT NULL,
    mapping_version VARCHAR(40) NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    observation_key CHAR(64) NOT NULL REFERENCES market_data.observations,
    UNIQUE (issuer_id,accession,revision_hash),
    CHECK (accepted_at <= available_at AND available_at <= observed_at),
    CHECK (fiscal_period_end <= filed_date),
    CHECK (amends_accession IS NULL OR amends_accession <> accession)
);
CREATE INDEX idx_filings_cutoff ON fundamentals.filings (issuer_id,available_at DESC,fiscal_period_end DESC);
CREATE TABLE fundamentals.fundamental_facts (
    fact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id UUID NOT NULL REFERENCES reference.issuers,
    filing_id UUID NOT NULL REFERENCES fundamentals.filings,
    metric_code VARCHAR(80) REFERENCES fundamentals.metric_definitions,
    mapping_version VARCHAR(40) NOT NULL,
    taxonomy VARCHAR(40) NOT NULL,
    source_concept VARCHAR(160) NOT NULL,
    period_start DATE,
    period_end DATE NOT NULL,
    source_value NUMERIC NOT NULL,
    numeric_value NUMERIC,
    unit VARCHAR(20) NOT NULL,
    dimensions JSONB NOT NULL CHECK (jsonb_typeof(dimensions)='object'),
    source_context JSONB NOT NULL,
    source_fact_hash CHAR(64) NOT NULL,
    priority INTEGER NOT NULL,
    quality_state VARCHAR(40) NOT NULL CHECK (quality_state IN
        ('VALID','UNMAPPED','INVALID_UNIT','INVALID_PERIOD','UNSUPPORTED_DIMENSIONS','INVALID_SIGN')),
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    UNIQUE (filing_id,source_fact_hash),
    CHECK (period_start IS NULL OR period_start <= period_end)
);
CREATE INDEX idx_fundamental_fact_lookup ON fundamentals.fundamental_facts
    (issuer_id,metric_code,period_end DESC,available_at DESC);
CREATE TABLE fundamentals.instrument_share_facts (
    instrument_id UUID NOT NULL REFERENCES reference.instruments,
    fact_id UUID NOT NULL REFERENCES fundamentals.fundamental_facts,
    allocation_method VARCHAR(40) NOT NULL CHECK (allocation_method IN ('EXPLICIT_CLASS','SINGLE_SHARE_CLASS')),
    available_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (instrument_id,fact_id)
);
CREATE TABLE fundamentals.profile_observations (
    issuer_id UUID NOT NULL REFERENCES reference.issuers,
    observation_key CHAR(64) NOT NULL REFERENCES market_data.observations,
    profile VARCHAR(40) NOT NULL,
    status VARCHAR(40) NOT NULL,
    reason TEXT NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    PRIMARY KEY (issuer_id,observation_key)
);
CREATE TABLE fundamentals.regulated_entities (
    regulated_entity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rssd_id VARCHAR(20) NOT NULL UNIQUE CHECK (rssd_id ~ '^[0-9]+$'),
    legal_name TEXT NOT NULL
);
CREATE TABLE fundamentals.issuer_regulated_entity_links (
    link_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    issuer_id UUID NOT NULL REFERENCES reference.issuers,
    regulated_entity_id UUID NOT NULL REFERENCES fundamentals.regulated_entities,
    effective_from DATE NOT NULL,
    effective_to DATE CHECK (effective_to > effective_from),
    scope VARCHAR(30) NOT NULL CHECK (scope IN ('SAME_LEGAL_ENTITY','SUBSIDIARY_ONLY')),
    evidence_uri TEXT NOT NULL CHECK (btrim(evidence_uri)<>''),
    available_at TIMESTAMPTZ NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    revision_hash CHAR(64) NOT NULL,
    UNIQUE (issuer_id,regulated_entity_id,revision_hash)
);
CREATE TABLE fundamentals.regulatory_facts (
    regulatory_fact_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    regulated_entity_id UUID NOT NULL REFERENCES fundamentals.regulated_entities,
    link_id UUID NOT NULL REFERENCES fundamentals.issuer_regulated_entity_links,
    period_end DATE NOT NULL,
    source_concept VARCHAR(160) NOT NULL,
    metric_code VARCHAR(80) REFERENCES fundamentals.metric_definitions,
    mapping_version VARCHAR(40) NOT NULL,
    source_value NUMERIC NOT NULL,
    numeric_value NUMERIC,
    source_unit VARCHAR(20) NOT NULL,
    quality_state VARCHAR(40) NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    source_artifact_id UUID NOT NULL REFERENCES operations.source_artifacts,
    revision_hash CHAR(64) NOT NULL,
    UNIQUE (regulated_entity_id,period_end,source_concept,revision_hash)
);
CREATE INDEX idx_regulatory_cutoff ON fundamentals.regulatory_facts
    (regulated_entity_id,period_end DESC,available_at DESC);

INSERT INTO fundamentals.metric_definitions VALUES ('REVENUE','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','REVENUE; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','RevenueFromContractWithCustomerExcludingAssessedTax','REVENUE','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','Revenues','REVENUE','USD',1,2,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','SalesRevenueNet','REVENUE','USD',1,3,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('NET_INCOME','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','NET INCOME; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','NetIncomeLoss','NET_INCOME','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('NET_INCOME_COMMON','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','NET INCOME COMMON; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','NetIncomeLossAvailableToCommonStockholdersBasic','NET_INCOME_COMMON','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('OPERATING_INCOME','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','OPERATING INCOME; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','OperatingIncomeLoss','OPERATING_INCOME','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('PRETAX_INCOME','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','PRETAX INCOME; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','IncomeLossFromContinuingOperationsBeforeIncomeTaxesExtraordinaryItemsNoncontrollingInterest','PRETAX_INCOME','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('INTEREST_EXPENSE','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','INTEREST EXPENSE; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','InterestExpense','INTEREST_EXPENSE','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('TAX_EXPENSE','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','TAX EXPENSE; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','IncomeTaxExpenseBenefit','TAX_EXPENSE','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('GROSS_PROFIT','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','GROSS PROFIT; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','GrossProfit','GROSS_PROFIT','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('COST_OF_REVENUE','INCOME','DURATION','USD','ADDITIVE','fundamentals-v1','COST OF REVENUE; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','CostOfRevenue','COST_OF_REVENUE','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','CostOfGoodsAndServicesSold','COST_OF_REVENUE','USD',1,2,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('OPERATING_CASH_FLOW','CASH_FLOW','DURATION','USD','ADDITIVE','fundamentals-v1','OPERATING CASH FLOW; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','NetCashProvidedByUsedInOperatingActivities','OPERATING_CASH_FLOW','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('CAPEX','CASH_FLOW','DURATION','USD','ADDITIVE','fundamentals-v1','CAPEX; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','PaymentsToAcquirePropertyPlantAndEquipment','CAPEX','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('COMMON_DIVIDENDS','CASH_FLOW','DURATION','USD','ADDITIVE','fundamentals-v1','COMMON DIVIDENDS; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','PaymentsOfDividendsCommonStock','COMMON_DIVIDENDS','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('COMMON_REPURCHASES','CASH_FLOW','DURATION','USD','ADDITIVE','fundamentals-v1','COMMON REPURCHASES; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','PaymentsForRepurchaseOfCommonStock','COMMON_REPURCHASES','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('STOCK_ISSUANCE','CASH_FLOW','DURATION','USD','ADDITIVE','fundamentals-v1','COMMON ISSUANCE; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','ProceedsFromStockIssuance','STOCK_ISSUANCE','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('TOTAL_ASSETS','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','TOTAL ASSETS; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','Assets','TOTAL_ASSETS','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('COMMON_EQUITY','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','COMMON EQUITY; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','CommonStockholdersEquity','COMMON_EQUITY','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('STOCKHOLDERS_EQUITY','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','STOCKHOLDERS EQUITY; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','StockholdersEquity','STOCKHOLDERS_EQUITY','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('CASH','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','CASH; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','CashAndCashEquivalentsAtCarryingValue','CASH','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('DEBT_CURRENT','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','DEBT CURRENT; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','LongTermDebtCurrent','DEBT_CURRENT','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('DEBT_NONCURRENT','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','DEBT NONCURRENT; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','LongTermDebtNoncurrent','DEBT_NONCURRENT','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('SHORT_TERM_BORROWINGS','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','SHORT TERM BORROWINGS; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','ShortTermBorrowings','SHORT_TERM_BORROWINGS','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('PREFERRED_EQUITY','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','PREFERRED EQUITY; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','PreferredStockValue','PREFERRED_EQUITY','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('MINORITY_INTEREST','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','MINORITY INTEREST; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','MinorityInterest','MINORITY_INTEREST','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('GOODWILL','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','GOODWILL; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','Goodwill','GOODWILL','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('INTANGIBLES','BALANCE_SHEET','INSTANT','USD','BALANCE','fundamentals-v1','INTANGIBLES; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','FiniteLivedIntangibleAssetsNet','INTANGIBLES','USD',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('DILUTED_WEIGHTED_SHARES','INCOME','DURATION','shares','WEIGHTED_AVERAGE','fundamentals-v1','DILUTED WEIGHTED SHARES; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','WeightedAverageNumberOfDilutedSharesOutstanding','DILUTED_WEIGHTED_SHARES','shares',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('BASIC_WEIGHTED_SHARES','INCOME','DURATION','shares','WEIGHTED_AVERAGE','fundamentals-v1','BASIC WEIGHTED SHARES; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','WeightedAverageNumberOfSharesOutstandingBasic','BASIC_WEIGHTED_SHARES','shares',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('SHARES_OUTSTANDING','BALANCE_SHEET','INSTANT','shares','BALANCE','fundamentals-v1','SHARES OUTSTANDING; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','us-gaap','CommonStockSharesOutstanding','SHARES_OUTSTANDING','shares',1,1,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Direct standard concept; no inferred dimensions or unit conversion');

INSERT INTO fundamentals.metric_definitions VALUES ('CET1_RATIO','REGULATORY','INSTANT','pure','RATIO','fundamentals-v1','CET1 RATIO; preserve source scope and do not substitute unavailable components');

INSERT INTO fundamentals.metric_definitions VALUES ('CET1_RATIO_ADVANCED','REGULATORY','INSTANT','pure','RATIO','fundamentals-v1','CET1 RATIO ADVANCED; preserve source scope and do not substitute unavailable components');
INSERT INTO fundamentals.source_metric_mappings VALUES ('ffiec-call-v1','ffiec','RCOAP793','CET1_RATIO','pure',1,1,'2015-01-01',NULL,'ENTITY_WIDE_ONLY','Reported ratio as a fraction; retain approach and reporting entity');
INSERT INTO fundamentals.source_metric_mappings VALUES ('ffiec-call-v1','ffiec','RCFAP793','CET1_RATIO','pure',1,1,'2015-01-01',NULL,'ENTITY_WIDE_ONLY','Reported ratio as a fraction; retain approach and reporting entity');
INSERT INTO fundamentals.source_metric_mappings VALUES ('ffiec-call-v1','ffiec','RCOWP793','CET1_RATIO_ADVANCED','pure',1,1,'2015-01-01',NULL,'ENTITY_WIDE_ONLY','Reported ratio as a fraction; retain approach and reporting entity');
INSERT INTO fundamentals.source_metric_mappings VALUES ('ffiec-call-v1','ffiec','RCFWP793','CET1_RATIO_ADVANCED','pure',1,1,'2015-01-01',NULL,'ENTITY_WIDE_ONLY','Reported ratio as a fraction; retain approach and reporting entity');
INSERT INTO fundamentals.source_metric_mappings VALUES ('sec-us-gaap-v1','dei','EntityCommonStockSharesOutstanding','SHARES_OUTSTANDING','shares',1,2,'2009-01-01',NULL,'ENTITY_WIDE_ONLY','Do not allocate issuer-wide shares across multiple classes');
