# Day 26 - Spring IoC (Inversion of Control)

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP đã quen Service Container, nay muốn hiểu "trái tim" của Spring.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Inversion of Control (IoC)** là gì — vì sao "đảo ngược quyền điều khiển" lại là tư tưởng cốt lõi của Spring.
- Phân biệt **IoC container** và **bean** — hai khái niệm nền tảng bạn sẽ gặp suốt phần còn lại của lộ trình.
- Phân biệt **BeanFactory vs ApplicationContext** (eager vs lazy, và những thứ ApplicationContext thêm vào).
- Biết các cách **khai báo bean**: `@Component`/`@Service`/`@Repository`/`@Configuration` + `@Bean`, và **component scan**.
- Hiểu **vì sao cần IoC**: giảm coupling, đảo phụ thuộc, dễ test.
- Ánh xạ trực tiếp sang **Service Container của Laravel** (`App::make`, `bind`, `singleton`) để học cực nhanh.

---

## 🧠 Lý thuyết cốt lõi

### 1. Inversion of Control là gì?

Hãy nhìn cách bạn **thường** tạo object trong code thủ công:

```java
public class AuctionService {
    // Bạn TỰ TAY tạo phụ thuộc bằng "new"
    private final BidRepository bidRepository = new JdbcBidRepository();
    private final NotificationService notifier = new EmailNotificationService();
}
```

Ở đây `AuctionService` **tự quyết định** dùng `JdbcBidRepository` cụ thể nào, `EmailNotificationService` cụ thể nào. Nó nắm **quyền điều khiển** việc khởi tạo và lắp ráp phụ thuộc. Hệ quả:

- `AuctionService` **bị dính chặt** (coupled) vào lớp cụ thể `JdbcBidRepository`. Muốn đổi sang `MongoBidRepository`? Phải sửa code.
- **Khó test**: muốn test `AuctionService` với một repository giả (mock), bạn không chèn vào được vì nó tự `new`.

**Inversion of Control** đảo ngược điều này: **không phải object tự tạo phụ thuộc, mà có một "container" bên ngoài tạo sẵn rồi đưa vào cho object**. Object chỉ "khai báo nhu cầu", còn ai cung cấp là việc của container.

```
   KHÔNG có IoC                          CÓ IoC (Spring)
   ───────────                           ──────────────
   AuctionService                        IoC Container
        │ new                                  │ tạo & quản lý
        ▼                                      ▼
   JdbcBidRepository  ◄── object tự tạo    AuctionService ◄── được "tiêm" repo từ ngoài
                                               ▲
                                          BidRepository (do container đưa vào)
```

> 💡 "Inversion" (đảo ngược) ở đây nghĩa là: **quyền kiểm soát luồng tạo/lắp ráp object bị lấy khỏi tay bạn và giao cho framework**. Bạn nói "tôi cần một `BidRepository`", còn việc tạo nó, chọn implementation nào, đưa vào lúc nào — Spring lo. Đây cũng là tinh thần của **Hollywood Principle**: "Don't call us, we'll call you."

### 2. IoC Container và Bean

- **IoC Container**: là "bộ não" của Spring, chịu trách nhiệm **tạo, cấu hình, lắp ráp (wiring) và quản lý vòng đời** của các object. Trong Spring, container được hiện thực qua interface `BeanFactory` và `ApplicationContext`.
- **Bean**: là **một object do IoC container tạo ra và quản lý**. Lưu ý: không phải mọi object trong app đều là bean — chỉ những object bạn "đăng ký" với container (qua annotation hoặc `@Bean`) mới là bean. Một object bạn tự `new` trong method không phải bean.

```
┌──────────────────────────────────────────────┐
│           IoC Container (ApplicationContext)   │
│                                                │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐  │
│   │ Bean A   │──►│ Bean B   │──►│ Bean C   │  │  ← container giữ & wiring các bean
│   │ Auction  │   │ BidRepo  │   │ Notifier │  │
│   │ Service  │   │          │   │          │  │
│   └──────────┘   └──────────┘   └──────────┘  │
│                                                │
│   Quản lý: tạo / inject / scope / lifecycle    │
└────────────────────────────────────────────────┘
```

