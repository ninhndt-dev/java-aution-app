# Day 29 - AOP (Aspect-Oriented Programming)

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP — quen middleware và event/listener, nay học "chắn ngang" ở cấp method.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **cross-cutting concern** (mối quan tâm cắt ngang): logging, security, transaction, metrics.
- Nắm vững thuật ngữ AOP: **aspect, advice, pointcut, join point, weaving**.
- Biết 5 loại **advice**: `@Before`, `@After`, `@AfterReturning`, `@AfterThrowing`, `@Around`.
- Viết được **pointcut** bằng biểu thức `execution(...)`.
- Dùng `@Aspect` + `@EnableAspectJAutoProxy`, `ProceedingJoinPoint`, và `@Order` để xếp thứ tự aspect.
- Ánh xạ sang Laravel: **middleware** + **event/listener** là họ hàng gần của AOP.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vấn đề: Cross-cutting concern

Hãy nhìn một method nghiệp vụ "thuần" bị **bẩn** bởi các mối quan tâm phụ:

```java
public Bid placeBid(long auctionId, String bidder, long amount) {
    long start = System.nanoTime();                 // [metrics] đo thời gian
    log.info("placeBid bắt đầu: {} {}", bidder, amount); // [logging]
    if (!security.canBid(bidder)) throw ...;        // [security]
    try {
        Bid bid = doPlaceBid(...);                  // <-- NGHIỆP VỤ THẬT chỉ có dòng này
        log.info("placeBid xong");                  // [logging]
        return bid;
    } finally {
        metrics.record(System.nanoTime() - start);  // [metrics]
    }
}
```

Logging, security, metrics, transaction là những **mối quan tâm cắt ngang**: chúng lặp đi lặp lại ở **hàng trăm method** nhưng không phải nghiệp vụ chính. Nhét chúng vào từng method → trùng lặp, khó bảo trì, làm lu mờ logic thật.

**AOP** tách những mối quan tâm này ra một nơi (aspect) rồi **"tiêm" tự động** vào các method cần thiết, để method nghiệp vụ chỉ còn:

```java
public Bid placeBid(long auctionId, String bidder, long amount) {
    return doPlaceBid(...); // chỉ còn nghiệp vụ thật; logging/metrics... do aspect lo
}
```

```
            ┌─────────── Aspect (1 nơi định nghĩa) ───────────┐
            │  Logging │ Security │ Metrics │ Transaction       │
            └──────────────────────┬──────────────────────────┘
                                   │ weaving (đan vào)
        ┌───────────┬──────────────┼──────────────┬───────────┐
        ▼           ▼              ▼              ▼           ▼
   placeBid()   openAuction()  closeAuction()  refund()   ...   (các method nghiệp vụ)
```

### 2. Thuật ngữ AOP (phải thuộc)

| Thuật ngữ | Định nghĩa | Ví dụ |
|---|---|---|
| **Aspect** | Module gói một mối quan tâm cắt ngang | `LoggingAspect`, `MetricsAspect` |
| **Join point** | Một "điểm" trong luồng chạy có thể chèn advice | Lời gọi một method (Spring AOP chỉ hỗ trợ join point ở mức **method**) |
| **Advice** | Hành động thực thi tại join point | "Ghi log trước khi method chạy" |
| **Pointcut** | Biểu thức **chọn** những join point nào áp advice | `execution(* com.example.auction..*Service.*(..))` |
| **Weaving** | Quá trình **đan** aspect vào code đối tượng | Spring làm lúc **runtime** qua **proxy** |
| **Target object** | Object gốc bị advice tác động | `AuctionService` thật |
| **AOP proxy** | Object bao quanh target, chèn advice | Proxy do Spring tạo (Day 30) |

> 💡 **Spring AOP weaving ở runtime qua proxy** (khác AspectJ "full" có thể weave lúc compile/load-time). Đây là lý do Spring AOP chỉ áp được cho **method public của bean** và bị bẫy **self-invocation** (Day 30).

### 3. Năm loại Advice

