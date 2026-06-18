# Day 28 - Bean Lifecycle (Vòng đời Bean)

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP — quen `singleton`/`bind`, deferred provider, boot callback.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm **toàn bộ các pha vòng đời bean**: từ instantiate → DI → callbacks → init → sẵn sàng → destroy.
- Biết 3 cách khai báo **init** và **destroy**, và **thứ tự** chúng được gọi.
- Hiểu **`BeanPostProcessor`** — điểm mở rộng quyền lực mà chính `@Autowired` và AOP cũng dựa vào.
- Nắm các **scope**: singleton (mặc định), prototype, request/session/application (web).
- Hiểu **lazy init** và khi nào dùng.
- Ánh xạ sang Laravel: `singleton`/`bind`, **deferred service provider**, `boot()` callback.

---

## 🧠 Lý thuyết cốt lõi

### 1. Toàn cảnh vòng đời một bean

Khi container khởi tạo một bean, nó đi qua chuỗi pha sau (đây là sơ đồ bạn nên thuộc):

```
  1. Instantiate            → tạo object (gọi constructor)
         │
  2. Populate properties    → Dependency Injection (tiêm phụ thuộc)
         │
  3. Aware callbacks        → BeanNameAware, BeanFactoryAware,
         │                     ApplicationContextAware... (bean "biết" về container)
         │
  4. BeanPostProcessor      → postProcessBeforeInitialization()  (TRƯỚC init)
         │  .before
  5. Initialization         → @PostConstruct
         │                     InitializingBean.afterPropertiesSet()
         │                     init-method (initMethod trong @Bean)
         │
  6. BeanPostProcessor      → postProcessAfterInitialization()   (SAU init)
         │  .after             (AOP proxy thường được tạo Ở ĐÂY!)
         ▼
  7. Bean SẴN SÀNG sử dụng  ───────────────────────────────────────
         │  (sống trong container, phục vụ request)
         │
  8. Container đóng (close) → @PreDestroy
                              DisposableBean.destroy()
                              destroy-method (destroyMethod trong @Bean)
```

> 💡 Hai pha quan trọng nhất với lập trình viên: **(5) init** — nơi bạn chuẩn bị tài nguyên (kết nối, cache, prefetch dữ liệu); và **(8) destroy** — nơi bạn dọn dẹp (đóng connection, flush buffer). Đừng để logic này trong constructor (lúc đó phụ thuộc có thể chưa được inject xong).

### 2. Ba cách khai báo init & destroy

| Cơ chế | Init | Destroy | Ghi chú |
|---|---|---|---|
| **Annotation (khuyến nghị)** | `@PostConstruct` | `@PreDestroy` | Chuẩn Jakarta, gọn, độc lập framework |
| **Interface** | `InitializingBean.afterPropertiesSet()` | `DisposableBean.destroy()` | Bị "dính" API Spring, ít dùng |
| **Khai báo trong `@Bean`** | `@Bean(initMethod="...")` | `@Bean(destroyMethod="...")` | Dùng cho class bên thứ ba không sửa được |

```java
@Service
public class AuctionService {

    @PostConstruct          // gọi SAU khi DI xong, TRƯỚC khi bean sẵn sàng
    public void warmUp() {
        System.out.println("Nạp sẵn danh sách phiên đấu giá đang mở...");
    }

    @PreDestroy             // gọi khi container đóng, trước khi bean bị hủy
    public void cleanUp() {
        System.out.println("Đóng kết nối, flush log đấu giá...");
    }
}
```

> ⚠️ **Thứ tự khi dùng cả 3**: `@PostConstruct` → `afterPropertiesSet()` → `initMethod`. Tương tự cho destroy: `@PreDestroy` → `destroy()` → `destroyMethod`. Thực tế **chỉ nên dùng `@PostConstruct`/`@PreDestroy`** để tránh rối.

### 3. `BeanPostProcessor` — điểm mở rộng tối thượng

`BeanPostProcessor` (BPP) là interface cho phép **chen vào** quá trình khởi tạo **mọi** bean, ngay trước và sau pha init:

```java
public interface BeanPostProcessor {
    Object postProcessBeforeInitialization(Object bean, String beanName);
    Object postProcessAfterInitialization(Object bean, String beanName);
}
```

