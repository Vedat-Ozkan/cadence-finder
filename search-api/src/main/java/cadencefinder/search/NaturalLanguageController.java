package cadencefinder.search;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NaturalLanguageController {

    private static final int MAX_TEXT = 500;
    private static final int LIMIT = 50;

    public record Request(String text) {
    }

    public record Response(NaturalLanguageParser.ParsedQuery interpretation, SearchService.Page page) {
    }

    private final NaturalLanguageParser parser;
    private final SearchService search;

    public NaturalLanguageController(NaturalLanguageParser parser, SearchService search) {
        this.parser = parser;
        this.search = search;
    }

    @PostMapping("/nl-search")
    public Response nlSearch(@RequestBody Request request) {
        if (request.text() == null || request.text().isBlank() || request.text().length() > MAX_TEXT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be 1 to " + MAX_TEXT + " characters");
        }
        NaturalLanguageParser.ParsedQuery parsed = parser.parse(request.text());
        if (parsed.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, parsed.note());
        }
        try {
            String corpus = parsed.corpus().isBlank() ? null : parsed.corpus();
            return new Response(parsed, search.search(parsed.query(), corpus, LIMIT, null));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "interpreted as '" + parsed.query() + "' (corpus '" + parsed.corpus() + "'), which is invalid: " + e.getMessage());
        }
    }
}