```
                    │  @Before     │ chạy TRƯỚC method
   join point       │──────────────┤
   (method gọi) ───►│  method NGHIỆP VỤ chạy
                    │──────────────┤
                    │  @AfterReturning │ chạy khi method TRẢ VỀ bình thường (có kết quả)
                    │  @AfterThrowing  │ chạy khi method NÉM exception
                    │  @After          │ chạy SAU method dù thành công hay lỗi (finally)
                    │──────────────────┘

   @Around : bao TRỌN — tự quyết định gọi method hay không, sửa cả input/output
```

| Advice | Khi nào chạy | Có chặn được method? | Dùng cho |
|---|---|---|---|
| `@Before` | Trước method | Không (chỉ ném exception để chặn) | Log đầu vào, kiểm tra quyền |
| `@AfterReturning` | Sau khi trả về OK | Không | Log kết quả, audit thành công |
| `@AfterThrowing` | Khi ném exception | Không | Log lỗi, alert |
| `@After` | Sau cùng (finally) | Không | Dọn dẹp, đóng tài nguyên |
| `@Around` | Bao trọn method | **Có** — gọi `pjp.proceed()` hay không tùy bạn | Đo thời gian, transaction, cache, retry |

> 💡 `@Around` mạnh nhất nhưng cũng dễ sai nhất: bạn **bắt buộc** gọi `joinPoint.proceed()` để method gốc chạy; quên gọi → method nghiệp vụ không bao giờ thực thi. Nó trả về kết quả mà bạn có thể sửa.

### 4. Pointcut với biểu thức `execution(...)`

Cú pháp `execution`:

```
execution( [modifiers] return-type  package.Class.method(params) )

  *                         → bất kỳ (return type / tên)
  ..  (trong package)       → package này và mọi package con
  ..  (trong tham số)       → bất kỳ số lượng/kiểu tham số
```

Ví dụ:

```java
// Mọi method public của mọi class kết thúc bằng "Service" trong package auction (& con)
execution(public * com.example.auction..*Service.*(..))

// Method placeBid với bất kỳ tham số nào
execution(* com.example.auction.BidService.placeBid(..))

// Mọi method trong class có annotation @AuditLog
@annotation(com.example.auction.AuditLog)
```

Có thể tách pointcut ra cho gọn và tái sử dụng:

```java
@Pointcut("execution(* com.example.auction..*Service.*(..))")
public void serviceLayer() {}   // method rỗng làm "tên" pointcut

@Around("serviceLayer()")        // tham chiếu pointcut theo tên
public Object measure(ProceedingJoinPoint pjp) throws Throwable { ... }
```

### 5. Bật AOP & thứ tự nhiều aspect

- Thêm `@Aspect` lên class aspect và `@Component` để nó thành bean.
- Bật cơ chế proxy bằng `@EnableAspectJAutoProxy` (Spring Boot **tự bật** khi có `spring-boot-starter-aop`).
- Khi nhiều aspect cùng áp một method, dùng `@Order(n)` — **số nhỏ chạy trước** (ở phía "before"; ở phía "after" thì ngược lại, như các lớp vỏ hành):

```
@Order(1) SecurityAspect  ──► @Order(2) LoggingAspect ──► method ──► Logging(after) ──► Security(after)
```

> ⚠️ Spring AOP chỉ chặn được lời gọi tới **bean** (qua proxy). Gọi `this.method()` trong cùng class **không qua proxy** → advice không chạy (self-invocation — Day 30 đào sâu).

---

## 🔁 Đối chiếu với Laravel/PHP

Bạn đã làm AOP "kiểu Laravel" mà không gọi tên nó như vậy:

| Khái niệm AOP (Spring) | Laravel tương đương | Ghi chú |
|---|---|---|
| Aspect (cross-cutting) | **Middleware** (chắn ngang request) | Middleware là AOP ở tầng HTTP request |
| `@Around` advice | Middleware `handle($request, $next)` gọi `$next()` | `$next()` ≈ `joinPoint.proceed()` |
| `@Before` | `before` của middleware (code trước `$next`) | |
| `@After`/`@AfterReturning` | code sau `$next($request)` | |
| Pointcut (`execution`) | Gắn middleware vào route/group | "Chọn nơi áp" |
| `@Order` aspect | Thứ tự middleware trong `$middlewarePriority` | |
| `@AfterThrowing` / audit | **Event + Listener** (`event()` → `Listener`) | Tách phản ứng phụ ra khỏi nghiệp vụ |
| Weaving runtime qua proxy | Pipeline middleware bọc quanh request | Cùng tinh thần "bọc" |

