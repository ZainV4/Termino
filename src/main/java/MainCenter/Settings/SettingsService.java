package MainCenter.Settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsService {
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    public SettingsService() {
        this(Path.of(System.getProperty("user.home"), ".fxterminal.json"));
    }
    public SettingsService(Path path) { this.path = path; }

    public Settings load() {
        try {
            if (Files.exists(path)) {
                try (Reader r = Files.newBufferedReader(path)) {
                    Settings s = gson.fromJson(r, Settings.class);
                    return (s != null) ? s : new Settings();
                }
            }
        } catch (Exception ignored) {}
        return new Settings();
    }

    public void save(Settings s) throws IOException {
        try (Writer w = Files.newBufferedWriter(path)) {
            gson.toJson(s, w);
        }
    }
}