> 💡 **Mặc định mọi bean là singleton** trong phạm vi container (mỗi tên bean chỉ có 1 instance dùng chung). Khác với Laravel nơi bind thường tạo mới mỗi lần `make` trừ khi bạn dùng `singleton`. Ta sẽ đào sâu scope ở Day 28.

### 3. BeanFactory vs ApplicationContext

Spring có **hai** loại container, xếp tầng:

| Tiêu chí | `BeanFactory` | `ApplicationContext` |
|---|---|---|
| Vai trò | Container cơ bản nhất, chỉ lo tạo/quản lý bean | "Bản nâng cấp" — kế thừa `BeanFactory` và thêm nhiều thứ |
| Khởi tạo bean | **Lazy** — tạo bean khi lần đầu gọi `getBean()` | **Eager** — tạo sẵn tất cả singleton lúc startup |
| i18n (đa ngôn ngữ) | Không | Có (`MessageSource`) |
| Event publishing | Không | Có (`ApplicationEventPublisher`) |
| Tự động nhận diện `BeanPostProcessor` / `BeanFactoryPostProcessor` | Phải đăng ký tay | Tự động |
| Tích hợp AOP, môi trường (`Environment`), `ResourceLoader` | Hạn chế | Đầy đủ |
| Dùng trong thực tế | Hiếm khi dùng trực tiếp | **Gần như luôn dùng cái này** |

```
        ┌────────────────────────────────────┐
        │        ApplicationContext           │   ← thêm: i18n, event, AOP,
        │  ┌──────────────────────────────┐  │      Environment, ResourceLoader,
        │  │        BeanFactory            │  │      eager init singleton...
        │  │  (tạo / quản lý / wiring bean)│  │
        │  └──────────────────────────────┘  │
        └────────────────────────────────────┘
```

> 💡 **Eager init là một tính năng, không phải bug.** Spring tạo sẵn singleton lúc startup để **phát hiện lỗi cấu hình ngay khi khởi động** (fail-fast) thay vì để lỗi nổ ra giữa lúc xử lý request của khách. Nếu một bean thiếu phụ thuộc, app sẽ không khởi động được — tốt hơn là chết ngầm.

Các implementation `ApplicationContext` hay gặp:

- `AnnotationConfigApplicationContext` — cấu hình bằng Java annotation (thông dụng nhất hiện nay).
- `ClassPathXmlApplicationContext` — cấu hình bằng XML (kiểu cũ, vẫn gặp ở dự án legacy).
- Trong **Spring Boot**, bạn không tự tạo — Boot tự dựng một `AnnotationConfigServletWebServerApplicationContext` (cho web) qua `SpringApplication.run(...)`.

### 4. Các cách khai báo bean

#### Cách A — Stereotype annotation + Component Scan

Spring quét (scan) classpath tìm các class có **stereotype annotation** rồi tự đăng ký làm bean:

| Annotation | Ý nghĩa ngữ nghĩa | Khi nào dùng |
|---|---|---|
| `@Component` | Bean chung chung | Mọi class không thuộc nhóm dưới |
| `@Service` | Bean tầng nghiệp vụ (business logic) | Service, use-case |
| `@Repository` | Bean tầng truy cập dữ liệu | DAO/Repository; còn được dịch exception JDBC sang `DataAccessException` |
| `@Controller` / `@RestController` | Bean tầng web (xử lý HTTP) | Controller |

> 💡 Về mặt kỹ thuật, `@Service`, `@Repository`, `@Controller` **đều là `@Component`** (chúng được annotate bởi `@Component`). Khác biệt chủ yếu là **ngữ nghĩa** (giúp người đọc và một số xử lý đặc biệt như dịch exception ở `@Repository`).

```java
@Service                       // Spring tự đăng ký class này làm bean
public class AuctionService {
    // ...
}
```

Để Spring biết quét ở đâu, dùng `@ComponentScan` (Spring Boot tự bật sẵn qua `@SpringBootApplication`):

```java
@Configuration
@ComponentScan(basePackages = "com.example.auction")  // quét package này & con
public class AppConfig { }
```

