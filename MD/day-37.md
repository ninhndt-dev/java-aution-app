# Day 37 - Auto Configuration

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu phần "ma thuật" của Spring Boot tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **"phép màu" của Spring Boot** thực ra là cơ chế **Auto Configuration** — không có gì huyền bí cả.
- Mổ xẻ `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`.
- Nắm cơ chế nạp auto-config qua file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Spring Boot 3) — thay cho `spring.factories` cũ.
- Thuộc họ annotation `@Conditional`: `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`, `@ConditionalOnBean`...
- Hiểu **starter** là gì và **tự viết một auto-configuration + starter mini**.
- Biết debug bằng `--debug` để đọc **Condition Evaluation Report**.
- Đối chiếu với **package auto-discovery của Laravel** (`extra.laravel.providers` trong `composer.json`, config publish/merge).

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao Spring Boot "tự cấu hình giúp"?

Trong Spring thuần (Spring Framework cổ điển), bạn phải khai báo **tay** mọi thứ: `DataSource`, `EntityManagerFactory`, `DispatcherServlet`, `ViewResolver`... bằng XML hoặc `@Bean`. Cực kỳ dài dòng — hàng trăm dòng XML chỉ để chạy được "Hello World".

Spring Boot đảo ngược triết lý đó bằng nguyên tắc **"Convention over Configuration"** (quy ước hơn cấu hình): *"Nếu trên classpath có thư viện X, và bạn chưa tự định nghĩa bean đó, thì tôi (Boot) sẽ tự tạo một cấu hình mặc định hợp lý cho bạn."*

```
Bạn thêm spring-boot-starter-data-jpa vào pom.xml
        │
        ▼
Classpath xuất hiện: Hibernate, HikariCP, JPA API...
        │
        ▼
Auto Configuration phát hiện ──► tự tạo DataSource, EntityManagerFactory,
                                  TransactionManager... với cấu hình mặc định
        │
        ▼
Bạn chỉ cần khai báo url/username/password trong application.yml
```

> 💡 Đây KHÔNG phải reflection ma thuật chạy lung tung. Mỗi bean tự sinh đều đi qua các **điều kiện (`@Conditional`)** rất rõ ràng. Học xong hôm nay bạn sẽ "nhìn xuyên" được mọi cấu hình tự động.

### 2. Mổ xẻ `@SpringBootApplication`

Đây là annotation bạn dán lên class `main`. Nó là một **meta-annotation** gộp 3 thứ:

```java
@SpringBootApplication
public class AuctionApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuctionApiApplication.class, args);
    }
}
```

Bóc tách ra, nó tương đương:

```java
@Configuration            // 1. Class này là nguồn định nghĩa bean (@Bean)
@EnableAutoConfiguration  // 2. Bật cơ chế Auto Configuration — TRÁI TIM hôm nay
@ComponentScan            // 3. Quét @Component/@Service/@Repository/@Controller
                          //    bắt đầu từ package chứa class này trở xuống
public class AuctionApiApplication { ... }
```

| Annotation con | Vai trò |
|---|---|
| `@Configuration` | Đánh dấu class là nơi khai báo bean thủ công (`@Bean`). |
| `@ComponentScan` | Tự động tìm và đăng ký bean của **bạn** (các class có `@Component`, `@Service`...). |
| `@EnableAutoConfiguration` | Tự động đăng ký bean của **Spring và thư viện bên thứ ba** dựa trên classpath. |

> ⚠️ Vì `@ComponentScan` quét từ package của class `main` **trở xuống**, hãy luôn đặt class `main` ở **package gốc** (ví dụ `com.example.auction`), không đặt sâu trong `com.example.auction.web`. Nếu đặt sai, các service/repository nằm ngoài nhánh quét sẽ "không được tìm thấy" → lỗi `NoSuchBeanDefinitionException`.

### 3. Cơ chế nạp: `AutoConfiguration.imports` (Spring Boot 3)

Làm sao `@EnableAutoConfiguration` biết có những class auto-config nào để xét? Nó **không** quét cả classpath (quá chậm). Thay vào đó, mỗi thư viện liệt kê sẵn các lớp auto-config của mình trong một file đặc biệt.

