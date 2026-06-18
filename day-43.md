# Day 43 - Monitoring (Observability, Actuator, Micrometer, Prometheus)

> **Giai đoạn:** Production Engineering
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn giám sát ứng dụng Spring Boot tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **observability** và ba trụ cột: **metrics, logs, traces**.
- Nắm **Spring Boot Actuator**: `/health`, `/metrics`, `/info`, `/prometheus` và cách expose có kiểm soát.
- Dùng **Micrometer**: `Counter`/`Gauge`/`Timer`, annotation `@Timed`.
- Hiểu cơ chế **Prometheus scrape** + dashboard **Grafana**.
- Viết **custom HealthIndicator** và phân biệt **liveness vs readiness**.
- Biết **structured logging (JSON)** + **correlation/trace id**, và sơ lược **distributed tracing** (Micrometer Tracing + OpenTelemetry/Zipkin).
- Đối chiếu với Telescope, Horizon metrics, logging channels của Laravel.

---

## 🧠 Lý thuyết cốt lõi

### 1. Observability & ba trụ cột

**Monitoring** = "biết hệ thống có khoẻ không" (đo cái đã biết trước). **Observability** = "hiểu được *vì sao* hệ thống hành xử như vậy" (suy ra trạng thái trong từ tín hiệu ngoài, kể cả lỗi chưa lường trước). Ba trụ cột:

| Trụ cột | Trả lời câu hỏi | Ví dụ |
|---|---|---|
| **Metrics** | "Bao nhiêu? Nhanh/chậm? Có tăng không?" — số liệu tổng hợp theo thời gian | số request/giây, latency p95, số bid/phút, heap dùng |
| **Logs** | "Chuyện gì đã xảy ra ở thời điểm cụ thể?" — sự kiện rời rạc | "bid 150 từ alice bị từ chối: giá thấp" |
| **Traces** | "Một request đi qua những đâu, tốn thời gian ở đâu?" — luồng xuyên service | request → controller(2ms) → service(5ms) → DB(40ms) |

```
        ┌── METRICS (tổng hợp, cảnh báo)   ──► Prometheus + Grafana + Alert
Request ┼── LOGS (chi tiết sự kiện)        ──► JSON log -> ELK/Loki
        └── TRACES (luồng xuyên service)   ──► Zipkin/Jaeger/OTel
```

> 💡 Ba thứ bổ trợ nhau: metric báo "latency tăng vọt lúc 3h", trace chỉ "chậm ở DB", log nói "deadlock trên bảng auctions". Thiếu một cái là điều tra mù.

### 2. Spring Boot Actuator

Actuator phơi bày **endpoint vận hành** sẵn có cho app. Thêm `spring-boot-starter-actuator` là có ngay:

| Endpoint | Cho biết |
|---|---|
| `/actuator/health` | Trạng thái sức khoẻ (UP/DOWN) tổng hợp từ các HealthIndicator |
| `/actuator/metrics` | Danh sách metric + giá trị (`jvm.memory.used`, `http.server.requests`...) |
| `/actuator/info` | Thông tin build/version/app |
| `/actuator/prometheus` | Metric ở **định dạng Prometheus** để scrape |
| `/actuator/env`, `/loggers`, `/threaddump`, `/heapdump` | Chẩn đoán sâu (cẩn thận khi expose) |

**Expose có kiểm soát** — mặc định chỉ `/health` được phơi qua HTTP:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus   # chỉ mở những cái cần
  endpoint:
    health:
      show-details: when-authorized               # ẩn chi tiết với người lạ
