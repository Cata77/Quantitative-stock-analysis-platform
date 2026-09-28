package com.quantplatform.scoring.fundamentals;

import com.quantplatform.ingestion.*;
import java.time.LocalDate;
import java.util.*;

/** Conservative entity-wide reconciliation; never treats absent preferred facts as zero. */
public final class CommonEquityReconciliation {
    private CommonEquityReconciliation() {}
    public static final String VERSION = "common-equity-zero-preferred-v1";
    public static List<FilingFacts.Fact> derive(FilingFacts filing) {
        if (!filing.mappingVersion().equals("sec-us-gaap-v2")) return List.of();
        var result = new ArrayList<FilingFacts.Fact>();
        var dates = new TreeSet<LocalDate>();
        filing.facts().forEach(f -> dates.add(f.end()));
        for (var date : dates) {
            if (date.isAfter(filing.filedDate())) continue;
            var facts = filing.facts().stream().filter(f -> f.end().equals(date) && f.taxonomy().equals("us-gaap")).toList();
            // A direct common-equity disclosure, even invalid/conflicting, must not be overridden.
            if (facts.stream().anyMatch(f -> f.concept().equals("CommonStockholdersEquity"))) continue;
            var equity = facts.stream().filter(f -> f.concept().equals("StockholdersEquity")).toList();
            var preferred = facts.stream().filter(f -> f.concept().equals("PreferredStockValue")).toList();
            var shares = facts.stream().filter(f -> Set.of("PreferredStockSharesIssued", "PreferredStockSharesOutstanding").contains(f.concept())).toList();
            if (!valid(equity, "USD") || !valid(preferred, "USD") || !valid(shares, "shares")) continue;
            if (preferred.stream().anyMatch(f -> f.value().signum()!=0) || shares.stream().anyMatch(f -> f.value().signum()!=0)) continue;
            // Any other reported nonzero preferred balance/share disclosure makes this narrow rule ambiguous.
            if (facts.stream().anyMatch(f -> f.start()==null && Set.of("USD","shares").contains(f.unit()) && f.concept().startsWith("PreferredStock")
                    && !f.concept().contains("Authorized") && f.value().signum()!=0)) continue;
            var sources = new ArrayList<FilingFacts.Fact>(); sources.addAll(equity); sources.addAll(preferred); sources.addAll(shares);
            var hashes = sources.stream().map(f -> CanonicalJson.sha256(CanonicalJson.MAPPER.writeValueAsString(f))).distinct().sorted().toList();
            result.add(new FilingFacts.Fact("quant-reconciled", "CommonEquityZeroPreferredV1", null, date,
                "USD", equity.getFirst().value(), Map.of(), Map.of("reconciliationVersion", VERSION,
                "accession", filing.accession(), "sourceFactHashes", hashes)));
        }
        return List.copyOf(result);
    }
    private static boolean valid(List<FilingFacts.Fact> facts, String unit) {
        return !facts.isEmpty() && facts.stream().allMatch(f -> f.start()==null && f.unit().equals(unit) && f.dimensions().isEmpty())
            && facts.stream().map(f -> f.value().stripTrailingZeros()).distinct().count()==1;
    }
}
