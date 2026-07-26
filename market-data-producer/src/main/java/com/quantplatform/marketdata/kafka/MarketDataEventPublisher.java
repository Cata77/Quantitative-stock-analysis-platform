package com.quantplatform.marketdata.kafka;

import com.quantplatform.marketdata.event.MarketDataEvent;

public interface MarketDataEventPublisher {

    void publish(MarketDataEvent event);
}