**Spring Boot 2.7 trở về trước** dùng:
```
src/main/resources/META-INF/spring.factories
```
```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.example.MyAutoConfiguration,\
com.example.OtherAutoConfiguration
```

**Spring Boot 3+ (Boot 2.7 cũng hỗ trợ song song)** dùng file mới, mỗi class một dòng:
```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
```
com.example.MyAutoConfiguration
com.example.OtherAutoConfiguration
```

```
@EnableAutoConfiguration
        │
        ▼ (qua AutoConfigurationImportSelector)
Đọc TẤT CẢ file .../AutoConfiguration.imports trên classpath
        │
        ▼
Gom danh sách candidate (hàng trăm class auto-config)
        │
        ▼
Lọc qua các @Conditional ──► chỉ giữ lại class thỏa điều kiện
        │
        ▼
Đăng ký bean tương ứng vào ApplicationContext
```

> 💡 Mở file JAR `spring-boot-autoconfigure-3.x.jar` ra, bạn sẽ thấy file `AutoConfiguration.imports` liệt kê cả trăm dòng — đó là toàn bộ "danh mục magic" của Spring Boot.

### 4. Họ annotation `@Conditional` — nơi quyết định "có nên cấu hình hay không"

Một class auto-config chỉ được kích hoạt nếu **mọi điều kiện** của nó đều đúng. Đây là các điều kiện hay gặp nhất:

| Annotation | Kích hoạt khi... |
|---|---|
| `@ConditionalOnClass` | Class này **có mặt** trên classpath (ví dụ `DataSource.class` tồn tại → có thư viện JDBC). |
| `@ConditionalOnMissingClass` | Class **không** có trên classpath. |
| `@ConditionalOnBean` | Đã có sẵn một bean kiểu/tên nào đó trong context. |
| `@ConditionalOnMissingBean` | **Chưa** có bean kiểu đó → cho phép người dùng "override" cấu hình mặc định. |
| `@ConditionalOnProperty` | Một property trong cấu hình bằng giá trị mong đợi (ví dụ `auction.notify.enabled=true`). |
| `@ConditionalOnWebApplication` | App đang chạy ở chế độ web (Servlet/Reactive). |
| `@ConditionalOnResource` | Một resource (file) tồn tại trên classpath. |

`@ConditionalOnMissingBean` là **then chốt** của triết lý "mặc định nhưng cho phép ghi đè":

```
Boot định nghĩa:  @Bean @ConditionalOnMissingBean ObjectMapper objectMapper() {...}
                          │
        ┌─────────────────┴──────────────────┐
        ▼                                     ▼
Bạn KHÔNG khai báo ObjectMapper      Bạn TỰ khai báo @Bean ObjectMapper
        │                                     │
        ▼                                     ▼
Boot tạo bản mặc định cho bạn        Boot LÙI lại, dùng bản của bạn
```

> 💡 Nhờ vậy bạn luôn có quyền "đè" cấu hình mặc định chỉ bằng cách khai báo bean cùng kiểu của riêng mình. Đây là lý do Spring Boot vừa "tiện" vừa "không khóa cứng".

### 5. Starter là gì?

**Starter** chỉ đơn giản là một **gói phụ thuộc (dependency) rỗng về code**, nhiệm vụ duy nhất là **kéo theo một chùm thư viện liên quan** + (thường kèm) một auto-configuration. Ví dụ `spring-boot-starter-web` kéo theo Spring MVC, Tomcat nhúng, Jackson...

```
spring-boot-starter-web  (pom rỗng, chỉ chứa <dependencies>)
   ├── spring-web
   ├── spring-webmvc
   ├── spring-boot-starter-tomcat  (Tomcat nhúng)
   └── spring-boot-starter-json    (Jackson)
```

Quy ước đặt tên:
- Starter **chính thức của Spring**: `spring-boot-starter-*` (ví dụ `spring-boot-starter-data-jpa`).
- Starter **của bên thứ ba**: đặt tên ngược lại — `*-spring-boot-starter` (ví dụ `mybatis-spring-boot-starter`). Đây là quy ước bắt buộc để tránh "đụng" namespace của Spring.

### 6. Thứ tự auto-config: `@AutoConfiguration` và `before/after`

Trong Boot 3, class auto-config đánh dấu bằng `@AutoConfiguration` (chứ không phải `@Configuration` thường). Annotation này cho phép khai báo thứ tự:

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
public class MyJpaAutoConfiguration { ... }
```

