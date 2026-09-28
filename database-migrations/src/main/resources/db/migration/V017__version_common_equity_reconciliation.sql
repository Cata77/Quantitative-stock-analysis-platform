-- Keep the original mapping reproducible. V2 adds a narrowly evidenced consumer reconciliation.
INSERT INTO fundamentals.source_metric_mappings
SELECT 'sec-us-gaap-v2',taxonomy,source_concept,metric_code,source_unit,multiplier,priority,
       valid_from,valid_to,dimension_policy,notes
FROM fundamentals.source_metric_mappings WHERE mapping_version='sec-us-gaap-v1';
INSERT INTO fundamentals.source_metric_mappings VALUES
('sec-us-gaap-v2','quant-reconciled','CommonEquityZeroPreferredV1','COMMON_EQUITY','USD',1,10,
 '2009-01-01',NULL,'ENTITY_WIDE_ONLY',
 'common-equity-zero-preferred-v1: same filing/instant entity-wide StockholdersEquity; explicit zero PreferredStockValue and zero PreferredStockSharesIssued or Outstanding; conflicting/nonzero evidence blocks derivation. Source fact hashes retained.');
