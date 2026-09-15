package com.quantplatform.ingestion;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public record RegulatoryReport(String cik,String rssd,String legalName,LocalDate periodEnd,Instant availableAt,
        LocalDate effectiveFrom,LocalDate effectiveTo,String scope,String evidenceUri,String mappingVersion,
        Map<String,BigDecimal> facts) {
    public RegulatoryReport {
        if(cik==null||!cik.matches("[0-9]{10}")||rssd==null||!rssd.matches("[0-9]{1,20}")
                ||legalName==null||legalName.isBlank()||evidenceUri==null||!evidenceUri.matches("https://[^\\s]+")
                ||!Set.of("SAME_LEGAL_ENTITY","SUBSIDIARY_ONLY").contains(scope)||!"ffiec-call-v1".equals(mappingVersion))
            throw new IllegalArgumentException("invalid regulatory identity/link/mapping");
        Objects.requireNonNull(periodEnd);Objects.requireNonNull(availableAt);Objects.requireNonNull(effectiveFrom);
        if(effectiveFrom.isAfter(periodEnd)||effectiveTo!=null&&!effectiveTo.isAfter(periodEnd)
                ||availableAt.isBefore(periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
            throw new IllegalArgumentException("invalid regulatory dates");
        facts=Map.copyOf(facts);
        if(facts.isEmpty()||facts.size()>2000)throw new IllegalArgumentException("invalid regulatory fact count");
    }
    public static RegulatoryReport parse(Map<String,Object> data) {
        return CanonicalJson.MAPPER.readValue(CanonicalJson.write(data),RegulatoryReport.class);
    }
}
