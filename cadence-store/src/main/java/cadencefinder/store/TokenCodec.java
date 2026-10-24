package cadencefinder.store;

import cadencefinder.corpus.Token;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

final class TokenCodec {

    private TokenCodec() {
    }

    static String encode(List<Token> tokens) {
        return tokens.stream()
                .map(t -> String.join("|", t.chord(), Integer.toString(t.firstBar()), Integer.toString(t.lastBar()),
                        t.cadence(), Integer.toString(t.segment())))
                .collect(Collectors.joining(";"));
    }

    static List<Token> decode(String encoded) {
        if (encoded.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(encoded.split(";"))
                .map(item -> item.split("\\|", -1))
                .map(f -> new Token(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), f[3], Integer.parseInt(f[4])))
                .toList();
    }
}