Điều này quan trọng vì có config phụ thuộc config khác (JPA cần `DataSource` được tạo trước).

---

## 🔁 Đối chiếu với Laravel/PHP

Laravel cũng có cơ chế "tự đăng ký" rất giống — **Package Auto-Discovery**. Khi bạn `composer require` một package, Laravel tự tìm và nạp `ServiceProvider` của nó mà không cần bạn khai báo tay vào `config/app.php`.

| Khái niệm | Laravel/PHP | Spring Boot |
|---|---|---|
| Điểm "tự nạp" của package | `extra.laravel.providers` trong `composer.json` của package | File `AutoConfiguration.imports` trong JAR |
| Đơn vị cấu hình | `ServiceProvider` (`register()`, `boot()`) | Class `@AutoConfiguration` với các `@Bean` |
| "Kéo theo chùm thư viện" | Một package phụ thuộc package khác qua `composer.json` | **Starter** (pom kéo theo dependency) |
| Cấu hình mặc định + cho ghi đè | `mergeConfigFrom()` (merge config mặc định, dev có thể `php artisan vendor:publish` để override) | `@ConditionalOnMissingBean` (tạo bean mặc định, dev khai báo bean riêng để đè) |
| Bật/tắt theo điều kiện | Tự viết `if (config('x.enabled'))` trong provider | `@ConditionalOnProperty` (khai báo, không cần viết if) |
| Tắt auto-discovery một package | `extra.laravel.dont-discover` trong `composer.json` | `spring.autoconfigure.exclude=...` hoặc `exclude` của `@SpringBootApplication` |

**Khác biệt tư duy quan trọng:**
- Ở **Laravel**, `ServiceProvider` luôn được nạp và **bạn tự viết logic điều kiện** bên trong (`if config enabled...`).
- Ở **Spring Boot**, điều kiện được **khai báo bằng annotation** (`@ConditionalOnProperty`) và chính framework đánh giá. Điều này khiến cấu hình "khai báo" (declarative) hơn, và có thể được liệt kê/giải thích tự động qua **Condition Evaluation Report**.

> 🧩 Nói ngắn gọn: `@AutoConfiguration` ≈ `ServiceProvider`, starter ≈ "metapackage" composer, `@ConditionalOnMissingBean` ≈ `mergeConfigFrom` + `vendor:publish`.

---

## 💻 Thực hành code

### Bài 1 — Bóc tách và soi auto-config đang chạy

Chạy app Spring Boot của bạn với cờ `--debug` để in **Condition Evaluation Report**:

```bash
java -jar auction-api.jar --debug
# hoặc khi dev:
./mvnw spring-boot:run -Dspring-boot.run.arguments=--debug
```

Output sẽ chia làm hai phần:

```text
Positive matches:   (config được kích hoạt — và VÌ SAO)
-----------------
   DataSourceAutoConfiguration matched:
      - @ConditionalOnClass found required classes 'javax.sql.DataSource' (OnClassCondition)

Negative matches:   (config bị BỎ QUA — và vì điều kiện nào không thỏa)
-----------------
   RabbitAutoConfiguration:
      Did not match:
         - @ConditionalOnClass did not find required class 'com.rabbitmq.client.Channel'
```

> ✅ **Bài tập tự giải thích:** Tìm trong report một config "Negative match" và giải thích bằng lời vì sao nó không được kích hoạt.

### Bài 2 — Viết một auto-configuration mini cho module thông báo đấu giá

Ta sẽ tạo một service `AuctionNotifier` được Spring Boot **tự cấu hình**, nhưng cho phép người dùng ghi đè.

**Bước 2.1 — Class properties (binding từ `application.yml`):**

```java
package com.example.auction.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Gắn các property dạng "auction.notify.*" trong application.yml vào object này
@___(prefix = "auction.notify") // Điền annotation cấu hình properties
public ___ AuctionNotifyProperties { // Điền từ khóa khai báo lớp

    /** Bật/tắt module thông báo. Mặc định bật. */
    private boolean enabled = true;

    /** Tiền tố thêm vào mỗi tin nhắn thông báo. */
    private String prefix = "[AUCTION]";

    // getter/setter bắt buộc để Spring binding được
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
}
```

