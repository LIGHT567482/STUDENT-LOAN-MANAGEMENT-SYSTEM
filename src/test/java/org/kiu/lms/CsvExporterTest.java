package org.kiu.lms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvExporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesQuotedUtf8HeadersAndValuesForEmptyAndPopulatedReports() throws Exception {
        Path empty = temporaryDirectory.resolve("empty.csv");
        CsvExporter.write(empty, List.of(), List.of("student", "amount"));
        assertEquals("\"student\",\"amount\"\r\n", Files.readString(empty));

        Path populated = temporaryDirectory.resolve("report.csv");
        CsvExporter.write(populated, List.of(Map.of("student", "Kato, Amina", "note", "said \"yes\"")),
                List.of("student", "note"));
        assertEquals("\"student\",\"note\"\r\n\"Kato, Amina\",\"said \"\"yes\"\"\"\r\n",
                Files.readString(populated));
    }
}
