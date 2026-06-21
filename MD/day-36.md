# Day 36 - Spring Context (ApplicationContext sâu hơn)

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP — quen `config()`, `.env`, event/listener, `ServiceProvider::boot()`.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **`ApplicationContext` sâu hơn** và **`refresh()`** — chuỗi bước dựng container (sơ lược).
- Nắm **phân cấp context** (parent-child) và web application context.
- Đọc cấu hình qua `Environment` + `PropertySource` + `@Value` + `@ConfigurationProperties`.
- Dùng **Profiles** (`@Profile`, `spring.profiles.active`) để bật/tắt bean theo môi trường.
- Phát/nghe **sự kiện** với `ApplicationEvent` + `@EventListener` + `ApplicationEventPublisher`.
- Biết `ResourceLoader`, `@PropertySource`.
- Ánh xạ Laravel: `config()`, `.env`, event/listener, `ServiceProvider::boot()`.

---

## 🧠 Lý thuyết cốt lõi

### 1. `refresh()` — trái tim của việc dựng context (sơ lược)

Khi `ApplicationContext` khởi động (qua `SpringApplication.run` hoặc `new AnnotationConfigApplicationContext`), nó gọi method `refresh()`. Đây là chuỗi bước **chuẩn hóa** dựng toàn bộ container:

```
refresh()
  │
  ├─ 1. prepareRefresh()             → chuẩn bị, validate Environment
  ├─ 2. obtainBeanFactory()          → tạo BeanFactory, đọc bean definition
  ├─ 3. prepareBeanFactory()         → cấu hình BeanFactory (đăng ký BPP lõi...)
  ├─ 4. invokeBeanFactoryPostProcessors() → chạy BeanFactoryPostProcessor
  │                                     (vd: thay placeholder ${...} trong @Value)
  ├─ 5. registerBeanPostProcessors() → đăng ký các BeanPostProcessor (Day 28)
  ├─ 6. initMessageSource()          → i18n
  ├─ 7. initApplicationEventMulticaster() → bộ phát sự kiện
  ├─ 8. onRefresh()                  → (web) khởi động Tomcat nhúng
  ├─ 9. registerListeners()          → đăng ký các ApplicationListener
  ├─ 10. finishBeanFactoryInitialization() → TẠO EAGER mọi singleton (Day 26)
  └─ 11. finishRefresh()             → publish ContextRefreshedEvent, sẵn sàng
```

> 💡 Bạn không cần thuộc lòng từng bước, nhưng nên nhớ **trật tự lớn**: đọc cấu hình → đăng ký BPP → tạo singleton (eager) → phát event "đã sẵn sàng". Hiểu điều này giải thích vì sao `@Value` được thay giá trị **trước** khi bean của bạn được tạo (bước 4 chạy trước bước 10).

### 2. Phân cấp context (parent-child)

`ApplicationContext` có thể xếp **cha-con**: context con **thấy** bean của context cha, nhưng cha **không thấy** con.

```
        ┌─────────────── Root / Parent Context ───────────────┐
        │   Bean dùng chung: DataSource, ServiceLayer, Repo    │
        └──────────────────────┬──────────────────────────────┘
                               │ (con kế thừa & thấy bean cha)
        ┌──────────────────────▼──────────────────────────────┐
        │   Web/Child Context: Controller, ViewResolver        │
        └──────────────────────────────────────────────────────┘
```

Trong Spring MVC truyền thống có 2 context: **root** (service/repo, chia sẻ) và **servlet/web** (controller). Trong **Spring Boot** thường chỉ **một context phẳng** — bạn ít gặp phân cấp này trừ khi dùng `@SpringBootApplication` với nhiều child (vd Spring Cloud).

### 3. `Environment`, `PropertySource`, `@Value`, `@ConfigurationProperties`

`Environment` là abstraction gom **mọi nguồn cấu hình** lại (gọi là `PropertySource`):

```
   Environment
      ├─ PropertySource: command-line args   (ưu tiên cao nhất)
      ├─ PropertySource: biến môi trường OS
      ├─ PropertySource: application-{profile}.yml
      ├─ PropertySource: application.yml
      └─ PropertySource: @PropertySource thêm vào (ưu tiên thấp)
```

Hai cách đọc cấu hình vào bean:

**A. `@Value` — đọc từng giá trị lẻ:**

