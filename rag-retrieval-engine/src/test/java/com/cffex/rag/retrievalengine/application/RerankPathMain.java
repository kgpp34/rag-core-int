package com.cffex.rag.retrievalengine.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.client.RestClient;

import com.cffex.rag.retrievalengine.application.command.ModelEndpointCommand;
import com.cffex.rag.retrievalengine.application.command.RerankGlobalRankingCommand;
import com.cffex.rag.retrievalengine.config.RetrievalHttpClientProperties;
import com.cffex.rag.retrievalengine.domain.RetrievalCandidate;
import com.cffex.rag.retrievalengine.infrastructure.ranking.HttpRerankAdapter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runs the real rag-core-int rerank path without booting the whole Spring app.
 *
 * <p>Call path:
 * HttpRerankAdapter -> RerankRankingService.rerankCandidates -> CandidateSet post-processing.
 */
public final class RerankPathMain {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_REQUEST_FILE = "traces/rag/global_rerank_request.json";
    private static final String DEFAULT_ENDPOINT = "http://172.31.73.27/jina_like_rerank/rerank";
    private static final String DEFAULT_TOKEN = "cffex-bhckgfzs31uadgek";
    private static final int DEFAULT_REQUESTS = 54;
    private static final int DEFAULT_CONCURRENCY = 54;
    private static final int DEFAULT_MAX_CONNECTIONS = 200;
    private static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 100;

    private RerankPathMain() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        RerankInput input = readInput(config.requestFile());
        List<RetrievalCandidate> candidates = toCandidates(input.documents());

        HttpRerankAdapter rerankAdapter = new HttpRerankAdapter(
                RestClient.builder(),
                new RetrievalHttpClientProperties(config.maxConnections(), config.maxConnectionsPerRoute())
        );
        RerankRankingService rerankRankingService = new RerankRankingService(rerankAdapter);
        RerankGlobalRankingCommand command = new RerankGlobalRankingCommand(new ModelEndpointCommand(
                config.endpoint(),
                config.token(),
                config.model() == null ? input.model() : config.model()
        ));

        System.out.printf(
                "RerankPathMain start | requestFile=%s, endpoint=%s, model=%s, candidates=%d, topN=%d, requests=%d, concurrency=%d, executor=%s, maxConn=%d, maxConnPerRoute=%d%n",
                config.requestFile(),
                config.endpoint(),
                config.model() == null ? input.model() : config.model(),
                candidates.size(),
                input.topN(),
                config.requests(),
                config.concurrency(),
                config.executor(),
                config.maxConnections(),
                config.maxConnectionsPerRoute()
        );