#### Cách B — `@Configuration` + `@Bean` (khai báo tường minh)

Khi bạn **không sửa được class** (ví dụ class của thư viện bên thứ ba) hoặc cần logic tạo phức tạp, hãy khai báo bean thủ công trong một class `@Configuration`:

```java
@Configuration
public class AppConfig {

    @Bean   // method trả về object -> object đó thành bean, tên bean = tên method ("clock")
    public Clock clock() {
        return Clock.systemUTC();   // tạo bean thủ công, kiểm soát hoàn toàn
    }
}
```

> ⚠️ **`@Component` vs `@Bean`**: `@Component` dán **lên class của bạn** để Spring tự tạo. `@Bean` dán **lên method trong class `@Configuration`** để bạn tự tạo và trả về object. Dùng `@Bean` khi cần kiểm soát cách khởi tạo, hoặc khi class không thuộc quyền sửa của bạn.

### 5. Vì sao cần IoC? (Lợi ích cốt lõi)

1. **Giảm coupling**: class chỉ phụ thuộc vào **interface/abstraction**, không phụ thuộc lớp cụ thể. Đổi implementation chỉ cần đổi cấu hình bean, không sửa code dùng nó.
2. **Dễ test**: vì phụ thuộc được tiêm từ ngoài, lúc test bạn tiêm mock/stub vào dễ dàng (Day 27 sẽ thấy rõ với constructor injection).
3. **Đảo phụ thuộc (Dependency Inversion)**: module cấp cao không phụ thuộc module cấp thấp, cả hai phụ thuộc abstraction. Đây là chữ **D** trong SOLID.
4. **Quản lý vòng đời tập trung**: container lo việc tạo, khởi tạo, dọn dẹp tài nguyên (Day 28) — bạn không phải tự quản.
5. **Tái sử dụng & cấu hình linh hoạt**: một bean cấu hình một lần, dùng khắp nơi; đổi profile (dev/prod) là đổi cả bộ bean (Day 31).

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là phần bạn sẽ thấy "à, hóa ra mình đã biết rồi". Laravel **cũng có IoC container** — gọi là **Service Container**.

| Khái niệm Spring | Laravel tương đương | Ghi chú |
|---|---|---|
| IoC Container | **Service Container** (`app()`, `$this->app`) | Cùng tư tưởng: container tạo & resolve phụ thuộc |
| Bean | **Binding** đã đăng ký trong container | Object do container quản lý |
| `@Component`/`@Service` (auto-register) | **Auto-resolution** qua type-hint constructor | Laravel tự resolve class cụ thể không cần bind |
| `@Bean` trong `@Configuration` | `$this->app->bind(Foo::class, fn() => new Foo(...))` | Khai báo tường minh cách tạo |
| Singleton bean (mặc định) | `$this->app->singleton(Foo::class, ...)` | Spring mặc định singleton; Laravel mặc định "transient" (`bind`) |
| `applicationContext.getBean(Foo.class)` | `app()->make(Foo::class)` / `resolve(Foo::class)` | Lấy object từ container |
| `@ComponentScan` | (không cần) — Laravel autoload qua Composer + auto-resolution | |
| `ApplicationContext` | Đối tượng `$app` (instance `Illuminate\Foundation\Application`) | "Cái container tổng" |

Ví dụ Laravel quen thuộc:

```php
// AppServiceProvider.php — đăng ký binding (giống @Bean / @Configuration)
$this->app->singleton(BidRepository::class, function ($app) {
    return new EloquentBidRepository($app->make('db'));
});

// Lấy ra (giống applicationContext.getBean)
$repo = app()->make(BidRepository::class);
```

So với Spring:

```java
@Configuration
public class AppConfig {
    @Bean   // giống singleton() trong ServiceProvider
    public BidRepository bidRepository() {
        return new JdbcBidRepository();
    }
}
// Lấy ra: applicationContext.getBean(BidRepository.class)  // giống app()->make()
```

