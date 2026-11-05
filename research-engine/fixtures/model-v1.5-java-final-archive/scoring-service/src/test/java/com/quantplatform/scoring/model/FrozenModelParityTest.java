package com.quantplatform.scoring.model;

import com.quantplatform.ingestion.CanonicalJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.quantplatform.scoring.model.FrozenModel.*;

class FrozenModelParityTest {
    @Test void packagedGoldenCertification(){assertThat(GoldenParity.verify(new FrozenModel())).hasSize(64);}
    @Test void livePythonParityEveryStageAndCandidate() throws Exception {
        Path root=Path.of("..").toAbsolutePath().normalize();
        String python=System.getenv().getOrDefault("PARITY_PYTHON",root.resolve(".venv/Scripts/python.exe").toString());
        if(!Files.exists(Path.of(python)))python="python";
        for(String prefix:List.of("","varied-"))for(String candidate:List.of("primary","pure_value","value_quality","balanced")){
            Path input=root.resolve("research-engine/fixtures/model-v1/"+prefix+"prepared.json");
            var document=CanonicalJson.readObject(Files.readString(input));
            var actual=new FrozenModel().score(list(document.get("rows")).stream().map(FrozenModel::map).toList(),str(document.get("as_of")),candidate);
            String script="import json,sys; from quant_research.model.engine import score; from quant_research.model.freeze import verify_freeze; verify_freeze(); d=json.load(open(sys.argv[1])); print(json.dumps(score(d['rows'],as_of=d['as_of'],candidate=sys.argv[2]),allow_nan=False))";
            var builder=new ProcessBuilder(python,"-c",script,input.toString(),candidate);
            builder.environment().put("PYTHONPATH",root.resolve("research-engine/src").toString());
            Path errors=Files.createTempFile("parity-python-",".log");
            try {
                var process=builder.redirectError(errors.toFile()).start();
                String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(process.waitFor()).withFailMessage(Files.readString(errors)).isZero();
                GoldenParity.compare(CanonicalJson.readObject(output),actual,"$",1e-9);
                GoldenParity.compare(serialize(CanonicalJson.readObject(output)),serialize(actual),"$stored",1e-9);
            } finally {Files.deleteIfExists(errors);}
        }
    }
}

