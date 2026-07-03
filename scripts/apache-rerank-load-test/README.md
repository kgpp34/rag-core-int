# Apache Rerank Load Test

Standalone Apache HttpClient 5 load test for the rerank endpoint. It uses:

- `PoolingHttpClientConnectionManager`
- virtual threads by default
- fixed request mode via `--requests`
- duration mode via `--duration`

Build a fat jar:

```bash
cd scripts/apache-rerank-load-test
mvn -DskipTests package
```

Run on host:

```bash
java -jar target/apache-rerank-load-test-1.0.0.jar \
  --requests=54 \
  --concurrency=54 \
  --max-connections=200 \
  --max-connections-per-route=100
```

Copy into an existing app container:

```bash
docker cp target/apache-rerank-load-test-1.0.0.jar <container>:/tmp/
docker exec <container> java -jar /tmp/apache-rerank-load-test-1.0.0.jar \
  --requests=54 \
  --concurrency=54 \
  --max-connections=200 \
  --max-connections-per-route=100
```

Compare virtual threads with fixed platform threads:

```bash
java -jar target/apache-rerank-load-test-1.0.0.jar --requests=54 --concurrency=54 --executor=virtual
java -jar target/apache-rerank-load-test-1.0.0.jar --requests=54 --concurrency=54 --executor=fixed
```

Send an exact JSON body:

```bash
java -jar target/apache-rerank-load-test-1.0.0.jar \
  --body-file=/tmp/rerank-request.json \
  --requests=54 \
  --concurrency=54
```
