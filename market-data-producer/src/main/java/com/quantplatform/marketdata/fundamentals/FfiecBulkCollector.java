package com.quantplatform.marketdata.fundamentals;

import com.quantplatform.ingestion.*;
import com.quantplatform.marketdata.operations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Import a downloaded CDR tab-delimited schedule with an explicit, reviewed identity manifest. */
@Service
public class FfiecBulkCollector {
    private final FundamentalProperties properties;
    private final IngestionProperties ingestion;
    private final IngestionRunStore store;
    private final JdbcClient jdbc;
    private final Clock clock;
    public FfiecBulkCollector(DataSource source,FundamentalProperties properties,IngestionProperties ingestion,
            IngestionRunStore store,Clock clock) {
        this.properties=properties;this.ingestion=ingestion;this.store=store;this.clock=clock;jdbc=JdbcClient.create(source);
    }
    public boolean enabled(){return !properties.ffiecFile().isBlank();}
    public Map<String,Object> configuration() {
        var input=read();
        return Map.of("mappingVersion","ffiec-call-v1","contentHash",CanonicalJson.sha256(input.raw()),
            "manifestHash",CanonicalJson.sha256(input.manifest()));
    }
    public void collect(JobLease lease,String topic) {
        try {
            var input=read();
            String cik=jdbc.sql("""
                SELECT i.cik FROM reference.issuers i JOIN reference.instruments s USING (issuer_id)
                WHERE s.instrument_id=:id
                """).param("id",lease.instrumentId()).query((rs,row)->Objects.toString(rs.getString(1),"")).single();
            var reports=parse(input.raw(),input.manifest()).stream().filter(r->r.cik().equals(cik)).toList();
            var artifact=new SourceArtifact("ffiec:"+CanonicalJson.sha256(input.raw())+":"+CanonicalJson.sha256(input.manifest()),
                CanonicalJson.readObject(input.manifest()).get("sourceUri").toString(),clock.instant(),"ffiec-tsv-v1",
                CanonicalJson.write(Map.of("rawTsv",input.raw(),"manifest",input.manifest())));
            var events=new ArrayList<OutboxObservation>();
            for(var report:reports)events.add(new OutboxObservation(topic,"REGULATORY_FACTS",
                report.periodEnd().atStartOfDay(ZoneOffset.UTC).toInstant(),"NONE",CanonicalJson.MAPPER.writeValueAsString(report)));
            if(events.isEmpty()) {
                // Explicit absence is inspectable; never substitute a subsidiary or fabricate a bank anchor.
                events.add(new OutboxObservation(topic,"FUNDAMENTAL_COLLECTION_STATUS",Instant.EPOCH,"NONE",
                    CanonicalJson.write(Map.of("cik",cik,"status","NOT_APPLICABLE"))));
            }
            store.stage(lease,artifact,events,"{}",true);
        } catch(Exception failure) {
            try {store.retry(lease,"FFIEC_IMPORT_FAILED",failure.getClass().getSimpleName(),ingestion.retryBackoff());}
            catch(IllegalStateException expired) { /* The replacement worker owns the lease. */ }
        }
    }
    private Input read() {
        try {
            var data=Path.of(properties.ffiecFile());
            var manifest=Path.of(properties.ffiecManifest());
            if(Files.size(data)>50_000_000||Files.size(manifest)>1_000_000)
                throw new IllegalArgumentException("FFIEC input exceeds size bound");
            return new Input(Files.readString(data,StandardCharsets.UTF_8),Files.readString(manifest,StandardCharsets.UTF_8));
        } catch(java.io.IOException e){throw new IllegalArgumentException("cannot read FFIEC input",e);}
    }
    public static List<RegulatoryReport> parse(String raw,String manifestJson) {
        var manifest=CanonicalJson.readObject(manifestJson);
        if(!CanonicalJson.sha256(raw).equals(manifest.get("sha256")))
            throw new IllegalArgumentException("FFIEC file checksum mismatch");
        String source=Objects.toString(manifest.get("sourceUri"),"");
        if(!source.startsWith("https://cdr.ffiec.gov/"))throw new IllegalArgumentException("FFIEC source provenance is required");
        var lines=raw.lines().toList();
        if(lines.isEmpty())throw new IllegalArgumentException("empty FFIEC file");
        var headers=lines.getFirst().replace("\uFEFF","").split("\t",-1);
        int id=Arrays.asList(headers).indexOf("IDRSSD");
        if(id<0||new HashSet<>(Arrays.asList(headers)).size()!=headers.length)
            throw new IllegalArgumentException("invalid FFIEC columns");
        var rows=new HashMap<String,Map<String,java.math.BigDecimal>>();
        for(int i=1;i<lines.size();i++) {
            if(lines.get(i).isBlank())continue;
            var values=lines.get(i).split("\t",-1);
            if(values.length!=headers.length)throw new IllegalArgumentException("ragged FFIEC row");
            if(!values[id].matches("[0-9]+")) {
                if(i==1)continue; // CDR's optional description row.
                throw new IllegalArgumentException("invalid RSSD");
            }
            var facts=new TreeMap<String,java.math.BigDecimal>();
            for(int j=0;j<headers.length;j++)if(Set.of("RCOAP793","RCFAP793","RCOWP793","RCFWP793").contains(headers[j])
                    &&!values[j].isBlank())facts.put(headers[j],new java.math.BigDecimal(values[j]));
            if(rows.putIfAbsent(values[id],facts)!=null)throw new IllegalArgumentException("duplicate RSSD in schedule");
        }
        var reports=new ArrayList<RegulatoryReport>();
        if(!(manifest.get("entities") instanceof List<?> entities))throw new IllegalArgumentException("identity manifest is required");
        for(var rawEntity:entities) {
            var entity=CanonicalJson.readObject(CanonicalJson.write(rawEntity));
            String rssd=entity.get("rssd").toString();
            var facts=rows.get(rssd);
            if(facts==null||facts.isEmpty())throw new IllegalArgumentException("manifest entity has no mapped regulatory facts");
            reports.add(new RegulatoryReport(entity.get("cik").toString(),rssd,entity.get("legalName").toString(),
                LocalDate.parse(manifest.get("periodEnd").toString()),Instant.parse(manifest.get("availableAt").toString()),
                LocalDate.parse(entity.get("effectiveFrom").toString()),entity.get("effectiveTo")==null?null:
                    LocalDate.parse(entity.get("effectiveTo").toString()),entity.get("scope").toString(),
                entity.get("evidenceUri").toString(),"ffiec-call-v1",facts));
        }
        return List.copyOf(reports);
    }
    private record Input(String raw,String manifest){}
}