**Bước 2.2 — Interface + implementation mặc định:**

```java
package com.example.auction.notify;

public ___ AuctionNotifier { // Điền từ khóa khai báo giao diện
    // Gửi thông báo khi có sự kiện đấu giá (ví dụ: có người vừa trả giá)
    void notify(String message);
}
```

```java
package com.example.auction.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Bản mặc định: chỉ ghi ra log.
public ___ LoggingAuctionNotifier ___ AuctionNotifier { // Điền từ khóa khai báo lớp và kế thừa giao diện
    private static final Logger log = LoggerFactory.getLogger(LoggingAuctionNotifier.class);
    private final String prefix;

    public LoggingAuctionNotifier(String prefix) {
        this.prefix = prefix;
    }

    @Override
    public void notify(String message) {
        log.info("{} {}", prefix, message);
    }
}
```

**Bước 2.3 — Lớp Auto Configuration (trái tim của bài):**

```java
package com.example.auction.notify;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@___                                  // Điền annotation khai báo đây là AutoConfiguration (Boot 3)
@EnableConfigurationProperties(AuctionNotifyProperties.class)  // bật binding properties
// Chỉ kích hoạt nếu auction.notify.enabled = true (hoặc không khai báo -> mặc định true)
@___(prefix = "auction.notify", name = "enabled", havingValue = "true", matchIfMissing = true) // Điền annotation xét điều kiện property
public ___ AuctionNotifyAutoConfiguration { // Điền từ khóa khai báo lớp

    @Bean
    @___   // Điền annotation CHỈ tạo bean mặc định nếu người dùng CHƯA tự khai báo AuctionNotifier
    public AuctionNotifier auctionNotifier(AuctionNotifyProperties props) {
        return new LoggingAuctionNotifier(props.getPrefix());
    }
}
```

**Bước 2.4 — Khai báo file imports để Boot tìm thấy auto-config:**

Tạo file:
```text
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
Nội dung (mỗi class một dòng):
```text
com.example.auction.notify.AuctionNotifyAutoConfiguration
```

**Bước 2.5 — Dùng thử (không cần khai báo gì thêm):**

```java
package com.example.auction.bid;

import com.example.auction.notify.AuctionNotifier;
import org.springframework.stereotype.Service;

