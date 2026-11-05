package com.quantplatform.scoring.inputs;

import com.quantplatform.ingestion.CanonicalJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ScoringInputRepository {
    private final JdbcClient jdbc;
    private final String sql;
    @org.springframework.beans.factory.annotation.Autowired
    public ScoringInputRepository(DataSource source) {
        this(source, "canonical-preparation-v3");
    }
    /** Explicit legacy policy supports audits; new publications use the current policy. */
    public ScoringInputRepository(DataSource source, String preprocessingVersion) {
        var template=new org.springframework.jdbc.core.JdbcTemplate(source);
        template.setQueryTimeout(60);
        jdbc=JdbcClient.create(template);
        String resource=switch(preprocessingVersion) {
            case "profiles-v1" -> "sql/scoring-inputs-profiles-v1.sql";
            case "canonical-preparation-v3" -> "sql/scoring-inputs.sql";
            default -> throw new IllegalArgumentException("Unknown preprocessing version");
        };
        try { sql=new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8); }
        catch(IOException failure) { throw new IllegalStateException("Missing scoring input query",failure); }
    }
    public CrossSection load(ScoringInputRequest request) {
        var payload=statement(request,sql).query(String.class).single();
        var result=CanonicalJson.MAPPER.readValue(payload,QueryResult.class);
        if(!result.errors().isEmpty())throw new IllegalArgumentException(String.join(", ",result.errors()));
        return new CrossSection(request,result.inputs());
    }
    /** Same parameterized statement used for the target-scale integration query-plan gate. */
    public String explain(ScoringInputRequest request) {
        return String.join("\n",statement(request,"EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) "+sql)
            .query(String.class).list());
    }
    private JdbcClient.StatementSpec statement(ScoringInputRequest r,String query) {
        return jdbc.sql(query).param("scoreDate",r.scoreDate())
            .param("marketCutoff",r.marketCutoff().atOffset(ZoneOffset.UTC))
            .param("knowledgeCutoff",r.knowledgeCutoff().atOffset(ZoneOffset.UTC))
            .param("basis",r.adjustmentBasis()).param("rawDataset",r.rawDataset())
            .param("adjustedDataset",r.adjustedDataset()).param("sp500",r.sp500Snapshot())
            .param("nasdaq",r.nasdaq100Snapshot()).param("classification",r.classificationVersion())
            .param("mapping",r.mappingVersion()).param("timingPolicy",r.timingPolicy())
            .param("requiredMetrics","{"+String.join(",",new TreeSet<>(r.requiredMetrics()))+"}");
    }
    public record CrossSection(ScoringInputRequest request,List<ScoringInput> inputs) {
        public CrossSection { inputs=List.copyOf(inputs); }
        public long readyCount(){return inputs.stream().filter(ScoringInput::inputReady).count();}
        public long excludedCount(){return inputs.size()-readyCount();}
    }
    private record QueryResult(List<String> errors,List<ScoringInput> inputs) {}
}
