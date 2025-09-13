package MainCenter;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import MainCenter.Settings.Settings;
import MainCenter.Settings.SettingsService;
import MainCenter.Settings.SettingsDialog;
import MainCenter.chat.ChatPanel;

import MainCenter.handlers.BuiltinHandlers;
import MainCenter.terminal.CommandRegistry;
import MainCenter.terminal.TerminalIO;
import MainCenter.terminal.CommandCall;
import MainCenter.terminal.CommandHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Main app with Tabbed REPL sessions.
 * - Ctrl+T: new tab
 * - Ctrl+W: close tab
 * - Ctrl+Tab / Ctrl+Shift+Tab: switch tabs
 * - Aliases (pcap/index/top/timeline/flows where/detect syn-scan/detect exfil/dns rare/graph/http suspicious/export/note/demo)
 * - Multiple commands per line using ';'
 */
public class Main extends Application {
    // UI
    private TextFlow output;
    private ScrollPane scroll;
    private TextField input;
    
    // Chat UI
    private ChatPanel chatPanel;
    private BorderPane root;
    private SplitPane splitPane;
    private double lastDividerPosition = 0.7; // Default divider position (70% terminal, 30% chat)
    private boolean chatVisible = false;
    private Button btnChat;
    private CheckBox backgroundAnalysisToggle;
    
    // history
    private final List<String> history = new ArrayList<>();
    private int histPos = 0;

    // commands
    private CommandRegistry registry;

    // settings
    private Settings settings;
    private SettingsService settingsService;

