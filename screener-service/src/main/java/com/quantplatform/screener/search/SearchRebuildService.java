package com.quantplatform.screener.search;

import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import com.quantplatform.screener.config.ScreenerProperties;
import com.quantplatform.screener.ranking.RankingQueryRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Periodic full reconciliation is deliberately bounded to the security master (hundreds of stocks).
 * PostgreSQL snapshot -> new generation -> refresh -> atomic alias -> durable checkpoint.
 * A failed checkpoint or crashed process is retried from canonical data, never an in-memory queue.
 */
@Service
public class SearchRebuildService {
    private final JdbcClient jdbc;
    private final ElasticsearchClient client;
    private final String alias;
    private final Clock clock;
    public SearchRebuildService(JdbcClient jdbc,ElasticsearchClient client,ScreenerProperties properties,Clock clock) {
        this.jdbc=jdbc;this.client=client;this.alias=properties.elasticsearch().companyIndex();this.clock=clock;
        if(!alias.matches("[a-z][a-z0-9_-]{0,100}")) throw new IllegalArgumentException("Invalid search alias");
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ, rollbackFor=Exception.class)
    public String rebuild(boolean force) throws IOException {
        // Serialize rebuilders without delaying API readers. Each alias has its own lock.
        if(!jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:alias,0))")
                .param("alias",alias).query(Boolean.class).single()) return "BUSY";
        Instant now=clock.instant();
        var documents=documents(now);
        String hash;
        try {
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsBytes(documents)));
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        var checkpoint=jdbc.sql("SELECT index_name,content_sha256 FROM operations.search_rebuild_checkpoints WHERE alias_name=:alias")
            .param("alias",alias).query((rs,n)->Map.of("index",rs.getString(1),"hash",rs.getString(2))).optional();
        Set<String> oldIndices=new HashSet<>();
        if(client.indices().existsAlias(r->r.name(alias)).value())
            oldIndices.addAll(client.indices().getAlias(r->r.name(alias)).result().keySet());
        if(!force && checkpoint.isPresent() && hash.equals(checkpoint.get().get("hash"))
                && oldIndices.equals(Set.of(checkpoint.get().get("index")))) return "UNCHANGED";

        String index=alias+"-v1-"+UUID.randomUUID().toString();
        client.indices().create(r->r.index(index).withJson(new StringReader(MAPPING)));
        for(int start=0;start<documents.size();start+=200) {
            var ops=new ArrayList<BulkOperation>();
            for(var document:documents.subList(start,Math.min(start+200,documents.size())))
                ops.add(BulkOperation.of(o->o.index(i->i.index(index)
                        .id(document.get("instrumentId").toString()).document(document))));
            var response=client.bulk(b->b.operations(ops));
            if(response.errors()) throw new IOException("Search rebuild bulk failed; previous alias retained");
        }
        var refresh=client.indices().refresh(r->r.index(index));
        if(refresh.shards().failed().intValue()>0) throw new IOException("Search rebuild refresh failed");
        long count=client.count(r->r.index(index)).count();
        if(count!=documents.size()) throw new IOException("Search rebuild count mismatch");
        var update=new UpdateAliasesRequest.Builder();
        for(String old:oldIndices) update.actions(a->a.remove(r->r.index(old).alias(alias)));
        update.actions(a->a.add(r->r.index(index).alias(alias)));
        if(!client.indices().updateAliases(update.build()).acknowledged())
            throw new IOException("Search alias update not acknowledged");
        jdbc.sql("""
            INSERT INTO operations.search_rebuild_checkpoints
                (alias_name,schema_version,index_name,content_sha256,document_count,source_as_of)
            VALUES(:alias,1,:index,:hash,:count,:now)
            ON CONFLICT(alias_name) DO UPDATE SET schema_version=1,index_name=excluded.index_name,
                content_sha256=excluded.content_sha256,document_count=excluded.document_count,
                source_as_of=excluded.source_as_of,completed_at=clock_timestamp()
            """).param("alias",alias).param("index",index).param("hash",hash).param("count",count)
            .param("now",now.atOffset(ZoneOffset.UTC)).update();
        return index;
    }

    public List<Map<String,Object>> documents(Instant now) {
        return jdbc.sql("""
            WITH latest AS (
                SELECT * FROM research.scoring_runs WHERE state='PUBLISHED' AND candidate='primary'
                    AND as_of_date<=:day AND expected_count=scored_count+excluded_count
                    AND scored_count=eligible_count AND scored_count>=2 AND published_at IS NOT NULL
                ORDER BY as_of_date DESC,published_at DESC,score_run_id LIMIT 1
            )
            SELECT jsonb_build_object('instrumentId',i.instrument_id,'symbol',sym.symbol,
                'name',issuer.legal_name,'exchange',i.primary_exchange_mic,'country',issuer.domicile,
                'sector',classification.mapped_sector,'industry',issuer.sic,'description',null,
                'updatedAt',greatest(i.updated_at,issuer.updated_at,sym.observed_at,classification.observed_at),
                'schemaVersion',1,
                'score',CASE WHEN s.instrument_id IS NULL THEN NULL ELSE jsonb_build_object(
                    'runId',r.score_run_id,'asOfDate',r.as_of_date,'modelVersionId',r.model_version_id,
                    'symbolAtScore',l.symbol,'eligible',s.eligible,'rank',s.ordinal_rank,
                    'percentile',s.composite_percentile,'compositeScore',s.composite_z,
                    'valueScore',s.value_score,'qualityScore',s.quality_score,'momentumScore',s.momentum_score,
                    'profile',s.scoring_profile,'peerGroup',s.peer_group,'warnings',s.warnings) END)::text
            FROM reference.instruments i JOIN reference.issuers issuer USING(issuer_id)
            LEFT JOIN LATERAL (
                SELECT symbol,observed_at FROM reference.instrument_symbols WHERE instrument_id=i.instrument_id
                    AND effective_from<=:day AND (effective_to IS NULL OR effective_to>:day)
                    AND available_at<=:now AND observed_at<=:now
                ORDER BY effective_from DESC,observed_at DESC,instrument_symbol_id LIMIT 1
            ) sym ON true
            LEFT JOIN LATERAL (
                SELECT mapped_sector,observed_at FROM reference.issuer_classifications WHERE issuer_id=i.issuer_id
                    AND effective_from<=:day AND (effective_to IS NULL OR effective_to>:day)
                    AND available_at<=:now AND observed_at<=:now
                ORDER BY effective_from DESC,observed_at DESC,issuer_classification_id LIMIT 1
            ) classification ON true
            LEFT JOIN latest r ON true
            LEFT JOIN research.stock_scores s ON s.score_run_id=r.score_run_id AND s.instrument_id=i.instrument_id
            LEFT JOIN research.score_lineage l ON l.score_run_id=s.score_run_id AND l.instrument_id=s.instrument_id
            WHERE i.valid_from<=:day AND (i.valid_to IS NULL OR i.valid_to>:day) AND i.active
            ORDER BY i.instrument_id
            """).param("day",now.atOffset(ZoneOffset.UTC).toLocalDate()).param("now",now.atOffset(ZoneOffset.UTC))
            .query((rs,n)->RankingQueryRepository.json(rs.getString(1))).list();
    }

    @Transactional(readOnly=true)
    public Map<String,Object> status() {
        return jdbc.sql("SELECT to_jsonb(c)::text FROM operations.search_rebuild_checkpoints c WHERE alias_name=:alias")
            .param("alias",alias).query((rs,n)->RankingQueryRepository.json(rs.getString(1)))
            .optional().orElse(Map.of("status","NOT_BUILT","alias_name",alias,"schema_version",1));
    }

    private static final String MAPPING="""
        {"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"dynamic":"strict",
         "_meta":{"schemaVersion":1},"properties":{
          "instrumentId":{"type":"keyword"},"symbol":{"type":"text"},"name":{"type":"text"},
          "exchange":{"type":"keyword"},"country":{"type":"keyword"},"sector":{"type":"text"},
          "industry":{"type":"text"},"description":{"type":"text"},"updatedAt":{"type":"date"},
          "schemaVersion":{"type":"integer"},"score":{"type":"object","properties":{
            "runId":{"type":"keyword"},"asOfDate":{"type":"date"},"modelVersionId":{"type":"keyword"},
            "symbolAtScore":{"type":"keyword"},"eligible":{"type":"boolean"},"rank":{"type":"integer"},
            "percentile":{"type":"double"},"compositeScore":{"type":"double"},"valueScore":{"type":"double"},
            "qualityScore":{"type":"double"},"momentumScore":{"type":"double"},"profile":{"type":"keyword"},
            "peerGroup":{"type":"keyword"},"warnings":{"type":"keyword"}}}}}}
        """;
}
