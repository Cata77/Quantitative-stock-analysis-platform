package com.quantplatform.marketdata.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.quantplatform.marketdata.support.TestProperties;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

class DailyPricePropertiesTest {
    @Test
    void existingConfigurationStillCollectsBothSeries() {
        assertThat(bind(Map.of()).adjustmentModes()).containsExactly("RAW", "SPLIT_DIVIDEND");
    }

    @Test
    void adjustedRecoveryDoesNotScheduleRawAndRawRecoveryRemainsSupported() {
        assertThat(bind(Map.of("ingestion.prices.raw-enabled", "false")).adjustmentModes())
                .containsExactly("SPLIT_DIVIDEND");
        assertThat(bind(Map.of("ingestion.prices.adjusted-enabled", "false")).adjustmentModes())
                .containsExactly("RAW");
    }

    @Test
    void emptyPriceSelectionFailsBeforeDatabaseOrProviderWork() {
        var prices = bind(Map.of("ingestion.prices.raw-enabled", "false",
                "ingestion.prices.adjusted-enabled", "false"));
        var source = mock(DataSource.class);
        var store = mock(IngestionRunStore.class);
        var calendar = mock(TradingCalendarLoader.class);
        var properties = new Binder(new MapConfigurationPropertySource())
                .bindOrCreate("ingestion", Bindable.of(IngestionProperties.class));
        var coordinator = new IngestionCoordinator(source, mock(PlatformTransactionManager.class),
                store, calendar, null, null,
                TestProperties.properties(true, List.of(), true, false, false), null,
                properties, Clock.systemUTC(), null, prices);

        assertThatThrownBy(coordinator::reconcile).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("raw-enabled or adjusted-enabled");
        verifyNoInteractions(source, store, calendar);
    }

    private DailyPriceProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("ingestion.prices", Bindable.of(DailyPriceProperties.class));
    }
}
