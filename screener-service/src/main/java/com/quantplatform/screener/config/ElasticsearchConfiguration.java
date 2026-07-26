package com.quantplatform.screener.config;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class ElasticsearchConfiguration {

    @Bean
    RestClient screenerElasticsearchRestClient(ScreenerProperties properties) {
        return RestClient.builder(HttpHost.create(properties.elasticsearch().url())).build();
    }

    @Bean(destroyMethod = "close")
    ElasticsearchTransport screenerElasticsearchTransport(RestClient restClient) {
        var objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return new RestClientTransport(restClient, new JacksonJsonpMapper(objectMapper));
    }

    @Bean
    ElasticsearchClient screenerElasticsearchClient(
            ElasticsearchTransport transport
    ) {
        return new ElasticsearchClient(transport);
    }
}
