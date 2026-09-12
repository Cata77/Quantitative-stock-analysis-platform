package com.quantplatform.marketdata.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatedUniverseCsvReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsQuotedNamesAndNormalizesStableIdentifiers() throws Exception {
        var snapshot = temporaryDirectory.resolve("sp500-2026-08-23.csv");
        Files.writeString(snapshot, """
                symbol,legal_name,cik,figi,exchange_mic,security_type,share_class,currency,domicile,sic,primary_liquid_class
                meta,"Meta Platforms, Inc.",1326801,figi-meta,xnas,common_stock,class_a,usd,us,7370,true
                """
        );

        var members = new DatedUniverseCsvReader().read(snapshot);

        assertThat(members).singleElement().satisfies(member -> {
            assertThat(member.symbol()).isEqualTo("META");
            assertThat(member.legalName()).isEqualTo("Meta Platforms, Inc.");
            assertThat(member.cik()).isEqualTo("0001326801");
            assertThat(member.figi()).isEqualTo("FIGI-META");
            assertThat(member.exchangeMic()).isEqualTo("XNAS");
            assertThat(member.primaryLiquidClass()).isTrue();
        });
    }
}
