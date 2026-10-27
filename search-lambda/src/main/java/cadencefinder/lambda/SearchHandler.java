package cadencefinder.lambda;

import cadencefinder.search.SearchService;
import cadencefinder.store.CadenceTable;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class SearchHandler implements RequestStreamHandler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 50;

    private final SearchService search;

    public SearchHandler() {
        search = new SearchService(new CadenceTable(DynamoDbClient.create(), System.getenv("CADENCE_TABLE")));
        search.preload();
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        JsonNode request = JSON.readTree(input);
        Object reply;
        try {
            reply = search.search(request.path("query").asText(), text(request, "corpus"),
                    request.path("limit").asInt(DEFAULT_LIMIT), text(request, "cursor"));
        } catch (IllegalArgumentException e) {
            reply = Map.of("error", e.getMessage());
        }
        JSON.writeValue(output, reply);
    }

    private static String text(JsonNode request, String field) {
        return request.hasNonNull(field) ? request.get(field).asText() : null;
    }
}
