package com.quantplatform.scoring.model;

import com.quantplatform.ingestion.CanonicalJson;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Manual binary64 implementation of the frozen v1 contract. No executable manifest expressions. */
public final class FrozenModel {
    public static final String SHA = "75c93c4176359f4dcd62cd4d68680f310b727ae27ff6c35a4fe5941006fdcd06";
    public final Map<String,Object> manifest;
    public FrozenModel() {
        try {
            manifest = CanonicalJson.readObject(new ClassPathResource("model/manifest.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            // Structural equality to the packaged, checksum-pinned canonical Python representation.
            String canonical = new ClassPathResource("model/manifest.canonical.json").getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            if (!CanonicalJson.sha256(canonical).equals(SHA) || !manifest.equals(CanonicalJson.readObject(canonical)))
                throw new IllegalStateException("Unrecognized frozen manifest");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object x) { return x instanceof Map ? (Map<String,Object>)x : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked") public static List<Object> list(Object x) { return x instanceof List ? (List<Object>)x : List.of(); }
    public static Map<String,Object> obj(Object... kv) {
        var m=new LinkedHashMap<String,Object>(); for(int i=0;i<kv.length;i+=2)m.put((String)kv[i],kv[i+1]); return m;
    }
    static double number(Object x) { return ((Number)x).doubleValue(); }
    static String str(Object x) { return x==null?null:x.toString(); }
    static LocalDate date(Object x) { return LocalDate.parse(str(x)); }
    static OffsetDateTime time(Object x) { return OffsetDateTime.parse(str(x)); }
    static boolean yes(Map<String,Object> m,String k) { return Boolean.TRUE.equals(m.get(k)); }
    static boolean span(long n,long lo,long hi) { return lo<=n && n<=hi; }
    static double clip(double x,double lo,double hi) { return Math.min(hi,Math.max(lo,x)); }
    static double mean(List<Double> xs) { return xs.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(xs.size()),MathContext.DECIMAL128).doubleValue(); }
    static double sigma(List<Double> xs) {
        var sum=BigDecimal.ZERO; var squares=BigDecimal.ZERO;
        for(double x:xs){var d=new BigDecimal(x);sum=sum.add(d);squares=squares.add(d.multiply(d));}
        var n=BigDecimal.valueOf(xs.size());
        return squares.multiply(n).subtract(sum.multiply(sum)).divide(n.multiply(n),MathContext.DECIMAL128).sqrt(MathContext.DECIMAL128).doubleValue();
    }
    static double percentile(List<Double> xs,double p) {
        var sorted=xs.stream().sorted().toList(); double pos=(xs.size()-1)*p;
        int lo=(int)Math.floor(pos),hi=(int)Math.ceil(pos);
        return sorted.get(lo)+(sorted.get(hi)-sorted.get(lo))*(pos-lo);
    }
    static final Set<String> CURRENT=Set.of("TOTAL_ASSETS","COMMON_EQUITY","CASH","DEBT_CURRENT","DEBT_NONCURRENT","PREFERRED_EQUITY","MINORITY_INTEREST","GOODWILL","INTANGIBLES","CET1_CAPITAL","RISK_WEIGHTED_ASSETS","NONACCRUAL_LOANS","PAST_DUE_90_LOANS","GROSS_LOANS","TOTAL_ADJUSTED_CAPITAL","AUTHORIZED_CONTROL_LEVEL_RBC");
    static final class Calc {
        final Map<String,Object> row; final OffsetDateTime cutoff;
        final Map<String,Object> derived=new LinkedHashMap<>(), reasons=new LinkedHashMap<>();
        Calc(Map<String,Object> r,OffsetDateTime t){row=r;cutoff=t;}
        Double get(String key) {
            if(derived.containsKey(key))return (Double)derived.get(key);
            Object raw=map(row.get("values")).get(key); var e=map(map(row.get("provenance")).get(key)); String reason=null;
            if(raw==null)reason="NOT_REPORTED";
            else if(!(raw instanceof Number) || !Double.isFinite(number(raw)) || list(e.get("source_ids")).isEmpty()
                || list(e.get("source_ids")).stream().anyMatch(s->!(s instanceof String)||((String)s).isEmpty()))reason="FAILED_QUALITY_CHECK";
            else try {
                LocalDate end=date(e.get("period_end"));
                String unit=key.startsWith("OPERATING_ROA_Q")||key.startsWith("CET1_REQUIREMENT_")?"pure":key.equals("SHARES_OUTSTANDING")?"shares":Set.of("OCCUPIED_AREA","AVAILABLE_AREA").contains(key)?"square_feet":"USD";
                if(time(e.get("available_at")).isAfter(cutoff)||time(e.get("observed_at")).isAfter(cutoff)||end.isAfter(cutoff.toLocalDate()))reason="FUTURE_INFORMATION";
                else if(!unit.equals(e.get("unit")))reason="INVALID_UNIT";
                else if(Set.of("RAW_CLOSE","ADJUSTED_CLOSE").contains(key)&&!end.equals(cutoff.toLocalDate()))reason="MISSING_SCORE_DATE_BAR";
                else if(key.startsWith("CET1_REQUIREMENT_")){
                    if(cutoff.toLocalDate().isBefore(date(e.get("effective_from")))||!cutoff.toLocalDate().isBefore(date(e.get("effective_to"))))reason="STALE";
                } else if(key.endsWith("_PRIOR")||key.equals("SAME_STORE_NOI_PRIOR_TTM")){
                    if(!span(ChronoUnit.DAYS.between(end,date(row.get("period_end"))),350,380))reason="INSUFFICIENT_HISTORY";
                } else if(Set.of("AFFO_PRIOR_TTM","DIVIDENDS_DECLARED_PRIOR_TTM").contains(key)){
                    if(!span(ChronoUnit.DAYS.between(end,date(row.get("period_end"))),70,110))reason="INSUFFICIENT_HISTORY";
                } else if((key.endsWith("_TTM")||CURRENT.contains(key))&&!Objects.equals(e.get("period_end"),row.get("period_end")))reason="STALE";
            }catch(RuntimeException ex){reason="FAILED_QUALITY_CHECK";}
            Double value=reason==null?number(raw):null;
            derived.put(key,value); if(reason!=null)reasons.put(key,reason); return value;
        }
        Double combine(String name,Object... terms) {
            double sum=0;String reason=null;
            for(int i=0;i<terms.length;i+=2){String key=(String)terms[i];Double v=get(key);if(v==null){if(reason==null)reason=str(reasons.getOrDefault(key,"NOT_REPORTED"));}else sum+=v*number(terms[i+1]);}
            derived.put(name,reason==null?sum:null);if(reason!=null)reasons.put(name,reason);return (Double)derived.get(name);
        }
        Double ratio(String name,String top,String bottom){return ratio(name,top,bottom,0);}
        Double ratio(String name,String top,String bottom,double offset){
            Double a=get(top),b=get(bottom),value=null;String reason=null;
            if(a==null||b==null)reason=str(reasons.getOrDefault(a==null?top:bottom,"NOT_REPORTED"));
            else if(b<=0)reason="INVALID_DENOMINATOR";else value=a/b+offset;
            derived.put(name,value);if(reason!=null)reasons.put(name,reason);return value;
        }
        void invalid(String key,String reason){derived.put(key,null);reasons.put(key,reason);}
    }
    Double tax(Calc c,List<String> warnings){
        var rates=new ArrayList<Double>();var ends=new ArrayList<LocalDate>();
        for(int i=1;i<=3;i++){
            Double a=c.get("ANNUAL_TAX_"+i),b=c.get("ANNUAL_PRETAX_"+i);
            var evidence=map(c.row.get("provenance"));var x=map(evidence.get("ANNUAL_TAX_"+i));var y=map(evidence.get("ANNUAL_PRETAX_"+i));
            if(a!=null&&b!=null&&b>0&&Objects.equals(x.get("period_end"),y.get("period_end"))){rates.add(a/b);ends.add(date(x.get("period_end")));}
        }
        if(rates.size()==3&&span(ChronoUnit.DAYS.between(ends.get(0),date(c.row.get("period_end"))),0,380)
            &&span(ChronoUnit.DAYS.between(ends.get(1),ends.get(0)),350,380)&&span(ChronoUnit.DAYS.between(ends.get(2),ends.get(1)),350,380))
            return clip(percentile(rates,.5),0,.4);
        for(Object rule:list(map(manifest.get("tax")).get("fallbacks"))){var r=map(rule);
            if(Objects.equals(r.get("jurisdiction"),c.row.get("jurisdiction"))&&!c.cutoff.toLocalDate().isBefore(date(r.get("effective_from")))&&c.cutoff.toLocalDate().isBefore(date(r.get("effective_to")))){
                warnings.add("STATUTORY_TAX_FALLBACK:"+r.get("version"));return number(r.get("rate"));
            }
        }return null;
    }
    Map<String,Object> raw(Map<String,Object> row,OffsetDateTime cutoff){
        var c=new Calc(row,cutoff);var errors=new ArrayList<String>();list(row.get("blocking_reasons")).forEach(x->errors.add(str(x)));
        var warnings=new ArrayList<String>();String profile=str(row.get("profile"));var contract=map(map(manifest.get("profiles")).get(profile));
        if(financial(profile)){
            if(!"PUBLIC_PARENT".equals(row.get("regulatory_scope")))errors.add("MODEL_NOT_SUPPORTED");
            if(profile.equals("BANK")&&!("STANDARDIZED".equals(row.get("capital_approach"))||"ADVANCED".equals(row.get("capital_approach"))))errors.add("MODEL_NOT_SUPPORTED");
        }
        if(contract.isEmpty()||!yes(row,"profile_supported"))errors.add("MODEL_NOT_SUPPORTED");
        for(var pair:List.of(new String[]{"in_universe","NOT_IN_UNIVERSE"},new String[]{"active","INACTIVE_SECURITY"},new String[]{"primary_class","NON_PRIMARY_SHARE_CLASS"}))
            if(!yes(row,pair[0]))errors.add(pair[1]);
        if(!"COMMON_STOCK".equals(row.get("security_type")))errors.add("UNSUPPORTED_SECURITY");
        for(var gate:List.of(new Object[]{"ADJUSTED_CLOSE",5d,"PRICE_BELOW_MINIMUM"},new Object[]{"MEDIAN_DOLLAR_VOLUME",5000000d,"ILLIQUID"})){
            Double value=c.get((String)gate[0]);if(value==null||value<(double)gate[1])errors.add(value==null?"NOT_REPORTED:"+gate[0]:(String)gate[2]);
        }
        if(number(row.getOrDefault("history_count",0))<252)errors.add("INSUFFICIENT_HISTORY");
        if(number(row.getOrDefault("liquidity_count",0))<20)errors.add("INCOMPLETE_LIQUIDITY_WINDOW");
        try{if(!span(ChronoUnit.DAYS.between(date(row.get("filing_date")),cutoff.toLocalDate()),0,180))errors.add("STALE");}catch(RuntimeException e){errors.add("STALE");}
        Double close=c.get("RAW_CLOSE"),shares=c.get("SHARES_OUTSTANDING");
        c.derived.put("market_cap",close!=null&&shares!=null&&close>0&&shares>0?close*shares:null);
        if(c.derived.get("market_cap")==null)errors.add("INVALID_MARKET_CAP");
        c.combine("debt","DEBT_CURRENT",1,"DEBT_NONCURRENT",1);
        c.combine("ev","market_cap",1,"debt",1,"PREFERRED_EQUITY",1,"MINORITY_INTEREST",1,"CASH",-1);
        c.combine("avg_assets","TOTAL_ASSETS",.5,"TOTAL_ASSETS_PRIOR",.5);
        c.ratio("momentum","MOMENTUM_RECENT","MOMENTUM_OLD",-1);
        if(c.get("MOMENTUM_RECENT")!=null&&c.get("MOMENTUM_RECENT")<=0)c.invalid("momentum","FAILED_QUALITY_CHECK");
        if("GENERAL".equals(profile)){
            c.combine("ebit","PRETAX_INCOME_TTM",1,"INTEREST_EXPENSE_TTM",1);
            c.combine("fcf","OPERATING_CASH_FLOW_TTM",1,"CAPEX_TTM",-1);
            if(c.get("CAPEX_TTM")!=null&&c.get("CAPEX_TTM")<0)c.invalid("fcf","FAILED_QUALITY_CHECK");
            c.combine("gross_profit","REVENUE_TTM",1,"COST_OF_REVENUE_TTM",-1);
            for(String s:List.of("","_PRIOR"))c.combine("invested"+s,"COMMON_EQUITY"+s,1,"PREFERRED_EQUITY"+s,1,"DEBT_CURRENT"+s,1,"DEBT_NONCURRENT"+s,1,"MINORITY_INTEREST"+s,1,"CASH"+s,-1);
            c.combine("avg_invested","invested",.5,"invested_PRIOR",.5);
            Double t=tax(c,warnings);c.derived.put("normalized_tax_rate",t);c.derived.put("nopat",t!=null&&c.get("ebit")!=null?c.get("ebit")*(1-t):null);
            c.combine("cash_earnings_top","OPERATING_CASH_FLOW_TTM",1,"NET_INCOME_TTM",-1);
            ratios(c,"ebit_ev ebit ev;fcf_yield fcf market_cap;book_market COMMON_EQUITY market_cap;gross_profitability gross_profit avg_assets;roic nopat avg_invested;cash_earnings cash_earnings_top avg_assets");
            if(c.get("COMMON_EQUITY")!=null&&c.get("COMMON_EQUITY")<=0)c.invalid("book_market","INVALID_DENOMINATOR");
        }else if(financial(profile)){
            for(String s:List.of("","_PRIOR"))c.combine("tce"+s,"COMMON_EQUITY"+s,1,"GOODWILL"+s,-1,"INTANGIBLES"+s,-1);
            c.combine("avg_tce","tce",.5,"tce_PRIOR",.5);
            c.combine("payout","COMMON_DIVIDENDS_TTM",1,"COMMON_REPURCHASES_TTM",1,"STOCK_ISSUANCE_TTM",-1);
            ratios(c,"earnings_yield NET_INCOME_COMMON_TTM market_cap;tangible_book tce market_cap;net_payout payout market_cap");
            if(c.get("tce")==null||c.get("tce")<=0)errors.add("NON_POSITIVE_TANGIBLE_COMMON_EQUITY");
            if("BANK".equals(profile)){
                c.ratio("roa","NET_INCOME_TTM","avg_assets");c.ratio("cet1","CET1_CAPITAL","RISK_WEIGHTED_ASSETS");
                c.combine("bad_loans","NONACCRUAL_LOANS",1,"PAST_DUE_90_LOANS",1);c.ratio("npl_ratio","bad_loans","GROSS_LOANS");c.combine("credit_quality","npl_ratio",-1);
                var components=list(row.get("capital_requirement_components"));
                if(components.isEmpty()||new HashSet<>(components).size()!=components.size()||components.stream().anyMatch(k->!str(k).startsWith("CET1_REQUIREMENT_")))errors.add("MISSING_CET1_REQUIREMENT");
                else{
                    var terms=new ArrayList<Object>();for(Object k:components){terms.add(k);terms.add(1);}
                    c.combine("cet1_requirement",terms.toArray());c.combine("cet1_surplus","cet1",1,"cet1_requirement",-1);
                    if(c.get("cet1_surplus")==null||components.stream().anyMatch(k->c.get(str(k))==null||c.get(str(k))<0))errors.add("MISSING_CET1_REQUIREMENT");
                    else if(c.get("cet1")<c.get("cet1_requirement"))errors.add("CET1_REQUIREMENT_BREACH");
                    else if(c.get("cet1")<c.get("cet1_requirement")+.01)warnings.add("LOW_CET1_SURPLUS");
                }
            }else{
                ratios(c,"operating_roe OPERATING_INCOME_COMMON_TTM avg_tce;operating_roa OPERATING_INCOME_TTM avg_assets");
                c.combine("underwriting_cost","LOSSES_LAE_TTM",1,"UNDERWRITING_EXPENSE_TTM",1);c.ratio("combined_ratio","underwriting_cost","NET_PREMIUMS_TTM");
                c.derived.put("underwriting_margin",c.get("combined_ratio")==null?null:1-c.get("combined_ratio"));
                var quarters=new ArrayList<Double>();for(int q=1;q<=12;q++)quarters.add(c.get("OPERATING_ROA_Q"+q));
                boolean contiguous=!quarters.contains(null);String boundary=str(row.get("period_end"));var evidence=map(row.get("provenance"));
                for(int q=1;q<=12;q++)try{var item=map(evidence.get("OPERATING_ROA_Q"+q));var end=date(item.get("period_end"));var start=date(item.get("period_start"));
                    contiguous &= end.toString().equals(boundary)&&span(ChronoUnit.DAYS.between(start,end)+1,70,110);boundary=start.minusDays(1).toString();
                }catch(RuntimeException e){contiguous=false;}
                c.derived.put("earnings_stability",contiguous?-sigma(quarters):null);if(!contiguous)c.reasons.put("earnings_stability","INSUFFICIENT_HISTORY");
                Double rbc=c.ratio("rbc","TOTAL_ADJUSTED_CAPITAL","AUTHORIZED_CONTROL_LEVEL_RBC");
                if(rbc==null)errors.add("MODEL_NOT_SUPPORTED");else if(rbc<2)errors.add("RBC_REGULATORY_RISK");else if(rbc<3)warnings.add("RBC_TREND_WARNING");
                if(profile.equals("PC_INSURER")&&c.get("combined_ratio")!=null&&c.get("combined_ratio")>1.05)warnings.add("HIGH_COMBINED_RATIO");
            }
        }else if("EQUITY_REIT".equals(profile)){
            c.combine("ffo","NET_INCOME_COMMON_TTM",1,"REAL_ESTATE_DA_TTM",1,"REAL_ESTATE_GAINS_TTM",-1,"REAL_ESTATE_LOSSES_TTM",1,"REAL_ESTATE_IMPAIRMENTS_TTM",1,"FFO_JV_NCI_TTM",1);
            c.combine("affo","ffo",1,"MAINTENANCE_CAPEX_TTM",-1,"TENANT_IMPROVEMENTS_TTM",-1,"LEASING_COMMISSIONS_TTM",-1,"NONCASH_RENT_TTM",-1);
            c.combine("ebitdare","NET_INCOME_TTM",1,"INTEREST_EXPENSE_TTM",1,"TAX_EXPENSE_TTM",1,"DA_TTM",1,"PROPERTY_GAINS_TTM",-1,"PROPERTY_LOSSES_TTM",1,"REAL_ESTATE_IMPAIRMENTS_TTM",1,"EBITDARE_AFFILIATES_TTM",1);
            c.combine("net_debt","debt",1,"CASH",-1);
            ratios(c,"ffo_yield ffo market_cap;affo_yield affo market_cap;ebitdare_yield ebitdare ev;interest_coverage ebitdare CASH_INTEREST_TTM;leverage net_debt ebitdare;affo_payout DIVIDENDS_DECLARED_TTM affo;affo_payout_prior DIVIDENDS_DECLARED_PRIOR_TTM AFFO_PRIOR_TTM;occupancy OCCUPIED_AREA AVAILABLE_AREA");
            c.ratio("noi_growth","SAME_STORE_NOI_TTM","SAME_STORE_NOI_PRIOR_TTM",-1);c.combine("leverage_quality","leverage",-1);
            if(!yes(row,"reit_reconciled")||!yes(row,"same_store_pool_consistent"))errors.add("MODEL_NOT_SUPPORTED");
            if(c.get("ebitdare")==null||c.get("ebitdare")<=0)errors.add("NON_POSITIVE_EBITDARE");
            for(String key:List.of("interest_coverage","leverage")){
                Double v=c.get(key);boolean low=key.equals("interest_coverage");
                if(v==null)errors.add("MISSING_RISK_INPUT:"+key);
                else if(low?v<1.5:v>10)errors.add("REIT_"+key.toUpperCase(Locale.ROOT)+"_BREACH");
                else if(low?v<2:v>8)warnings.add("REIT_"+key.toUpperCase(Locale.ROOT)+"_WARNING");
            }
            var evidence=map(row.get("provenance"));
            if(!Objects.equals(map(evidence.get("AFFO_PRIOR_TTM")).get("period_end"),map(evidence.get("DIVIDENDS_DECLARED_PRIOR_TTM")).get("period_end")))c.derived.put("affo_payout_prior",null);
            Double payout=c.get("affo_payout"),prior=c.get("affo_payout_prior");
            if(payout==null||prior==null)errors.add("MISSING_RISK_INPUT:affo_payout");else if(Math.min(payout,prior)>1.2)errors.add("REIT_PAYOUT_BREACH");else if(payout>1)warnings.add("REIT_PAYOUT_WARNING");
        }
        var metrics=new LinkedHashMap<String,Object>();
        if(!contract.isEmpty()){
            for(String family:List.of("value","quality"))for(Object k:list(contract.get(family))){
                String name=str(k);Double v=c.get(name);metrics.put(name,metric(family,v,v==null?c.reasons.get(name):null));
            }
            metrics.put("momentum",metric("momentum",c.get("momentum"),c.reasons.get("momentum")));
            for(String family:List.of("value","quality"))if(list(contract.get(family)).stream().filter(k->map(metrics.get(str(k))).get("raw")!=null).count()<2)errors.add("INSUFFICIENT_"+family.toUpperCase(Locale.ROOT)+"_COVERAGE");
            if(c.get("momentum")==null)errors.add("MISSING_MOMENTUM_BOUNDARY");
            if(contract.get("anchor")!=null&&c.get(str(contract.get("anchor")))==null)errors.add("MISSING_PROFILE_ANCHOR");
        }
        return obj("instrument_id",UUID.fromString(str(row.get("instrument_id"))).toString(),"profile",profile,"peer_group",row.get("peer_group"),"metrics",metrics,
            "derived",c.derived,"input_reasons",c.reasons,"provenance",row.get("provenance"),"reasons",new ArrayList<>(new TreeSet<>(errors)),"warnings",new ArrayList<>(new TreeSet<>(warnings)),
            "eligible",false,"rank",null,"percentile",null,"composite",null,"families",null,"contributions",null);
    }
    static boolean financial(String profile){return "BANK".equals(profile)||"PC_INSURER".equals(profile)||"LIFE_INSURER".equals(profile);}
    static void ratios(Calc c,String definitions){for(String definition:definitions.split(";")){var p=definition.split(" ");c.ratio(p[0],p[1],p[2]);}}
    static Map<String,Object> metric(String family,Double v,Object reason){
        var m=obj("family",family,"raw",v,"directed",v,"reason",reason,"cohort_count",0);
        for(String k:List.of("winsorized","cohort","lower","upper","mean","sigma","z","clipped_z"))m.put(k,null);return m;
    }
    public Map<String,Object> score(List<Map<String,Object>> rows,String asOf,String candidate){
        var weights=list(map(manifest.get("weights")).get(candidate));if(weights.isEmpty())throw new IllegalArgumentException("Unknown model candidate");
        var ids=new HashSet<String>();for(var row:rows){
            for(Object k:list(map(manifest.get("input_contract")).get("required_metadata")))if(!row.containsKey(str(k)))throw new IllegalArgumentException("Missing prepared metadata: "+k);
            for(String k:List.of("profile_supported","in_universe","active","primary_class"))if(!(row.get(k) instanceof Boolean))throw new IllegalArgumentException("Eligibility flags must be booleans");
            if(!(row.get("values") instanceof Map)||!(row.get("provenance") instanceof Map)||map(row.get("provenance")).values().stream().anyMatch(v->!(v instanceof Map)))throw new IllegalArgumentException("Structured evidence required");
            if(!ids.add(UUID.fromString(str(row.get("instrument_id"))).toString()))throw new IllegalArgumentException("Duplicate instrument");
        }
        var cutoff=time(asOf);var results=rows.stream().sorted(Comparator.comparing(r->UUID.fromString(str(r.get("instrument_id"))).toString())).map(r->raw(r,cutoff)).toList();
        var base=results.stream().filter(r->list(r.get("reasons")).isEmpty()).toList();
        for(var row:base){
            for(var entry:map(row.get("metrics")).entrySet()){
                String name=entry.getKey();var metric=map(entry.getValue());if(metric.get("raw")==null)continue;
                var compatible=base.stream().filter(o->Objects.equals(o.get("profile"),row.get("profile"))&&map(map(o.get("metrics")).get(name)).get("raw")!=null).toList();
                var primary=compatible.stream().filter(o->Objects.equals(o.get("peer_group"),row.get("peer_group"))).toList();
                List<Map<String,Object>> cohort;String label;
                if(row.get("peer_group")!=null&&!str(row.get("peer_group")).isEmpty()&&primary.size()>=10){cohort=primary;label="PRIMARY:"+row.get("peer_group");}
                else if(compatible.size()>=20){cohort=compatible;label="PROFILE:"+row.get("profile");list(row.get("warnings")).add("PEER_FALLBACK:"+name);}
                else{metric.put("reason","NO_COMPATIBLE_COHORT");continue;}
                var values=cohort.stream().map(o->number(map(map(o.get("metrics")).get(name)).get("directed"))).toList();
                double lower=percentile(values,.025),upper=percentile(values,.975);var wins=values.stream().map(v->clip(v,lower,upper)).toList();
                double mean=mean(wins),sigma=sigma(wins),v=clip(number(metric.get("directed")),lower,upper),z=sigma<1e-12?0:(v-mean)/sigma;
                if(sigma<1e-12)metric.put("reason","NO_DISPERSION");
                metric.putAll(obj("winsorized",v,"cohort",label,"cohort_count",cohort.size(),"lower",lower,"upper",upper,"mean",mean,"sigma",sigma,"z",z,"clipped_z",clip(z,-3,3)));
            }
            var families=new LinkedHashMap<String,Object>();var contributions=new LinkedHashMap<String,Object>();int index=0;double total=0;
            for(String family:List.of("value","quality","momentum")){
                var parts=map(row.get("metrics")).values().stream().map(FrozenModel::map).filter(m->family.equals(m.get("family"))).map(m->m.get("clipped_z")).toList();
                if(parts.stream().filter(Objects::nonNull).count()<(family.equals("momentum")?1:2))list(row.get("reasons")).add("INSUFFICIENT_NORMALIZED_"+family.toUpperCase(Locale.ROOT));
                double sum=0;for(Object p:parts)if(p!=null)sum+=number(p);sum/=family.equals("momentum")?1:3;
                families.put(family,sum);double contribution=sum*number(weights.get(index++));contributions.put(family,contribution);total+=contribution;
            }
            row.put("families",families);row.put("contributions",contributions);
            if(list(row.get("reasons")).isEmpty()){row.put("eligible",true);row.put("composite",total);}
        }
        var eligible=results.stream().filter(r->yes(r,"eligible")).sorted(Comparator.<Map<String,Object>>comparingDouble(r->-number(r.get("composite"))).thenComparing(r->str(r.get("instrument_id")))).toList();
        boolean enough=eligible.size()>=2;
        if(enough)for(int i=0;i<eligible.size();i++){
            var row=eligible.get(i);double sum=0,count=0;
            for(int j=0;j<eligible.size();j++)if(number(row.get("composite"))==number(eligible.get(j).get("composite"))){sum+=j+1;count++;}
            row.put("rank",i+1);row.put("percentile",100*(eligible.size()-sum/count)/(eligible.size()-1));
        }
        for(var row:results)for(String k:List.of("warnings","reasons"))row.put(k,list(row.get(k)).stream().map(FrozenModel::str).distinct().sorted().toList());
        return obj("model_version","1.0.0","manifest_sha256",SHA,"candidate",candidate,"as_of",asOf,"expected_count",results.size(),"eligible_count",eligible.size(),"excluded_count",results.size()-eligible.size(),"status",enough?"COMPLETE":"FAILED","reasons",enough?List.of():List.of("INSUFFICIENT_UNIVERSE"),"rows",results);
    }
    public static Object serialize(Object value){
        if(value instanceof BigDecimal b)return b.setScale(6,RoundingMode.HALF_EVEN);
        if(value instanceof Double d){if(!Double.isFinite(d))throw new IllegalArgumentException("Non-finite output");return BigDecimal.valueOf(d).setScale(6,RoundingMode.HALF_EVEN);}
        if(value instanceof Map<?,?> m){var result=new LinkedHashMap<String,Object>();m.forEach((k,v)->result.put(str(k),serialize(v)));return result;}
        if(value instanceof List<?> l)return l.stream().map(FrozenModel::serialize).toList();return value;
    }
}

