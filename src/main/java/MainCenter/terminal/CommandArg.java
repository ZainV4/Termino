package MainCenter.terminal;

public class CommandArg {
    public String name;
    public String type;        // "string" | "int" | "bool"
    public boolean required = false;
    public boolean variadic = false;
    public String defaultValue;
}
