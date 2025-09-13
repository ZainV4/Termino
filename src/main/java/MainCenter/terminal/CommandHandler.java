package MainCenter.terminal;

public interface CommandHandler {
    /** Unique id used to invoke the command (e.g., "echo", "time"). */
    String id();

    /** Execute the command. */
    void execute(CommandCall call, TerminalIO io);
}
