# SecKill Performance Test

Load tests use [JMeter](http://jmeter.apache.org/download_jmeter.cgi) against the **current** stack in `docker-compose.yml`: PostgreSQL, Redis, Kafka (KRaft), Elasticsearch, and the Spring Boot 3 services. There is no separate performance Compose file (the old MySQL + ActiveMQ overlay was removed).

Start the stack first (JDK 17 images, about 30 seconds after containers are healthy):

```bash
docker compose up -d --build
```

Point `test/performance-test/script/seckill.jmx` at the host that serves Query (`8083`) and Command (`8082`), or use the frontend proxy on `8080` (`/query`, `/command`). The checked-in script still contains an old IP; change the HTTP sampler domain before running.

Sold-out / duplicate grabs return **HTTP 429**. Set the sampler to treat 429 as an expected failure if you are measuring reject rate.

## How to Run

From `test/performance-test/script`:

```
jmeter -n -t seckill.jmx -l log.jtl
```

Generate a report from the JMeter log:

```
jmeter -g log.jtl -o <report folder>
```
