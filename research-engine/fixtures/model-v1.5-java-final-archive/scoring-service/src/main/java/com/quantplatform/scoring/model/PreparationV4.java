package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import java.util.*;

/** Explicit parent equity less preferred equity; never assumes an unreported balance is zero. */
final class PreparationV4 {
    private PreparationV4() {}

    static void enrich(Map<String,Object> row, Map<String,Object> input, String cutoff) {
        PreparationV3.enrich(row, input, cutoff);
        if (!"GENERAL".equals(row.get("profile"))) return;
        var values = map(row.get("values"));
        var provenance = map(row.get("provenance"));
        var facts = list(input.get("facts")).stream().map(FrozenModel::map)
            .filter(f -> !time(f.get("availableAt")).isAfter(time(cutoff))
                && !time(f.get("observedAt")).isAfter(time(cutoff))
                && !date(f.get("end")).isAfter(date(row.get("score_date")))
                && "USD".equals(f.get("unit"))).toList();
        for (String suffix : List.of("", "_PRIOR")) {
            String key = "COMMON_EQUITY" + suffix;
            var anchor = map(provenance.get("TOTAL_ASSETS" + suffix));
            if (values.containsKey(key) || anchor.isEmpty()) continue;
            var end = anchor.get("period_end");
            if (facts.stream().anyMatch(f -> "COMMON_EQUITY".equals(f.get("metric"))
                && Objects.equals(end, f.get("end")))) continue;
            var totals = facts.stream().filter(f -> "STOCKHOLDERS_EQUITY".equals(f.get("metric"))
                && Objects.equals(end, f.get("end")) && f.get("start") == null).toList();
            var preferred = facts.stream().filter(f -> "PREFERRED_EQUITY".equals(f.get("metric"))
                && Objects.equals(end, f.get("end")) && f.get("start") == null).toList();
            if (totals.size() != 1 || preferred.size() != 1) continue;
            var total = totals.getFirst();
            var pref = preferred.getFirst();
            if (total.get("filingId") == null || str(total.get("filingId")).isEmpty()
                || !Objects.equals(total.get("filingId"), pref.get("filingId"))
                || number(pref.get("value")) < 0) continue;
            var derived = CanonicalModelInputs.result(List.of(total, pref), 1, -1);
            derived.put("derivation", "PARENT_EQUITY_MINUS_PREFERRED_SAME_FILING_PERIOD_V1");
            CanonicalModelInputs.put(values, provenance, key, derived);
            map(row.get("preparation_diagnostics")).remove(key);
        }
    }
}
