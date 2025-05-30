package io.kestra.plugin.meilisearch;

import com.meilisearch.sdk.Client;
import com.meilisearch.sdk.Index;
import com.meilisearch.sdk.model.SearchResult;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import reactor.core.publisher.Flux;

import java.io.*;
import java.net.URI;
import java.util.*;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Search for a document in Meilisearch.",
    description = "Perform a basic search query on a Meilisearch database and return the results in an ION file."
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            full = true,
            code = {
                """
                id: meilisearch_search_flow
                namespace: company.team

                variables:
                  index: movies
                  query: "Lord of the Rings"
                  host: http://172.18.0.3:7700/

                tasks:
                  - id: search_documents
                    type: io.kestra.plugin.meilisearch.Search
                    index: "{{ vars.index }}"
                    query: "{{ vars.query }}"
                    url: "{{ vars.host }}"
                    key: "{{ secret('MEILISEARCH_MASTER_KEY') }}"

                  - id: to_json
                    type: io.kestra.plugin.serdes.json.IonToJson
                    from: "{{ outputs.search_documents.uri }}"
                """
            }
        )
    }
)
public class Search extends AbstractMeilisearchConnection implements RunnableTask<Search.Output> {

    @Schema(title = "Search Query", description = "Query performed to search on a specific collection")
    private Property<String> query;
    @Schema(title = "Index", description = "Index of the collection you want to perform a search on")
    private Property<String> index;

    @Override
    public Search.Output run(RunContext runContext) throws Exception {
        Client client = this.createClient(runContext);
        Index searchIndex = client.index(runContext.render(this.index).as(String.class).orElse(null));
        SearchResult results = searchIndex.search(runContext.render(this.query).as(String.class).orElse(null));
        List<HashMap<String, Object>> hits = results.getHits();

        File tempFile = runContext.workingDir().createTempFile(".ion").toFile();

        try (var output = new BufferedWriter(new FileWriter(tempFile), FileSerde.BUFFER_SIZE)) {
            Flux<Map> hitFlux = Flux.fromIterable(hits);
            Long count = FileSerde.writeAll(output, hitFlux).blockOptional().orElse(0L);

            return Output.builder()
                .uri(runContext.storage().putFile(tempFile))
                .totalHits(count)
                .build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "URI to output", description = "Results URI to an Amazon .ion file")
        private final URI uri;
        @Schema(title = "Hits number", description = "Number of items hit by the search request")
        private final Long totalHits;
    }
}