Điểm "thần kỳ": **chính Spring dùng BPP để hiện thực nhiều tính năng lõi**:

- `@Autowired`/`@Value` được xử lý bởi `AutowiredAnnotationBeanPostProcessor`.
- `@PostConstruct`/`@PreDestroy` được xử lý bởi `CommonAnnotationBeanPostProcessor`.
- **AOP proxy** (Day 29-30) được **tạo trong `postProcessAfterInitialization`** — bean gốc được "bọc" bằng proxy ở đây.

```
   Bean gốc  ──► BPP.before ──► init ──► BPP.after ──► (có thể trả về PROXY thay bean gốc!)
                                                              │
                                          Đây là lý do @Transactional/@Async hoạt động:
                                          BPP thay bean của bạn bằng một proxy bao quanh nó.
```

> 💡 Hiểu BPP giúp bạn trả lời được câu hỏi sâu: "Vì sao `@Transactional` lại hoạt động? Vì sao self-invocation phá nó?" (trả lời đầy đủ ở Day 30). Mấu chốt: bean mà container giữ thường **không phải** object gốc của bạn mà là một **proxy** do BPP tạo.

### 4. Bean Scope (phạm vi)

Scope quyết định **container tạo bao nhiêu instance** và **chúng sống bao lâu**:

| Scope | Số instance | Vòng đời | Dùng khi |
|---|---|---|---|
| `singleton` (mặc định) | **1** cho cả container | Suốt đời app | Hầu hết bean (stateless service, repo) |
| `prototype` | **Mới mỗi lần** `getBean`/inject | Container **không** quản destroy | Bean có state riêng từng lần dùng |
| `request` (web) | 1 cho mỗi HTTP request | Theo request | Dữ liệu gắn với 1 request |
| `session` (web) | 1 cho mỗi HTTP session | Theo session | Giỏ hàng, dữ liệu user đăng nhập |
| `application` (web) | 1 cho `ServletContext` | Suốt đời servlet context | Cấu hình toàn cục web |

```java
@Service
@Scope("prototype")   // mỗi lần lấy ra một instance MỚI
public class BidDraft { ... }
```

> ⚠️ **Bẫy scope kinh điển**: inject một bean `prototype` (hoặc `request`) vào một bean `singleton`. Vì singleton chỉ được inject **một lần lúc startup**, nó sẽ giữ **mãi mãi** một instance prototype duy nhất → mất ý nghĩa prototype. Giải pháp: dùng `ObjectProvider<T>`, `@Lookup`, hoặc proxy scope (`proxyMode = TARGET_CLASS`).

> ⚠️ **Prototype và destroy**: Spring **không** gọi `@PreDestroy` cho bean prototype (nó "buông tay" sau khi tạo). Bạn phải tự dọn dẹp tài nguyên prototype.

### 5. Lazy Initialization

Mặc định `ApplicationContext` tạo singleton **eager** (lúc startup). Dùng `@Lazy` để hoãn tới lần dùng đầu tiên:

```java
@Service
@Lazy   // chỉ tạo khi bean này thực sự được gọi tới lần đầu
public class ReportingService { ... }
```

Dùng khi: bean nặng/hiếm dùng (sinh báo cáo), hoặc để phá circular dependency. Đánh đổi: mất fail-fast (lỗi cấu hình lộ ra muộn). Bật toàn cục bằng `spring.main.lazy-initialization=true` (cẩn trọng).

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm Spring | Laravel tương đương | Ghi chú |
|---|---|---|
| Singleton scope | `$this->app->singleton(...)` | Một instance dùng chung |
| Prototype scope | `$this->app->bind(...)` (mặc định) | Mỗi `make` một instance mới |
| `request` scope | (gần) `scoped()` binding (Laravel 9+) | Một instance mỗi request/lifecycle |
| `@PostConstruct` (init) | `boot()` của ServiceProvider / `booted()` callback | Logic chạy sau khi mọi thứ đăng ký xong |
| Đăng ký bean (`register`) | `register()` của ServiceProvider | Chỉ bind, chưa dùng phụ thuộc khác |
| `@PreDestroy` (destroy) | `terminating()` callback (Laravel) | Dọn dẹp cuối vòng đời |
| `@Lazy` / lazy init | **Deferred Service Provider** (`$defer = true`) | Hoãn tạo tới khi cần |
| `BeanPostProcessor` | (không có tương đương trực tiếp) | Cơ chế mở rộng riêng của Spring |

