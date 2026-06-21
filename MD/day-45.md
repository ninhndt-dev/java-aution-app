# Day 45 - Final Review Project

> **Giai đoạn:** Capstone Project — Tổng kết toàn khóa
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã đi hết 44 ngày, nay ghép tất cả thành một hệ thống production-grade.

---

## 🎯 Mục tiêu ngày hôm nay

Hôm nay là ngày **CUỐI**. Không học khái niệm mới — thay vào đó ta **ghép** mọi thứ 44 ngày qua thành một sản phẩm hoàn chỉnh: **Auction API** (hệ thống đấu giá thời gian thực).

- **Nhìn lại toàn bộ lộ trình** qua bảng năng lực theo từng phase: Foundation/JVM, Collections/Generics, Concurrency, Spring Internals, JPA/Hibernate, Infrastructure (Redis/Kafka/Docker), Production (Test/Monitor/Deploy).
- **Hiểu kiến trúc tổng thể** của một service Java enterprise: luồng một request "đặt giá" đi xuyên Controller → Service → Repository → DB, kèm Redis cache, Kafka event, Actuator metrics.
- **Lắp ráp Auction API end-to-end**: REST + Security + JPA (optimistic locking `@Version`) + chống N+1 + `@Transactional` + Redis leaderboard (ZSet) + Kafka event + Docker Compose + Test (Testcontainers) + Actuator + cấu hình deploy prod.
- **Luyện một buổi phỏng vấn backend Java senior thật**: câu hỏi tổng hợp xuyên chủ đề (JVM, GC, concurrency, Spring, JPA, transaction isolation, Kafka, cache).
- **Tự đánh giá "đã hoàn thành khóa học"** và vạch lộ trình tiếp theo (microservices, observability, performance tuning/JMH, event sourcing/CQRS, Kubernetes).

> 💡 Mục tiêu thực sự của Day 45 không phải viết thêm code, mà là **kết nối các điểm**. Bạn phải nhìn thấy *vì sao* mỗi mảnh tồn tại và nó *ăn khớp* với các mảnh khác ra sao. Đó là tư duy của một kỹ sư senior.

---

## 🧠 Lý thuyết cốt lõi

### 1. Sơ đồ kiến trúc tổng thể Auction API

Đây là bức tranh toàn cảnh hệ thống ta dựng. Hãy "đọc" nó như đọc một bản đồ — mỗi hộp là một thành phần bạn đã học ở một ngày nào đó.

```
                                  ┌─────────────────────────────────────────────┐
                                  │            Auction API (1 JVM process)        │
   ┌──────────┐   HTTP/JSON       │                                               │
   │  Client  │ ───────────────►  │  ┌──────────────┐   ┌──────────────────┐      │
   │ (web/app)│   POST /bids      │  │  Spring      │   │   Service Layer   │      │
   │          │ ◄───────────────  │  │  Security    │──►│  (@Service,       │      │
   └──────────┘   200/409/401     │  │  Filter      │   │  @Transactional)  │      │
                                  │  │  Chain (JWT) │   └────────┬─────────┘      │
                                  │  └──────┬───────┘            │                │
                                  │         │                    │                │
                                  │  ┌──────▼───────┐   ┌────────▼─────────┐      │
                                  │  │ Controller   │   │  Repository      │      │
                                  │  │ (@RestCtrl)  │   │  (Spring Data    │      │
                                  │  │ Bean Valid.  │   │   JPA/Hibernate) │      │
                                  │  └──────────────┘   └───┬──────────┬───┘      │
                                  │                         │          │          │
                                  │   ┌─────────────────────┘          │          │
                                  │   │ @Cacheable / ZSet              │          │
                                  │   ▼                                ▼          │
                                  └───┼────────────────────────────────┼──────────┘
                                      │                                 │
                            ┌─────────▼────────┐            ┌───────────▼─────────┐
                            │      Redis       │            │     PostgreSQL      │
                            │  - cache @Cacheable          │  - auctions         │
                            │  - leaderboard   │            │  - bids             │
                            │    ZSet (sorted) │            │  - users            │
                            └──────────────────┘            │  @Version optimistic│
                                                            └─────────────────────┘
        Sau khi commit DB thành công:
                                      │ KafkaTemplate.send("bid-placed", event)
                                      ▼
                            ┌──────────────────┐        ┌──────────────────────┐
                            │      Kafka       │ ─────► │  Consumers (async):  │
                            │  topic:bid-placed│        │  - Notification svc  │
                            └──────────────────┘        │  - Analytics svc     │
                                                        └──────────────────────┘

   Quan sát/vận hành:  Actuator  /actuator/health  /actuator/metrics  /actuator/prometheus
                       (Micrometer → Prometheus → Grafana)
```

