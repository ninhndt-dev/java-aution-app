# Day 33 - Boot Startup

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu chính xác điều gì xảy ra từ lúc gõ `java -jar` đến khi app sẵn sàng nhận request.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm **trình tự đầy đủ** mà `SpringApplication.run()` thực hiện.
- Hiểu **embedded server** (Tomcat/Netty/Jetty nhúng) — vì sao app Spring Boot tự "host" được mà không cần WAR.
- Biết các "móc nối" (hook) lúc khởi động: `SpringApplicationRunListener`, `ApplicationContextInitializer`, `ApplicationListener`.
- Phân biệt `CommandLineRunner` vs `ApplicationRunner` và dùng `@Order` để sắp thứ tự.
- Biết bật **lazy initialization** toàn cục và **đo thời gian khởi động**.
- Nắm khái niệm **AOT / GraalVM native image** ở mức tổng quan (teaser).
- Đối chiếu với quá trình bootstrap của Laravel (`artisan serve`, `bootstrap/app.php`, HTTP Kernel).

---

## 🧠 Lý thuyết cốt lõi

### 1. Toàn cảnh: `java -jar app.jar` rồi sao nữa?

```
java -jar auction-api.jar
        │
        ▼
JarLauncher (của Spring Boot)  ──► thiết lập classpath từ BOOT-INF/lib
        │
        ▼
Gọi main() của bạn  ──► SpringApplication.run(AuctionApiApplication.class, args)
        │
        ▼
┌──────────────────── SpringApplication.run() ─────────────────────┐
│ 1. Tạo & chuẩn bị Environment (đọc application.yml, env, args)    │
│ 2. In banner                                                      │
│ 3. Suy luận loại ứng dụng (NONE / SERVLET / REACTIVE)             │
│ 4. Tạo ApplicationContext phù hợp                                 │
│ 5. Chạy ApplicationContextInitializer                            │
│ 6. refresh() context  ──► tạo bean, auto-config, khởi động server│
│ 7. Gọi CommandLineRunner / ApplicationRunner                     │
│ 8. Phát sự kiện ApplicationReadyEvent                            │
└──────────────────────────────────────────────────────────────────┘
        │
        ▼
App SẴN SÀNG nhận HTTP request
```

### 2. Bóc tách `SpringApplication.run()` theo trình tự

Khi gọi `SpringApplication.run(...)`, bên trong diễn ra (đơn giản hóa):

1. **Tạo `SpringApplication`** và **suy luận loại ứng dụng** (`WebApplicationType`):
   - `SERVLET` nếu có Spring MVC + servlet trên classpath.
   - `REACTIVE` nếu có WebFlux (Netty).
   - `NONE` nếu là app dòng lệnh thuần (không web).
2. **Tải các listener khởi động** (`SpringApplicationRunListener`) — chúng phát các sự kiện ở mỗi mốc.
3. **Chuẩn bị `Environment`**: gom property từ `application.yml/properties`, biến môi trường, tham số dòng lệnh, profile đang active. Phát sự kiện `ApplicationEnvironmentPreparedEvent`.
4. **In banner** (cái logo Spring ASCII bạn hay thấy).
5. **Tạo `ApplicationContext`** đúng loại (ví dụ `AnnotationConfigServletWebServerApplicationContext` cho app web servlet).
6. **Chạy `ApplicationContextInitializer`** để tùy biến context trước khi nó refresh.
7. **`refresh()` context** — bước nặng nhất:
   - Quét component, nạp định nghĩa bean.
   - Chạy **Auto Configuration** (Day 32).
   - Khởi tạo các **singleton bean** (eager mặc định).
   - **Khởi động embedded server** (Tomcat bind cổng 8080...). Đây là lúc cổng được mở.
8. **Gọi runner**: tất cả `CommandLineRunner` và `ApplicationRunner` (theo `@Order`).
9. **Phát `ApplicationReadyEvent`** — app chính thức sẵn sàng.

> 💡 Mốc quan trọng cần nhớ cho production: **server bind cổng ở bước refresh (7)**, nhưng app chỉ thực sự "ready" sau bước 9. Đó là lý do **readiness probe** nên đợi `ApplicationReadyEvent`, không chỉ đợi cổng mở.

### 3. Embedded server — vì sao không cần WAR + Tomcat ngoài?

Trước Spring Boot, bạn đóng gói app thành file `.war` rồi deploy vào một Tomcat/JBoss cài sẵn trên server. Spring Boot **đảo ngược**: nhúng luôn server vào trong app.

