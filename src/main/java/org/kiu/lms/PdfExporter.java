package org.kiu.lms;

import java.awt.Color;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PdfExporter {
    private static final float MARGIN = 36;
    private static final float FONT_SIZE = 7;
    private static final float LINE_HEIGHT = 9;
    private static final float CELL_PADDING = 4;
    private static final PDRectangle PAGE_SIZE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final PDType1Font REGULAR = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final PDType1Font BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private PdfExporter() {
    }

    public static void write(Path path, String title, List<Map<String, Object>> rows, List<String> columns)
            throws IOException {
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("This report does not contain any columns to export.");
        }

        List<String> labels = columns.stream().map(PdfExporter::columnLabel).toList();
        float[] widths = columnWidths(columns, labels, rows, PAGE_SIZE.getWidth() - 2 * MARGIN);
        try (PDDocument document = new PDDocument()) {
            PDPageContentStream stream = null;
            float y = 0;
            try {
                stream = newPage(document, title, rows.size());
                y = PAGE_SIZE.getHeight() - MARGIN - 59;
                y = drawRow(stream, labels, widths, y, true);
                for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                    List<String> values = new ArrayList<>(columns.size());
                    for (String column : columns) {
                        values.add(printable(rows.get(rowIndex).get(column)));
                    }
                    float rowHeight = rowHeight(values, widths);
                    if (y - rowHeight < MARGIN) {
                        stream.close();
                        stream = newPage(document, title, rows.size());
                        y = PAGE_SIZE.getHeight() - MARGIN - 59;
                        y = drawRow(stream, labels, widths, y, true);
                    }
                    y = drawRow(stream, values, widths, y, false, rowIndex % 2 == 0);
                }
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
            document.save(path.toFile());
        }
    }

    private static PDPageContentStream newPage(PDDocument document, String title, int rowCount) throws IOException {
        PDPage page = new PDPage(PAGE_SIZE);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.setNonStrokingColor(new Color(24, 115, 67));
        stream.beginText();
        stream.setFont(BOLD, 16);
        stream.newLineAtOffset(MARGIN, PAGE_SIZE.getHeight() - MARGIN - 16);
        stream.showText(sanitize(title));
        stream.endText();
        stream.setNonStrokingColor(new Color(80, 100, 86));
        stream.beginText();
        stream.setFont(REGULAR, 8);
        stream.newLineAtOffset(MARGIN, PAGE_SIZE.getHeight() - MARGIN - 31);
        stream.showText(Branding.loginBrand() + "  |  Generated " + LocalDate.now() + "  |  " + rowCount + " records");
        stream.endText();
        return stream;
    }

    private static float drawRow(PDPageContentStream stream, List<String> cells, float[] widths, float y,
                                 boolean header) throws IOException {
        return drawRow(stream, cells, widths, y, header, false);
    }

    private static float drawRow(PDPageContentStream stream, List<String> cells, float[] widths, float y,
                                 boolean header, boolean alternate) throws IOException {
        List<List<String>> wrapped = new ArrayList<>(cells.size());
        int maxLines = 1;
        for (int index = 0; index < cells.size(); index++) {
            List<String> lines = wrap(cells.get(index), widths[index] - 2 * CELL_PADDING);
            wrapped.add(lines);
            maxLines = Math.max(maxLines, lines.size());
        }
        float height = maxLines * LINE_HEIGHT + CELL_PADDING * 2;
        float x = MARGIN;
        for (int index = 0; index < cells.size(); index++) {
            stream.setNonStrokingColor(new Color(
                    header ? 224 : alternate ? 245 : 255,
                    header ? 239 : alternate ? 249 : 255,
                    header ? 228 : alternate ? 245 : 255));
            stream.addRect(x, y - height, widths[index], height);
            stream.fill();
            stream.setStrokingColor(new Color(207, 222, 210));
            stream.addRect(x, y - height, widths[index], height);
            stream.stroke();

            stream.setNonStrokingColor(new Color(30, 48, 36));
            stream.beginText();
            stream.setFont(header ? BOLD : REGULAR, FONT_SIZE);
            stream.newLineAtOffset(x + CELL_PADDING, y - CELL_PADDING - FONT_SIZE);
            for (int lineIndex = 0; lineIndex < wrapped.get(index).size(); lineIndex++) {
                if (lineIndex > 0) {
                    stream.newLineAtOffset(0, -LINE_HEIGHT);
                }
                stream.showText(wrapped.get(index).get(lineIndex));
            }
            stream.endText();
            x += widths[index];
        }
        return y - height;
    }

    private static float rowHeight(List<String> cells, float[] widths) throws IOException {
        int maxLines = 1;
        for (int index = 0; index < cells.size(); index++) {
            maxLines = Math.max(maxLines, wrap(cells.get(index), widths[index] - 2 * CELL_PADDING).size());
        }
        return maxLines * LINE_HEIGHT + CELL_PADDING * 2;
    }

    private static float[] columnWidths(List<String> columns, List<String> labels, List<Map<String, Object>> rows,
                                        float availableWidth) throws IOException {
        float[] weights = new float[columns.size()];
        float totalWeight = 0;
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            float maximum = BOLD.getStringWidth(sanitize(labels.get(index))) / 1000 * FONT_SIZE;
            for (Map<String, Object> row : rows) {
                String value = printable(row.get(column));
                maximum = Math.max(maximum, Math.min(REGULAR.getStringWidth(value) / 1000 * FONT_SIZE, 110));
            }
            weights[index] = Math.max(38, maximum + 2 * CELL_PADDING);
            totalWeight += weights[index];
        }
        float[] widths = new float[columns.size()];
        for (int index = 0; index < widths.length; index++) {
            widths[index] = availableWidth * weights[index] / totalWeight;
        }
        return widths;
    }

    private static List<String> wrap(String text, float maxWidth) throws IOException {
        String value = sanitize(text);
        if (value.isEmpty()) {
            return List.of("");
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : value.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && REGULAR.getStringWidth(candidate) / 1000 * FONT_SIZE > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                if (!line.isEmpty()) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private static String printable(Object value) {
        return value == null ? "" : value.toString().replace('\n', ' ').replace('\r', ' ');
    }

    private static String columnLabel(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String sanitize(String value) {
        return value
                .replace('–', '-')
                .replace('—', '-')
                .replace('’', '\'')
                .replace('“', '"')
                .replace('”', '"')
                .replace('·', '|')
                .codePoints()
                .map(character -> character >= 32 && character <= 255 ? character : '?')
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