**Luồng một request "đặt giá" (POST /api/v1/auctions/{id}/bids) đi xuyên hệ thống:**

```
1. Client gửi POST kèm JWT trong header Authorization: Bearer <token>.
2. Spring Security Filter Chain: xác thực token → dựng Authentication → kiểm tra quyền (ROLE_USER).
3. DispatcherServlet định tuyến tới BidController.placeBid(...).
4. Bean Validation kiểm tra body (@Valid): amount > 0, không null...
5. Controller gọi BidService.placeBid(auctionId, userId, amount).
6. @Transactional mở transaction. Service:
      a. Đọc Auction (kèm @Version) bằng fetch join (tránh N+1).
      b. Kiểm tra nghiệp vụ: phiên còn mở? amount > giá hiện tại + bước giá?
      c. Tạo Bid, cập nhật currentPrice của Auction.
      d. save() → Hibernate sinh UPDATE ... WHERE id=? AND version=?.
7. Commit:
      - Nếu version khớp → commit thành công.
      - Nếu version đã thay đổi (người khác đặt giá trước) → OptimisticLockException
        → retry (đọc lại, tính lại) hoặc trả 409 Conflict.
8. Sau commit (AFTER_COMMIT): cập nhật Redis ZSet leaderboard + publish Kafka "bid-placed".
9. Controller trả 200 (BidResponse) hoặc 409 (đặt giá thất bại do tranh chấp).
10. Consumer Kafka (riêng biệt) nhận event → gửi notification, cập nhật analytics.
11. Micrometer ghi metric: counter bids_total, timer bid_latency... → Actuator expose ra Prometheus.
```

> 💡 Hãy để ý điểm tinh tế: **phát Kafka và cập nhật Redis phải xảy ra SAU khi DB commit** (dùng `TransactionSynchronization` / `@TransactionalEventListener(phase = AFTER_COMMIT)`). Nếu phát event *trước* commit rồi transaction rollback, consumer sẽ xử lý một sự kiện "ma" chưa từng tồn tại trong DB — bug rất khó truy.

### 2. Bản đồ năng lực toàn khóa — map về từng phase

Bảng này là "tấm gương" soi lại 45 ngày. Mỗi dòng là một năng lực mà Auction API sử dụng trực tiếp.

| Phase | Năng lực cốt lõi đã học | Auction API dùng ở đâu | Day tham chiếu |
|---|---|---|---|
| **1. Foundation & JVM** | JDK/JRE/JVM, bytecode, JIT, class loading, vùng nhớ Heap/Stack/Metaspace, GC (G1/ZGC), tham trị/tham chiếu, `equals`/`hashCode`, immutability, `record`, exception checked/unchecked | Chọn base image JRE 21, tune `-Xmx`, `record` cho DTO, GC khi giữ object lâu dài | Day 01–10 |
| **2. Collections & Generics** | `List/Set/Map`, `HashMap` internals, `ConcurrentHashMap`, Generics, wildcard `? extends/super`, Stream API, Optional, Comparator | Map cache cục bộ, Stream lọc/biến đổi bid, `Optional` trả về từ repo | Day 11–17 |
| **3. Concurrency** | Thread, `ExecutorService`, `CompletableFuture`, `synchronized`/`Lock`, `volatile`, atomic, lost update, deadlock, thread pool sizing, virtual threads (Java 21) | Xử lý nhiều người đặt giá đồng thời, optimistic locking, virtual threads cho I/O | Day 18–24 |
| **4. Spring Internals** | IoC/DI, bean lifecycle, scope, AOP/proxy, `@Transactional` (proxy + propagation), auto-configuration, Spring MVC, Bean Validation, exception handling `@ControllerAdvice`, Spring Security (filter chain, JWT) | Toàn bộ tầng web + DI service + bảo mật đặt giá | Day 25–33 |
| **5. JPA & Hibernate** | Entity mapping, quan hệ `@OneToMany`/`@ManyToOne`, lazy/eager, **N+1** + fetch join/`@EntityGraph`, persistence context, dirty checking, **optimistic locking `@Version`**, pessimistic lock, isolation level, flush/commit | Auction/Bid/User entity, chống N+1, `@Version` chống lost update | Day 34–39 |
| **6. Infrastructure** | Redis (cache-aside, `@Cacheable`, TTL, **ZSet leaderboard**), Kafka (producer/consumer, partition, offset, at-least-once), Docker & Compose, networking giữa container | Cache top auctions, leaderboard realtime, event-driven, compose toàn stack | Day 40–43 |
| **7. Production** | Testing (JUnit 5, Mockito, `@SpringBootTest`, **Testcontainers**), Actuator/Micrometer/Prometheus, logging cấu trúc, profile `dev/prod`, graceful shutdown, health/readiness probe, performance tuning | Integration test với Postgres+Kafka thật, metrics, deploy config | Day 44 + hôm nay |