```
Cách CŨ:  app.war  ──deploy──►  Tomcat cài sẵn trên server (chạy nhiều app)
Cách MỚI: app.jar (đã chứa Tomcat nhúng)  ──►  java -jar  ──►  app TỰ host chính nó
```

- `spring-boot-starter-web` → mặc định kéo theo **Tomcat nhúng**.
- `spring-boot-starter-webflux` → kéo theo **Netty** (non-blocking).
- Có thể đổi sang **Jetty/Undertow** bằng cách loại Tomcat và thêm starter tương ứng.

Lợi ích: mỗi app là một process độc lập, "self-contained" → hợp với Docker, Kubernetes, 12-factor app (mỗi container chạy đúng một process).

> ⚠️ Vì server nằm trong app, **cấu hình server giờ là property của app** (`server.port`, `server.tomcat.threads.max`...) chứ không phải config của Tomcat bên ngoài. Đây là thay đổi tư duy lớn khi tới từ thế giới deploy WAR.

### 4. Các hook lúc khởi động

| Hook | Khi nào chạy | Dùng để |
|---|---|---|
| `ApplicationContextInitializer` | **Trước** khi context refresh | Tùy biến context cấp thấp (hiếm dùng trong app thường). |
| `SpringApplicationRunListener` | Xuyên suốt vòng đời startup | Phát/nghe các sự kiện mốc startup (Spring tự dùng nội bộ). |
| `ApplicationListener<ApplicationReadyEvent>` | Sau khi app ready | Chạy việc cần làm khi app đã sẵn sàng. |
| `CommandLineRunner` / `ApplicationRunner` | Ngay sau khi context refresh, trước ready event | Seed dữ liệu, warm-up cache, kiểm tra kết nối... |

### 5. `CommandLineRunner` vs `ApplicationRunner`

Cả hai chạy **một lần** ngay sau khi context khởi tạo xong. Khác biệt duy nhất là **kiểu tham số tham số dòng lệnh nhận vào**:

```java
// CommandLineRunner: nhận mảng String[] args THÔ
@Component
public class SeedRunner implements CommandLineRunner {
    @Override
    public void run(String... args) {
        // args là mảng thô, ví dụ ["--seed", "dev"]
    }
}

// ApplicationRunner: nhận ApplicationArguments đã PARSE sẵn (tách option/non-option)
@Component
public class SeedRunner2 implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        boolean seed = args.containsOption("seed");       // --seed
        List<String> values = args.getOptionValues("env"); // --env=dev
    }
}
```

Dùng `@Order` (số nhỏ chạy trước) để sắp thứ tự nhiều runner:

```java
@Component
@Order(1)   // chạy trước
public class MigrationCheckRunner implements CommandLineRunner { ... }

@Component
@Order(2)   // chạy sau
public class SeedDataRunner implements CommandLineRunner { ... }
```

> 💡 Chọn `ApplicationRunner` khi bạn cần đọc tham số dòng lệnh dạng `--key=value` một cách có cấu trúc; chọn `CommandLineRunner` cho việc đơn giản không quan tâm parse.

### 6. Lazy initialization toàn cục

Mặc định Spring khởi tạo **tất cả singleton bean ngay lúc khởi động** (eager) — an toàn (phát hiện lỗi cấu hình sớm) nhưng làm khởi động chậm. Bật lazy toàn cục:

```yaml
spring:
  main:
    lazy-initialization: true
```

Khi đó bean chỉ được tạo **lần đầu được dùng tới**.

> ⚠️ Lazy giúp khởi động nhanh nhưng có cái giá: lỗi cấu hình bean **không lộ lúc startup** mà nổ lúc request đầu tiên; request đầu cũng chậm hơn. Thường chỉ bật trong môi trường dev hoặc kết hợp có chọn lọc (`@Lazy` trên bean cụ thể).

### 7. Đo thời gian khởi động & teaser AOT/GraalVM

Spring Boot tự log thời gian khởi động:
```
Started AuctionApiApplication in 2.317 seconds (process running for 2.78)
```
Bật log chi tiết từng bước:
```yaml
logging:
  level:
    org.springframework.boot.autoconfigure: DEBUG
debug: true   # in cả Condition Evaluation Report (Day 32)
```

**AOT (Ahead-Of-Time) & GraalVM native image (teaser):** Spring Boot 3 hỗ trợ biên dịch app thành **native executable** qua GraalVM. Phần lớn quyết định (auto-config, proxy, reflection) được "đóng băng" lúc **build-time** thay vì runtime → khởi động **dưới 100ms** và tốn ít RAM hơn nhiều. Đánh đổi: thời gian build lâu, một số tính năng động (reflection runtime) cần khai báo hint. Đây là hướng đi mạnh cho serverless/scale-to-zero — sẽ đào sâu ở phần sau.

