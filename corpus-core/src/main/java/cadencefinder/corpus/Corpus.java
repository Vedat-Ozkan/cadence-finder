package cadencefinder.corpus;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum Corpus {
    ABC("abc", "Beethoven", "abc", false, Pattern.compile("n(\\d+)op(\\d+)(?:-(\\d+))?_(\\d+)")),
    BPS("bps", "Beethoven", "beethoven_piano_sonatas", true, Pattern.compile("(\\d+)-(\\d+)")),
    MPS("mps", "Mozart", "mozart_piano_sonatas", true, Pattern.compile("K(\\d+)-(\\d+)"));

    private final String id;
    private final String composer;
    private final String directory;
    private final boolean hasCadences;
    private final Pattern stem;

    Corpus(String id, String composer, String directory, boolean hasCadences, Pattern stem) {
        this.id = id;
        this.composer = composer;
        this.directory = directory;
        this.hasCadences = hasCadences;
        this.stem = stem;
    }

    public String id() {
        return id;
    }

    public String composer() {
        return composer;
    }

    public String directory() {
        return directory;
    }

    public boolean hasCadences() {
        return hasCadences;
    }

    public static Corpus byId(String id) {
        return Arrays.stream(values())
                .filter(corpus -> corpus.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown corpus " + id));
    }

    public String title(String fileStem) {
        Matcher m = stem.matcher(fileStem);
        if (!m.matches()) {
            throw new IllegalArgumentException(id + ": unexpected file name " + fileStem);
        }
        return switch (this) {
            case ABC -> "String Quartet No. %d, Op. %s%s, mvt %d".formatted(
                    Integer.parseInt(m.group(1)),
                    m.group(2),
                    m.group(3) == null ? "" : " No. " + m.group(3),
                    Integer.parseInt(m.group(4)));
            case BPS -> "Piano Sonata No. %d, mvt %d".formatted(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            case MPS -> "Piano Sonata K. %s, mvt %d".formatted(m.group(1), Integer.parseInt(m.group(2)));
        };
    }
}