Ví dụ middleware Laravel (giống `@Around`):

```php
class MeasureTime
{
    public function handle($request, Closure $next)
    {
        $start = microtime(true);            // ~ @Before
        $response = $next($request);         // ~ joinPoint.proceed()
        Log::info('Mất ' . (microtime(true)-$start) . 's'); // ~ @After
        return $response;                    // có thể sửa response
    }
}
```

So với Spring `@Around` (xem phần code) — **cùng một mô hình "bọc và chuyển tiếp"**.

> 💡 **Khác biệt then chốt**: Middleware Laravel chỉ chắn ở **tầng HTTP request** (vào/ra controller). Spring AOP chắn ở **mức method của bất kỳ bean nào** (service, repository...), tinh vi hơn và sâu hơn. Còn **event/listener** thì cả hai framework đều có và dùng cho mục đích "phản ứng phụ" (Day 31 nói kỹ về event của Spring).

---

## 💻 Thực hành code

### Bước 1 — Bật AOP & đo thời gian thực thi method service

```java
// File: AuctionAopConfig.java
package com.example.auction;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@Configuration
@EnableAspectJAutoProxy  // bật proxy-based AOP (Spring Boot starter-aop tự bật sẵn)
public class AuctionAopConfig { }
```

```java
// File: TimingAspect.java
package com.example.auction;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(2) // chạy SAU SecurityAspect (@Order(1)) ở phía before
public class TimingAspect {

    // Pointcut: mọi method public của class *Service trong package auction (& con)
    @Pointcut("execution(public * com.example.auction..*Service.*(..))")
    public void serviceLayer() {}

    @Around("serviceLayer()")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.nanoTime();
        try {
            // BẮT BUỘC gọi proceed() để method nghiệp vụ thật chạy
            return pjp.proceed();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String method = pjp.getSignature().toShortString();
            System.out.println("[timing] " + method + " mất " + ms + " ms");
        }
    }
}
```

### Bước 2 — Audit log mỗi lần đặt giá (annotation-targeted pointcut)

```java
// File: AuditLog.java — annotation đánh dấu method cần audit
package com.example.auction;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String action() default "";
}
```

```java
// File: AuditAspect.java
package com.example.auction;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.stereotype.Component;
import java.util.Arrays;

@Aspect
@Component
public class AuditAspect {

    // Pointcut: mọi method được đánh dấu @AuditLog
    @Before("@annotation(auditLog)")
    public void logBefore(JoinPoint jp, AuditLog auditLog) {
        System.out.println("[audit] HÀNH ĐỘNG=" + auditLog.action()
                + " method=" + jp.getSignature().getName()
                + " args=" + Arrays.toString(jp.getArgs()));
    }

    // Chạy khi method trả về bình thường, nhận cả kết quả
    @AfterReturning(pointcut = "@annotation(com.example.auction.AuditLog)", returning = "result")
    public void logSuccess(JoinPoint jp, Object result) {
        System.out.println("[audit] THÀNH CÔNG " + jp.getSignature().getName()
                + " -> " + result);
    }

    // Chạy khi method ném exception
    @AfterThrowing(pointcut = "@annotation(com.example.auction.AuditLog)", throwing = "ex")
    public void logFailure(JoinPoint jp, Throwable ex) {
        System.out.println("[audit] LỖI " + jp.getSignature().getName()
                + " -> " + ex.getMessage());
    }
}
```

```java
// File: BidService.java — gắn @AuditLog vào method đặt giá
@Service
public class BidService {
    private final BidRepository bidRepository;
    public BidService(BidRepository bidRepository) { this.bidRepository = bidRepository; }

    @AuditLog(action = "PLACE_BID")   // <-- aspect sẽ "bắt" method này
    public Bid placeBid(long auctionId, String bidder, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Số tiền phải dương");
        Bid bid = new Bid(auctionId, bidder, amount);
        bidRepository.save(bid);
        return bid;
    }
}
```

Khi gọi `bidService.placeBid(1, "an", 500_000)`, bạn thấy:
```
[audit] HÀNH ĐỘNG=PLACE_BID method=placeBid args=[1, an, 500000]
[timing] BidService.placeBid(..) mất 3 ms
[audit] THÀNH CÔNG placeBid -> Bid{...}
```

