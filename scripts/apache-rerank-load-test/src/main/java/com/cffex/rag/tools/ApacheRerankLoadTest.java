package com.cffex.rag.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

public final class ApacheRerankLoadTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String DEFAULT_URL = "http://172.31.73.27/jina_like_rerank/rerank";
    private static final String DEFAULT_TOKEN = "cffex-bhckgfzs31uadgek";
    private static final int DEFAULT_DURATION_SECONDS = 1;
    private static final int DEFAULT_CONCURRENCY = 18;
    private static final int DEFAULT_MAX_CONNECTIONS = 64;
    private static final int DEFAULT_MAX_CONNECTIONS_PER_ROUTE = 32;

    private static final String QUERY = "国债期货临近交割月并进入交割月的合约保证金率变化是怎样的";
    private static final String MODEL = "bge_m3";

    private static final List<String> DOCS = List.of(
            """
            关于5年期国债期货最小变动价位和交易保证金有关事项的通知
            各会员单位：
            根据我所2015年2月27日修订并发布的5年期国债期货合约及其细则，自2015年3月16日结算时起，5年期国债期货所有挂牌合约的最小变动价位调整为0.005元。
            依据《中国金融期货交易所交易细则》第四十一条，在3月17日5年期国债期货合约交易中，若使用上一交易日收盘价作为前一成交价（cp）时，按5年期国债期货合约最小变动价位0.005元取值后作为前一成交价确定第一笔成交价。
            5年期国债期货新上市合约自2015年3月16日起交易保证金标准为合约价值的1.2%；
            交割月份前一个月下旬的前一交易日结算时起，交易保证金为合约价值的1.5%，交割月份第一个交易日的前一交易日结算时起，交易保证金为合约价值的2%。
            已上市合约的交易保证金仍按我所于2014年10月31日发布的《关于调整5年期国债期货交易保证金的通知》规定的标准执行。
            特此通知。
            中国金融期货交易所
            2015年3月10日
            """,
            """
            1
            30 年期国债期货合约
            合约标的 面值为 100 万元人民币、票面利率为 3%的名义超长期国债
            可交割国债 发行期限不高于 30 年，合约到期月份首日剩余期限不低于 25 年的记账式附息国债
            报价方式 百元净价报价
            最小变动价位 0.01 元
            合约月份 最近的三个季月（3 月、6 月、9 月、12 月中的最近三个月循环）
            交易时间 09:30 - 11:30，13:00 - 15:15
            最后交易日交易时间 09:30 - 11:30
            每日价格最大波动限制 上一交易日结算价的±3.5%
            最低交易保证金 合约价值的 3.5%
            最后交易日 合约到期月份的第二个星期五
            最后交割日 最后交易日后的第三个交易日
            交割方式 实物交割
            交易代码 TL
            上市交易所 中国金融期货交易所
            """,
            """
            三、交易保证金和涨跌停板幅度
            10年期国债期货各合约的交易保证金为合约价值的2%，交割月份前一个月下旬的前一交易日结算时起，交易保证金为合约价值的3%，交割月份第一个交易日的前一交易日结算时起，交易保证金为合约价值的4%。
            上市当日各合约的涨跌停板幅度为挂盘基准价的±4％。
            四、相关费用
            10年期国债期货合约的手续费标准暂定为每手3元，平今仓交易免收手续费，交割手续费标准为每手5元。
            交易所有权根据市场运行情况对手续费标准进行调整。
            """,
            """
            1
            2 年期国债期货合约
            合约标的 面值为 200 万元人民币、票面利率为 3%的名义中短期国债
            可交割国债 发行期限不高于 5 年，合约到期月份首日剩余期限为 1.5-2.25 年的记账式附息国债
            报价方式 百元净价报价
            最小变动价位 0.005 元
            合约月份 最近的三个季月（3 月、6 月、9 月、12 月中的最近三个月循环）
            交易时间 9:30-11:30，13:00-15:15
            最后交易日交易时间 9:30-11:30
            每日价格最大波动限制 上一交易日结算价的±0.5%
            最低交易保证金 合约价值的 0.5%
            最后交易日 合约到期月份的第二个星期五
            最后交割日 最后交易日后的第三个交易日
            交割方式 实物交割
            交易代码 TS
            上市交易所 中国金融期货交易所
            """,
            """
            关于国债期货交割业务有关事项的通知
            各会员单位：
            我所于2015年6月26日修订并发布了《中国金融期货交易所国债期货合约交割细则》，对国债期货交割业务进行调整。
            自2015年7月1日起，参与交割的客户应当事先通过会员向交易所申报国债托管账户。
            在交割月份之前的二个交易日尚未通过国债托管账户审核的客户，自交割月份之前的一个交易日至最后交易日，其在该国债期货交割月份合约的持仓应当为0手。
            自交割月份之前的一个交易日起，交易所按照《中国金融期货交易所风险控制管理办法》的相关规定，对未通过国债托管账户审核客户的交割月份合约持仓予以强行平仓。
            请各会员单位根据业务规则，做好国债托管账户申报和交割风险防范工作。
            特此通知。
            中国金融期货交易所
            2015年8月7日
            """,
            """
            为加强防范交割风险，中金所国债期货将采用滚动交割、实物交割制度。
            滚动交割期：自交割月第一个交易日至最后交易日的前一个交易日，持有交割月合约的卖方主动提出交割申请，交易所按照一定规则选取买方进入交割。
            集中交割期：最后交易日闭市后，同一客户所持有的该交割月合约买卖持仓相对应部分自动平仓后，剩余未平仓交割合约自动进入交割程序。
            一般模式：T日买卖方交割意愿申报，交易所进行交割匹配；T+1日卖方债券过户到中金所账户；T+2日收取买方交割货款；T+4日完成交割。
            DVP模式：T日买卖方交割意愿申报，T+2日交易所向托管机构发送DVP划转指令，T+3日确认完成交割。
            """,
            """
            关于30年期国债期货合约上市交易有关事项的通知
            中金所发〔2023〕21号
            30年期国债期货各合约的交易保证金为合约价值的3.5%，自交割月份之前的两个交易日结算时起，交易保证金标准为合约价值的5%。
            对2年期国债期货、5年期国债期货、10年期国债期货和30年期国债期货的跨品种双向持仓，按照交易保证金单边较大者收取交易保证金。
            上市当日各合约的涨跌停板幅度为挂盘基准价的±7%。
            30年期国债期货合约的手续费标准暂定为每手3元，平今仓交易免收手续费，交割手续费标准为每手5元。
            """,
            """
            中国金融期货交易所30年期国债期货合约交易细则
            本合约的合约标的为面值为100万元人民币、票面利率为3%的名义超长期国债。
            本合约的可交割国债为发行期限不高于30年、合约到期月份首日剩余期限不低于25年的记账式附息国债。
            本合约的最小变动价位为0.01元，合约交易报价为0.01元的整数倍。
            本合约的合约月份为最近的三个季月，交易代码为TL。
            """
    );

    private ApacheRerankLoadTest() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        String requestBody = config.bodyFile() == null
                ? defaultBody()
                : Files.readString(config.bodyFile());

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(config.maxConnections())
                .setMaxConnPerRoute(config.maxConnectionsPerRoute())
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(config.timeoutSeconds()))
                .setResponseTimeout(Timeout.ofSeconds(config.timeoutSeconds()))
                .build();

        System.out.printf(
                "start url=%s duration=%ds requests=%s concurrency=%d executor=%s maxConn=%d maxConnPerRoute=%d bodyBytes=%d%n",
                config.url(),
                config.durationSeconds(),
                config.requests() <= 0 ? "duration-mode" : config.requests(),
                config.concurrency(),
                config.executor(),
                config.maxConnections(),
                config.maxConnectionsPerRoute(),
                requestBody.getBytes(StandardCharsets.UTF_8).length
        );

        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .build();
             ExecutorService executor = createExecutor(config)) {
            if (config.requests() > 0) {
                runFixedRequests(client, config, requestBody, executor);
            } else {
                runDuration(client, config, requestBody, executor);
            }
        }
    }

    private static void runFixedRequests(
            CloseableHttpClient client,
            Config config,
            String requestBody,
            ExecutorService executor
    ) throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        Semaphore permits = new Semaphore(config.concurrency());
        List<Future<Result>> futures = new ArrayList<>(config.requests());
        for (int i = 1; i <= config.requests(); i++) {
            int taskId = i;
            futures.add(executor.submit(() -> {
                startGate.await();
                permits.acquire();
                try {
                    return send(client, config, requestBody, taskId);
                } finally {
                    permits.release();
                }
            }));
        }

        Instant start = Instant.now();
        startGate.countDown();
        List<Result> results = new ArrayList<>(futures.size());
        for (Future<Result> future : futures) {
            results.add(future.get());
        }
        printSummary(results, Duration.between(start, Instant.now()));
    }

    private static void runDuration(
            CloseableHttpClient client,
            Config config,
            String requestBody,
            ExecutorService executor
    ) throws Exception {
        Semaphore permits = new Semaphore(config.concurrency());
        AtomicInteger seq = new AtomicInteger();
        List<Future<Result>> futures = new ArrayList<>();
        Instant start = Instant.now();
        Instant deadline = start.plusSeconds(config.durationSeconds());
        while (Instant.now().isBefore(deadline)) {
            if (!permits.tryAcquire()) {
                Thread.sleep(1);
                continue;
            }
            int taskId = seq.incrementAndGet();
            futures.add(executor.submit(() -> {
                try {
                    return send(client, config, requestBody, taskId);
                } finally {
                    permits.release();
                }
            }));
        }
        List<Result> results = new ArrayList<>(futures.size());
        for (Future<Result> future : futures) {
            results.add(future.get());
        }
        printSummary(results, Duration.between(start, Instant.now()));
    }

    private static Result send(CloseableHttpClient client, Config config, String requestBody, int taskId) {
        long start = System.nanoTime();
        try {
            HttpPost request = new HttpPost(config.url());
            request.setHeader("Authorization", "Bearer " + config.token());
            request.setHeader("Content-Type", "application/json");
            request.setEntity(new StringEntity(requestBody, ContentType.APPLICATION_JSON));

            ClassicHttpResponse response = client.executeOpen(null, request, null);
            String responseBody;
            try {
                HttpEntity entity = response.getEntity();
                responseBody = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            } finally {
                response.close();
            }

            long elapsedMs = elapsedMs(start);
            int status = response.getCode();
            boolean ok = status >= 200 && status < 300;
            System.out.printf("task=%d status=%d elapsedMs=%d responseBytes=%d%n",
                    taskId,
                    status,
                    elapsedMs,
                    responseBody.getBytes(StandardCharsets.UTF_8).length);
            if (config.printResponse()) {
                System.out.println(responseBody);
            }
            return new Result(ok, elapsedMs);
        } catch (Exception ex) {
            long elapsedMs = elapsedMs(start);
            System.out.printf("task=%d failed elapsedMs=%d error=%s%n", taskId, elapsedMs, ex);
            return new Result(false, elapsedMs);
        }
    }

    private static ExecutorService createExecutor(Config config) {
        return switch (config.executor()) {
            case "fixed" -> Executors.newFixedThreadPool(config.concurrency());
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            default -> throw new IllegalArgumentException("unsupported executor: " + config.executor());
        };
    }

    private static String defaultBody() throws IOException {
        return OBJECT_MAPPER.writeValueAsString(Map.of(
                "query", QUERY,
                "documents", DOCS,
                "model", MODEL
        ));
    }

    private static void printSummary(List<Result> results, Duration duration) {
        List<Long> latencies = results.stream()
                .map(Result::elapsedMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        long success = results.stream().filter(Result::ok).count();
        long failed = results.size() - success;
        double seconds = duration.toNanos() / 1_000_000_000.0d;
        double rps = results.isEmpty() ? 0.0d : results.size() / seconds;
        System.out.printf(
                "summary total=%d success=%d failed=%d seconds=%.3f rps=%.3f min=%d p50=%d p90=%d p99=%d max=%d avg=%.1f%n",
                results.size(),
                success,
                failed,
                seconds,
                rps,
                percentile(latencies, 0.0d),
                percentile(latencies, 0.50d),
                percentile(latencies, 0.90d),
                percentile(latencies, 0.99d),
                percentile(latencies, 1.0d),
                average(latencies)
        );
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(values.size() * percentile) - 1;
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

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private record Result(boolean ok, long elapsedMs) {
    }

    private record Config(
            String url,
            String token,
            Path bodyFile,
            int durationSeconds,
            int requests,
            int concurrency,
            String executor,
            int timeoutSeconds,
            int maxConnections,
            int maxConnectionsPerRoute,
            boolean printResponse
    ) {
        private static Config parse(String[] args) {
            String url = DEFAULT_URL;
            String token = DEFAULT_TOKEN;
            Path bodyFile = null;
            int durationSeconds = DEFAULT_DURATION_SECONDS;
            int requests = 0;
            int concurrency = DEFAULT_CONCURRENCY;
            String executor = "virtual";
            int timeoutSeconds = 60;
            int maxConnections = DEFAULT_MAX_CONNECTIONS;
            int maxConnectionsPerRoute = DEFAULT_MAX_CONNECTIONS_PER_ROUTE;
            boolean printResponse = false;

            for (String arg : args) {
                if (arg.startsWith("--url=")) {
                    url = arg.substring("--url=".length());
                } else if (arg.startsWith("--token=")) {
                    token = arg.substring("--token=".length());
                } else if (arg.startsWith("--body-file=")) {
                    bodyFile = Path.of(arg.substring("--body-file=".length()));
                } else if (arg.startsWith("--duration=")) {
                    durationSeconds = Integer.parseInt(arg.substring("--duration=".length()));
                } else if (arg.startsWith("--requests=")) {
                    requests = Integer.parseInt(arg.substring("--requests=".length()));
                } else if (arg.startsWith("--concurrency=")) {
                    concurrency = Integer.parseInt(arg.substring("--concurrency=".length()));
                } else if (arg.startsWith("--executor=")) {
                    executor = arg.substring("--executor=".length());
                } else if (arg.startsWith("--timeout=")) {
                    timeoutSeconds = Integer.parseInt(arg.substring("--timeout=".length()));
                } else if (arg.startsWith("--max-connections=")) {
                    maxConnections = Integer.parseInt(arg.substring("--max-connections=".length()));
                } else if (arg.startsWith("--max-connections-per-route=")) {
                    maxConnectionsPerRoute = Integer.parseInt(arg.substring("--max-connections-per-route=".length()));
                } else if ("--print-response".equals(arg)) {
                    printResponse = true;
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    usage();
                    System.exit(0);
                } else {
                    throw new IllegalArgumentException("unknown argument: " + arg);
                }
            }

            if (!"virtual".equals(executor) && !"fixed".equals(executor)) {
                throw new IllegalArgumentException("--executor must be virtual or fixed");
            }
            if (durationSeconds <= 0 || concurrency <= 0 || timeoutSeconds <= 0
                    || maxConnections <= 0 || maxConnectionsPerRoute <= 0) {
                throw new IllegalArgumentException("numeric options must be positive");
            }
            return new Config(
                    url,
                    token,
                    bodyFile,
                    durationSeconds,
                    requests,
                    concurrency,
                    executor,
                    timeoutSeconds,
                    maxConnections,
                    maxConnectionsPerRoute,
                    printResponse
            );
        }

        private static void usage() {
            System.out.println("""
                    Usage:
                      java -jar target/apache-rerank-load-test-1.0.0.jar [options]

                    Options:
                      --url=<url>                         default: http://172.31.73.27/jina_like_rerank/rerank
                      --token=<token>                     default: cffex-bhckgfzs31uadgek
                      --body-file=<json>                  send this JSON body instead of built-in body
                      --duration=<seconds>                default: 1, used when --requests is absent or 0
                      --requests=<n>                      fixed request count, e.g. 54
                      --concurrency=<n>                   default: 18
                      --executor=virtual|fixed            default: virtual
                      --timeout=<seconds>                 default: 60
                      --max-connections=<n>               default: 64
                      --max-connections-per-route=<n>     default: 32
                      --print-response                    print response body
                    """);
        }
    }
}
