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
// File: domain/User.java
package com.auction.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String passwordHash;  // đã hash bằng BCrypt

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;  // USER, ADMIN

    // getters/setters/constructors lược bớt cho gọn
    public enum Role { USER, ADMIN }
}
```

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

    @Column(nullable = false)
    private String title;

    // Dùng BigDecimal cho tiền — KHÔNG dùng double (sai số dấu phẩy động)
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal minIncrement;  // bước giá tối thiểu

    @Column(nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;  // OPEN, CLOSED

    // ⭐ TRÁI TIM chống lost update: optimistic lock.
    // Hibernate tự thêm "AND version=?" vào câu UPDATE và tăng version.
    @Version
    private Long version;

    // LAZY mặc định cho collection — tránh load toàn bộ bid mỗi lần đọc auction
    @OneToMany(mappedBy = "auction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Bid> bids = new ArrayList<>();

    public enum Status { OPEN, CLOSED }

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

    // getters/setters lược bớt
}
```

```java
// File: domain/Bid.java
package com.auction.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bids", indexes = @Index(name = "idx_bid_auction", columnList = "auction_id"))
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id")
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bidder_id")
    private User bidder;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant placedAt;

    // getters/setters lược bớt
    public BigDecimal getAmount() { return amount; }
    public void setAuction(Auction a) { this.auction = a; }
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
        LEFT JOIN FETCH a.bids
        WHERE a.id = :id
        """)
    Optional<Auction> findByIdWithBids(@Param("id") Long id);

    // EntityGraph: cách khai báo nạp kèm quan hệ mà không viết JPQL fetch join
    @EntityGraph(attributePaths = {"bids"})
    @Query("SELECT a FROM Auction a WHERE a.status = com.auction.domain.Auction$Status.OPEN")
    List<Auction> findAllOpenWithBids();

    // Pessimistic lock cho món "hot" tranh chấp cao (tùy chọn)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);
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

    private final AuctionRepository auctionRepo;
    private final UserRepository userRepo;
    private final BidLeaderboardService leaderboard;   // Redis ZSet
    private final BidEventPublisher eventPublisher;    // Kafka (gọi sau commit)

    public BidService(AuctionRepository auctionRepo, UserRepository userRepo,
                      BidLeaderboardService leaderboard, BidEventPublisher eventPublisher) {
        this.auctionRepo = auctionRepo;
        this.userRepo = userRepo;
        this.leaderboard = leaderboard;
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
                    throw new BidConflictException(
                        "Có người vừa đặt giá cao hơn, vui lòng thử lại");
                }
                // backoff nhẹ để giảm va chạm liên tiếp
                try { Thread.sleep(10L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BidConflictException("Bị ngắt khi retry");
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Transactional
    protected BidResult doPlaceBid(Long auctionId, Long userId, BigDecimal amount) {
        // Đọc auction kèm bids bằng fetch join (1 query, tránh N+1)
        Auction auction = auctionRepo.findByIdWithBids(auctionId)
            .orElseThrow(() -> new AuctionNotFoundException(auctionId));
        User bidder = userRepo.getReferenceById(userId);

        Bid bid = new Bid();
        bid.setBidder(bidder);
        bid.setAmount(amount);
        bid.setPlacedAt(Instant.now());

        // Bất biến nghiệp vụ + cập nhật currentPrice nằm trong entity
        auction.placeBid(bid);

        // save() → Hibernate sinh: UPDATE auctions SET ... version=? WHERE id=? AND version=?
        // Nếu version không khớp → ObjectOptimisticLockingFailureException khi flush/commit
        auctionRepo.save(auction);

        // ⚠️ KHÔNG publish Kafka / update Redis ở đây — phải đợi AFTER_COMMIT.
        // Ta đăng ký một event miền (domain event) để xử lý sau commit (xem mục 5).
        eventPublisher.registerAfterCommit(
            new BidPlacedEvent(auctionId, userId, amount, Instant.now()));

        return new BidResult(auctionId, amount, "ACCEPTED");
    }
}
```

