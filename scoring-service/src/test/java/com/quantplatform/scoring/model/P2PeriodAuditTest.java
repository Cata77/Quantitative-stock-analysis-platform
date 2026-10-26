package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.quantplatform.ingestion.CanonicalJson;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class P2PeriodAuditTest {
    @Test void fiscalCalendarsAndAmbiguityMatchIndependentExpectationsAndPython() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("PARITY_PYTHON",
                root.resolve("research-engine/.venv/Scripts/python.exe").toString());
        String script = """
            import json
            from test_p2_period_audit import period_cases
            from quant_research.model.periods import ttm
            print(json.dumps([dict(name=n,facts=f,end=e,value=v,python=ttm(f,'REVENUE',e))
                for n,f,e,v in period_cases()]))
            """;
        var builder = new ProcessBuilder(python, "-c", script);
        builder.environment().put("PYTHONPATH", root.resolve("research-engine/src")
                + java.io.File.pathSeparator + root.resolve("research-engine/tests"));
        var process = builder.redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var cases = CanonicalJson.MAPPER.readValue(process.getInputStream().readAllBytes(), java.util.List.class);
        assertThat(process.waitFor()).isZero();
        for (var item : cases) {
            var c = map(item);
            var result = CanonicalModelInputs.ttm(list(c.get("facts")).stream()
                    .map(FrozenModel::map).toList(), "REVENUE", str(c.get("end")));
            GoldenParity.compare(c.get("python"), result, "$" + c.get("name"), 1e-9);
            GoldenParity.compare(c.get("value"), result == null ? null : result.get("value"),
                    "$expected." + c.get("name"), 1e-9);
        }
    }
}
