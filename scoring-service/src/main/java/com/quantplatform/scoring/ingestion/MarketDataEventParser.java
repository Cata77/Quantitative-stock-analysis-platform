package com.quantplatform.scoring.ingestion;

import org.springframework.stereotype.Component;

import com.quantplatform.scoring.ingestion.event.MarketDataEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class MarketDataEventParser {

    private final ObjectMapper objectMapper;

    public MarketDataEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public MarketDataEvent parse(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new MarketDataValidationException("market data payload must not be blank");
        }

        try {
            var event = objectMapper.readValue(payload, MarketDataEvent.class);
            if (recordKey == null || !event.symbol().equalsIgnoreCase(recordKey)) {
                throw new MarketDataValidationException(
                        "Kafka record key must match the event symbol");
            }
            return event;
        } catch (MarketDataValidationException exception) {
            throw exception;
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new MarketDataValidationException("invalid market data event", exception);
        }
    }
}
