package org.kiu.lms;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.chart.Chart;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Shape;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.io.IOException;

final class UiStyles {
    private static final Color GREEN = Branding.primary();
    private static final Color DARK_GREEN = Branding.primaryDark();
    private static final Color SIDEBAR = Branding.sidebar();
    private static final Color SIDEBAR_HOVER = Branding.sidebarHover();
    private static final Color SIDEBAR_LINE = Branding.sidebarLine();
    private static final Color TEXT = Branding.text();
    private static final Color MUTED = Branding.muted();
    private static final Color BORDER = Branding.border();
    private static final Color SURFACE = Branding.surface();
    private static final Color SURFACE_ALT = Branding.surfaceAlt();
    private static final Color PAGE_SURFACE = Branding.pageSurface();
    private static final Color INPUT_SURFACE = Branding.inputSurface();
    private static final Color DIALOG_SURFACE = Branding.dialogSurface();
    private static final Color CARD_BORDER = Branding.cardBorder();
    private static final Color CARD_HOVER = Branding.cardHover();
    private static final Color TABLE_HEADER = Branding.tableHeader();
    private static final Color TABLE_SELECTED = Branding.tableSelected();
    private static final Color CHART_PLOT = Branding.chartPlot();
    private static final Color HEADER_BG = Branding.headerBackground();
    private static final Color HEADER_TEXT = Branding.headerText();
    private static final String WATERMARK = Branding.logoPath();
    private static final double WATERMARK_ALPHA = Branding.watermarkOpacity();
    private static final double TABLE_WATERMARK_ALPHA = Branding.watermarkTableOpacity();
    private static final double WATERMARK_WIDTH = Branding.watermarkWidth();
    private static final double WATERMARK_HEIGHT = Branding.watermarkHeight();
    private static BackgroundImage watermarkImage;
    private static BackgroundImage tableWatermarkImage;
    private static final Font BASE_FONT = Font.font("Arial", 14);

    private UiStyles() {
    }

    static void tip(Node node, String text) {
        if (node == null || node.getProperties().containsKey("lms-tip")) {
            return;
        }
        node.getProperties().put("lms-tip", text);
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(320);
        tooltip.setStyle("-fx-background-color: #0d3022; -fx-text-fill: #eaf6ee; -fx-background-radius: 8; "
                + "-fx-padding: 9 12 11 12; -fx-font-size: 12px; -fx-font-family: Arial;");
        Tooltip.install(node, tooltip);
    }

    static void tipOnNode(ReadOnlyObjectProperty<Node> nodeProperty, String text) {
        nodeProperty.addListener((observable, previous, node) -> tip(node, text));
    }

