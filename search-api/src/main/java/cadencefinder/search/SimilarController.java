package cadencefinder.search;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SimilarController {

    private static final int MAX_K = 50;

    public record SimilarHit(String passageId, String movementId, int firstBar, int lastBar, String text, double score) {
    }

    public record SimilarResults(List<SimilarHit> results) {
    }

    private final RestClient embedder;

    public SimilarController(RestClient.Builder http, @Value("${cadence.embedder-url}") String embedderUrl) {
        this.embedder = http.baseUrl(embedderUrl).build();
    }

    @GetMapping("/passages/{id}/similar")
    public SimilarResults similar(
            @PathVariable("id") String passageId,
            @RequestParam(name = "k", defaultValue = "5") int k,
            @RequestParam(name = "rerank", defaultValue = "false") boolean rerank) {
        if (k < 1 || k > MAX_K) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "k must be between 1 and " + MAX_K);
        }
        return embedder.post()
                .uri("/search")
                .body(Map.of("passageId", passageId, "k", k, "rerank", rerank))
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown passage " + passageId);
                })
                .body(SimilarResults.class);
    }
}
