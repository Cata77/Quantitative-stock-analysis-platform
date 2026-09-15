package com.quantplatform.ingestion;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record FilingFacts(String cik, String accession, String form, LocalDate filedDate,
        Instant acceptedAt, LocalDate fiscalPeriodEnd, String amendsAccession, String primaryDocument,
        String sic, String mappingVersion, List<Fact> facts) {
    public FilingFacts {
        if (cik == null || !cik.matches("[0-9]{10}") || accession == null || !accession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")
                || !Set.of("10-K","10-Q","10-K/A","10-Q/A").contains(form)
                || !"sec-us-gaap-v1".equals(mappingVersion))
            throw new IllegalArgumentException("unsupported filing identity, form or mapping");
        Objects.requireNonNull(filedDate); Objects.requireNonNull(acceptedAt); Objects.requireNonNull(fiscalPeriodEnd);
        if (fiscalPeriodEnd.isAfter(filedDate) || primaryDocument == null || primaryDocument.isBlank()
                || (amendsAccession != null && (!amendsAccession.matches("[0-9]{10}-[0-9]{2}-[0-9]{6}")
                    || amendsAccession.equals(accession) || !form.endsWith("/A"))))
            throw new IllegalArgumentException("invalid filing dates/document/amendment");
        facts = List.copyOf(facts);
        if (facts.size() > 5000) throw new IllegalArgumentException("filing fact limit exceeded");
    }
    public record Fact(String taxonomy, String concept, LocalDate start, LocalDate end,
            String unit, BigDecimal value, Map<String,Object> dimensions, Map<String,Object> context) {
        public Fact {
            if (taxonomy == null || !taxonomy.matches("[a-zA-Z0-9_-]{1,40}") || concept == null
                    || !concept.matches("[a-zA-Z0-9_-]{1,160}") || unit == null || unit.isBlank() || unit.length()>20)
                throw new IllegalArgumentException("invalid fact concept/unit");
            Objects.requireNonNull(end); Objects.requireNonNull(value);
            if (start != null && start.isAfter(end)) throw new IllegalArgumentException("invalid fact interval");
            dimensions=Map.copyOf(dimensions); context=Map.copyOf(context);
        }
    }
}
