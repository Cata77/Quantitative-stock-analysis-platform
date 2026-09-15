package com.quantplatform.scoring.fundamentals;

import com.quantplatform.ingestion.*;
import com.quantplatform.scoring.ingestion.MarketDataValidationException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** All writes participate in the consumer inbox transaction. */
public class FundamentalDataStore {
    private final JdbcClient jdbc;
    public FundamentalDataStore(DataSource source){jdbc=JdbcClient.create(source);}
    public void validate(ObservationEvent event) {
        if(!event.adjustmentMode().equals("NONE"))throw new IllegalArgumentException("invalid fundamental adjustment");
        if(event.eventType().equals("FILING_FACTS")) {
            var filing=filing(event);
            if(!filing.fiscalPeriodEnd().atStartOfDay(ZoneOffset.UTC).toInstant().equals(event.economicTime()))
                throw new IllegalArgumentException("filing envelope period mismatch");
        } else if(event.eventType().equals("REGULATORY_FACTS")) {
            RegulatoryReport.parse(event.payload());
        } else if(!Set.of("COLLECTED","NOT_APPLICABLE","NO_CIK","NO_SUPPORTED_FILINGS").contains(event.payload().get("status")))
            throw new IllegalArgumentException("invalid collection status");
    }
    public void persist(ObservationEvent event,UUID artifact,Instant observed) {
        var identity=jdbc.sql("""
            SELECT s.issuer_id,i.cik FROM reference.instruments s JOIN reference.issuers i USING (issuer_id)
            WHERE instrument_id=:instrument
            """).param("instrument",event.instrumentId()).query((rs,row)->new Identity(rs.getObject(1,UUID.class),rs.getString(2))).single();
        if(event.eventType().equals("REGULATORY_FACTS")) {
            regulatory(event,artifact,observed,identity);return;
        }
        if(event.eventType().equals("FUNDAMENTAL_COLLECTION_STATUS")) {
            String cik=Objects.toString(event.payload().get("cik"),"");
            if(!cik.isBlank()&&!cik.equals(identity.cik()))throw new MarketDataValidationException("collection CIK mismatch");
            String status=event.payload().get("status").toString();
            if(!Set.of("COLLECTED","NOT_APPLICABLE").contains(status)) profile(identity.id(),event,artifact,observed,"UNKNOWN","MODEL_NOT_SUPPORTED",status);
            return;
        }
        var filing=filing(event);
        if(!filing.cik().equals(identity.cik())||filing.acceptedAt().isAfter(observed))
            throw new MarketDataValidationException("filing issuer or availability mismatch");
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key,0))")
            .param("key","filing:"+identity.id()+":"+filing.accession()).query(Integer.class).single();
        String revision=CanonicalJson.sha256(CanonicalJson.write(event.payload()));
        UUID id=jdbc.sql("""
            INSERT INTO fundamentals.filings (issuer_id,accession,revision_hash,form,fiscal_period_end,filed_date,
                accepted_at,published_at,available_at,observed_at,amends_accession,primary_document,
                parser_version,mapping_version,source_artifact_id,observation_key)
            VALUES (:issuer,:accession,:revision,:form,:period,:filed,:accepted,:accepted,:observed,:observed,
                :amends,:document,'sec-companyfacts-v1',:mapping,:artifact,:key)
            ON CONFLICT (issuer_id,accession,revision_hash) DO NOTHING RETURNING filing_id
            """).param("issuer",identity.id()).param("accession",filing.accession()).param("revision",revision)
            .param("form",filing.form()).param("period",filing.fiscalPeriodEnd()).param("filed",filing.filedDate())
            .param("accepted",offset(filing.acceptedAt())).param("observed",offset(observed))
            .param("amends",filing.amendsAccession()).param("document",filing.primaryDocument())
            .param("mapping",filing.mappingVersion()).param("artifact",artifact).param("key",event.observationKey())
            .query(UUID.class).optional().orElse(null);
        if(id==null)return;
        var mappings=mappings(filing.mappingVersion());
        int validFacts=0;
        for(var fact:filing.facts()) {
            if(fact.end().isAfter(filing.filedDate()))throw new MarketDataValidationException("fact ends after filing");
            var mapping=mappings.get(fact.taxonomy()+":"+fact.concept());
            String quality="VALID";
            if(mapping==null || fact.end().isBefore(mapping.from()) || mapping.to()!=null&&!fact.end().isBefore(mapping.to()))quality="UNMAPPED";
            else if(!mapping.unit().equals(fact.unit()))quality="INVALID_UNIT";
            else if(!fact.dimensions().isEmpty())quality="UNSUPPORTED_DIMENSIONS";
            else if(mapping.period().equals("INSTANT")!=(fact.start()==null))quality="INVALID_PERIOD";
            else if(Set.of("CAPEX","DILUTED_WEIGHTED_SHARES","BASIC_WEIGHTED_SHARES","SHARES_OUTSTANDING").contains(mapping.metric())
                    && fact.value().signum()<0)quality="INVALID_SIGN";
            if(quality.equals("VALID"))validFacts++;
            String raw=CanonicalJson.MAPPER.writeValueAsString(fact);
            UUID factId=jdbc.sql("""
                INSERT INTO fundamentals.fundamental_facts (issuer_id,filing_id,metric_code,mapping_version,taxonomy,
                    source_concept,period_start,period_end,source_value,numeric_value,unit,dimensions,source_context,
                    source_fact_hash,priority,quality_state,available_at,observed_at,source_artifact_id)
                VALUES (:issuer,:filing,:metric,:mapping,:taxonomy,:concept,:start,:end,:source,:value,:unit,
                    CAST(:dimensions AS jsonb),CAST(:context AS jsonb),:hash,:priority,:quality,:observed,:observed,:artifact)
                ON CONFLICT DO NOTHING RETURNING fact_id
                """).param("issuer",identity.id()).param("filing",id).param("metric",mapping==null?null:mapping.metric())
                .param("mapping",filing.mappingVersion()).param("taxonomy",fact.taxonomy()).param("concept",fact.concept())
                .param("start",fact.start()).param("end",fact.end()).param("source",fact.value())
                .param("value",quality.equals("VALID")?fact.value().multiply(mapping.multiplier()):null)
                .param("unit",fact.unit()).param("dimensions",CanonicalJson.write(fact.dimensions()))
                .param("context",CanonicalJson.write(fact.context())).param("hash",CanonicalJson.sha256(raw))
                .param("priority",mapping==null?999:mapping.priority()).param("quality",quality)
                .param("observed",offset(observed)).param("artifact",artifact).query(UUID.class).optional().orElse(null);
            // Weighted-average diluted shares are never outstanding shares for market capitalization.
            if(factId!=null && quality.equals("VALID") && mapping.metric().equals("SHARES_OUTSTANDING")) {
                jdbc.sql("""
                    INSERT INTO fundamentals.instrument_share_facts
                    SELECT s.instrument_id,:fact,'SINGLE_SHARE_CLASS',:observed FROM reference.instruments s
                    WHERE s.issuer_id=:issuer AND s.security_type='COMMON_STOCK' AND s.valid_from<=:date
                      AND (s.valid_to IS NULL OR s.valid_to>:date)
                      AND 1=(SELECT COUNT(*) FROM reference.instruments x WHERE x.issuer_id=s.issuer_id
                        AND x.security_type='COMMON_STOCK' AND x.valid_from<=:date AND (x.valid_to IS NULL OR x.valid_to>:date))
                    ON CONFLICT DO NOTHING
                    """).param("fact",factId).param("observed",offset(observed)).param("issuer",identity.id()).param("date",fact.end()).update();
            }
        }
        String sic=Objects.toString(filing.sic(),"");
        String profile="GENERAL",status="FACTS_AVAILABLE",reason="Factor coverage must be checked at the score cutoff";
        if(sic.isBlank()) {profile="UNKNOWN";status="MODEL_NOT_SUPPORTED";reason="MISSING_BUSINESS_CLASSIFICATION";}
        else {
            int code=Integer.parseInt(sic);
            if(code==6798){profile="EQUITY_REIT";status="MODEL_NOT_SUPPORTED";reason="NO_VALIDATED_FFO_AFFO_RECONCILIATION";}
            else if(code>=6300&&code<=6399){profile="INSURER";status="MODEL_NOT_SUPPORTED";reason="NO_VALIDATED_PARENT_SOLVENCY_AND_OPERATING_FACTS";}
            else if(code>=6000&&code<=6999){profile="FINANCIAL";status="MODEL_NOT_SUPPORTED";reason="REQUIRES_VERIFIED_BUSINESS_PROFILE_AND_REGULATORY_ANCHOR";}
        }
        if(validFacts==0){status="MODEL_NOT_SUPPORTED";reason="NO_MAPPED_FACTS";}
        profile(identity.id(),event,artifact,observed,profile,status,reason);
    }
    private void profile(UUID issuer,ObservationEvent event,UUID artifact,Instant observed,String profile,String status,String reason) {
        jdbc.sql("""
            INSERT INTO fundamentals.profile_observations VALUES (:issuer,:key,:profile,:status,:reason,:observed,:artifact)
            ON CONFLICT DO NOTHING
            """).param("issuer",issuer).param("key",event.observationKey()).param("profile",profile).param("status",status)
            .param("reason",reason).param("observed",offset(observed)).param("artifact",artifact).update();
    }
    private Map<String,Mapping> mappings(String version) {
        var result=new HashMap<String,Mapping>();
        jdbc.sql("""
            SELECT m.*,d.period_type FROM fundamentals.source_metric_mappings m
            JOIN fundamentals.metric_definitions d USING (metric_code) WHERE mapping_version=:version
            """).param("version",version).query((rs,row)->{
                result.put(rs.getString("taxonomy")+":"+rs.getString("source_concept"),new Mapping(rs.getString("metric_code"),
                    rs.getString("source_unit"),rs.getBigDecimal("multiplier"),rs.getInt("priority"),
                    rs.getString("period_type"),rs.getObject("valid_from",LocalDate.class),rs.getObject("valid_to",LocalDate.class)));
                return 1;
            }).list();
        if(result.isEmpty())throw new MarketDataValidationException("unknown fundamental mapping version");
        return result;
    }
    private void regulatory(ObservationEvent event,UUID artifact,Instant observed,Identity identity) {
        var report=RegulatoryReport.parse(event.payload());
        if(!report.cik().equals(identity.cik())||report.availableAt().isAfter(observed))
            throw new MarketDataValidationException("regulatory identity or availability mismatch");
        if(!report.periodEnd().atStartOfDay(ZoneOffset.UTC).toInstant().equals(event.economicTime()))
            throw new MarketDataValidationException("regulatory envelope period mismatch");
        UUID entity=jdbc.sql("""
            INSERT INTO fundamentals.regulated_entities (rssd_id,legal_name) VALUES (:rssd,:name)
            ON CONFLICT (rssd_id) DO UPDATE SET rssd_id=EXCLUDED.rssd_id RETURNING regulated_entity_id
            """).param("rssd",report.rssd()).param("name",report.legalName()).query(UUID.class).single();
        String hash=CanonicalJson.sha256(CanonicalJson.write(event.payload()));
        UUID link=jdbc.sql("""
            INSERT INTO fundamentals.issuer_regulated_entity_links (issuer_id,regulated_entity_id,effective_from,effective_to,
                scope,evidence_uri,available_at,source_artifact_id,revision_hash)
            VALUES (:issuer,:entity,:from,:to,:scope,:evidence,:available,:artifact,:hash)
            ON CONFLICT (issuer_id,regulated_entity_id,revision_hash) DO UPDATE SET revision_hash=EXCLUDED.revision_hash RETURNING link_id
            """).param("issuer",identity.id()).param("entity",entity).param("from",report.effectiveFrom()).param("to",report.effectiveTo())
            .param("scope",report.scope()).param("evidence",report.evidenceUri()).param("available",offset(observed))
            .param("artifact",artifact).param("hash",hash).query(UUID.class).single();
        var mappings=mappings(report.mappingVersion());
        for(var fact:report.facts().entrySet()) {
            var mapping=mappings.get("ffiec:"+fact.getKey());
            String quality=mapping==null?"UNMAPPED":report.periodEnd().isBefore(mapping.from())?"INVALID_PERIOD":
                fact.getValue().signum()<0?"INVALID_SIGN":"VALID";
            jdbc.sql("""
                INSERT INTO fundamentals.regulatory_facts (regulated_entity_id,link_id,period_end,source_concept,metric_code,
                    mapping_version,source_value,numeric_value,source_unit,quality_state,available_at,observed_at,source_artifact_id,revision_hash)
                VALUES (:entity,:link,:period,:concept,:metric,:mapping,:source,:value,'pure',:quality,:available,:observed,:artifact,:hash)
                ON CONFLICT DO NOTHING
                """).param("entity",entity).param("link",link).param("period",report.periodEnd()).param("concept",fact.getKey())
                .param("metric",mapping==null?null:mapping.metric()).param("mapping",report.mappingVersion()).param("source",fact.getValue())
                .param("value",quality.equals("VALID")?fact.getValue().multiply(mapping.multiplier()):null)
                .param("quality",quality).param("available",offset(observed)).param("observed",offset(observed))
                .param("artifact",artifact).param("hash",hash).update();
        }
        profile(identity.id(),event,artifact,observed,"BANK","MODEL_NOT_SUPPORTED",
            report.scope().equals("SUBSIDIARY_ONLY")?"SUBSIDIARY_IS_NOT_LISTED_PARENT":"ADDITIONAL_BANK_FACTS_AND_PROFILE_VALIDATION_REQUIRED");
    }
    private static FilingFacts filing(ObservationEvent event){return CanonicalJson.MAPPER.readValue(CanonicalJson.write(event.payload()),FilingFacts.class);}
    private static OffsetDateTime offset(Instant time){return time.atOffset(ZoneOffset.UTC);}
    private record Identity(UUID id,String cik){}
    private record Mapping(String metric,String unit,BigDecimal multiplier,int priority,String period,LocalDate from,LocalDate to){}
}