@___ // Điền annotation đánh dấu Service
public ___ BidService { // Điền từ khóa khai báo lớp

    private final AuctionNotifier notifier;  // Boot TỰ inject bean đã auto-config

    public BidService(AuctionNotifier notifier) {
        this.notifier = notifier;
    }

    public void placeBid(Long auctionId, long amount) {
        // ... lưu bid ...
        notifier.notify("Phiên #" + auctionId + " có giá mới: " + amount);
    }
}
```

### Bước 3 — CHALLENGE: Ghi đè Auto-Configuration (Override)

> 🏆 Yêu cầu:
> 1. Hiện tại Spring Boot đã tự động tạo `LoggingAuctionNotifier` cho bạn thông qua AutoConfiguration.
> 2. Hãy tạo một file cấu hình `@Configuration` riêng của bạn (Ví dụ: `CustomAuctionConfig.java`) và tự khai báo một `@Bean AuctionNotifier` trả về một `EmailAuctionNotifier` (bạn tự tạo class này, chỉ cần in ra console là gửi email giả).
> 3. Chạy lại ứng dụng và quan sát khi `placeBid` được gọi. Nó gọi bản log cũ hay bản email mới? Tại sao?
> 4. Chạy app với tham số `--debug`. Xem lại Report, tìm lớp `AuctionNotifyAutoConfiguration`. Xác nhận vì sao method tạo `LoggingAuctionNotifier` không được thực thi!

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Đặt sai đường dẫn file imports.** Tên file phải **chính xác** `org.springframework.boot.autoconfigure.AutoConfiguration.imports` và nằm trong `META-INF/spring/`. Gõ sai một ký tự → auto-config bị bỏ qua âm thầm, không báo lỗi.
- **Dùng `@Configuration` thay vì `@AutoConfiguration` cho lớp auto-config trong starter.** `@Configuration` thường dùng cho config nội bộ app (được `@ComponentScan` quét), còn `@AutoConfiguration` mới đúng cho starter (được nạp qua file imports, có thứ tự `before/after`).
- **Quên `@ConditionalOnMissingBean`.** Nếu thiếu, bean mặc định của bạn sẽ "đè" cả bean người dùng tự khai báo, hoặc gây xung đột "expected single matching bean but found 2".
- **Đặt class `main` quá sâu trong cây package** → `@ComponentScan` không quét tới các bean nằm "ngang hàng phía trên" → `NoSuchBeanDefinitionException`.
- **Quên thêm `spring-boot-configuration-processor`** (dependency optional) → IDE không gợi ý các property `auction.notify.*` trong `application.yml`.
- **Tưởng auto-config "luôn chạy".** Mỗi auto-config đều có điều kiện. Khi không hiểu vì sao một bean không xuất hiện, **luôn chạy `--debug` đọc report** thay vì đoán mò.

---

## 🚀 Liên hệ Spring Boot / Production

- **Loại bỏ auto-config không cần thiết** để khởi động nhanh hơn và giảm bề mặt tấn công:
  ```java
  @SpringBootApplication(exclude = { SecurityAutoConfiguration.class })
  ```
  hoặc trong `application.yml`:
  ```yaml
  spring:
    autoconfigure:
      exclude:
        - org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
  ```
- **Library nội bộ công ty thường đóng gói thành starter riêng** (`company-logging-spring-boot-starter`, `company-tracing-spring-boot-starter`) để mọi team dùng chung cấu hình chuẩn chỉ bằng một dòng dependency. Hiểu auto-config là kỹ năng cốt lõi khi bạn build platform nội bộ.
- **Thời gian khởi động:** mỗi auto-config được đánh giá điều kiện đều tốn chút thời gian. Với hàng nghìn class candidate, Boot dùng kỹ thuật `AutoConfigurationMetadata` (file metadata pre-indexed) để lọc nhanh trước khi load class — đây cũng là lý do bạn nên tránh thêm starter "thừa".
- **AOT / GraalVM native image (teaser):** auto-config được "đóng băng" lúc build-time để loại bỏ phần đánh giá điều kiện lúc runtime — chủ đề ngày sau.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta biến module thông báo đấu giá thành một **auto-configuration đúng chuẩn**, mô phỏng cách một starter nội bộ hoạt động.

**Nhiệm vụ Day 37:**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. Tạo file `AutoConfiguration.imports` đúng đường dẫn để đăng ký AutoConfiguration.
3. Inject `AuctionNotifier` vào `BidService` mà **không** khai báo bean thủ công — xác nhận nó vẫn chạy (nhờ auto-config).
4. Hoàn thành **CHALLENGE** ở Bước 3: Tự cấu hình một Bean để ghi đè (override) cấu hình mặc định. Kiểm tra bằng cách chạy lại ứng dụng với `--debug` và xác định `Negative match` đối với `LoggingAuctionNotifier`.
5. Đặt `auction.notify.enabled=false` trong `application.yml`, chạy lại, xác nhận config không được kích hoạt do `@ConditionalOnProperty`.

> 🎯 Tiêu chí đạt: Đã điền đúng các `@Conditional...`. Tự tay override được bean auto-config và giải thích được lý do qua `--debug`.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `@SpringBootApplication` gồm những gì?**
> **Đáp:** Là meta-annotation gộp `@Configuration` (class khai báo bean), `@ComponentScan` (quét bean của bạn từ package hiện tại trở xuống), và `@EnableAutoConfiguration` (bật cơ chế tự cấu hình của Spring Boot dựa trên classpath).

**Q2: Auto Configuration là gì và hoạt động dựa trên cái gì?**
> **Đáp:** Là cơ chế Spring Boot tự đăng ký bean mặc định dựa trên những gì có trên classpath và các property cấu hình. Nó nạp danh sách candidate từ file `META-INF/spring/...AutoConfiguration.imports`, rồi lọc qua các annotation `@Conditional` (`@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`...). Chỉ class thỏa mọi điều kiện mới được kích hoạt.

**Q3: Starter là gì?**
> **Đáp:** Là một gói dependency (thường không chứa code logic) dùng để kéo theo một chùm thư viện liên quan, thường đi kèm auto-config. Ví dụ `spring-boot-starter-web` kéo theo Spring MVC + Tomcat nhúng + Jackson. Quy ước: starter chính thức `spring-boot-starter-*`, starter bên thứ ba `*-spring-boot-starter`.

**Q4: Làm sao biết auto-config nào đang chạy / bị bỏ qua?**
> **Đáp:** Chạy app với cờ `--debug` để in **Condition Evaluation Report**, gồm "Positive matches" (kích hoạt + lý do) và "Negative matches" (bỏ qua + điều kiện không thỏa).

### Mức Senior

**Q5: Vai trò của `@ConditionalOnMissingBean` trong triết lý của Spring Boot?**
> **Đáp:** Nó hiện thực hóa nguyên tắc "mặc định hợp lý nhưng cho phép ghi đè". Auto-config chỉ tạo bean mặc định **nếu người dùng chưa tự định nghĩa** bean cùng kiểu/tên. Nhờ vậy người dùng luôn nắm quyền kiểm soát: chỉ cần khai báo bean riêng là cấu hình mặc định tự lùi. Đây là cơ chế "override không cần config flag".

**Q6: Khác nhau giữa `spring.factories` và `AutoConfiguration.imports`?**
> **Đáp:** `spring.factories` (Boot ≤ 2.7) dùng định dạng key=value gộp nhiều class trên một dòng nối bằng `\`, và còn dùng cho nhiều loại factory khác. Từ Boot 2.7 giới thiệu và Boot 3 dùng chính thức file `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, mỗi class một dòng, **chỉ** dành cho auto-config — giúp xử lý nhanh hơn và rõ ràng hơn. Boot 3 đã bỏ hỗ trợ liệt kê auto-config qua `spring.factories`.

