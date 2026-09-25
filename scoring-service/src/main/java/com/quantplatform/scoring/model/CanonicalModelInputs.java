package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.scoring.inputs.ScoringInputRepository.CrossSection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Canonical Phase 6 adapter; never enables an unsupported supplemental profile. */
public final class CanonicalModelInputs {
    static final Set<String> ADDITIVE=new TreeSet<>(Set.of("REVENUE","NET_INCOME","NET_INCOME_COMMON","OPERATING_INCOME","PRETAX_INCOME","INTEREST_EXPENSE","TAX_EXPENSE","GROSS_PROFIT","COST_OF_REVENUE","OPERATING_CASH_FLOW","CAPEX","COMMON_DIVIDENDS","COMMON_REPURCHASES","STOCK_ISSUANCE"));
    static final Set<String> BALANCES=new TreeSet<>(Set.of("TOTAL_ASSETS","COMMON_EQUITY","CASH","DEBT_CURRENT","DEBT_NONCURRENT","PREFERRED_EQUITY","MINORITY_INTEREST","GOODWILL","INTANGIBLES"));
    private CanonicalModelInputs(){}
    static long days(Map<String,Object> f){return ChronoUnit.DAYS.between(date(f.get("start")),date(f.get("end")))+1;}
    static boolean annual(Map<String,Object> f){return f.get("start")!=null&&span(days(f),350,380);}
    static Map<String,Object> result(List<Map<String,Object>> facts,int... signs){
        double sum=0;for(int i=0;i<facts.size();i++)sum+=number(facts.get(i).get("value"))*(signs.length==0?1:signs[i]);
        return obj("value",sum,"source_ids",facts.stream().map(f->str(f.get("factId"))).distinct().sorted().toList(),
            "available_at",facts.stream().map(f->str(f.get("availableAt"))).max(Comparator.comparing(FrozenModel::time)).orElseThrow(),
            "observed_at",facts.stream().map(f->str(f.get("observedAt"))).max(Comparator.comparing(FrozenModel::time)).orElseThrow(),
            "unit","USD","filed_date",facts.stream().map(f->str(f.get("filedDate"))).max(String::compareTo).orElseThrow(),
            "period_end",facts.stream().map(f->str(f.get("end"))).max(String::compareTo).orElseThrow());
    }
    public static Map<String,Object> ttm(List<Map<String,Object>> facts,String metric,String end){
        if(!ADDITIVE.contains(metric))return null;
        var selected=facts.stream().filter(f->metric.equals(f.get("metric"))&&f.get("start")!=null&&str(f.get("end")).compareTo(end)<=0).toList();
        var direct=selected.stream().filter(f->end.equals(f.get("end"))&&annual(f)).toList();
        if(!direct.isEmpty())return direct.size()==1?result(direct):null;
        var quarters=new ArrayList<Map<String,Object>>();String boundary=end;
        for(int i=0;i<4;i++){String b=boundary;var matches=selected.stream().filter(f->b.equals(f.get("end"))&&span(days(f),70,110)).toList();
            if(matches.size()!=1)break;var f=matches.getFirst();quarters.add(f);boundary=date(f.get("start")).minusDays(1).toString();
        }
        if(quarters.size()==4&&span(ChronoUnit.DAYS.between(date(boundary),date(end)),350,380))return result(quarters);
        var results=new ArrayList<Map<String,Object>>();
        for(var ytd:selected)if(end.equals(ytd.get("end"))&&span(days(ytd),70,349))for(var year:selected)
            if(annual(year)&&date(year.get("end")).plusDays(1).equals(date(ytd.get("start"))))for(var prior:selected)
                if(Objects.equals(prior.get("start"),year.get("start"))&&Math.abs(days(prior)-days(ytd))<=7&&span(ChronoUnit.DAYS.between(date(prior.get("end")),date(end)),350,380))
                    results.add(result(List.of(year,ytd,prior),1,1,-1));
        return results.size()==1?results.getFirst():null;
    }
    static void put(Map<String,Object> values,Map<String,Object> provenance,String key,Map<String,Object> e){
        if(e==null)return;var evidence=new LinkedHashMap<>(e);values.put(key,evidence.remove("value"));provenance.put(key,evidence);
    }
    static Map<String,Object> balance(List<Map<String,Object>> facts,String metric,String end){
        var matches=facts.stream().filter(f->metric.equals(f.get("metric"))&&f.get("start")==null&&end.equals(f.get("end"))).toList();
        return matches.size()==1?result(matches):null;
    }
    public static Map<String,Object> prepare(CrossSection section,Map<String,String> jurisdictions){
        return prepare(CanonicalJson.readObject(CanonicalJson.MAPPER.writeValueAsString(section)),jurisdictions);
    }
    public static Map<String,Object> prepare(Map<String,Object> section,Map<String,String> jurisdictions){
        var request=map(section.get("request"));var cutoff=time(request.get("knowledgeCutoff"));var scoreDate=date(request.get("scoreDate"));
        com.quantplatform.scoring.inputs.ScoringInputRequest.validateTiming(time(request.get("marketCutoff")).toInstant(),cutoff.toInstant(),str(request.getOrDefault("timingPolicy","CLOSE_V1")));
        String evidenceCutoff=cutoff.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd\u0027T\u0027HH:mm:ss"))+(cutoff.getNano()==0?"":String.format(java.util.Locale.ROOT,".%06d",cutoff.getNano()/1000))+(cutoff.getOffset().equals(ZoneOffset.UTC)?"+00:00":cutoff.getOffset().toString());
        var rows=new ArrayList<Map<String,Object>>();
        for(Object input:list(section.get("inputs"))){
            var item=map(input);
            var facts=list(item.get("facts")).stream().map(FrozenModel::map).filter(f->!time(f.get("availableAt")).isAfter(cutoff)&&!time(f.get("observedAt")).isAfter(cutoff)&&!date(f.get("end")).isAfter(scoreDate)&&"USD".equals(f.get("unit"))).toList();
            var periods=new HashSet<String>();for(var f:facts)if(!periods.add(f.get("metric")+"|"+f.get("start")+"|"+f.get("end")))throw new IllegalArgumentException("Ambiguous fact period");
            String latest=facts.stream().filter(f->"TOTAL_ASSETS".equals(f.get("metric"))&&f.get("start")==null).map(f->str(f.get("end"))).max(String::compareTo).orElse(null);
            var values=new LinkedHashMap<String,Object>();var provenance=new LinkedHashMap<String,Object>();
            if(latest!=null){
                var priors=facts.stream().filter(f->"TOTAL_ASSETS".equals(f.get("metric"))&&f.get("start")==null&&span(ChronoUnit.DAYS.between(date(f.get("end")),date(latest)),350,380)).map(f->str(f.get("end"))).distinct().sorted().toList();
                String prior=priors.size()==1?priors.getFirst():null;
                for(String metric:BALANCES){put(values,provenance,metric,balance(facts,metric,latest));if(prior!=null)put(values,provenance,metric+"_PRIOR",balance(facts,metric,prior));}
                for(String metric:ADDITIVE)put(values,provenance,metric+"_TTM",ttm(facts,metric,latest));
                var ends=facts.stream().filter(f->"PRETAX_INCOME".equals(f.get("metric"))&&annual(f)).map(f->str(f.get("end"))).distinct().sorted(Comparator.reverseOrder()).limit(3).toList();
                for(int i=0;i<ends.size();i++)for(String key:List.of("TAX","PRETAX")){
                    String metric=key.equals("TAX")?"TAX_EXPENSE":"PRETAX_INCOME",end=ends.get(i);
                    var matches=facts.stream().filter(f->metric.equals(f.get("metric"))&&end.equals(f.get("end"))&&annual(f)).toList();
                    if(matches.size()==1)put(values,provenance,"ANNUAL_"+key+"_"+(i+1),result(matches));
                }
            }
            var priceSources=list(item.get("priceLineage")).stream().map(FrozenModel::map).map(p->p.get("observationKey")).toList();
            var fields=Map.of("RAW_CLOSE","rawClose","ADJUSTED_CLOSE","adjustedClose","MOMENTUM_RECENT","momentumRecent","MOMENTUM_OLD","momentumOld","MEDIAN_DOLLAR_VOLUME","medianDollarVolume","SHARES_OUTSTANDING","sharesOutstanding");
            fields.forEach((key,field)->{if(item.get(field)!=null)put(values,provenance,key,obj("value",item.get(field),"unit",key.equals("SHARES_OUTSTANDING")?"shares":"USD","source_ids",key.equals("SHARES_OUTSTANDING")?Arrays.asList(item.get("shareFactId")):priceSources,
                "available_at",evidenceCutoff,"observed_at",evidenceCutoff,"availability_basis","PHASE6_CUTOFF_UPPER_BOUND","period_end",scoreDate.toString()));});
            rows.add(obj("score_date",scoreDate.toString(),"instrument_id",item.get("instrumentId"),"issuer_id",item.get("issuerId"),"profile",item.get("profile"),"profile_supported","GENERAL".equals(item.get("profile")),"peer_group",item.get("peerGroup"),
                "jurisdiction",jurisdictions.get(str(item.get("issuerId"))),"in_universe",yes(item,"inSp500")||yes(item,"inNasdaq100"),"active",true,"primary_class",item.get("primaryClass"),"security_type","COMMON_STOCK",
                "history_count",item.get("historyCount"),"liquidity_count",item.get("liquidityCount"),"filing_date",facts.stream().map(f->str(f.get("filedDate"))).max(String::compareTo).orElse("1900-01-01"),
                "period_end",latest,"values",values,"provenance",provenance,"blocking_reasons",list(item.get("reasons")).stream().map(FrozenModel::map).map(r->r.get("code")+(r.get("detail")!=null&&!str(r.get("detail")).isEmpty()?":"+r.get("detail"):"")).toList()));
        }
        return obj("as_of",request.get("knowledgeCutoff"),"rows",rows);
    }
}
