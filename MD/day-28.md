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

### Bước 1 — Cache warm-up thực tế

Trong môi trường thực tế, ta thường dùng `@PostConstruct` để "warm-up" (nạp sẵn dữ liệu vào bộ nhớ) và `@PreDestroy` để dọn dẹp.

```java
// File: AuctionService.java
package com.example.auction;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;

@Service
public class AuctionService {

    private final BidRepository bidRepository;
    private Map<Long, String> auctionCache; // cache lưu tên phiên đấu giá

    public AuctionService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
        // ⚠️ KHÔNG nạp cache ở đây — vì repository có thể chứa bean chưa sẵn sàng
    }

    @___ // Gọi SAU khi DI xong: bean đã an toàn để dùng
    public void initCache() {
        this.auctionCache = ___ ConcurrentHashMap<>(); // Điền từ khóa khởi tạo đối tượng
        // Giả lập lấy dữ liệu từ DB
        auctionCache.put(1L, "Đấu giá iPhone 15");
        auctionCache.put(2L, "Đấu giá MacBook Pro");
        System.out.println("[init] Đã nạp cache với " + auctionCache.size() + " phiên");
    }

    @___ // Gọi khi tắt app
    public void flushOnShutdown() {
        System.out.println("[destroy] Dọn dẹp tài nguyên và lưu trạng thái xuống DB...");
    }
    
    public ___<String> getActiveAuctions() { // Điền kiểu trả về danh sách
        return ___.copyOf(auctionCache.values()); // Điền class tạo danh sách
    }
}
```

### Bước 2 — Tạo endpoint kiểm tra (Debug Endpoints)

Ta sẽ tạo các endpoint để chứng minh container đang quản lý những gì.

```java
// File: DebugController.java
package com.example.auction;

import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final ApplicationContext context;

    // Inject chính ApplicationContext vào để khám phá container
    public DebugController(ApplicationContext context) {
        this.context = context;
    }

    @GetMapping("/beans")
    public ___<String> getBeans() { // Điền kiểu trả về danh sách
        // Lấy tất cả tên bean có chứa chữ "auction"
        return Arrays.stream(context.___) // Điền hàm lấy tên các bean definition (getBeanDefinitionNames)
                .filter(name -> name.contains("auction"))
                .toList();
    }
}
```

Kiểm tra bằng lệnh:
```bash
curl http://localhost:8080/api/debug/beans
# Expected: ["auctionController", "auctionService", "auctionApiApplication"...]
```

### Bước 3 — Custom `BeanPostProcessor` ghi log vòng đời

```java
// File: LifecycleLoggingPostProcessor.java
package com.example.auction;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

@Component  // Spring tự nhận diện BeanPostProcessor
public class LifecycleLoggingPostProcessor ___ BeanPostProcessor { // Điền từ khóa triển khai interface

    @___
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (beanName.toLowerCase().contains("auction")) {
            System.out.println("[BPP.before] sắp init bean: " + beanName);
        }
        return bean; // trả về bean (có thể bọc proxy ở đây nếu muốn)
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (beanName.toLowerCase().contains("auction")) {
            System.out.println("[BPP.after]  đã init xong bean: " + beanName);
        }
        return bean;
    }
}
```

Chạy app và xem console, bạn sẽ thấy log in đúng thứ tự: `BPP.before` -> `init` -> `BPP.after`.

### Bước 4 — CHALLENGE: Bẫy Prototype trong Singleton (Thử thách)

> 🏆 Yêu cầu:
> 1. Tạo class `BidDraft` đánh dấu `@Service` và `@Scope("prototype")`. Trong constructor của nó, in ra dòng `"Tạo BidDraft mới " + hashCode()`.
> 2. Bổ sung endpoint `GET /api/debug/prototype` trong `DebugController`. Gọi nó 3 lần, mỗi lần yêu cầu một instance của `BidDraft` bằng cách gọi `context.getBean(BidDraft.class)`. Bạn sẽ thấy 3 mã hash khác nhau.
> 3. **Thử thách khó:** Bây giờ, thay vì lấy từ context, hãy Inject `BidDraft` vào `AuctionService` (vốn là singleton) thông qua constructor. Viết 1 hàm trong `AuctionService` gọi phương thức nào đó trên `BidDraft` này.
> 4. Chạy lại và gọi hàm đó 3 lần. Tại sao mã hash lại GIỐNG HỆT nhau, dù `BidDraft` là prototype?
> 5. **Hãy tự sửa lỗi trên** bằng cách dùng `ObjectProvider<BidDraft>` thay vì inject trực tiếp `BidDraft`.

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

> Hôm nay ta dùng vòng đời bean để **khởi tạo và dọn dẹp tài nguyên** cho `AuctionService`, đồng thời khám phá ruột container.

**Nhiệm vụ Day 28:**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. Cập nhật `AuctionService`: thêm `@PostConstruct` nạp sẵn 5 phiên đấu giá vào `auctionCache` (ConcurrentHashMap) và thêm `@PreDestroy` in log khi tắt app. Cập nhật endpoint `GET /api/auctions` để lấy dữ liệu từ cache này thay vì hardcode ở controller.
3. Tạo `DebugController` expose `GET /api/debug/beans` sử dụng `ApplicationContext`.
4. Viết `LifecycleLoggingPostProcessor` (BPP) log các bean có tên chứa "auction" qua hai pha (before/after).
5. Hoàn thành **CHALLENGE** ở Bước 4: Khám phá bẫy prototype bên trong singleton và dùng `ObjectProvider` để khắc phục. Expose endpoint `/api/debug/prototype-test` để kiểm tra.
6. Bật `server.shutdown=graceful` trong `application.yml`, gửi SIGTERM (Ctrl+C) và quan sát `@PreDestroy` chạy.
7. Ghi `notes/day-28.md`: vẽ lại sơ đồ vòng đời bean bằng lời của bạn; trả lời "Vì sao Laravel tách `register()`/`boot()` giống Spring tách instantiate/`@PostConstruct`?".

> 🎯 Tiêu chí đạt: App chạy in log đúng thứ tự BPP -> Init -> BPP. `/api/debug/beans` hoạt động qua curl. Bẫy prototype được giải quyết và chứng minh bằng lệnh curl liên tiếp nhận các hash khác nhau.

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
- [ ] Dựng endpoint lấy danh sách bean và kiểm tra bằng `curl`
- [ ] Hoàn thành Challenge: Sửa bẫy prototype bằng `ObjectProvider`
- [ ] Hoàn thành Mini Project Day 28
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Customizing the Nature of a Bean" (lifecycle callbacks)
- Spring Framework Reference — "Bean Scopes" & "BeanPostProcessor"
- Baeldung — "A Guide to the Spring Bean Lifecycle", "Spring Bean Scopes"
- Laravel Docs — "Service Providers" (`register` vs `boot`, deferred providers)
- Spring Boot Docs — "Graceful Shutdown"
