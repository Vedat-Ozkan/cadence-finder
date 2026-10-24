package cadencefinder.search;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.StructuredOutputConfig;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.stereotype.Component;

@Component
public class NaturalLanguageParser {

    private static final String MODEL = "claude-opus-5-5";

    private static final String SYSTEM = """
            You translate a music theorist's request into a structured chord-progression query for a search engine \
            over Roman-numeral harmonic analyses (DCML annotation standard).

            Query grammar:
            - Steps are separated by a single space (the next chord follows immediately) or by " ... " (zero to four \
            other chords may come in between).
            - A step is a chord, a chord with a cadence (V@HC), or a cadence alone (@PAC = any chord carrying that cadence).
            - Cadences: PAC (perfect authentic), IAC (imperfect authentic), HC (half), DC (deceptive), \
            EC (evaded), PC (plagal).
            - Chords are relative to the local key. Upper case is major, lower case is minor: I, i, ii, IV, V, vi, \
            bII, #iv. Qualities: o (diminished), % (half-diminished), + (augmented), M (major seventh), e.g. viio7, ii%7.
            - Inversion figures: 6, 64 (triads), 7, 65, 43, 2 (sevenths), e.g. V65, ii6, I6.
            - Applied chords use a slash: V/V, V65/V, viio7/ii.
            - Augmented sixths are Ger, It, Fr, optionally with a figure: Ger6, It6, Fr6, Ger43.
            - The cadential six-four is written V, not I64: write "V V7 I@PAC", never "I64 V".
            - Level: if any chord in the query has an inversion figure, every chord must match exactly as written \
            (V means root position). If no chord has a figure, inversions are ignored (V matches V, V6, V65, V7...). \
            Prefer the figure-free form unless the request names a specific inversion.
            - A figure always means that exact inversion: 7 is a root-position seventh chord, not "any seventh chord". \
            To match a seventh chord in any inversion, omit the figure: V matches V7 and V65, ii% matches ii%7 and ii%65.
            - A minor-key tonic is i. When a cadence's arrival chord is not stated, use the bare cadence step (@PAC).

            Corpora (use the id, or "" for all):
            - abc: Beethoven string quartets. Has no cadence labels, so never combine abc with a cadence.
            - bps: Beethoven piano sonatas. mps: Mozart piano sonatas. Both have cadence labels.

            Examples:
            - "German sixth before a perfect authentic cadence in Beethoven sonatas" -> query "Ger ... @PAC", corpus "bps"
            - "Italian sixth going straight to the dominant in Mozart" -> query "It V", corpus "mps"
            - "deceptive cadences after a dominant seventh" -> query "V7 vi@DC", corpus ""

            If the request cannot be expressed in this grammar, return an empty query and explain why in note.""";

    public record ParsedQuery(
            @JsonPropertyDescription("The query in the grammar, or empty if the request cannot be expressed") String query,
            @JsonPropertyDescription("Corpus id: abc, bps, mps, or empty for all corpora") String corpus,
            @JsonPropertyDescription("One sentence: how the request was interpreted, or why it cannot be expressed") String note) {
    }

    private final AnthropicClient client;

    public NaturalLanguageParser(AnthropicClient client) {
        this.client = client;
    }

    public ParsedQuery parse(String request) {
        StructuredMessageCreateParams<ParsedQuery> params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4000L)
                .system(SYSTEM)
                .outputConfig(StructuredOutputConfig.<ParsedQuery>builder()
                        .format(ParsedQuery.class)
                        .effort(OutputConfig.Effort.LOW)
                        .build())
                .addUserMessage(request)
                .putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build();
        StructuredMessage<ParsedQuery> message = client.messages().create(params);
        if (message.stopReason().filter(reason -> reason.equals(StopReason.END_TURN)).isEmpty()) {
            throw new IllegalStateException("query parser stopped with " + message.stopReason().orElse(null));
        }
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("query parser returned no text"));
    }
}
