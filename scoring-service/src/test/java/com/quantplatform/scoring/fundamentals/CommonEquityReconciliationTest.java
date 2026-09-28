package com.quantplatform.scoring.fundamentals;
import com.quantplatform.ingestion.*;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class CommonEquityReconciliationTest {
    FilingFacts.Fact fact(String concept,String value,String unit) {
        return new FilingFacts.Fact("us-gaap",concept,null,LocalDate.parse("2025-12-31"),unit,new BigDecimal(value),Map.of(),Map.of());
    }
    FilingFacts filing(List<FilingFacts.Fact> facts,String mapping) {
        return new FilingFacts("0000000001","0000000001-26-000001","10-K",LocalDate.parse("2026-02-01"),
            Instant.parse("2026-02-01T12:00:00Z"),LocalDate.parse("2025-12-31"),null,"test.htm","3571",mapping,facts);
    }
    List<FilingFacts.Fact> base() {return new ArrayList<>(List.of(fact("StockholdersEquity","100","USD"),
        fact("PreferredStockValue","0","USD"),fact("PreferredStockSharesOutstanding","0","shares")));}
    @Test void derivesOnlyUnderVersionTwoAndRetainsAllSourceHashes() {
        assertThat(CommonEquityReconciliation.derive(filing(base(),"sec-us-gaap-v1"))).isEmpty();
        var derived=CommonEquityReconciliation.derive(filing(base(),"sec-us-gaap-v2"));
        assertThat(derived).hasSize(1);assertThat(derived.getFirst().value()).isEqualByComparingTo("100");
        assertThat((List<?>)derived.getFirst().context().get("sourceFactHashes")).hasSize(3);
    }
    @Test void absentNonzeroConflictingAndDirectEvidenceNeverGetsSubstituted() {
        for(int remove=0;remove<3;remove++){var facts=base();facts.remove(remove);assertThat(CommonEquityReconciliation.derive(filing(facts,"sec-us-gaap-v2"))).isEmpty();}
        for(var bad:List.of(fact("PreferredStockSharesIssued","1","shares"),fact("PreferredStockValue","1","USD"),
                fact("StockholdersEquity","101","USD"),fact("CommonStockholdersEquity","90","USD"),
                fact("PreferredStockSharesOutstanding","0","USD"))) {
            var facts=base();facts.add(bad);assertThat(CommonEquityReconciliation.derive(filing(facts,"sec-us-gaap-v2"))).isEmpty();
        }
    }
    @Test void differentDatesDimensionsAndDurationsCannotSupplyTheEvidence() {
        var original=base().getLast();
        for(var bad:List.of(new FilingFacts.Fact("us-gaap",original.concept(),null,original.end().minusDays(1),"shares",BigDecimal.ZERO,Map.of(),Map.of()),
            new FilingFacts.Fact("us-gaap",original.concept(),null,original.end(),"shares",BigDecimal.ZERO,Map.of("class","A"),Map.of()),
            new FilingFacts.Fact("us-gaap",original.concept(),original.end().minusDays(1),original.end(),"shares",BigDecimal.ZERO,Map.of(),Map.of()))) {
            var facts=base();facts.set(2,bad);assertThat(CommonEquityReconciliation.derive(filing(facts,"sec-us-gaap-v2"))).isEmpty();
        }
    }
}