**Q7: Auto-config ảnh hưởng thời gian khởi động thế nào và tối ưu ra sao?**
> **Đáp:** Mỗi candidate phải được đánh giá điều kiện; nhiều starter thừa → nhiều candidate → khởi động chậm. Boot tối ưu bằng `AutoConfigurationMetadata` (đọc metadata index trước, lọc class không thỏa mà chưa cần load class). Người dùng có thể `spring.autoconfigure.exclude` các config không dùng, giảm starter thừa, và (nâng cao) dùng AOT/GraalVM để đóng băng quyết định cấu hình lúc build.

**Q8: Vì sao starter bên thứ ba phải đặt tên `*-spring-boot-starter`?**
> **Đáp:** Để tránh chiếm dụng namespace `spring-boot-starter-*` vốn được Spring giữ riêng cho starter chính thức. Đây là quy ước được nêu rõ trong tài liệu Spring Boot, giúp phân biệt rõ starter của Pivotal/Spring với của cộng đồng/công ty.

---

## ✅ Checklist hoàn thành

- [ ] Bóc tách được `@SpringBootApplication` thành 3 annotation con và giải thích vai trò từng cái
- [ ] Hiểu cơ chế nạp qua `AutoConfiguration.imports` (và biết file `spring.factories` cũ)
- [ ] Phân biệt được ít nhất 4 annotation họ `@Conditional`
- [ ] Tự tạo `AutoConfiguration` và file `.imports`
- [ ] Dùng đúng các annotation `@ConditionalOnProperty` và `@ConditionalOnMissingBean`
- [ ] Hoàn thành Challenge: Override thành công cấu hình mặc định và đọc `--debug` report
- [ ] Chạy `--debug` và đọc hiểu Condition Evaluation Report
- [ ] Đối chiếu đúng với Package Auto-Discovery của Laravel
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Boot Reference — "Auto-configuration" và "Creating Your Own Starter"
- Spring Boot Reference — "Condition Annotations"
- Baeldung — "Create a Custom Auto-Configuration with Spring Boot"
- Source code: mở `spring-boot-autoconfigure-3.x.jar` đọc file `AutoConfiguration.imports`
- Laravel Docs — "Package Development: Package Discovery" (để đối chiếu)
