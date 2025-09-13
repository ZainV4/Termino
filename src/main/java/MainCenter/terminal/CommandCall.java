package MainCenter.terminal;

/** Minimal command call object holding the raw line, the command token, and the remainder args string. */
public final class CommandCall {
    private final String raw;
    private final String command;
    private final String args;

    public CommandCall(String raw, String command, String args) {
        this.raw = raw;
        this.command = command;
        this.args = args == null ? "" : args;
    }

    public String raw()     { return raw; }
    public String command() { return command; }
    public String args()    { return args; }
}
