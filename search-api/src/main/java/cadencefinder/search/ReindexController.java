package cadencefinder.search;

import cadencefinder.store.CadenceTable;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ReindexController {

    public record Queued(String movementId) {
    }

    private final CadenceTable table;
    private final SqsTemplate sqs;
    private final String queue;

    public ReindexController(CadenceTable table, SqsTemplate sqs, @Value("${cadence.queue}") String queue) {
        this.table = table;
        this.sqs = sqs;
        this.queue = queue;
    }

    @PostMapping("/reindex/{movementId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Queued reindex(@PathVariable("movementId") String movementId) {
        if (table.read(movementId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown movement " + movementId);
        }
        sqs.send(queue, Map.of("movementId", movementId));
        return new Queued(movementId);
    }
}
