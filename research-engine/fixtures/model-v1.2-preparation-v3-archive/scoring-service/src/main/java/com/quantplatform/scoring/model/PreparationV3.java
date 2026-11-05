package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import java.util.*;

/** Versioned accounting derivations. Formula and historical adapter remain unchanged. */
final class PreparationV3 {
    private PreparationV3() {}

    static void enrich(Map<String,Object> row, Map<String,Object> input, String cutoff) {
        var values=map(row.get("values"));var evidence=map(row.get("provenance"));
        var facts=list(input.get("facts")).stream().map(FrozenModel::map)
            .filter(f->!time(f.get("availableAt")).isAfter(time(cutoff))
                && !time(f.get("observedAt")).isAfter(time(cutoff))
                && !date(f.get("end")).isAfter(date(row.get("score_date")))
                && "USD".equals(f.get("unit"))).toList();
        if("GENERAL".equals(row.get("profile"))&&!values.containsKey("COST_OF_REVENUE_TTM")
            &&values.containsKey("REVENUE_TTM")&&values.containsKey("GROSS_PROFIT_TTM")) {
            var revenue=selected(facts,map(evidence.get("REVENUE_TTM")));
            var gross=selected(facts,map(evidence.get("GROSS_PROFIT_TTM")));
            if(!revenue.isEmpty()&&legs(revenue).equals(legs(gross))
                && revenue.stream().allMatch(f->f.get("filingId")!=null)
                && gross.stream().allMatch(f->f.get("filingId")!=null)) {
                var combined=new ArrayList<>(revenue);combined.addAll(gross);
                var derived=CanonicalModelInputs.result(combined);
                derived.put("value",number(values.get("REVENUE_TTM"))-number(values.get("GROSS_PROFIT_TTM")));
                derived.put("derivation","REVENUE_MINUS_GROSS_PROFIT_SAME_FILING_PERIOD_V1");
                CanonicalModelInputs.put(values,evidence,"COST_OF_REVENUE_TTM",derived);
            }
        }
        var diagnostics=new TreeMap<String,Object>();
        for(String metric:CanonicalModelInputs.ADDITIVE) {
            String key=metric+"_TTM";
            if(!values.containsKey(key))diagnostics.put(key,facts.stream().anyMatch(f->metric.equals(f.get("metric")))
                ?"TTM_NO_COMPATIBLE_PERIOD_COMBINATION":"NO_SELECTED_CANONICAL_FACT");
        }
        for(String metric:CanonicalModelInputs.BALANCES)for(String suffix:List.of("","_PRIOR")) {
            String key=metric+suffix;
            if(!values.containsKey(key))diagnostics.put(key,facts.stream().anyMatch(f->metric.equals(f.get("metric")))
                ?"NO_UNIQUE_COMPARABLE_BALANCE":"NO_SELECTED_CANONICAL_FACT");
        }
        row.put("preparation_diagnostics",diagnostics);
    }
    private static List<Map<String,Object>> selected(List<Map<String,Object>> facts,Map<String,Object> evidence) {
        var ids=list(evidence.get("source_ids"));
        var selected=facts.stream().filter(f->ids.contains(f.get("factId"))).toList();
        return selected.size()==ids.size()?selected:List.of();
    }
    private static Set<List<Object>> legs(List<Map<String,Object>> facts) {
        var result=new HashSet<List<Object>>();
        for(var f:facts)result.add(Arrays.asList(f.get("start"),f.get("end"),f.get("filingId"),f.get("unit")));
        return result;
    }
}
