package MainCenter.terminal;

public interface TerminalIO {
    void out(String line);
    void err(String line);
    void clear();
    void exit();
}