> ⚠️ Lưu ý cái bẫy proxy AOP: vì `@Transactional` hoạt động qua **proxy**, gọi `doPlaceBid` từ trong cùng class (self-invocation) sẽ **bỏ qua proxy** → transaction không mở. Ở đây `placeBid` (public, không transactional) gọi `doPlaceBid` (transactional) — để chạy đúng, hoặc tách `doPlaceBid` sang một bean riêng, hoặc inject self-proxy. Trong production thường tách `AuctionTxService` riêng. Ta sẽ ghi rõ ở phần Pitfalls.

### 4. Redis leaderboard bằng Sorted Set (ZSet) + `@Cacheable`

```java
// File: service/BidLeaderboardService.java
package com.auction.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class BidLeaderboardService {

    private final StringRedisTemplate redis;

    public BidLeaderboardService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(Long auctionId) {
        return "auction:" + auctionId + ":leaderboard";
    }

    /** Ghi điểm (= số tiền đặt) vào ZSet. score càng cao → hạng càng cao. */
    public void recordBid(Long auctionId, Long userId, double amount) {
        // ZADD auction:{id}:leaderboard amount user:{userId}
        redis.opsForZSet().add(key(auctionId), "user:" + userId, amount);
    }

    /** Lấy top N người đặt giá cao nhất — O(log N + topN), cực nhanh, realtime. */
    public List<LeaderEntry> topBidders(Long auctionId, int topN) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
            redis.opsForZSet().reverseRangeWithScores(key(auctionId), 0, topN - 1);

        if (tuples == null) return List.of();
        return tuples.stream()
            .map(t -> new LeaderEntry(t.getValue(), t.getScore()))
            .toList();
    }

    public record LeaderEntry(String user, Double amount) {}
}
```

```java
// File: service/AuctionQueryService.java
package com.auction.service;

import com.auction.domain.Auction;
import com.auction.repository.AuctionRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class AuctionQueryService {

    private final AuctionRepository auctionRepo;

    public AuctionQueryService(AuctionRepository auctionRepo) {
        this.auctionRepo = auctionRepo;
    }

    // Cache-aside: kết quả lưu Redis với TTL cấu hình ở application.yml.
    // Lần đầu miss → query DB → ghi cache; các lần sau hit cache.
    @Cacheable(value = "auctionDetails", key = "#id")
    public Auction getAuctionDetail(Long id) {
        return auctionRepo.findByIdWithBids(id)
            .orElseThrow(() -> new AuctionNotFoundException(id));
    }
}
```

### 5. Phát Kafka event SAU khi commit

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
                    public void afterCommit() {
                        // key = auctionId → các bid cùng phiên vào cùng partition (giữ thứ tự)
                        kafkaTemplate.send("bid-placed", String.valueOf(event.auctionId()), event);
                    }
                });
        } else {
            kafkaTemplate.send("bid-placed", String.valueOf(event.auctionId()), event);
        }
    }
}
```

```java
// File: event/BidPlacedEvent.java
package com.auction.event;

import java.math.BigDecimal;
import java.time.Instant;

// record bất biến — payload event
public record BidPlacedEvent(Long auctionId, Long userId, BigDecimal amount, Instant at) {}
```

```java
// File: event/NotificationConsumer.java  (service tiêu thụ, có thể là module riêng)
package com.auction.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationConsumer {

    // at-least-once: cần xử lý idempotent (vd: dedup theo eventId)
    @KafkaListener(topics = "bid-placed", groupId = "notification-svc")
    public void onBidPlaced(BidPlacedEvent event) {
        // Gửi thông báo "có người đặt giá cao hơn" cho người đang giữ giá cũ
        System.out.printf("[NOTIFY] Auction %d: user %d đặt %s%n",
            event.auctionId(), event.userId(), event.amount());
    }
}
```

### 6. Controller REST + Spring Security

```java
// File: web/BidController.java
package com.auction.web;

import com.auction.service.BidService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/auctions/{auctionId}/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    @PostMapping
    public ResponseEntity<BidResponse> placeBid(
            @PathVariable Long auctionId,
            @Valid @RequestBody PlaceBidRequest req,
            @AuthenticationPrincipal UserDetails principal) {

        Long userId = ((AuthUser) principal).getId();
        var result = bidService.placeBid(auctionId, userId, req.amount());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new BidResponse(result.auctionId(), result.amount(), result.status()));
    }

    // DTO request có Bean Validation
    public record PlaceBidRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount) {}

    public record BidResponse(Long auctionId, BigDecimal amount, String status) {}
}
```

```java
// File: config/SecurityConfig.java
package com.auction.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)  // REST stateless dùng JWT, không cần CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Ai cũng xem được auction
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auctions/**").permitAll()
                // Đặt giá: cần đăng nhập (ROLE_USER)
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auctions/*/bids").hasRole("USER")
                // Đóng phiên: chỉ ADMIN
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            );
        // .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)  // JWT filter (Day 33)
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 7. Cấu hình `application.yml` (profile prod) + graceful shutdown

