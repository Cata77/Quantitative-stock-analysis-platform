package com.quantplatform.marketdata.fundamentals;

import java.net.URI;
import java.time.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fundamentals")
public record FundamentalProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("https://data.sec.gov") URI secBaseUrl, @DefaultValue("") String secUserAgent,
        @DefaultValue("200ms") Duration requestSpacing, @DefaultValue("2010-01-01") LocalDate startDate,
        @DefaultValue("40") int maxSubmissionPages, @DefaultValue("30s") Duration timeout,
        @DefaultValue("") String ffiecFile, @DefaultValue("") String ffiecManifest) {
    public FundamentalProperties {
        if (enabled && (secUserAgent == null || !secUserAgent.matches("[^\\r\\n]+[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))
            throw new IllegalArgumentException("SEC requests require an organization/contact User-Agent");
        if (requestSpacing.compareTo(Duration.ofMillis(100))<0 || maxSubmissionPages<1 || maxSubmissionPages>100
                || timeout.isNegative() || timeout.isZero() || startDate.isBefore(LocalDate.of(2009,1,1)))
            throw new IllegalArgumentException("invalid SEC limits/history");
        if (ffiecFile.isBlank() != ffiecManifest.isBlank())
            throw new IllegalArgumentException("FFIEC data and identity manifest must be supplied together");
    }
}
