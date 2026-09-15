package com.quantplatform.scoring.ingestion;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.ingestion.DailyPrice;
import com.quantplatform.ingestion.ObservationEvent;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Invoked inside the same transaction as the durable inbox and observation journal. */
public class DailyMarketDataStore {
    private final JdbcClient jdbc;
    public DailyMarketDataStore(DataSource source) { jdbc = JdbcClient.create(source); }

    public void validate(ObservationEvent event) {
        if (event.eventType().equals("DAILY_PRICE")) {
            var price = CanonicalJson.MAPPER.readValue(CanonicalJson.write(event.payload()),DailyPrice.class);
            price.validateAdjustment(event.adjustmentMode());
            if (!price.time().equals(event.economicTime())) throw new IllegalArgumentException("daily price time differs from envelope");
        } else {
            if (!event.adjustmentMode().equals("NONE") || !(event.payload().get("actions") instanceof List<?>))
                throw new IllegalArgumentException("invalid corporate action batch");
            LocalDate date = LocalDate.parse(event.payload().get("processDate").toString());
            if (!date.atStartOfDay(ZoneOffset.UTC).toInstant().equals(event.economicTime()))
                throw new IllegalArgumentException("action process date differs from envelope");
            for (var raw : (List<?>)event.payload().get("actions")) {
                var action = CanonicalJson.readObject(CanonicalJson.write(raw));
                if (string(action,"id") == null || string(action,"type") == null
                        || !date.equals(date(action,"process_date"))) throw new IllegalArgumentException("invalid action identity/date");
            }
        }
    }