    private static BackgroundImage buildWatermark(double alpha) {
        Image source;
        try (var stream = UiStyles.class.getResourceAsStream(WATERMARK)) {
            if (stream == null) {
                throw new IllegalStateException("The KIU logo resource is missing: " + WATERMARK);
            }
            source = new Image(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("The KIU logo resource could not be read: " + WATERMARK, exception);
        }
        int width = (int) Math.round(source.getWidth());
        int height = (int) Math.round(source.getHeight());
        WritableImage faded = new WritableImage(width, height);
        PixelReader reader = source.getPixelReader();
        PixelWriter writer = faded.getPixelWriter();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = reader.getArgb(x, y);
                int alphaChannel = (int) Math.round(((argb >>> 24) & 0xff) * alpha);
                writer.setArgb(x, y, (alphaChannel << 24) | (argb & 0xffffff));
            }
        }
        return new BackgroundImage(faded, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER,
                new BackgroundSize(WATERMARK_WIDTH, WATERMARK_HEIGHT, false, false, false, false));
    }

    private static BackgroundImage watermarkImage() {
        if (watermarkImage == null) {
            watermarkImage = buildWatermark(WATERMARK_ALPHA);
        }
        return watermarkImage;
    }

    private static BackgroundImage tableWatermarkImage() {
        if (tableWatermarkImage == null) {
            tableWatermarkImage = buildWatermark(TABLE_WATERMARK_ALPHA);
        }
        return tableWatermarkImage;
    }

    private static Background wm(Color base) {
        return new Background(
                new BackgroundFill[]{new BackgroundFill(base, CornerRadii.EMPTY, Insets.EMPTY)},
                new BackgroundImage[]{watermarkImage()});
    }

    private static Background wm(Color base, double radius) {
        return new Background(
                new BackgroundFill[]{new BackgroundFill(base, new CornerRadii(radius), Insets.EMPTY)},
                new BackgroundImage[]{watermarkImage()});
    }

    private static Background wmTable(Color base, double radius) {
        return new Background(
                new BackgroundFill[]{new BackgroundFill(base, new CornerRadii(radius), Insets.EMPTY)},
                new BackgroundImage[]{tableWatermarkImage()});
    }

    static void applyTree(Node root) {
        apply(root);
        if (root instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                applyTree(child);
            }
        }
    }

    static void apply(Node node) {
        if (node instanceof Labeled labeled) {
            labeled.setFont(BASE_FONT);
        } else if (node instanceof TextInputControl input) {
            input.setFont(BASE_FONT);
        }

        if (has(node, "root")) {
            if (node instanceof javafx.scene.layout.BorderPane) {
                background(node, PAGE_SURFACE);
            }
        }
        if (has(node, "sidebar")) {
            if (node instanceof Region region) {
                background(region, SIDEBAR);
                region.setBorder(new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(
                        SIDEBAR_HOVER, javafx.scene.layout.BorderStrokeStyle.SOLID,
                        CornerRadii.EMPTY, new javafx.scene.layout.BorderWidths(0, 1, 0, 0))));
            }
            if (node instanceof javafx.scene.layout.BorderPane pane) {
                pane.setMinWidth(205);
            }
        }
        if (has(node, "sidebar-header")) {
            background(node, SIDEBAR);
            if (node instanceof Region region) {
                region.setPadding(new Insets(14, 16, 12, 16));
            }
        }
        if (has(node, "logo-badge")) {
            if (node instanceof Region region) {
                region.setBackground(new Background(new BackgroundFill(color("#dcf3e5"),
                        new CornerRadii(999), Insets.EMPTY)));
                region.setEffect(new DropShadow(16, new Color(0, 0, 0, 0.18)));
            }
        }
        if (has(node, "brand")) {
            label(node, Color.WHITE, 19, true);
        }
        if (has(node, "sidebar-subtitle")) {
            label(node, color("#a8c7b4"), 10, true);
        }
        if (has(node, "sidebar-navigation") && node instanceof Region region) {
            background(region, Color.TRANSPARENT);
        }
        if (has(node, "sidebar-section")) {
            label(node, color("#83a895"), 10, true);
            if (node instanceof Label label) {
                label.setPadding(new Insets(8, 8, 3, 6));
            }
        }
        if (has(node, "sidebar-logo") && node instanceof Region region) {
            region.setTranslateX(0);
        }
        if (has(node, "nav-button")) {
            Button navButton = (Button) node;
            buttonBase(navButton, SIDEBAR, color("#d8e8de"), new Insets(5, 10, 5, 10), 9);
            navButton.setAlignment(Pos.CENTER_LEFT);
            navButton.setFont(Font.font("Arial", 13));
            updateNavigationButton(navButton, false);
            navButton.setOnMouseEntered(event -> {
                updateNavigationButton(navButton, true);
            });
            navButton.setOnMouseExited(event -> updateNavigationButton(navButton, false));
        }
        if (has(node, "sidebar-account")) {
            if (node instanceof Region region) {
                region.setPadding(new Insets(8));
                region.setBackground(new Background(new BackgroundFill(SIDEBAR,
                        new CornerRadii(11), Insets.EMPTY)));
                region.setBorder(new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(
                        SIDEBAR_LINE, javafx.scene.layout.BorderStrokeStyle.SOLID,
                        new CornerRadii(11), javafx.scene.layout.BorderWidths.DEFAULT)));
            }
        }
        if (has(node, "sidebar-avatar")) {
            label(node, color("#10452e"), 12, true);
            if (node instanceof Label avatar) {
                avatar.setMinSize(36, 36);
                avatar.setPrefSize(36, 36);
                avatar.setMaxSize(36, 36);
                avatar.setAlignment(Pos.CENTER);
                avatar.setBackground(new Background(new BackgroundFill(color("#b9e2c6"),
                        new CornerRadii(999), Insets.EMPTY)));
            }
        }
        if (has(node, "sidebar-account-details") && node instanceof javafx.scene.layout.VBox details
                && details.getChildren().size() >= 2) {
            label(details.getChildren().get(0), Color.WHITE, 12, true);
            label(details.getChildren().get(1), color("#a8c7b4"), 11, false);
            details.getChildren().forEach(child -> {
                if (child instanceof Label label) {
                    label.setMaxWidth(Double.MAX_VALUE);
                    label.setWrapText(true);
                }
            });
        }
        if (has(node, "sidebar-footer") && node instanceof Region region) {
            region.setPadding(new Insets(10, 12, 10, 12));
            background(region, SIDEBAR);
        }
        if (has(node, "sidebar-sign-out")) {
            Button button = (Button) node;
            buttonBase(button, SIDEBAR, color("#e3eee7"), new Insets(8, 12, 8, 12), 9);
            button.setAlignment(Pos.CENTER);
            button.setFont(Font.font("Arial", 13));
            border(button, SIDEBAR_LINE, 9);
            hover(button, SIDEBAR_HOVER, SIDEBAR);
        }
        if (has(node, "header")) {
            if (node instanceof Region region) {
                region.setBackground(new Background(new BackgroundFill(HEADER_BG,
                        new CornerRadii(0, 0, 12, 12, false), Insets.EMPTY)));
                region.setPadding(new Insets(17, 24, 17, 24));
                region.setEffect(new DropShadow(8, 0, 2, new Color(0, 0, 0, 0.08)));
            }
        }
        if (has(node, "page-title")) {
            label(node, HEADER_TEXT, 22, true);
        }
        if (has(node, "page")) {
            background(node, Color.TRANSPARENT);
            if (node instanceof Region region) {
                region.setPadding(new Insets(22, 24, 24, 24));
            }
            if (node instanceof javafx.scene.layout.VBox box) {
                box.setSpacing(18);
            }
        }
        if (has(node, "panel") || has(node, "stat-card") || has(node, "auth-card")) {
            card(node, SURFACE);
            if (node instanceof javafx.scene.layout.VBox box && has(node, "stat-card")) {
                box.setSpacing(9);
            }
        }
        if ((has(node, "stat-card") || has(node, "panel")) && node instanceof Region region) {
            hoverCard(region);
        }
        if (has(node, "stat-label")) {
            label(node, MUTED, 12, false);
        }
        if (has(node, "stat-value")) {
            label(node, TEXT, 17, true);
            if (node instanceof Label value) {
                value.setWrapText(true);
            }
        }
        if (has(node, "button") && node instanceof Button button && !has(node, "nav-button")
                && !has(node, "sidebar-sign-out")) {
            buttonBase(button, GREEN, Color.WHITE, new Insets(9, 15, 9, 15), 7);
            hover(button, DARK_GREEN, GREEN);
            if (hasAncestorClass(button, "auth-login-content")) {
                button.setMaxWidth(320);
            }
        }
        if (isInput(node)) {
            input(node);
        }
        if (node instanceof CheckBox checkBox) {
            checkBox.setTextFill(TEXT);
        }
        if (node instanceof RadioButton radioButton) {
            radioButton.setTextFill(TEXT);
        }
        if (node instanceof TableView<?> table) {
            table(table);
        }
        if (node instanceof TableRow<?> row) {
            styleRow(row);
        }
        if (node instanceof TableCell<?, ?> cell) {
            styleCell(cell);
        }
        if (node instanceof ScrollPane scroll) {
            scroll(scroll);
        }
        if (node instanceof Separator separator && hasAncestorClass(separator, "sidebar")) {
            styleSidebarSeparator(separator);
            if (!Boolean.TRUE.equals(separator.getProperties().get("lms-separator-style"))) {
                separator.getProperties().put("lms-separator-style", true);
                separator.skinProperty().addListener((observable, oldSkin, skin) -> {
                    if (skin != null) {
                        javafx.application.Platform.runLater(() -> styleSidebarSeparator(separator));
                    }
                });
            }
        }
        if (has(node, "status-approved") || has(node, "status-paid") || has(node, "status-active")) {
            label(node, color("#15803d"), 14, true);
        } else if (has(node, "status-submitted") || has(node, "status-partial") || has(node, "status-pending")) {
            label(node, color("#9a6811"), 14, true);
        } else if (has(node, "status-rejected") || has(node, "status-closed") || has(node, "status-inactive")) {
            label(node, color("#b43b45"), 14, true);
        }
        if (has(node, "auth-background")) {
            LinearGradient gradient = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, color("#e8f4e9")), new Stop(1, color("#f8fbf8")));
            background(node, gradient);
        }
        if (has(node, "auth-login-tint")) {
            background(node, new Color(3 / 255.0, 26 / 255.0, 15 / 255.0, 0.76));
        }
        if (has(node, "auth-login-content")) {
            background(node, Color.TRANSPARENT);
        }
        if (has(node, "auth-title")) {
            label(node, hasAncestorClass(node, "auth-login-content") ? Color.BLACK : TEXT, 25, true);
        }
        if (has(node, "auth-brand")) {
            label(node, hasAncestorClass(node, "auth-login-content") ? Color.WHITE : SIDEBAR, 15, true);
        }
        if (has(node, "muted")) {
            label(node, hasAncestorClass(node, "auth-login-content") ? color("#e1f2e6") : MUTED, 14, false);
        }
        if (has(node, "login-field-label")) {
            label(node, TEXT, 14, true);
            if (node instanceof Label label) {
                label.setAlignment(Pos.CENTER);
            }
        }
        if (has(node, "password-visibility-toggle") && node instanceof Button button) {
            buttonBase(button, Color.TRANSPARENT, GREEN, new Insets(3), 5);
            button.setMinSize(28, 28);
            button.setPrefSize(28, 28);
            hover(button, color("#dcefe0"), Color.TRANSPARENT);
        }
        if (node instanceof DialogPane dialogPane) {
            background(dialogPane, DIALOG_SURFACE, 14);
            dialogPane.lookupAll(".header-panel").forEach(header -> background(header, TABLE_HEADER));
            dialogPane.lookupAll(".header-panel .label").forEach(header -> label(header, TEXT, 14, true));
            dialogPane.lookupAll(".content").forEach(content -> {
                if (content instanceof Labeled labeled) {
                    labeled.setTextFill(TEXT);
                }
            });
        }
        if (node instanceof Shape shape) {
            chartShape(shape);
        }
        if (node instanceof Chart chart) {
            chart(chart);
        }
    }

    private static void styleSidebarSeparator(Separator separator) {
        background(separator, Color.TRANSPARENT);
        separator.lookupAll(".line").forEach(line -> {
            if (line instanceof Region region) {
                border(region, color("#2f7a58"), 0);
            }
        });
    }

    private static void card(Node node, Color fill) {
        if (node instanceof Region region) {
            background(region, fill, 10);
            region.setEffect(new DropShadow(12, 0, 3, new Color(0, 0, 0, 0.045)));
        }
        border(node, CARD_BORDER, 10);
        if (node instanceof Region region) {
            region.setPadding(new Insets(18));
        }
    }

    private static void hoverCard(Region region) {
        region.setOnMouseEntered(event -> {
            background(region, CARD_HOVER, 10);
            border(region, GREEN, 10);
            region.setEffect(new DropShadow(18, 0, 5, new Color(0, 0, 0, 0.09)));
        });
        region.setOnMouseExited(event -> {
            background(region, SURFACE, 10);
            border(region, CARD_BORDER, 10);
            region.setEffect(new DropShadow(12, 0, 3, new Color(0, 0, 0, 0.045)));
        });
    }

    private static <T> void table(TableView<T> table) {
        table.setBackground(wmTable(SURFACE, 8));
        border(table, BORDER, 8);
        roundClip(table, 8);
        table.setRowFactory(ignored -> {
            TableRow<T> row = new TableRow<>();
            row.selectedProperty().addListener((observable, wasSelected, isSelected) -> styleRow(row));
            row.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> styleRow(row));
            styleRow(row);
            return row;
        });
        if (!Boolean.TRUE.equals(table.getProperties().get("lms-table-skin-style"))) {
            table.getProperties().put("lms-table-skin-style", true);
            table.skinProperty().addListener((observable, oldSkin, skin) -> {
                if (skin != null) {
                    javafx.application.Platform.runLater(() -> styleTableParts(table));
                }
            });
        }
        javafx.application.Platform.runLater(() -> styleTableParts(table));
    }

    private static void styleTableParts(TableView<?> table) {
        table.setBackground(wmTable(SURFACE, 8));
        table.lookupAll(".column-header-background")
                .forEach(node -> background(node, TABLE_HEADER, 8));
        clearOpaqueBackgrounds(table);
    }

    private static void clearOpaqueBackgrounds(javafx.scene.Parent parent) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Region region && !(child instanceof TableRow<?>) && !(child instanceof TableCell<?, ?>)) {
                if (String.join(" ", region.getStyleClass()).contains("column-header")) {
                    continue;
                }
                region.setBackground(Background.EMPTY);
            }
            if (child instanceof javafx.scene.Parent nested) {
                clearOpaqueBackgrounds(nested);
            }
        }
    }

    private static void roundClip(Region region, double radius) {
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        clip.setArcWidth(radius * 2);
        clip.setArcHeight(radius * 2);
        region.setClip(clip);
    }

    private static void styleRow(TableRow<?> row) {
        if (row.isEmpty()) {
            background(row, Color.TRANSPARENT);
        } else if (row.isSelected()) {
            background(row, TABLE_SELECTED);
        } else {
            background(row, row.getIndex() % 2 == 0 ? translucent(SURFACE, 0.55) : translucent(SURFACE_ALT, 0.55));
        }
        row.setTextFill(TEXT);
    }

    private static void styleCell(TableCell<?, ?> cell) {
        String status = cell.getText() == null ? "" : cell.getText().toLowerCase();
        Color color = switch (status) {
            case "approved", "paid", "active" -> color("#15803d");
            case "submitted", "partial", "pending", "stalled" -> color("#9a6811");
            case "rejected", "closed", "inactive" -> color("#b43b45");
            default -> TEXT;
        };
        cell.setTextFill(color);
        if (!color.equals(TEXT)) {
            cell.setFont(Font.font("Arial", FontWeight.BOLD, 14));
        } else {
            cell.setFont(BASE_FONT);
        }
    }

    private static void input(Node node) {
        if (node instanceof Region region) {
            background(region, INPUT_SURFACE, 6);
            border(region, color("#c6ddca"), 6);
            region.setPadding(new Insets(7));
            if (!Boolean.TRUE.equals(region.getProperties().get("lms-focus-style"))) {
                region.focusedProperty().addListener((observable, wasFocused, focused) ->
                        border(region, focused ? GREEN : color("#c6ddca"), 6));
                region.getProperties().put("lms-focus-style", true);
            }
            if (hasAncestorClass(node, "password-entry-eye")) {
                region.setPadding(new Insets(7, 38, 7, 9));
            }
        }
    }

    private static boolean isInput(Node node) {
        return node instanceof TextField || node instanceof PasswordField || node instanceof ChoiceBox<?>
                || node instanceof DatePicker || node instanceof ComboBox<?>;
    }

    private static void scroll(ScrollPane pane) {
        if (has(pane, "auth-scroll-pane")) {
            background(pane, Color.TRANSPARENT);
        } else if (has(pane, "sidebar-scroll-pane")) {
            background(pane, Color.TRANSPARENT);
        } else {
            background(pane, wm(PAGE_SURFACE));
        }
        if (pane.getContent() instanceof Region content) {
            content.setBackground(Background.EMPTY);
        }
        applyScrollContent(pane);
        styleScrollPaneParts(pane);
        if (!Boolean.TRUE.equals(pane.getProperties().get("lms-scroll-skin-style"))) {
            pane.getProperties().put("lms-scroll-skin-style", true);
            pane.skinProperty().addListener((observable, oldSkin, skin) -> {
                if (skin != null) {
                    javafx.application.Platform.runLater(() -> {
                        applyScrollContent(pane);
                        styleScrollPaneParts(pane);
                    });
                }
            });
        }
    }

    private static void applyScrollContent(ScrollPane pane) {
        if (pane.getContent() instanceof Node content) {
            applyTree(content);
        }
    }

    private static void styleScrollPaneParts(ScrollPane pane) {
        pane.lookupAll(".viewport").forEach(viewport -> background(viewport, Color.TRANSPARENT));
        if (has(pane, "sidebar-scroll-pane")) {
            pane.lookupAll(".scroll-bar").forEach(bar -> background(bar, Color.TRANSPARENT));
            pane.lookupAll(".track-background").forEach(track -> background(track, Color.TRANSPARENT));
            pane.lookupAll(".track").forEach(track -> background(track, Color.TRANSPARENT));
            pane.lookupAll(".corner").forEach(corner -> background(corner, Color.TRANSPARENT));
            pane.lookupAll(".increment-button").forEach(button -> background(button, Color.TRANSPARENT));
            pane.lookupAll(".decrement-button").forEach(button -> background(button, Color.TRANSPARENT));
            pane.lookupAll(".thumb").forEach(thumb -> background(thumb, SIDEBAR_HOVER));
        } else if (!has(pane, "auth-scroll-pane")) {
            pane.lookupAll(".thumb").forEach(thumb -> background(thumb, color("#2f7a58")));
        }
    }

    private static void chartShape(Shape shape) {
        for (int i = 0; i < 8; i++) {
            Color color = chartColor(i);
            if (has(shape, "default-color" + i)) {
                if (has(shape, "chart-series-line")) {
                    shape.setStroke(color);
                } else {
                    shape.setFill(color);
                }
                return;
            }
        }
        if (has(shape, "chart-horizontal-grid-lines") || has(shape, "chart-vertical-grid-lines")) {
            shape.setStroke(color("#e5ece7"));
        }
        if (has(shape, "chart-vertical-zero-line")) {
            shape.setStroke(color("#cbd8ce"));
        }
    }

    private static void chart(Chart chart) {
        chart.lookupAll(".chart-plot-background").forEach(plot -> background(plot, CHART_PLOT));
        chart.lookupAll(".chart-title").forEach(title -> label(title, TEXT, 15, true));
        chart.lookupAll(".axis-label").forEach(label -> label(label, MUTED, 11, false));
        chart.lookupAll(".tick-mark").forEach(tick -> {
            if (tick instanceof Shape shape) {
                shape.setStroke(color("#cbd8ce"));
            }
        });
        chart.lookupAll(".chart-legend").forEach(legend -> {
            background(legend, Color.TRANSPARENT);
            if (legend instanceof Region region) {
                region.setPadding(new Insets(8));
            }
        });
    }

    private static Color chartColor(int index) {
        return switch (index) {
            case 0 -> GREEN;
            case 1 -> color("#2878b8");
            case 2 -> color("#e89336");
            case 3 -> color("#d95f59");
            case 4 -> color("#43505a");
            case 5 -> color("#4c9c66");
            case 6 -> color("#e6b85c");
            default -> color("#7691a5");
        };
    }

    private static void updateNavigationButton(Button button, boolean hovered) {
        boolean active = button.getStyleClass().contains("active");
        boolean highlighted = active || hovered;
        Color fill = highlighted ? SIDEBAR_HOVER : SIDEBAR;
        button.setBackground(new Background(new BackgroundFill(fill, new CornerRadii(9), Insets.EMPTY)));
        button.setBorder(javafx.scene.layout.Border.EMPTY);
        button.setTextFill(highlighted ? Color.WHITE : color("#d8e8de"));
        if (button.getGraphic() instanceof Region icon) {
            icon.setBackground(new Background(new BackgroundFill(
                    highlighted ? color("#b8e6c9") : Color.TRANSPARENT,
                    new CornerRadii(8), Insets.EMPTY)));
            Color ink = highlighted ? color("#0f4d31") : color("#bfe3cd");
            icon.lookupAll(".sidebar-nav-shape").forEach(node -> {
                if (node instanceof Shape shape) {
                    if (shape.getStroke() != null) {
                        shape.setStroke(ink);
                    } else {
                        shape.setFill(ink);
                    }
                }
            });
        }
    }

    private static void buttonBase(Button button, Color fill, Color text, Insets padding, double radius) {
        button.setTextFill(text);
        button.setPadding(padding);
        button.getProperties().put("lms-radius", radius);
        button.setBackground(new Background(new BackgroundFill(fill, new CornerRadii(radius), Insets.EMPTY)));
        button.setCursor(javafx.scene.Cursor.HAND);
    }

    private static void hover(Button button, Color hover, Color normal) {
        double radius = button.getProperties().containsKey("lms-radius")
                ? (Double) button.getProperties().get("lms-radius") : 7;
        button.setOnMouseEntered(event -> background(button, hover, radius));
        button.setOnMouseExited(event -> background(button, normal, radius));
    }

    private static void label(Node node, Color color, double size, boolean bold) {
        if (node instanceof Labeled labeled) {
            labeled.setTextFill(color);
            labeled.setFont(Font.font("Arial", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        }
    }

    private static void background(Node node, Color color) {
        if (node instanceof Region region) {
            region.setBackground(new Background(new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
        }
    }

    private static void background(Node node, Color color, double radius) {
        if (node instanceof Region region) {
            region.setBackground(new Background(new BackgroundFill(color, new CornerRadii(radius), Insets.EMPTY)));
        }
    }

    private static void background(Node node, LinearGradient gradient) {
        if (node instanceof Region region) {
            region.setBackground(new Background(new BackgroundFill(gradient, CornerRadii.EMPTY, Insets.EMPTY)));
        }
    }

    private static void background(Node node, Background background) {
        if (node instanceof Region region) {
            region.setBackground(background);
        }
    }

    private static void border(Node node, Color color, double radius) {
        if (node instanceof Region region) {
            region.setBorder(new javafx.scene.layout.Border(new javafx.scene.layout.BorderStroke(color,
                    javafx.scene.layout.BorderStrokeStyle.SOLID, new CornerRadii(radius),
                    new javafx.scene.layout.BorderWidths(1))));
        }
    }

    private static boolean has(Node node, String styleClass) {
        return node.getStyleClass().contains(styleClass);
    }

    private static boolean hasAncestorClass(Node node, String styleClass) {
        for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (has(parent, styleClass)) {
                return true;
            }
        }
        return false;
    }

    private static Color color(String value) {
        return Color.web(value);
    }

    private static Color translucent(Color base, double opacity) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), opacity);
    }
}
