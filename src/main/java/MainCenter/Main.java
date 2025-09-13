package MainCenter;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import MainCenter.Settings.Settings;
import MainCenter.Settings.SettingsService;
import MainCenter.Settings.SettingsDialog;

import MainCenter.handlers.BuiltinHandlers;
import MainCenter.terminal.CommandRegistry;
import MainCenter.terminal.TerminalIO;

import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class Main extends Application {
    // UI
    private TextFlow output;
    private ScrollPane scroll;
    private TextField input;

    // history
    private final List<String> history = new ArrayList<>();
    private int histPos = 0;

    // commands
    private CommandRegistry registry;

    // settings
    private Settings settings;
    private SettingsService settingsService;

    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public void start(Stage stage) {
        // Load settings (persisted in ~/.fxterminal.json)
        settingsService = new SettingsService();
        settings = settingsService.load();

        // --- UI root ---
        output = new TextFlow();
        output.getStyleClass().add("output");
        output.setLineSpacing(4);

        scroll = new ScrollPane(output);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("scroll");

        input = new TextField();
        input.getStyleClass().add("input");
        input.setPromptText("type a command, e.g. help");

        BorderPane root = new BorderPane();
        root.setCenter(scroll);
        VBox bottom = new VBox(input);
        bottom.setPadding(new Insets(8));
        root.setBottom(bottom);
        root.getStyleClass().add("root");

        // --- Scene ---
        Scene scene = new Scene(root, 900, 560);
        scene.getStylesheets().add(getClass().getResource("/terminal.css").toExternalForm());
        scene.setFill(Color.web("#0b0f10")); // avoid any white flash

        // --- Custom title bar (undecorated) ---
        stage.initStyle(StageStyle.UNDECORATED);

        Label title = new Label(" Terminal ");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button btnSettings = new Button("⚙");
        Button btnMin = new Button("—");
        Button btnMax = new Button("▢");
        Button btnClose = new Button("✕");

        HBox titleBar = new HBox(title, spacer, btnSettings, btnMin, btnMax, btnClose);
        titleBar.getStyleClass().add("titlebar");
        root.setTop(titleBar);

        // window actions
        btnMin.setOnAction(e -> stage.setIconified(true));
        btnMax.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        btnClose.setOnAction(e -> stage.close());

        // drag window by the bar
        final double[] drag = new double[2];
        titleBar.setOnMousePressed(e -> { drag[0] = e.getScreenX() - stage.getX(); drag[1] = e.getScreenY() - stage.getY(); });
        titleBar.setOnMouseDragged(e -> { if (!stage.isMaximized()) { stage.setX(e.getScreenX() - drag[0]); stage.setY(e.getScreenY() - drag[1]); } });
        titleBar.setOnMouseClicked(e -> { if (e.getClickCount() == 2) stage.setMaximized(!stage.isMaximized()); });

        // Settings dialog
        btnSettings.setOnAction(e ->
            new SettingsDialog(stage, settingsService).showAndApply(settings, s -> {
                settings = s;
                applySettings(stage);
            })
        );

        // Finish stage
        stage.setTitle("Terminal");
        stage.setScene(scene);
        applySettings(stage); // push colors/font/always-on-top
        stage.show();

        // --- Terminal I/O binding ---
        TerminalIO io = new TerminalIO() {
            @Override public void out(String line)  { appendOut(line); }
            @Override public void err(String line)  { appendErr(line); }
            @Override public void clear()           { output.getChildren().clear(); printBanner(); }
            @Override public void exit()            { stage.close(); }
        };

        // --- Registry + builtins ---
        registry = new CommandRegistry(Path.of("commands")); // project-root/commands/*.json
        registry.registerHandler(new BuiltinHandlers.Echo());
        registry.registerHandler(new BuiltinHandlers.Time());
        registry.registerHandler(new BuiltinHandlers.Clear());
        registry.registerHandler(new BuiltinHandlers.Exit());

        try { registry.loadAll(); }
        catch (Exception ex) { io.err("Failed to load commands: " + ex.getMessage()); }

        // --- Input handlers ---
        input.setOnAction(e -> handleEnter(io));

        input.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.L) { io.clear(); e.consume(); return; }
            if (e.isControlDown() && e.getCode() == KeyCode.R) {
                try { registry.loadAll(); io.out("Commands reloaded."); }
                catch (Exception ex) { io.err("Reload failed: " + ex.getMessage()); }
                e.consume(); return;
            }
            if (e.getCode() == KeyCode.UP) {
                if (!history.isEmpty()) {
                    if (histPos > 0) histPos--;
                    input.setText(history.get(histPos));
                    input.positionCaret(input.getText().length());
                }
                e.consume();
            } else if (e.getCode() == KeyCode.DOWN) {
                if (!history.isEmpty()) {
                    if (histPos < history.size() - 1) {
                        histPos++;
                        input.setText(history.get(histPos));
                        input.positionCaret(input.getText().length());
                    } else {
                        histPos = history.size();
                        input.clear();
                    }
                }
                e.consume();
            }
        });

        printBanner();
        appendOut("Loaded commands: " + String.join(", ", registry.definedCommands()));
        appendOut("Type 'help' for a list, 'reload' after editing JSON, or 'exit' to quit.");
    }

    private void handleEnter(TerminalIO io) {
        String line = input.getText();
        if (line == null) return;
        String cmd = line.trim();

        appendPrompt(cmd);
        input.clear();
        if (cmd.isEmpty()) return;

        history.add(cmd);
        histPos = history.size();

        // built-ins without JSON
        if (cmd.equalsIgnoreCase("help")) {
            io.out("Available commands: " + String.join(", ", registry.definedCommands()));
            io.out("Usage: see each JSON file's 'usage' field (e.g., echo <text>)");
            return;
        }
        if (cmd.equalsIgnoreCase("reload")) {
            try { registry.loadAll(); io.out("Commands reloaded."); }
            catch (Exception ex) { io.err("Reload failed: " + ex.getMessage()); }
            return;
        }

        registry.dispatch(cmd, io);
        scrollToBottom();
    }

    private void printBanner() {
        appendOut("Terminal ready. Drop JSON files into ./commands and type 'help'.");
    }

    private void appendPrompt(String cmd) {
        Text prompt = new Text((settings.prompt == null || settings.prompt.isBlank() ? "λ" : settings.prompt) + " ");
        prompt.getStyleClass().add("prompt");
        Text text = new Text(cmd + System.lineSeparator());
        text.getStyleClass().add("inputline");
        output.getChildren().addAll(prompt, text);
        scrollToBottom();
    }

    private void appendOut(String s) {
        if (settings.showTimestamps) {
            s = "[" + LocalTime.now().format(tsFmt) + "] " + s;
        }
        Text t = new Text(s + System.lineSeparator());
        t.getStyleClass().add("line");
        output.getChildren().add(t);
        scrollToBottom();
    }

    private void appendErr(String s) {
        if (settings.beepOnError) {
            try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
        }
        Text t = new Text(s + System.lineSeparator());
        t.getStyleClass().add("error");
        output.getChildren().add(t);
        scrollToBottom();
    }

    private void scrollToBottom() {
        if (!settings.autoScroll) return;
        output.requestLayout();
        scroll.layout();
        scroll.setVvalue(1.0);
    }

    /** Apply colors, font size, and window flags from Settings */
    private void applySettings(Stage stage) {
        if (output == null || output.getScene() == null) return;
        String style = String.format(
                "-fx-font-size: %dpx; -fx-bg: %s; -fx-fg: %s; -fx-muted: %s;",
                settings.fontSize, settings.bg, settings.fg, settings.border
        );
        output.getScene().getRoot().setStyle(style);
        output.getScene().setFill(Color.web(settings.bg));
        stage.setAlwaysOnTop(settings.alwaysOnTop);
    }

    public static void main(String[] args) { launch(args); }
}
