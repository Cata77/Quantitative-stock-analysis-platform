package com.quantplatform.marketdata.reference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DatedUniverseCsvReader {

    private static final List<String> REQUIRED_COLUMNS = List.of(
            "symbol", "legal_name", "cik", "exchange_mic");

    public List<UniverseMemberInput> read(Path path) {
        try {
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("Universe snapshot file is empty: " + path);
            }

            var headers = parseLine(stripBom(lines.getFirst())).stream()
                    .map(value -> value.trim().toLowerCase(Locale.ROOT))
                    .toList();
            REQUIRED_COLUMNS.forEach(column -> {
                if (!headers.contains(column)) {
                    throw new IllegalArgumentException("Missing required CSV column: " + column);
                }
            });

            var members = new ArrayList<UniverseMemberInput>();
            for (int index = 1; index < lines.size(); index++) {
                var values = parseLine(lines.get(index));
                if (values.size() != headers.size()) {
                    throw new IllegalArgumentException(
                            "CSV row " + (index + 1) + " has " + values.size()
                                    + " values but the header has " + headers.size());
                }
                var row = new HashMap<String, String>();
                for (int column = 0; column < headers.size(); column++) {
                    row.put(headers.get(column), values.get(column));
                }
                members.add(member(row));
            }
            return List.copyOf(members);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read universe snapshot file " + path, exception);
        }
    }

    private UniverseMemberInput member(Map<String, String> row) {
        return new UniverseMemberInput(
                row.get("symbol"),
                row.get("legal_name"),
                row.get("cik"),
                row.get("figi"),
                row.get("exchange_mic"),
                row.get("security_type"),
                row.get("share_class"),
                row.get("currency"),
                row.get("domicile"),
                row.get("sic"),
                row.get("source_classification"),
                row.get("mapped_sector"),
                booleanValue(row.get("primary_liquid_class")));
    }

    private boolean booleanValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> throw new IllegalArgumentException("Invalid primary_liquid_class value: " + value);
        };
    }

    private List<String> parseLine(String line) {
        var values = new ArrayList<String>();
        var value = new StringBuilder();
        var quoted = false;
        for (int index = 0; index < line.length(); index++) {
            var character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString().trim());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quoted CSV value");
        }
        values.add(value.toString().trim());
        return values;
    }

    private String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
}
