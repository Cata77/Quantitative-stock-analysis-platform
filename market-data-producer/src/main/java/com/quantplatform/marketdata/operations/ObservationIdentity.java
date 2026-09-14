package com.quantplatform.marketdata.operations;

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

final class ObservationIdentity {
    private ObservationIdentity() {
    }

    static String hash(String... fields) {
        var digest = digest();
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String contentHash(String content) {
        return HexFormat.of().formatHex(digest().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String canonicalJson(ObjectMapper mapper, String json) {
        Object value = readJson(mapper, json);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("observation payload must be a JSON object");
        }
        return mapper.writeValueAsString(sorted(value));
    }

    static Object readJson(ObjectMapper mapper, String json) {
        return mapper.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(json);
    }

    private static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            map.forEach((key, child) -> sorted.put((String) key, sorted(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(ObservationIdentity::sorted).toList();
        }
        if (value instanceof BigDecimal decimal) {
            return new BigDecimal(decimal.stripTrailingZeros().toPlainString());
        }
        return value;
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
