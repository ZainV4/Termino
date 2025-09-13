package MainCenter;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import MainCenter.Settings.Settings;
import MainCenter.Settings.SettingsService;
import MainCenter.Settings.SettingsDialog;

import MainCenter.handlers.BuiltinHandlers;     // keep your existing builtins
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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Map.entry("note", "net:note")
    );

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
        Pane spacer = new Pane();                  // Pane avoids Region/Swing clash
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

        // --- Registry + handlers ---
        registry = new CommandRegistry(Path.of("commands")); // project-root/commands/*.json

        // Built-ins that exist
        registry.registerHandler(new BuiltinHandlers.Echo());    // "builtin:echo"
        registry.registerHandler(new BuiltinHandlers.Time());    // "builtin:time"
        registry.registerHandler(new BuiltinHandlers.Clear());   // "builtin:clear"
        registry.registerHandler(new BuiltinHandlers.Exit());    // "builtin:exit"

        // Network pack (embedded below as nested classes)
        registry.registerHandler(new NetHandlers.PcapLoad());       // "net:pcap"
        registry.registerHandler(new NetHandlers.IndexBuild());     // "net:index"
        registry.registerHandler(new NetHandlers.Filter());         // "net:filter"
        registry.registerHandler(new NetHandlers.TopTalkers());     // "net:top.talkers"
        registry.registerHandler(new NetHandlers.Timeline());       // "net:timeline"
        registry.registerHandler(new NetHandlers.FlowsWhere());     // "net:flows.where"
        registry.registerHandler(new NetHandlers.DetectSynScan());  // "net:detect.syn_scan"
        registry.registerHandler(new NetHandlers.DetectExfil());    // "net:detect.exfil"
        registry.registerHandler(new NetHandlers.DnsRare());        // "net:dns.rare"
        registry.registerHandler(new NetHandlers.Graph());          // "net:graph"
        registry.registerHandler(new NetHandlers.HttpSuspicious()); // "net:http.suspicious"
        registry.registerHandler(new NetHandlers.Export());         // "net:export"
        registry.registerHandler(new NetHandlers.Note());           // "net:note"

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

    // --- Enhanced REPL: aliases + multi-command support (";") ---
    private void handleEnter(TerminalIO io) {
        String line = input.getText();
        if (line == null) return;
        String full = line.trim();
        input.clear();
        if (full.isEmpty()) return;

        // Support multiple commands separated by ';'
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

    /** Rewrite first token using our alias map (supports multi-word heads like "flows where") */
    private String rewriteAlias(String cmd) {
        if (cmd == null || cmd.isBlank()) return cmd;
        String trimmed = cmd.trim();

        // Special multi-word heads:
        // flows where "expr"
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("flows where")) {
            String rest = trimmed.substring("flows where".length()).trim();
            return ALIASES.get("flows.where") + " " + rest;
        }
        // detect syn-scan ...
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("detect syn-scan")) {
            String rest = trimmed.substring("detect syn-scan".length()).trim();
            return ALIASES.get("detect.syn-scan") + (rest.isEmpty() ? "" : " " + rest);
        }
        // detect exfil ...
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("detect exfil")) {
            String rest = trimmed.substring("detect exfil".length()).trim();
            return ALIASES.get("detect.exfil") + (rest.isEmpty() ? "" : " " + rest);
        }
        // dns rare ...
        if (trimmed.toLowerCase(Locale.ROOT).startsWith("dns rare")) {
            String rest = trimmed.substring("dns rare".length()).trim();
            return ALIASES.get("dns.rare") + (rest.isEmpty() ? "" : " " + rest);
        }
        // http suspicious ...
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

    private void printBanner() {
        appendOut("Terminal ready. Drop JSON files into ./commands and type 'help'.");
    }

    private void appendPrompt(String cmd) {
        String pfx = (settings.prompt == null || settings.prompt.isBlank()) ? "λ" : settings.prompt;
        Text prompt = new Text(pfx + " ");
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

    /* =======================================================================
       ===================  EMBEDDED NETWORK HANDLERS  =======================
       ======================================================================= */
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
            static Predicate<Flow> activeFilter = f->true;
            static final List<Flow> lastResult = new ArrayList<>();
            static final List<String> notes = new ArrayList<>();
            static void setResult(Collection<Flow> r){ lastResult.clear(); lastResult.addAll(r); }
            static void clearIndex(){ all.clear(); }
        }

        // --------- CSV helpers ----------
        static final class CSV {
            static List<String> header;
            static Map<String,Integer> index;

            static void load(Path path) throws Exception {
                Store.clearIndex();
                header = null; index = null;
                try (var br = java.nio.file.Files.newBufferedReader(path)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.isBlank()) continue;
                        if (header == null) {
                            header = splitCsv(line);
                            index = new HashMap<>();
                            for (int i=0;i<header.size();i++) index.put(header.get(i), i);
                            continue;
                        }
                        List<String> cols = splitCsv(line);
                        double ts = parseD(cols, "ts", 0d);
                        String src = get(cols,"src","");
                        String dst = get(cols,"dst","");
                        int sport = (int) parseL(cols,"sport",0);
                        int dport = (int) parseL(cols,"dport",0);
                        int proto = (int) parseL(cols,"proto",0);
                        long bytes = parseL(cols,"bytes",0);
                        long pkts  = parseL(cols,"pkts",1);
                        String flags = get(cols,"tcp_flags","");
                        String qn = get(cols,"dns_qname","");
                        String rc = get(cols,"dns_rcode","");
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
        static Predicate<Flow> parseFilter(String expr){
            if (expr==null || expr.isBlank()) return f->true;
            Deque<String> t = new ArrayDeque<>(Arrays.asList(expr.replace("(", " ( ").replace(")", " ) ").trim().split("\\s+")));
            return parseTokens(t);
        }
        static Predicate<Flow> parseTokens(Deque<String> q){
            Predicate<Flow> acc = term(q);
            while(!q.isEmpty()){
                String op = q.peek().toLowerCase();
                if ("and".equals(op) || "or".equals(op)){
                    q.poll();
                    Predicate<Flow> rhs = term(q);
                    acc = "and".equals(op) ? acc.and(rhs) : acc.or(rhs);
                } else break;
            }
            return acc;
        }
        static Predicate<Flow> term(Deque<String> q){
            String t = q.poll();
            if (t==null) return f->true;
            if ("(".equals(t)){
                Predicate<Flow> inner = parseTokens(q);
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
        static Predicate<Flow> testEq(String key, String val){
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
        static Predicate<Flow> testIn(String key, List<String> vals){
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
                    io.out(String.format("Index built: %,d flows in %.1fs",
                            Store.all.size(), dt/1000.0));
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
                Predicate<Flow> pred = parseFilter(expr).and(Store.activeFilter);
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

        // net:detect.exfil  ->  detect exfil <host> [window=600] [thrMB=50]
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

                NavigableMap<Long, Long> bucks = new TreeMap<>();
                for (Flow f : Store.all){
                    if (!Store.activeFilter.test(f)) continue;
                    if (!host.equals(f.src)) continue;
                    if (f.dst.startsWith("10.")) continue; // naive internal check
                    long t = (long)f.ts;
                    bucks.put(t, bucks.getOrDefault(t,0L)+f.bytes);
                }
                boolean hit=false;
                for (Long t : new ArrayList<>(bucks.keySet())){
                    long start = t-window;
                    long sum = 0;
                    for (long v : bucks.subMap(start,true,t,true).values()) sum += v;
                    if (sum >= thrBytes){
                        io.out(String.format("EXFIL suspected: %s bytes=%,d (>= %,d) in last %ds ending at %s",
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
                Predicate<Flow> pred = parseFilter(expr).and(Store.activeFilter);
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
                io.out("HTTP suspicious: stub. Add UA/URI/SNI to CSV to enable richer rules.");
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
    }
}
