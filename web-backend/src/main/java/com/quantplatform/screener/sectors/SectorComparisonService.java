package com.quantplatform.screener.sectors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.quantplatform.screener.sectors.SectorComparison.Group;
import com.quantplatform.screener.sectors.SectorComparison.Member;
import com.quantplatform.screener.sectors.SectorComparison.MemberMetric;
import com.quantplatform.screener.sectors.SectorComparison.MetricSummary;
import com.quantplatform.screener.sectors.SectorComparison.Population;
import com.quantplatform.screener.sectors.SectorComparison.Publication;
import com.quantplatform.screener.sectors.SectorComparison.Rules;
import com.quantplatform.screener.sectors.SectorComparisonRepository.Factor;

/**
 * Sector comparison of a publication (P2b.1). The populations follow the audit in
 * Documentation/evidence/p2b1: GENERAL rows are normalized against the sector, other profiles are
 * scored on momentum only against the whole price universe. The two are never ranked together.
 */
@Service
public class SectorComparisonService {
    public static final int SMALL_SAMPLE = 20;
    static final String GENERAL = "GENERAL";
    static final String PRICE_ONLY = "PRICE_ONLY";
    private static final List<String> METRICS = List.of("book_market", "cash_earnings", "ebit_ev",
            "fcf_yield", "gross_profitability", "momentum", "roic");
    private static final Rules RULES = new Rules(
            "score_lineage.canonical_input.sector of the publication (frozen classification)",
            "Eligible members of the same comparison group, by the published global order (composite, then the model's tie-break); computed on the whole group before any client paging",
            "100 * (N - mean rank of ties) / (N - 1), ties on the unrounded composite; none when N < 2",
            SMALL_SAMPLE,
            "GENERAL eligible members with a raw value, one row per issuer");

    private final SectorComparisonRepository repository;

