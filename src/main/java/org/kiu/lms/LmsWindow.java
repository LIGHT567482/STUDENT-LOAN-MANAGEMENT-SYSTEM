package org.kiu.lms;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.Group;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.awt.Desktop;
import javax.imageio.ImageIO;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.function.Consumer;

public final class LmsWindow {
    private static final List<String> CURRENCIES = List.of("UGX", "USD");
    private static final String LOGO = Branding.logoPath();
    private static final String LOGIN_BACKGROUND = Branding.loginBackgroundPath();
    private static final Pattern TECHNICAL_MESSAGE = Pattern.compile(
            "(?i)^(?:[a-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*(?::.*)?$|^(?:SQLITE_[A-Z_]+|SQLSTATE\\[?[\\w]+\\]?).*$");
    private final Stage stage;
    private final LmsService service;
    private final BackupService backups;
    private User currentUser;
    private BorderPane shell;
    private BorderPane sidebar;
    private VBox navigation;
    private VBox content;
    private String dashboardCurrency = "UGX";
    private String ledgerCurrency = "UGX";
    private String selectedReport = "Portfolio summary";
    private boolean journalView;
    private long pageLoadGeneration;

    public LmsWindow(Stage stage, LmsService service, BackupService backups) {
        this.stage = stage;
        this.service = service;
        this.backups = backups;
        stage.setTitle(Branding.title());
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        stage.setMinWidth(Math.min(640, bounds.getWidth() * 0.8));
        stage.setMinHeight(Math.min(480, bounds.getHeight() * 0.7));
        stage.widthProperty().addListener((observable, previous, width) -> {
            if (sidebar != null) {
                sidebar.setPrefWidth(Math.max(205, Math.min(270, width.doubleValue() * 0.21)));
            }
        });
    }

    private void toggleStudent(Map<String, Object> student) {
        boolean restore = "Archived".equals(text(student, "status"));
        String action = restore ? "Restore student" : "Archive student";
        confirm(action, (restore ? "Restore " : "Archive ") + text(student, "name") + "?",
                () -> {
                    service.setStudentActive(currentUser, number(student, "id"), restore);
                    showPage("Students");
                });
    }

    public void showStart() {
        showStart(service.requiresFirstAdministrator());
    }

    public void showStart(boolean requiresFirstAdministrator) {
        if (requiresFirstAdministrator) {
            showFirstAdministrator();
        } else {
            showLogin();
        }
    }

    private void showFirstAdministrator() {
        TextField name = field("Full name");
        TextField username = field("Username");
        PasswordEntry password = passwordEntry("At least 8 characters");
        PasswordEntry confirm = passwordEntry("Confirm password");
        GridPane form = formGrid();
        addFormRow(form, 0, "Administrator name", name);
        addFormRow(form, 1, "Username", username);
        addFormRow(form, 2, "Password", password.container());
        addFormRow(form, 3, "Confirm password", confirm.container());
        showAuthentication("Welcome to KIU Loans", "Create the first administrator account to secure this installation.",
                form, "Create administrator", false, () -> {
                    if (!password.password().getText().equals(confirm.password().getText())) {
                        throw new IllegalArgumentException("The passwords do not match.");
                    }
                    currentUser = service.createFirstAdministrator(name.getText(), username.getText(),
                            password.password().getText().toCharArray());
                    showShell();
                });
    }