```java
@Service
public class AuctionService {
    // ${...} là placeholder; :30 là giá trị mặc định nếu không cấu hình
    @Value("${auction.default-duration-minutes:30}")
    private int defaultDurationMinutes;
}
```

**B. `@ConfigurationProperties` — gom cả nhóm cấu hình (KHUYẾN NGHỊ cho nhóm):**

```java
@ConfigurationProperties(prefix = "auction")  // ánh xạ mọi key bắt đầu bằng "auction."
public record AuctionProperties(
        int defaultDurationMinutes,   // <- auction.default-duration-minutes
        long minBidIncrement,         // <- auction.min-bid-increment
        boolean notificationsEnabled  // <- auction.notifications-enabled
) {}
```

```yaml
# application.yml
auction:
  default-duration-minutes: 60
  min-bid-increment: 10000
  notifications-enabled: true
```

> 💡 `@Value` hợp cho **một vài giá trị lẻ**; `@ConfigurationProperties` hợp cho **một nhóm cấu hình có cấu trúc** (type-safe, validate được với `@Validated`, IDE gợi ý, dễ test). Trong dự án thật, ưu tiên `@ConfigurationProperties`.

### 4. Profiles — bật/tắt bean theo môi trường

`@Profile` cho phép một bean **chỉ tồn tại** trong môi trường nhất định:

```java
@Configuration
public class NotificationConfig {

    @Bean
    @Profile("dev")     // chỉ tạo bean này khi profile "dev" đang bật
    public NotificationService consoleNotifier() {
        return new ConsoleNotificationService(); // dev: in ra console
    }

    @Bean
    @Profile("prod")    // chỉ tạo khi profile "prod"
    public NotificationService emailNotifier() {
        return new EmailNotificationService();   // prod: gửi email thật
    }
}
```

Kích hoạt profile:
- `application.yml`: `spring.profiles.active: dev`
- Biến môi trường: `SPRING_PROFILES_ACTIVE=prod`
- Tham số: `java -jar app.jar --spring.profiles.active=prod`

> 💡 Profile giúp **một codebase chạy nhiều môi trường** (dev/staging/prod) mà đổi cả bộ bean + cấu hình chỉ bằng một biến — tương đương việc Laravel đọc `.env` khác nhau theo `APP_ENV`.

### 5. ApplicationEvent — phát/nghe sự kiện trong context

Spring có cơ chế **event nội bộ** để **tách rời** (decouple) các thành phần: nơi phát không cần biết ai nghe.

```
   AuctionService.placeBid()
        │ publisher.publishEvent(new BidPlacedEvent(...))
        ▼
   ApplicationEventMulticaster  ──► @EventListener gửi thông báo
                               ──► @EventListener cập nhật thống kê
                               ──► @EventListener ghi audit
   (1 event, NHIỀU listener; nơi phát không biết gì về listener)
```

- **Phát**: inject `ApplicationEventPublisher`, gọi `publishEvent(event)`.
- **Nghe**: đánh dấu method bằng `@EventListener` nhận đúng kiểu event.
- Từ Spring 4.2, event **không bắt buộc** kế thừa `ApplicationEvent` — POJO/`record` là đủ.
- `@EventListener` mặc định **đồng bộ** (chạy cùng thread, cùng transaction). Thêm `@Async` để chạy nền; dùng `@TransactionalEventListener` để chạy sau khi commit.

### 6. `ResourceLoader` & `@PropertySource`

- **`ResourceLoader`**: nạp tài nguyên (file, classpath, URL) qua một API thống nhất:
  ```java
  Resource r = resourceLoader.getResource("classpath:auction-rules.json");
  ```
  Tiền tố: `classpath:`, `file:`, `http:`.
- **`@PropertySource`**: thêm một file `.properties` làm nguồn cấu hình ngoài luồng mặc định:
  ```java
  @Configuration
  @PropertySource("classpath:auction-extra.properties")
  public class ExtraConfig { }
  ```

---

## 🔁 Đối chiếu với Laravel/PHP

Phần này ánh xạ gần như 1-1 với những gì bạn làm hằng ngày trong Laravel:

