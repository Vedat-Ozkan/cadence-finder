package cadencefinder.corpus;

public record Harmony(
        int mc,
        double mcOnsetQuarters,
        int mn,
        double mnOnsetQuarters,
        double durationQuarters,
        double barQuarters,
        String numeral,
        String form,
        String figbass,
        String relativeroot,
        String chord,
        String chordType,
        String cadence,
        String globalkey) {

    public boolean isAugmentedSixth() {
        return chordType.equals("Ger") || chordType.equals("It") || chordType.equals("Fr");
    }

    public int lastBar() {
        return mn + Math.max(0, (int) Math.floor((mnOnsetQuarters + durationQuarters - 1e-9) / barQuarters));
    }
}