```

> ⚠️ **Đừng expose tất cả (`include: "*"`) ra Internet.** `/env`, `/heapdump`, `/threaddump` lộ thông tin nhạy cảm. Giữ Actuator sau mạng nội bộ hoặc bảo vệ bằng auth, hoặc đặt ở `management.server.port` riêng.

### 3. Micrometer — "SLF4J cho metrics"

**Micrometer** là façade đo lường: bạn viết metric một lần, đẩy được tới nhiều backend (Prometheus, Datadog, New Relic...). Ba loại metric cơ bản:

| Loại | Bản chất | Dùng cho |
|---|---|---|
| **Counter** | Số **chỉ tăng** | đếm sự kiện: số bid, số lỗi, số request |
| **Gauge** | Giá trị **lên xuống** tại thời điểm | số phiên đang mở, kích thước queue, heap |
| **Timer** | Đo **thời lượng** + đếm số lần (tính p50/p95/p99) | latency của `placeBid`, thời gian query |

```java
Counter bidCounter = meterRegistry.counter("auction.bids", "result", "accepted");
bidCounter.increment();                    // mỗi bid hợp lệ +1

meterRegistry.gauge("auction.open.sessions", openSessions);   // theo dõi giá trị sống

Timer timer = meterRegistry.timer("auction.placeBid.latency");
timer.record(() -> bidService.placeBid(...));   // tự đo thời gian
```

Hoặc khai báo bằng annotation `@Timed` (cần `TimedAspect` bean):

```java
@Timed(value = "auction.placeBid.latency", percentiles = {0.5, 0.95, 0.99})
public void placeBid(...) { ... }
```

> 💡 **Tag (label)** là sức mạnh của metric hiện đại: `auction.bids{result="accepted"}` vs `{result="rejected"}` — cùng một metric, tách chiều phân tích. Đừng tạo tag có **cardinality cao** (vd `userId`) — nổ số chuỗi thời gian, sập Prometheus.

### 4. Prometheus + Grafana

```
App (/actuator/prometheus) ◄── scrape mỗi 15s ── Prometheus (lưu time-series)
                                                       │
                                                  Grafana (vẽ dashboard, query PromQL)
                                                       │
                                                  Alertmanager (cảnh báo)
```

- **Prometheus** **kéo (pull)** metric từ endpoint `/actuator/prometheus` theo chu kỳ, lưu dạng time-series, truy vấn bằng **PromQL**.
- **Grafana** vẽ dashboard từ Prometheus (đồ thị latency, throughput, error rate).
- **Alertmanager** bắn cảnh báo (Slack/email/PagerDuty) khi rule vi phạm (vd `error_rate > 1%`).

Ví dụ PromQL "số bid mỗi phút":
```promql
rate(auction_bids_total[1m]) * 60
```

### 5. Health indicator: liveness vs readiness

Spring Boot tổng hợp `/health` từ nhiều **HealthIndicator** (DB, Redis, disk, Kafka...). Hai khái niệm cho orchestrator (k8s):

- **Liveness** (`/actuator/health/liveness`): "App còn **sống** không?" DOWN → **restart** container.
- **Readiness** (`/actuator/health/readiness`): "App **sẵn sàng nhận traffic** chưa?" DOWN → **ngừng route** traffic (nhưng không restart) — vd lúc warm-up (Day 01 JIT!) hoặc DB tạm mất.

> ⚠️ Đừng để check DB vào **liveness**: DB chập chờn sẽ làm k8s restart app vô ích (app vẫn sống). DB nên thuộc **readiness**.

Viết **custom HealthIndicator**:

```java
@Component
public class RedisHealthIndicator implements HealthIndicator {
    @Override public Health health() {
        try { /* ping Redis */ return Health.up().withDetail("redis", "reachable").build(); }
        catch (Exception e) { return Health.down(e).build(); }
    }
}
```

### 6. Structured logging & correlation/trace id

Log dạng text khó máy đọc. **Structured logging (JSON)** mỗi dòng là một object → ELK/Loki query được theo field. Quan trọng nhất: gắn **trace id / correlation id** vào mọi log của cùng một request (qua **MDC** — Mapped Diagnostic Context) để **gom log theo request** dù đi qua nhiều thread/service.

```json
{"timestamp":"2026-06-18T03:00:00Z","level":"WARN","traceId":"a1b2c3","spanId":"d4e5",
 "logger":"BidService","message":"Bid 90 bị từ chối: thấp hơn 100","auctionId":1}
