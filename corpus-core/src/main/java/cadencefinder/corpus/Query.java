package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record Query(Level level, List<Query.Step> steps) {

    public static final int MAX_GAP = 4;
    public static final Set<String> CADENCES = Set.of("PAC", "IAC", "HC", "DC", "EC", "PC");

    private static final String ROMAN = "(?:VII|VI|V|IV|III|II|I|vii|vi|v|iv|iii|ii|i)";
    private static final String ACCIDENTAL = "(?:b+|#+)?";
    private static final String FIGBASS = "(?:7|65|43|42|2|64|6)";
    private static final Pattern CHORD = Pattern.compile(
            "(?:" + ACCIDENTAL + ROMAN + "(?:%|o|\\+M|\\+|M)?" + FIGBASS + "?"
                    + "|(?:Ger|It|Fr)" + FIGBASS + "?)"
                    + "(?:/" + ACCIDENTAL + ROMAN + ")*");
    private static final Pattern HAS_FIGBASS = Pattern.compile("^[^/]*\\d");

    public record Step(String chord, String cadence, int maxGapBefore) {

        public boolean matches(Token token) {
            return (chord.isEmpty() || chord.equals(token.chord()))
                    && (cadence.isEmpty() || cadence.equals(token.cadence()));
        }
    }

    public static boolean isChord(String text) {
        return CHORD.matcher(text).matches();
    }

    public static Query parse(String text) {
        String[] parts = text.trim().split("\\s+");
        List<Step> steps = new ArrayList<>();
        boolean gap = false;
        boolean figbass = false;
        for (String part : parts) {
            if (part.equals("...")) {
                if (steps.isEmpty() || gap) {
                    throw new QueryException("'...' must sit between two steps");
                }
                gap = true;
                continue;
            }
            int at = part.indexOf('@');
            String chord = at < 0 ? part : part.substring(0, at);
            String cadence = at < 0 ? "" : part.substring(at + 1);
            if (chord.isEmpty() && cadence.isEmpty()) {
                throw new QueryException("empty step");
            }
            if (!chord.isEmpty() && !isChord(chord)) {
                throw new QueryException("unknown chord '" + chord + "'");
            }
            if (!cadence.isEmpty() && !CADENCES.contains(cadence)) {
                throw new QueryException("unknown cadence '" + cadence + "', expected one of " + CADENCES);
            }
            figbass |= HAS_FIGBASS.matcher(chord).find();
            steps.add(new Step(chord, cadence, gap ? MAX_GAP : 0));
            gap = false;
        }
        if (steps.isEmpty() || gap) {
            throw new QueryException("query must start and end with a step");
        }
        return new Query(figbass ? Level.L1 : Level.L2, List.copyOf(steps));
    }

    public boolean usesCadences() {
        return steps.stream().anyMatch(step -> !step.cadence().isEmpty());
    }

    public static final class QueryException extends IllegalArgumentException {
        public QueryException(String message) {
            super(message);
        }
    }
}
