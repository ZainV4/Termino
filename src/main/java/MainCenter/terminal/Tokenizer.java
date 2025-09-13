package MainCenter.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// splits: respects "double quotes" and 'single quotes'
public class Tokenizer {
    private static final Pattern TOKENS =
            Pattern.compile("\"([^\"]*)\"|'([^']*)'|\\S+");

    public static List<String> split(String s) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKENS.matcher(s);
        while (m.find()) {
            String dq = m.group(1);
            String sq = m.group(2);
            tokens.add(dq != null ? dq : (sq != null ? sq : m.group()));
        }
        return tokens;
    }
}
