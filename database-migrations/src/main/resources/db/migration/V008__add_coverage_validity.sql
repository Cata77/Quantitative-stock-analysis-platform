-- Coverage may be withdrawn by canonical quality validation without erasing its audit trail.
ALTER TABLE operations.data_coverage ADD COLUMN valid BOOLEAN NOT NULL DEFAULT TRUE;