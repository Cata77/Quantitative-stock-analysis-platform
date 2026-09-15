package com.quantplatform.marketdata.fundamentals;

import static org.assertj.core.api.Assertions.*;
import com.quantplatform.ingestion.CanonicalJson;
import java.util.*;
import org.junit.jupiter.api.Test;

class FfiecBulkCollectorTest {
    final String raw="IDRSSD\tRCOAP793\tRCOWP793\nRSSD\tCET1 column A\tCET1 column B\n852218\t0.15\t0.14\n";
    String manifest(String data) {
        return CanonicalJson.write(Map.of("sha256",CanonicalJson.sha256(data),
            "sourceUri","https://cdr.ffiec.gov/public/PWS/DownloadBulkData.aspx","periodEnd","2026-06-30",
            "availableAt","2026-08-15T12:00:00Z","entities",List.of(Map.of(
                "cik","0000019617","rssd","852218","legalName","JPMorgan Chase Bank, National Association",
                "effectiveFrom","2026-01-01","scope","SUBSIDIARY_ONLY",
                "evidenceUri","https://jpmorganchaseco.gcs-web.com/ir/sec-other-filings/other-us-regulatory-filings/"))));
    }
    @Test void importsReportedRatiosWithoutPercentOrEntityConflation() {
        var reports=FfiecBulkCollector.parse(raw,manifest(raw));
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().facts().get("RCOAP793")).isEqualByComparingTo("0.15");
        assertThat(reports.getFirst().facts().get("RCOWP793")).isEqualByComparingTo("0.14");
        assertThat(reports.getFirst().scope()).isEqualTo("SUBSIDIARY_ONLY");
    }
    @Test void refusesChecksumMismatchAndDuplicateInstitutions() {
        assertThatThrownBy(()->FfiecBulkCollector.parse(raw+" ",manifest(raw))).hasMessageContaining("checksum");
        String duplicate=raw+"852218\t0.16\t0.15\n";
        assertThatThrownBy(()->FfiecBulkCollector.parse(duplicate,manifest(duplicate))).hasMessageContaining("duplicate");
    }
    @Test void missingAndNonNumericValuesAreNotFabricated() {
        String missing="IDRSSD\tRCOAP793\n852218\t\n";
        assertThatThrownBy(()->FfiecBulkCollector.parse(missing,manifest(missing))).hasMessageContaining("no mapped");
        String invalid="IDRSSD\tRCOAP793\n852218\tN/A\n";
        assertThatThrownBy(()->FfiecBulkCollector.parse(invalid,manifest(invalid))).isInstanceOf(NumberFormatException.class);
    }
}
