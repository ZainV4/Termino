package MainCenter.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.web.WebView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

/**
 * Minimal OAuth2 Authorization Code + PKCE helper for AWS Cognito Hosted UI.
 *
 * Pass ONLY the Hosted UI domain host (no https://), for example:
 *    "yourprefix.auth.us-east-2.amazoncognito.com"
 *
 * Callback is http://localhost:{port}/callback (add it in App integration → Login pages).
 *
 * Requires:
 * - Jackson Databind
 * - Nimbus JOSE (only if you verify tokens elsewhere; not needed in this class)
 * - JavaFX (for the in-app WebView flow)
 * - Add --add-modules jdk.httpserver at runtime (already in build.gradle 'run' task)
 */
public final class CognitoAuth {

    // ----------------------------- Tokens -----------------------------
    public static final class Tokens {
        public final String accessToken;
        public final String idToken;
        public final String refreshToken;
        public final Instant expiresAt;

        Tokens(String accessToken, String idToken, String refreshToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.idToken = idToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
        }
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt.minusSeconds(30));
        }
    }

    // --------------------------- Fields -------------------------------
    private String domain;              // host only, e.g. "<prefix>.auth.us-east-2.amazoncognito.com"
    private final String region;        // informational
    private final String userPoolId;    // optional here
    private final String clientId;      // public app client id
    private final int    localPort;     // e.g. 5555
    private final String redirectUri;   // http://localhost:{port}/callback

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Preferences prefs = Preferences.userRoot().node("fxterminal/cognito");

    private volatile Tokens tokens;

    // ------------------------ Construction ----------------------------
    public CognitoAuth(String domain, String region, String userPoolId, String clientId, int localPort) {
        if (domain == null) throw new IllegalArgumentException("Hosted UI domain is null");
        domain = domain.trim();
        // strip accidental protocol (and the common "https//" typo), and trailing slashes
        domain = domain.replaceFirst("^https?://", "").replaceFirst("^https//", "").replaceAll("/+$", "");
        // If a pool id was pasted by mistake, it contains '_'
        if (domain.contains("_")) {
            throw new IllegalArgumentException(
                "It looks like you passed a User Pool ID. Pass the Hosted UI domain like '<prefix>.auth.us-east-2.amazoncognito.com'."
            );
        }
        // quick DNS sanity check (throws if unresolvable)
        try { java.net.InetAddress.getByName(domain); }
        catch (Exception e) { throw new IllegalArgumentException("Hosted UI domain does not resolve: " + domain, e); }

        this.domain     = domain;
        this.region     = Objects.requireNonNull(region);
        this.userPoolId = userPoolId; // optional in this class
        this.clientId   = Objects.requireNonNull(clientId);
        this.localPort  = localPort;
        this.redirectUri = "http://localhost:" + localPort + "/callback";

        loadFromPrefs();
    }

    // ------------------------ Public API ------------------------------
    /** True when we currently have a non-expired access/id token. */
    public boolean isSignedIn() { return tokens != null && !tokens.isExpired(); }
    public String getIdToken()      { return tokens == null ? null : tokens.idToken; }
    public String getAccessToken()  { return tokens == null ? null : tokens.accessToken; }
    public String getRefreshToken() { return tokens == null ? null : tokens.refreshToken; }

    /** Clears local token cache (does not call Cognito logout). */
    public void signOut() {
        tokens = null;
        try { prefs.clear(); } catch (Exception ignored) {}
    }

    /** Classic flow: open the system browser for the Hosted UI. */
    public CompletableFuture<Tokens> signInAsync() {
        return signInCommon(/*inApp=*/false, null);
    }

    /** In-app flow: show Hosted UI in a JavaFX WebView modal dialog. */
    public CompletableFuture<Tokens> signInInApp(Stage owner) {
        return signInCommon(/*inApp=*/true, owner);
    }

    /** Ensure tokens are valid or refresh them if possible. */
    public CompletableFuture<Tokens> ensureValidAsync() {
        if (isSignedIn()) return CompletableFuture.completedFuture(tokens);
        if (tokens == null || tokens.refreshToken == null || tokens.refreshToken.isBlank())
            return CompletableFuture.failedFuture(new IllegalStateException("Not signed in"));
        return refreshAsync(tokens.refreshToken);
    }

    // ------------------------- Core logic -----------------------------
    private CompletableFuture<Tokens> signInCommon(boolean inApp, Stage ownerIfAny) {
        if (isSignedIn()) return CompletableFuture.completedFuture(tokens);

        final String state = randomUrlSafe(32);
        final String codeVerifier  = randomUrlSafe(64);
        final String codeChallenge = base64UrlNoPad(sha256(codeVerifier.getBytes(StandardCharsets.UTF_8)));

        // Keep scopes aligned with your App client. From your screenshot: openid, email, phone are enabled.
        // Use "openid email" to avoid invalid_request if 'profile' is not enabled.
        final String scopes = "openid email";

        String authUrl = String.format(
                "https://%s/oauth2/authorize?response_type=code&client_id=%s&redirect_uri=%s&scope=%s&state=%s&code_challenge=%s&code_challenge_method=S256",
                domain,
                urlEnc(clientId),
                urlEnc(redirectUri),
                urlEnc(scopes),
                urlEnc(state),
                urlEnc(codeChallenge)
        );
        System.out.println("[Cognito] Opening Hosted UI: " + authUrl);

        CompletableFuture<Tokens> future = new CompletableFuture<>();

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", localPort), 0);
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.createContext("/callback", exchange -> {
                try {
                    var query  = exchange.getRequestURI().getRawQuery(); // code=...&state=...
                    var params = new QueryString(query);

                    String err     = params.get("error");
                    String errDesc = params.get("error_description");
                    String code    = params.get("code");
                    String gotState= params.get("state");

                    if (err != null) {
                        byte[] body = ("<h3>Login failed: " + escapeHtml(err)
                                + (errDesc != null ? " — " + escapeHtml(errDesc) : "") + "</h3>").getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                        future.completeExceptionally(new RuntimeException("Hosted UI error: " + err + (errDesc != null ? " — " + errDesc : "")));
                        return;
                    }

                    if (!Objects.equals(state, gotState) || code == null || code.isBlank()) {
                        byte[] body = "<h3>Invalid login response.</h3>".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(400, body.length);
                        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                        future.completeExceptionally(new RuntimeException("State/code mismatch"));
                        return;
                    }

                    // Exchange authorization code for tokens
                    Tokens t = exchangeCode(code, codeVerifier);
                    tokens = t;
                    saveToPrefs(t);

                    byte[] ok = "<h3>Login successful. You can close this tab.</h3>".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, ok.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(ok); }

                    future.complete(t);
                } catch (Exception ex) {
                    future.completeExceptionally(ex);
                } finally {
                    server.stop(0);
                    if (inApp) {
                        Platform.runLater(() -> {
                            // Close any modal window we opened
                            Stage s = (Stage) Stage.getWindows().stream()
                                    .filter(w -> w.getUserData() != null && "cognito-login".equals(w.getUserData()))
                                    .findFirst().orElse(null);
                            if (s != null) s.close();
                        });
                    }
                }
            });
            server.start();

            if (inApp) {
                // Make sure WebView can keep session cookies
                try { if (CookieHandler.getDefault() == null) CookieHandler.setDefault(new CookieManager()); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    WebView web = new WebView();
                    web.getEngine().load(authUrl);
                    Stage dlg = new Stage(StageStyle.DECORATED);
                    dlg.setUserData("cognito-login");
                    if (ownerIfAny != null) {
                        dlg.initOwner(ownerIfAny);
                        dlg.initModality(Modality.WINDOW_MODAL);
                    }
                    dlg.setTitle("Sign in");
                    dlg.setScene(new Scene(web, 520, 720));
                    dlg.setOnCloseRequest(ev -> {
                        if (!future.isDone()) future.completeExceptionally(new CancellationException("User closed login"));
                        server.stop(0);
                    });
                    dlg.show();
                });
            } else {
                openInBrowser(authUrl);
            }

        } catch (IOException ioe) {
            future.completeExceptionally(ioe);
        }

        return future;
    }

    private CompletableFuture<Tokens> refreshAsync(String refreshToken) {
        String tokenUrl = "https://" + domain + "/oauth2/token";
        String body = "grant_type=refresh_token"
                + "&client_id=" + urlEnc(clientId)
                + "&refresh_token=" + urlEnc(refreshToken);

        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("Refresh failed: " + resp.statusCode() + " " + resp.body());
                    }
                    try {
                        JsonNode n = mapper.readTree(resp.body());
                        String at = n.path("access_token").asText(null);
                        String it = n.path("id_token").asText(null);
                        String rt = refreshToken; // Cognito may omit refresh_token on refresh
                        long expiresIn = n.path("expires_in").asLong(3600);
                        Tokens t = new Tokens(at, it, rt, Instant.now().plusSeconds(expiresIn));
                        tokens = t;
                        saveToPrefs(t);
                        return t;
                    } catch (Exception e) {
                        throw new RuntimeException("Parse refresh response failed", e);
                    }
                });
    }

    private Tokens exchangeCode(String code, String codeVerifier) throws Exception {
        String tokenUrl = "https://" + domain + "/oauth2/token";
        String form = "grant_type=authorization_code"
                + "&client_id=" + urlEnc(clientId)
                + "&code=" + urlEnc(code)
                + "&redirect_uri=" + urlEnc(redirectUri)
                + "&code_verifier=" + urlEnc(codeVerifier);

        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Token exchange failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonNode n = mapper.readTree(resp.body());
        String at = n.path("access_token").asText(null);
        String it = n.path("id_token").asText(null);
        String rt = n.path("refresh_token").asText(null);
        long expiresIn = n.path("expires_in").asLong(3600);

        return new Tokens(at, it, rt, Instant.now().plusSeconds(expiresIn));
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignore) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else                     new ProcessBuilder("xdg-open", url).start();
        } catch (IOException ioe) {
            throw new RuntimeException("Cannot open browser", ioe);
        }
    }

    // --------------------------- Helpers ------------------------------
    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
    private static String base64UrlNoPad(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static byte[] sha256(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
    }

    private void saveToPrefs(Tokens t) {
        try {
            prefs.put("access_token", t.accessToken == null ? "" : t.accessToken);
            prefs.put("id_token", t.idToken == null ? "" : t.idToken);
            prefs.put("refresh_token", t.refreshToken == null ? "" : t.refreshToken);
            prefs.putLong("expires_at", t.expiresAt == null ? 0L : t.expiresAt.getEpochSecond());
        } catch (Exception ignored) {}
    }
    private void loadFromPrefs() {
        try {
            String at = prefs.get("access_token", null);
            String it = prefs.get("id_token", null);
            String rt = prefs.get("refresh_token", null);
            long exp  = prefs.getLong("expires_at", 0L);
            if (at != null && it != null && exp > 0) tokens = new Tokens(at, it, rt, Instant.ofEpochSecond(exp));
        } catch (Exception ignored) {}
    }
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    /** Tiny querystring parser */
    private static final class QueryString {
        private final java.util.Map<String, String> map = new java.util.HashMap<>();
        QueryString(String raw) {
            if (raw == null || raw.isBlank()) return;
            for (String part : raw.split("&")) {
                int i = part.indexOf('=');
                if (i > 0) {
                    String k = URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
                    map.put(k, v);
                }
            }
        }
        String get(String k) { return map.get(k); }
    }
}
