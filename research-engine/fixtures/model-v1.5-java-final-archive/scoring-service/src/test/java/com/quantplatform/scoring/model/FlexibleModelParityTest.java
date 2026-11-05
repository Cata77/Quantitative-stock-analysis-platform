package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import static org.junit.jupiter.api.Assertions.*;
import com.quantplatform.ingestion.CanonicalJson;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class FlexibleModelParityTest {
    @Test void packagedAdversarialFixtureMatchesPython() {
        assertEquals(64, new FlexibleModel().certify().length());
        assertEquals(64, new FlexibleModel("1.4.0").certify().length());
        assertEquals(64, new FlexibleModel("1.5.0").certify().length());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="FLEXIBLE_COHORT_DIR", matches=".+")
    void retainedPublicationMatchesPythonAtEveryStage() throws Exception {
        Path directory = Path.of(System.getenv("FLEXIBLE_COHORT_DIR"));
        var prepared = CanonicalJson.readObject(Files.readString(directory.resolve("prepared.json")));
        var expected = CanonicalJson.readObject(Files.readString(directory.resolve("model.json")));
        var actual = new FlexibleModel(str(expected.get("model_version"))).score(list(prepared.get("rows")).stream().map(FrozenModel::map).toList(),
            str(prepared.get("as_of")), "primary");
        GoldenParity.compare(expected, actual, "$cohort", 1e-9);
        GoldenParity.compare(serialize(expected), serialize(actual), "$serialized", 1e-9);
    }
}