| Khái niệm Spring | Laravel tương đương | Ghi chú |
|---|---|---|
| `ApplicationContext` | `$app` (container ứng dụng) | "Cái container tổng" |
| `Environment` / `PropertySource` | Hệ thống `config()` + `.env` | Gom nhiều nguồn cấu hình |
| `@Value("${x}")` | `config('auction.x')` / `env('X')` | Đọc một giá trị |
| `@ConfigurationProperties` | File `config/auction.php` (mảng cấu hình) | Gom cả nhóm cấu hình |
| `@Profile("prod")` | `APP_ENV=production` + `.env.production` | Đổi hành vi theo môi trường |
| `spring.profiles.active` | `APP_ENV` | Biến chọn môi trường |
| `ApplicationEventPublisher.publishEvent` | `event(new BidPlaced(...))` / `BidPlaced::dispatch(...)` | Phát sự kiện |
| `@EventListener` | Listener trong `EventServiceProvider` | Nghe sự kiện |
| `@Async` event | `ShouldQueue` listener (chạy qua queue) | Xử lý sự kiện nền |
| `ContextRefreshedEvent` | `ServiceProvider::boot()` chạy sau khi app dựng xong | "App đã sẵn sàng" |
| `ResourceLoader` | `Storage` / `resource_path()` / `File::get()` | Nạp tài nguyên |
| `@PropertySource` | Thêm file cấu hình vào `config/` | Nguồn cấu hình phụ |

Ví dụ event Laravel (rất giống Spring):

```php
// Phát
event(new BidPlaced($bid));            // ~ publisher.publishEvent(new BidPlacedEvent(bid))

// Listener (EventServiceProvider)
class SendBidNotification {
    public function handle(BidPlaced $e) {   // ~ @EventListener
        Notification::send(...);
    }
}
```

> 💡 **Bài học đối chiếu**: Bạn đã quen "phát event để tách phản ứng phụ khỏi nghiệp vụ" trong Laravel — Spring **giống hệt về tư tưởng**. Khác biệt: event Spring mặc định **đồng bộ** (cùng thread/transaction), trong khi listener Laravel có thể `ShouldQueue` để vào hàng đợi. Muốn async ở Spring, thêm `@Async` + bật `@EnableAsync`.

---

## 💻 Thực hành code

### Bước 1 — Đọc cấu hình bằng `@ConfigurationProperties`

Hãy dùng `@ConfigurationProperties` thay vì `@Value` để cấu hình gọn gàng và type-safe hơn.

```java
// File: AuctionProperties.java
package com.example.auction.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@___(prefix = "auction")  // Điền annotation ánh xạ key "auction.*"
public ___ AuctionProperties( // Điền từ khóa khai báo record
        int defaultDurationMinutes,
        long minBidIncrement,
        boolean notificationsEnabled
) {}
```

```java
// File: AuctionApplication.java — bật binding cho properties
package com.example.auction;

import com.example.auction.config.AuctionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@___(AuctionProperties.class) // Điền annotation đăng ký properties làm bean
public ___ AuctionApplication { // Điền từ khóa khai báo lớp
    public static void main(String[] args) {
        SpringApplication.run(AuctionApplication.class, args);
    }
}
```

```yaml
# application.yml
spring:
  profiles:
    active: dev
auction:
  default-duration-minutes: 60
  min-bid-increment: 10000
  notifications-enabled: true
```

### Bước 2 — Định nghĩa & phát `BidPlacedEvent`

Tạo event và phát nó đi sau khi lưu vào DB.

```java
// File: BidPlacedEvent.java — event là một record (POJO, không cần kế thừa ApplicationEvent)
package com.example.auction.event;

public ___ BidPlacedEvent(long auctionId, String bidder, long amount, java.time.Instant at) {} // Điền từ khóa khai báo record
```

```java
// File: BidService.java — phát event sau khi đặt giá thành công
package com.example.auction;

import com.example.auction.event.BidPlacedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import java.time.Instant;

@___ // Điền annotation đánh dấu Service
public ___ BidService { // Điền từ khóa khai báo lớp
    private final BidRepository bidRepository;
    private final ___ publisher; // Điền interface dùng để phát sự kiện

    public BidService(BidRepository bidRepository, ApplicationEventPublisher publisher) {
        this.bidRepository = bidRepository;
        this.publisher = publisher;
    }

    public Bid placeBid(long auctionId, String bidder, long amount) {
        Bid bid = ___ Bid(auctionId, bidder, amount); // Điền từ khóa tạo mới đối tượng
        bidRepository.save(bid);
        
        // Điền method phát sự kiện: nơi phát KHÔNG cần biết ai nghe (decouple)
        publisher.___(new BidPlacedEvent(auctionId, bidder, amount, Instant.now()));
        return bid;
    }
}
```