```

### 7. Distributed tracing (sơ lược)

Trong hệ microservice, một request đi qua nhiều service. **Distributed tracing** gán mỗi request một **traceId** chung và mỗi chặng một **spanId**, ghép thành cây thời gian. **Micrometer Tracing** (kế thừa Spring Cloud Sleuth) tự truyền traceId qua HTTP/Kafka và export sang **Zipkin/Jaeger** qua **OpenTelemetry**. Bạn nhìn được "request chậm 2s vì chặng gọi payment-service tốn 1.8s".

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel/PHP | Spring Boot |
|---|---|---|
| Soi request/query/job khi dev | **Telescope** (UI debug) | Actuator + log + tracing (không có UI built-in tương đương, dùng Grafana/Zipkin) |
| Metric queue/worker | **Horizon** (dashboard Redis queue) | Micrometer metric cho `@KafkaListener`/executor + Grafana |
| Logging | `Log::info()`, **logging channels** (`config/logging.php`), Monolog | SLF4J + Logback, log JSON, MDC |
| Health check | gói cộng đồng (`spatie/laravel-health`) | Actuator `/health` **tích hợp sẵn** + HealthIndicator |
| Metric tuỳ biến | thường tự đẩy StatsD/Prometheus exporter | Micrometer `Counter/Gauge/Timer` chuẩn hoá |
| Trace id | tự thêm middleware gắn request id | Micrometer Tracing tự truyền traceId/spanId |

**Khác biệt tư duy:**

- **Telescope/Horizon** là **UI debug tích hợp** cho dev — tiện nhưng hướng phát triển. Spring không có UI built-in tương đương; thay vào đó chuẩn hoá **metric/log/trace** rồi đẩy ra hệ quan sát chuyên dụng (**Prometheus + Grafana + Zipkin**) — mạnh hơn cho production thật, đa service.
- Java là **process chạy lâu dài** (Day 01): theo dõi **heap, GC, thread, connection pool** theo thời gian là bắt buộc — điều PHP per-request "giấu" giúp bạn. Micrometer phơi sẵn `jvm.memory.*`, `jvm.gc.*`, `hikaricp.connections.*`.

> 💡 Người Laravel quen "mở Telescope xem". Sang Java, hãy đổi sang tư duy: **đo bằng metric (Micrometer) → cảnh báo (Prometheus) → điều tra bằng log JSON + trace**.

---

## 💻 Thực hành code

### Bước 1 — Phụ thuộc & cấu hình expose

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>  <!-- bật /actuator/prometheus -->
  <scope>runtime</scope>
</dependency>
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true            # bật /health/liveness và /health/readiness
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true   # để Grafana tính p95/p99
  health:
    redis:
      enabled: true
```

### Bước 2 — Custom metric: đếm số bid (cho PromQL "bid/phút")

```java
// File: BidMetrics.java
package com.example.auction.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class BidMetrics {

    private final Counter accepted;
    private final Counter rejected;
    private final Timer placeBidTimer;
    private final AtomicInteger openSessions = new AtomicInteger(0);

    public BidMetrics(MeterRegistry registry) {
        // Counter "auction.bids" tách theo tag result -> PromQL rate() ra bid/phút
        this.accepted = registry.counter("auction.bids", "result", "accepted");
        this.rejected = registry.counter("auction.bids", "result", "rejected");
        this.placeBidTimer = registry.timer("auction.placeBid.latency");
        // Gauge theo dõi số phiên đang mở (lên xuống)
        registry.gauge("auction.open.sessions", openSessions);
    }

    public void recordAccepted() { accepted.increment(); }
    public void recordRejected() { rejected.increment(); }
    public Timer timer()         { return placeBidTimer; }
    public void sessionOpened()  { openSessions.incrementAndGet(); }
    public void sessionClosed()  { openSessions.decrementAndGet(); }
}
```

