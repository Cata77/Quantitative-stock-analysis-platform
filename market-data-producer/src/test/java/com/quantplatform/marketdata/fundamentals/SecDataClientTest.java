package com.quantplatform.marketdata.fundamentals;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.quantplatform.ingestion.*;
import com.quantplatform.marketdata.provider.alpaca.ProviderRequestGate;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;

class SecDataClientTest {
    final ProviderRequestGate gate=mock(ProviderRequestGate.class);
    final List<ClientRequest> requests=new ArrayList<>();
    static final String CIK="0000320193", ACCESSION="0000320193-23-000106";
    static FundamentalProperties properties() {
        return new FundamentalProperties(true,URI.create("https://data.sec.test"),"Quant Research developer@example.com",
            Duration.ofMillis(200),LocalDate.of(2010,1,1),40,Duration.ofSeconds(5),"","");
    }
    static Map<String,Object> catalog() {
        return Map.of("accessionNumber",List.of(ACCESSION),"filingDate",List.of("2023-11-03"),"reportDate",List.of("2023-09-30"),
            "acceptanceDateTime",List.of("2023-11-03T12:00:00Z"),"form",List.of("10-K"),"primaryDocument",List.of("aapl-20230930.htm"));
    }
    static Map<String,Object> company() {
        return Map.of("cik",320193,"facts",Map.of("us-gaap",Map.of("Revenues",Map.of("units",Map.of("USD",List.of(
            Map.of("accn",ACCESSION,"filed","2023-11-03","form","10-K","start","2022-09-25","end","2023-09-30","val",383285000000L)))))));
    }
    @Test void followsSubmissionArchivesPreservesSourceDocumentsAndParsesKnownAnnualValue() {
        var client=client(request->{
            if(request.url().getPath().endsWith("-submissions-001.json"))return json(catalog());
            if(request.url().getPath().contains("companyfacts"))return json(company());
            var empty=new TreeMap<String,Object>();catalog().keySet().forEach(key->empty.put(key,List.of()));
            return json(Map.of("cik","320193","sic","3571","filings",Map.of("recent",empty,"files",List.of(
                Map.of("name","CIK"+CIK+"-submissions-001.json","filingFrom","2023-01-01","filingTo","2023-12-31")))));
        });
        var bundle=client.fetch(CIK,LocalDate.of(2023,1,1),LocalDate.of(2023,12,31));
        var filings=new SecCompanyFactsParser().parse(CIK,bundle.companyFacts(),bundle.catalogs(),
            LocalDate.of(2023,1,1),LocalDate.of(2023,12,31),bundle.sic(),Set.of("us-gaap:Revenues"));
        assertThat(filings).hasSize(1);
        assertThat(filings.getFirst().facts().getFirst().value()).isEqualByComparingTo("383285000000");
        assertThat(filings.getFirst().accession()).isEqualTo(ACCESSION);
        assertThat(bundle.documents()).hasSize(3);
        assertThat(requests.getFirst().headers().getFirst("User-Agent")).contains("developer@example.com");
        verify(gate,times(3)).awaitTurn("sec",Duration.ofMillis(200));
    }
    @Test void rejectsDifferentIssuerAndUnsafeArchivePath() {
        var wrong=client(request->json(Map.of("cik","1")));
        assertThatThrownBy(()->wrong.fetch(CIK,LocalDate.of(2023,1,1),LocalDate.of(2023,12,31))).hasMessageContaining("CIK");
        var unsafe=client(request->json(Map.of("cik","320193","filings",Map.of("recent",catalog(),"files",List.of(
            Map.of("name","../other.json","filingFrom","2023-01-01","filingTo","2023-12-31"))))));
        assertThatThrownBy(()->unsafe.fetch(CIK,LocalDate.of(2023,1,1),LocalDate.of(2023,12,31))).hasMessageContaining("page");
    }
    @Test void quotaResponseDefersTheSharedProviderBudget() {
        var client=client(request->Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After","120").build()));
        assertThatThrownBy(()->client.fetch(CIK,LocalDate.of(2023,1,1),LocalDate.of(2023,12,31)))
            .isInstanceOf(ProviderRequestGate.DeferredRequest.class);
        verify(gate).postpone("sec",Duration.ofSeconds(120));
    }
    @Test void raggedColumnsAndOrphanFactsFailInsteadOfLosingLineage() {
        var parser=new SecCompanyFactsParser();
        var bad=new TreeMap<>(catalog());bad.put("form",List.of());
        assertThatThrownBy(()->parser.parse(CIK,company(),List.of(bad),LocalDate.of(2023,1,1),
            LocalDate.of(2023,12,31),"3571",Set.of("us-gaap:Revenues"))).hasMessageContaining("ragged");
        assertThatThrownBy(()->parser.parse(CIK,company(),List.of(Map.of("accessionNumber",List.of())),LocalDate.of(2023,1,1),
            LocalDate.of(2023,12,31),"3571",Set.of("us-gaap:Revenues"))).hasMessageContaining("absent");
    }
    @Test void amendmentUsesSeparateAccessionAndPreservesNegativeValues() {
        var original=catalog();
        var amendment=new TreeMap<String,Object>();
        original.forEach((key,value)->amendment.put(key,new ArrayList<>((List<?>)value)));
        amendment.put("accessionNumber",List.of("0000320193-23-000107"));
        amendment.put("form",List.of("10-K/A"));amendment.put("filingDate",List.of("2023-11-04"));
        amendment.put("acceptanceDateTime",List.of("2023-11-04T12:00:00Z"));
        var filings=new SecCompanyFactsParser().parse(CIK,company(),List.of(original,amendment),LocalDate.of(2023,1,1),
            LocalDate.of(2023,12,31),"3571",Set.of("us-gaap:Revenues"));
        assertThat(filings).hasSize(2);
        assertThat(filings.getLast().amendsAccession()).isEqualTo(ACCESSION);
        assertThat(filings.getLast().facts()).isEmpty();
    }
    @Test void enabledClientRequiresAnIdentifiableContact() {
        assertThatThrownBy(()->new FundamentalProperties(true,URI.create("https://data.sec.gov"),"",
            Duration.ofMillis(200),LocalDate.of(2010,1,1),40,Duration.ofSeconds(30),"",""))
            .hasMessageContaining("User-Agent");
    }
    private SecDataClient client(ExchangeFunction response) {
        return new SecDataClient(WebClient.builder().exchangeFunction(request->{requests.add(request);return response.exchange(request);}),
            properties(),gate,Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"),ZoneOffset.UTC));
    }
    private Mono<ClientResponse> json(Object value) {
        return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
            .body(CanonicalJson.write(value)).build());
    }
}
