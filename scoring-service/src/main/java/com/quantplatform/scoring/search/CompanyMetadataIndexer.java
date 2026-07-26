package com.quantplatform.scoring.search;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClient;

import com.quantplatform.scoring.config.ScoringProperties;

@Component
public class CompanyMetadataIndexer {

    private static final Logger LOGGER = LoggerFactory.getLogger(CompanyMetadataIndexer.class);

    private final WebClient webClient;
    private final ScoringProperties properties;

    public CompanyMetadataIndexer(WebClient webClient, ScoringProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void index(CompanyMetadataChanged metadata) {
        if (!properties.elasticsearch().enabled()) {
            return;
        }

        webClient.put()
                .uri(uriBuilder -> uriBuilder
                        .pathSegment(
                                properties.elasticsearch().companyIndex(),
                                "_doc",
                                metadata.symbol())
                        .build())
                .bodyValue(document(metadata))
                .retrieve()
                .toBodilessEntity()
                .subscribe(
                        ignored -> LOGGER.debug(
                                "Indexed company metadata for {}", metadata.symbol()),
                        exception -> LOGGER.warn(
                                "Could not index company metadata for {}: {}",
                                metadata.symbol(),
                                exception.getMessage()));
    }

    private Map<String, Object> document(CompanyMetadataChanged metadata) {
        var document = new LinkedHashMap<String, Object>();
        putIfPresent(document, "symbol", metadata.symbol());
        putIfPresent(document, "name", metadata.name());
        putIfPresent(document, "exchange", metadata.exchange());
        putIfPresent(document, "country", metadata.country());
        putIfPresent(document, "sector", metadata.sector());
        putIfPresent(document, "industry", metadata.industry());
        putIfPresent(document, "updatedAt", metadata.observedAt());
        return document;
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