> 💡 **Khác biệt tư duy quan trọng**: Trong Laravel, container resolve mỗi khi `make` (trừ `singleton`), và app **bootstrap lại mỗi request**. Trong Spring, container tạo singleton **một lần lúc startup** và **sống suốt vòng đời tiến trình** — như bạn đã học ở Day 01. Vì vậy bean Spring giữ state phải cẩn thận với thread-safety.

---

## 💻 Thực hành code

### Bước 1 — Container "chay" với `AnnotationConfigApplicationContext`

Ta dựng một container tối giản (không Spring Boot) để **nhìn rõ** cơ chế.

```java
// File: PricingStrategy.java
package com.example.auction;

// Abstraction: chiến lược tính giá khởi điểm
public interface PricingStrategy {
    long startingPrice(long basePrice);
}
```

```java
// File: StandardPricingStrategy.java
package com.example.auction;

import org.springframework.stereotype.Component;

@Component   // Spring tự đăng ký làm bean (component scan sẽ tìm thấy)
public class StandardPricingStrategy implements PricingStrategy {
    @Override
    public long startingPrice(long basePrice) {
        return basePrice; // giá khởi điểm = giá gốc
    }
}
```

```java
// File: AuctionService.java
package com.example.auction;

import org.springframework.stereotype.Service;

@Service   // bean tầng nghiệp vụ
public class AuctionService {

    private final PricingStrategy pricingStrategy;

    // Constructor injection — Day 27 sẽ đào sâu; ở đây Spring tự đưa bean vào
    public AuctionService(PricingStrategy pricingStrategy) {
        this.pricingStrategy = pricingStrategy;
    }

    public long openAuction(long basePrice) {
        long start = pricingStrategy.startingPrice(basePrice);
        System.out.println("Mở phiên đấu giá, giá khởi điểm = " + start);
        return start;
    }
}
```

```java
// File: AppConfig.java
package com.example.auction;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "com.example.auction") // quét package này
public class AppConfig { }
```

```java
// File: Main.java
package com.example.auction;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class Main {
    public static void main(String[] args) {
        // 1) Tạo IoC container từ class cấu hình
        var context = new AnnotationConfigApplicationContext(AppConfig.class);

        // 2) Lấy bean ra khỏi container (giống app()->make() bên Laravel)
        AuctionService service = context.getBean(AuctionService.class);
        service.openAuction(1_000_000);

        // 3) Chứng minh "mặc định là singleton": 2 lần getBean trả về CÙNG object
        AuctionService a = context.getBean(AuctionService.class);
        AuctionService b = context.getBean(AuctionService.class);
        System.out.println("Cùng instance? " + (a == b)); // true -> singleton

        // 4) Liệt kê tên các bean container đang quản lý
        for (String name : context.getBeanDefinitionNames()) {
            System.out.println("Bean: " + name);
        }

        context.close(); // đóng container, dọn dẹp
    }
}
```

Kết quả mong đợi: in ra giá khởi điểm, `Cùng instance? true`, và danh sách bean (`auctionService`, `standardPricingStrategy`, `appConfig`, cùng các bean hạ tầng).

### Bước 2 — Khai báo bean bằng `@Bean` (cho class không thể sửa)

Giả sử bạn cần một `Clock` (lớp JDK, không sửa được) để đo thời gian phiên đấu giá:

```java
// Trong AppConfig.java thêm:
import java.time.Clock;
import org.springframework.context.annotation.Bean;

@Bean   // tên bean mặc định = "clock"
public Clock clock() {
    return Clock.systemDefaultZone(); // tự kiểm soát cách tạo
}
```

Khi đó `context.getBean(Clock.class)` trả về object bạn vừa tạo. Đây chính là tương đương `$this->app->singleton(Clock::class, ...)` của Laravel.

### Bước 3 — Bài tập tự giải thích