> 💡 Laravel **tách rõ `register()` và `boot()`** chính vì lý do giống Spring tách "instantiate/DI" và "@PostConstruct": trong `register()` bạn chỉ được bind (chưa chắc dependency khác đã sẵn sàng); tới `boot()` (≈ `@PostConstruct`) thì mọi binding đã xong, an toàn để dùng. Cùng một bài học về **thứ tự khởi tạo**.

> 💡 **Deferred Service Provider** của Laravel = `@Lazy` của Spring: chỉ "boot" provider khi một trong các binding của nó thực sự được resolve, để tăng tốc khởi động.

---

## 💻 Thực hành code

### Bước 1 — Bean với `@PostConstruct` / `@PreDestroy`

```java
// File: AuctionService.java
package com.example.auction;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Service
public class AuctionService {

    private final BidRepository bidRepository;
    private Map<Long, Long> highestBidCache; // cache giá cao nhất theo auctionId

    public AuctionService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
        // ⚠️ KHÔNG nạp cache ở đây — phụ thuộc có thể chưa hoàn toàn sẵn sàng;
        //    để dành cho @PostConstruct.
    }

    @PostConstruct
    public void initCache() {
        // Gọi SAU khi DI xong: lúc này bidRepository chắc chắn đã sẵn sàng
        this.highestBidCache = new ConcurrentHashMap<>();
        highestBidCache.putAll(bidRepository.loadHighestBids());
        System.out.println("[init] Đã nạp cache giá cao nhất: " + highestBidCache.size() + " phiên");
    }

    @PreDestroy
    public void flushOnShutdown() {
        // Gọi khi app tắt: ghi cache xuống DB, đóng tài nguyên
        bidRepository.persistHighestBids(highestBidCache);
        System.out.println("[destroy] Đã flush cache, đóng tài nguyên đấu giá");
    }
}
```

### Bước 2 — Custom `BeanPostProcessor` ghi log vòng đời

```java
// File: LifecycleLoggingPostProcessor.java
package com.example.auction;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component  // chỉ cần là bean, Spring TỰ nhận diện nó là BeanPostProcessor
public class LifecycleLoggingPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        // chạy TRƯỚC @PostConstruct của MỌI bean
        if (beanName.toLowerCase().contains("auction")) {
            System.out.println("[BPP.before] sắp init bean: " + beanName
                    + " (" + bean.getClass().getSimpleName() + ")");
        }
        return bean; // trả về bean (có thể bọc proxy ở đây nếu muốn)
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // chạy SAU @PostConstruct — đây là nơi Spring AOP thường tạo proxy
        if (beanName.toLowerCase().contains("auction")) {
            System.out.println("[BPP.after]  đã init xong bean: " + beanName);
        }
        return bean;
    }
}
```

Chạy app, bạn sẽ thấy thứ tự log:
```
[BPP.before] sắp init bean: auctionService (AuctionService)
[init] Đã nạp cache giá cao nhất: ... phiên
[BPP.after]  đã init xong bean: auctionService
```
→ Chứng minh đúng trình tự: BPP.before → `@PostConstruct` → BPP.after.

### Bước 3 — Thử scope prototype

```java
@Service
@Scope("prototype")
public class BidDraft {
    public BidDraft() {
        System.out.println("Tạo BidDraft mới @" + Integer.toHexString(hashCode()));
    }
}
```

```java
// Trong main:
context.getBean(BidDraft.class); // in hash A
context.getBean(BidDraft.class); // in hash B (KHÁC A) -> mỗi lần một instance
```

