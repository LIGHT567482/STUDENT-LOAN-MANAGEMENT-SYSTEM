package org.kiu.lms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class CsvExporter {
    private CsvExporter() {
    }

    public static void write(Path path, List<Map<String, Object>> rows) throws IOException {
        List<String> headers = rows.isEmpty() ? List.of() : List.copyOf(rows.getFirst().keySet());
        write(path, rows, headers);
    }

    public static void write(Path path, List<Map<String, Object>> rows, List<String> headers) throws IOException {
        StringBuilder output = new StringBuilder();
        if (!headers.isEmpty()) {
            output.append(headers.stream().map(CsvExporter::quote)
                    .collect(Collectors.joining(","))).append("\r\n");
            for (Map<String, Object> row : rows) {
                output.append(headers.stream().map(header -> {
                            Object value = row.get(header);
                            return quote(value == null ? "" : value.toString());
                        })
                        .collect(Collectors.joining(","))).append("\r\n");
            }
        }
        Files.writeString(path, output.toString(), StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