    public void persist(ObservationEvent event, UUID artifact, Instant observedAt) {
        var metadata = jdbc.sql("""
                SELECT d.provider_id,i.currency,i.primary_exchange_mic FROM operations.datasets d
                CROSS JOIN reference.instruments i WHERE d.dataset_id=:dataset AND i.instrument_id=:instrument
                """).param("dataset",event.datasetId()).param("instrument",event.instrumentId())
                .query((rs,row)->new Metadata(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3))).single();
        if (event.eventType().equals("DAILY_PRICE")) price(event,artifact,observedAt,metadata);
        else for (var raw : (List<?>)event.payload().get("actions"))
            action(event,CanonicalJson.readObject(CanonicalJson.write(raw)),artifact,observedAt,metadata);
    }

    private void price(ObservationEvent event, UUID artifact, Instant observedAt, Metadata metadata) {
        var price = CanonicalJson.MAPPER.readValue(CanonicalJson.write(event.payload()),DailyPrice.class);
        if (!price.currency().equals(metadata.currency())) throw new MarketDataValidationException("instrument currency differs from daily price");
        boolean session = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reference.trading_sessions
                    WHERE exchange_mic=:exchange AND session_date=:date AND NOT holiday)
                """).param("exchange",metadata.exchange()).param("date",price.sessionDate()).query(Boolean.class).single();
        if (!session) throw new MarketDataValidationException("daily price has no open trading session");
        jdbc.sql("""
                INSERT INTO market_data.daily_bar_observations (session_date,observation_key,instrument_id,provider_id,dataset_id,
                    exchange_mic,feed,adjustment_mode,adjustment_as_of,currency,bar_time,open,high,low,close,volume,vwap,trade_count,
                    source_revision,source_artifact_id,available_at,observed_at)
                VALUES (:date,:key,:instrument,:provider,:dataset,:exchange,:feed,:adjustment,:vintage,:currency,:time,
                    :open,:high,:low,:close,:volume,:vwap,:trades,:revision,:artifact,:observed,:observed)
                ON CONFLICT DO NOTHING
                """).param("date",price.sessionDate()).param("key",event.observationKey()).param("instrument",event.instrumentId())
                .param("provider",metadata.provider()).param("dataset",event.datasetId()).param("exchange",metadata.exchange())
                .param("feed",price.feed()).param("adjustment",event.adjustmentMode()).param("vintage",price.adjustmentAsOf())
                .param("currency",price.currency()).param("time",price.time().atOffset(ZoneOffset.UTC)).param("open",price.open())
                .param("high",price.high()).param("low",price.low()).param("close",price.close()).param("volume",price.volume())
                .param("vwap",price.volumeWeightedAveragePrice()).param("trades",price.tradeCount())
                .param("revision",CanonicalJson.sha256(CanonicalJson.write(event.payload()))).param("artifact",artifact)
                .param("observed",observedAt.atOffset(ZoneOffset.UTC)).update();
    }

    private void action(ObservationEvent event, Map<String,Object> action, UUID artifact, Instant observed, Metadata metadata) {
        String revision = CanonicalJson.sha256(CanonicalJson.write(action));
        boolean duplicate = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM market_data.corporate_actions
                    WHERE provider_id=:provider AND provider_action_id=:id
                      AND revision_hash=:revision AND instrument_id=:instrument)
                """).param("provider",metadata.provider()).param("id",string(action,"id"))
                .param("revision",revision).param("instrument",event.instrumentId()).query(Boolean.class).single();
        if (duplicate) return;
        String type = string(action,"type");
        LocalDate process = date(action,"process_date");
        LocalDate effective = date(action,"effective_date");
        LocalDate ex = date(action,"ex_date");
        String subject = first(action,"symbol","old_symbol","source_symbol","acquiree_symbol");
        String related = first(action,"new_symbol","acquirer_symbol");
        LocalDate boundary = effective != null ? effective : ex != null ? ex : process;
        boolean knownSubject = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM reference.instrument_symbols WHERE instrument_id=:id AND symbol=:symbol
                    AND effective_from<=:date AND (effective_to IS NULL OR effective_to>=:date))
                """).param("id",event.instrumentId()).param("symbol",subject).param("date",boundary).query(Boolean.class).single();
        UUID relatedId = related == null ? null : resolve(related,boundary);
        boolean split = Set.of("forward_splits","reverse_splits").contains(type);
        boolean dividend = type.equals("cash_dividends");
        boolean rename = type.equals("name_changes");
        boolean merger = Set.of("cash_mergers","stock_mergers","stock_and_cash_mergers").contains(type);
        boolean spinoff = type.equals("spin_offs");
        BigDecimal oldRate = decimal(action,split ? "old_rate" : spinoff ? "source_rate" : "acquiree_rate");
        BigDecimal newRate = decimal(action,merger ? "acquirer_rate" : "new_rate");
        BigDecimal cash = decimal(action,type.equals("stock_and_cash_mergers") ? "cash_rate" : "rate");
        boolean valid = knownSubject && (split || dividend || rename || merger || spinoff);
        if (split) valid &= ex != null && positive(oldRate) && positive(newRate);
        if (dividend) valid &= ex != null && cash != null && cash.signum() >= 0;
        if (spinoff || merger && !type.equals("cash_mergers")) valid &= relatedId != null && positive(oldRate) && positive(newRate);
        if (spinoff) valid &= ex != null;
        if (merger) valid &= effective != null;
        // Terms are retained, but continuity/terminal-return reconciliation is an explicit review.
        // Split-and-dividend prices do not account for spinoff distributions.
        if (merger || spinoff) valid = false;
        if (type.equals("cash_mergers") || type.equals("stock_and_cash_mergers")) valid &= cash != null && cash.signum() >= 0;
        if (rename) {
            effective = process;
            boolean sameSecurity = Objects.equals(string(action,"old_cusip"),string(action,"new_cusip"))
                    && string(action,"old_cusip") != null;
            valid &= sameSecurity && related != null && (relatedId == null || relatedId.equals(event.instrumentId()));
            if (valid) {
                valid = rename(event.instrumentId(),subject,related,process,observed,metadata.exchange());
                if (valid) relatedId = event.instrumentId();
            }
        }
        // Malformed numeric terms remain in provider_content but cannot violate canonical numeric checks.
        if (!positive(oldRate)) oldRate = null;
        if (!positive(newRate)) newRate = null;
        if (cash != null && cash.signum() < 0) cash = null;
        jdbc.sql("""
                INSERT INTO market_data.corporate_actions (provider_id,dataset_id,provider_action_id,revision_hash,
                    instrument_id,related_instrument_id,action_type,announcement_date,process_date,effective_date,ex_date,
                    record_date,payment_date,currency,cash_amount,old_rate,new_rate,old_symbol,new_symbol,
                    source_artifact_id,observation_key,provider_content,available_at,observed_at,quality_state)
                VALUES (:provider,:dataset,:id,:revision,:instrument,:related,:type,:announcement,:process,:effective,:ex,
                    :record,:payment,:currency,:cash,:oldRate,:newRate,:oldSymbol,:newSymbol,:artifact,:key,CAST(:content AS jsonb),
                    :observed,:observed,:quality) ON CONFLICT DO NOTHING
                """).param("provider",metadata.provider()).param("dataset",event.datasetId()).param("id",string(action,"id"))
                .param("revision",revision).param("instrument",event.instrumentId()).param("related",relatedId).param("type",type)
                .param("announcement",date(action,"announcement_date")).param("process",process).param("effective",effective)
                .param("ex",ex).param("record",date(action,"record_date")).param("payment",date(action,"payable_date"))
                .param("currency",metadata.currency()).param("cash",cash).param("oldRate",oldRate).param("newRate",newRate)
                .param("oldSymbol",subject).param("newSymbol",related).param("artifact",artifact).param("key",event.observationKey())
                .param("content",CanonicalJson.write(action)).param("observed",observed.atOffset(ZoneOffset.UTC))
                .param("quality",valid ? "VALID" : "REVIEW_REQUIRED").update();
        if (!valid) jdbc.sql("""
                INSERT INTO operations.data_quality_issues (dataset_id,instrument_id,severity,issue_type,affected_key,evidence)
                SELECT :dataset,:instrument,'BLOCKING','CORPORATE_ACTION_REVIEW',:key,CAST(:evidence AS jsonb)
                WHERE NOT EXISTS (SELECT 1 FROM operations.data_quality_issues
                    WHERE dataset_id=:dataset AND instrument_id=:instrument AND affected_key=:key
                      AND issue_type='CORPORATE_ACTION_REVIEW' AND status='OPEN')
                """).param("dataset",event.datasetId()).param("instrument",event.instrumentId()).param("key",string(action,"id"))
                .param("evidence",CanonicalJson.write(Map.of("revision",revision,"type",type,"reason","identity or required terms unresolved"))).update();
        else jdbc.sql("""
                UPDATE operations.data_quality_issues SET status='RESOLVED',resolved_at=clock_timestamp()
                WHERE dataset_id=:dataset AND instrument_id=:instrument AND issue_type='CORPORATE_ACTION_REVIEW'
                    AND affected_key=:key AND status='OPEN'
                """).param("dataset",event.datasetId()).param("instrument",event.instrumentId()).param("key",string(action,"id")).update();
    }
    private boolean rename(UUID instrument, String oldSymbol, String newSymbol, LocalDate date, Instant observed, String exchange) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key,0))")
                .param("key","symbol:"+exchange+":"+newSymbol).query(Integer.class).single();
        var existing = resolve(newSymbol,date);
        if (existing != null) return existing.equals(instrument);
        int closed = jdbc.sql("""
                UPDATE reference.instrument_symbols SET effective_to=:date WHERE instrument_id=:id AND symbol=:old
                    AND exchange_mic=:exchange AND effective_from<:date AND (effective_to IS NULL OR effective_to>:date)
                """).param("date",date).param("id",instrument).param("old",oldSymbol).param("exchange",exchange).update();
        if (closed != 1) return false;
        jdbc.sql("""
                INSERT INTO reference.instrument_symbols (instrument_id,symbol,exchange_mic,effective_from,source,available_at,observed_at)
                VALUES (:id,:symbol,:exchange,:date,'ALPACA_CORPORATE_ACTION',:observed,:observed)
                """).param("id",instrument).param("symbol",newSymbol).param("exchange",exchange).param("date",date)
                .param("observed",observed.atOffset(ZoneOffset.UTC)).update();
        return true;
    }
    private UUID resolve(String symbol, LocalDate date) {
        var ids = jdbc.sql("""
                SELECT DISTINCT instrument_id FROM reference.instrument_symbols WHERE symbol=:symbol
                    AND effective_from<=:date AND (effective_to IS NULL OR effective_to>:date)
                """).param("symbol",symbol).param("date",date).query(UUID.class).list();
        return ids.size()==1 ? ids.getFirst() : null;
    }
    private static boolean positive(BigDecimal value) { return value != null && value.signum()>0; }
    private static String string(Map<String,Object> data,String key) { return data.get(key)==null ? null : data.get(key).toString(); }
    private static LocalDate date(Map<String,Object> data,String key) { return data.get(key)==null || data.get(key).toString().isBlank() ? null : LocalDate.parse(data.get(key).toString()); }
    private static BigDecimal decimal(Map<String,Object> data,String key) { return data.get(key)==null ? null : new BigDecimal(data.get(key).toString()); }
    private static String first(Map<String,Object> data,String... keys) { for(String key:keys) if(data.get(key)!=null)return data.get(key).toString();return null; }
    private record Metadata(UUID provider,String currency,String exchange) { }
}
