package cadencefinder.corpus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class CorpusCli {

    private static final String SUFFIX = ".harmonies.tsv";

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: stats <corpus-root> | search <corpus-root> <query> [corpus-id]");
            System.exit(2);
        }
        List<Movement> movements = load(Path.of(args[1]));
        switch (args[0]) {
            case "stats" -> stats(movements);
            case "search" -> search(movements, Query.parse(args[2]), args.length > 3 ? args[3] : "");
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    static List<Movement> load(Path root) throws IOException {
        List<Movement> movements = new ArrayList<>();
        for (Corpus corpus : Corpus.values()) {
            try (Stream<Path> files = Files.list(root.resolve(corpus.directory()).resolve("harmonies"))) {
                for (Path file : files.filter(f -> f.toString().endsWith(SUFFIX)).sorted().toList()) {
                    String stem = file.getFileName().toString().replace(SUFFIX, "");
                    movements.add(Movement.parse(corpus, stem, Files.readString(file)));
                }
            }
        }
        return movements;
    }

    private static void stats(List<Movement> movements) {
        for (Corpus corpus : Corpus.values()) {
            List<Movement> group = movements.stream().filter(m -> m.corpus() == corpus).toList();
            Map<String, Integer> cadences = new TreeMap<>();
            Map<String, Integer> augmentedSixths = new TreeMap<>();
            int l1 = 0;
            int l2 = 0;
            int grams = 0;
            int passages = 0;
            for (Movement movement : group) {
                l1 += movement.l1().size();
                l2 += movement.l2().size();
                grams += NGrams.of(movement.l1(), Level.L1).size() + NGrams.of(movement.l2(), Level.L2).size();
                passages += Passage.of(movement).size();
                for (Token token : movement.l1()) {
                    if (token.hasCadence()) {
                        cadences.merge(token.cadence(), 1, Integer::sum);
                    }
                    if (token.chord().matches("(Ger|It|Fr).*")) {
                        augmentedSixths.merge(token.chord(), 1, Integer::sum);
                    }
                }
            }
            System.out.printf("%s: %d movements, %d L1 tokens, %d L2 tokens, %d index keys, %d passages%n",
                    corpus.id(), group.size(), l1, l2, grams, passages);
            System.out.println("  cadences: " + cadences);
            System.out.println("  augmented sixths: " + augmentedSixths);
        }
        Map<String, Integer> unknown = new TreeMap<>();
        for (Movement movement : movements) {
            for (Level level : Level.values()) {
                for (Token token : movement.tokens(level)) {
                    if (!Query.isChord(token.chord())) {
                        unknown.merge(token.chord(), 1, Integer::sum);
                    }
                }
            }
        }
        System.out.println("tokens the query grammar cannot express: " + unknown);
    }

    private static void search(List<Movement> movements, Query query, String corpusId) {
        int total = 0;
        for (Movement movement : movements) {
            if (!corpusId.isEmpty() && !movement.corpus().id().equals(corpusId)) {
                continue;
            }
            for (Matcher.Match match : Matcher.find(query, movement.tokens(query.level()))) {
                total++;
                List<Token> tokens = movement.tokens(query.level()).subList(match.start(), match.end() + 1);
                System.out.printf("%s  %s  bars %d-%d  %s%n", movement.id(), movement.title(), match.firstBar(), match.lastBar(),
                        String.join(" ", tokens.stream().map(t -> t.chord() + (t.hasCadence() ? "@" + t.cadence() : "")).toList()));
            }
        }
        System.out.println(total + " matches at " + query.level());
    }
}