        runBatch(config, input, candidates, rerankRankingService, command);
    }

    private static void runBatch(
            Config config,
            RerankInput input,
            List<RetrievalCandidate> candidates,
            RerankRankingService rerankRankingService,
            RerankGlobalRankingCommand command
    ) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger taskSeq = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = createExecutor(config)) {
            List<Future<?>> futures = new ArrayList<>(config.requests());
            for (int i = 0; i < config.requests(); i++) {
                futures.add(executor.submit(() -> {
                    int taskId = taskSeq.incrementAndGet();
                    await(startGate);
                    long start = System.nanoTime();
                    try {
                        List<RetrievalCandidate> result = rerankRankingService.rerankCandidates(
                                input.query(),
                                candidates,
                                command,
                                input.topN(),
                                input.scoreThreshold()
                        );
                        long elapsedMs = elapsedMs(start);
                        latencies.add(elapsedMs);
                        success.incrementAndGet();
                        System.out.printf(
                                "task=%d ok elapsedMs=%d output=%d%n",
                                taskId,
                                elapsedMs,
                                result.size()
                        );
                    } catch (RuntimeException ex) {
                        long elapsedMs = elapsedMs(start);
                        latencies.add(elapsedMs);
                        failed.incrementAndGet();
                        System.out.printf(
                                "task=%d failed elapsedMs=%d error=%s%n",
                                taskId,
                                elapsedMs,
                                summarize(ex)
                        );
                    }
                }));
            }

            Instant batchStart = Instant.now();
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            double totalSeconds = Duration.between(batchStart, Instant.now()).toNanos() / 1_000_000_000.0d;
            printSummary(config.requests(), success.get(), failed.get(), totalSeconds, latencies);
        }
    }

    private static ExecutorService createExecutor(Config config) {
        return switch (config.executor()) {
            case "fixed" -> Executors.newFixedThreadPool(config.concurrency());
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            default -> throw new IllegalArgumentException("Unsupported executor: " + config.executor());
        };
    }

    private static RerankInput readInput(Path requestFile) {
        try {
            return OBJECT_MAPPER.readValue(Files.readString(requestFile), RerankInput.class).normalized();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Failed to read rerank request file: " + requestFile, ex);
        }
    }

    private static List<RetrievalCandidate> toCandidates(List<String> documents) {
        List<RetrievalCandidate> candidates = new ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            candidates.add(new RetrievalCandidate(
                    "chunk-" + i,
                    "document-" + i,
                    "kb-test",
                    documents.get(i),
                    0.0d,
                    null,
                    Map.of("doc_id", "doc-" + i)
            ));
        }
        return List.copyOf(candidates);
    }

    private static void printSummary(
            int requests,
            int success,
            int failed,
            double totalSeconds,
            List<Long> latencies
    ) {
        List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
        double rps = requests / totalSeconds;
        System.out.printf(
                "summary requests=%d success=%d failed=%d totalSeconds=%.3f rps=%.3f min=%d p50=%d p90=%d p99=%d max=%d avg=%.1f%n",
                requests,
                success,
                failed,
                totalSeconds,
                rps,
                percentile(sorted, 0.0d),
                percentile(sorted, 0.50d),
                percentile(sorted, 0.90d),
                percentile(sorted, 0.99d),
                percentile(sorted, 1.0d),
                average(sorted)
        );
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        index = Math.max(0, Math.min(index, values.size() - 1));
        return values.get(index);
    }

    private static double average(List<Long> values) {
        if (values.isEmpty()) {
            return 0.0d;
        }
        long sum = 0L;
        for (Long value : values) {
            sum += value;
        }
        return sum / (double) values.size();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted before starting rerank task", ex);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String summarize(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record RerankInput(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") Integer topN,
            @JsonProperty("score_threshold") Double scoreThreshold
    ) {
        private RerankInput normalized() {
            Objects.requireNonNull(model, "model must not be null");
            Objects.requireNonNull(query, "query must not be null");
            Objects.requireNonNull(documents, "documents must not be null");
            return new RerankInput(
                    model,
                    query,
                    List.copyOf(documents),
                    topN == null || topN <= 0 ? documents.size() : Math.min(topN, documents.size()),
                    scoreThreshold == null ? 0.0d : scoreThreshold
            );
        }
    }

    private record Config(
            Path requestFile,
            String endpoint,
            String token,
            String model,
            int requests,
            int concurrency,
            String executor,
            int maxConnections,
            int maxConnectionsPerRoute
    ) {
        private static Config parse(String[] args) {
            Path requestFile = Path.of(DEFAULT_REQUEST_FILE);
            String endpoint = DEFAULT_ENDPOINT;
            String token = DEFAULT_TOKEN;
            String model = null;
            int requests = DEFAULT_REQUESTS;
            int concurrency = DEFAULT_CONCURRENCY;
            String executor = "virtual";
            int maxConnections = DEFAULT_MAX_CONNECTIONS;
            int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;

            for (String arg : args) {
                if (arg.startsWith("--request-file=")) {
                    requestFile = Path.of(arg.substring("--request-file=".length()));
                } else if (arg.startsWith("--endpoint=")) {
                    endpoint = arg.substring("--endpoint=".length());
                } else if (arg.startsWith("--token=")) {
                    token = arg.substring("--token=".length());
                } else if (arg.startsWith("--model=")) {
                    model = arg.substring("--model=".length());
                } else if (arg.startsWith("--requests=")) {
                    requests = Integer.parseInt(arg.substring("--requests=".length()));
                } else if (arg.startsWith("--concurrency=")) {
                    concurrency = Integer.parseInt(arg.substring("--concurrency=".length()));
                } else if (arg.startsWith("--executor=")) {
                    executor = arg.substring("--executor=".length());
                } else if (arg.startsWith("--max-connections=")) {
                    maxConnections = Integer.parseInt(arg.substring("--max-connections=".length()));
                } else if (arg.startsWith("--max-connections-per-route=")) {
                    maxConnectionsPerRoute = Integer.parseInt(arg.substring("--max-connections-per-route=".length()));
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (requests <= 0) {
                throw new IllegalArgumentException("--requests must be positive");
            }
            if (concurrency <= 0) {
                throw new IllegalArgumentException("--concurrency must be positive");
            }
            if (!"virtual".equals(executor) && !"fixed".equals(executor)) {
                throw new IllegalArgumentException("--executor must be virtual or fixed");
            }
            return new Config(
                    requestFile,
                    endpoint,
                    token,
                    model,
                    requests,
                    concurrency,
                    executor,
                    maxConnections,
                    maxConnectionsPerRoute
            );
        }

        private static void printUsageAndExit() {
            System.out.println("""
                    Usage:
                      mvn -pl rag-retrieval-engine -DskipTests test-compile
                      java -cp "<test-and-project-classpath>" com.cffex.rag.retrievalengine.application.RerankPathMain [options]

                    Options:
                      --request-file=<path>                 default: traces/rag/global_rerank_request.json
                      --endpoint=<url>                      default: http://172.31.73.27/jina_like_rerank/rerank
                      --token=<token>                       default: cffex-bhckgfzs31uadgek
                      --model=<model>                       override request file model
                      --requests=<n>                        default: 54
                      --concurrency=<n>                     default: 54
                      --executor=virtual|fixed              default: virtual
                      --max-connections=<n>                 default: 200
                      --max-connections-per-route=<n>       default: 100
                    """);
            System.exit(0);
        }
    }
}