    private void showLogin() {
        TextField username = field("Username");
        PasswordEntry password = passwordEntry("Password", true);
        VBox form = new VBox(14,
                loginField("Username", username),
                loginField("Password", password.container()));
        form.setAlignment(Pos.CENTER);
        form.setMaxWidth(320);
        showAuthentication("Sign in", "",
                form, "Sign in", true, () -> {
                    requireLoginFields(username, password);
                    currentUser = service.login(username.getText(), password.password().getText().toCharArray());
                    showShell();
                });
        Runnable submit = () -> runAction(() -> {
            requireLoginFields(username, password);
            currentUser = service.login(username.getText(), password.password().getText().toCharArray());
            showShell();
        });
        password.setOnAction(submit);
        username.setOnAction(event -> password.password().requestFocus());
        username.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
                password.password().requestFocus();
                event.consume();
            }
        });
        password.password().addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.UP) {
                username.requestFocus();
                event.consume();
            }
        });
    }

    private void requireLoginFields(TextField username, PasswordEntry password) {
        if (username.getText().isBlank()) {
            username.requestFocus();
            throw new IllegalArgumentException("Username is required.");
        }
        if (password.password().getText().isBlank()) {
            password.password().requestFocus();
            throw new IllegalArgumentException("Password is required.");
        }
    }

    private void showAuthentication(String title, String subtitle, Node form, String actionLabel,
                                    boolean useLoginBackground, Runnable action) {
        pageLoadGeneration++;
        StackPane logo = logoBadge(132);
        Label heading = new Label(title);
        heading.getStyleClass().add("auth-title");
        Label subheading = new Label(subtitle);
        subheading.getStyleClass().add("muted");
        Button submit = new Button(actionLabel);
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(event -> runAction(action));
        VBox card = subtitle.isBlank()
                ? new VBox(18, heading, form, submit)
                : new VBox(18, heading, subheading, form, submit);
        if (useLoginBackground) {
            card.setAlignment(Pos.CENTER);
            heading.setMaxWidth(Double.MAX_VALUE);
            heading.setAlignment(Pos.CENTER);
        }
        card.getStyleClass().add("auth-card");
        card.setMaxWidth(useLoginBackground ? 420 : 520);
        Label brand = new Label(Branding.loginBrand());
        brand.getStyleClass().add("auth-brand");
        VBox outer = new VBox(20, logo, brand, card);
        outer.setAlignment(Pos.CENTER);
        outer.setPadding(new Insets(32));
        outer.getStyleClass().add(useLoginBackground ? "auth-login-content" : "auth-background");
        ScrollPane scrollableContent = new ScrollPane(outer);
        scrollableContent.setMinSize(0, 0);
        scrollableContent.setFitToWidth(true);
        scrollableContent.setFitToHeight(true);
        scrollableContent.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollableContent.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollableContent.getStyleClass().add("auth-scroll-pane");
        Parent root;
        if (useLoginBackground) {
            var backgroundResource = LmsWindow.class.getResource(LOGIN_BACKGROUND);
            if (backgroundResource == null) {
                throw new IllegalStateException("The login background image is missing: " + LOGIN_BACKGROUND);
            }
            Image background = new Image(backgroundResource.toExternalForm(), false);
            if (background.isError()) {
                throw new IllegalStateException("The login background image could not be loaded: " + LOGIN_BACKGROUND,
                        background.getException());
            }
            Region tint = new Region();
            tint.getStyleClass().add("auth-login-tint");
            tint.setMouseTransparent(true);
            StackPane loginRoot = new StackPane(tint, scrollableContent);
            loginRoot.setMinSize(0, 0);
            loginRoot.getStyleClass().add("auth-login-root");
            loginRoot.setBackground(new Background(new BackgroundImage(background,
                    BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER,
                    new BackgroundSize(100, 100, true, true, false, true))));
            root = loginRoot;
        } else {
            root = new BorderPane(scrollableContent);
        }
        setScene(root);
    }

    private void showShell() {
        shell = new BorderPane();
        shell.getStyleClass().add("root");
        sidebar = new BorderPane();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setMinWidth(205);
        sidebar.setPrefWidth(Math.max(205, Math.min(270, stage.getWidth() * 0.21)));

        Label brand = new Label(Branding.name());
        brand.getStyleClass().add("brand");
        Label brandSubtitle = new Label(Branding.subtitle());
        brandSubtitle.getStyleClass().add("sidebar-subtitle");
        StackPane logo = logoBadge(60);
        logo.getStyleClass().add("sidebar-logo");
        logo.setAlignment(Pos.CENTER);
        VBox sidebarHeader = new VBox(10, logo, brand, brandSubtitle);
        sidebarHeader.getStyleClass().add("sidebar-header");
        sidebarHeader.setAlignment(Pos.CENTER);
        sidebar.setTop(sidebarHeader);

        navigation = new VBox(4);
        navigation.getStyleClass().add("sidebar-navigation");
        navigation.setPadding(new Insets(6, 10, 8, 10));
        String currentSection = "";
        for (String page : pagesFor(currentUser.role())) {
            String section = List.of("Users", "Audit log", "Backup & restore").contains(page)
                    ? "ADMINISTRATION" : "WORKSPACE";
            if (!section.equals(currentSection)) {
                Label sectionLabel = new Label(section);
                sectionLabel.getStyleClass().add("sidebar-section");
                navigation.getChildren().add(sectionLabel);
                currentSection = section;
            }
            Button button = new Button(page);
            button.getStyleClass().add("nav-button");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setGraphic(sidebarNavIcon(page));
            button.setGraphicTextGap(12);
            button.setAlignment(Pos.CENTER_LEFT);
            button.setMinHeight(30);
            button.setPrefHeight(40);
            button.setMaxHeight(48);
            VBox.setVgrow(button, Priority.ALWAYS);
            button.setOnAction(event -> runAction(() -> showPage(page)));
            navigation.getChildren().add(button);
        }
        sidebar.setCenter(navigation);

        Label accountInitials = new Label(initials(currentUser.displayName()));
        accountInitials.getStyleClass().add("sidebar-avatar");
        VBox accountDetails = new VBox(3,
                new Label(currentUser.displayName()), new Label(roleName(currentUser.role())));
        accountDetails.getStyleClass().add("sidebar-account-details");
        HBox sidebarAccount = new HBox(10, accountInitials, accountDetails);
        sidebarAccount.getStyleClass().add("sidebar-account");
        sidebarAccount.setAlignment(Pos.CENTER_LEFT);
        sidebarAccount.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(accountDetails, Priority.ALWAYS);
        Button signOut = new Button("Sign out");
        signOut.getStyleClass().addAll("button", "sidebar-sign-out");
        signOut.setMaxWidth(Double.MAX_VALUE);
        signOut.setMinHeight(36);
        signOut.setOnAction(event -> {
            currentUser = null;
            showLogin();
        });
        VBox sidebarFooter = new VBox(8, new Separator(), sidebarAccount, signOut);
        sidebarFooter.getStyleClass().add("sidebar-footer");
        sidebar.setBottom(sidebarFooter);
        shell.setLeft(sidebar);

        content = new VBox();
        content.setFillWidth(true);
        content.setMinWidth(0);
        shell.setCenter(content);
        setScene(shell);
        showPage("Dashboard");
    }

    private StackPane sidebarNavIcon(String page) {
        Node iconShape = switch (page) {
            case "Dashboard" -> navFillIcon("M1 1h6v6h-6z M9 1h6v6h-6z M1 9h6v6h-6z M9 9h6v6h-6z");
            case "Students" -> navFillIcon(
                    "M8 1.5a3.2 3.2 0 1 1 0 6.4 3.2 3.2 0 1 1 0-6.4z"
                            + " M1.5 14.5c0-3.1 2.9-4.9 6.5-4.9s6.5 1.8 6.5 4.9z");
            case "Loan products" -> navFillIcon("M8 1.2l6.4 3.2v7.2l-6.4 3.2-6.4-3.2v-7.2z");
            case "Applications" -> navEvenOddIcon(
                    "M3 3h10v12.5h-10z M6 1h4v2h-4z"
                            + " M5.2 9l1.8 1.8 3.4-3.6 1.2 1.2-4.6 4.8-3-3z");
            case "Loans & payments" -> navEvenOddIcon(
                    "M1 4h14v8h-14z M8 5.5a2.5 2.5 0 1 1 0 5 2.5 2.5 0 1 1 0-5z");
            case "Ledger" -> navEvenOddIcon(
                    "M2.5 1.5h11v13h-11z M5 4.8h6v1.4h-6z M5 7.6h6v1.4h-6z M5 10.4h6v1.4h-6z");
            case "Reports" -> navFillIcon("M1.5 9h3.2v5.5h-3.2z M6.4 5.5h3.2v9h-3.2z M11.3 2h3.2v12.5h-3.2z");
            case "Users" -> navFillIcon(
                    "M6 2a2.7 2.7 0 1 1 0 5.4 2.7 2.7 0 1 1 0-5.4z"
                            + " M0.8 14.2c0-2.7 2.3-4.2 5.2-4.2s5.2 1.5 5.2 4.2z"
                            + " M11.6 3.4a2.2 2.2 0 1 1 0 4.4 2.2 2.2 0 1 1 0-4.4z"
                            + " M11.2 9.6c2.5 0 4.4 1.4 4.4 3.6v1h-4z");
            case "Audit log" -> navClockIcon();
            case "Backup & restore" -> navFillIcon(
                    "M7.3 1h1.4v6.1h2.3l-3 3.4-3-3.4h2.3z M2.6 10.5h10.8v4.3h-10.8z");
            default -> navFillIcon("M3 3h10v10h-10z");
        };
        StackPane icon = new StackPane(iconShape);
        icon.getStyleClass().add("sidebar-nav-icon");
        icon.setMinSize(20, 20);
        icon.setPrefSize(26, 26);
        icon.setMaxSize(26, 26);
        return icon;
    }

    private static SVGPath navFillIcon(String content) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.setFill(javafx.scene.paint.Color.web("#bfe3cd"));
        path.getStyleClass().add("sidebar-nav-shape");
        return path;
    }

    private static SVGPath navEvenOddIcon(String content) {
        SVGPath path = navFillIcon(content);
        path.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
        return path;
    }

    private static Node navClockIcon() {
        Circle face = new Circle(8, 8, 6.6);
        face.setFill(javafx.scene.paint.Color.TRANSPARENT);
        face.setStroke(javafx.scene.paint.Color.web("#bfe3cd"));
        face.setStrokeWidth(1.6);
        face.getStyleClass().add("sidebar-nav-shape");
        Line hourHand = new Line(8, 8, 8, 4.7);
        Line minuteHand = new Line(8, 8, 10.9, 9.4);
        for (Line hand : List.of(hourHand, minuteHand)) {
            hand.setStroke(javafx.scene.paint.Color.web("#bfe3cd"));
            hand.setStrokeWidth(1.6);
            hand.getStyleClass().add("sidebar-nav-shape");
        }
        return new Group(face, hourHand, minuteHand);
    }

    private static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, 1).toUpperCase();
        }
        return (words[0].substring(0, 1) + words[words.length - 1].substring(0, 1)).toUpperCase();
    }

    private void showPage(String page) {
        long request = ++pageLoadGeneration;
        for (Node node : navigation.getChildren()) {
            if (node instanceof Button button) {
                button.getStyleClass().remove("active");
                if (button.getText().equals(page)) {
                    button.getStyleClass().add("active");
                }
                UiStyles.apply(button);
            }
        }
        Label title = new Label(page);
        title.getStyleClass().add("page-title");
        HBox header = new HBox(16, title);
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        UiStyles.applyTree(header);
        showPageLoading(header, page);
        loadPage(request, page);
    }

    private Node createPage(String page) {
        return switch (page) {
            case "Dashboard" -> dashboardPage();
            case "Students" -> studentsPage();
            case "Loan products" -> productsPage();
            case "Applications" -> applicationsPage();
            case "Loans & payments" -> loansPage();
            case "Ledger" -> ledgerPage();
            case "Reports" -> reportsPage();
            case "Users" -> usersPage();
            case "Audit log" -> auditPage();
            case "Backup & restore" -> backupPage();
            default -> new Label("This screen is not available.");
        };
    }

    private void showPageLoading(Node header, String page) {
        ProgressIndicator progress = new ProgressIndicator();
        progress.setMaxSize(44, 44);
        VBox loading = new VBox(12, progress, new Label("Loading " + page + "..."));
        loading.setAlignment(Pos.CENTER);
        content.getChildren().setAll(header, loading);
        VBox.setVgrow(loading, Priority.ALWAYS);
    }

    private void loadPage(long request, String page) {
        Task<Node> task = new Task<>() {
            @Override
            protected Node call() {
                return createPage(page);
            }
        };
        task.setOnSucceeded(event -> {
            if (request != pageLoadGeneration || stage.getScene().getRoot() != shell) {
                return;
            }
            Node pageContent = task.getValue();
            content.getChildren().set(1, pageContent);
            VBox.setVgrow(content.getChildren().get(1), Priority.ALWAYS);
            Platform.runLater(() -> UiStyles.applyTree(pageContent));
        });
        task.setOnFailed(event -> {
            if (request == pageLoadGeneration && stage.getScene().getRoot() == shell) {
                Label failure = new Label(page + " could not be loaded.");
                Button retry = button("Retry", () -> showPage(page));
                VBox errorState = new VBox(12, failure, retry);
                errorState.setAlignment(Pos.CENTER);
                content.getChildren().set(1, errorState);
                VBox.setVgrow(errorState, Priority.ALWAYS);
                showError(exceptionMessage(task.getException()));
            }
        });
        Thread loader = new Thread(task, "lms-page-loader-" + request);
        loader.setDaemon(true);
        loader.start();
    }

    private FlowPane responsiveTiles(double gap, int preferredColumns, Node... children) {
        FlowPane tiles = new FlowPane(gap, gap, children);
        tiles.widthProperty().addListener((observable, previous, width) -> {
            double available = width.doubleValue();
            if (available <= 0) {
                return;
            }
            int maxColumns = Math.max(1, (int) ((available + gap) / (140 + gap)));
            int columns = Math.max(1, Math.min(children.length, Math.min(preferredColumns, maxColumns)));
            double tileWidth = (available - gap * (columns - 1)) / columns - 1;
            for (Node child : children) {
                if (child instanceof Region region) {
                    region.setMinWidth(0);
                    region.setPrefWidth(tileWidth);
                    region.setMaxWidth(Double.MAX_VALUE);
                }
            }
        });
        return tiles;
    }

    private Node dashboardPage() {
        ChoiceBox<String> currency = currencyChoice();
        currency.setValue(dashboardCurrency);
        HBox actions = new HBox(12, new Label("Currency"), currency);
        currency.setOnAction(event -> {
            dashboardCurrency = currency.getValue();
            showPage("Dashboard");
        });
        Map<String, Long> stats = service.dashboard(currentUser, currency.getValue());
        FlowPane cards = responsiveTiles(12, 5,
                statCard("Students", stats.get("students").toString(),
                        "Students registered in the system."),
                statCard("Active loans", stats.get("activeLoans").toString(),
                        "Loans disbursed and not yet fully repaid."),
                statCard("Awaiting decision", stats.get("awaiting").toString(),
                        "Submitted applications still waiting for approval or rejection."),
                statCard("Overdue installments", stats.get("overdue").toString(),
                        "Installments past their due date - follow up with the borrowers."),
                statCard("Principal outstanding", Money.format(stats.get("outstanding"), currency.getValue()),
                        "Principal lent out and not yet recovered."),
                statCard("Collected", Money.format(stats.get("collected"), currency.getValue()),
                        "Payments received from borrowers to date."),
                statCard("Disbursed principal", Money.format(stats.get("disbursed"), currency.getValue()),
                        "Principal paid out to borrowers."),
                statCard("Pending requests", Money.format(stats.get("pendingRequested"), currency.getValue()),
                        "Requested amounts awaiting funds release."),
                statCard("Due in next 30 days", stats.get("dueSoon").toString(),
                        "Installments falling due within the next 30 days."),
                statCard("Cash available", Money.format(stats.get("cash"), currency.getValue()),
                        "Liquid funds currently held by the institution."));
        PieChart applications = new PieChart();
        applications.setTitle("Applications by status");
        List<Map<String, Object>> statusRows = service.applicationStatusCounts(currency.getValue());
        long statusTotal = statusRows.stream().mapToLong(row -> number(row, "total")).sum();
        for (Map<String, Object> row : statusRows) {
            String status = text(row, "status");
            long total = number(row, "total");
            PieChart.Data slice = new PieChart.Data(status + " (" + total + ")", total);
            UiStyles.tipOnNode(slice.nodeProperty(), status + ": " + total + " application"
                    + (total == 1 ? "" : "s") + share(total, statusTotal));
            applications.getData().add(slice);
        }
        applications.setLegendVisible(true);
        applications.setLabelsVisible(true);
        applications.setPrefHeight(320);
        applications.setMinSize(0, 0);
        BarChart<String, Number> activity = new BarChart<>(new CategoryAxis(), new NumberAxis());
        activity.setTitle("Disbursed vs collected · last 6 months");
        activity.setLegendVisible(true);
        XYChart.Series<String, Number> disbursed = new XYChart.Series<>();
        disbursed.setName("Disbursed");
        XYChart.Series<String, Number> collected = new XYChart.Series<>();
        collected.setName("Collected");
        for (Map<String, Object> row : service.monthlyActivity(currency.getValue())) {
            String month = text(row, "month");
            long disbursedAmount = number(row, "disbursed");
            long collectedAmount = number(row, "collected");
            XYChart.Data<String, Number> disbursedPoint =
                    new XYChart.Data<>(month, amountForChart(disbursedAmount, currency.getValue()));
            UiStyles.tipOnNode(disbursedPoint.nodeProperty(),
                    "Disbursed · " + month + "\n" + Money.format(disbursedAmount, currency.getValue()));
            disbursed.getData().add(disbursedPoint);
            XYChart.Data<String, Number> collectedPoint =
                    new XYChart.Data<>(month, amountForChart(collectedAmount, currency.getValue()));
            UiStyles.tipOnNode(collectedPoint.nodeProperty(),
                    "Collected · " + month + "\n" + Money.format(collectedAmount, currency.getValue()));
            collected.getData().add(collectedPoint);
        }
        activity.getData().add(disbursed);
        activity.getData().add(collected);
        activity.setPrefHeight(320);
        activity.setMinSize(0, 0);

        PieChart repayments = new PieChart();
        repayments.setTitle("Installment health");
        List<Map<String, Object>> healthRows = service.installmentHealth(currency.getValue());
        long healthTotal = healthRows.stream().mapToLong(row -> number(row, "total")).sum();
        for (Map<String, Object> row : healthRows) {
            String category = text(row, "category");
            long total = number(row, "total");
            PieChart.Data slice = new PieChart.Data(category + " (" + total + ")", total);
            UiStyles.tipOnNode(slice.nodeProperty(), category + " installments: " + total + share(total, healthTotal));
            repayments.getData().add(slice);
        }
        repayments.setLegendVisible(true);
        repayments.setLabelsVisible(true);
        repayments.setPrefHeight(320);
        repayments.setMinSize(0, 0);

        LineChart<String, Number> applicationTrend = new LineChart<>(new CategoryAxis(), new NumberAxis());
        applicationTrend.setTitle("Applications submitted · last 6 months");
        applicationTrend.setLegendVisible(false);
        XYChart.Series<String, Number> applicationsByMonth = new XYChart.Series<>();
        applicationsByMonth.setName("Applications");
        for (Map<String, Object> row : service.monthlyApplications(currency.getValue())) {
            String month = text(row, "month");
            long total = number(row, "applications");
            XYChart.Data<String, Number> point = new XYChart.Data<>(month, total);
            UiStyles.tipOnNode(point.nodeProperty(), month + "\n" + total + " application"
                    + (total == 1 ? "" : "s") + " submitted");
            applicationsByMonth.getData().add(point);
        }
        applicationTrend.getData().add(applicationsByMonth);
        applicationTrend.setCreateSymbols(true);
        applicationTrend.setPrefHeight(320);
        applicationTrend.setMinSize(0, 0);

        StackedBarChart<String, Number> collections = new StackedBarChart<>(new CategoryAxis(), new NumberAxis());
        collections.setTitle("Collection composition · last 6 months");
        collections.setLegendVisible(true);
        List<Map<String, Object>> monthlyCollections =
                service.monthlyCollectionComposition(currency.getValue());
        for (String component : List.of("principal", "interest", "penalty")) {
            XYChart.Series<String, Number> series = new XYChart.Series<>();
            series.setName(component.substring(0, 1).toUpperCase() + component.substring(1));
            for (Map<String, Object> row : monthlyCollections) {
                String month = text(row, "month");
                long amount = number(row, component);
                XYChart.Data<String, Number> point =
                        new XYChart.Data<>(month, amountForChart(amount, currency.getValue()));
                UiStyles.tipOnNode(point.nodeProperty(), component.substring(0, 1).toUpperCase()
                        + component.substring(1) + " · " + month + "\n" + Money.format(amount, currency.getValue()));
                series.getData().add(point);
            }
            collections.getData().add(series);
        }
        collections.setPrefHeight(320);
        collections.setMinSize(0, 0);

        BarChart<String, Number> productPortfolio = new BarChart<>(new CategoryAxis(), new NumberAxis());
        productPortfolio.setTitle("Disbursed principal by product");
        productPortfolio.setLegendVisible(false);
        XYChart.Series<String, Number> principalByProduct = new XYChart.Series<>();
        principalByProduct.setName("Principal");
        for (Map<String, Object> row : service.portfolioByProduct(currency.getValue())) {
            String product = text(row, "product");
            long principal = number(row, "principal");
            long loans = number(row, "loans");
            XYChart.Data<String, Number> point =
                    new XYChart.Data<>(product, amountForChart(principal, currency.getValue()));
            UiStyles.tipOnNode(point.nodeProperty(), product + "\nDisbursed principal: "
                    + Money.format(principal, currency.getValue()) + "\n" + loans + " loan" + (loans == 1 ? "" : "s"));
            principalByProduct.getData().add(point);
        }
        productPortfolio.getData().add(principalByProduct);
        productPortfolio.setPrefHeight(320);
        productPortfolio.setMinSize(0, 0);

        FlowPane charts = new FlowPane(16, 16);
        List<VBox> chartPanels = List.of(panel(applications), panel(activity), panel(repayments),
                panel(applicationTrend), panel(collections), panel(productPortfolio));
        for (int index = 0; index < chartPanels.size(); index++) {
            VBox chartPanel = chartPanels.get(index);
            chartPanel.setMinWidth(0);
            chartPanel.setMaxWidth(Double.MAX_VALUE);
            if (chartPanel.getChildren().getFirst() instanceof Region chart) {
                chart.setMinWidth(0);
                chart.setPrefHeight(320);
            }
        }
        charts.getChildren().addAll(chartPanels);
        charts.widthProperty().addListener((observable, previous, width) -> {
            double available = width.doubleValue();
            if (available <= 0) {
                return;
            }
            int columns = available >= 3 * 300 + 2 * charts.getHgap() ? 3
                    : available >= 2 * 380 + charts.getHgap() ? 2 : 1;
            double panelWidth = (available - charts.getHgap() * (columns - 1)) / columns - 1;
            chartPanels.forEach(chartPanel -> chartPanel.setPrefWidth(panelWidth));
        });
        VBox page = pageContainer(actions, cards, charts);
        ScrollPane scroll = scroll(page);
        return scroll;
    }

    private Node studentsPage() {
        List<Button> actions = new ArrayList<>();
        if (currentUser.role() == Role.OFFICER) {
            actions.add(button("Add student", () -> studentDialog(null)));
            actions.add(button("Edit selected", () -> withSelection("Select a student to edit.", row -> studentDialog(row))));
            actions.add(button("Archive / restore", () ->
                    withSelection("Select a student to archive or restore.", this::toggleStudent)));
        }
        return tablePage("Students", service.students(),
                List.of("photo", "studentId", "name", "faculty", "year", "phone", "email", "guarantor", "guarantorPhone", "relation", "status"),
                actions);
    }

    private Node productsPage() {
        List<Button> actions = new ArrayList<>();
        if (currentUser.role() == Role.ADMINISTRATOR) {
            actions.add(button("Add product", () -> productDialog(null)));
            actions.add(button("Edit selected", () -> withSelection("Select a product to edit.", row -> productDialog(row))));
            actions.add(button("Activate / deactivate", () -> withSelection("Select a product.", this::toggleProduct)));
            actions.add(button("Delete selected", () -> withSelection("Select a product to delete.", this::deleteProduct)));
        }
        return tablePage("Loan products", service.products(),
                List.of("name", "currency", "minimum", "maximum", "annualRate", "maxTerm", "penaltyRate", "status"),
                actions);
    }

    private Node applicationsPage() {
        List<Node> actions = new ArrayList<>();
        actions.add(button("View selected evidence", () ->
                withSelection("Select an application to view its evidence.", this::showApplicationEvidence)));
        Button approve = null;
        Button reject = null;
        Button stall = null;
        Button addEvidence = null;
        Button disburse = null;
        if (canSubmitApplications()) {
            actions.add(button("New application", this::applicationDialog));
            approve = button("Approve", () -> withSelection("Select a submitted or stalled application.", row -> decide(row, true)));
            reject = button("Reject", () -> withSelection("Select a submitted or stalled application.", row -> decide(row, false)));
            stall = button("Stall", () -> withSelection("Select a submitted or stalled application.", this::stall));
            addEvidence = button("Add evidence", () -> withSelection("Select a submitted or stalled application.", this::addEvidence));
            actions.add(approve);
            actions.add(reject);
            actions.add(stall);
            actions.add(addEvidence);
        }
        if (canDisburse()) {
            disburse = button("Disburse approved", () -> withSelection("Select an approved application.", this::disburse));
            actions.add(disburse);
        }
        VBox page = tablePage("Applications", service.applications(),
                List.of("studentId", "student", "product", "currency", "amount", "term", "purpose", "status", "creator", "decider", "note", "created"),
                actions);
        TableView<Map<String, Object>> table = findTable(page);
        Button approveButton = approve;
        Button rejectButton = reject;
        Button stallButton = stall;
        Button addEvidenceButton = addEvidence;
        Button disburseButton = disburse;
        table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            boolean decidable = selected != null
                    && ("SUBMITTED".equals(text(selected, "status")) || "STALLED".equals(text(selected, "status")));
            boolean ownOfficerApplication = selected != null && currentUser.role() == Role.OFFICER
                    && number(selected, "creatorId") == currentUser.id();
            if (approveButton != null) {
                approveButton.setDisable(!decidable || ownOfficerApplication);
            }
            if (rejectButton != null) {
                rejectButton.setDisable(!decidable || ownOfficerApplication);
            }
            if (stallButton != null) {
                stallButton.setDisable(!decidable || ownOfficerApplication);
            }
            if (addEvidenceButton != null) {
                addEvidenceButton.setDisable(!decidable);
            }
            if (disburseButton != null) {
                disburseButton.setDisable(selected == null || !"APPROVED".equals(text(selected, "status")));
            }
        });
        if (approveButton != null) {
            approveButton.setDisable(true);
            rejectButton.setDisable(true);
            stallButton.setDisable(true);
            addEvidenceButton.setDisable(true);
        }
        if (disburseButton != null) {
            disburseButton.setDisable(true);
        }
        return page;
    }

    private Node loansPage() {
        List<Button> actions = new ArrayList<>();
        actions.add(button("Installments", () -> withSelection("Select a loan.", this::showInstallments)));
        actions.add(button("Payment history", () -> withSelection("Select a loan.", this::showLoanPayments)));
        Button payment = null;
        if (canDisburse()) {
            payment = button("Record repayment", () -> withSelection("Select an active loan.", this::paymentDialog));
            actions.add(payment);
        }
        VBox page = tablePage("Loans & payments", service.loans(currentUser),
                List.of("studentId", "student", "currency", "principal", "term", "disbursed", "status", "outstanding", "penalty", "overdue"),
                actions);
        if (payment != null) {
            Button paymentButton = payment;
            TableView<Map<String, Object>> table = findTable(page);
            paymentButton.setDisable(true);
            table.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) ->
                    paymentButton.setDisable(selected == null || !"ACTIVE".equals(text(selected, "status"))));
        }
        return page;
    }

    private Node ledgerPage() {
        ChoiceBox<String> currency = currencyChoice();
        currency.setValue(ledgerCurrency);
        Button journals = button(journalView ? "Trial balance" : "Journal entries", () -> {
            journalView = !journalView;
            showPage("Ledger");
        });
        FlowPane actions = new FlowPane(10, 10, new Label("Currency"), currency, journals);
        if (canDisburse()) {
            actions.getChildren().add(button("Add loan funds", () -> fundsDialog(currency.getValue())));
        }
        currency.setOnAction(event -> {
            ledgerCurrency = currency.getValue();
            runAction(() -> showPage("Ledger"));
        });
        if (journalView) {
            return tablePage("Journal entries · " + currency.getValue(), service.journalEntries(currency.getValue()),
                    List.of("date", "memo", "reference", "currency", "postedBy", "account", "debit", "credit"),
                    List.of(actions.getChildren().toArray(Button[]::new)));
        }
        return tablePage("Trial balance · " + currency.getValue(), service.ledger(currency.getValue()),
                List.of("code", "name", "currency", "debits", "credits", "balance"),
                List.of(actions.getChildren().toArray(Button[]::new)));
    }

    private Node reportsPage() {
        ChoiceBox<String> reports = new ChoiceBox<>(FXCollections.observableArrayList(
                "Portfolio summary", "Overdue installments", "Collections by month",
                "Disbursements by faculty", "Applications by status"));
        reports.setValue("Portfolio summary");
        reports.setValue(selectedReport);
        Button load = button("Show report", () -> {
            selectedReport = reports.getValue();
            showPage("Reports");
        });
        Button exportCsv = button("Export CSV", () -> exportReport(reports.getValue(), false));
        Button exportPdf = button("Export PDF", () -> exportReport(reports.getValue(), true));
        FlowPane actions = new FlowPane(10, 10, reports, load, exportCsv, exportPdf);
        return tablePage(reports.getValue(), service.report(reports.getValue()), reportColumns(reports.getValue()), List.of(actions));
    }

    private Node usersPage() {
        List<Button> actions = new ArrayList<>();
        actions.add(button("Add user", this::userDialog));
        actions.add(button("Reset password", () -> withSelection("Select a user.", this::resetPasswordDialog)));
        actions.add(button("Activate / deactivate", () -> withSelection("Select a user.", this::toggleUser)));
        return tablePage("Users", service.users(), List.of("username", "name", "role", "status"), actions);
    }

    private Node auditPage() {
        List<Map<String, Object>> rows = service.auditLog();
        List<Button> actions = List.of(button("Refresh", () -> showPage("Audit log")));
        VBox page = tablePage("Audit log · " + service.unsyncedCount() + " changes awaiting sync",
                rows, List.of("time", "user", "action", "entity", "entityId", "detail"), actions);
        Label device = new Label("Device ID: " + service.deviceId() + "    ·    Unsynced outbox rows: " + service.unsyncedCount());
        device.getStyleClass().add("muted");
        page.getChildren().add(1, device);
        return page;
    }

    private Node backupPage() {
        Button create = button("Create encrypted backup", this::createBackup);
        Button restore = button("Restore backup", this::restoreBackup);
        Label info = new Label("Backups use AES-256-GCM. Keep the passphrase safe; it cannot be recovered.");
        info.getStyleClass().add("muted");
        VBox panel = new VBox(14, new Label("Local database: " + service.database().file()),
                info, new FlowPane(10, 10, create, restore));
        panel.getStyleClass().add("panel");
        return pageContainer(panel);
    }

    private VBox tablePage(String title, List<Map<String, Object>> rows, List<String> requestedColumns,
                           List<? extends Node> actions) {
        VBox page = new VBox(14);
        page.getStyleClass().add("page");
        page.setMinWidth(0);
        Label subheading = new Label(title);
        subheading.setTextFill(javafx.scene.paint.Color.web("#334155"));
        subheading.setFont(javafx.scene.text.Font.font("Arial", javafx.scene.text.FontWeight.BOLD, 14));
        TextField search = field("Search this list...");
        search.setPrefWidth(280);
        search.setMinWidth(0);
        search.setMaxWidth(Double.MAX_VALUE);
        FlowPane toolbar = new FlowPane(10, 10, search);
        toolbar.getChildren().addAll(actions);
        toolbar.widthProperty().addListener((observable, previous, width) ->
                search.setPrefWidth(Math.min(280, Math.max(120, width.doubleValue() * 0.45))));
        page.getChildren().addAll(subheading, toolbar);
        List<String> columns = requestedColumns;
        if (columns == null) {
            columns = rows.isEmpty() ? List.of() : new ArrayList<>(rows.getFirst().keySet());
        }
        TableView<Map<String, Object>> table = dataTable(rows, columns);
        FilteredList<Map<String, Object>> filtered = new FilteredList<>(table.getItems(), row -> true);
        search.textProperty().addListener((observable, previous, value) -> {
            String term = value == null ? "" : value.toLowerCase();
            filtered.setPredicate(row -> row.values().stream()
                    .filter(Objects::nonNull).anyMatch(item -> item.toString().toLowerCase().contains(term)));
        });
        SortedList<Map<String, Object>> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        VBox.setVgrow(table, Priority.ALWAYS);
        page.getChildren().add(table);
        page.setUserData(table);
        return page;
    }

    private TableView<Map<String, Object>> dataTable(List<Map<String, Object>> rows, List<String> columns) {
        TableView<Map<String, Object>> table = new TableView<>(FXCollections.observableArrayList(rows));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setPlaceholder(new Label("No records to display."));
        for (String key : columns) {
            TableColumn<Map<String, Object>, Object> column = new TableColumn<>(columnName(key));
            column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().get(key)));
            if ("photo".equals(key)) {
                column.setPrefWidth(70);
                column.setMinWidth(70);
                column.setMaxWidth(90);
                column.setCellFactory(ignored -> photoCell());
            } else {
                column.setCellFactory(ignored -> new TableCell<>() {
                    @Override
                    protected void updateItem(Object value, boolean empty) {
                        super.updateItem(value, empty);
                        Map<String, Object> row = getTableRow() == null ? null : getTableRow().getItem();
                        setText(empty || row == null ? null : formatCell(key, row, value));
                        getStyleClass().removeAll("status-approved", "status-paid", "status-active",
                                "status-submitted", "status-partial", "status-pending",
                                "status-rejected", "status-closed", "status-inactive");
                        if (!empty && value != null) {
                            String status = value.toString().toLowerCase();
                            if (List.of("approved", "paid", "active").contains(status)) {
                                getStyleClass().add("status-" + status);
                            } else if (List.of("submitted", "partial", "pending").contains(status)) {
                                getStyleClass().add("status-" + status);
                            } else if (List.of("rejected", "closed", "inactive").contains(status)) {
                                getStyleClass().add("status-" + status);
                            }
                        }
                    }
                });
            }
            column.setSortable(true);
            table.getColumns().add(column);
        }
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.SINGLE);
        return table;
    }

    private static final double PHOTO_THUMB = 36;
    private static final double PHOTO_ZOOM = 132;

    private TableCell<Map<String, Object>, Object> photoCell() {
        return new TableCell<>() {
            private final ImageView thumbnail = new ImageView();
            private final StackPane holder = new StackPane(thumbnail);
            private Path loaded;

            {
                holder.setAlignment(Pos.CENTER);
                setGraphic(holder);
                setAlignment(Pos.CENTER);
                thumbnail.setPreserveRatio(true);
                thumbnail.setFitWidth(PHOTO_THUMB);
                thumbnail.setFitHeight(PHOTO_THUMB);
                holder.setPrefSize(PHOTO_THUMB, PHOTO_THUMB);
                holder.setMinSize(PHOTO_THUMB, PHOTO_THUMB);
                holder.setMaxSize(PHOTO_THUMB, PHOTO_THUMB);
                holder.setOnMouseEntered(event -> zoom(true));
                holder.setOnMouseExited(event -> zoom(false));
                holder.setOnMouseClicked(event -> zoom(!isZoomed()));
            }

            private boolean isZoomed() {
                return thumbnail.getFitWidth() > PHOTO_THUMB + 1;
            }

            private void zoom(boolean on) {
                double target = on ? PHOTO_ZOOM : PHOTO_THUMB;
                thumbnail.setFitWidth(target);
                thumbnail.setFitHeight(target);
                holder.setPrefSize(target, target);
                holder.setMinSize(target, target);
                holder.setMaxSize(target, target);
                if (on) {
                    toFront();
                    setStyle("-fx-background-color: rgba(255,255,255,0.96);");
                    getTableView().lookupAll(".table-row-cell").forEach(node -> {
                        if (node instanceof javafx.scene.Parent) {
                            node.toBack();
                        }
                    });
                } else {
                    setStyle(null);
                }
            }

            @Override
            protected void updateItem(Object value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || value.toString().isBlank()) {
                    setGraphic(null);
                    loaded = null;
                    return;
                }
                setGraphic(holder);
                Path file = Path.of(value.toString()).isAbsolute()
                        ? Path.of(value.toString())
                        : service.database().file().getParent().resolve(value.toString());
                if (loaded == null || !loaded.equals(file)) {
                    loaded = file;
                    if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            Image image = new Image(file.toUri().toString(), true);
                            thumbnail.setImage(image);
                        } catch (RuntimeException ignored) {
                            thumbnail.setImage(null);
                        }
                    } else {
                        thumbnail.setImage(null);
                    }
                }
            }
        };
    }

    private void studentDialog(Map<String, Object> existing) {
        boolean editing = existing != null;
        TextField studentId = field("Student ID");
        TextField name = field("Full name");
        TextField faculty = field("Faculty");
        TextField year = field("Year of study");
        TextField phone = field("Phone");
        TextField email = field("Email");
        TextField guarantor = field("Guarantor name");
        TextField guarantorPhone = field("Guarantor phone");
        TextField relation = field("Relation");
        TextField photoPath = evidencePathField();
        Path[] photoSource = new Path[1];
        if (editing) {
            studentId.setText(text(existing, "studentId"));
            name.setText(text(existing, "name"));
            faculty.setText(text(existing, "faculty"));
            year.setText(text(existing, "year"));
            phone.setText(text(existing, "phone"));
            email.setText(text(existing, "email"));
            guarantor.setText(text(existing, "guarantor"));
            guarantorPhone.setText(text(existing, "guarantorPhone"));
            relation.setText(text(existing, "relation"));
            String currentPhoto = text(existing, "photo");
            if (currentPhoto != null && !currentPhoto.isBlank()) {
                photoPath.setText("(current photo kept — choose a new file to replace)");
            }
        }
        GridPane form = formGrid();
        addFormRow(form, 0, "Student ID", studentId);
        addFormRow(form, 1, "Full name", name);
        addFormRow(form, 2, "Faculty", faculty);
        addFormRow(form, 3, "Year of study", year);
        addFormRow(form, 4, "Phone", phone);
        addFormRow(form, 5, "Email", email);
        addFormRow(form, 6, "Guarantor name", guarantor);
        addFormRow(form, 7, "Guarantor phone", guarantorPhone);
        addFormRow(form, 8, "Guarantor relation", relation);
        addFormRow(form, 9, "Passport photo (JPG/PNG)", evidencePicker("Choose image", photoPath,
                () -> chooseEvidence(photoPath, photoSource, "Passport photo", "*.jpg", "*.jpeg", "*.png")));
        formDialog(editing ? "Edit student" : "Add student", form, () -> {
            service.saveStudent(currentUser, editing ? number(existing, "id") : null, studentId.getText(),
                    name.getText(), faculty.getText(), parseInt(year.getText(), "Year of study"),
                    phone.getText(), email.getText(), guarantor.getText(), guarantorPhone.getText(), relation.getText(),
                    photoSource[0]);
            showPage("Students");
        });
    }

    private void productDialog(Map<String, Object> existing) {
        boolean editing = existing != null;
        TextField name = field("Product name");
        ChoiceBox<String> currency = currencyChoice();
        currency.setValue(editing ? text(existing, "currency") : "UGX");
        TextField minimum = field("Minimum amount");
        TextField maximum = field("Maximum amount");
        TextField annual = field("Annual interest rate (%)");
        TextField term = field("Maximum term in months");
        TextField penalty = field("Monthly penalty rate (%)");
        if (editing) {
            name.setText(text(existing, "name"));
            minimum.setText(decimalAmount(existing, "minimum", currency.getValue()));
            maximum.setText(decimalAmount(existing, "maximum", currency.getValue()));
            annual.setText(text(existing, "annualRate"));
            term.setText(text(existing, "maxTerm"));
            penalty.setText(text(existing, "penaltyRate"));
        }
        GridPane form = formGrid();
        addFormRow(form, 0, "Product name", name);
        addFormRow(form, 1, "Currency", currency);
        addFormRow(form, 2, "Minimum amount", minimum);
        addFormRow(form, 3, "Maximum amount", maximum);
        addFormRow(form, 4, "Annual rate (%)", annual);
        addFormRow(form, 5, "Maximum term (months)", term);
        addFormRow(form, 6, "Monthly penalty (%)", penalty);
        formDialog(editing ? "Edit loan product" : "Add loan product", form, () -> {
            service.saveProduct(currentUser, editing ? number(existing, "id") : null, name.getText(), currency.getValue(),
                    Money.parseMinor(minimum.getText(), currency.getValue()),
                    Money.parseMinor(maximum.getText(), currency.getValue()), parseDouble(annual.getText(), "Annual rate"),
                    parseInt(term.getText(), "Maximum term"), parseDouble(penalty.getText(), "Penalty rate"));
            showPage("Loan products");
        });
    }

    private void applicationDialog() {
        List<Map<String, Object>> students = service.query(
                "SELECT id,student_id,full_name FROM students WHERE active=1 ORDER BY full_name");
        List<Map<String, Object>> products = service.query("""
                SELECT id,name,currency,minimum_amount,maximum_amount,max_term_months,annual_rate,monthly_penalty_rate
                FROM loan_products
                WHERE active=1 ORDER BY name""");
        if (students.isEmpty() || products.isEmpty()) {
            showError("Register at least one student and create an active loan product before submitting an application.");
            return;
        }
        ChoiceBox<Map<String, Object>> student = choice(students, row ->
                text(row, "student_id") + " · " + text(row, "full_name"));
        ChoiceBox<Map<String, Object>> product = choice(products, row ->
                text(row, "name") + " · " + text(row, "currency"));
        TextField amount = field("Amount");
        TextField term = field("Term in months");
        TextField purpose = field("Purpose");
        TextField guarantorLetter = evidencePathField();
        TextField staffId = evidencePathField();
        TextField passportPhoto = evidencePathField();
        TextField applicantSignature = evidencePathField();
        TextField academicDocuments = evidencePathField();
        Path[] guarantorLetterPath = new Path[1];
        Path[] staffIdPath = new Path[1];
        Path[] passportPhotoPath = new Path[1];
        Path[] applicantSignaturePath = new Path[1];
        List<Path> academicDocumentPaths = new ArrayList<>();
        Label repaymentPreview = new Label("Choose a product and enter an amount and term to preview repayments.");
        repaymentPreview.setWrapText(true);
        repaymentPreview.getStyleClass().add("muted");
        ChoiceBox<String> stampAuthority = new ChoiceBox<>(FXCollections.observableArrayList(
                "Finance Director", "School/College/Faculty Dean"));
        stampAuthority.setValue("Finance Director");
        CheckBox stampConfirmed = new CheckBox("I confirm the letter bears this official stamp.");
        CheckBox guarantorSigned = new CheckBox("I confirm the guarantor has signed the guarantor letter.");
        CheckBox acceptTerms = new CheckBox("I have reviewed the proposed amount, rate, term and schedule, and agree to the terms.");
        Button agreementPreview = button("Preview draft PDF", () -> previewLoanAgreement(student.getValue(),
                product.getValue(), amount.getText(), term.getText(), purpose.getText(), stampAuthority.getValue(),
                applicantSignaturePath[0]));
        GridPane form = formGrid();
        addFormRow(form, 0, "Student", student);
        addFormRow(form, 1, "Product", product);
        addFormRow(form, 2, "Amount", amount);
        addFormRow(form, 3, "Term", term);
        addFormRow(form, 4, "Purpose", purpose);
        addFormRow(form, 5, "Guarantor letter (stamped PDF)", evidencePicker("Choose PDF", guarantorLetter,
                () -> chooseEvidence(guarantorLetter, guarantorLetterPath, "PDF files", "*.pdf")));
        addFormRow(form, 6, "Stamping authority", stampAuthority);
        addFormRow(form, 7, "Stamp attestation", stampConfirmed);
        addFormRow(form, 8, "Guarantor signature", guarantorSigned);
        addFormRow(form, 9, "Staff ID (PDF scan)", evidencePicker("Choose PDF", staffId,
                () -> chooseEvidence(staffId, staffIdPath, "PDF files", "*.pdf")));
        addFormRow(form, 10, "Academic documents (PDF)", evidencePicker("Choose PDFs", academicDocuments, () -> {
            FileChooser chooser = evidenceChooser("Choose academic document scans", "PDF files", "*.pdf");
            List<File> files = chooser.showOpenMultipleDialog(stage);
            if (files != null) {
                academicDocumentPaths.clear();
                academicDocumentPaths.addAll(files.stream().map(File::toPath).toList());
                academicDocuments.setText(String.join(", ", files.stream().map(File::getName).toList()));
            }
        }));
        addFormRow(form, 11, "Passport photo (JPG/PNG)", evidencePicker("Choose image", passportPhoto,
                () -> chooseEvidence(passportPhoto, passportPhotoPath, "Image files", "*.jpg", "*.jpeg", "*.png")));
        addFormRow(form, 12, "Applicant signature (PDF scan)", evidencePicker("Choose PDF", applicantSignature,
                () -> chooseEvidence(applicantSignature, applicantSignaturePath, "PDF files", "*.pdf")));
        addFormRow(form, 13, "Proposed repayment", repaymentPreview);
        addFormRow(form, 14, "Agreement", acceptTerms);
        addFormRow(form, 15, "Loan agreement", agreementPreview);
        Runnable updatePreview = () -> {
            Map<String, Object> selectedProduct = product.getValue();
            if (selectedProduct == null || amount.getText().isBlank() || term.getText().isBlank()) {
                repaymentPreview.setText("Choose a product and enter an amount and term to preview repayments.");
                return;
            }
            try {
                String currency = text(selectedProduct, "currency");
                long principal = Money.parseMinor(amount.getText(), currency);
                int months = parseInt(term.getText(), "Term");
                List<Map<String, Object>> schedule = service.repaymentPreview(principal,
                        decimal(selectedProduct, "annual_rate"), months, LocalDate.now());
                String details = schedule.stream().map(row -> "Month " + row.get("number") + " ("
                        + row.get("due") + "): " + Money.format(number(row, "total"), currency))
                        .collect(java.util.stream.Collectors.joining("\n"));
                long total = schedule.stream().mapToLong(row -> number(row, "total")).sum();
                repaymentPreview.setText("Proposed only; final dates start from disbursement. Product rate: "
                        + text(selectedProduct, "annual_rate") + "% annual; monthly late rate: "
                        + text(selectedProduct, "monthly_penalty_rate") + "% as configured. Total estimate: "
                        + Money.format(total, currency) + "\n" + details);
            } catch (RuntimeException exception) {
                repaymentPreview.setText(exception.getMessage());
            }
        };
        product.valueProperty().addListener((observable, previous, selected) -> updatePreview.run());
        amount.textProperty().addListener((observable, previous, value) -> updatePreview.run());
        term.textProperty().addListener((observable, previous, value) -> updatePreview.run());
        updatePreview.run();
        ScrollPane applicationForm = new ScrollPane(form);
        applicationForm.setFitToWidth(true);
        applicationForm.setPrefViewportHeight(560);
        applicationForm.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        formDialog("New loan application", applicationForm, () -> {
            Map<String, Object> selectedStudent = student.getValue();
            Map<String, Object> selectedProduct = product.getValue();
            if (selectedStudent == null || selectedProduct == null) {
                throw new IllegalArgumentException("Select a student and a product.");
            }
            String currency = text(selectedProduct, "currency");
            service.submitApplication(currentUser, number(selectedStudent, "id"), number(selectedProduct, "id"),
                    Money.parseMinor(amount.getText(), currency), parseInt(term.getText(), "Term"), purpose.getText(),
                    guarantorLetterPath[0], stampAuthority.getValue(), stampConfirmed.isSelected(),
                    guarantorSigned.isSelected(), staffIdPath[0], List.copyOf(academicDocumentPaths),
                    passportPhotoPath[0], applicantSignaturePath[0], acceptTerms.isSelected());
            showPage("Applications");
        });
    }

    private void previewLoanAgreement(Map<String, Object> student, Map<String, Object> product,
                                      String amountInput, String termInput, String purpose,
                                      String stampAuthority, Path applicantSignature) {
        if (student == null || product == null) {
            showError("Select a student and a loan product before previewing the agreement.");
            return;
        }
        try {
            String currency = text(product, "currency");
            long principal = Money.parseMinor(amountInput, currency);
            int months = parseInt(termInput, "Term");
            if (purpose == null || purpose.isBlank()) {
                throw new IllegalArgumentException("Enter a purpose for the loan.");
            }
            List<Map<String, Object>> schedule = service.repaymentPreview(principal,
                    decimal(product, "annual_rate"), months, LocalDate.now());
            LoanAgreementPdf.Details details = new LoanAgreementPdf.Details("Draft preview (not submitted)",
                    text(student, "name"), text(student, "studentId"), text(student, "faculty"),
                    Math.toIntExact(number(student, "year")), text(student, "phone"), text(student, "email"),
                    text(student, "guarantor"), text(student, "guarantorPhone"), text(student, "relation"),
                    text(product, "name"), currency, principal, decimal(product, "annual_rate"),
                    decimal(product, "monthly_penalty_rate"), months, purpose.trim(), stampAuthority,
                    LocalDate.now(), true);
            showAgreementPreview(details, schedule, applicantSignature);
        } catch (RuntimeException exception) {
            showError(exceptionMessage(exception));
        }
    }

    private void showAgreementPreview(LoanAgreementPdf.Details details,
                                      List<Map<String, Object>> schedule, Path applicantSignature) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Draft loan agreement preview");
        brandDialog(dialog, stage);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ProgressIndicator progress = new ProgressIndicator();
        Label status = new Label("Preparing the draft agreement...");
        VBox loading = new VBox(12, progress, status);
        loading.setAlignment(Pos.CENTER);
        loading.setPadding(new Insets(30));
        dialog.getDialogPane().setContent(loading);
        dialog.getDialogPane().setMinWidth(Math.min(900, Math.max(480, stage.getWidth() * 0.82)));

        Task<List<Image>> render = new Task<>() {
            @Override
            protected List<Image> call() throws Exception {
                Path temporaryPdf = Files.createTempFile("kiu-loan-agreement-preview-", ".pdf");
                try {
                    LoanAgreementPdf.write(temporaryPdf, details, schedule, applicantSignature);
                    try (PDDocument document = Loader.loadPDF(temporaryPdf.toFile())) {
                        PDFRenderer renderer = new PDFRenderer(document);
                        List<Image> pages = new ArrayList<>(document.getNumberOfPages());
                        for (int page = 0; page < document.getNumberOfPages(); page++) {
                            ByteArrayOutputStream png = new ByteArrayOutputStream();
                            if (!ImageIO.write(renderer.renderImage(page, 0.8f, ImageType.RGB), "png", png)) {
                                throw new IOException("The agreement preview page could not be rendered.");
                            }
                            pages.add(new Image(new java.io.ByteArrayInputStream(png.toByteArray())));
                        }
                        return pages;
                    }
                } finally {
                    Files.deleteIfExists(temporaryPdf);
                }
            }
        };
        render.setOnSucceeded(event -> {
            VBox pages = new VBox(14);
            pages.setAlignment(Pos.TOP_CENTER);
            double pageWidth = Math.max(420, Math.min(820, stage.getWidth() * 0.78));
            for (Image page : render.getValue()) {
                ImageView image = new ImageView(page);
                image.setPreserveRatio(true);
                image.setFitWidth(pageWidth);
                image.setSmooth(true);
                pages.getChildren().add(image);
            }
            ScrollPane pageScroll = new ScrollPane(pages);
            pageScroll.setFitToWidth(true);
            pageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
            pageScroll.setPrefViewportWidth(pageWidth + 32);
            pageScroll.setPrefViewportHeight(Math.min(720, stage.getHeight() * 0.78));
            dialog.getDialogPane().setContent(pageScroll);
        });
        render.setOnFailed(event -> {
            dialog.close();
            showError(exceptionMessage(render.getException()));
        });
        dialog.show();
        Thread worker = new Thread(render, "loan-agreement-preview");
        worker.setDaemon(true);
        worker.start();
    }

    private TextField evidencePathField() {
        TextField field = new TextField();
        field.setEditable(false);
        field.setPromptText("No file selected");
        return field;
    }

    private HBox evidencePicker(String buttonText, TextField fileName, Runnable choose) {
        Button button = button(buttonText, choose);
        HBox picker = new HBox(8, fileName, button);
        HBox.setHgrow(fileName, Priority.ALWAYS);
        return picker;
    }

    private void chooseEvidence(TextField display, Path[] selected, String description, String... extensions) {
        FileChooser chooser = evidenceChooser("Choose " + description, description, extensions);
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            selected[0] = file.toPath();
            display.setText(file.getName());
        }
    }

    private FileChooser evidenceChooser(String title, String description, String... extensions) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extensions));
        return chooser;
    }

    private void showApplicationEvidence(Map<String, Object> application) {
        List<Map<String, Object>> evidence = service.applicationEvidence(number(application, "id"));
        if (evidence.isEmpty()) {
            showInfo("Application evidence", "No evidence is recorded for this application.");
            return;
        }
        TableView<Map<String, Object>> table = new TableView<>(FXCollections.observableArrayList(evidence));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        addEvidenceColumn(table, "Document", "type");
        addEvidenceColumn(table, "Original file name", "name");
        addEvidenceColumn(table, "Added", "uploaded");
        Button open = button("Open selected document", () -> {
            Map<String, Object> selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("Select a document first.");
            } else {
                runAction(() -> {
                    if (!Desktop.isDesktopSupported()) {
                        throw new IllegalStateException("Opening local files is not supported on this computer.");
                    }
                    try {
                        Desktop.getDesktop().open(service.resolveApplicationEvidence(selected).toFile());
                    } catch (IOException exception) {
                        throw new IllegalStateException("The selected document could not be opened.", exception);
                    }
                });
            }
        });
        VBox content = new VBox(12, new Label("Application " + number(application, "id")
                + " — " + text(application, "student")), table, open);
        content.setPadding(new Insets(12));
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Application evidence");
        brandDialog(dialog, stage);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void addEvidenceColumn(TableView<Map<String, Object>> table, String title, String key) {
        TableColumn<Map<String, Object>, Object> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().get(key)));
        table.getColumns().add(column);
    }

    private void decide(Map<String, Object> application, boolean approved) {
        String status = text(application, "status");
        if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
            showError("Only submitted or stalled applications can be decided.");
            return;
        }
        long applicationId = number(application, "id");
        if (approved && service.applicationEvidenceCount(applicationId) == 0) {
            showError("This application cannot be approved without evidence. Use Stall and add the required documents first.");
            return;
        }
        TextField note = field("Decision note");
        formDialog(approved ? "Approve application" : "Reject application", note, () -> {
            service.decideApplication(currentUser, applicationId, approved, note.getText());
            showPage("Applications");
        });
    }

    private void stall(Map<String, Object> application) {
        String status = text(application, "status");
        if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
            showError("Only submitted or stalled applications can be stalled.");
            return;
        }
        TextField note = field("Reason (e.g. missing documents)");
        formDialog("Stall application", note, () -> {
            service.stallApplication(currentUser, number(application, "id"), note.getText());
            showPage("Applications");
        });
    }

    private void addEvidence(Map<String, Object> application) {
        String status = text(application, "status");
        if (!"SUBMITTED".equals(status) && !"STALLED".equals(status)) {
            showError("Evidence can only be added to submitted or stalled applications.");
            return;
        }
        TextField pathField = evidencePathField();
        Path[] sourcePath = new Path[1];
        ChoiceBox<String> type = new ChoiceBox<>(FXCollections.observableArrayList(
                "GUARANTOR_LETTER", "STAFF_ID", "ACADEMIC_DOCUMENT", "PASSPORT_PHOTO",
                "APPLICANT_SIGNATURE", "LOAN_AGREEMENT", "OTHER"));
        type.setValue("ACADEMIC_DOCUMENT");
        GridPane form = formGrid();
        addFormRow(form, 0, "Evidence type", type);
        addFormRow(form, 1, "File", evidencePicker("Choose file", pathField,
                () -> chooseEvidence(pathField, sourcePath, "Evidence file",
                        "*.pdf", "*.jpg", "*.jpeg", "*.png")));
        formDialog("Add evidence", form, () -> {
            if (sourcePath[0] == null) {
                throw new IllegalArgumentException("Choose an evidence file to attach.");
            }
            service.addApplicationEvidence(currentUser, number(application, "id"), sourcePath[0], type.getValue());
            showPage("Applications");
        });
    }

    private void disburse(Map<String, Object> application) {
        if (!"APPROVED".equals(text(application, "status"))) {
            showError("Select an approved application.");
            return;
        }
        DatePicker date = new DatePicker(LocalDate.now());
        GridPane form = formGrid();
        addFormRow(form, 0, "Disbursement date", date);
        formDialog("Disburse loan", form, () -> {
            service.disburse(currentUser, number(application, "id"), date.getValue());
            showPage("Loans & payments");
        });
    }

    private void showInstallments(Map<String, Object> loan) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Loan installments");
        brandDialog(dialog, stage);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
        TableView<Map<String, Object>> table = dataTable(service.installments(number(loan, "id")),
                List.of("number", "due", "principal", "interest", "principalPaid", "interestPaid", "penalty", "status"));
        table.setPrefSize(Math.min(900, stage.getWidth() * 0.85), Math.min(480, stage.getHeight() * 0.65));
        dialog.getDialogPane().setContent(table);
        dialog.showAndWait();
    }

    private void showLoanPayments(Map<String, Object> loan) {
        List<Map<String, Object>> rows = service.query("""
                SELECT receipt_number AS receipt,paid_on AS date,amount,principal_part AS principal,
                       interest_part AS interest,penalty_part AS penalty
                FROM payments WHERE loan_id=? ORDER BY id DESC""", number(loan, "id"));
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Payment history · " + text(loan, "student"));
        brandDialog(dialog, stage);
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Close", ButtonBar.ButtonData.CANCEL_CLOSE));
        TableView<Map<String, Object>> table = dataTable(rows,
                List.of("receipt", "date", "amount", "principal", "interest", "penalty"));
        table.setPrefSize(Math.min(760, stage.getWidth() * 0.85), Math.min(430, stage.getHeight() * 0.65));
        dialog.getDialogPane().setContent(table);
        dialog.showAndWait();
    }

    private void paymentDialog(Map<String, Object> loan) {
        if (!"ACTIVE".equals(text(loan, "status"))) {
            showError("Select an active loan.");
            return;
        }
        TextField amount = field("Amount");
        DatePicker date = new DatePicker(LocalDate.now());
        GridPane form = formGrid();
        addFormRow(form, 0, "Loan", new Label(text(loan, "student") + " · " + text(loan, "currency")));
        addFormRow(form, 1, "Amount", amount);
        addFormRow(form, 2, "Payment date", date);
        formDialog("Record repayment", form, () -> {
            long amountMinor = Money.parseMinor(amount.getText(), text(loan, "currency"));
            String receipt = service.recordPayment(currentUser, number(loan, "id"), amountMinor, date.getValue());
            showInfo("Payment recorded", "Receipt number: " + receipt);
            showPage("Loans & payments");
        });
    }

    private void fundsDialog(String currency) {
        TextField amount = field("Amount");
        TextField note = field("Reference or note");
        GridPane form = formGrid();
        addFormRow(form, 0, "Currency", new Label(currency));
        addFormRow(form, 1, "Amount", amount);
        addFormRow(form, 2, "Note", note);
        formDialog("Add loan funds", form, () -> {
            service.addFunds(currentUser, currency, Money.parseMinor(amount.getText(), currency), note.getText());
            showPage("Ledger");
        });
    }

    private List<String> reportColumns(String name) {
        return switch (name) {
            case "Portfolio summary" -> List.of("currency", "status", "loans", "principal", "outstanding");
            case "Overdue installments" -> List.of("studentId", "student", "phone", "guarantor",
                    "guarantorPhone", "currency", "installment", "due", "principal", "interest", "penalty");
            case "Collections by month" -> List.of("month", "currency", "collected", "principal", "interest", "penalty");
            case "Disbursements by faculty" -> List.of("faculty", "currency", "loans", "disbursed");
            case "Applications by status" -> List.of("status", "currency", "applications", "requested");
            default -> List.of();
        };
    }

    private void exportReport(String name, boolean pdf) {
        FileChooser chooser = new FileChooser();
        String format = pdf ? "PDF" : "CSV";
        String extension = pdf ? ".pdf" : ".csv";
        chooser.setTitle("Export " + name + " as " + format);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format + " files", "*" + extension));
        chooser.setInitialFileName(name.toLowerCase().replace(' ', '-') + extension);
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            List<Map<String, Object>> rows = service.report(name);
            if (pdf) {
                PdfExporter.write(file.toPath(), name, rows, reportColumns(name));
            } else {
                CsvExporter.write(file.toPath(), rows, reportColumns(name));
            }
            showInfo("Report exported", format + " saved to " + file.getAbsolutePath());
        } catch (IOException exception) {
            showError("The report could not be saved. Check that the selected folder is writable and try again.");
        }
    }

    private void userDialog() {
        TextField name = field("Display name");
        TextField username = field("Username");
        ChoiceBox<Role> role = new ChoiceBox<>(FXCollections.observableArrayList(Role.values()));
        role.setValue(Role.OFFICER);
        PasswordEntry password = passwordEntry("At least 8 characters");
        GridPane form = formGrid();
        addFormRow(form, 0, "Display name", name);
        addFormRow(form, 1, "Username", username);
        addFormRow(form, 2, "Role", role);
        addFormRow(form, 3, "Password", password.container());
        formDialog("Add user", form, () -> {
            service.createUser(currentUser, name.getText(), username.getText(), role.getValue(),
                    password.password().getText().toCharArray());
            showPage("Users");
        });
    }

    private void resetPasswordDialog(Map<String, Object> user) {
        PasswordEntry password = passwordEntry("At least 8 characters");
        formDialog("Reset password · " + text(user, "username"), password.container(), () -> {
            service.resetPassword(currentUser, number(user, "id"), password.password().getText().toCharArray());
            showPage("Users");
        });
    }

    private void toggleUser(Map<String, Object> user) {
        boolean active = !"Active".equals(text(user, "status"));
        if (active) {
            service.setUserActive(currentUser, number(user, "id"), true);
            showPage("Users");
        } else {
            confirm("Deactivate account", "Deactivate " + text(user, "username") + "?", () -> {
                service.setUserActive(currentUser, number(user, "id"), false);
                showPage("Users");
            });
        }
    }

    private void toggleProduct(Map<String, Object> product) {
        boolean active = !"Active".equals(text(product, "status"));
        service.setProductActive(currentUser, number(product, "id"), active);
        showPage("Loan products");
    }

    private void deleteProduct(Map<String, Object> product) {
        confirm("Delete loan product", "Delete \"" + text(product, "name") + "\"? This cannot be undone.",
                () -> {
                    service.deleteProduct(currentUser, number(product, "id"));
                    showPage("Loan products");
                });
    }

    private void createBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save encrypted backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("KIU encrypted backup", "*.kiubak"));
        chooser.setInitialFileName("kiu-loans-" + LocalDate.now() + ".kiubak");
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        PasswordEntry passphrase = passwordEntry("At least 8 characters");
        PasswordEntry confirm = passwordEntry("Confirm passphrase");
        GridPane form = formGrid();
        addFormRow(form, 0, "Passphrase", passphrase.container());
        addFormRow(form, 1, "Confirm", confirm.container());
        formDialog("Protect backup", form, () -> {
            if (!passphrase.password().getText().equals(confirm.password().getText())) {
                throw new IllegalArgumentException("The passphrases do not match.");
            }
            try {
                backups.create(currentUser, file.toPath(), passphrase.password().getText().toCharArray());
            } catch (Exception exception) {
                throw new IllegalStateException("Backup failed: " + exception.getMessage(), exception);
            }
            showInfo("Backup created", file.getAbsolutePath());
            showPage("Backup & restore");
        });
    }

    private void restoreBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select encrypted KIU backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("KIU encrypted backup", "*.kiubak"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        PasswordEntry passphrase = passwordEntry("Backup passphrase");
        formDialog("Restore backup", passphrase.container(), () -> {
            try {
                Path previous = backups.restore(currentUser, file.toPath(), passphrase.password().getText().toCharArray());
                currentUser = null;
                showInfo("Database restored", "The previous database was saved as " + previous);
                showLogin();
            } catch (Exception exception) {
                throw new IllegalStateException("Restore failed: " + exception.getMessage(), exception);
            }
        });
    }

    private void formDialog(String title, Node form, Runnable save) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        brandDialog(dialog, stage);
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType,
                new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE));
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().setMinWidth(Math.min(480, Math.max(0, stage.getWidth() - 64)));
        Node saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                save.run();
                dialog.setResult(null);
                dialog.close();
            } catch (RuntimeException exception) {
                event.consume();
                showError(exceptionMessage(exception));
            }
        });
        installFormNavigation(form, dialog, saveType);
        dialog.showAndWait();
    }

    private void installFormNavigation(Node form, Dialog<?> dialog, ButtonType saveType) {
        List<Node> focusables = new ArrayList<>();
        collectFocusables(form, focusables);
        if (focusables.isEmpty()) {
            return;
        }
        for (Node node : focusables) {
            node.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                switch (event.getCode()) {
                    case ENTER -> {
                        int index = focusables.indexOf(node);
                        if (index >= 0 && index < focusables.size() - 1) {
                            focusables.get(index + 1).requestFocus();
                            event.consume();
                        } else {
                            Node saveButton = dialog.getDialogPane().lookupButton(saveType);
                            if (saveButton instanceof Button button) {
                                button.fire();
                                event.consume();
                            }
                        }
                    }
                    case DOWN -> {
                        int index = focusables.indexOf(node);
                        if (index >= 0 && index < focusables.size() - 1) {
                            focusables.get(index + 1).requestFocus();
                            event.consume();
                        }
                    }
                    case UP -> {
                        int index = focusables.indexOf(node);
                        if (index > 0) {
                            focusables.get(index - 1).requestFocus();
                            event.consume();
                        }
                    }
                    default -> {
                    }
                }
            });
        }
        Platform.runLater(() -> focusables.getFirst().requestFocus());
    }

    private void collectFocusables(Node node, List<Node> focusables) {
        if (node instanceof TextInputControl input && input.isEditable()) {
            focusables.add(input);
            return;
        }
        if (node instanceof javafx.scene.Parent parent) {
            if (node instanceof GridPane grid) {
                List<Node> ordered = new ArrayList<>(grid.getChildrenUnmodifiable());
                ordered.sort((a, b) -> {
                    Integer ra = GridPane.getRowIndex(a);
                    Integer rb = GridPane.getRowIndex(b);
                    int rowA = ra == null ? 0 : ra;
                    int rowB = rb == null ? 0 : rb;
                    return Integer.compare(rowA, rowB);
                });
                for (Node child : ordered) {
                    collectFocusables(child, focusables);
                }
            } else {
                for (Node child : parent.getChildrenUnmodifiable()) {
                    collectFocusables(child, focusables);
                }
            }
        } else if (node instanceof javafx.scene.Group group) {
            for (Node child : group.getChildren()) {
                collectFocusables(child, focusables);
            }
        }
    }

    private void withSelection(String message, Consumer<Map<String, Object>> action) {
        Node center = shell.getCenter();
        TableView<Map<String, Object>> table = findTable(center);
        if (table == null || table.getSelectionModel().getSelectedItem() == null) {
            showError(message);
            return;
        }
        action.accept(table.getSelectionModel().getSelectedItem());
    }

    @SuppressWarnings("unchecked")
    private TableView<Map<String, Object>> findTable(Node node) {
        if (node instanceof TableView<?> table) {
            return (TableView<Map<String, Object>>) table;
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                TableView<Map<String, Object>> found = findTable(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private List<String> pagesFor(Role role) {
        List<String> pages = new ArrayList<>(List.of("Dashboard", "Students", "Applications",
                "Loans & payments", "Ledger", "Reports"));
        if (role == Role.ADMINISTRATOR) {
            pages.add(2, "Loan products");
            pages.add("Users");
            pages.add("Audit log");
            pages.add("Backup & restore");
        } else if (role == Role.AUDITOR) {
            pages.add("Audit log");
        }
        return pages;
    }

    private boolean canEditStudents() {
        return currentUser.role() == Role.OFFICER;
    }

    private boolean canSubmitApplications() {
        return currentUser.role() == Role.ADMINISTRATOR || currentUser.role() == Role.OFFICER;
    }

    private boolean canDisburse() {
        return currentUser.role() == Role.ADMINISTRATOR || currentUser.role() == Role.FINANCE;
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(label);
        button.setOnAction(event -> runAction(action));
        return button;
    }

    private void confirm(String title, String message, Runnable action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message,
                new ButtonType("Continue", ButtonBar.ButtonData.OK_DONE),
                new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE));
        alert.setTitle(title);
        alert.setHeaderText(null);
        brandDialog(alert, stage);
        alert.showAndWait().filter(button -> button.getButtonData() == ButtonBar.ButtonData.OK_DONE)
                .ifPresent(button -> runAction(action));
    }

    private void runAction(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            showError(exceptionMessage(exception));
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Action could not be completed");
        alert.setHeaderText("Please check the information and try again.");
        alert.setContentText(message == null ? "An unexpected error occurred." : message);
        brandDialog(alert, stage);
        alert.showAndWait();
    }

    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        brandDialog(alert, stage);
        alert.showAndWait();
    }

    public static void brandDialog(Dialog<?> dialog, Stage owner) {
        dialog.initOwner(owner);
        DialogPane pane = dialog.getDialogPane();
        pane.setGraphic(logoBadge(42));
        UiStyles.applyTree(pane);
        dialog.setOnShown(event -> UiStyles.applyTree(pane));
    }

    public static String exceptionMessage(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && !message.isBlank() && !isTechnicalMessage(message)) {
                return message;
            }
        }
        if (hasCause(exception, SecurityException.class)) {
            return "Your account does not have permission to do this. Ask an administrator if you need access.";
        }
        if (hasCause(exception, NumberFormatException.class)) {
            return "Enter a valid number in each amount, rate, and term field.";
        }
        if (hasCause(exception, IOException.class)) {
            return "The selected file could not be read or saved. Check the file and folder permissions, then try again.";
        }
        if (hasCause(exception, java.sql.SQLException.class)) {
            return "The database could not save this change. Check that the information is complete and not already in use, then try again.";
        }
        return "This action could not be completed. Check the required information and try again.";
    }

    private static boolean isTechnicalMessage(String message) {
        return TECHNICAL_MESSAGE.matcher(message.trim()).matches()
                || message.contains("javafx.scene.")
                || message.contains("com.sun.")
                || message.startsWith("class ");
    }

    private static boolean hasCause(Throwable exception, Class<? extends Throwable> type) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private void setScene(Parent root) {
        if (root instanceof Region region) {
            region.setMinSize(0, 0);
        }
        Scene scene = stage.getScene();
        if (scene == null) {
            Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
            scene = new Scene(root, Math.min(1280, bounds.getWidth() * 0.94),
                    Math.min(820, bounds.getHeight() * 0.9));
            stage.setScene(scene);
            stage.centerOnScreen();
        } else {
            scene.setRoot(root);
        }
        stage.show();
        UiStyles.applyTree(root);
        Platform.runLater(() -> UiStyles.applyTree(root));
    }

    private static StackPane logoBadge(double size) {
        var logoResource = LmsWindow.class.getResource(LOGO);
        if (logoResource == null) {
            throw new IllegalStateException("The KIU logo resource is missing: " + LOGO);
        }
        StackPane badge = new StackPane();
        badge.getStyleClass().add("logo-badge");
        badge.setMinSize(size, size);
        badge.setPrefSize(size, size);
        badge.setMaxSize(size, size);

        ImageView image = new ImageView(new Image(logoResource.toExternalForm(), true));
        image.setPreserveRatio(true);
        image.setFitWidth(size * 0.9);
        image.setFitHeight(size * 0.9);
        badge.getChildren().add(image);
        badge.setClip(new Circle(size / 2, size / 2, size / 2));
        return badge;
    }

    private VBox pageContainer(Node... children) {
        VBox box = new VBox(18, children);
        box.getStyleClass().add("page");
        box.setMinWidth(0);
        return box;
    }

    private VBox panel(Node node) {
        VBox panel = new VBox(node);
        panel.getStyleClass().add("panel");
        VBox.setVgrow(node, Priority.ALWAYS);
        return panel;
    }

    private ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setMinWidth(0);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    private VBox statCard(String title, String value, String message) {
        Label label = new Label(title);
        label.getStyleClass().add("stat-label");
        Label number = new Label(value);
        number.getStyleClass().add("stat-value");
        VBox card = new VBox(label, number);
        card.getStyleClass().add("stat-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        UiStyles.tip(card, message + "\nCurrent: " + value);
        return card;
    }

    private static String share(long value, long total) {
        if (total <= 0) {
            return "";
        }
        return " · " + Math.round(value * 100.0 / total) + "% of all";
    }

    private GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(12);
        grid.setPadding(new Insets(16, 4, 8, 4));
        ColumnConstraints label = new ColumnConstraints();
        label.setMinWidth(Math.min(130, stage.getWidth() * 0.25));
        ColumnConstraints input = new ColumnConstraints();
        input.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(label, input);
        return grid;
    }

    private void addFormRow(GridPane grid, int row, String label, Node input) {
        grid.add(new Label(label), 0, row);
        grid.add(input, 1, row);
        if (input instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
    }

    private TextField field(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        return field;
    }

    private PasswordEntry passwordEntry(String prompt) {
        return passwordEntry(prompt, false);
    }

    private PasswordEntry passwordEntry(String prompt, boolean useEyeToggle) {
        PasswordField hidden = new PasswordField();
        hidden.setPromptText(prompt);
        TextField visible = new TextField();
        visible.setPromptText(prompt);
        visible.textProperty().bindBidirectional(hidden.textProperty());
        visible.setVisible(false);
        visible.setManaged(false);
        StackPane fields = new StackPane(hidden, visible);
        CheckBox show = useEyeToggle ? null : new CheckBox("Show password");
        Button eyeToggle = useEyeToggle ? eyeToggleButton() : null;
        if (eyeToggle != null) {
            eyeToggle.getStyleClass().add("password-visibility-toggle");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            spacer.setMouseTransparent(true);
            HBox eyeOverlay = new HBox(spacer, eyeToggle);
            eyeOverlay.setAlignment(Pos.CENTER_RIGHT);
            eyeOverlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            eyeOverlay.setPickOnBounds(false);
            fields.getChildren().add(eyeOverlay);
        }
        Runnable toggleVisibility = () -> {
            boolean isShowing = hidden.isVisible();
            hidden.setVisible(!isShowing);
            hidden.setManaged(!isShowing);
            visible.setVisible(isShowing);
            visible.setManaged(isShowing);
            (isShowing ? visible : hidden).requestFocus();
            if (eyeToggle != null) {
                eyeToggle.setAccessibleText(isShowing ? "Hide password" : "Show password");
                eyeToggle.setGraphic(eyeIcon(isShowing));
            } else if (show != null) {
                show.setSelected(isShowing);
            }
        };
        if (eyeToggle != null) {
            eyeToggle.setAccessibleText("Show password");
            eyeToggle.setOnAction(event -> toggleVisibility.run());
        }
        if (show != null) {
            show.selectedProperty().addListener((observable, wasShowing, isShowing) -> {
                hidden.setVisible(!isShowing);
                hidden.setManaged(!isShowing);
                visible.setVisible(isShowing);
                visible.setManaged(isShowing);
                (isShowing ? visible : hidden).requestFocus();
            });
        }
        VBox container = useEyeToggle ? new VBox(fields) : new VBox(4, fields, show);
        if (useEyeToggle) {
            container.getStyleClass().add("password-entry-eye");
        }
        return new PasswordEntry(container, hidden, visible);
    }

    private Node eyeIcon(boolean passwordVisible) {
        SVGPath outline = new SVGPath();
        outline.setContent("M 1 9 C 4 3, 12 3, 15 9 C 12 15, 4 15, 1 9 Z");
        outline.setFill(javafx.scene.paint.Color.TRANSPARENT);
        outline.setStroke(javafx.scene.paint.Color.web("#075b30"));
        outline.setStrokeWidth(1.5);
        Circle pupil = new Circle(8, 9, 2, javafx.scene.paint.Color.web("#075b30"));
        Line slash = new Line(2, 15, 14, 3);
        slash.setStroke(javafx.scene.paint.Color.web("#075b30"));
        slash.setStrokeWidth(1.5);
        StackPane icon = new StackPane(outline, pupil);
        icon.setPrefSize(18, 18);
        if (!passwordVisible) {
            icon.getChildren().add(slash);
        }
        return icon;
    }

    private Button eyeToggleButton() {
        Button button = new Button();
        button.setGraphic(eyeIcon(false));
        button.setFocusTraversable(false);
        button.setMnemonicParsing(false);
        return button;
    }

    private VBox loginField(String labelText, Node input) {
        Label label = new Label(labelText);
        label.getStyleClass().add("login-field-label");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        input.setManaged(true);
        if (input instanceof Region region) {
            region.setMaxWidth(320);
        }
        VBox field = new VBox(6, label, input);
        field.setAlignment(Pos.CENTER);
        return field;
    }

    private ChoiceBox<String> currencyChoice() {
        return new ChoiceBox<>(FXCollections.observableArrayList(CURRENCIES));
    }

    private ChoiceBox<Map<String, Object>> choice(List<Map<String, Object>> items,
                                                   java.util.function.Function<Map<String, Object>, String> label) {
        ChoiceBox<Map<String, Object>> box = new ChoiceBox<>(FXCollections.observableArrayList(items));
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(Map<String, Object> value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public Map<String, Object> fromString(String value) {
                return null;
            }
        });
        box.setValue(items.getFirst());
        return box;
    }

    private String formatCell(String key, Map<String, Object> row, Object value) {
        if (value == null) {
            return "";
        }
        if (List.of("amount", "minimum", "maximum", "principal", "outstanding", "penalty", "interest",
                "debits", "credits", "balance", "collected", "disbursed", "requested", "principalPaid",
                "interestPaid").contains(key)) {
            String currency = Objects.toString(row.get("currency"), "UGX");
            return Money.format(((Number) value).longValue(), currency);
        }
        if (List.of("annualRate", "penaltyRate").contains(key) && value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().toPlainString() + "%";
        }
        return value.toString();
    }

    private String columnName(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String decimalAmount(Map<String, Object> row, String key, String currency) {
        return Money.toAmount(number(row, key), currency).toPlainString();
    }

    private int parseInt(String value, String label) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
    }

    private double parseDouble(String value, String label) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(label + " must be a valid number.");
        }
    }

    private double amountForChart(long minor, String currency) {
        return Money.toAmount(minor, currency).doubleValue();
    }

    private String roleName(Role role) {
        String value = role.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? 0 : ((Number) value).doubleValue();
    }

    private record PasswordEntry(VBox container, PasswordField password, TextField visiblePassword) {
        private void setOnAction(Runnable action) {
            password.setOnAction(event -> action.run());
            visiblePassword.setOnAction(event -> action.run());
        }
    }

}