### Bước 3 — Nghe event bằng `@EventListener`

```java
// File: BidNotificationListener.java
package com.example.auction.listener;

import com.example.auction.event.BidPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@___ // Điền annotation đánh dấu Component
public ___ BidNotificationListener { // Điền từ khóa khai báo lớp

    @___  // Điền annotation lắng nghe sự kiện
    public void onBidPlaced(BidPlacedEvent event) {
        System.out.println("[notify] Gửi thông báo: phiên " + event.auctionId()
                + " có bid mới " + event.amount() + " từ " + event.bidder());
    }
}
```

```java
// File: BidStatsListener.java — listener thứ 2, cùng một event (1 event -> nhiều listener)
package com.example.auction.listener;

import com.example.auction.event.BidPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@___ // Điền annotation đánh dấu Component
public ___ BidStatsListener { // Điền từ khóa khai báo lớp

    @___          // Điền annotation chạy hàm ở thread nền (cần @EnableAsync ở config)
    @EventListener
    public void updateStats(BidPlacedEvent event) {
        System.out.println("[stats] Thread: " + Thread.currentThread().getName() + " - Cập nhật thống kê cho phiên " + event.auctionId());
    }
}
```

### Bước 4 — Profile cho notifier dev/prod

```java
// File: NotificationConfig.java
package com.example.auction.config;

import org.springframework.context.annotation.*;

@___ // Điền annotation cấu hình
public ___ NotificationConfig { // Điền từ khóa khai báo lớp

    @Bean
    @___("dev")   // Điền annotation chỉ tạo Bean này khi môi trường là "dev"
    public String notifierMode() { return "DEV: in console"; }

    @Bean
    @___("prod")  // Điền annotation chỉ tạo Bean này khi môi trường là "prod"
    public String notifierModeProd() { return "PROD: gửi email thật"; }
}
```

> ✅ **Bài tập tự giải thích:** Đổi `spring.profiles.active` trong `application.yml` từ `dev` sang `prod`, quan sát bean nào được tạo ra và log hiển thị gì. Vì sao `BidStatsListener` (`@Async`) chạy trên thread khác còn `BidNotificationListener` chạy cùng thread với `placeBid`?

### Bước 5 — CHALLENGE: Gửi thông báo chỉ khi lưu thành công (Thử thách)

> 🏆 Yêu cầu:
> 1. Hiện tại, `@EventListener` trong `BidNotificationListener` chạy **ngay lập tức** khi sự kiện được phát ra. Nếu hàm `placeBid` ném lỗi và transaction bị rollback ở những dòng code phía sau, thông báo vẫn bị gửi đi -> Lỗi Logic!
> 2. Hãy đổi annotation của hàm `onBidPlaced` thành `@TransactionalEventListener`.
> 3. Trong `BidService`, cấu hình Transactional và thêm đoạn code ném lỗi ngay sau khi phát Event để mô phỏng một rollback.
> 4. Chạy `curl` test. Quan sát log. Bạn sẽ thấy log thông báo của `@TransactionalEventListener` sẽ KHÔNG bị in ra nếu xảy ra rollback. Tại sao?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Quên `@EnableConfigurationProperties` (hoặc `@ConfigurationPropertiesScan`).** Bean `@ConfigurationProperties` không được tạo → inject lỗi. Hoặc đánh dấu trực tiếp bằng `@Component`.
- **`@Value` với key không tồn tại và không có default.** App **chết lúc startup** vì không resolve được `${...}`. Luôn cho default (`${x:mặc-định}`) hoặc chắc chắn key tồn tại.
- **Listener đồng bộ làm chậm luồng chính.** `@EventListener` mặc định đồng bộ — nếu nó gọi I/O chậm (gửi email), luồng `placeBid` bị chặn. Dùng `@Async` (nhớ `@EnableAsync`) cho phản ứng phụ nặng.
- **`@Async` listener mất transaction/context.** Chạy thread khác → không thấy transaction của luồng phát. Nếu cần dữ liệu đã commit, dùng `@TransactionalEventListener(phase = AFTER_COMMIT)`.
- **Event publish trong transaction nhưng listener đồng bộ ném exception → rollback cả nghiệp vụ chính.** Cân nhắc phase commit hoặc tách async.
- **Nhầm thứ tự ưu tiên PropertySource.** Command-line args & biến môi trường **ghi đè** `application.yml`. Đừng ngạc nhiên khi giá trị trong file bị override lúc deploy.
- **Profile sai tên** (`prod` vs `production`) → bean không được tạo, không báo lỗi rõ ràng. Kiểm tra log "The following profiles are active".
- **Dùng `@Profile` cho logic nghiệp vụ phức tạp.** Profile hợp cho cấu hình hạ tầng (notifier, datasource); đừng nhét quá nhiều nhánh nghiệp vụ vào profile.

