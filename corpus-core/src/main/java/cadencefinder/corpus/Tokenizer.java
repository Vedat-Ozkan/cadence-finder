package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class Tokenizer {

    private static final Pattern CHANGES = Pattern.compile("\\([^)]*\\)");

    private Tokenizer() {
    }

    public static List<Token> tokenize(List<Harmony> harmonies, Level level) {
        List<Token> tokens = new ArrayList<>();
        int segment = 0;
        for (Harmony harmony : harmonies) {
            if (harmony.numeral().equals("@none")) {
                segment++;
                continue;
            }
            String cadence = cadenceCode(harmony.cadence());
            Token last = tokens.isEmpty() ? null : tokens.getLast();
            boolean sameSegment = last != null && last.segment() == segment;
            if (harmony.numeral().isEmpty()) {
                if (!cadence.isEmpty() && sameSegment) {
                    tokens.set(tokens.size() - 1, last.withCadence(cadence));
                }
                continue;
            }
            String chord = chordText(harmony, level);
            if (sameSegment && !last.hasCadence() && last.chord().equals(chord)) {
                tokens.set(tokens.size() - 1, last.extendedTo(harmony.lastBar(), cadence));
            } else {
                tokens.add(new Token(chord, harmony.mn(), harmony.lastBar(), cadence, segment));
            }
        }
        return tokens;
    }

    static String chordText(Harmony harmony, Level level) {
        if (harmony.isAugmentedSixth()) {
            String chord = CHANGES.matcher(harmony.chord()).replaceAll("");
            return level == Level.L1 ? chord : harmony.chordType() + appliedPart(chord);
        }
        String applied = harmony.relativeroot().isEmpty() ? "" : "/" + harmony.relativeroot();
        String figbass = level == Level.L1 ? harmony.figbass() : "";
        return harmony.numeral() + harmony.form() + figbass + applied;
    }

    private static String appliedPart(String chord) {
        int slash = chord.indexOf('/');
        return slash < 0 ? "" : chord.substring(slash);
    }

    private static String cadenceCode(String cadence) {
        int dot = cadence.indexOf('.');
        return dot < 0 ? cadence : cadence.substring(0, dot);
    }
}
