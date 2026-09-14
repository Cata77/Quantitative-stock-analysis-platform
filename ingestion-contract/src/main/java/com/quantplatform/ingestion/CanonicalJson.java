package com.quantplatform.ingestion;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class CanonicalJson {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private CanonicalJson() { }

    public static String hash(String... fields) {
        var digest = digest();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256(String content) {
        return HexFormat.of().formatHex(digest().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    public static String normalize(String json) {
        return write(readObject(json));
    }

    public static Map<String, Object> readObject(String json) {
        Object value = MAPPER.readValue(json, Object.class);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
        var result = new TreeMap<String, Object>();
        map.forEach((key, child) -> result.put((String) key, child));
        return result;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(sorted(value));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, child) -> result.put((String) key, sorted(child)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(CanonicalJson::sorted).toList();
        if (value instanceof BigDecimal decimal) return new BigDecimal(decimal.stripTrailingZeros().toPlainString());
        return value;
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