> ✅ Thử bỏ annotation `@Service` trên `AuctionService` rồi chạy lại. Bạn sẽ gặp `NoSuchBeanDefinitionException`. **Vì sao?** Vì khi không có stereotype annotation, component scan không "thấy" class này → nó không phải bean → container không có gì để trả về.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tự `new` bean trong code rồi thắc mắc sao DI không chạy.** Object bạn `new` **không** do container quản lý → mọi `@Autowired` bên trong nó đều null. Luôn để Spring tạo bean.
- **Class bean nằm ngoài phạm vi component scan.** Spring Boot mặc định chỉ quét package chứa class `@SpringBootApplication` **và các package con**. Đặt bean ở package "anh em" bên ngoài → không được quét → `NoSuchBeanDefinitionException`.
- **Nhầm `@Component` với `@Bean`.** `@Component` lên class, `@Bean` lên method trong `@Configuration`. Dán `@Bean` lên class hoặc `@Component` lên method là sai.
- **Tưởng ApplicationContext là lazy như BeanFactory.** `ApplicationContext` tạo eager mọi singleton lúc startup → nếu cấu hình sai, app **chết ngay khi khởi động** (đây là điều tốt, nhưng đừng bất ngờ).
- **Lạm dụng `context.getBean()` rải rác khắp code.** Đó là "service locator", phản pattern. Hãy để Spring inject phụ thuộc qua constructor; chỉ dùng `getBean` ở rìa ngoài (main, test, framework code).
- **Quên rằng singleton bean sống suốt đời app.** Đặt field mutable (ví dụ một `Map` đếm số bid) trong singleton mà không đồng bộ → bug đa luồng (khác hẳn Laravel reset mỗi request).

---

## 🚀 Liên hệ Spring Boot / Production

- Trong Spring Boot, `@SpringBootApplication` **đã gộp sẵn** `@Configuration` + `@ComponentScan` + `@EnableAutoConfiguration`. Bạn hiếm khi tự tạo `ApplicationContext` — `SpringApplication.run(App.class, args)` làm điều đó.
- **Auto-configuration** là IoC ở quy mô lớn: Boot tự đăng ký hàng trăm bean hạ tầng (DataSource, Tomcat, Jackson, DispatcherServlet...) dựa trên những gì có trong classpath. Bạn override bằng cách định nghĩa `@Bean` cùng kiểu (Boot lùi lại nhờ `@ConditionalOnMissingBean`).
- **Fail-fast lúc startup** nhờ eager init giúp phát hiện cấu hình sai (thiếu bean, sai datasource URL...) **trước khi** app nhận traffic — rất quan trọng cho readiness probe trong Kubernetes.
- Dùng endpoint Actuator `/actuator/beans` để **soi toàn bộ bean** trong container production — cực hữu ích khi debug "vì sao bean của tôi không được tạo".

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Từ hôm nay, Auction API bắt đầu **chuyển mình sang Spring**. Ta dựng container và biến service đấu giá đầu tiên thành bean.

**Nhiệm vụ Day 26:**

1. Tạo project Spring Boot 3 (Java 21) tên `auction-api` qua [start.spring.io] (chọn dependency `Spring Web`). Hoặc dùng container chay như phần thực hành nếu chưa quen Maven/Gradle.
2. Tạo interface `BidRepository` và một implementation `InMemoryBidRepository` (dùng `ConcurrentHashMap` lưu bid theo `auctionId`). Đánh dấu `@Repository`.
3. Tạo `AuctionService` (`@Service`) **nhận `BidRepository` qua constructor**. Thêm method `placeBid(long auctionId, String bidder, long amount)` ghi bid vào repository.
4. Trong `main`, lấy `AuctionService` ra từ `ApplicationContext` và đặt thử vài bid; in danh sách bean bằng `getBeanDefinitionNames()`.
5. Chứng minh `AuctionService` là singleton (hai lần `getBean` cho cùng instance).
6. Ghi vào `notes/day-26.md`: trả lời "IoC khác việc tự `new` object ở chỗ nào?" và "Bean trong Spring tương ứng với khái niệm gì bên Laravel?".

> 🎯 Tiêu chí đạt: app khởi động không lỗi, `placeBid` lưu được bid, và bạn giải thích được vì sao `AuctionService` không cần tự `new` `BidRepository`.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Inversion of Control là gì? "Đảo ngược" cái gì?**
> **Đáp:** IoC là nguyên lý trong đó **quyền kiểm soát việc tạo và lắp ráp phụ thuộc bị đảo từ object sang một container bên ngoài**. Thay vì object tự `new` các phụ thuộc của mình, container tạo sẵn rồi tiêm vào. "Đảo" ở đây là đảo luồng điều khiển: không phải code của bạn gọi framework để xin object, mà framework chủ động tạo và đưa object cho bạn (Hollywood Principle).