> ✅ **Bài tập tự giải thích:** Gọi `placeBid` với `amount = -1`. Quan sát `@AfterThrowing` chạy còn `@AfterReturning` thì không. Vì sao `@Around` của `TimingAspect` vẫn in được thời gian dù method ném exception?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Quên gọi `joinPoint.proceed()` trong `@Around`.** Method nghiệp vụ sẽ không bao giờ chạy, kết quả trả về null/sai. Luôn `return pjp.proceed();`.
- **Self-invocation.** Gọi `this.placeBid()` từ method khác trong **cùng class** → không qua proxy → advice không chạy (Day 30). Phải gọi qua bean khác/inject chính nó.
- **Pointcut sai phạm vi.** `execution(* *Service.*(..))` không có package → áp quá rộng (cả bean hạ tầng), tốn hiệu năng. Luôn giới hạn package.
- **AOP chỉ áp method `public` của bean.** Method `private`/`protected`/`static`, hoặc object tự `new` (không phải bean) → advice không tác động.
- **Nuốt exception trong advice.** Nếu `@Around` bắt exception mà không ném lại, bạn che giấu lỗi nghiệp vụ. Hãy để exception lan ra (hoặc bọc lại có chủ đích).
- **Thứ tự aspect không xác định.** Không đặt `@Order` khi nhiều aspect cùng áp → thứ tự khó đoán. Đặt `@Order` rõ ràng cho security/transaction/logging.
- **Advice nặng làm chậm mọi method.** Aspect áp lên hàng trăm method — giữ nó nhẹ, tránh I/O đồng bộ trong advice nóng.

---

## 🚀 Liên hệ Spring Boot / Production

- `@Transactional`, `@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize` **đều là AOP**: Spring tạo proxy bao quanh bean để chèn logic transaction/cache/bảo mật. Hiểu AOP = hiểu cách những annotation "thần kỳ" này hoạt động.
- AOP là cách chuẩn để thêm **observability**: đo latency, đếm lỗi, ghi metric Micrometer cho mọi method service mà không sửa code nghiệp vụ.
- **Audit log** (ai làm gì, lúc nào) thường hiện thực bằng aspect + annotation `@AuditLog` như trên — rất hợp cho hệ thống tài chính/đấu giá cần truy vết.
- Cẩn trọng hiệu năng: proxy thêm một lớp gọi gián tiếp. Với method cực nóng (gọi triệu lần/giây), cân nhắc đo lường trước khi đan aspect.
- Trong Boot, chỉ cần thêm `spring-boot-starter-aop` là `@EnableAspectJAutoProxy` bật tự động — không cần config tay.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta thêm **observability + audit** cho luồng đấu giá bằng AOP, giữ code nghiệp vụ sạch.

**Nhiệm vụ Day 29:**

1. Thêm dependency `spring-boot-starter-aop`. Tạo `TimingAspect` (`@Around`) đo thời gian **mọi** method của các `*Service` trong package auction; in `method + ms`.
2. Tạo annotation `@AuditLog(action=...)` và `AuditAspect` với `@Before` (log đầu vào), `@AfterReturning` (log kết quả), `@AfterThrowing` (log lỗi).
3. Gắn `@AuditLog(action="PLACE_BID")` lên `BidService.placeBid(...)`. Đặt bid hợp lệ và bid lỗi (số âm), quan sát đủ 3 nhánh advice.
4. Thêm `@Order` cho `TimingAspect` và `AuditAspect` để kiểm soát thứ tự; giải thích thứ tự bạn quan sát được.
5. (Mở rộng) Tạo `SecurityAspect` (`@Before`, `@Order(1)`) kiểm tra "bidder không rỗng" trước khi đặt giá.
6. Ghi `notes/day-29.md`: đối chiếu `@Around` với middleware Laravel (`$next($request)` ↔ `pjp.proceed()`); giải thích vì sao audit nên tách ra aspect thay vì viết trong `placeBid`.

