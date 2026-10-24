package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;

public final class NGrams {

    public static final int MAX_N = 4;

    private NGrams() {
    }

    public record Gram(String key, int start) {
    }

    public static List<Gram> of(List<Token> tokens, Level level) {
        List<Gram> grams = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start++) {
            Token first = tokens.get(start);
            StringBuilder key = new StringBuilder(level.name()).append('#').append(first.chord());
            grams.add(new Gram(key.toString(), start));
            for (int n = 2; n <= MAX_N && start + n <= tokens.size(); n++) {
                Token next = tokens.get(start + n - 1);
                if (next.segment() != first.segment()) {
                    break;
                }
                key.append('>').append(next.chord());
                grams.add(new Gram(key.toString(), start));
            }
            if (first.hasCadence()) {
                grams.add(new Gram(cadenceKey(level, first.cadence()), start));
            }
        }
        return grams;
    }

    public static String chordKey(Level level, List<String> chords) {
        return level.name() + "#" + String.join(">", chords);
    }

    public static String cadenceKey(Level level, String cadence) {
        return level.name() + "#@" + cadence;
    }
}