**Q2: Bean là gì? Mọi object có phải bean không?**
> **Đáp:** Bean là object **do IoC container tạo, cấu hình và quản lý vòng đời**. Không phải mọi object đều là bean — chỉ những object được đăng ký với container (qua `@Component`/`@Service`/... hoặc `@Bean`) mới là bean. Object bạn tự `new` trong method không phải bean và không được Spring quản lý.

**Q3: Khác nhau giữa `@Component` và `@Bean`?**
> **Đáp:** `@Component` (và họ hàng `@Service`/`@Repository`/`@Controller`) dán **trên class của bạn**, để Spring tự quét và tạo bean. `@Bean` dán **trên method trong class `@Configuration`**, để bạn **tự tay tạo** object và trả về làm bean. Dùng `@Bean` khi cần kiểm soát cách khởi tạo hoặc khi class không thuộc quyền sửa (thư viện bên thứ ba).

### Mức Senior

**Q4: BeanFactory và ApplicationContext khác nhau thế nào? Khi nào dùng cái nào?**
> **Đáp:** `BeanFactory` là container cơ bản, khởi tạo bean **lazy** (chỉ tạo khi `getBean`). `ApplicationContext` kế thừa `BeanFactory` và thêm: khởi tạo **eager** mọi singleton lúc startup, hỗ trợ i18n (`MessageSource`), publish event, tự nhận diện `BeanPostProcessor`/`BeanFactoryPostProcessor`, tích hợp `Environment`/`ResourceLoader`/AOP. Thực tế gần như luôn dùng `ApplicationContext`; `BeanFactory` chỉ dùng khi cần tiết kiệm bộ nhớ cực hạn (nhúng thiết bị) hoặc trong nội bộ framework.

**Q5: Vì sao IoC giúp code dễ test và bảo trì hơn?**
> **Đáp:** Vì phụ thuộc được **tiêm từ ngoài qua abstraction (interface)** thay vì object tự tạo lớp cụ thể. Lúc test, ta tiêm mock/stub vào dễ dàng mà không đụng tới production code. Khi bảo trì, đổi implementation chỉ cần đổi cấu hình bean (hoặc swap `@Bean`), không sửa nơi sử dụng — giảm coupling, tăng tuân thủ Dependency Inversion (chữ D trong SOLID).

**Q6: Vì sao ApplicationContext khởi tạo singleton eager lúc startup được coi là ưu điểm?**
> **Đáp:** Eager init thực hiện **fail-fast**: nếu cấu hình sai (thiếu bean cần inject, sai kiểu, lỗi datasource), app **chết ngay lúc khởi động** thay vì để lỗi nổ ra giữa lúc phục vụ khách. Điều này giúp phát hiện sự cố sớm (trước khi readiness probe cho phép nhận traffic), tăng độ tin cậy khi deploy. Đánh đổi là thời gian khởi động lâu hơn một chút — chấp nhận được vì app Spring là long-running.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được IoC và "đảo ngược điều khiển" bằng lời của mình
- [ ] Phân biệt được IoC container vs bean
- [ ] Phân biệt được BeanFactory vs ApplicationContext (eager/lazy + những thứ thêm)
- [ ] Khai báo được bean bằng cả `@Component` và `@Bean`
- [ ] Lấy được bean từ `ApplicationContext` và chứng minh singleton
- [ ] Ánh xạ được sang Service Container của Laravel (`bind`/`singleton`/`make`)
- [ ] Hoàn thành Mini Project Day 26 (AuctionService thành bean)
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "The IoC Container" (chương `core.html#beans`)
- Baeldung — "Inversion of Control and Dependency Injection in Spring"
- Baeldung — "Spring BeanFactory vs ApplicationContext"
- Laravel Docs — "Service Container" (đối chiếu lại để thấy điểm chung)
- Sách *Spring in Action* (Craig Walls) — chương 1 & 2 về container và bean
