package com.quantplatform.scoring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration(proxyBeanMethods = false)
class ElasticsearchConfiguration {

    @Bean
    WebClient scoringElasticsearchWebClient(
            WebClient.Builder builder,
            ScoringProperties properties
    ) {
        return builder.baseUrl(properties.elasticsearch().baseUrl()).build();
    }
}
