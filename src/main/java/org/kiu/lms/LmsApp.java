package org.kiu.lms;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.Scene;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class LmsApp extends Application {
    @Override
    public void start(Stage stage) {
        stage.setTitle(Branding.title());
        var iconResource = LmsApp.class.getResource(Branding.logoPath());
        if (iconResource != null) {
            stage.getIcons().add(new javafx.scene.image.Image(iconResource.toExternalForm(), true));
        }
        VBox loading = new VBox(16, new ProgressIndicator(), new Label("Loading " + Branding.name() + "..."));
        loading.setAlignment(Pos.CENTER);
        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1280, bounds.getWidth() * 0.94);
        double height = Math.min(820, bounds.getHeight() * 0.9);
        stage.setMinWidth(Math.min(640, bounds.getWidth() * 0.8));
        stage.setMinHeight(Math.min(480, bounds.getHeight() * 0.75));
        stage.setScene(new Scene(new StackPane(loading), width, height));
        stage.centerOnScreen();
        stage.show();

        Task<Startup> startup = new Task<>() {
            @Override
            protected Startup call() throws Exception {
                LmsService service = new LmsService(new Database());
                return new Startup(service, service.requiresFirstAdministrator());
            }
        };
        startup.setOnSucceeded(event -> {
            Startup result = startup.getValue();
            LmsWindow window = new LmsWindow(stage, result.service(), new BackupService(result.service()));
            window.showStart(result.requiresFirstAdministrator());
        });
        startup.setOnFailed(event -> {
            Throwable exception = startup.getException();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(Branding.title());
            alert.setHeaderText("The application could not start.");
            alert.setContentText(LmsWindow.exceptionMessage(exception));
            LmsWindow.brandDialog(alert, stage);
            alert.showAndWait();
            Platform.exit();
        });

        Thread startupThread = new Thread(startup, "lms-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    private record Startup(LmsService service, boolean requiresFirstAdministrator) {
    }

    public static void main(String[] args) {
        launch(args);
    }
}
