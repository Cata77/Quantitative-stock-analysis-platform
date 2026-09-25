package com.quantplatform.scoring.ingestion;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.quantplatform.marketdata.operations.*;
import com.quantplatform.marketdata.config.*;
import com.quantplatform.marketdata.provider.alpaca.*;
import com.quantplatform.marketdata.provider.alphavantage.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class TradingCalendarTimingIntegrationTest extends DurableDeliveryFixture {
    @Test void futureHolidayAndOpeningAreRecordedBeforeMonthEndWithoutBackdating() {
        var requests=new ArrayList<ClientRequest>();
        var builder=WebClient.builder().exchangeFunction(request->{requests.add(request);return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body("""
            [{"date":"2026-09-30","open":"09:30","close":"16:00"},
             {"date":"2026-10-01","open":"09:30","close":"16:00"},
             {"date":"2026-10-02","open":"09:30","close":"16:00"},
             {"date":"2026-10-05","open":"09:30","close":"16:00"}]
            """).build());});
        var settings=settings();
        var market=market();
        var loader=new TradingCalendarLoader(source,tx,builder,new AlpacaProperties(URI.create("https://data.alpaca.markets"),"","","sip"),market,settings,clock("2026-09-29T12:00:00Z"));
        loader.ensure(LocalDate.parse("2026-09-30"),LocalDate.parse("2026-10-05"));
        loader.ensure(LocalDate.parse("2026-09-30"),LocalDate.parse("2026-10-05"));
        assertThat(requests).hasSize(1);
        assertThat(count("reference.trading_sessions")).isEqualTo(24);
        assertThat(jdbc.sql("SELECT count(*) FROM reference.trading_sessions WHERE holiday").query(Integer.class).single()).isEqualTo(8);
        assertThat(jdbc.sql("SELECT count(*) FROM reference.trading_sessions WHERE observed_at='2026-09-29T12:00:00Z' AND available_at=observed_at").query(Integer.class).single()).isEqualTo(24);
    }
    @Test void reconciliationRequestsFullFutureMonthBeforeCollectingPrices() {
        var loader=mock(TradingCalendarLoader.class);
        var coordinator=new IngestionCoordinator(source,tx,jobs,loader,mock(AlpacaStockMarketClient.class),mock(AlphaVantageFundamentalClient.class),market(),new AlpacaProperties(URI.create("https://data.alpaca.markets"),"","","sip"),settings(),clock("2026-09-30T12:00:00Z"),mock(BulkDailyCollector.class),new DailyPriceProperties("sip",100,10000,Duration.ofMillis(350),Duration.ofMinutes(16),true,false,14));
        coordinator.reconcile();
        verify(loader).ensure(LocalDate.parse("2026-09-01"),LocalDate.parse("2026-10-31"));
    }
    IngestionProperties settings(){return new IngestionProperties("catch-up-and-serve",LocalDate.parse("2026-09-01"),null,null,null,false,100,3,Duration.ofMinutes(2),Duration.ofSeconds(5),Duration.ofSeconds(10),"https://paper-api.alpaca.markets",group);}
    MarketDataProperties market(){return new MarketDataProperties(true,List.of("IGNORED"),topic,1,Duration.ofSeconds(10),Duration.ofSeconds(10),true,false,365,"1Day",false);}
}
