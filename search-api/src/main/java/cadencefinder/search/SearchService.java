package cadencefinder.search;

import cadencefinder.corpus.Corpus;
import cadencefinder.corpus.Matcher;
import cadencefinder.corpus.Movement;
import cadencefinder.corpus.NGrams;
import cadencefinder.corpus.Query;
import cadencefinder.corpus.Token;
import cadencefinder.store.CadenceTable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    public record Hit(String movementId, String title, int firstBar, int lastBar, String chords) {
    }

    public record Page(String query, String level, List<Hit> results, String nextCursor) {
    }

    private final CadenceTable table;
    private final Map<String, Movement> movements = new ConcurrentHashMap<>();

    public SearchService(CadenceTable table) {
        this.table = table;
    }

    public Page search(String text, String corpusId, int limit, String cursor) {
        Query query = Query.parse(text);
        String prefix = "MOV#";
        if (corpusId != null) {
            Corpus corpus = Corpus.byId(corpusId);
            if (query.usesCadences() && !corpus.hasCadences()) {
                throw new IllegalArgumentException("corpus " + corpusId + " has no cadence labels");
            }
            prefix += corpusId + ":";
        }
        String from = cursor == null ? prefix : CadenceTable.partitionKey(cursor) + "#~";
        List<Hit> hits = new ArrayList<>();
        String next = null;
        try (Stream<String> candidates = table.movementsWith(anchor(query), from, prefix + "￿")) {
            Iterator<String> ids = candidates.iterator();
            while (ids.hasNext()) {
                Movement movement = movements.computeIfAbsent(ids.next(), id -> table.read(id).orElseThrow());
                if (query.usesCadences() && !movement.corpus().hasCadences()) {
                    continue;
                }
                List<Token> tokens = movement.tokens(query.level());
                for (Matcher.Match match : Matcher.find(query, tokens)) {
                    hits.add(new Hit(movement.id(), movement.title(), match.firstBar(), match.lastBar(),
                            tokens.subList(match.start(), match.end() + 1).stream()
                                    .map(t -> t.chord() + (t.hasCadence() ? "@" + t.cadence() : ""))
                                    .collect(Collectors.joining(" "))));
                }
                if (hits.size() >= limit && ids.hasNext()) {
                    next = movement.id();
                    break;
                }
            }
        }
        return new Page(text, query.level().name(), hits, next);
    }

    static String anchor(Query query) {
        List<String> best = List.of();
        List<String> run = new ArrayList<>();
        for (Query.Step step : query.steps()) {
            if (step.chord().isEmpty() || step.maxGapBefore() > 0) {
                run = new ArrayList<>();
            }
            if (!step.chord().isEmpty() && run.size() < NGrams.MAX_N) {
                run.add(step.chord());
                if (run.size() > best.size()) {
                    best = List.copyOf(run);
                }
            }
        }
        String cadence = query.steps().stream().map(Query.Step::cadence).filter(c -> !c.isEmpty()).findFirst().orElse("");
        if (best.size() <= 1 && !cadence.isEmpty()) {
            return NGrams.cadenceKey(query.level(), cadence);
        }
        return NGrams.chordKey(query.level(), best);
    }
}
