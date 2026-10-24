package cadencefinder.search;

import cadencefinder.search.SimilarController.SimilarHit;
import cadencefinder.search.SimilarController.SimilarResults;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AskController {

    private static final int MAX_QUESTION = 500;
    private static final int STRUCTURED = 4;
    private static final int PASSAGES = 8;

    public record Request(String question) {
    }

    public record Response(String answer, List<Asker.Citation> citations, List<SimilarHit> passages,
            NaturalLanguageParser.ParsedQuery interpretation, int attempts) {
    }

    private record Span(String movementId, int firstBar, int lastBar) {
    }

    private final NaturalLanguageParser parser;
    private final SearchService search;
    private final Asker asker;
    private final RestClient embedder;

    public AskController(NaturalLanguageParser parser, SearchService search, Asker asker, RestClient.Builder http,
            @Value("${cadence.embedder-url}") String embedderUrl) {
        this.parser = parser;
        this.search = search;
        this.asker = asker;
        this.embedder = http.baseUrl(embedderUrl).build();
    }

    @PostMapping("/ask")
    public Response ask(@RequestBody Request request) {
        String question = request.question();
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question must be 1 to " + MAX_QUESTION + " characters");
        }
        NaturalLanguageParser.ParsedQuery interpretation = parser.parse(question);
        Map<String, SimilarHit> passages = new LinkedHashMap<>();
        structured(interpretation).forEach(hit -> passages.putIfAbsent(hit.passageId(), hit));
        similar(question).forEach(hit -> {
            if (passages.size() < PASSAGES) {
                passages.putIfAbsent(hit.passageId(), hit);
            }
        });
        if (passages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "no passages retrieved for this question");
        }
        List<SimilarHit> retrieved = List.copyOf(passages.values());
        try {
            Asker.Result result = asker.ask(question, retrieved);
            return new Response(result.answer().answer(), result.answer().citations(), retrieved, interpretation,
                    result.attempts());
        } catch (Asker.InvalidCitationsException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        }
    }

    private List<SimilarHit> structured(NaturalLanguageParser.ParsedQuery interpretation) {
        if (interpretation.query().isBlank()) {
            return List.of();
        }
        SearchService.Page page;
        try {
            page = search.search(interpretation.query(), interpretation.corpus().isBlank() ? null : interpretation.corpus(),
                    STRUCTURED, null);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (page.results().isEmpty()) {
            return List.of();
        }
        List<Span> spans = page.results().stream().map(hit -> new Span(hit.movementId(), hit.firstBar(), hit.lastBar())).toList();
        return embedder.post().uri("/covering").body(Map.of("spans", spans)).retrieve().body(SimilarResults.class).results();
    }

    private List<SimilarHit> similar(String question) {
        return embedder.post()
                .uri("/search")
                .body(Map.of("text", question, "k", PASSAGES, "rerank", true))
                .retrieve()
                .body(SimilarResults.class)
                .results();
    }
}
