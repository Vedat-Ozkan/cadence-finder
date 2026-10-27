package cadencefinder.search;

import cadencefinder.store.CadenceTable;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@SpringBootApplication
public class SearchApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApiApplication.class, args);
    }

    @Bean
    AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }

    @Bean
    CadenceTable cadenceTable(DynamoDbClient dynamo, @Value("${cadence.table}") String table) {
        return new CadenceTable(dynamo, table);
    }

    @Bean
    SearchService searchService(CadenceTable table) {
        return new SearchService(table);
    }
}
