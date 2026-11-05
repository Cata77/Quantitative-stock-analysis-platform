package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import com.quantplatform.ingestion.CanonicalJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Compare the entire retained cohort, including normalization, with the Python audit. */
@EnabledIfEnvironmentVariable(named="P2_COMPARISON_DIR", matches=".+")
class P2CohortParityTest {
    @Test void retainedCohortMatchesPythonPreparationAndScores() throws Exception {
        Path directory=Path.of(System.getenv("P2_COMPARISON_DIR"));
        var canonical=CanonicalJson.readObject(Files.readString(directory.resolve("canonical.json")));
        var expected=CanonicalJson.readObject(Files.readString(directory.resolve("prepared.json")));
        var actual=CanonicalModelInputs.prepare(canonical,Map.of(),CanonicalModelInputs.CURRENT_VERSION);
        GoldenParity.compare(expected,actual,"$prepared",1e-9);
        var model=new FrozenModel();
        var scores=model.score(list(actual.get("rows")).stream().map(FrozenModel::map).toList(),
            str(actual.get("as_of")),"primary");
        GoldenParity.compare(CanonicalJson.readObject(Files.readString(directory.resolve("model.json"))),
            serialize(scores),"$scores",1e-9);
    }
}
