package MainCenter.terminal;

import java.util.ArrayList;
import java.util.List;

public class CommandSpec {
    public String name;
    public String description;
    public String usage;
    public String handler;           // "builtin:echo" or "class:pkg.ClassName"
    public List<CommandArg> args = new ArrayList<>();
    public List<String> aliases = new ArrayList<>();
}