> ✅ **Bài tập tự giải thích:** Inject `BidDraft` (prototype) vào một `@Service` singleton, rồi gọi nó nhiều lần. Vì sao luôn ra **cùng** instance dù `BidDraft` là prototype? Hãy sửa lại bằng `ObjectProvider<BidDraft>` để mỗi lần lấy ra một bản mới.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Đặt logic khởi tạo nặng trong constructor.** Lúc constructor chạy, một số phụ thuộc/cấu hình có thể chưa sẵn sàng hoàn toàn. Dùng `@PostConstruct` cho việc "khởi động" (nạp cache, mở kết nối).
- **Trông chờ `@PreDestroy` chạy với prototype.** Spring **không** quản destroy cho prototype → tài nguyên rò rỉ. Tự dọn hoặc đừng dùng prototype cho bean giữ tài nguyên.
- **Inject prototype/request vào singleton.** Singleton chỉ inject một lần → "đóng băng" một instance prototype. Dùng `ObjectProvider`/`@Lookup`/proxy scope.
- **`@PreDestroy` không chạy khi tắt app đột ngột.** `kill -9`/crash không cho Spring gọi destroy. Đừng phụ thuộc hoàn toàn vào nó cho dữ liệu sống còn; dùng cơ chế bền vững (DB transaction).
- **Quên bean mặc định eager.** Nếu `@PostConstruct` của bạn ném exception, app **chết lúc startup** — đúng kỳ vọng fail-fast, nhưng đừng bất ngờ.
- **Tưởng `BeanPostProcessor` chỉ áp cho một bean.** Nó chạy cho **mọi** bean — nhớ lọc theo `beanName`/kiểu, và giữ nó **nhẹ** (chạy rất nhiều lần lúc startup).
- **`BeanPostProcessor` không thể tự `@Autowired` bean thường một cách an toàn**, vì nó được tạo **rất sớm** (trước các bean khác). Tránh phụ thuộc nặng trong BPP.

---

## 🚀 Liên hệ Spring Boot / Production

- **Graceful shutdown**: Spring Boot hỗ trợ `server.shutdown=graceful` — khi nhận SIGTERM, app ngừng nhận request mới, xử lý nốt request đang chạy, rồi gọi `@PreDestroy`. Cực quan trọng cho zero-downtime deploy trên Kubernetes (cùng `terminationGracePeriodSeconds`).
- `@PostConstruct` thường dùng để **warm-up cache**, kiểm tra kết nối DB/Redis, đăng ký metric — chạy trước khi readiness probe báo "sẵn sàng".
- Bean hạ tầng giữ tài nguyên (`DataSource` HikariCP, Redis pool, Kafka producer) đều dựa vào destroy callback để đóng pool sạch sẽ — đó là lý do bạn hiếm khi tự đóng connection trong code.
- `@Scope("request")` rất hữu ích cho **per-request context** (trace id, user hiện tại) — nhưng nhớ dùng `proxyMode = ScopedProxyMode.TARGET_CLASS` khi inject vào singleton.
- Lazy init toàn cục (`spring.main.lazy-initialization=true`) **giảm thời gian startup** trong dev, nhưng đánh đổi fail-fast — cân nhắc cho production.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta dùng vòng đời bean để **khởi tạo và dọn dẹp tài nguyên** cho `AuctionService` đúng chỗ.

**Nhiệm vụ Day 28:**

1. Thêm `@PostConstruct initCache()` vào `AuctionService`: nạp sẵn "giá cao nhất hiện tại" của các phiên đang mở vào một `ConcurrentHashMap` (cache).
2. Thêm `@PreDestroy flushOnShutdown()`: ghi cache xuống repository và in log dọn dẹp.
3. Viết một `BeanPostProcessor` log các bean có tên chứa "auction" qua hai pha (before/after) — quan sát thứ tự so với `@PostConstruct`.
4. Tạo bean `BidDraft` scope `prototype`, chứng minh mỗi lần lấy ra là instance mới; rồi inject nó vào một singleton và sửa bằng `ObjectProvider` cho đúng.
5. Bật `server.shutdown=graceful` trong `application.yml`, gửi SIGTERM (Ctrl+C) và quan sát `@PreDestroy` chạy.
6. Ghi `notes/day-28.md`: vẽ lại sơ đồ vòng đời bean bằng lời của bạn; trả lời "Vì sao Laravel tách `register()`/`boot()` giống Spring tách instantiate/`@PostConstruct`?".