    public SectorComparisonService(SectorComparisonRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<SectorComparisonRepository.SectorCount> sectors(UUID runId) {
        run(runId);
        return repository.sectors(runId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SectorComparison compare(UUID runId, String sector) {
        var run = run(runId);
        var rows = repository.members(runId, sector);
        if (rows.isEmpty()) throw new SectorNotFoundException("Sector has no members in the publication");
        return build(run, sector, rows);
    }

    private SectorComparisonRepository.Run run(UUID runId) {
        return repository.run(runId).orElseThrow(() -> new SectorNotFoundException("Publication not found"));
    }

    static SectorComparison build(SectorComparisonRepository.Run run, String sector,
            List<SectorComparisonRepository.Member> rows) {
        var profiles = new TreeMap<String, Integer>();
        rows.forEach(r -> profiles.merge(r.profile(), 1, Integer::sum));
        var general = rows.stream().filter(r -> GENERAL.equals(r.profile())).toList();
        var priceOnly = rows.stream().filter(r -> !GENERAL.equals(r.profile())).toList();

        var ranks = new HashMap<UUID, BigDecimal[]>();
        rank(general, ranks);
        rank(priceOnly, ranks);

        var groups = new ArrayList<Group>();
        if (!general.isEmpty()) groups.add(group(GENERAL,
                "GENERAL accounting profile: normalized against this sector (fallback: the GENERAL profile)", general));
        if (!priceOnly.isEmpty()) groups.add(group(PRICE_ONLY,
                "Other accounting profiles: scored on momentum only, normalized against the whole price universe",
                priceOnly));

        var representatives = representatives(general);
        var metrics = new ArrayList<MetricSummary>();
        for (String metric : METRICS) metrics.add(summary(metric, general, representatives));

        var warnings = new ArrayList<String>();
        warnings.add("PUBLISHED_SCORES_NOT_RECALCULATED");
        if (general.size() < rows.size()) warnings.add("PEER_GROUP_SMALLER_THAN_SECTOR");
        if (!priceOnly.isEmpty() && !general.isEmpty()) warnings.add("PRICE_ONLY_MEMBERS_RANKED_SEPARATELY");
        groups.stream().filter(Group::smallSample).forEach(g -> warnings.add("SMALL_SAMPLE:" + g.group()));
        metrics.stream().filter(m -> m.normalizedOnFallback() > 0)
                .forEach(m -> warnings.add("FALLBACK_NORMALIZATION:" + m.metric()));
        metrics.stream().filter(m -> m.observations() == 0 && !general.isEmpty())
                .forEach(m -> warnings.add("NO_OBSERVATIONS:" + m.metric()));

        var members = rows.stream().map(r -> {
            var rank = ranks.get(r.instrumentId());
            String group = GENERAL.equals(r.profile()) ? GENERAL : PRICE_ONLY;
            var metricRows = r.factors().stream().map(SectorComparisonService::memberMetric).toList();
            return new Member(r.instrumentId(), r.symbol(), r.profile(), group, r.eligible(), r.scoreStatus(),
                    r.compositeScore(), r.globalRank(), r.globalPercentile(),
                    rank == null ? null : rank[0].intValueExact(), rank == null ? null : rank[1],
                    rank == null ? null : rank[2].intValueExact(), metricRows);
        }).toList();
        return new SectorComparison(new Publication(run.id(), run.asOfDate(), run.modelVersion(),
                run.classificationVersionId()), sector,
                new Population(rows.size(), (int) rows.stream().filter(SectorComparisonRepository.Member::eligible).count(),
                        profiles, general.size()),
                List.copyOf(groups), List.copyOf(metrics), members, List.copyOf(warnings), RULES);
    }

    /** Rank, percentile and population size per eligible member, within one comparison group. */
    static void rank(List<SectorComparisonRepository.Member> rows, Map<UUID, BigDecimal[]> result) {
        var eligible = rows.stream().filter(r -> r.eligible() && r.globalRank() != null)
                .sorted(Comparator.comparingInt(SectorComparisonRepository.Member::globalRank)).toList();
        int n = eligible.size();
        for (int i = 0; i < n; i++) {
            var composite = eligible.get(i).unroundedComposite();
            BigDecimal percentile = null;
            if (n >= 2) {
                int first = i, last = i;
                while (first > 0 && composite.compareTo(eligible.get(first - 1).unroundedComposite()) == 0) first--;
                while (last < n - 1 && composite.compareTo(eligible.get(last + 1).unroundedComposite()) == 0) last++;
                var meanRank = BigDecimal.valueOf(first + last + 2L).divide(BigDecimal.TWO);
                percentile = BigDecimal.valueOf(100L).multiply(BigDecimal.valueOf(n).subtract(meanRank))
                        .divide(BigDecimal.valueOf(n - 1L), 6, RoundingMode.HALF_UP);
            }
            result.put(eligible.get(i).instrumentId(),
                    new BigDecimal[] {BigDecimal.valueOf(i + 1L), percentile, BigDecimal.valueOf(n)});
        }
    }

    private static Group group(String name, String definition, List<SectorComparisonRepository.Member> rows) {
        int eligible = (int) rows.stream().filter(SectorComparisonRepository.Member::eligible).count();
        return new Group(name, definition, rows.size(), eligible, eligible < SMALL_SAMPLE);
    }

    /** One row per issuer, as the model normalizes: primary classes, or an issuer's only class. */
    static List<SectorComparisonRepository.Member> representatives(List<SectorComparisonRepository.Member> rows) {
        var withPrimary = new HashSet<UUID>();
        rows.stream().filter(r -> !r.nonPrimaryClass()).forEach(r -> withPrimary.add(r.issuerId()));
        return rows.stream().filter(r -> !r.nonPrimaryClass() || !withPrimary.contains(r.issuerId())).toList();
    }

    private static MetricSummary summary(String metric, List<SectorComparisonRepository.Member> general,
            List<SectorComparisonRepository.Member> representatives) {
        var values = new ArrayList<BigDecimal>();
        String family = null;
        for (var r : representatives) {
            var f = r.byMetric().get(metric);
            if (f != null) family = f.family();
            if (r.eligible() && f != null && f.raw() != null) values.add(f.raw());
        }
        int primary = 0, fallback = 0, none = 0;
        Integer primarySize = null, fallbackSize = null;
        String fallbackCohort = null;
        var fallbackLabels = new LinkedHashMap<String, Integer>();
        for (var r : general) {
            var f = r.byMetric().get(metric);
            if (f != null && family == null) family = f.family();
            String kind = normalization(f);
            switch (kind) {
                case "SECTOR" -> {
                    primary++;
                    primarySize = primarySize == null ? f.cohortCount() : Math.max(primarySize, f.cohortCount());
                }
                case "FALLBACK" -> {
                    fallback++;
                    fallbackLabels.merge(f.cohort(), f.cohortCount(), Math::max);
                }
                default -> none++;
            }
        }
        if (!fallbackLabels.isEmpty()) {
            var first = fallbackLabels.entrySet().iterator().next();
            fallbackCohort = first.getKey();
            fallbackSize = first.getValue();
        }
        return new MetricSummary(metric, family, values.size(), median(values), primary, fallback, none,
                primarySize, fallbackCohort, fallbackSize, values.size() < SMALL_SAMPLE);
    }

    static BigDecimal median(List<BigDecimal> values) {
        if (values.isEmpty()) return null;
        var sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle)
                : sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.TWO);
    }

    private static String normalization(Factor f) {
        if (f == null || f.cohort() == null) return "NONE";
        return f.cohort().startsWith("PRIMARY:") ? "SECTOR" : "FALLBACK";
    }

    private static MemberMetric memberMetric(Factor f) {
        return new MemberMetric(f.metric(), f.family(), f.raw(), normalization(f), f.cohort(), f.cohortCount(),
                f.reason());
    }
}