```java
// Trong BidService: gắn metric vào nghiệp vụ đặt giá
public void placeBid(Long auctionId, String bidder, long amount) {
    bidMetrics.timer().record(() -> {          // đo latency
        try {
            // ... logic Day 38 ...
            bidMetrics.recordAccepted();        // bid hợp lệ +1
        } catch (IllegalStateException e) {
            bidMetrics.recordRejected();        // bid bị từ chối +1
            throw e;
        }
    });
}
```

### Bước 3 — Custom HealthIndicator kiểm tra Redis & DB

```java
// File: RedisHealthIndicator.java
package com.example.auction.health;

import org.springframework.boot.actuate.health.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redis;
    public RedisHealthIndicator(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public Health health() {
        try {
            String pong = redis.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong)
                ? Health.up().withDetail("redis", "reachable").build()
                : Health.down().withDetail("redis", "unexpected: " + pong).build();
        } catch (Exception e) {
            return Health.down(e).withDetail("redis", "unreachable").build();
        }
    }
}
```

> 💡 DB đã có sẵn `DataSourceHealthIndicator` tự động (chạy `SELECT 1`) khi có Actuator + JDBC — không cần viết tay. Ta gắn nó vào **readiness** (không phải liveness).

### Bước 4 — Phân nhóm liveness/readiness

```yaml
# application.yml — đưa Redis/DB vào readiness (không vào liveness)
management:
  endpoint:
    health:
      group:
        readiness:
          include: readinessState, db, redis    # sẵn sàng nhận traffic?
        liveness:
          include: livenessState                  # còn sống? (không phụ thuộc DB)
```

### Bước 5 — Structured logging JSON + traceId

```xml
<!-- pom.xml: log JSON + tracing -->
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>  <!-- traceId/spanId tự bơm vào MDC -->
</dependency>
```

```yaml
# logback-spring.xml dùng LogstashEncoder -> mỗi dòng log là JSON,
# tự kèm traceId/spanId nhờ Micrometer Tracing (gom log theo request).
```

### Bước 6 — Cấu hình Prometheus scrape

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'auction-api'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['app:8080']        # tên service trong docker-compose (Day 41)
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Expose toàn bộ Actuator (`include: "*"`) ra Internet.** `/env`, `/heapdump`, `/threaddump` lộ secret/thông tin nhạy cảm. Chỉ mở cái cần, đặt sau auth/mạng nội bộ hoặc port quản trị riêng.
- **Tag metric có cardinality cao** (userId, bidId, URL có id). Mỗi giá trị tạo một time-series → nổ bộ nhớ Prometheus. Dùng tag ít giá trị (result, status, method).
- **Để DB vào liveness probe.** DB chập chờn → k8s restart app liên tục dù app vẫn sống. DB thuộc **readiness**.
- **Đếm bằng Gauge cái cần Counter (hoặc ngược lại).** Counter cho thứ chỉ tăng (dùng `rate()`); Gauge cho thứ lên xuống. Nhầm → đồ thị vô nghĩa.
- **`@Timed` không có `TimedAspect` bean.** Annotation không hoạt động nếu thiếu `TimedAspect` (cần khai báo bean). `Counter`/`Timer` thủ công thì không cần.
- **Log không có traceId.** Lỗi rải rác nhiều dòng/nhiều service không gom được. Bật Micrometer Tracing + MDC.
- **Đo latency nhưng chỉ xem trung bình.** Trung bình giấu đuôi. Luôn xem **p95/p99** (bật `percentiles-histogram`).
- **Cardinality từ tag tự động `uri`.** Nếu URL chứa id (`/auctions/123`), Spring đã template hoá (`/auctions/{id}`) — nhưng path tự ghép sai có thể nổ tag. Kiểm tra.

