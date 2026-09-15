package com.quantplatform.marketdata.fundamentals;

import com.quantplatform.ingestion.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** SEC companyfacts contains only standard, entity-wide contexts; custom dimensions are not inferred. */
public class SecCompanyFactsParser {
    public List<FilingFacts> parse(String cik, Map<String,Object> company, List<Map<String,Object>> catalogs,
            LocalDate from, LocalDate through, String sic, Set<String> concepts) {
        if (!String.format("%010d",Long.parseLong(company.get("cik").toString())).equals(cik))
            throw new IllegalArgumentException("SEC companyfacts CIK differs from request");
        var metadata=new TreeMap<String,Map<String,Object>>();
        for(var catalog:catalogs) {
            var accessions=list(catalog,"accessionNumber");
            for(int i=0;i<accessions.size();i++) {
                var row=new TreeMap<String,Object>();
                for(var entry:catalog.entrySet()) if(entry.getValue() instanceof List<?> values) {
                    if(values.size()!=accessions.size())throw new IllegalArgumentException("ragged SEC submissions columns");
                    if(values.get(i)!=null)row.put(entry.getKey(),values.get(i));
                }
                String accession=accessions.get(i).toString();
                var previous=metadata.putIfAbsent(accession,row);
                if(previous!=null && !previous.equals(row))throw new IllegalArgumentException("conflicting submissions metadata");
            }
        }
        var byAccession=new TreeMap<String,List<FilingFacts.Fact>>();
        map(company.get("facts")).forEach((taxonomy,rawConcepts)->map(rawConcepts).forEach((concept,rawDefinition)->{
            if(!concepts.contains(taxonomy+":"+concept))return;
            map(map(rawDefinition).get("units")).forEach((unit,rawValues)->{
                if(!(rawValues instanceof List<?> values))throw new IllegalArgumentException("invalid SEC unit facts");
                for(var raw:values) {
                    var fact=map(raw);
                    String accession=Objects.toString(fact.get("accn"),"");
                    LocalDate filed=LocalDate.parse(fact.get("filed").toString());
                    if(filed.isBefore(from)||filed.isAfter(through))continue;
                    String form=Objects.toString(fact.get("form"),"");
                    if(!Set.of("10-K","10-Q","10-K/A","10-Q/A").contains(form))continue;
                    var meta=metadata.get(accession);
                    if(meta==null)throw new IllegalArgumentException("fact accession absent from submissions history");
                    if(!filed.toString().equals(meta.get("filingDate")) || !form.equals(meta.get("form")))
                        throw new IllegalArgumentException("fact/submission identity mismatch");
                    var context=new TreeMap<>(fact);
                    context.remove("val");context.remove("start");context.remove("end");
                    byAccession.computeIfAbsent(accession,k->new ArrayList<>()).add(new FilingFacts.Fact(taxonomy,concept,
                        fact.get("start")==null?null:LocalDate.parse(fact.get("start").toString()),
                        LocalDate.parse(fact.get("end").toString()),unit,new BigDecimal(fact.get("val").toString()),
                        fact.containsKey("dimensions")?map(fact.get("dimensions")):Map.of(),context));
                }
            });
        }));
        var result=new ArrayList<FilingFacts>();
        for(var entry:metadata.entrySet()) {
            var meta=entry.getValue();
            String form=Objects.toString(meta.get("form"),"");
            LocalDate filed=LocalDate.parse(meta.get("filingDate").toString());
            if(!Set.of("10-K","10-Q","10-K/A","10-Q/A").contains(form)||filed.isBefore(from)||filed.isAfter(through))continue;
            String period=Objects.toString(meta.get("reportDate"),"");
            if(period.isBlank())throw new IllegalArgumentException("required filing fiscal period is absent");
            String amends=null;
            if(form.endsWith("/A")) {
                amends=metadata.entrySet().stream().filter(e->e.getValue().get("form").equals(form.substring(0,form.length()-2))
                        && period.equals(e.getValue().get("reportDate"))
                        && e.getValue().get("acceptanceDateTime").toString().compareTo(meta.get("acceptanceDateTime").toString())<0)
                    .max(Comparator.comparing(e->e.getValue().get("acceptanceDateTime").toString())).map(Map.Entry::getKey).orElse(null);
            }
            var facts=byAccession.getOrDefault(entry.getKey(),List.of()).stream().distinct()
                    .sorted(Comparator.comparing(f->CanonicalJson.MAPPER.writeValueAsString(f))).toList();
            result.add(new FilingFacts(cik,entry.getKey(),form,filed,Instant.parse(meta.get("acceptanceDateTime").toString()),
                    LocalDate.parse(period),amends,Objects.toString(meta.get("primaryDocument"),""),sic,"sec-us-gaap-v1",facts));
        }
        result.sort(Comparator.comparing(FilingFacts::acceptedAt).thenComparing(FilingFacts::accession));
        return List.copyOf(result);
    }
    static Map<String,Object> map(Object value) {
        if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("expected SEC object");
        return CanonicalJson.readObject(CanonicalJson.write(value));
    }
    static List<?> list(Map<String,Object> value,String key) {
        if(!(value.get(key) instanceof List<?> list))throw new IllegalArgumentException("missing SEC array "+key);
        return list;
    }
}