---

## 🔁 Đối chiếu với Laravel/PHP

| Giai đoạn | Laravel/PHP | Spring Boot |
|---|---|---|
| Điểm vào | `public/index.php` mỗi request | `main()` → `SpringApplication.run()` **một lần** |
| Bootstrap framework | `bootstrap/app.php` tạo `$app`, đăng ký kernel | `SpringApplication` tạo `ApplicationContext` |
| Đăng ký dịch vụ | `ServiceProvider::register()` (lazy, mỗi request) | Tạo singleton bean (eager, một lần lúc startup) |
| "Boot" sau đăng ký | `ServiceProvider::boot()` | `CommandLineRunner` / `@PostConstruct` / `ApplicationReadyEvent` |
| Server | Nginx/Apache + php-fpm (ngoài app) | **Embedded** Tomcat/Netty (trong app) |
| Vòng đời | App "sinh ra rồi chết" mỗi request | App **chạy liên tục**, giữ state trong RAM |
| Lệnh chạy dev | `php artisan serve` | `./mvnw spring-boot:run` |
| Tác vụ chạy 1 lần lúc khởi động | Không có khái niệm tương đương trực tiếp (thường dùng command/scheduler) | `CommandLineRunner` / `ApplicationRunner` |

**Khác biệt tư duy quan trọng nhất:**
- Ở **Laravel**, mỗi request **bootstrap lại từ đầu** — đăng ký provider, build container, rồi vứt đi khi request xong. Khởi động "rẻ" nhưng lặp lại liên tục, nên Laravel có OPcache + lazy provider để giảm chi phí.
- Ở **Spring Boot**, bootstrap **chỉ chạy một lần** lúc khởi động. Sau đó container (`ApplicationContext`) và toàn bộ singleton bean **sống suốt vòng đời**. Vì vậy thời gian khởi động được "trả góp" một lần, nhưng bạn phải quan tâm tới state tích lũy, memory, thread.

> 🧩 `CommandLineRunner` là "cái Laravel không có": một hook chạy đúng một lần lúc app sống dậy — hoàn hảo để seed dữ liệu, kiểm tra kết nối DB, warm-up cache.

---

## 💻 Thực hành code

### Bài 1 — Một `CommandLineRunner` đơn giản để seed dữ liệu

```java
package com.example.auction.config;

import com.example.auction.repository.AuctionRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)                 // chạy sau các runner @Order(1)
@Profile("dev")           // CHỈ seed ở môi trường dev, không seed production
public class AuctionSeedRunner implements CommandLineRunner {

    private final AuctionRepository auctionRepository;

    public AuctionSeedRunner(AuctionRepository auctionRepository) {
        this.auctionRepository = auctionRepository;
    }

    @Override
    public void run(String... args) {
        if (auctionRepository.count() > 0) {
            return;  // đã có dữ liệu thì bỏ qua, tránh seed trùng
        }
        // ... tạo vài phiên đấu giá mẫu ...
        System.out.println(">> Đã seed dữ liệu phiên đấu giá mẫu.");
    }
}
```

### Bài 2 — `ApplicationRunner` đọc tham số dòng lệnh có cấu trúc

```java
package com.example.auction.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)   // chạy trước AuctionSeedRunner
public class StartupInfoRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        // Chạy với: java -jar app.jar --reset-data --env=dev
        boolean reset = args.containsOption("reset-data");
        var env = args.getOptionValues("env");   // List<String> hoặc null
        System.out.println(">> reset-data = " + reset + ", env = " + env);
    }
}
```

### Bài 3 — Nghe `ApplicationReadyEvent` (chạy khi app thực sự sẵn sàng)

```java
package com.example.auction.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReadyLogger {

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        // Khác CommandLineRunner ở chỗ: chắc chắn server đã mở cổng và app ready.
        System.out.println(">> Auction API đã sẵn sàng nhận request.");
    }
}
```

### Bài 4 — Custom banner

Tạo file `src/main/resources/banner.txt`:

```
   _              _   _
  / \  _   _  ___| |_(_) ___  _ __
 / _ \| | | |/ __| __| |/ _ \| '_ \
/ ___ \ |_| | (__| |_| | (_) | | | |
/_/   \_\__,_|\___|\__|_|\___/|_| |_|
:: Auction API ::   (v${application.version})
```