---

## 🚀 Liên hệ Spring Boot / Production

- Spring Boot mở rộng `Environment` với **thứ tự ưu tiên rõ ràng** (config tree, env vars, args, `application-{profile}.yml`...) — nền tảng cho cấu hình 12-factor app.
- `@ConfigurationProperties` + `@Validated` cho phép **fail-fast** khi cấu hình sai (vd `min-bid-increment` âm) — app không khởi động, an toàn hơn lỗi runtime.
- Event nội bộ là cách tốt để **tách module** trong monolith (gửi mail, cập nhật search index, ghi audit khi có bid) mà không tạo phụ thuộc cứng — bước đệm trước khi tách microservice.
- `@TransactionalEventListener(AFTER_COMMIT)` cực quan trọng trong production: chỉ gửi thông báo/đẩy message khi transaction đã commit thành công, tránh "gửi mail rồi rollback".
- Actuator `/actuator/env` và `/actuator/configprops` giúp **soi cấu hình thực tế** đang chạy và giá trị properties đã bind — vô giá khi debug "vì sao prod đọc sai config".
- Profile + cấu hình ngoài (config server, Kubernetes ConfigMap/Secret) là cách chuẩn để **một image chạy mọi môi trường**.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta **decouple thông báo khỏi nghiệp vụ** bằng event, và đọc luật đấu giá từ cấu hình type-safe.

**Nhiệm vụ Day 36:**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. Thêm `ApplicationEventPublisher` vào `BidService` và phát `BidPlacedEvent` khi lưu xong Bid. (Bước 2)
3. Viết 2 Listener: một cái in log đồng bộ, một cái in log async chạy trên thread nền (Bước 3). Gọi `curl` tạo Bid và quan sát 2 log chạy từ 2 thread khác nhau.
4. Tạo bean cấu hình Notifier theo Profile `dev` và `prod` (Bước 4). Đổi profile trong `application.yml` và xem log khác biệt.
5. Hoàn thành **CHALLENGE** ở Bước 5: Thử nghiệm đổi `@EventListener` thành `@TransactionalEventListener` và tạo rollback giả. Gửi `curl` và xác nhận log không in ra khi transaction thất bại.
6. Ghi `notes/day-31.md`: đối chiếu event Spring với `event()`/Listener của Laravel; giải thích vì sao đọc cấu hình bằng `@ConfigurationProperties` tốt hơn `@Value` rải rác.

> 🎯 Tiêu chí đạt: Đặt bid kích hoạt nhiều listener. Async listener chạy trên thread khác. Đổi profile đổi được log. `@TransactionalEventListener` ngăn chặn thông báo sai lệch khi rollback.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `Environment` trong Spring là gì? Liên quan gì tới `@Value`?**
> **Đáp:** `Environment` là abstraction gom **mọi nguồn cấu hình** (gọi là `PropertySource`: command-line args, biến môi trường, `application.yml`, `@PropertySource`...) theo thứ tự ưu tiên. `@Value("${key}")` đọc giá trị từ `Environment` — Spring thay placeholder `${...}` bằng giá trị tìm được (hoặc default sau dấu `:`). Tương đương `config()`/`.env` của Laravel.

**Q2: `@Value` và `@ConfigurationProperties` khác nhau thế nào? Nên dùng cái nào?**
> **Đáp:** `@Value` đọc **từng giá trị lẻ** (tiện cho 1-2 giá trị). `@ConfigurationProperties` **gom cả nhóm** key cùng prefix vào một object/record, **type-safe**, hỗ trợ validate (`@Validated`), IDE gợi ý, dễ test. Với nhóm cấu hình có cấu trúc, nên dùng `@ConfigurationProperties`; `@Value` chỉ cho giá trị đơn lẻ.

