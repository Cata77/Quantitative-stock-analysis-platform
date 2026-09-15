package com.quantplatform.marketdata.fundamentals;

import com.quantplatform.ingestion.CanonicalJson;
import com.quantplatform.marketdata.provider.alpaca.ProviderRequestGate;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class SecDataClient {
    private final WebClient client;
    private final FundamentalProperties properties;
    private final ProviderRequestGate gate;
    private final Clock clock;
    public SecDataClient(WebClient.Builder builder,FundamentalProperties properties,ProviderRequestGate gate,Clock clock) {
        this.properties=properties;this.gate=gate;this.clock=clock;
        client=builder.clone().defaultHeader("User-Agent",properties.secUserAgent())
                .codecs(c->c.defaultCodecs().maxInMemorySize(32*1024*1024)).build();
    }
    public Bundle fetch(String cik,LocalDate from,LocalDate through) {
        if(!cik.matches("[0-9]{10}")||through.isBefore(from))throw new IllegalArgumentException("invalid SEC request");
        var documents=new LinkedHashMap<String,String>();
        String submissions="/submissions/CIK"+cik+".json";
        var root=read(submissions,documents);
        if(!String.format("%010d",Long.parseLong(root.get("cik").toString())).equals(cik))
            throw new IllegalArgumentException("submissions CIK differs from request");
        var filings=SecCompanyFactsParser.map(root.get("filings"));
        var catalogs=new ArrayList<Map<String,Object>>();
        catalogs.add(SecCompanyFactsParser.map(filings.get("recent")));
        var names=new HashSet<String>();
        for(var raw:SecCompanyFactsParser.list(filings,"files")) {
            var file=SecCompanyFactsParser.map(raw);
            if(LocalDate.parse(file.get("filingTo").toString()).isBefore(from)
                    || LocalDate.parse(file.get("filingFrom").toString()).isAfter(through))continue;
            String name=file.get("name").toString();
            if(!name.matches("CIK"+cik+"-submissions-[0-9]+\\.json")||!names.add(name)
                    ||names.size()>properties.maxSubmissionPages())throw new IllegalArgumentException("invalid/repeated/excessive submissions page");
            catalogs.add(read("/submissions/"+name,documents));
        }
        var facts=read("/api/xbrl/companyfacts/CIK"+cik+".json",documents);
        return new Bundle(facts,catalogs,Objects.toString(root.get("sic"),""),documents,clock.instant());
    }
    private Map<String,Object> read(String path,Map<String,String> documents) {
        gate.awaitTurn("sec",properties.requestSpacing());
        URI uri=URI.create(properties.secBaseUrl().toString().replaceAll("/$","")+path);
        String body=client.get().uri(uri).exchangeToMono(response->{
            if(response.statusCode().value()==429 || response.statusCode().value()==403) {
                Duration delay=Duration.ofMinutes(10);
                String header=response.headers().asHttpHeaders().getFirst("Retry-After");
                if(header!=null)try {delay=Duration.ofSeconds(Math.max(1,Math.min(3600,Long.parseLong(header))));}
                    catch(NumberFormatException invalid) {
                        try {delay=Duration.between(clock.instant(),ZonedDateTime.parse(header,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                            if(delay.isNegative()||delay.isZero())delay=Duration.ofSeconds(1);
                            if(delay.compareTo(Duration.ofHours(1))>0)delay=Duration.ofHours(1);
                        } catch(RuntimeException ignored) {delay=Duration.ofMinutes(10);}
                    }
                gate.postpone("sec",delay);
                return response.releaseBody().then(Mono.error(new ProviderRequestGate.DeferredRequest(delay)));
            }
            if(response.statusCode().isError())return response.createException().flatMap(Mono::error);
            return response.bodyToMono(String.class);
        }).block(properties.timeout());
        documents.put(uri.toString(),Objects.requireNonNull(body,"empty SEC response"));
        return CanonicalJson.readObject(body);
    }
    public record Bundle(Map<String,Object> companyFacts,List<Map<String,Object>> catalogs,String sic,
            Map<String,String> documents,Instant retrievedAt) {}
}
