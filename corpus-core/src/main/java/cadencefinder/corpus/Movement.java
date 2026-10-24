package cadencefinder.corpus;

import java.util.List;

public record Movement(String id, Corpus corpus, String title, String globalkey, List<Token> l1, List<Token> l2) {

    public static Movement parse(Corpus corpus, String fileStem, String tsv) {
        List<Harmony> harmonies = HarmonyTsv.parse(tsv, corpus.id() + "/" + fileStem);
        return new Movement(
                corpus.id() + ":" + fileStem,
                corpus,
                corpus.title(fileStem),
                harmonies.isEmpty() ? "" : harmonies.getFirst().globalkey(),
                Tokenizer.tokenize(harmonies, Level.L1),
                Tokenizer.tokenize(harmonies, Level.L2));
    }

    public List<Token> tokens(Level level) {
        return level == Level.L1 ? l1 : l2;
    }
}
