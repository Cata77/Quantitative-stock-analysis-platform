package com.quantplatform.screener.sectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantplatform.screener.sectors.SectorComparisonRepository.Factor;
import com.quantplatform.screener.sectors.SectorComparisonRepository.Member;

class SectorComparisonServiceTest {
    static final SectorComparisonRepository.Run RUN = new SectorComparisonRepository.Run(UUID.randomUUID(),
            LocalDate.parse("2026-10-30"), "1.5.0", UUID.randomUUID());

    static Member member(int n, String profile, Integer rank, String composite, UUID issuer, boolean nonPrimary,
            Factor... factors) {
        return new Member(new UUID(0, n), issuer == null ? new UUID(1, n) : issuer, "S" + n, profile,
                "GENERAL".equals(profile) ? "Financials" : null, rank != null, rank,
                rank == null ? null : BigDecimal.ONE, composite == null ? null : new BigDecimal(composite),
                composite == null ? null : new BigDecimal(composite), "PARTIAL", nonPrimary,
                new ArrayList<>(List.of(factors)));
    }

    static Factor book(String raw, String cohort, int count) {
        return new Factor("book_market", "value", raw == null ? null : new BigDecimal(raw), cohort, count,
                cohort == null ? "NOT_REPORTED" : null);
    }

    @Test void generalAndPriceOnlyMembersAreRankedInSeparateGroups() {
        var rows = List.of(
                member(1, "GENERAL", 3, "0.9", null, false, book("0.10", "PROFILE:GENERAL", 111)),
                member(2, "FINANCIAL", 5, "0.8", null, false),
                member(3, "GENERAL", 7, "0.4", null, false, book("0.30", "PROFILE:GENERAL", 111)),
                member(4, "FINANCIAL", 9, "0.1", null, false),
                member(5, "GENERAL", 11, "0.2", null, false, book(null, null, 0)),
                member(6, "INSURER", null, null, null, false));
        var result = SectorComparisonService.build(RUN, "Financials", rows);
        var byId = result.members();
        assertThat(byId).extracting(SectorComparison.Member::sectorRank).containsExactly(1, 1, 2, 2, 3, null);
        assertThat(byId).extracting(SectorComparison.Member::sectorPopulation).containsExactly(3, 2, 3, 2, 3, null);
        assertThat(byId.get(0).sectorPercentile()).isEqualByComparingTo("100");
        assertThat(byId.get(2).sectorPercentile()).isEqualByComparingTo("50");
        assertThat(byId.get(4).sectorPercentile()).isEqualByComparingTo("0");
        assertThat(byId.get(3).comparisonGroup()).isEqualTo("PRICE_ONLY");
        assertThat(result.population().peerGroupMembers()).isEqualTo(3);
        assertThat(result.groups()).extracting(SectorComparison.Group::eligible).containsExactly(3, 2);
        var book = result.metrics().stream().filter(m -> m.metric().equals("book_market")).findFirst().orElseThrow();
        assertThat(book.observations()).isEqualTo(2);
        assertThat(book.median()).isEqualByComparingTo("0.20");
        assertThat(book.normalizedOnFallback()).isEqualTo(2);
        assertThat(book.notNormalized()).isEqualTo(1);
        assertThat(book.fallbackCohort()).isEqualTo("PROFILE:GENERAL");
        assertThat(book.fallbackCohortSize()).isEqualTo(111);
        assertThat(result.warnings()).contains("FALLBACK_NORMALIZATION:book_market", "SMALL_SAMPLE:GENERAL",
                "SMALL_SAMPLE:PRICE_ONLY", "PRICE_ONLY_MEMBERS_RANKED_SEPARATELY", "NO_OBSERVATIONS:roic");
    }

    @Test void tiesShareTheMeanRankAndASingleMemberHasNoPercentile() {
        var tied = List.of(member(1, "GENERAL", 1, "0.5", null, false), member(2, "GENERAL", 2, "0.50", null, false),
                member(3, "GENERAL", 3, "0.1", null, false));
        var ranks = SectorComparisonService.build(RUN, "X", tied).members();
        assertThat(ranks).extracting(SectorComparison.Member::sectorRank).containsExactly(1, 2, 3);
        assertThat(ranks.get(0).sectorPercentile()).isEqualByComparingTo("75");
        assertThat(ranks.get(1).sectorPercentile()).isEqualByComparingTo("75");
        var single = SectorComparisonService.build(RUN, "X", List.of(member(1, "GENERAL", 4, "1", null, false)));
        assertThat(single.members().get(0).sectorRank()).isEqualTo(1);
        assertThat(single.members().get(0).sectorPercentile()).isNull();
    }

    @Test void medianUsesOneRowPerIssuerAndPrimaryCohortSize() {
        UUID issuer = UUID.randomUUID();
        var rows = List.of(
                member(1, "GENERAL", 1, "1", issuer, false, book("1", "PRIMARY:Media", 12)),
                member(2, "GENERAL", 2, "1", issuer, true, book("100", "PRIMARY:Media", 12)),
                member(3, "GENERAL", 3, "1", null, false, book("3", "PRIMARY:Media", 12)));
        var book = SectorComparisonService.build(RUN, "Media", rows).metrics().stream()
                .filter(m -> m.metric().equals("book_market")).findFirst().orElseThrow();
        assertThat(book.observations()).isEqualTo(2);
        assertThat(book.median()).isEqualByComparingTo("2");
        assertThat(book.normalizedInSector()).isEqualTo(3);
        assertThat(book.primaryCohortSize()).isEqualTo(12);
        assertThat(SectorComparisonService.median(List.of(new BigDecimal("5")))).isEqualByComparingTo("5");
        assertThat(SectorComparisonService.median(List.of())).isNull();
    }
}