```yaml
# File: src/main/resources/application.yml
spring:
  application:
    name: auction-api
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/auction
    username: ${DB_USER:auction}
    password: ${DB_PASS:secret}
    hikari:
      maximum-pool-size: 20        # tune theo số core DB và tải
  jpa:
    hibernate:
      ddl-auto: validate           # PROD: KHÔNG dùng create/update — dùng Flyway/Liquibase
    properties:
      hibernate:
        jdbc.batch_size: 50
        order_inserts: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379
  kafka:
    bootstrap-servers: ${KAFKA_BROKERS:localhost:9092}
    producer:
      acks: all                    # độ bền cao: chờ tất cả replica ack
    consumer:
      group-id: auction-api
      enable-auto-commit: false    # commit offset thủ công sau khi xử lý xong
  cache:
    type: redis
    redis:
      time-to-live: 60000          # TTL cache 60s

server:
  shutdown: graceful               # ngừng nhận request mới, xử lý nốt request đang chạy

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true              # bật liveness/readiness cho Kubernetes
  metrics:
    tags:
      application: auction-api

lifecycle:
  timeout-per-shutdown-phase: 30s  # cho phép tối đa 30s để drain
```

### 8. Test với Testcontainers (integration thật)

```java
// File: src/test/java/com/auction/BidServiceIntegrationTest.java
package com.auction;

import com.auction.service.BidService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class BidServiceIntegrationTest {

    // Postgres THẬT trong container — không mock, không H2
    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired BidService bidService;

    /** Kiểm tra optimistic lock: 10 thread đặt giá đồng thời, chỉ phần thắng được commit. */
    @Test
    void concurrentBids_shouldNotLostUpdate() throws InterruptedException {
        Long auctionId = seedOpenAuction(BigDecimal.valueOf(100));  // helper tạo phiên
        int threads = 10;
        var pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        var accepted = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final long userId = i + 1;
            pool.submit(() -> {
                try {
                    bidService.placeBid(auctionId, userId, BigDecimal.valueOf(110));
                    accepted.incrementAndGet();
                } catch (Exception ignored) {
                    // bid bị từ chối do đã có người đặt 110 trước — đúng kỳ vọng
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Với cùng mức giá 110, chỉ 1 bid được chấp nhận → không có lost update
        assertThat(accepted.get()).isEqualTo(1);
    }

    // seedOpenAuction(...) lược bớt
    private Long seedOpenAuction(BigDecimal price) { /* ... */ return 1L; }
}
```

### 9. `docker-compose.yml` tổng

```yaml
# File: docker-compose.yml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: auction
      POSTGRES_USER: auction
      POSTGRES_PASSWORD: secret
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U auction"]
      interval: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      retries: 5

  kafka:
    image: bitnami/kafka:3.7         # KRaft mode, không cần Zookeeper
    environment:
      KAFKA_CFG_NODE_ID: 0
      KAFKA_CFG_PROCESS_ROLES: controller,broker
      KAFKA_CFG_CONTROLLER_QUORUM_VOTERS: 0@kafka:9093
      KAFKA_CFG_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_CFG_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_CFG_CONTROLLER_LISTENER_NAMES: CONTROLLER
    ports: ["9092:9092"]

  auction-api:
    build: .
    depends_on:
      postgres: { condition: service_healthy }
      redis:    { condition: service_healthy }
      kafka:    { condition: service_started }
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_HOST: postgres
      REDIS_HOST: redis
      KAFKA_BROKERS: kafka:9092
    ports: ["8080:8080"]
```

