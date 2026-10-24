package cadencefinder.ingest;

import cadencefinder.store.CadenceTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication
public class IngestWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestWorkerApplication.class, args);
    }

    @Bean
    CadenceTable cadenceTable(DynamoDbClient dynamo, @Value("${cadence.table}") String table) {
        return new CadenceTable(dynamo, table);
    }
}
