package MainCenter.handlers;

import MainCenter.terminal.CommandCall;
import MainCenter.terminal.CommandHandler;
import MainCenter.terminal.TerminalIO;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class BuiltinHandlers {

    public static class Echo implements CommandHandler {
        @Override public String id() { return "echo"; }
        @Override public void execute(CommandCall call, TerminalIO io) {
            io.out(call.args());
        }
    }

    public static class Time implements CommandHandler {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
        @Override public String id() { return "time"; }
        @Override public void execute(CommandCall call, TerminalIO io) {
            io.out("⏱ " + LocalTime.now().format(FMT));
        }
    }

    public static class Clear implements CommandHandler {
        @Override public String id() { return "clear"; }
        @Override public void execute(CommandCall call, TerminalIO io) { io.clear(); }
    }

    public static class Exit implements CommandHandler {
        @Override public String id() { return "exit"; }
        @Override public void execute(CommandCall call, TerminalIO io) { io.exit(); }
    }
}