> ⚠️ Nếu có dòng nào bạn không tự tin giải thích trong 1 phút, hãy **quay lại đúng Day đó** trước khi coi là đã hoàn thành khóa. Một hệ thống chỉ vững khi không có "mắt xích yếu".

### 3. Vì sao kiến trúc phân tầng (layered) quan trọng

Auction API tuân theo phân tầng kinh điển. Mỗi tầng có **một trách nhiệm**:

```
Controller   → nói chuyện HTTP: nhận request, validate, map DTO, trả status code.
               KHÔNG chứa logic nghiệp vụ. KHÔNG đụng entity trực tiếp ra ngoài.
Service      → chứa logic nghiệp vụ + ranh giới transaction (@Transactional).
               Đây là "bộ não". Điều phối repository, cache, event.
Repository   → chỉ truy cập dữ liệu (Spring Data JPA). Không có logic nghiệp vụ.
Domain/Entity→ mô hình dữ liệu + bất biến nghiệp vụ (vd: giá mới phải > giá cũ).
```

Lợi ích: **dễ test** (mock repository khi test service), **dễ thay đổi** (đổi DB không ảnh hưởng controller), **ranh giới transaction rõ ràng** (đặt đúng ở service, không lan ra controller).

> 💡 Quy tắc vàng: **transaction thuộc về tầng Service, không thuộc Controller hay Repository.** `@Transactional` đặt ở service method là nơi gói một "đơn vị nghiệp vụ" trọn vẹn.

### 4. Optimistic locking — trái tim của hệ thống đấu giá

Đấu giá là bài toán **lost update** kinh điển: hai người cùng thấy giá 100, cùng đặt 110, một người ghi đè người kia. `@Version` giải quyết bằng cách thêm cột version vào row:

```
Người A đọc auction: price=100, version=5
Người B đọc auction: price=100, version=5
Người A đặt 110 → UPDATE SET price=110, version=6 WHERE id=1 AND version=5 → OK (1 row)
Người B đặt 110 → UPDATE SET price=110, version=6 WHERE id=1 AND version=5 → 0 row!
   → Hibernate phát hiện 0 row updated → ném OptimisticLockException
   → ta retry: đọc lại (price=110, version=6), thấy 110 không > 110 → từ chối, trả 409
```

So sánh hai chiến lược khóa:

| | Optimistic (`@Version`) | Pessimistic (`SELECT ... FOR UPDATE`) |
|---|---|---|
| Cơ chế | Kiểm tra version lúc commit | Khóa row ngay lúc đọc |
| Phù hợp khi | Ít tranh chấp, đọc nhiều | Tranh chấp cao, ghi nhiều |
| Hiệu năng | Tốt (không giữ lock) | Có thể nghẽn (giữ lock, chờ) |
| Rủi ro | Phải xử lý retry/conflict | Deadlock nếu khóa nhiều row sai thứ tự |

> ⚠️ Với đấu giá lúc cao điểm (nhiều người tranh một món), optimistic có thể conflict liên tục. Lúc đó cân nhắc pessimistic lock cho riêng món "hot", hoặc đẩy việc xếp giá qua một hàng đợi (Redis ZSet / Kafka) để tuần tự hóa.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là so sánh **tổng thể** cách bạn dựng một app Laravel quen thuộc với cách dựng app Spring Boot enterprise.

