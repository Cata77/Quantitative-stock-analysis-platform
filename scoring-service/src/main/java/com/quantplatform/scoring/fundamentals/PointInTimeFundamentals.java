package com.quantplatform.scoring.fundamentals;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Phase 5 fact access; the complete score cross-section remains phase 6. */
@Repository
public class PointInTimeFundamentals {
    private final JdbcClient jdbc;
    public PointInTimeFundamentals(DataSource source){jdbc=JdbcClient.create(source);}
    public List<Fact> load(UUID issuer,Instant cutoff) {
        return jdbc.sql("SELECT * FROM fundamentals.facts_as_of(:issuer,:cutoff,'sec-us-gaap-v1')")
            .param("issuer",issuer).param("cutoff",cutoff.atOffset(ZoneOffset.UTC)).query((rs,row)->new Fact(
                rs.getObject("fact_id",UUID.class),rs.getString("metric_code"),rs.getObject("period_start",LocalDate.class),
                rs.getObject("period_end",LocalDate.class),rs.getBigDecimal("numeric_value"),
                rs.getString("unit"),rs.getTimestamp("available_at").toInstant())).list();
    }
    public Result annual(UUID issuer,String metric,LocalDate end,Instant cutoff) {
        if(!additive(metric))return Result.missing("NON_ADDITIVE_METRIC");
        var candidates=load(issuer,cutoff).stream().filter(f->f.metric().equals(metric)&&f.end().equals(end)
                &&annual(f)).toList();
        return candidates.size()==1?Result.of(candidates.getFirst().value(),candidates):Result.missing("NOT_REPORTED_OR_AMBIGUOUS");
    }
    public Result trailingTwelveMonths(UUID issuer,String metric,LocalDate end,Instant cutoff) {
        if(!additive(metric))return Result.missing("NON_ADDITIVE_METRIC");
        var facts=load(issuer,cutoff).stream().filter(f->f.metric().equals(metric)&&f.start()!=null&&!f.end().isAfter(end)).toList();
        var direct=facts.stream().filter(f->f.end().equals(end)&&annual(f)).toList();
        if(direct.size()==1)return Result.of(direct.getFirst().value(),direct);
        // Four contiguous non-overlapping quarters; never sum cumulative YTD values as quarters.
        var quarters=new ArrayList<Fact>();
        LocalDate boundary=end;
        for(int i=0;i<4;i++) {
            LocalDate current=boundary;
            var matches=facts.stream().filter(f->f.end().equals(current)&&days(f)>=70&&days(f)<=110).toList();
            if(matches.size()!=1){quarters.clear();break;}
            var match=matches.getFirst();quarters.add(match);boundary=match.start().minusDays(1);
        }
        if(quarters.size()==4&&ChronoUnit.DAYS.between(boundary,end)>=350&&ChronoUnit.DAYS.between(boundary,end)<=380)
            return Result.of(quarters.stream().map(Fact::value).reduce(BigDecimal.ZERO,BigDecimal::add),quarters);
        // Annual + current YTD - matching prior YTD. Require exact boundary alignment, no fabricated periods.
        var results=new ArrayList<Result>();
        for(var ytd:facts)if(ytd.end().equals(end)&&days(ytd)>=70&&days(ytd)<350)
            for(var year:facts)if(annual(year)&&year.end().plusDays(1).equals(ytd.start()))
                for(var prior:facts)if(prior.start().equals(year.start())
                    &&Math.abs(ChronoUnit.DAYS.between(prior.end(),end.minusYears(1)))<=7
                    &&Math.abs(days(prior)-days(ytd))<=7
                    &&ChronoUnit.DAYS.between(prior.end(),end)>=350
                    &&ChronoUnit.DAYS.between(prior.end(),end)<=380)
                    results.add(Result.of(year.value().add(ytd.value()).subtract(prior.value()),List.of(year,ytd,prior)));
        return results.size()==1?results.getFirst():Result.missing("INSUFFICIENT_CONTIGUOUS_HISTORY");
    }
    private boolean additive(String metric) {
        return jdbc.sql("SELECT aggregation='ADDITIVE' FROM fundamentals.metric_definitions WHERE metric_code=:metric")
            .param("metric",metric).query(Boolean.class).optional().orElse(false);
    }
    private static long days(Fact fact){return ChronoUnit.DAYS.between(fact.start(),fact.end())+1;}
    private static boolean annual(Fact fact){return fact.start()!=null&&days(fact)>=350&&days(fact)<=380;}
    public record Fact(UUID id,String metric,LocalDate start,LocalDate end,BigDecimal value,String unit,Instant availableAt){}
    public record Result(BigDecimal value,List<UUID> sourceFactIds,Instant availableAt,String reason) {
        static Result of(BigDecimal value,List<Fact> facts){return new Result(value,facts.stream().map(Fact::id).toList(),
            facts.stream().map(Fact::availableAt).max(Comparator.naturalOrder()).orElseThrow(),"AVAILABLE");}
        static Result missing(String reason){return new Result(null,List.of(),null,reason);}
    }
}
