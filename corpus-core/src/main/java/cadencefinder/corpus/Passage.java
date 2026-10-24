package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final Map<String, String> AUGMENTED_SIXTHS = Map.of(
            "Ger", "German augmented sixth",
            "It", "Italian augmented sixth",
            "Fr", "French augmented sixth");

    private static final Map<String, String> DEGREES = Map.of(
            "I", "tonic",
            "II", "supertonic",
            "III", "mediant",
            "IV", "subdominant",
            "V", "dominant",
            "VI", "submediant",
            "VII", "subtonic");

    private static final Map<String, String> FORMS = Map.of(
            "o", " diminished",
            "%", " half-diminished",
            "+", " augmented",
            "M", " major");

    private static final Map<String, String> FIGURES = Map.of(
            "6", " in first inversion",
            "64", " six-four",
            "7", "",
            "65", " in first inversion",
            "43", " in second inversion",
            "2", " in third inversion");

    private static final Pattern AUGMENTED_SIXTH = Pattern.compile("(Ger|It|Fr)\\d*(?:/(.+))?");
    private static final Pattern CHORD = Pattern.compile("([b#]?)([IViv]+)([o%+M]?)(\\d*)(?:/(.+))?");

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
        String glosses = window.stream()
                .map(t -> gloss(t.chord()))
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.joining(", "));
        String cadences = window.stream()
                .filter(Token::hasCadence)
                .map(t -> CADENCE_NAMES.get(t.cadence()) + " at bar " + t.lastBar())
                .collect(Collectors.joining("; "));
        return "%s.%s%s %s, %s, %s, bars %d-%d.".formatted(
                chords,
                glosses.isEmpty() ? "" : " " + capitalized(glosses) + ".",
                cadences.isEmpty() ? "" : " " + capitalized(cadences) + ".",
                movement.corpus().composer(),
                movement.title(),
                keyName(movement.globalkey()),
                firstBar,
                lastBar);
    }

    static String gloss(String chord) {
        Matcher sixth = AUGMENTED_SIXTH.matcher(chord);
        if (sixth.matches()) {
            return AUGMENTED_SIXTHS.get(sixth.group(1)) + target(sixth.group(2));
        }
        Matcher m = CHORD.matcher(chord);
        if (!m.matches() || !FIGURES.containsKey(m.group(4)) && !m.group(4).isEmpty()) {
            return null;
        }
        String accidental = m.group(1);
        String numeral = m.group(2);
        String form = m.group(3);
        String figure = m.group(4);
        String applied = m.group(5);
        boolean plain = form.isEmpty() && (figure.isEmpty() || figure.equals("6")) && applied == null;
        if (plain && (accidental.isEmpty() || accidental.equals("#") && numeral.equals("vii"))) {
            return null;
        }
        boolean seventh = figure.equals("7") || figure.equals("65") || figure.equals("43") || figure.equals("2");
        String inversion = figure.isEmpty() ? "" : FIGURES.get(figure);
        String degree = degree(accidental, numeral, form.isEmpty());
        if (degree.equals("Neapolitan") && figure.equals("6") && form.isEmpty()) {
            return "Neapolitan sixth" + target(applied);
        }
        return (applied == null ? "" : "applied ")
                + degree
                + FORMS.getOrDefault(form, "")
                + (seventh ? " seventh" : !form.isEmpty() && !form.equals("M") ? " triad" : "")
                + target(applied)
                + inversion;
    }

    private static String degree(String accidental, String numeral, boolean showMinor) {
        String upper = numeral.toUpperCase();
        if (upper.equals("VII") && !numeral.equals(upper) && !accidental.equals("b")) {
            return "leading-tone";
        }
        if (accidental.equals("b") && numeral.equals("II")) {
            return "Neapolitan";
        }
        boolean minor = showMinor && accidental.isEmpty() && !numeral.equals(upper) && (upper.equals("I") || upper.equals("IV") || upper.equals("V"));
        String prefix = switch (accidental) {
            case "b" -> "flat ";
            case "#" -> "raised ";
            default -> "";
        };
        return prefix + (minor ? "minor " : "") + DEGREES.getOrDefault(upper, numeral);
    }

    private static String target(String applied) {
        if (applied == null) {
            return "";
        }
        int slash = applied.indexOf('/');
        String head = slash < 0 ? applied : applied.substring(0, slash);
        Matcher m = CHORD.matcher(head);
        String name = m.matches() ? degree(m.group(1), m.group(2), false) : head;
        return " of the " + name + target(slash < 0 ? null : applied.substring(slash + 1));
    }

    private static String capitalized(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
