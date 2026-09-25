package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import static org.assertj.core.api.Assertions.*;
import com.quantplatform.ingestion.CanonicalJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CanonicalModelInputsTest {
    @Test void livePythonAdapterAndPeriodParity() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        String python=System.getenv().getOrDefault("PARITY_PYTHON",root.resolve(".venv/Scripts/python.exe").toString());if(!Files.exists(Path.of(python)))python="python";
        String script="""
            import json, copy
            from test_model_periods import section, fact
            from quant_research.model.periods import from_cross_section, ttm
            documents=[]
            for scenario in range(6):
                d=section()
                if scenario==1:
                    d['inputs'][0]['profile']='BANK'
                    d['inputs'][0]['reasons']=[{'code':'BLOCKING_ISSUE','detail':'split'}]
                if scenario==2: d['inputs'][0]['facts']=[]
                if scenario==3: d['inputs'][0]['facts'][0]['observedAt']='2026-10-01T00:00:00Z'
                if scenario>=4:
                    d['request']['knowledgeCutoff']='2026-10-01T13:00:00Z'
                    d['request']['timingPolicy']='NEXT_OPEN_MINUS_30M_V1'
                    if scenario==5: d['inputs'][0]['facts'][0]['observedAt']='2026-10-01T13:01:00Z'
                a,r=from_cross_section(d)
                documents.append({'input':d,'expected':{'as_of':a,'rows':r}})
            quarters=[fact('2025-07-01','2025-09-30',10),fact('2025-10-01','2025-12-31',20),fact('2026-01-01','2026-03-31',30),fact('2026-04-01','2026-06-30',40)]
            annual=[fact('2025-07-01','2026-06-30',123)]
            ytd=[fact('2025-01-01','2025-12-31',100),fact('2026-01-01','2026-06-30',70),fact('2025-01-01','2025-06-30',40)]
            cases=[quarters,quarters[1:],annual,annual+annual,ytd,ytd[:2]]
            print(json.dumps({'documents':documents,'periods':[{'facts':f,'expected':ttm(f,'REVENUE','2026-06-30')} for f in cases]}))
            """;
        var builder=new ProcessBuilder(python,"-c",script);
        builder.environment().put("PYTHONPATH",root.resolve("research-engine/src")+java.io.File.pathSeparator+root.resolve("research-engine/tests"));
        var process=builder.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var payload=CanonicalJson.readObject(new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        assertThat(process.waitFor()).isZero();
        for(Object item:list(payload.get("documents"))){var c=map(item);GoldenParity.compare(c.get("expected"),CanonicalModelInputs.prepare(map(c.get("input")),Map.of()),"$adapter",1e-9);}
        for(Object item:list(payload.get("periods"))){var c=map(item);GoldenParity.compare(c.get("expected"),CanonicalModelInputs.ttm(list(c.get("facts")).stream().map(FrozenModel::map).toList(),"REVENUE","2026-06-30"),"$ttm",1e-9);}
        var document=map(map(list(payload.get("documents")).getFirst()).get("input"));var input=map(list(document.get("inputs")).getFirst());
        list(input.get("facts")).add(list(input.get("facts")).getFirst());
        assertThatThrownBy(()->CanonicalModelInputs.prepare(document,Map.of())).hasMessageContaining("Ambiguous");
        map(document.get("request")).put("knowledgeCutoff","2026-10-01T00:00:00Z");
        assertThatThrownBy(()->CanonicalModelInputs.prepare(document,Map.of())).hasMessageContaining("cutoff");
    }
}
