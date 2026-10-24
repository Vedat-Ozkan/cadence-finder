package cadencefinder.ingest;

import io.awspring.cloud.sqs.annotation.SqsListener;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IngestListener {

    private final Ingestor ingestor;
    private final JsonMapper json;

    public IngestListener(Ingestor ingestor, JsonMapper json) {
        this.ingestor = ingestor;
        this.json = json;
    }

    @SqsListener("${cadence.queue}")
    public void onMessage(String body) {
        JsonNode message = json.readTree(body);
        if (message.path("Event").asString("").equals("s3:TestEvent")) {
            return;
        }
        if (message.has("Records")) {
            for (JsonNode record : message.get("Records")) {
                String key = record.path("s3").path("object").path("key").asString();
                ingestor.ingestKey(URLDecoder.decode(key, StandardCharsets.UTF_8));
            }
            return;
        }
        if (message.has("movementId")) {
            ingestor.ingestMovement(message.get("movementId").asString());
            return;
        }
        throw new IllegalArgumentException("unrecognised message: " + body);
    }
}
