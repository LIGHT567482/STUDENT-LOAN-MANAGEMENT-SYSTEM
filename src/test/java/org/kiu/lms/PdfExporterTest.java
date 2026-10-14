package org.kiu.lms;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExporterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void writesReadableReportTitleHeadersAndRows() throws Exception {
        Path output = temporaryDirectory.resolve("portfolio-summary.pdf");
        PdfExporter.write(output, "Portfolio summary",
                List.of(Map.of("studentId", "KIU-001", "outstanding", "UGX 125,000")),
                List.of("studentId", "outstanding"));

        try (var document = Loader.loadPDF(output.toFile())) {
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.contains("Portfolio summary"));
            assertTrue(text.contains("Student Id"));
            assertTrue(text.contains("Outstanding"));
            assertTrue(text.contains("KIU-001"));
            assertTrue(text.contains("UGX 125,000"));
        }
    }

    @Test
    void replacesJavaFxImplementationNamesWithHumanReadableGuidance() {
        assertTrue(LmsWindow.exceptionMessage(
                new IllegalStateException("javafx.scene.control.Label"))
                .contains("Check the required information"));
    }
}
