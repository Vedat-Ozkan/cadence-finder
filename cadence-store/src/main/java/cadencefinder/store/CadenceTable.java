package cadencefinder.store;

import cadencefinder.corpus.Corpus;
import cadencefinder.corpus.Level;
import cadencefinder.corpus.Movement;
import cadencefinder.corpus.NGrams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public final class CadenceTable {

    private static final String INDEX = "gsi1";
    private static final int BATCH = 25;
    private static final String META = "META";

    private final DynamoDbClient dynamo;
    private final String table;

    public CadenceTable(DynamoDbClient dynamo, String table) {
        this.dynamo = dynamo;
        this.table = table;
    }

    public void write(Movement movement) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        for (Level level : Level.values()) {
            for (NGrams.Gram gram : NGrams.of(movement.tokens(level), level)) {
                String position = "%05d".formatted(gram.start());
                items.add(Map.of(
                        "pk", s(partitionKey(movement.id())),
                        "sk", s("OCC#" + gram.key() + "#" + position),
                        "gram", s(gram.key()),
                        "gsi1sk", s(partitionKey(movement.id()) + "#" + position)));
            }
        }
        writeAll(items);
        writeAll(List.of(Map.of(
                "pk", s(partitionKey(movement.id())),
                "sk", s(META),
                "corpus", s(movement.corpus().id()),
                "title", s(movement.title()),
                "globalkey", s(movement.globalkey()),
                "l1", s(TokenCodec.encode(movement.l1())),
                "l2", s(TokenCodec.encode(movement.l2())))));
    }

    public Optional<Movement> read(String movementId) {
        Map<String, AttributeValue> item = dynamo.getItem(b -> b.tableName(table)
                .key(Map.of("pk", s(partitionKey(movementId)), "sk", s(META)))).item();
        if (item == null || item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Movement(
                movementId,
                Corpus.byId(item.get("corpus").s()),
                item.get("title").s(),
                item.get("globalkey").s(),
                TokenCodec.decode(item.get("l1").s()),
                TokenCodec.decode(item.get("l2").s())));
    }

    public Stream<String> movementsWith(String gram, String fromMovementSortKey, String toMovementSortKey) {
        QueryRequest request = QueryRequest.builder()
                .tableName(table)
                .indexName(INDEX)
                .keyConditionExpression("gram = :gram AND gsi1sk BETWEEN :from AND :to")
                .expressionAttributeValues(Map.of(
                        ":gram", s(gram),
                        ":from", s(fromMovementSortKey),
                        ":to", s(toMovementSortKey)))
                .projectionExpression("gsi1sk")
                .build();
        return dynamo.queryPaginator(request).items().stream()
                .map(item -> item.get("gsi1sk").s())
                .map(sortKey -> sortKey.substring("MOV#".length(), sortKey.lastIndexOf('#')))
                .distinct();
    }

    public static String partitionKey(String movementId) {
        return "MOV#" + movementId;
    }

    private void writeAll(List<Map<String, AttributeValue>> items) {
        for (int from = 0; from < items.size(); from += BATCH) {
            List<WriteRequest> requests = items.subList(from, Math.min(from + BATCH, items.size())).stream()
                    .map(item -> WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build())
                    .toList();
            Map<String, List<WriteRequest>> pending = new HashMap<>(Map.of(table, requests));
            for (int attempt = 0; !pending.isEmpty(); attempt++) {
                if (attempt > 0) {
                    sleep(Math.min(1000L, 50L << attempt));
                }
                Map<String, List<WriteRequest>> batch = pending;
                BatchWriteItemResponse response = dynamo.batchWriteItem(b -> b.requestItems(batch));
                pending = response.hasUnprocessedItems() ? new HashMap<>(response.unprocessedItems()) : Map.of();
                if (attempt == 8 && !pending.isEmpty()) {
                    throw new IllegalStateException("DynamoDB did not accept " + pending.get(table).size() + " items");
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
