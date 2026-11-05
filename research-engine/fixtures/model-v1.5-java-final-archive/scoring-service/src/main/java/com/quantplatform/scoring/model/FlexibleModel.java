package com.quantplatform.scoring.model;

import static com.quantplatform.scoring.model.FrozenModel.*;
import com.quantplatform.ingestion.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.ClassPathResource;

/** Versioned available-component model; legacy formulas and hard identity gates remain intact. */
public final class FlexibleModel {
    public static final String SHA = "c92776898dc572ffdb8da5310fe247b0d5a29dc169675e7ee132df6194d6e2cf";
    private static final List<String> FAMILIES = List.of("value", "quality", "momentum");
    private static final Set<String> SOFT = Set.of(
        "INSUFFICIENT_VALUE_COVERAGE", "INSUFFICIENT_QUALITY_COVERAGE",
        "INSUFFICIENT_NORMALIZED_VALUE", "INSUFFICIENT_NORMALIZED_QUALITY",
        "INSUFFICIENT_NORMALIZED_MOMENTUM", "MISSING_MOMENTUM_BOUNDARY",
        "INVALID_MARKET_CAP", "MISSING_INSTRUMENT_SHARES", "STALE_SHARES", "STALE",
        "INSUFFICIENT_HISTORY", "INSUFFICIENT_PRICE_HISTORY", "MISSING_PROFILE_ANCHOR",
        "NON_POSITIVE_EBITDARE", "MISSING_REQUIRED_METRIC:TOTAL_ASSETS");
    /** Version 1.5 security policy; delisted, not-yet-listed and other types stay blocking. */
    private static final Set<String> SOFT_V15 = Set.of(
        "NON_PRIMARY_SHARE_CLASS", "INACTIVE_OR_UNSUPPORTED_SECURITY:DEPOSITARY_RECEIPT",
        "MULTI_CLASS_ISSUER_CAPITALIZATION_UNAVAILABLE");
    private final FrozenModel legacy = new FrozenModel();

    public static final String V14_SHA = "6eba49f0785ae1fd6d311461e2952cf139dc1f8054042103cea4c23f83fa9c53";
    public static final String V15_SHA = "58a1c2c213fa687b6e1c45c9525ce456243395ef3bbe5b9b24a6caa5b45f6de5";
    private final String version;
    private final String manifestSha;
    private final String resourcePath;

    public FlexibleModel() { this("1.3.0"); }