Tắt banner nếu muốn:
```yaml
spring:
  main:
    banner-mode: off
```

### Bài 5 — Đo và rút ngắn thời gian khởi động

```bash
# Đọc dòng "Started ... in X seconds" trong log
./mvnw spring-boot:run

# Thử bật lazy init để so sánh thời gian
./mvnw spring-boot:run -Dspring-boot.run.arguments=--spring.main.lazy-initialization=true
```

> ✅ **Bài tập tự giải thích:** Vì sao nên dùng `@EventListener(ApplicationReadyEvent.class)` thay vì `CommandLineRunner` để mở traffic/đăng ký service vào service-discovery?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Đặt logic nặng vào `CommandLineRunner` không kiểm tra dữ liệu trùng** → mỗi lần khởi động lại seed thêm bản ghi, làm phình DB. Luôn kiểm tra `count()` hoặc dùng idempotent.
- **Seed dữ liệu test ở production** vì quên `@Profile("dev")`. Hậu quả nghiêm trọng: dữ liệu giả lẫn vào DB thật.
- **Nhầm "cổng mở" = "app ready".** Cổng có thể đã bind nhưng runner/khởi tạo cache còn đang chạy → request đầu lỗi. Dùng `ApplicationReadyEvent` cho readiness.
- **Bật `lazy-initialization=true` ở production mà không cân nhắc** → lỗi cấu hình bean không lộ lúc startup mà nổ lúc request đầu, khó debug.
- **Ném exception trong `CommandLineRunner`** → mặc định làm **app dừng khởi động** (exit). Đôi khi đó là điều bạn muốn (fail-fast), nhưng phải biết hệ quả.
- **Đóng gói nhầm thành WAR rồi lại deploy vào Tomcat ngoài + vẫn nhúng Tomcat** → xung đột. Với Spring Boot, mặc định cứ dùng fat JAR + embedded server.

---

## 🚀 Liên hệ Spring Boot / Production

- **Liveness vs Readiness probe (Kubernetes):** Spring Boot Actuator cung cấp `/actuator/health/liveness` và `/actuator/health/readiness`. Readiness chỉ "UP" sau khi app thật sự sẵn sàng (sau `ApplicationReadyEvent`) → K8s mới route traffic vào. Đây là cách dùng đúng các mốc startup ở trên.
- **Graceful shutdown:** bật `server.shutdown=graceful` để khi nhận tín hiệu dừng, app **ngừng nhận request mới nhưng hoàn tất request đang xử lý** rồi mới tắt — quan trọng khi rolling update.
- **Warm-up:** vì JVM cần JIT warm-up (Day 01), nhiều team dùng `CommandLineRunner`/`ApplicationReadyEvent` để gọi vài request giả hoặc nạp trước cache, giảm độ trễ request thật đầu tiên.
- **Native image (GraalVM):** với serverless/Lambda nơi cold-start quan trọng, native image rút khởi động từ vài giây xuống vài chục mili-giây — bù lại build phức tạp hơn.
- **Đổi embedded server:** cần I/O non-blocking quy mô lớn → WebFlux + Netty; cần tối ưu throughput servlet → cân nhắc Undertow.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta dùng các hook startup để **seed dữ liệu phiên đấu giá mẫu** lúc khởi động, phục vụ phát triển và test.

**Nhiệm vụ Day 33:**
1. Tạo `AuctionSeedRunner implements CommandLineRunner`, gắn `@Profile("dev")`, seed 3 phiên đấu giá mẫu (mỗi phiên có giá khởi điểm, thời gian kết thúc), chỉ seed khi DB rỗng (idempotent).
2. Thêm `StartupInfoRunner implements ApplicationRunner` đọc cờ `--reset-data` và `--env`, in ra thông tin khởi động. Dùng `@Order` để chạy **trước** seed runner.
3. Thêm `ReadyLogger` nghe `ApplicationReadyEvent` in dòng "Auction API đã sẵn sàng".
4. Tạo `banner.txt` riêng cho Auction API có hiển thị version qua `${application.version}`.
5. Chạy app với profile `dev`, xác nhận thứ tự log: `StartupInfoRunner` → `AuctionSeedRunner` → `ReadyLogger`.
6. Ghi lại thời gian "Started ... in X seconds" và so sánh khi bật `lazy-initialization=true`.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `SpringApplication.run()` làm gì?**
> **Đáp:** Khởi động ứng dụng Spring Boot: chuẩn bị `Environment` (đọc property/profile), in banner, suy luận loại app (servlet/reactive/none), tạo `ApplicationContext` phù hợp, refresh context (quét bean, chạy auto-config, tạo singleton, khởi động embedded server), gọi các runner, rồi phát `ApplicationReadyEvent`.