```dockerfile
# File: Dockerfile (multi-stage — image production gọn, chỉ JRE)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:21-jre        # chỉ JRE → image nhỏ, ít bề mặt tấn công
WORKDIR /app
COPY --from=build /app/target/auction-api.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
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

**Checklist hoàn thiện Auction API (tiêu chí "hoàn thành khóa học"):**

**Tầng dữ liệu (JPA/Hibernate):**
1. Entity `User`, `Auction`, `Bid` với quan hệ đúng; `Auction.version` (`@Version`) chống lost update.
2. Repository có truy vấn fetch join / `@EntityGraph` chống N+1; chứng minh bằng log số query.
3. Migration schema bằng Flyway (không dùng `ddl-auto: update` ở prod).

**Tầng nghiệp vụ (Service + Transaction):**
4. `BidService.placeBid` đặt đúng ranh giới `@Transactional`, có retry optimistic lock + trả 409 khi conflict.
5. Bất biến nghiệp vụ nằm trong entity (`auction.placeBid` kiểm tra giá tối thiểu, phiên mở).

**Tầng web (REST + Security):**
6. `@RestController` cho xem auction (public), đặt giá (`ROLE_USER`), đóng phiên (`ROLE_ADMIN`).
7. Bean Validation cho request; `@ControllerAdvice` map exception → status code chuẩn (404/409/400/401).
8. JWT auth (Day 33), mật khẩu hash BCrypt.

**Infrastructure (Redis + Kafka + Docker):**
9. `@Cacheable` cache chi tiết auction (có TTL) + `@CacheEvict` khi đổi giá.
10. Redis ZSet leaderboard top người đặt giá, cập nhật realtime.
11. Phát Kafka `bid-placed` **sau commit**; consumer notification/analytics idempotent.
12. `docker-compose.yml` chạy được cả stack (Postgres + Redis + Kafka + app) bằng một lệnh.

**Production (Test + Monitor + Deploy):**
13. Unit test (Mockito) cho service logic; integration test (Testcontainers) cho luồng đặt giá + concurrency (test lost update).
14. Actuator expose health/metrics/prometheus; có counter & timer cho bid.
15. Profile `prod`, graceful shutdown, Dockerfile multi-stage (image JRE gọn).

**Tự đánh giá năng lực toàn khóa:**
16. Với mỗi phase trong bảng năng lực (mục Lý thuyết #2), tự giải thích được 1 phút mỗi điểm cốt lõi mà không nhìn tài liệu.

**Gợi ý mở rộng & lộ trình tiếp theo (sau khi hoàn thành):**
- **Microservices:** tách Auction / Notification / Analytics thành service riêng, giao tiếp qua Kafka; thêm API Gateway + service discovery.
- **Observability sâu:** distributed tracing (OpenTelemetry + Jaeger/Tempo), correlation ID xuyên service, structured logging tập trung (Loki/ELK).
- **Performance tuning:** benchmark đúng cách bằng **JMH**, profiling bằng async-profiler, đọc GC log; tải thử bằng k6/Gatling.
- **Event Sourcing / CQRS:** lưu chuỗi sự kiện bid làm nguồn sự thật; tách read model (leaderboard) khỏi write model.
- **Kubernetes:** deploy với Deployment + HPA (autoscale theo CPU/metric), liveness/readiness probe, ConfigMap/Secret, rolling update.
- **Resilience:** Resilience4j (circuit breaker, retry, bulkhead) cho gọi service ngoài; outbox pattern để đảm bảo gửi event đáng tin cậy.

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

- [ ] Vẽ lại được sơ đồ kiến trúc tổng thể và mô tả luồng request "đặt giá" bằng lời của mình
- [ ] Hoàn thành bảng năng lực toàn khóa — tự tin mọi phase, đánh dấu Day cần ôn lại
- [ ] Auction API chạy được end-to-end bằng `docker compose up` (Postgres + Redis + Kafka + app)
- [ ] Optimistic locking `@Version` + retry/409 hoạt động đúng (test concurrency pass)
- [ ] Chống N+1 bằng fetch join / `@EntityGraph`, chứng minh bằng log số query
- [ ] `@Transactional` đặt đúng tầng service; Kafka/Redis cập nhật sau commit
- [ ] Redis ZSet leaderboard + `@Cacheable` (có TTL) hoạt động
- [ ] Spring Security phân quyền: xem (public), đặt giá (USER), đóng phiên (ADMIN)
- [ ] Integration test bằng Testcontainers (Postgres thật) pass
- [ ] Actuator expose health/metrics/prometheus; có metric cho bid
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
