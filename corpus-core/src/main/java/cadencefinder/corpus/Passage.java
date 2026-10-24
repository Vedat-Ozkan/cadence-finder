package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record Passage(String id, String movementId, int firstBar, int lastBar, String text) {

    public static final int WINDOW = 8;
    public static final int STRIDE = 4;

    private static final Map<String, String> CADENCE_NAMES = Map.of(
            "PAC", "perfect authentic cadence",
            "IAC", "imperfect authentic cadence",
            "HC", "half cadence",
            "DC", "deceptive cadence",
            "EC", "evaded cadence",
            "PC", "plagal cadence");

    public static List<Passage> of(Movement movement) {
        List<Token> tokens = movement.l1();
        List<Passage> passages = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start += STRIDE) {
            List<Token> window = tokens.subList(start, Math.min(start + WINDOW, tokens.size()));
            int firstBar = window.getFirst().firstBar();
            int lastBar = window.stream().mapToInt(Token::lastBar).max().orElse(firstBar);
            passages.add(new Passage(
                    movement.id() + "#" + passages.size(),
                    movement.id(),
                    firstBar,
                    lastBar,
                    describe(movement, window, firstBar, lastBar)));
            if (start + WINDOW >= tokens.size()) {
                break;
            }
        }
        return passages;
    }

    private static String describe(Movement movement, List<Token> window, int firstBar, int lastBar) {
        String chords = window.stream().map(Token::chord).collect(Collectors.joining(" "));
        String cadences = window.stream()
                .filter(Token::hasCadence)
                .map(t -> CADENCE_NAMES.get(t.cadence()) + " at bar " + t.lastBar())
                .collect(Collectors.joining("; "));
        return "%s, %s, %s, bars %d-%d: %s%s".formatted(
                movement.corpus().composer(),
                movement.title(),
                keyName(movement.globalkey()),
                firstBar,
                lastBar,
                chords,
                cadences.isEmpty() ? "" : ". " + cadences);
    }

    private static String keyName(String key) {
        if (key.isEmpty()) {
            return "key unknown";
        }
        boolean major = Character.isUpperCase(key.charAt(0));
        String tonic = Character.toUpperCase(key.charAt(0)) + key.substring(1).replace('b', '♭').replace('#', '♯');
        return tonic + (major ? " major" : " minor");
    }
}