**Q2: Embedded server là gì? Lợi ích?**
> **Đáp:** Là server (Tomcat/Netty/Jetty) được nhúng thẳng vào fat JAR thay vì cài ngoài. Lợi ích: app self-contained chạy bằng `java -jar`, hợp với Docker/K8s, cấu hình server trở thành property của app (`server.port`...), mỗi app một process độc lập.

**Q3: `CommandLineRunner` và `ApplicationRunner` khác nhau gì?**
> **Đáp:** Cả hai chạy một lần ngay sau khi context khởi tạo xong. `CommandLineRunner.run(String... args)` nhận tham số thô; `ApplicationRunner.run(ApplicationArguments args)` nhận tham số đã parse (phân biệt option `--key=value` và non-option, có `containsOption`, `getOptionValues`).

**Q4: Làm sao chạy code đúng một lần khi app khởi động (ví dụ seed dữ liệu)?**
> **Đáp:** Dùng `CommandLineRunner`/`ApplicationRunner`, hoặc nghe `ApplicationReadyEvent`. Nên gắn `@Profile` để giới hạn môi trường và viết idempotent (kiểm tra dữ liệu đã tồn tại) để tránh seed trùng.

### Mức Senior

**Q5: Khác nhau giữa `CommandLineRunner` và `@EventListener(ApplicationReadyEvent.class)`?**
> **Đáp:** Runner chạy sau khi context refresh nhưng **trước** khi `ApplicationReadyEvent` phát ra. `ApplicationReadyEvent` đảm bảo toàn bộ khởi tạo (kể cả các runner) đã xong và app thực sự sẵn sàng — phù hợp để mở traffic, đăng ký vào service discovery, đánh dấu readiness. Nếu trong runner gặp lỗi, app có thể dừng khởi động (fail-fast).

**Q6: Vì sao readiness probe không nên chỉ kiểm tra "cổng đã mở"?**
> **Đáp:** Cổng được bind ở bước refresh context, nhưng các runner (migration check, warm-up cache, seed) chạy **sau** đó. Nếu route traffic ngay khi cổng mở, request đầu có thể gặp bean chưa sẵn sàng. Dùng Actuator readiness gắn với `ApplicationReadyEvent` để route đúng lúc.

**Q7: Lazy initialization có lợi và hại gì? Khi nào nên bật?**
> **Đáp:** Lợi: khởi động nhanh vì bean chỉ tạo khi cần. Hại: lỗi cấu hình bean không lộ lúc startup mà nổ lúc request đầu; request đầu chậm hơn; mất tính fail-fast. Nên bật ở dev để vòng lặp phát triển nhanh; ở production cân nhắc kỹ hoặc dùng `@Lazy` có chọn lọc thay vì bật toàn cục.

**Q8: AOT/GraalVM native image giúp gì cho startup và đánh đổi là gì?**
> **Đáp:** Native image đóng băng quyết định cấu hình (auto-config, proxy, reflection) lúc build-time, loại bỏ phần lớn việc khởi tạo runtime → khởi động dưới ~100ms, tốn ít RAM, lý tưởng cho serverless/scale-to-zero. Đánh đổi: build chậm, cần khai báo reflection/resource hint cho các phần động, và một số thư viện chưa tương thích hoàn toàn.

---

## ✅ Checklist hoàn thành

- [ ] Mô tả được trình tự `SpringApplication.run()` từ Environment đến ApplicationReadyEvent
- [ ] Giải thích được embedded server và lợi ích so với deploy WAR
- [ ] Phân biệt `CommandLineRunner` vs `ApplicationRunner`, biết dùng `@Order`
- [ ] Biết bật lazy init toàn cục và hiểu đánh đổi
- [ ] Hiểu sự khác biệt readiness vs "cổng đã mở"
- [ ] Đối chiếu đúng với bootstrap của Laravel
- [ ] Hoàn thành Mini Project seed dữ liệu + custom banner
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Boot Reference — "SpringApplication" và "Spring Boot Application Events and Listeners"
- Spring Boot Reference — "Web Server: Embedded Container", "Lazy Initialization", "Graceful Shutdown"
- Spring Boot Reference — "Spring Boot AOT" và "GraalVM Native Image Support"
- Baeldung — "Spring Boot CommandLineRunner and ApplicationRunner"
- Laravel Docs — "Service Providers", "Request Lifecycle" (để đối chiếu)