---

## 🚀 Liên hệ Spring Boot / Production

- **Metric JVM/GC/pool có sẵn:** Micrometer tự phơi `jvm.memory.used`, `jvm.gc.pause`, `hikaricp.connections.active`, `system.cpu.usage` — dựng dashboard "sức khoẻ JVM" ngay.
- **HTTP metric tự động:** `http.server.requests` (count, latency, theo `uri`/`status`/`method`) — tính RPS, error rate, p99 không cần viết gì.
- **Cache & Kafka metric:** bật metric cache hit/miss (Day 39) và **consumer lag** Kafka (Day 40) để cảnh báo sớm.
- **SLO/SLA & alert:** định nghĩa mục tiêu (vd p99 < 200ms, error < 0.1%) → rule Alertmanager bắn cảnh báo khi vi phạm; burn-rate alert cho error budget.
- **OpenTelemetry chuẩn hoá:** export trace/metric/log theo OTel sang collector → trung lập backend (Jaeger, Tempo, Datadog).
- **Dashboard sẵn:** import Grafana dashboard "JVM (Micrometer)" và "Spring Boot Statistics" làm điểm khởi đầu.
- **`/info` từ build:** cấu hình `build-info` (Maven plugin) để `/actuator/info` hiện version/commit — biết chính xác bản nào đang chạy khi điều tra sự cố.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Auction API giờ đã chạy trong container (Day 41) với DB/Redis/Kafka. Trước khi "lên sóng", ta phải **nhìn thấy** nó khoẻ hay yếu. Hôm nay: phơi metric **"số bid/phút"** + **latency đặt giá**, và **health check** kết nối Redis/DB cho orchestrator.

**Nhiệm vụ Day 43:**

1. Thêm Actuator + Prometheus registry; expose `health,info,metrics,prometheus`, bật `health.probes`.
2. Tạo `BidMetrics`: `Counter auction.bids{result}`, `Timer auction.placeBid.latency` (p95/p99), `Gauge auction.open.sessions`. Gắn vào `BidService.placeBid`.
3. Viết PromQL "số bid hợp lệ/phút": `rate(auction_bids_total{result="accepted"}[1m]) * 60`.
4. Viết `RedisHealthIndicator` (ping Redis); để DB + Redis vào **readiness group**, `livenessState` riêng (không phụ thuộc DB).
5. Cấu hình `prometheus.yml` scrape `app:8080/actuator/prometheus` (nối docker-compose Day 41), thêm service `prometheus` + `grafana` vào compose, import dashboard JVM.
6. (Tuỳ chọn) Bật log JSON + traceId; gây một bid lỗi và truy vết qua traceId trên log.

**Kết quả mong đợi:** Grafana hiển thị số bid/phút và latency p99 của `placeBid` theo thời gian thực; `/actuator/health/readiness` chuyển DOWN khi tắt Redis/DB (orchestrator ngừng route traffic) còn `/liveness` vẫn UP (không bị restart oan); mọi log của một request gom được theo traceId.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Ba trụ cột của observability là gì?**
> **Đáp:** **Metrics** (số liệu tổng hợp theo thời gian: RPS, latency, số bid/phút — để cảnh báo & xu hướng), **Logs** (sự kiện rời rạc chi tiết tại thời điểm cụ thể), **Traces** (luồng một request đi qua các thành phần, tốn thời gian ở đâu). Ba thứ bổ trợ nhau để hiểu *vì sao* hệ thống hành xử như vậy, không chỉ *có khoẻ không*.

**Q2: Spring Boot Actuator cung cấp gì?**
> **Đáp:** Các endpoint vận hành sẵn có: `/health` (sức khoẻ UP/DOWN), `/metrics` (metric ứng dụng/JVM), `/info` (build/version), `/prometheus` (metric định dạng Prometheus), và chẩn đoán sâu (`/env`, `/threaddump`, `/heapdump`). Phải **expose có kiểm soát**, không mở hết ra Internet.

