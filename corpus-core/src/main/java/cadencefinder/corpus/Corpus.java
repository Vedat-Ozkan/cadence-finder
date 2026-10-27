package cadencefinder.corpus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record Corpus(String id, String directory, String composer, String name, boolean hasCadences) {

    private static final Map<String, Corpus> CORPORA = rows("corpora.tsv").stream()
            .map(row -> new Corpus(row[0], row[1], row[2], row[3], Boolean.parseBoolean(row[4])))
            .collect(Collectors.toMap(Corpus::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    private static final Map<String, String> TITLES = rows("titles.tsv").stream()
            .collect(Collectors.toMap(row -> row[0] + ":" + row[1], row -> row[2]));

    public static List<Corpus> values() {
        return List.copyOf(CORPORA.values());
    }

    public static String catalogue() {
        return CORPORA.values().stream()
                .map(corpus -> "- " + corpus.id + ": " + corpus.name + (corpus.hasCadences ? "" : " (no cadence labels)"))
                .collect(Collectors.joining("\n"));
    }

    public static Corpus byId(String id) {
        Corpus corpus = CORPORA.get(id);
        if (corpus == null) {
            throw new IllegalArgumentException("unknown corpus " + id);
        }
        return corpus;
    }

    public String title(String fileStem) {
        String title = TITLES.get(id + ":" + fileStem);
        if (title == null) {
            throw new IllegalArgumentException(id + ": unexpected file name " + fileStem);
        }
        return title;
    }

    private static List<String[]> rows(String resource) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Corpus.class.getResourceAsStream(resource), StandardCharsets.UTF_8))) {
            return reader.lines().map(line -> line.split("\t", -1)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
