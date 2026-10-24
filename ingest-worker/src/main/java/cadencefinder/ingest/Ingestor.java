package cadencefinder.ingest;

import cadencefinder.corpus.Corpus;
import cadencefinder.corpus.Movement;
import cadencefinder.corpus.Passage;
import cadencefinder.store.CadenceTable;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.json.JsonMapper;

@Service
public class Ingestor {

    private static final Logger log = LoggerFactory.getLogger(Ingestor.class);
    private static final Pattern RAW_KEY = Pattern.compile("raw/([a-z]+)/(.+)\\.harmonies\\.tsv");

    private final S3Client s3;
    private final CadenceTable table;
    private final JsonMapper json;
    private final RestClient embedder;
    private final String bucket;

    public Ingestor(S3Client s3, CadenceTable table, JsonMapper json, RestClient.Builder http,
            @Value("${cadence.bucket}") String bucket, @Value("${cadence.embedder-url}") String embedderUrl) {
        this.s3 = s3;
        this.table = table;
        this.json = json;
        this.embedder = http.baseUrl(embedderUrl).build();
        this.bucket = bucket;
    }

    public void ingestKey(String key) {
        Matcher m = RAW_KEY.matcher(key);
        if (!m.matches()) {
            throw new IllegalArgumentException("not a raw harmonies key: " + key);
        }
        Corpus corpus = Corpus.byId(m.group(1));
        String tsv = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asUtf8String();
        Movement movement = Movement.parse(corpus, m.group(2), tsv);
        List<Passage> passages = Passage.of(movement);
        embedder.post().uri("/passages").body(Map.of("passages", passages)).retrieve().toBodilessEntity();
        table.write(movement);
        String lines = passages.stream()
                .map(json::writeValueAsString)
                .collect(Collectors.joining("\n", "", "\n"));
        s3.putObject(b -> b.bucket(bucket).key("passages/" + corpus.id() + "/" + m.group(2) + ".jsonl"),
                RequestBody.fromString(lines));
        log.info("ingested {} tokens={} passages={}", movement.id(), movement.l1().size(), passages.size());
    }

    public void ingestMovement(String movementId) {
        int colon = movementId.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("movement id must look like corpus:stem, got " + movementId);
        }
        ingestKey("raw/" + movementId.substring(0, colon) + "/" + movementId.substring(colon + 1) + ".harmonies.tsv");
    }
}