    private final DateTimeFormatter tsFmt = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ---- Aliases: user-friendly names -> real handler IDs ----
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("pcap", "net:pcap"),
            Map.entry("index", "net:index"),
            Map.entry("filter", "net:filter"),
            Map.entry("top", "net:top.talkers"),
            Map.entry("timeline", "net:timeline"),
            Map.entry("flows.where", "net:flows.where"),
            Map.entry("detect.syn-scan", "net:detect.syn_scan"),
            Map.entry("detect.exfil", "net:detect.exfil"),
            Map.entry("dns.rare", "net:dns.rare"),
            Map.entry("graph", "net:graph"),
            Map.entry("http.suspicious", "net:http.suspicious"),
            Map.entry("export", "net:export"),
            Map.entry("note", "net:note"),
            Map.entry("demo", "net:make_demo") // optional demo generator
    );

    // Keep references to sessions so we can re-apply settings
    private final List<TerminalSession> sessions = new ArrayList<>();

    @Override
    public void start(Stage stage) {
        // Load settings (persisted in ~/.fxterminal.json)
        settingsService = new SettingsService();
        settings = settingsService.load();

        // --- Root UI ---
        BorderPane root = new BorderPane();

        // Title bar
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

        // Create the main layout with BorderPane as the root container
        root = new BorderPane();
        root.getStyleClass().add("root");
        
        // Bottom input area for terminal
        VBox bottom = new VBox(input);
        bottom.setPadding(new Insets(8));
        
        // Create the chat panel
        chatPanel = new ChatPanel();
        
        // --- Center: tabbed terminal sessions ---
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("term-tabs");   // <— make tabs pretty
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        
        // Create a SplitPane for resizable panels (terminal and chat)
        splitPane = new SplitPane();
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.getItems().add(tabs); // Add tabs as the first item
        
        // Initialize divider positions and style
        splitPane.setDividerPosition(0, 1.0); // Initially terminal takes full width
        splitPane.getStyleClass().add("terminal-split-pane");
        
        // Place SplitPane in center of the root BorderPane
        root.setCenter(splitPane);
        root.setBottom(bottom);
        
        // Create a single scene for the application
        Scene scene = new Scene(root, 1000, 620);
        scene.getStylesheets().add(getClass().getResource("/terminal.css").toExternalForm());
        scene.setFill(Color.web("#0b0f10")); // avoid any white flash
        
        // --- Custom title bar (undecorated) ---
        stage.initStyle(StageStyle.UNDECORATED);

        Label title = new Label(" Terminal ");
        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button btnNewTab = new Button("+");
        btnNewTab.getStyleClass().add("newtab"); // <— distinct look for the + button
        
        // Add chat button with an icon
        btnChat = new Button("💬");
        btnChat.getStyleClass().add("titlebar-button");
        btnChat.setTooltip(new Tooltip("Open AI Chat Assistant"));
        
        Button btnSettings = new Button("⚙");
        Button btnMin = new Button("—");
        Button btnMax = new Button("▢");
        Button btnClose = new Button("✕");
        HBox titleBar = new HBox(title, spacer, btnNewTab, btnChat, btnSettings, btnMin, btnMax, btnClose);
        titleBar.getStyleClass().add("titlebar");
        root.setTop(titleBar);

        // helper to add a new terminal tab
        Runnable addNewTab = () -> {
            TerminalSession s = new TerminalSession(settings);
            sessions.add(s);
            Tab t = new Tab("Session " + (tabs.getTabs().size() + 1), s.getRoot());
            t.setOnClosed(ev -> s.dispose());
            tabs.getTabs().add(t);
            tabs.getSelectionModel().select(t);
            s.focusInput();
        };

        // create first tab
        addNewTab.run();


        
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Terminal");
        stage.setScene(scene);

        // window actions
        btnMin.setOnAction(e -> stage.setIconified(true));
        btnMax.setOnAction(e -> stage.setMaximized(!stage.isMaximized()));
        btnClose.setOnAction(e -> stage.close());
        
        // Chat button action
        btnChat.setOnAction(e -> toggleChatPanel());

        // drag window by the bar
        final double[] drag = new double[2];
        titleBar.setOnMousePressed(e -> { drag[0] = e.getScreenX() - stage.getX(); drag[1] = e.getScreenY() - stage.getY(); });
        titleBar.setOnMouseDragged(e -> { if (!stage.isMaximized()) { stage.setX(e.getScreenX() - drag[0]); stage.setY(e.getScreenY() - drag[1]); } });
        titleBar.setOnMouseClicked(e -> { if (e.getClickCount() == 2) stage.setMaximized(!stage.isMaximized()); });

        // Title bar actions
        btnNewTab.setOnAction(e -> addNewTab.run());
        btnSettings.setOnAction(e ->
                new SettingsDialog(stage, settingsService).showAndApply(settings, s -> {
                    settings = s;
                    applySettings(stage);
                    // push settings to each session
                    for (var sess : sessions) sess.applySettings(settings);
                })
        );

        // Keyboard shortcuts
        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.T) { // Ctrl+T -> new tab
                addNewTab.run(); e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.SPACE) { // Ctrl+Space -> toggle chat
                toggleChatPanel(); e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.W) { // Ctrl+W -> close tab
                if (!tabs.getTabs().isEmpty()) {
                    Tab sel = tabs.getSelectionModel().getSelectedItem();
                    if (sel != null) tabs.getTabs().remove(sel);
                }
                e.consume();
            } else if (e.isControlDown() && e.getCode() == KeyCode.TAB) { // Ctrl+Tab -> next
                if (!tabs.getTabs().isEmpty()) {
                    var sm = tabs.getSelectionModel();
                    int i = (sm.getSelectedIndex() + 1) % tabs.getTabs().size();
                    sm.select(i);
                }
                e.consume();
            } else if (e.isControlDown() && e.isShiftDown() && e.getCode() == KeyCode.TAB) { // Ctrl+Shift+Tab -> prev
                if (!tabs.getTabs().isEmpty()) {
                    var sm = tabs.getSelectionModel();
                    int i = (sm.getSelectedIndex() - 1 + tabs.getTabs().size()) % tabs.getTabs().size();
                    sm.select(i);
                }
                e.consume();
            }
        });

        applySettings(stage);
        stage.show();
    }

    private void toggleChatPanel() {
            if (chatVisible) {
                // Hide chat panel
                lastDividerPosition = splitPane.getDividerPositions()[0]; // Save position before removing
                splitPane.getItems().remove(chatPanel);
                chatVisible = false;
                btnChat.getStyleClass().remove("active");
            } else {
                // Show chat panel
                splitPane.getItems().add(chatPanel);
                chatVisible = true;
                btnChat.getStyleClass().add("active");
                
                // Set the divider to the last position or default
                splitPane.setDividerPosition(0, lastDividerPosition);
                
                // Focus on chat input when opened
                chatPanel.focusInput();
            }
        }

    /** Apply window-level settings (bg/always-on-top) */
    private void applySettings(Stage stage) {
        if (stage.getScene() == null) return;
        stage.getScene().getRoot().setStyle(String.format(
                "-fx-font-size: %dpx; -fx-bg: %s; -fx-fg: %s; -fx-muted: %s;",
                settings.fontSize, settings.bg, settings.fg, settings.border
        ));
        stage.getScene().setFill(Color.web(settings.bg));
        stage.setAlwaysOnTop(settings.alwaysOnTop);
    }

    /** Rewrite first token using our alias map (supports multi-word heads like "flows where") */
    private String rewriteAlias(String cmd) {
        if (cmd == null || cmd.isBlank()) return cmd;
        String trimmed = cmd.trim();

        // Special multi-word heads:
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("flows where")) {
            String rest = trimmed.substring("flows where".length()).trim();
            return ALIASES.get("flows.where") + " " + rest;
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("detect syn-scan")) {
            String rest = trimmed.substring("detect syn-scan".length()).trim();
            return ALIASES.get("detect.syn-scan") + (rest.isEmpty() ? "" : " " + rest);
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("detect exfil")) {
            String rest = trimmed.substring("detect exfil".length()).trim();
            return ALIASES.get("detect.exfil") + (rest.isEmpty() ? "" : " " + rest);
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("dns rare")) {
            String rest = trimmed.substring("dns rare".length()).trim();
            return ALIASES.get("dns.rare") + (rest.isEmpty() ? "" : " " + rest);
        }
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("http suspicious")) {
            String rest = trimmed.substring("http suspicious".length()).trim();
            return ALIASES.get("http.suspicious") + (rest.isEmpty() ? "" : " " + rest);
        }

        // Single-word head
        int sp = trimmed.indexOf(' ');
        String head = (sp < 0) ? trimmed : trimmed.substring(0, sp);
        String tail = (sp < 0) ? ""  : trimmed.substring(sp + 1).trim();

        String canonical = ALIASES.getOrDefault(head.toLowerCase(Locale.ROOT), head);
        return tail.isEmpty() ? canonical : (canonical + " " + tail);
    }


    // =========================================================================
    //                       Terminal Session (one Tab)
    // =========================================================================
    private final class TerminalSession {
        private final TextFlow output = new TextFlow();
        private final ScrollPane scroll = new ScrollPane(output);
        private final TextField input = new TextField();
        private final BorderPane root = new BorderPane();

        // per-tab history
        private final List<String> history = new ArrayList<>();
        private int histPos = 0;

        // per-tab command registry
        private final CommandRegistry registry;

        // a session-local view of settings (copy)
        private Settings settingsRef;

        TerminalSession(Settings initial) {
            this.settingsRef = initial;

            output.getStyleClass().add("output");
            output.setLineSpacing(4);
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.getStyleClass().add("scroll");
            input.getStyleClass().add("input");
            input.setPromptText("type a command, e.g. help");

            VBox bottom = new VBox(input);
            bottom.setPadding(new Insets(8));
            root.setCenter(scroll);
            root.setBottom(bottom);

            // Terminal I/O
            TerminalIO io = new TerminalIO() {
                @Override public void out(String line)  { appendOut(line); }
                @Override public void err(String line)  { appendErr(line); }
                @Override public void clear()           { output.getChildren().clear(); printBanner(); }
                @Override public void exit()            { clear(); }
            };

            // Registry + handlers (per tab)
            registry = new CommandRegistry(Path.of("commands"));
            registry.registerHandler(new BuiltinHandlers.Echo());
            registry.registerHandler(new BuiltinHandlers.Time());
            registry.registerHandler(new BuiltinHandlers.Clear());
            registry.registerHandler(new BuiltinHandlers.Exit());

            // Network pack
            registry.registerHandler(new NetHandlers.PcapLoad());
            registry.registerHandler(new NetHandlers.IndexBuild());
            registry.registerHandler(new NetHandlers.Filter());
            registry.registerHandler(new NetHandlers.TopTalkers());
            registry.registerHandler(new NetHandlers.Timeline());
            registry.registerHandler(new NetHandlers.FlowsWhere());
            registry.registerHandler(new NetHandlers.DetectSynScan());
            registry.registerHandler(new NetHandlers.DetectExfil()); // robust sliding window
            registry.registerHandler(new NetHandlers.DnsRare());
            registry.registerHandler(new NetHandlers.Graph());
            registry.registerHandler(new NetHandlers.HttpSuspicious());
            registry.registerHandler(new NetHandlers.Export());
            registry.registerHandler(new NetHandlers.Note());
            registry.registerHandler(new NetHandlers.MakeDemo()); // optional demo generator

            try { registry.loadAll(); }
            catch (Exception ex) { io.err("Failed to load commands: " + ex.getMessage()); }

            // Input handlers
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

            applySettings(settingsRef); // per-tab styling
            printBanner();
            appendOut("Loaded commands: " + String.join(", ", registry.definedCommands()));
            appendOut("Type 'help' for a list, 'reload' after editing JSON, or 'exit' to clear this tab.");
        }

        BorderPane getRoot() { return root; }
        void focusInput() { input.requestFocus(); }
        void dispose() { /* keep if you later persist per-tab state */ }

        void applySettings(Settings s) {
            this.settingsRef = s;
            String style = String.format(
                    "-fx-font-size: %dpx; -fx-bg: %s; -fx-fg: %s; -fx-muted: %s;",
                    s.fontSize, s.bg, s.fg, s.border
            );
            root.setStyle(style);
            if (root.getScene() != null) root.getScene().setFill(Color.web(s.bg));
        }

        private void printBanner() { appendOut("Terminal ready. Drop JSON files into ./commands and type 'help'."); }

        private void appendPrompt(String cmd) {
            String pfx = (settingsRef.prompt == null || settingsRef.prompt.isBlank()) ? "λ" : settingsRef.prompt;
            Text prompt = new Text(pfx + " ");
            prompt.getStyleClass().add("prompt");
            Text text = new Text(cmd + System.lineSeparator());
            text.getStyleClass().add("inputline");
            output.getChildren().addAll(prompt, text);
            scrollToBottom();
        }

        private void appendOut(String s) {
            if (settingsRef.showTimestamps) s = "[" + LocalTime.now().format(tsFmt) + "] " + s;
            Text t = new Text(s + System.lineSeparator());
            t.getStyleClass().add("line");
            output.getChildren().add(t);
            scrollToBottom();
        }

        private void appendErr(String s) {
            if (settingsRef.beepOnError) { try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {} }
            Text t = new Text(s + System.lineSeparator());
            t.getStyleClass().add("error");
            output.getChildren().add(t);
            scrollToBottom();
        }

        private void scrollToBottom() {
            if (!settingsRef.autoScroll) return;
            output.requestLayout();
            scroll.layout();
            scroll.setVvalue(1.0);
        }

        // Terminal session uses its own applySettings method

        /**
         * Toggles the visibility of the chat panel by adding/removing it from the SplitPane
         */
        private void toggleChatPanel() {
            if (chatVisible) {
                // Hide chat panel
                lastDividerPosition = splitPane.getDividerPositions()[0]; // Save position before removing
                splitPane.getItems().remove(chatPanel);
                chatVisible = false;
                btnChat.getStyleClass().remove("active");
            } else {
                // Show chat panel
                splitPane.getItems().add(chatPanel);
                chatVisible = true;
                btnChat.getStyleClass().add("active");
                
                // Set the divider to the last position or default
                splitPane.setDividerPosition(0, lastDividerPosition);
                
                // Focus on chat input when opened
                chatPanel.focusInput();
            }
        }


        // same alias + multi-command support as before
        private void handleEnter(TerminalIO io) {
            String line = input.getText();
            if (line == null) return;
            String full = line.trim();
            input.clear();
            if (full.isEmpty()) return;

            String[] cmds = Arrays.stream(full.split("\\s*;\\s*"))
                    .filter(s -> s != null && !s.isBlank())
                    .toArray(String[]::new);

            for (String rawCmd : cmds) {
                appendPrompt(rawCmd);
                history.add(rawCmd);
                histPos = history.size();

                String rewritten = rewriteAlias(rawCmd);

                if (rewritten.equalsIgnoreCase("help")) {
                    io.out("Available commands: " + String.join(", ", registry.definedCommands()));
                    io.out("Usage: see each JSON file's 'usage' field (e.g., echo <text>)");
                    continue;
                }
                if (rewritten.equalsIgnoreCase("reload")) {
                    try { registry.loadAll(); io.out("Commands reloaded."); }
                    catch (Exception ex) { io.err("Reload failed: " + ex.getMessage()); }
                    continue;
                }

                registry.dispatch(rewritten, io);
            }
            scrollToBottom();
        }
    }

    // =========================================================================
    //                   Embedded Network Handlers (Net pack)
    // =========================================================================
    public static final class NetHandlers {

        // --------- In-memory store ----------
        static final class Flow {
            final double ts;     // seconds epoch
            final String src, dst;
            final int sport, dport, proto; // 6=tcp, 17=udp
            final long bytes, pkts;
            final String tcpFlags;   // "0x02" etc if present
            final String dnsQname;   // may be ""
            final String dnsRcode;   // "3" for NXDOMAIN, else ""
            Flow(double ts, String src, String dst, int sport, int dport, int proto,
                 long bytes, long pkts, String tcpFlags, String dnsQname, String dnsRcode) {
                this.ts=ts; this.src=src; this.dst=dst; this.sport=sport; this.dport=dport; this.proto=proto;
                this.bytes=bytes; this.pkts=pkts; this.tcpFlags=tcpFlags==null?"":tcpFlags;
                this.dnsQname=dnsQname==null?"":dnsQname; this.dnsRcode=dnsRcode==null?"":dnsRcode;
            }
            boolean isTCP(){ return proto==6; }
            boolean isUDP(){ return proto==17; }
        }

        static final class Store {
            static Path loadedCsv = null;
            static final List<Flow> all = new ArrayList<>();
            static java.util.function.Predicate<Flow> activeFilter = f->true;
            static final List<Flow> lastResult = new ArrayList<>();
            static final List<String> notes = new ArrayList<>();
            static void setResult(Collection<Flow> r){ lastResult.clear(); lastResult.addAll(r); }
            static void clearIndex(){ all.clear(); }
        }

        // --------- CSV helpers (comment-tolerant) ----------
        static final class CSV {
            static List<String> header;
            static Map<String,Integer> index;

            static void load(Path path) throws Exception {
                Store.clearIndex();
                header = null; index = null;
                try (var br = java.nio.file.Files.newBufferedReader(path)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // strip trailing comments (not inside quotes)
                        int hash = -1; boolean inQ=false;
                        for (int i=0;i<line.length();i++){
                            char c=line.charAt(i);
                            if (c=='"') inQ = !inQ;
                            else if (c=='#' && !inQ) { hash=i; break; }
                        }
                        if (hash>=0) line = line.substring(0, hash);
                        line = line.trim();
                        if (line.isBlank()) continue;

                        if (header == null) {
                            header = splitCsv(line);
                            index = new HashMap<>();
                            for (int i=0;i<header.size();i++) index.put(header.get(i).trim(), i);
                            continue;
                        }

                        List<String> cols = splitCsv(line);
                        double ts = parseD(cols, "ts", 0d);
                        String src = get(cols,"src","").trim();
                        String dst = get(cols,"dst","").trim();
                        int sport = (int) parseL(cols,"sport",0);
                        int dport = (int) parseL(cols,"dport",0);
                        int proto = (int) parseL(cols,"proto",0);
                        long bytes = parseL(cols,"bytes",0);
                        long pkts  = parseL(cols,"pkts",1);
                        String flags = get(cols,"tcp_flags","").trim();
                        String qn = get(cols,"dns_qname","").trim();
                        String rcRaw = get(cols,"dns_rcode","").trim();
                        String rc = rcRaw.replaceAll("\\D",""); // keep only digits
                        Store.all.add(new Flow(ts,src,dst,sport,dport,proto,bytes,pkts,flags,qn,rc));
                    }
                }
            }

            static String get(List<String> cols, String key, String def){
                Integer idx = index.get(key); if (idx==null || idx>=cols.size()) return def;
                String v = cols.get(idx); return v==null? def : v;
            }
            static long parseL(List<String> cols, String key, long def){
                try { return Long.parseLong(get(cols,key, Long.toString(def))); } catch(Exception e){ return def; }
            }
            static double parseD(List<String> cols, String key, double def){
                try { return Double.parseDouble(get(cols,key, Double.toString(def))); } catch(Exception e){ return def; }
            }

            static List<String> splitCsv(String line){
                List<String> out = new ArrayList<>();
                StringBuilder cur = new StringBuilder();
                boolean inQ=false;
                for (int i=0;i<line.length();i++){
                    char c=line.charAt(i);
                    if (c=='"'){ inQ = !inQ; continue; }
                    if (c==',' && !inQ) { out.add(cur.toString()); cur.setLength(0); }
                    else cur.append(c);
                }
                out.add(cur.toString());
                return out;
            }
        }

        // --------- Filter language ----------
        static java.util.function.Predicate<Flow> parseFilter(String expr){
            if (expr==null || expr.isBlank()) return f->true;
            Deque<String> t = new ArrayDeque<>(Arrays.asList(expr.replace("(", " ( ").replace(")", " ) ").trim().split("\\s+")));
            return parseTokens(t);
        }
        static java.util.function.Predicate<Flow> parseTokens(Deque<String> q){
            java.util.function.Predicate<Flow> acc = term(q);
            while(!q.isEmpty()){
                String op = q.peek().toLowerCase();
                if ("and".equals(op) || "or".equals(op)){
                    q.poll();
                    java.util.function.Predicate<Flow> rhs = term(q);
                    acc = "and".equals(op) ? acc.and(rhs) : acc.or(rhs);
                } else break;
            }
            return acc;
        }
        static java.util.function.Predicate<Flow> term(Deque<String> q){
            String t = q.poll();
            if (t==null) return f->true;
            if ("(".equals(t)){
                java.util.function.Predicate<Flow> inner = parseTokens(q);
                q.poll(); // ')'
                return inner;
            }
            String key=t; String op=q.poll();
            if (op==null) return f->true;
            if ("=".equals(op)){
                String val = strip(q.poll());
                return testEq(key, val);
            } else if ("in".equals(op)){
                q.poll(); // '('
                List<String> vals = new ArrayList<>();
                String s;
                while(!(s=q.poll()).equals(")")){
                    if (",".equals(s)) continue;
                    vals.add(strip(s));
                }
                return testIn(key, vals);
            }
            return f->true;
        }
        static String strip(String s){ if (s==null) return ""; return s.replaceAll("^\"|\"$", ""); }
        static java.util.function.Predicate<Flow> testEq(String key, String val){
            return f -> switch (key.toLowerCase()) {
                case "proto" -> Integer.toString(f.proto).equals(val) ||
                        ("tcp".equalsIgnoreCase(val) && f.isTCP()) ||
                        ("udp".equalsIgnoreCase(val) && f.isUDP());
                case "src" -> f.src.equals(val);
                case "dst" -> f.dst.equals(val);
                case "sport" -> f.sport == tryParseInt(val,0);
                case "dport" -> f.dport == tryParseInt(val,0);
                default -> true;
            };
        }
        static java.util.function.Predicate<Flow> testIn(String key, List<String> vals){
            return f -> switch (key.toLowerCase()) {
                case "src" -> vals.contains(f.src);
                case "dst" -> vals.contains(f.dst);
                case "sport" -> vals.stream().map(v->tryParseInt(v,0)).anyMatch(v->v==f.sport);
                case "dport" -> vals.stream().map(v->tryParseInt(v,0)).anyMatch(v->v==f.dport);
                default -> false;
            };
        }

        // --------- Arg helpers ----------
        static final Pattern KV = Pattern.compile("(\\w+)=([^\\s]+)");
        static String raw(CommandCall c){
            for (String m : List.of("raw","text","input","toString")) {
                try { var mm = c.getClass().getMethod(m); Object v=mm.invoke(c); if (v!=null) return v.toString(); } catch(Exception ignore){}
            }
            return "";
        }
        static String get(CommandCall c, String key, String def){
            try { var m = c.getClass().getMethod("get", String.class); Object v = m.invoke(c, key); if (v!=null) return v.toString(); } catch(Exception ignore){}
            try { var m = c.getClass().getMethod("getString", String.class); Object v = m.invoke(c, key); if (v!=null) return v.toString(); } catch(Exception ignore){}
            String r = raw(c);
            Matcher m = KV.matcher(r);
            while (m.find()) if (m.group(1).equalsIgnoreCase(key)) return stripQuotes(m.group(2));
            return def;
        }
        static int getInt(CommandCall c, String key, int def){
            try {
                try { var m = c.getClass().getMethod("getInt", String.class); Object v = m.invoke(c, key); if (v!=null) return (int)v; } catch(Exception ignore){}
                String s = get(c, key, null); if (s==null) return def; return Integer.parseInt(s);
            } catch(Exception e){ return def; }
        }
        static long getLong(CommandCall c, String key, long def){
            try {
                try { var m = c.getClass().getMethod("getLong", String.class); Object v = m.invoke(c, key); if (v!=null) return (long)v; } catch(Exception ignore){}
                String s = get(c, key, null); if (s==null) return def; return Long.parseLong(s);
            } catch(Exception e){ return def; }
        }
        static String stripQuotes(String s){
            if (s==null) return null;
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
                return s.substring(1, s.length()-1);
            return s;
        }
        static int tryParseInt(String s, int d){ try { return Integer.parseInt(s); } catch(Exception e){ return d; } }

        // --------- Formatting ----------
        static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
        static String fmtTs(double sec){ return TS.format(Instant.ofEpochMilli((long)(sec*1000))); }

        // ===================== HANDLERS =====================

        // net:pcap  ->  pcap load file="flows.csv"
        public static final class PcapLoad implements CommandHandler {
            @Override public String id() { return "net:pcap"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String action = get(call, "action", "load");
                String file = get(call, "file", null);

                // Allow natural "pcap load \"file\"" form (no key=val)
                String raw = raw(call);
                if ((file == null || file.isBlank()) && raw.toLowerCase(Locale.ROOT).startsWith("pcap load")) {
                    int q1 = raw.indexOf('"'), q2 = raw.lastIndexOf('"');
                    if (q1 >= 0 && q2 > q1) file = raw.substring(q1 + 1, q2);
                }

                if (!"load".equalsIgnoreCase(action) || file==null || file.isBlank()) {
                    io.err("usage: pcap load file=\"flows.csv\"   or   pcap load \"flows.csv\"");
                    return;
                }
                try {
                    Path p = Path.of(stripQuotes(file));
                    if (!Files.exists(p)) { io.err("File not found: " + p.toAbsolutePath()); return; }
                    Store.loadedCsv = p;
                    io.out("Loaded file reference: " + p.toAbsolutePath());
                } catch (Exception e){
                    io.err("pcap load failed: " + e.getMessage());
                }
            }
        }

        // net:index  ->  index build
        public static final class IndexBuild implements CommandHandler {
            @Override public String id() { return "net:index"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String action = get(call, "action", "build");
                if (!"build".equalsIgnoreCase(action)) { io.err("usage: index build"); return; }
                if (Store.loadedCsv == null) { io.err("No file loaded. Use: pcap load \"flows.csv\""); return; }
                long t0 = System.currentTimeMillis();
                try {
                    CSV.load(Store.loadedCsv);
                    long dt = System.currentTimeMillis() - t0;
                    io.out(String.format("Index built: %,d flows in %.1fs", Store.all.size(), dt/1000.0));
                    Store.setResult(Store.all);
                } catch (Exception e){
                    io.err("index build failed: " + e.getMessage());
                }
            }
        }

        // net:filter  ->  filter proto=tcp and dport=445
        public static final class Filter implements CommandHandler {
            @Override public String id() { return "net:filter"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String expr = get(call, "expr", null);
                if (expr == null) {
                    String r = raw(call);
                    int i = r.indexOf(' ');
                    expr = (i>0 && i<r.length()-1) ? r.substring(i+1).trim() : "";
                }
                if (expr.isBlank()) io.out("Warning: clearing global filter.");
                Store.activeFilter = parseFilter(expr);
                io.out("Filter set: " + expr);
            }
        }

        // net:top.talkers  ->  top by=bytes limit=5
        public static final class TopTalkers implements CommandHandler {
            @Override public String id() { return "net:top.talkers"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String by = Optional.ofNullable(get(call, "by", null)).orElse("bytes");
                int limit = getInt(call, "limit", 5);
                Map<String, Long> agg = new HashMap<>();
                for (Flow f : Store.all) {
                    if (!Store.activeFilter.test(f)) continue;
                    long inc = "flows".equalsIgnoreCase(by) ? 1L : f.bytes;
                    agg.put(f.src, agg.getOrDefault(f.src,0L) + inc);
                }
                List<Map.Entry<String, Long>> list = new ArrayList<>(agg.entrySet());
                list.sort((a,b)-> Long.compare(b.getValue(), a.getValue()));
                list.stream().limit(Math.max(1,limit))
                        .forEach(e -> io.out(String.format("%-16s  %,12d %s", e.getKey(), e.getValue(), by)));
            }
        }

        // net:timeline  ->  timeline metric=bytes per=60
        public static final class Timeline implements CommandHandler {
            @Override public String id() { return "net:timeline"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String metric = Optional.ofNullable(get(call, "metric", null)).orElse("bytes");
                int per = Math.max(1, getInt(call, "per", 60));
                NavigableMap<Long, Long> buckets = new TreeMap<>();
                for (Flow f : Store.all){
                    if (!Store.activeFilter.test(f)) continue;
                    long bucket = (long)Math.floor(f.ts/per)*per;
                    long inc = "flows".equalsIgnoreCase(metric) ? 1L : f.bytes;
                    buckets.put(bucket, buckets.getOrDefault(bucket,0L)+inc);
                }
                long max = buckets.values().stream().mapToLong(v->v).max().orElse(1);
                for (var e : buckets.entrySet()){
                    int bars = (int)Math.max(1, Math.round((40.0 * e.getValue()) / max));
                    io.out(String.format("%s  %s  (%,d)", fmtTs(e.getKey()), "#".repeat(bars), e.getValue()));
                }
            }
        }

        // net:flows.where  ->  flows where "expr"
        public static final class FlowsWhere implements CommandHandler {
            @Override public String id() { return "net:flows.where"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String expr = get(call, "expr", null);
                if (expr == null || expr.isBlank()){
                    String r = raw(call);
                    int i = r.toLowerCase().indexOf("where");
                    if (i>=0) expr = r.substring(i+"where".length()).trim();
                    expr = stripQuotes(expr);
                }
                java.util.function.Predicate<Flow> pred = parseFilter(expr).and(Store.activeFilter);
                List<Flow> out = new ArrayList<>();
                for (Flow f: Store.all) if (pred.test(f)) out.add(f);
                Store.setResult(out);
                int shown=0;
                for (Flow f : out){
                    if (shown++>=20) break;
                    io.out(String.format("%s  %s:%d -> %s:%d  p=%d  bytes=%d  pkts=%d  flags=%s  q=%s",
                            fmtTs(f.ts), f.src, f.sport, f.dst, f.dport, f.proto, f.bytes, f.pkts, f.tcpFlags, f.dnsQname));
                }
                if (out.size()>20) io.out("... ("+out.size()+" total)");
            }
        }

        // net:detect.syn_scan  ->  detect syn-scan [src=IP] [window=120] [thr=150]
        public static final class DetectSynScan implements CommandHandler {
            @Override public String id() { return "net:detect.syn_scan"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                int window = getInt(call,"window",120);
                int thr = getInt(call,"thr",150);
                String srcLimit = get(call,"src", null);

                Map<String, NavigableMap<Long, Set<String>>> buckets = new HashMap<>();
                for (Flow f : Store.all){
                    if (!Store.activeFilter.test(f)) continue;
                    if (!f.isTCP()) continue;
                    if (srcLimit!=null && !srcLimit.equals(f.src)) continue;
                    boolean synNoAck = f.tcpFlags.contains("0x02") && !f.tcpFlags.contains("0x10");
                    if (!synNoAck) continue;
                    long t = (long)f.ts;
                    buckets.computeIfAbsent(f.src, k->new TreeMap<>());
                    buckets.get(f.src).computeIfAbsent(t, k->new HashSet<>()).add(f.dst+":"+f.dport);
                }
                List<String> offenders = new ArrayList<>();
                for (var e : buckets.entrySet()){
                    var times = e.getValue().navigableKeySet();
                    for (Long t : times){
                        long start = t-window;
                        Set<String> uniq = new HashSet<>();
                        for (var s : e.getValue().subMap(start,true,t,true).values()) uniq.addAll(s);
                        if (uniq.size() >= thr){
                            offenders.add(String.format("%s  fanout=%d  window=%ds  until=%s",
                                    e.getKey(), uniq.size(), window, fmtTs(t)));
                            break;
                        }
                    }
                }
                if (offenders.isEmpty()) io.out("No SYN scan offenders (thr="+thr+").");
                else offenders.forEach(io::out);
            }
        }

        // net:detect.exfil  ->  detect exfil <host> [window=600] [thrMB=50]  (sliding window)
        public static final class DetectExfil implements CommandHandler {
            @Override public String id() { return "net:detect.exfil"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String host = get(call,"host", null);
                if (host==null || host.isBlank()){
                    String r = raw(call).trim();
                    String[] parts = r.split("\\s+");
                    if (parts.length>=3) host = stripQuotes(parts[2]);
                }
                if (host==null || host.isBlank()){ io.err("usage: detect exfil <host> [window=600] [thrMB=50]"); return; }

                int window = getInt(call,"window",600);
                long thrMB = getLong(call,"thrMB",50);
                long thrBytes = thrMB*1024L*1024L;

                List<long[]> points = new ArrayList<>(); // [t, bytes]
                for (Flow f : Store.all){
                    if (!Store.activeFilter.test(f)) continue;
                    if (!host.equals(f.src)) continue;
                    if (f.dst.startsWith("10.")) continue; // naive internal check
                    points.add(new long[]{ (long)f.ts, f.bytes });
                }
                if (points.isEmpty()){
                    io.out("No external egress for " + host + " under current filter.");
                    return;
                }
                points.sort(Comparator.comparingLong(a -> a[0]));

                Deque<long[]> q = new ArrayDeque<>();
                long sum = 0;
                boolean hit=false;
                for (long[] p : points){
                    long t = p[0], b = p[1];
                    q.addLast(p); sum += b;
                    while (!q.isEmpty() && q.peekFirst()[0] < t - window) {
                        sum -= q.removeFirst()[1];
                    }
                    if (sum >= thrBytes){
                        io.out(String.format(
                                "EXFIL suspected: %s  bytes=%,d (>= %,d)  window=%ds  until=%s",
                                host, sum, thrBytes, window, fmtTs(t)));
                        hit=true; break;
                    }
                }
                if (!hit) io.out("No exfil over threshold ("+thrMB+" MB).");
            }
        }

        // net:dns.rare  ->  dns rare [min=2]
        public static final class DnsRare implements CommandHandler {
            @Override public String id() { return "net:dns.rare"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                int min = getInt(call,"min",2);
                Map<String,Integer> counts = new HashMap<>();
                Map<String,Integer> nxs = new HashMap<>();
                for (Flow f : Store.all){
                    if (!Store.activeFilter.test(f)) continue;
                    if (f.dnsQname.isBlank()) continue;
                    counts.put(f.dnsQname, counts.getOrDefault(f.dnsQname,0)+1);
                    if ("3".equals(f.dnsRcode)) nxs.put(f.dnsQname, nxs.getOrDefault(f.dnsQname,0)+1);
                }
                List<Map.Entry<String,Integer>> rare = new ArrayList<>();
                for (var e : counts.entrySet()) if (e.getValue()<=min) rare.add(e);
                rare.sort(Comparator.comparingInt(Map.Entry::getValue));
                if (rare.isEmpty()){ io.out("No rare domains <= "+min); return; }
                io.out("Rare domains (<= "+min+"):");
                int shown=0;
                for (var e : rare){
                    io.out(String.format("%-50s  count=%d  NX=%d", e.getKey(), e.getValue(), nxs.getOrDefault(e.getKey(),0)));
                    if (++shown>=50) break;
                }
            }
        }

        // net:graph  ->  graph "expr"
        public static final class Graph implements CommandHandler {
            @Override public String id() { return "net:graph"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String expr = get(call,"expr", null);
                if (expr==null || expr.isBlank()){
                    String r = raw(call);
                    int q1=r.indexOf('"'), q2=r.lastIndexOf('"');
                    if (q1>=0 && q2>q1) expr = r.substring(q1+1,q2);
                }
                if (expr==null) expr="";
                java.util.function.Predicate<Flow> pred = parseFilter(expr).and(Store.activeFilter);
                Set<String> edges = new LinkedHashSet<>();
                for (Flow f: Store.all){
                    if (!pred.test(f)) continue;
                    edges.add(f.src+" -> "+f.dst+" ["+f.dport+"/p"+f.proto+"]");
                }
                int shown=0;
                for (String e : edges){
                    io.out(e);
                    if (++shown>=50){ io.out("... ("+edges.size()+" total edges)"); break; }
                }
            }
        }

        // net:http.suspicious  ->  http suspicious (stub)
        public static final class HttpSuspicious implements CommandHandler {
            @Override public String id() { return "net:http.suspicious"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                io.out("HTTP suspicious: stub. Add UA/URI/SNI fields in CSV to enable richer rules.");
            }
        }

        // net:export  ->  export "file.csv"
        public static final class Export implements CommandHandler {
            @Override public String id() { return "net:export"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String file = get(call,"file", null);
                if (file==null || file.isBlank()){
                    String r = raw(call).trim();
                    int sp = r.indexOf(' ');
                    if (sp>0 && sp<r.length()-1) file = stripQuotes(r.substring(sp+1).trim());
                }
                if (file==null || file.isBlank()){ io.err("usage: export \"file.csv\""); return; }
                try (var bw = new java.io.BufferedWriter(new java.io.FileWriter(file))){
                    bw.write("ts,src,dst,sport,dport,proto,bytes,pkts,tcp_flags,dns_qname,dns_rcode\n");
                    for (Flow f : Store.lastResult){
                        bw.write(String.format(java.util.Locale.ROOT,
                                "%f,%s,%s,%d,%d,%d,%d,%d,%s,%s,%s\n",
                                f.ts, f.src, f.dst, f.sport, f.dport, f.proto, f.bytes, f.pkts,
                                safeCsv(f.tcpFlags), safeCsv(f.dnsQname), safeCsv(f.dnsRcode)));
                    }
                } catch (Exception e){
                    io.err("export failed: " + e.getMessage());
                    return;
                }
                io.out("Exported "+Store.lastResult.size()+" rows -> "+file);
            }
            private String safeCsv(String s){ if (s==null) return ""; return s.replace(",", " "); }
        }

        // net:note  ->  note "text"
        public static final class Note implements CommandHandler {
            @Override public String id() { return "net:note"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String text = get(call,"text", null);
                if (text==null || text.isBlank()){
                    String r = raw(call);
                    int i = r.indexOf(' ');
                    text = (i>0 && i<r.length()-1)? r.substring(i+1).trim() : "";
                    text = stripQuotes(text);
                }
                if (text.isBlank()){ io.err("usage: note \"text\""); return; }
                Store.notes.add(text);
                io.out("Noted: " + text);
            }
        }

        // net:make_demo  ->  demo make file="demo.csv"   (optional generator)
        public static final class MakeDemo implements CommandHandler {
            @Override public String id() { return "net:make_demo"; }
            @Override public void execute(CommandCall call, TerminalIO io) {
                String file = get(call, "file", "day1_flows_demo.csv");
                long t0 = System.currentTimeMillis()/1000L; // epoch seconds
                try (var bw = new java.io.BufferedWriter(new java.io.FileWriter(file))) {
                    bw.write("ts,src,dst,sport,dport,proto,bytes,pkts,tcp_flags,dns_qname,dns_rcode\n");
                    bw.write((t0)   +",10.1.2.10,10.1.2.23,12345,445,6,820,1,0x18,,\n");
                    bw.write((t0+5) +",10.1.2.10,10.1.2.23,12345,445,6,840,1,0x18,,\n");
                    bw.write((t0+60) +",10.1.2.23,8.8.8.8,51522,443,6,1048576,5,0x18,,\n");
                    bw.write((t0+120)+",10.1.2.23,8.8.4.4,51522,443,6,1572864,7,0x18,,\n");
                    bw.write((t0+180)+",10.1.2.23,1.1.1.1,51522,443,6,943718,4,0x18,,\n");
                    bw.write((t0+240)+",10.1.2.23,9.9.9.9,51522,443,6,524288,3,0x18,,\n");
                    int[] ports = {22,23,25,80,110,135,139,143,443,445,8080};
                    for (int i=0;i<ports.length;i++) {
                        bw.write((t0+300+i)+",10.1.2.50,10.1.2."+ (20+i) +",53000,"+ports[i]+",6,60,1,0x02,,\n");
                    }
                    bw.write((t0+400)+",10.1.2.31,10.1.2.53,53100,53,17,90,1,,odd1.bad.labs,3\n");
                    bw.write((t0+405)+",10.1.2.31,10.1.2.53,53101,53,17,90,1,,odd2.bad.labs,3\n");
                    bw.write((t0+410)+",10.1.2.31,10.1.2.53,53102,53,17,90,1,,www.google.com,0\n");
                    bw.write((t0+415)+",10.1.2.31,10.1.2.53,53103,53,17,90,1,,assets.cloudflare.com,0\n");
                    bw.write((t0+500)+",10.1.2.40,10.1.2.41,54000,3389,6,50000,10,0x18,,\n");
                    bw.write((t0+560)+",10.1.2.41,10.1.2.40,3389,54000,6,52000,10,0x18,,\n");
                } catch (Exception e) {
                    io.err("demo make failed: "+e.getMessage());
                    return;
                }
                io.out("Demo CSV written: "+file);
                io.out("Run: pcap load file=\""+file+"\"; index build; top by=bytes limit=5");
            }
        }
    }

    public static void main(String[] args) { launch(args); }
}