**Q3: Profile là gì? Kích hoạt thế nào?**
> **Đáp:** Profile cho phép bật/tắt bean theo môi trường: bean gắn `@Profile("prod")` chỉ tồn tại khi profile "prod" active. Kích hoạt qua `spring.profiles.active` trong `application.yml`, biến môi trường `SPRING_PROFILES_ACTIVE`, hoặc tham số `--spring.profiles.active=prod`. Giúp một codebase chạy nhiều môi trường — tương đương `APP_ENV` + `.env.{env}` của Laravel.

### Mức Senior

**Q4: Cơ chế ApplicationEvent hoạt động thế nào và giải quyết vấn đề gì?**
> **Đáp:** Đây là mẫu **publish-subscribe trong context** để **tách rời (decouple)** thành phần: nơi phát gọi `ApplicationEventPublisher.publishEvent(event)` mà không cần biết ai nghe; các method `@EventListener` nhận đúng kiểu event sẽ phản ứng. Một event có thể có nhiều listener. Giải quyết vấn đề coupling cứng (vd `placeBid` không phải gọi trực tiếp `emailService`, `statsService`...). Từ Spring 4.2, event không cần kế thừa `ApplicationEvent` (POJO/record là đủ).

**Q5: `@EventListener` mặc định đồng bộ hay bất đồng bộ? Khi nào dùng `@Async` và `@TransactionalEventListener`?**
> **Đáp:** Mặc định **đồng bộ** — listener chạy **cùng thread, cùng transaction** với nơi phát, nên nếu nó ném exception có thể làm rollback nghiệp vụ chính. Dùng `@Async` (cần `@EnableAsync`) để đẩy listener sang thread pool, tránh chặn luồng chính cho phản ứng phụ nặng (gửi mail). Dùng `@TransactionalEventListener(phase = AFTER_COMMIT)` khi chỉ muốn phản ứng **sau khi transaction commit thành công** — tránh "gửi thông báo rồi rollback".

**Q6: `refresh()` làm gì và vì sao `@Value` được resolve trước khi bean được tạo?**
> **Đáp:** `refresh()` là chuỗi bước dựng `ApplicationContext`: chuẩn bị Environment → tạo BeanFactory & đọc bean definition → chạy `BeanFactoryPostProcessor` (thay placeholder `${...}`) → đăng ký `BeanPostProcessor` → init MessageSource/event multicaster → tạo **eager** mọi singleton → publish `ContextRefreshedEvent`. Vì việc resolve placeholder (`PropertySourcesPlaceholderConfigurer`, một `BeanFactoryPostProcessor`) chạy **trước** bước tạo singleton, nên khi bean của bạn được khởi tạo, các `@Value` đã có sẵn giá trị thật.

---

## ✅ Checklist hoàn thành

- [ ] Hiểu sơ lược `refresh()` và trật tự dựng context
- [ ] Hiểu phân cấp context (parent-child) và context web vs root
- [ ] Đọc cấu hình bằng `@Value` và `@ConfigurationProperties`, biết khi nào dùng cái nào
- [ ] Dùng Profiles để bật/tắt bean theo môi trường
- [ ] Phát event bằng `ApplicationEventPublisher` và nghe bằng `@EventListener`
- [ ] Phân biệt listener đồng bộ / `@Async` / `@TransactionalEventListener`
- [ ] Ánh xạ sang `config()`, `.env`, event/listener, `boot()` của Laravel
- [ ] Dùng `@ConfigurationProperties` đọc config từ YAML
- [ ] Cấu hình EventPublisher và @EventListener đa luồng
- [ ] Dùng `@Profile` để chuyển đổi config
- [ ] Hoàn thành Challenge: Dùng `@TransactionalEventListener` tránh side-effect khi rollback
- [ ] Hoàn thành Mini Project Day 36
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Additional Capabilities of the ApplicationContext", "Standard and Custom Events", "Environment Abstraction"
- Spring Boot Docs — "Externalized Configuration", "Profiles", "@ConfigurationProperties"
- Baeldung — "Spring Events", "Guide to @ConfigurationProperties", "Spring Profiles"
- Laravel Docs — "Configuration", "Events" (đối chiếu `config()`/`event()`/Listener)
- Sách *Spring in Action* (Craig Walls) — chương về cấu hình và sự kiện ứng dụng