| Khía cạnh | Laravel / PHP | Spring Boot (Java) |
|---|---|---|
| **Vòng đời ứng dụng** | Mỗi request bootstrap lại từ đầu, "sinh ra rồi chết" (stateless) | Process chạy liên tục hàng ngày/tháng, bean Singleton sống suốt vòng đời |
| **Định tuyến** | `routes/web.php`, `routes/api.php` | `@RestController` + `@GetMapping/@PostMapping` |
| **DI Container** | Service Container, `app()->bind()` | Spring IoC Container, `@Component/@Service/@Autowired` |
| **ORM** | Eloquent (Active Record): `$user->save()` | JPA/Hibernate (Data Mapper): `repository.save(user)`, có persistence context + dirty checking |
| **N+1** | Eager loading `with('relations')` | Fetch join JPQL / `@EntityGraph` |
| **Transaction** | `DB::transaction(fn() => ...)` | `@Transactional` (proxy AOP), có propagation/isolation |
| **Validation** | Form Request `rules()` | Bean Validation `@NotNull/@Min`, `@Valid` |
| **Middleware/Auth** | Middleware, Guard, Sanctum/Passport | Spring Security Filter Chain, JWT |
| **Queue/Event** | Laravel Queue (Redis/SQS), Events & Listeners | Kafka / `@KafkaListener`, Spring Events |
| **Cache** | `Cache::remember()` | `@Cacheable` + Redis, ZSet cho leaderboard |
| **Concurrency** | Hầu như không lo (mỗi request 1 process php-fpm) | **Phải lo**: thread pool, lock, lost update, atomic |
| **Deploy** | Upload code lên server, php-fpm + Nginx | Đóng **fat JAR** → `java -jar` hoặc Docker image |
| **Config theo môi trường** | `.env` + `config/*.php` | `application-{profile}.yml`, `--spring.profiles.active=prod` |
| **Quan sát** | Telescope, Horizon, log file | Actuator + Micrometer + Prometheus + Grafana |
| **Testing** | PHPUnit, `RefreshDatabase` | JUnit 5 + Mockito + `@SpringBootTest` + **Testcontainers** |

**Khác biệt tư duy lớn nhất khi dựng cả hệ thống:**

- **Laravel:** Bạn "viết route → controller → Eloquent → trả về". Framework giấu phần lớn concurrency, memory, lifecycle. Mỗi request độc lập, ít rủi ro state lẫn nhau.
- **Spring Boot:** Bạn "dựng một process sống lâu" — phải thiết kế cho **đồng thời** (nhiều thread chia sẻ bean), **bộ nhớ** (GC, connection pool, cache có TTL), **vòng đời** (graceful shutdown, drain Kafka consumer). Đổi lại bạn có **hiệu năng cao hơn nhiều** (giữ state trong RAM, không bootstrap lại mỗi request) và một hệ sinh thái enterprise đầy đủ (Security, JPA, Actuator chuẩn hóa).

> 🧩 Từ Laravel sang Spring không phải "học lại từ đầu" — mọi khái niệm đều có cặp song chiếu (DI ↔ Service Container, `@Transactional` ↔ `DB::transaction`, `@Cacheable` ↔ `Cache::remember`). Cái mới chủ yếu là **tư duy về tiến trình sống lâu và đồng thời**.

---

## 💻 Thực hành code

Dưới đây là các mảnh code chủ chốt ghép thành Auction API. Tất cả dùng **Spring Boot 3 / Java 21**.

### 1. Entity với optimistic locking (`@Version`)

```java
// File: domain/Auction.java
package com.auction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "auctions")
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Dùng BigDecimal cho tiền — KHÔNG dùng double (sai số dấu phẩy động)
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal minIncrement;  // bước giá tối thiểu

    // ⭐ TRÁI TIM chống lost update: optimistic lock.
    // Hibernate tự thêm "AND version=?" vào câu UPDATE và tăng version.
    @___ // Điền annotation cho cột lưu phiên bản cập nhật
    private Long version;

    // LAZY mặc định cho collection — tránh load toàn bộ bid mỗi lần đọc auction
    @OneToMany(mappedBy = "auction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Bid> bids = new ArrayList<>();

    public enum Status { OPEN, CLOSED }
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    /** Bất biến nghiệp vụ: chỉ chấp nhận giá hợp lệ. */
    public void placeBid(Bid bid) {
        if (status != Status.OPEN) {
            throw new AuctionClosedException("Phiên đã đóng");
        }
        BigDecimal minRequired = currentPrice.add(minIncrement);
        if (bid.getAmount().compareTo(minRequired) < 0) {
            throw new InvalidBidException(
                "Giá phải >= " + minRequired);
        }
        this.currentPrice = bid.getAmount();
        bid.setAuction(this);
        this.bids.add(bid);
    }
}
```

### 2. Repository chống N+1 (fetch join + `@EntityGraph`)

