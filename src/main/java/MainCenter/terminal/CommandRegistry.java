package MainCenter.terminal;

import java.nio.file.Path;
import java.util.*;

public class CommandRegistry {
    private final Map<String, CommandHandler> handlers = new HashMap<>();
    @SuppressWarnings("unused")
    private final Path commandsDir;

    public CommandRegistry(Path commandsDir) {
        this.commandsDir = commandsDir;
    }

    public void registerHandler(CommandHandler h) {
        handlers.put(h.id().toLowerCase(), h);
    }

    public void loadAll() {
        // Stub: JSON loading can be added later
    }

    public List<String> definedCommands() {
        List<String> list = new ArrayList<>(handlers.keySet());
        Collections.sort(list);
        return list;
    }

    public void dispatch(String cmdLine, TerminalIO io) {
        String line = cmdLine == null ? "" : cmdLine.trim();
        if (line.isEmpty()) return;

        String[] parts = line.split("\\s+", 2);
        String cmd  = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        CommandHandler h = handlers.get(cmd);
        if (h == null) {
            io.err("Unknown command: " + cmd);
            return;
        }
        CommandCall call = new CommandCall(line, cmd, args);
        h.execute(call, io);
    }
}