**Q3: Counter, Gauge, Timer khác nhau thế nào?**
> **Đáp:** Counter = số **chỉ tăng** (đếm sự kiện: số bid, số lỗi; dùng `rate()` ra tốc độ). Gauge = giá trị **lên xuống** tức thời (số phiên mở, heap, kích thước queue). Timer = đo **thời lượng + số lần** (latency, tính p50/p95/p99). Chọn sai loại → đồ thị vô nghĩa.

**Q4: Liveness và readiness probe khác nhau ra sao?**
> **Đáp:** Liveness = "app còn sống không?" — DOWN thì orchestrator **restart** container. Readiness = "app sẵn sàng nhận traffic chưa?" — DOWN thì **ngừng route** traffic (không restart), dùng khi warm-up hoặc dependency (DB/Redis) tạm mất. DB nên ở readiness, không ở liveness để tránh restart oan.

### Mức Senior

**Q5: Vì sao cardinality cao của tag metric nguy hiểm?**
> **Đáp:** Mỗi tổ hợp tag tạo **một chuỗi time-series riêng** trong Prometheus. Tag cardinality cao (userId, bidId, URL có id) sinh hàng triệu series → nổ RAM/đĩa Prometheus, query chậm, có thể sập hệ giám sát. Chỉ dùng tag số giá trị nhỏ và ổn định (result, status, method); id nên đưa vào **log/trace**, không phải tag metric.

**Q6: Prometheus pull hay push, và vì sao quan trọng với app long-running?**
> **Đáp:** Prometheus **pull (scrape)** định kỳ từ `/actuator/prometheus`. App long-running (Day 01) phơi state hiện tại của JVM (heap, GC, thread, pool) liên tục; Prometheus kéo về lưu time-series → thấy **xu hướng theo thời gian** (heap rò rỉ, GC pause tăng, pool cạn) — thứ PHP per-request không bộc lộ. Pull cũng giúp Prometheus tự biết target nào **chết** (scrape fail = `up=0`).

**Q7: Distributed tracing giải quyết vấn đề gì mà log/metric không làm được?**
> **Đáp:** Trong hệ nhiều service, metric cho biết "latency tổng tăng" nhưng không chỉ **chặng nào** chậm; log rời rạc khó gom theo một request xuyên service. Tracing gán **traceId chung** cho cả request và **spanId** cho từng chặng, dựng cây thời gian → thấy chính xác request chậm vì gọi service/DB nào, bao lâu. Micrometer Tracing truyền traceId qua HTTP/Kafka, export sang Zipkin/Jaeger qua OpenTelemetry.

---

## ✅ Checklist hoàn thành

- [ ] Hiểu observability và ba trụ cột metrics/logs/traces
- [ ] Cấu hình Actuator expose health/info/metrics/prometheus an toàn
- [ ] Tạo được custom metric (Counter/Gauge/Timer) bằng Micrometer
- [ ] Hiểu Prometheus scrape + Grafana + PromQL cơ bản
- [ ] Viết custom HealthIndicator, phân biệt liveness vs readiness
- [ ] Biết structured logging JSON + traceId và sơ lược distributed tracing
- [ ] Hoàn thành Mini Project: metric "số bid/phút" + health check Redis/DB
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Boot Docs — "Production-ready Features" (Actuator endpoints, health groups, probes)
- Micrometer Docs — Concepts (Counter/Gauge/Timer), naming & tags, Prometheus registry
- Prometheus Docs — "Querying Basics" (PromQL `rate`, `histogram_quantile`), scrape config
- Grafana — dashboard "JVM (Micrometer)" / "Spring Boot 3 Statistics"
- Micrometer Tracing + OpenTelemetry/Zipkin docs (distributed tracing)
- Laravel Docs — "Telescope", "Horizon", "Logging" (đối chiếu công cụ quan sát)