```java
// File: repository/AuctionRepository.java
package com.auction.repository;

import com.auction.domain.Auction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // Fetch join: nạp auction + bids trong MỘT câu query → tránh N+1
    @Query("""
        SELECT DISTINCT a FROM Auction a
        LEFT ___ FETCH a.bids # Điền từ khóa kết nối bảng (FETCH đã có sẵn)
        WHERE a.id = :id
        """)
    Optional<Auction> findByIdWithBids(@Param("id") Long id);

    // EntityGraph: cách khai báo nạp kèm quan hệ mà không viết JPQL fetch join
    @___(attributePaths = {"bids"}) // Điền Annotation cho phép load Eager các thuộc tính được liệt kê
    @Query("SELECT a FROM Auction a WHERE a.status = com.auction.domain.Auction$Status.OPEN")
    List<Auction> findAllOpenWithBids();
}
```

### 3. Service với `@Transactional` + retry optimistic lock

```java
// File: service/BidService.java
package com.auction.service;

import com.auction.domain.*;
import com.auction.repository.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
public class BidService {

    // ... (Các bước Inject Bean và constructor) ...
    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final BidEventPublisher eventPublisher;

    public BidService(AuctionRepository auctionRepo, UserRepository userRepo, BidEventPublisher eventPublisher) {
        this.auctionRepo = auctionRepo;
        this.userRepo = userRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Đặt giá. Tự retry tối đa 3 lần khi gặp tranh chấp optimistic lock.
     * Vòng retry nằm NGOÀI @Transactional để mỗi lần thử là 1 transaction mới.
     */
    public BidResult placeBid(Long auctionId, Long userId, BigDecimal amount) {
        int maxRetry = 3;
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                return doPlaceBid(auctionId, userId, amount);
            } catch (ObjectOptimisticLockingFailureException ex) {
                // Có người đặt giá xen vào giữa → thử lại với dữ liệu mới nhất
                if (attempt == maxRetry) {
                    throw new BidConflictException("Có người vừa đặt giá cao hơn, vui lòng thử lại");
                }
                try { Thread.sleep(10L * attempt); } catch (InterruptedException ie) {}
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @___ // Điền annotation mở transaction cho logic database
    protected BidResult doPlaceBid(Long auctionId, Long userId, BigDecimal amount) {
        // Đọc auction kèm bids bằng fetch join (1 query, tránh N+1)
        Auction auction = auctionRepo.findByIdWithBids(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        User bidder = userRepo.getReferenceById(userId);

        Bid bid = new Bid();
        bid.setBidder(bidder);
        bid.setAmount(amount);
        bid.setPlacedAt(Instant.now());

        auction.placeBid(bid); // Gọi logic trong Entity
        auctionRepo.save(auction);

        // Đăng ký gửi Kafka event SAU khi commit thành công
        eventPublisher.registerAfterCommit(new BidPlacedEvent(auctionId, userId, amount, Instant.now()));

        return new BidResult(auctionId, amount, "ACCEPTED");
    }
}
```

### 4. Phát Kafka event SAU khi commit

```java
// File: event/BidEventPublisher.java
package com.auction.event;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class BidEventPublisher {

    private final KafkaTemplate<String, BidPlacedEvent> kafkaTemplate;

    public BidEventPublisher(KafkaTemplate<String, BidPlacedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Chỉ phát Kafka SAU khi transaction DB commit thành công.
     * Nếu rollback → event KHÔNG bao giờ được gửi (tránh "event ma").
     */
    public void registerAfterCommit(BidPlacedEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void ___() { // Điền override method chạy sau khi commit thành công
                        // key = auctionId → các bid cùng phiên vào cùng partition (giữ thứ tự)
                        kafkaTemplate.send("bid-placed", String.valueOf(event.auctionId()), event);
                    }
                });
        }
    }
}
```

### 5. Controller REST + Spring Security

```java
// File: config/SecurityConfig.java
package com.auction.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)  // REST stateless dùng JWT, không cần CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.___)) // Điền policy cho REST API (không lưu session)
            .authorizeHttpRequests(auth -> auth
                // Ai cũng xem được auction
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auctions/**").permitAll()
                // Đặt giá: cần đăng nhập (ROLE_USER)
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auctions/*/bids").___("USER") // Điền method để check Role
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
```

### 6. Cấu hình Production

