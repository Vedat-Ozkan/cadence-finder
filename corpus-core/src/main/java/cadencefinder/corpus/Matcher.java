package cadencefinder.corpus;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

public final class Matcher {

    private Matcher() {
    }

    public record Match(int start, int end, int firstBar, int lastBar) {
    }

    public static List<Match> find(Query query, List<Token> tokens) {
        List<Match> matches = new ArrayList<>();
        BitSet deadEnds = new BitSet();
        for (int start = 0; start < tokens.size(); start++) {
            Match match = matchAt(query, tokens, start, deadEnds);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches;
    }

    public static Match matchAt(Query query, List<Token> tokens, int start) {
        return matchAt(query, tokens, start, new BitSet());
    }

    private static Match matchAt(Query query, List<Token> tokens, int start, BitSet deadEnds) {
        if (!query.steps().getFirst().matches(tokens.get(start))) {
            return null;
        }
        int end = extend(query, tokens, 1, start, deadEnds);
        if (end < 0) {
            return null;
        }
        return new Match(start, end, tokens.get(start).firstBar(), tokens.get(end).lastBar());
    }

    private static int extend(Query query, List<Token> tokens, int step, int previous, BitSet deadEnds) {
        if (step == query.steps().size()) {
            return previous;
        }
        int state = step * tokens.size() + previous;
        if (deadEnds.get(state)) {
            return -1;
        }
        Query.Step next = query.steps().get(step);
        int segment = tokens.get(previous).segment();
        for (int gap = 0; gap <= next.maxGapBefore(); gap++) {
            int index = previous + 1 + gap;
            if (index >= tokens.size() || tokens.get(index).segment() != segment) {
                break;
            }
            if (next.matches(tokens.get(index))) {
                int end = extend(query, tokens, step + 1, index, deadEnds);
                if (end >= 0) {
                    return end;
                }
            }
        }
        deadEnds.set(state);
        return -1;
    }
}
