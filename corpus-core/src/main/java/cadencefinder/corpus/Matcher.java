package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.List;

public final class Matcher {

    private Matcher() {
    }

    public record Match(int start, int end, int firstBar, int lastBar) {
    }

    public static List<Match> find(Query query, List<Token> tokens) {
        List<Match> matches = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start++) {
            Match match = matchAt(query, tokens, start);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches;
    }

    public static Match matchAt(Query query, List<Token> tokens, int start) {
        if (!query.steps().getFirst().matches(tokens.get(start))) {
            return null;
        }
        int end = extend(query, tokens, 1, start, tokens.get(start).segment());
        if (end < 0) {
            return null;
        }
        return new Match(start, end, tokens.get(start).firstBar(), tokens.get(end).lastBar());
    }

    private static int extend(Query query, List<Token> tokens, int step, int previous, int segment) {
        if (step == query.steps().size()) {
            return previous;
        }
        Query.Step next = query.steps().get(step);
        for (int gap = 0; gap <= next.maxGapBefore(); gap++) {
            int index = previous + 1 + gap;
            if (index >= tokens.size() || tokens.get(index).segment() != segment) {
                return -1;
            }
            if (next.matches(tokens.get(index))) {
                int end = extend(query, tokens, step + 1, index, segment);
                if (end >= 0) {
                    return end;
                }
            }
        }
        return -1;
    }
}