    public FlexibleModel(String version) {
        if (!Set.of("1.3.0", "1.4.0", "1.5.0").contains(version))
            throw new IllegalArgumentException("Unsupported flexible model version: " + version);
        this.version = version;
        manifestSha = switch (version) { case "1.4.0" -> V14_SHA; case "1.5.0" -> V15_SHA; default -> SHA; };
        resourcePath = switch (version) {
            case "1.4.0" -> "model/flexible_v14/"; case "1.5.0" -> "model/flexible_v15/"; default -> "model/flexible/"; };
        try {
            String canonical = new ClassPathResource(resourcePath + "manifest.canonical.json")
                .getContentAsString(StandardCharsets.UTF_8);
            String document = new ClassPathResource(resourcePath + "manifest.json")
                .getContentAsString(StandardCharsets.UTF_8);
            if (!manifestSha.equals(CanonicalJson.sha256(canonical))
                || !CanonicalJson.readObject(canonical).equals(CanonicalJson.readObject(document)))
                throw new IllegalStateException("Flexible manifest checksum mismatch");
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public String manifestSha() { return manifestSha; }

    private boolean filingLocal() { return "1.4.0".equals(version) || "1.5.0".equals(version); }

    private boolean soft(String reason) {
        return (filingLocal() && "STALE_OR_MISSING_FILING".equals(reason))
            || ("1.5.0".equals(version) && SOFT_V15.contains(reason))
            || SOFT.contains(reason) || reason.startsWith("MODEL_NOT_SUPPORTED")
            || reason.startsWith("MISSING_SESSION:") || reason.startsWith("MISSING_RISK_INPUT:");
    }

    public Map<String,Object> score(List<Map<String,Object>> inputs, String asOf, String candidate) {
        if (!"primary".equals(candidate)) throw new IllegalArgumentException("Flexible model supports only primary");
        if ("1.5.0".equals(version)) inputs = issuerCapitalization(inputs);
        var report = legacy.score(inputs, asOf, candidate);
        var sources = new HashMap<String,Map<String,Object>>();
        for (var input : inputs) sources.put(UUID.fromString(str(input.get("instrument_id"))).toString(), input);
        var results = list(report.get("rows")).stream().map(FrozenModel::map).toList();
        for (var row : results) {
            var source = sources.get(str(row.get("instrument_id")));
            var original = list(row.get("reasons")).stream().map(FrozenModel::str).toList();
            row.put("reasons", new ArrayList<>(original.stream().filter(r -> !soft(r)).toList()));
            var warnings = new ArrayList<>(list(row.get("warnings")).stream().map(FrozenModel::str)
                .filter(w -> !w.startsWith("PEER_FALLBACK:")).toList());
            original.stream().filter(this::soft).forEach(r -> warnings.add("LIMITATION:" + r));
            row.put("warnings", warnings);
            row.putAll(obj("eligible", false, "rank", null, "percentile", null, "composite", null,
                "families", null, "contributions", null, "score_status", "UNAVAILABLE",
                "effective_weights", obj("value", 0d, "quality", 0d, "momentum", 0d)));
            if (!yes(source,"profile_supported") || !"GENERAL".equals(source.get("profile"))) {
                Object raw = map(row.get("derived")).get("momentum");
                row.put("metrics", obj("momentum", obj("family", "momentum", "raw", raw,
                    "directed", raw, "reason", map(row.get("input_reasons")).get("momentum"))));
                warnings.add("LIMITATION:PRICE_ONLY_UNSUPPORTED_ACCOUNTING_PROFILE");
            }
            boolean gaps = original.stream().anyMatch(r -> r.startsWith("MISSING_SESSION:"));
            for (Object value : map(row.get("metrics")).values()) {
                var metric = map(value);
                String reason = null;
                if ("value".equals(metric.get("family")) && original.contains("STALE_SHARES")) reason = "STALE_SHARES";
                if (!"momentum".equals(metric.get("family")) && (original.contains("STALE")
                    || (filingLocal() && original.contains("STALE_OR_MISSING_FILING")))) reason = "STALE_FILING";
                if ("momentum".equals(metric.get("family")) && (gaps || number(source.get("history_count")) < 252))
                    reason = "INSUFFICIENT_HISTORY";
                if (reason != null) metric.putAll(obj("raw", null, "directed", null, "reason", reason));
                for (String key : List.of("winsorized", "cohort", "lower", "upper", "mean", "sigma", "z", "clipped_z"))
                    metric.put(key, null);
                metric.putAll(obj("cohort_count", 0, "effective_weight", 0d, "contribution", null));
            }
            row.put("coverage", obj("normalized_metrics", 0, "expected_metrics", 7, "available_families", 0));
        }
        var base = results.stream().filter(r -> list(r.get("reasons")).isEmpty()).toList();
        for (var row : base) {
            for (var entry : map(row.get("metrics")).entrySet()) {
                String name = entry.getKey();
                var metric = map(entry.getValue());
                if (metric.get("raw") == null) continue;
                boolean priceCohort = filingLocal() && "momentum".equals(name);
                var compatible = base.stream().filter(r -> (priceCohort || Objects.equals(r.get("profile"), row.get("profile")))
                    && map(map(r.get("metrics")).get(name)).get("raw") != null).toList();
                if ("1.5.0".equals(version)) compatible = representatives(compatible, sources);
                var primary = compatible.stream().filter(r -> Objects.equals(r.get("peer_group"), row.get("peer_group"))).toList();
                List<Map<String,Object>> cohort;
                String label;
                if (row.get("peer_group") != null && !str(row.get("peer_group")).isEmpty() && primary.size() >= 10) {
                    cohort = primary; label = "PRIMARY:" + row.get("peer_group");
                } else if (compatible.size() >= 20) {
                    cohort = compatible; label = priceCohort ? "PRICE_UNIVERSE" : "PROFILE:" + row.get("profile");
                    list(row.get("warnings")).add("PEER_FALLBACK:" + name);
                } else { metric.put("reason", "NO_COMPATIBLE_COHORT"); continue; }
                var values = cohort.stream().map(r -> number(map(map(r.get("metrics")).get(name)).get("raw"))).toList();
                double lower = percentile(values, .025), upper = percentile(values, .975);
                var wins = values.stream().map(v -> clip(v, lower, upper)).toList();
                double mean = mean(wins), sigma = sigma(wins), value = clip(number(metric.get("raw")), lower, upper);
                double z = sigma < 1e-12 ? 0 : (value - mean) / sigma;
                metric.putAll(obj("winsorized", value, "cohort", label, "cohort_count", cohort.size(),
                    "lower", lower, "upper", upper, "mean", mean, "sigma", sigma, "z", z,
                    "clipped_z", clip(z, -3, 3), "reason", sigma < 1e-12 ? "NO_DISPERSION" : null));
            }
            var parts = new LinkedHashMap<String,List<Map<String,Object>>>();
            var families = new LinkedHashMap<String,Object>();
            var weights = obj("value", .5, "quality", .3, "momentum", .2);
            double denominator = 0;
            int normalized = 0, availableFamilies = 0;
            for (String family : FAMILIES) {
                var metrics = map(row.get("metrics")).values().stream().map(FrozenModel::map)
                    .filter(m -> family.equals(m.get("family")) && m.get("clipped_z") != null).toList();
                parts.put(family, metrics);
                double sum = 0;
                for (var metric : metrics) sum += number(metric.get("clipped_z"));
                families.put(family, metrics.isEmpty() ? null : sum / metrics.size());
                normalized += metrics.size();
                if (!metrics.isEmpty()) { denominator += number(weights.get(family)); availableFamilies++; }
            }
            row.put("families", families);
            row.put("coverage", obj("normalized_metrics", normalized, "expected_metrics", 7, "available_families", availableFamilies));
            if (denominator == 0) { list(row.get("reasons")).add("NO_VALID_NORMALIZED_METRICS"); continue; }
            var effective = new LinkedHashMap<String,Object>();
            var contributions = new LinkedHashMap<String,Object>();
            double composite = 0;
            for (String family : FAMILIES) {
                var metrics = parts.get(family);
                double weight = metrics.isEmpty() ? 0 : number(weights.get(family)) / denominator;
                effective.put(family, weight);
                Double contribution = metrics.isEmpty() ? null : number(families.get(family)) * weight;
                contributions.put(family, contribution);
                if (contribution != null) composite += contribution;
                for (var metric : metrics) metric.putAll(obj("effective_weight", weight / metrics.size(),
                    "contribution", number(metric.get("clipped_z")) * (weight / metrics.size())));
            }
            row.putAll(obj("effective_weights", effective, "contributions", contributions,
                "composite", composite, "eligible", true, "score_status", normalized == 7 ? "COMPLETE" : "PARTIAL"));
            if (normalized != 7) list(row.get("warnings")).add("PARTIAL_SCORE:AVAILABLE_COMPONENTS_ONLY");
        }
        var eligible = results.stream().filter(r -> yes(r,"eligible"))
            .sorted(Comparator.<Map<String,Object>>comparingDouble(r -> -number(r.get("composite")))
                .thenComparing(r -> str(r.get("instrument_id")))).toList();
        if (eligible.size() >= 2) for (int i = 0; i < eligible.size(); i++) {
            var row = eligible.get(i);
            double sum = 0, count = 0;
            for (int j = 0; j < eligible.size(); j++) if (number(row.get("composite")) == number(eligible.get(j).get("composite"))) {
                sum += j + 1; count++;
            }
            row.put("rank", i + 1);
            row.put("percentile", 100 * (eligible.size() - sum / count) / (eligible.size() - 1));
        }
        for (var row : results) for (String key : List.of("reasons", "warnings"))
            row.put(key, list(row.get(key)).stream().map(FrozenModel::str).distinct().sorted().toList());
        report.putAll(obj("model_version", version, "manifest_sha256", manifestSha, "eligible_count", eligible.size(),
            "excluded_count", results.size() - eligible.size(), "status", eligible.size() >= 2 ? "COMPLETE" : "FAILED",
            "reasons", eligible.size() >= 2 ? List.of() : List.of("INSUFFICIENT_UNIVERSE")));
        return report;
    }

    /** Remove class-level shares for issuers with several universe instruments (1.5). */
    static List<Map<String,Object>> issuerCapitalization(List<Map<String,Object>> inputs) {
        var counts = new HashMap<String,Integer>();
        for (var row : inputs) counts.merge(str(row.get("issuer_id")), 1, Integer::sum);
        var result = new ArrayList<Map<String,Object>>();
        for (var row : inputs) {
            if (counts.get(str(row.get("issuer_id"))) > 1) {
                var copy = CanonicalJson.readObject(CanonicalJson.write(row));
                map(copy.get("values")).remove("SHARES_OUTSTANDING");
                map(copy.get("provenance")).remove("SHARES_OUTSTANDING");
                var reasons = new ArrayList<Object>(list(copy.get("blocking_reasons")));
                reasons.add("MULTI_CLASS_ISSUER_CAPITALIZATION_UNAVAILABLE");
                copy.put("blocking_reasons", reasons);
                row = copy;
            }
            result.add(row);
        }
        return result;
    }

    /** One fitting row per issuer: primary classes, or the only class of an issuer. */
    static List<Map<String,Object>> representatives(List<Map<String,Object>> rows, Map<String,Map<String,Object>> sources) {
        var primary = new HashSet<String>();
        for (var row : rows) {
            var source = sources.get(str(row.get("instrument_id")));
            if (yes(source, "primary_class")) primary.add(str(source.get("issuer_id")));
        }
        return rows.stream().filter(row -> {
            var source = sources.get(str(row.get("instrument_id")));
            return yes(source, "primary_class") || !primary.contains(str(source.get("issuer_id")));
        }).toList();
    }

    /** Certify actual packaged outputs and include both implementations in the identity. */
    public String certify() {
        String legacyCertification = GoldenParity.verify(legacy);
        try {
            String preparedText = new ClassPathResource(resourcePath + "prepared.json").getContentAsString(StandardCharsets.UTF_8);
            String expectedText = new ClassPathResource(resourcePath + "expected.json").getContentAsString(StandardCharsets.UTF_8);
            var prepared = CanonicalJson.readObject(preparedText);
            var actual = score(list(prepared.get("rows")).stream().map(FrozenModel::map).toList(), str(prepared.get("as_of")), "primary");
            GoldenParity.compare(CanonicalJson.readObject(expectedText), actual, "$flexible", 1e-9);
            try (var stream = FlexibleModel.class.getResourceAsStream("FlexibleModel.class")) {
                String implementation = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNull(stream).readAllBytes()));
                return CanonicalJson.hash(manifestSha, legacyCertification, implementation,
                    CanonicalJson.sha256(preparedText), CanonicalJson.sha256(expectedText));
            }
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