> 🎯 Tiêu chí đạt: method `placeBid` chỉ còn logic nghiệp vụ; log timing + audit xuất hiện tự động; bạn giải thích được thứ tự advice và bẫy self-invocation.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Cross-cutting concern là gì? Cho ví dụ.**
> **Đáp:** Là mối quan tâm **cắt ngang** nhiều module/method, lặp đi lặp lại nhưng không phải nghiệp vụ chính. Ví dụ: logging, security/authorization, transaction, caching, metrics/monitoring, audit. AOP giúp tách chúng ra một nơi (aspect) và áp tự động, giữ code nghiệp vụ sạch.

**Q2: Giải thích các thuật ngữ aspect, advice, pointcut, join point.**
> **Đáp:** **Aspect** = module gói một mối quan tâm cắt ngang. **Join point** = một điểm trong luồng chạy có thể chèn advice (trong Spring AOP là lời gọi method). **Advice** = hành động chạy tại join point (before/after/around...). **Pointcut** = biểu thức chọn những join point nào sẽ áp advice (ví dụ `execution(...)`).

**Q3: Có mấy loại advice? Khác nhau thế nào?**
> **Đáp:** 5 loại: `@Before` (trước method), `@AfterReturning` (sau khi trả về OK, nhận kết quả), `@AfterThrowing` (khi ném exception), `@After` (sau cùng — finally), và `@Around` (bao trọn method, tự quyết định gọi `proceed()` hay không, có thể sửa input/output). `@Around` mạnh nhất.

### Mức Senior

**Q4: Spring AOP weaving lúc nào? Khác AspectJ ra sao?**
> **Đáp:** Spring AOP weave ở **runtime qua proxy** (JDK dynamic proxy hoặc CGLIB — Day 30). AspectJ "full" có thể weave lúc **compile-time** hoặc **load-time** (sửa bytecode), nên áp được cả method private, field access, constructor. Đánh đổi: Spring AOP đơn giản, đủ dùng cho hầu hết nhu cầu (chỉ method public của bean) nhưng có bẫy self-invocation; AspectJ mạnh hơn nhưng phức tạp hơn nhiều.

**Q5: `@Around` khác `@Before` + `@After` ở điểm cốt lõi nào?**
> **Đáp:** `@Around` **bao trọn** join point và **nắm quyền điều khiển**: nó quyết định có gọi method gốc (`joinPoint.proceed()`) hay không, có thể sửa tham số trước khi gọi, sửa kết quả sau khi gọi, hoặc chặn hoàn toàn (cache hit, circuit breaker). `@Before`/`@After` chỉ "chứng kiến" — không chặn được method, không sửa được kết quả (chỉ có thể ném exception để chặn). Vì vậy transaction/cache/retry phải dùng `@Around`.

**Q6: Vì sao thứ tự aspect quan trọng và điều khiển bằng gì?**
> **Đáp:** Khi nhiều aspect áp cùng method, chúng tạo các "lớp vỏ" lồng nhau. Thứ tự quyết định đúng/sai: ví dụ **security phải chạy trước transaction** (chặn truy cập trước khi mở transaction); **transaction phải bọc ngoài logging** để log đúng trạng thái. Điều khiển bằng `@Order(n)` — số nhỏ ở **ngoài cùng** (chạy trước ở phía before, sau ở phía after). Không đặt `@Order` → thứ tự không xác định, dễ sinh bug tinh vi.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được cross-cutting concern và lợi ích tách aspect
- [ ] Thuộc 5 thuật ngữ AOP và phân biệt được
- [ ] Phân biệt 5 loại advice, đặc biệt `@Around` vs phần còn lại
- [ ] Viết được pointcut `execution(...)` và `@annotation(...)`
- [ ] Bật AOP, viết aspect đo thời gian + audit
- [ ] Điều khiển thứ tự bằng `@Order`, hiểu bẫy self-invocation
- [ ] Ánh xạ `@Around` ↔ middleware `$next()` của Laravel
- [ ] Hoàn thành Mini Project Day 29 (timing + audit aspect)
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Aspect Oriented Programming with Spring"
- Baeldung — "Introduction to Spring AOP", "Spring AOP Advices", "Spring AOP Pointcut Expressions"
- Laravel Docs — "Middleware" & "Events" (đối chiếu mô hình bọc/phản ứng)
- AspectJ Documentation — khái niệm join point, pointcut (đào sâu khi cần)
