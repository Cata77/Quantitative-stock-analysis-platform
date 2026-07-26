CREATE TABLE factor_scores (
   time TIMESTAMP WITH TIME ZONE NOT NULL,
   symbol VARCHAR(10) NOT NULL,
   composite_score NUMERIC(10, 6) NOT NULL,
   z_value NUMERIC(10, 6) NOT NULL,
   z_momentum NUMERIC(10, 6) NOT NULL,
   z_quality NUMERIC(10, 6) NOT NULL,
   PRIMARY KEY (time, symbol)
);

CREATE INDEX idx_factor_scores_time_composite
   ON factor_scores (time DESC, composite_score DESC, symbol ASC);