> 🎯 Tiêu chí đạt: log vòng đời in đúng thứ tự BPP.before → init → BPP.after; cache nạp khi khởi động và flush khi tắt; bạn giải thích được bẫy "prototype trong singleton".

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Liệt kê các pha chính trong vòng đời một bean Spring.**
> **Đáp:** (1) Instantiate (gọi constructor) → (2) Populate properties / DI (tiêm phụ thuộc) → (3) Aware callbacks → (4) `BeanPostProcessor.postProcessBeforeInitialization` → (5) Initialization (`@PostConstruct` → `afterPropertiesSet()` → init-method) → (6) `BeanPostProcessor.postProcessAfterInitialization` → (7) Bean sẵn sàng → (8) Destroy (`@PreDestroy` → `destroy()` → destroy-method) khi container đóng.

**Q2: Có mấy cách khai báo init/destroy? Nên dùng cái nào?**
> **Đáp:** 3 cách: (a) annotation `@PostConstruct`/`@PreDestroy`; (b) interface `InitializingBean`/`DisposableBean`; (c) khai báo `initMethod`/`destroyMethod` trong `@Bean`. Nên dùng **annotation** (`@PostConstruct`/`@PreDestroy`) vì gọn, độc lập framework. Cách (c) dành cho class bên thứ ba không sửa được.

**Q3: Bean scope mặc định là gì? Nêu các scope khác.**
> **Đáp:** Mặc định là **singleton** (1 instance/container, sống suốt đời app). Các scope khác: **prototype** (mới mỗi lần lấy, container không quản destroy), và cho web: **request** (mỗi HTTP request), **session** (mỗi HTTP session), **application** (mỗi ServletContext).

### Mức Senior

**Q4: `BeanPostProcessor` là gì? Vì sao nó quan trọng?**
> **Đáp:** `BeanPostProcessor` là interface cho phép chen vào quá trình khởi tạo **mọi** bean, ngay trước và sau pha init (`postProcessBeforeInitialization`/`AfterInitialization`). Nó quan trọng vì chính Spring dùng nó để hiện thực các tính năng lõi: xử lý `@Autowired`/`@Value`, `@PostConstruct`/`@PreDestroy`, và đặc biệt **tạo AOP proxy** trong `postProcessAfterInitialization`. Nghĩa là bean container giữ thường là proxy do BPP trả về, không phải object gốc — nền tảng để hiểu `@Transactional`/`@Async`.

**Q5: Vì sao inject một bean prototype vào singleton thường không như kỳ vọng? Cách khắc phục?**
> **Đáp:** Vì singleton chỉ được inject **một lần lúc tạo**, nên nó giữ **mãi** một instance prototype duy nhất — mất ý nghĩa "mỗi lần một mới". Khắc phục: dùng `ObjectProvider<T>.getObject()` mỗi lần cần bản mới, hoặc `@Lookup` method injection, hoặc khai báo prototype với `proxyMode = ScopedProxyMode.TARGET_CLASS` để Spring tiêm một proxy luôn lấy instance mới.

**Q6: `@PreDestroy` có luôn được gọi khi app dừng không? Production cần lưu ý gì?**
> **Đáp:** Không phải luôn. `@PreDestroy` chỉ chạy khi container **đóng có trật tự** (`context.close()`, SIGTERM + graceful shutdown). `kill -9`, crash JVM, hoặc prototype bean sẽ **không** gọi destroy. Production nên bật `server.shutdown=graceful` + cấu hình grace period; nhưng không phụ thuộc destroy callback cho dữ liệu sống còn — hãy dùng cơ chế bền vững (commit DB) thay vì chỉ flush lúc tắt.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ lại được sơ đồ vòng đời bean (8 pha) bằng trí nhớ
- [ ] Dùng đúng `@PostConstruct`/`@PreDestroy` và biết thứ tự với các cách khác
- [ ] Hiểu `BeanPostProcessor` và vai trò của nó với AOP/`@Autowired`
- [ ] Phân biệt các scope và bẫy "prototype trong singleton"
- [ ] Hiểu lazy init và đánh đổi fail-fast
- [ ] Ánh xạ `register()`/`boot()` và deferred provider của Laravel
- [ ] Hoàn thành Mini Project Day 28 (init cache + flush + BPP log)
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Customizing the Nature of a Bean" (lifecycle callbacks)
- Spring Framework Reference — "Bean Scopes" & "BeanPostProcessor"
- Baeldung — "A Guide to the Spring Bean Lifecycle", "Spring Bean Scopes"
- Laravel Docs — "Service Providers" (`register` vs `boot`, deferred providers)
- Spring Boot Docs — "Graceful Shutdown"
