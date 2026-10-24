package cadencefinder.corpus;

public record Token(String chord, int firstBar, int lastBar, String cadence, int segment) {

    public boolean hasCadence() {
        return !cadence.isEmpty();
    }

    Token withCadence(String code) {
        return new Token(chord, firstBar, lastBar, code, segment);
    }

    Token extendedTo(int bar, String code) {
        return new Token(chord, firstBar, Math.max(lastBar, bar), code, segment);
    }
}
