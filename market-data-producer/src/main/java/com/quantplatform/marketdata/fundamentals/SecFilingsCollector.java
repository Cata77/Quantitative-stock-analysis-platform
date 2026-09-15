package com.quantplatform.marketdata.fundamentals;

import com.quantplatform.ingestion.*;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.provider.alpaca.ProviderRequestGate;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SecFilingsCollector {
    private final JdbcClient jdbc;
    private final SecDataClient client;
    private final FundamentalProperties properties;
    private final IngestionProperties ingestion;
    private final IngestionRunStore store;
    private final Clock clock;
    public SecFilingsCollector(DataSource source,SecDataClient client,FundamentalProperties properties,
            IngestionProperties ingestion,IngestionRunStore store,Clock clock) {
        jdbc=JdbcClient.create(source);this.client=client;this.properties=properties;
        this.ingestion=ingestion;this.store=store;this.clock=clock;
    }
    public boolean enabled(){return properties.enabled();}
    public Map<String,Object> configuration(){return Map.of("startDate",properties.startDate().toString(),
        "parserVersion","sec-companyfacts-v1","mappingVersion","sec-us-gaap-v1","baseUrl",properties.secBaseUrl().toString());}
    public void collect(JobLease lease,String topic) {
        try {
            var issuer=jdbc.sql("""
                SELECT i.cik FROM reference.issuers i JOIN reference.instruments s USING (issuer_id)
                WHERE s.instrument_id=:instrument
                """).param("instrument",lease.instrumentId()).query((rs,row)->Objects.toString(rs.getString(1),"")).single();
            var through=jdbc.sql("SELECT window_end FROM operations.ingestion_runs WHERE ingestion_run_id=:id")
                .param("id",lease.runId()).query(LocalDate.class).single();
            if(issuer.isBlank()) {
                stageStatus(lease,topic,through,"NO_CIK","",new SourceArtifact("no-cik:"+lease.instrumentId(),
                    "internal://reference/instrument/"+lease.instrumentId(),clock.instant(),"sec-companyfacts-v1","{}"));return;
            }
            String request="sec:"+issuer+":"+lease.runId();
            // A persisted issuer response is shared by share classes and by retries of the same run.
            var cached=jdbc.sql("""
                SELECT a.inline_content::text,a.retrieved_at FROM operations.source_artifacts a
                JOIN operations.ingestion_runs r ON r.dataset_id=a.dataset_id
                WHERE r.ingestion_run_id=:run AND a.request_key=:request
                  AND a.parser_version='sec-companyfacts-v1' ORDER BY a.retrieved_at DESC LIMIT 1
                """).param("run",lease.runId()).param("request",request)
                .query((rs,row)->new SourceArtifact(request,"sec://company/"+issuer,rs.getTimestamp(2).toInstant(),
                    "sec-companyfacts-v1",rs.getString(1))).optional();
            SourceArtifact artifact;
            SecDataClient.Bundle bundle;
            if(cached.isPresent()) {
                artifact=cached.get();
                bundle=CanonicalJson.MAPPER.readValue(artifact.rawJson(),SecDataClient.Bundle.class);
            } else {
                store.renew(lease,Duration.ofMinutes(5));
                bundle=client.fetch(issuer,properties.startDate(),through);
                artifact=new SourceArtifact(request,"sec://company/"+issuer,bundle.retrievedAt(),"sec-companyfacts-v1",
                    CanonicalJson.MAPPER.writeValueAsString(bundle));
            }
            var concepts=new HashSet<>(jdbc.sql("""
                SELECT taxonomy||':'||source_concept FROM fundamentals.source_metric_mappings
                WHERE mapping_version='sec-us-gaap-v1'
                """).query(String.class).list());
            var filings=new SecCompanyFactsParser().parse(issuer,bundle.companyFacts(),bundle.catalogs(),
                properties.startDate(),through,bundle.sic(),concepts);
            var checkpoint=lease.checkpointJson()==null?Map.<String,Object>of():CanonicalJson.readObject(lease.checkpointJson());
            int start=((Number)checkpoint.getOrDefault("filingIndex",0)).intValue();
            for(int i=start;i<filings.size();i++) {
                store.renew(lease,ingestion.jobLease());
                var filing=filings.get(i);
                String payload=CanonicalJson.MAPPER.writeValueAsString(filing);
                if(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>750_000)
                    throw new IllegalArgumentException("filing payload exceeds Kafka safety bound");
                store.stage(lease,artifact,List.of(new OutboxObservation(topic,"FILING_FACTS",
                    filing.fiscalPeriodEnd().atStartOfDay(ZoneOffset.UTC).toInstant(),"NONE",payload)),
                    CanonicalJson.write(Map.of("filingIndex",i+1)),false);
            }
            stageStatus(lease,topic,through,filings.isEmpty()?"NO_SUPPORTED_FILINGS":"COLLECTED",issuer,artifact);
        } catch(RuntimeException failure) {
            try {store.retry(lease,"SEC_COLLECTION_FAILED",failure.getClass().getSimpleName(),
                failure instanceof ProviderRequestGate.DeferredRequest d?d.delay():ingestion.retryBackoff()
                    .multipliedBy(1L<<Math.min(lease.attemptNumber()-1,8)));}
            catch(IllegalStateException expired) { /* Replacement lease owns the work now. */ }
        }
    }
    private void stageStatus(JobLease lease,String topic,LocalDate date,String status,String cik,SourceArtifact artifact) {
        store.stage(lease,artifact,List.of(new OutboxObservation(topic,"FUNDAMENTAL_COLLECTION_STATUS",
            date.atStartOfDay(ZoneOffset.UTC).toInstant(),"NONE",CanonicalJson.write(Map.of("cik",cik,"status",status)))),
            "{}",true);
    }
}