```yaml
# File: src/main/resources/application-prod.yml
spring:
  application:
    name: auction-api
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/auction
    username: ${DB_USER:auction}
    password: ${DB_PASS:secret}
  jpa:
    hibernate:
      ddl-auto: validate           # PROD: KHÔNG dùng create/update
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Self-invocation phá `@Transactional`.** Gọi method `@Transactional` từ chính class đó (qua `this`) sẽ bỏ qua proxy → không có transaction, không có retry. Khắc phục: tách method transactional sang **bean khác**, hoặc inject self-proxy, hoặc dùng `TransactionTemplate`.
- **Phát Kafka/cập nhật Redis TRƯỚC khi commit.** Nếu transaction rollback, consumer xử lý "event ma". Luôn dùng `AFTER_COMMIT` (`TransactionSynchronization` hoặc `@TransactionalEventListener`).
- **Quên xử lý `OptimisticLockException`.** Không retry → mọi tranh chấp đều thành lỗi 500. Phải bắt và retry hoặc trả 409 Conflict rõ ràng.
- **N+1 query âm thầm.** Đọc danh sách auction rồi lặp `auction.getBids()` (LAZY) → mỗi vòng lặp một query. Dùng fetch join/`@EntityGraph`. Bật `spring.jpa.show-sql` + log để soi số query.
- **Dùng `double` cho tiền.** Sai số dấu phẩy động → lệch tiền. Luôn dùng `BigDecimal`.
- **`ddl-auto: update` trên production.** Có thể làm hỏng/mất schema. PROD dùng `validate` + migration tool (Flyway/Liquibase).
- **Thread pool sai kích thước.** Pool quá lớn → tốn RAM, context switch; quá nhỏ → nghẽn. Với I/O-bound, cân nhắc **virtual threads** (Java 21).
- **Consumer Kafka không idempotent.** At-least-once nghĩa là event có thể đến **2 lần**. Phải khử trùng lặp (dedup theo eventId/offset) trước khi gây tác dụng phụ.
- **Cache không có TTL hoặc không invalidate.** Dữ liệu cũ (stale) phục vụ mãi. Đặt TTL hợp lý và `@CacheEvict` khi auction đổi giá.
- **Không cấu hình graceful shutdown.** Deploy/restart cắt ngang request đang chạy → mất dữ liệu, lỗi 502. Bật `server.shutdown: graceful`.

---

## 🚀 Liên hệ Spring Boot / Production

- **Health & readiness probe:** `/actuator/health/liveness` và `/actuator/health/readiness` cho Kubernetes biết khi nào pod sẵn sàng nhận traffic (sau khi DB/Kafka đã kết nối). Tránh route request vào pod chưa "ấm".
- **Metrics & alerting:** Micrometer expose ra `/actuator/prometheus`. Counter `bids_total`, timer `bid_latency`, gauge connection pool. Cảnh báo khi p99 latency hoặc tỷ lệ 409 tăng đột biến (dấu hiệu tranh chấp cao).
- **JVM tuning:** trong container đặt `-XX:MaxRAMPercentage=75` thay vì `-Xmx` cố định, để JVM tự co giãn theo memory limit của pod. Chọn GC theo tải (G1 mặc định, ZGC nếu cần latency thấp ổn định).
- **Connection pool (HikariCP):** kích thước pool ≈ số kết nối DB chịu được, không phải "càng nhiều càng tốt". Pool cạn → request xếp hàng. Theo dõi qua Actuator.
- **Backpressure cho Kafka:** consumer xử lý chậm → lag tăng. Theo dõi consumer lag; scale consumer theo số partition.
- **Idempotency key cho đặt giá:** client gửi `Idempotency-Key` để retry mạng không tạo bid trùng.
- **Rollout an toàn:** vì JVM cần warm-up (JIT, cache), dùng rolling update + readiness probe + có thể "pre-warm" để tránh tụt hiệu năng ngay sau deploy.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Đây là **capstone** — sản phẩm tổng kết. Mục tiêu: lắp ráp Auction API **end-to-end** chạy được thật bằng `docker compose up`.

**Nhiệm vụ Day 45:**

1. Điền các chỗ trống trong code Auction API ở trên để luyện phản xạ code Spring Boot (entity `@Version`, repository `JOIN FETCH`, `@EntityGraph`, `@Transactional`, Kafka `afterCommit`, config `SessionCreationPolicy`).
2. Tự build một source code hoàn chỉnh dựa trên các snippet trên.
3. Chạy `docker-compose up` để dựng toàn bộ stack (Auction API, Postgres, Redis, Kafka).
4. Dùng Postman để đặt nhiều `Bid` đồng thời, kiểm tra Optimistic Lock có ném Conflict không.
5. Kiểm tra log của Notification Consumer để xem Event đã phát ra sau khi Commit thành công chưa.

**Kết quả mong đợi:** 1 project chạy thực tế được, hiểu toàn bộ thiết kế hệ thống và cấu trúc code bên dưới. Bạn đã có đủ kiến thức nền tảng để làm dự án thật.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

Bộ câu hỏi này mô phỏng **một buổi phỏng vấn backend Java senior thật** — tổng hợp xuyên chủ đề.

### Mức Junior/Mid

**Q1: Phân biệt JDK, JRE, JVM và vì sao Java "chạy mọi nơi"?**
> **Đáp:** JVM thực thi bytecode; JRE = JVM + thư viện chuẩn (đủ để chạy); JDK = JRE + công cụ phát triển (`javac`...). "Run anywhere" nhờ lớp trung gian bytecode + JVM: `javac` ra bytecode độc lập nền tảng, mỗi OS có JVM riêng dịch bytecode đó sang mã máy.

**Q2: N+1 query là gì và xử lý ra sao trong JPA?**
> **Đáp:** Khi nạp N entity cha rồi với mỗi cha lại phát một query nạp con (LAZY) → 1 + N query. Xử lý: fetch join (`JOIN FETCH` trong JPQL), `@EntityGraph`, hoặc batch fetching. Phát hiện bằng cách bật log SQL và đếm query.

**Q3: `@Transactional` hoạt động thế nào trong Spring?**
> **Đáp:** Spring tạo **proxy AOP** quanh bean; proxy mở transaction trước method và commit/rollback sau. Vì qua proxy nên self-invocation (gọi `this.method()`) bị bỏ qua. Có propagation (REQUIRED, REQUIRES_NEW...) và isolation level. Mặc định rollback với unchecked exception.

**Q4: Optimistic locking là gì, vì sao hợp với đấu giá?**
> **Đáp:** Thêm cột `@Version`; lúc UPDATE thêm `WHERE version=?` và tăng version. Nếu 0 row updated → có người sửa trước → `OptimisticLockException`. Hợp khi đọc nhiều ghi ít vì không giữ khóa; với conflict thì retry. Chống lost update mà không nghẽn như pessimistic lock.

**Q5: Khác nhau giữa cache-aside và write-through? Bạn dùng Redis ZSet để làm gì?**
> **Đáp:** Cache-aside (`@Cacheable`): app đọc cache trước, miss thì đọc DB rồi ghi cache. Write-through: ghi cache và DB cùng lúc. ZSet (Sorted Set) lưu phần tử kèm score, tự sắp xếp — lý tưởng cho **leaderboard**: `ZADD` thêm điểm, `ZREVRANGE` lấy top N với độ phức tạp O(log N), realtime.

### Mức Senior

**Q6: Mô tả luồng một request "đặt giá" đi xuyên toàn hệ thống, chỉ ra điểm dễ sai về tính nhất quán.**
> **Đáp:** Security filter (JWT) → Controller (validate) → Service (`@Transactional`: đọc auction fetch join, kiểm tra giá, save với `@Version`) → commit. **Điểm dễ sai:** phát Kafka/cập nhật Redis phải nằm **sau commit** (`AFTER_COMMIT`), nếu không rollback sẽ tạo "event ma" và cache lệch DB. Ngoài ra conflict optimistic phải retry/trả 409, không để thành 500.

**Q7: GC ảnh hưởng gì tới một service đấu giá realtime? Chọn GC và tune thế nào?**
> **Đáp:** GC pause gây latency spike → đặt giá trễ, hỏng trải nghiệm realtime. G1 (mặc định) cân bằng throughput/latency; nếu cần latency thấp, ổn định, dùng **ZGC** (pause sub-millisecond). Tune: trong container dùng `-XX:MaxRAMPercentage`, theo dõi GC log, giảm allocation rate (tái dùng object, tránh tạo rác trong hot path), kích thước heap đủ để giảm tần suất GC.

**Q8: Hai người đặt cùng mức giá gần như đồng thời. Hệ thống đảm bảo đúng đắn ra sao? Optimistic conflict liên tục thì sao?**
> **Đáp:** `@Version` đảm bảo chỉ một UPDATE thắng (1 row), người kia 0 row → exception → retry: đọc lại thấy giá đã bằng nhau nên từ chối (trả 409). Nếu conflict liên tục lúc cao điểm: chuyển sang **pessimistic lock** cho món hot, hoặc **tuần tự hóa** việc xếp giá qua một queue (Kafka theo key=auctionId vào cùng partition, hoặc Redis), giảm tranh chấp ở DB.

**Q9: Transaction isolation level nào phù hợp? Phantom read, lost update khác nhau ra sao?**
> **Đáp:** Lost update: hai transaction cùng đọc-ghi đè nhau — giải bằng optimistic/pessimistic lock (không phải tăng isolation đơn thuần). Phantom read: cùng query nhưng số dòng đổi do transaction khác insert — chỉ SERIALIZABLE/REPEATABLE READ (tùy DB) ngăn được. Với đấu giá thường dùng READ COMMITTED + optimistic lock cho cập nhật giá, đủ đúng đắn mà không nghẽn như SERIALIZABLE.

**Q10: Kafka đảm bảo gì về thứ tự và "exactly-once"? Áp dụng cho event bid thế nào?**
> **Đáp:** Kafka đảm bảo thứ tự **trong một partition**. Để các bid cùng phiên giữ thứ tự, dùng `key=auctionId` → cùng partition. Mặc định là **at-least-once** (event có thể lặp), nên consumer phải **idempotent** (dedup theo eventId/offset). "Exactly-once" cần transactional producer + read-committed consumer (phức tạp); đa số dùng at-least-once + idempotent.

**Q11: Virtual threads (Java 21) thay đổi gì cho service web I/O-bound như đặt giá?**
> **Đáp:** Virtual threads rẻ (hàng triệu thread), cho phép mô hình "một request một thread" mà không tốn OS thread, rất hợp I/O-bound (chờ DB, Redis, Kafka). Giảm nhu cầu lập trình bất đồng bộ phức tạp. Lưu ý tránh `synchronized` quanh I/O dài (pinning carrier thread) — ưu tiên `ReentrantLock`.

**Q12: Service vừa deploy đang xử lý đặt giá thì bị restart. Làm sao không mất bid và không trả lỗi cho user?**
> **Đáp:** Bật **graceful shutdown** (`server.shutdown: graceful`): ngừng nhận request mới, xử lý nốt request đang chạy trong timeout. Kết hợp readiness probe (rút pod khỏi load balancer trước khi tắt), drain Kafka consumer (commit offset đã xử lý), rolling update để luôn còn pod phục vụ. Idempotency key giúp client retry an toàn nếu vẫn lỡ bị cắt.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ lại được sơ đồ kiến trúc tổng thể và mô tả luồng request "đặt giá"
- [ ] Điền đúng toàn bộ các annotation ở code trên (`@Version`, `FETCH`, `@EntityGraph`, `@Transactional`)
- [ ] Xử lý đúng TransactionSynchronization của Kafka
- [ ] Auction API chạy được end-to-end bằng `docker-compose up`
- [ ] Optimistic locking `@Version` + retry hoạt động đúng (test concurrency pass)
- [ ] Trả lời được toàn bộ 12 câu phỏng vấn tổng hợp ở trên
- [ ] Vạch được lộ trình tiếp theo (microservices, observability, JMH, CQRS, Kubernetes)
- [ ] Tạo git commit/tag "course-complete" cho dự án capstone 🎉

---

## 📚 Tài liệu tham khảo

- Spring Boot Reference — Building a RESTful Web Service, Data Access, Caching, Actuator
- Spring Data JPA Reference — Fetching strategies, `@EntityGraph`, Locking
- Vlad Mihalcea — *High-Performance Java Persistence* (N+1, optimistic/pessimistic lock, isolation)
- Spring Security Reference — Architecture, Filter Chain, JWT Resource Server
- Confluent / Apache Kafka Docs — Producer/Consumer semantics, ordering, idempotency
- Redis Docs — Sorted Sets (ZSet), TTL, caching patterns
- Testcontainers Docs — JUnit 5 integration, PostgreSQL/Kafka modules
- *Java Performance* (Scott Oaks) — GC, JIT, tuning
- JEP 444 — Virtual Threads (Java 21)
- Micrometer & Prometheus Docs — metrics, dashboards (Grafana)
- 🎓 **Chúc mừng bạn đã hoàn thành "Java Deep Dive 45 ngày"!** Hãy quay lại bất kỳ Day nào còn lấn cấn và tiếp tục đào sâu trên chính dự án Auction API này.
