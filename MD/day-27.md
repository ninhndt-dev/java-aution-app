# Day 27 - Dependency Injection

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP — đã quen container resolve constructor, nay học cách wiring chuẩn của Spring.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Dependency Injection (DI)** là **cách hiện thực IoC** (Day 26 nói "vì sao", hôm nay nói "bằng cách nào").
- Nắm **3 kiểu DI**: constructor (khuyến nghị), setter, field (không nên) — và **vì sao constructor injection tốt nhất**.
- Dùng đúng `@Autowired` (by type), `@Qualifier`/`@Primary` (khi nhiều bean cùng kiểu), `@Resource` (by name).
- Biết các kỹ thuật nâng cao: **inject `List`/`Map` bean**, `ObjectProvider`, `Optional`.
- Hiểu **circular dependency** xuất hiện thế nào và cách constructor injection phát hiện sớm.
- Ánh xạ sang Laravel: container tự resolve qua **type-hint constructor** — gần như giống hệt constructor injection.

---

## 🧠 Lý thuyết cốt lõi

### 1. DI là gì và quan hệ với IoC

- **IoC** (Day 26) là **nguyên lý**: đảo quyền tạo phụ thuộc cho container.
- **DI** là **kỹ thuật cụ thể** để thực hiện IoC: container **tiêm (inject)** các phụ thuộc đã tạo sẵn vào bean.

```
IoC  = "Container, anh quản lý phụ thuộc giúp tôi"   (nguyên lý)
 │
 └─ DI = "Container tiêm phụ thuộc vào qua constructor/setter/field"  (cách làm)
```

> 💡 Ngoài DI, IoC còn có thể hiện thực bằng "Service Locator" (object tự đi hỏi container xin phụ thuộc). Nhưng DI được ưa chuộng hơn vì phụ thuộc **lộ rõ** ra ngoài (qua constructor), dễ test, không giấu coupling.

### 2. Ba kiểu Dependency Injection

#### A. Constructor Injection (KHUYẾN NGHỊ ✅)

Phụ thuộc được truyền qua **constructor**:

```java
@Service
public class AuctionService {
    private final BidRepository bidRepository;       // final -> bất biến
    private final NotificationService notifier;

    // Từ Spring 4.3+: nếu chỉ có 1 constructor, KHÔNG cần @Autowired
    public AuctionService(BidRepository bidRepository, NotificationService notifier) {
        this.bidRepository = bidRepository;
        this.notifier = notifier;
    }
}
```

#### B. Setter Injection

Phụ thuộc được truyền qua **setter method**:

```java
@Service
public class AuctionService {
    private BidRepository bidRepository; // KHÔNG final được -> có thể bị thay đổi

    @Autowired // báo Spring gọi setter này để inject
    public void setBidRepository(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }
}
```

Dùng khi: phụ thuộc **tùy chọn** (optional) hoặc cần **thay đổi sau khi tạo**. Hiếm.

#### C. Field Injection (KHÔNG NÊN ❌)

Tiêm thẳng vào field bằng reflection:

```java
@Service
public class AuctionService {
    @Autowired                       // ngắn gọn nhưng nhiều nhược điểm
    private BidRepository bidRepository;
}
```

> ⚠️ **Vì sao field injection bị chê?** (1) Không `final` được → không bất biến. (2) Ẩn phụ thuộc — nhìn constructor không biết class cần gì. (3) **Khó test**: muốn tạo object trong unit test phải dùng reflection hoặc `@InjectMocks`, không thể `new` với mock truyền vào. (4) Dễ giấu **quá nhiều phụ thuộc** (class phình to mà không ai để ý). (5) Không phát hiện được circular dependency lúc khởi tạo. Spring team chính thức khuyến nghị **constructor injection**.

### 3. Vì sao constructor injection tốt nhất?

| Tiêu chí | Constructor ✅ | Setter | Field ❌ |
|---|---|---|---|
| Cho phép `final` (bất biến) | Có | Không | Không |
| Phụ thuộc **bắt buộc** được đảm bảo | Có (không tạo được nếu thiếu) | Không | Không |
| Test dễ (chỉ cần `new` với mock) | Rất dễ | Trung bình | Khó (cần reflection) |
| Phụ thuộc **lộ rõ** trong API | Có | Một phần | Không (ẩn) |
| Phát hiện circular dependency sớm | Có (lỗi lúc startup) | Trễ | Trễ/ngầm |
| Thread-safe sau khi tạo | Có (object đầy đủ ngay) | Không chắc | Không chắc |

> 💡 Constructor injection ép object **"đầy đủ ngay khi sinh ra"** — không có trạng thái "nửa vời" (đã tạo nhưng chưa được inject). Đây là nguyên tắc thiết kế object tốt, đúng với cả Java thuần lẫn Spring.

### 4. `@Autowired`, `@Qualifier`, `@Primary`, `@Resource`

- **`@Autowired`** — resolve **theo kiểu (by type)**. Spring tìm bean khớp kiểu để tiêm. Trên constructor là tùy chọn (nếu 1 constructor); trên setter/field thì bắt buộc.

Vấn đề: nếu có **nhiều bean cùng kiểu** (ví dụ 2 implementation của `PricingStrategy`), Spring không biết chọn cái nào → `NoUniqueBeanDefinitionException`. Giải quyết:

- **`@Primary`** — đánh dấu **một** bean là "ưu tiên mặc định" khi có nhiều lựa chọn:

```java
@Primary
@Component
public class StandardPricingStrategy implements PricingStrategy { ... }
```

- **`@Qualifier("tenBean")`** — chỉ định **chính xác tên bean** muốn tiêm, ghi đè `@Primary`:

```java
public AuctionService(@Qualifier("vipPricingStrategy") PricingStrategy strategy) { ... }
```

- **`@Resource(name = "...")`** (từ Jakarta) — resolve **theo tên (by name)** trước, rồi mới theo kiểu. Khác với `@Autowired` (type trước).

```
@Autowired           → khớp THEO KIỂU (type), nhập nhằng thì xét @Primary / @Qualifier / tên
@Resource(name=...)  → khớp THEO TÊN (name) trước
```

> 💡 Quy tắc resolve của `@Autowired` khi nhiều bean: (1) lọc theo kiểu → còn nhiều; (2) có `@Qualifier`? dùng nó; (3) có bean nào `@Primary`? dùng nó; (4) khớp **tên field/tham số** với tên bean; (5) vẫn nhập nhằng → ném exception.

### 5. Inject tập hợp bean (`List` / `Map`)

Khi có nhiều bean cùng kiểu mà bạn muốn **tất cả**, hãy inject `List` hoặc `Map`:

```java
@Service
public class PricingEngine {
    private final List<PricingStrategy> strategies;       // nhận TẤT CẢ bean PricingStrategy
    private final Map<String, PricingStrategy> byName;    // key = tên bean

    public PricingEngine(List<PricingStrategy> strategies,
                         Map<String, PricingStrategy> byName) {
        this.strategies = strategies;
        this.byName = byName;
    }
}
```

> 💡 Dùng `@Order(n)` trên từng bean để kiểm soát **thứ tự** trong `List`. Rất hợp cho mẫu **Strategy/Chain** (ví dụ một chuỗi validator cho bid).

### 6. Phụ thuộc tùy chọn: `Optional` & `ObjectProvider`

```java
// Cách 1: Optional -> nếu không có bean thì là Optional.empty(), không lỗi
public AuctionService(Optional<DiscountPolicy> discount) { ... }

// Cách 2: ObjectProvider -> lazy, an toàn khi 0 hoặc nhiều bean
private final ObjectProvider<DiscountPolicy> discountProvider;
public AuctionService(ObjectProvider<DiscountPolicy> discountProvider) {
    this.discountProvider = discountProvider;
}
// dùng: discountProvider.ifAvailable(policy -> ...);  // chỉ chạy nếu có bean
```

`ObjectProvider` còn giúp **trì hoãn** (lazy) việc lấy bean — hữu ích để **phá vòng lặp** circular dependency.

### 7. Circular Dependency (phụ thuộc vòng)

Xảy ra khi A cần B và B cần A:

```
   AuctionService ──cần──► BidValidator
        ▲                       │
        └──────── cần ──────────┘
```

- Với **constructor injection**, Spring **không thể** tạo A (cần B trước) cũng không thể tạo B (cần A trước) → ném `BeanCurrentlyInCreationException` **ngay lúc startup** (fail-fast, tốt!).
- Với **setter/field injection**, Spring có thể "tạo nửa vời" rồi inject sau → vòng lặp bị che giấu, dễ sinh bug.

**Cách xử lý đúng**: vòng lặp là **dấu hiệu thiết kế sai** — tách trách nhiệm, tạo abstraction trung gian, hoặc dùng event. Cách "chữa cháy" tạm: `@Lazy` trên một phía, hoặc `ObjectProvider` để trì hoãn.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là phần bạn sẽ gật gù: **Laravel cũng làm constructor injection**, gần như y hệt.

```php
class AuctionService
{
    // Laravel container TỰ resolve các phụ thuộc qua type-hint constructor
    public function __construct(
        private BidRepository $bidRepository,
        private NotificationService $notifier,
    ) {}
}
// Khi resolve: app()->make(AuctionService::class) -> Laravel tự new các tham số
```

So với Spring:

```java
@Service
public class AuctionService {
    public AuctionService(BidRepository bidRepository, NotificationService notifier) {
        this.bidRepository = bidRepository;
        this.notifier = notifier;
    }
}
```

| Khái niệm Spring | Laravel tương đương | Ghi chú |
|---|---|---|
| Constructor injection | Type-hint trong `__construct` | Gần như giống hệt — cả hai container đọc kiểu tham số rồi resolve |
| `@Autowired` (by type) | Auto-resolution theo type-hint | Laravel mặc định luôn theo type |
| `@Qualifier("x")` | **Contextual binding** (`when(...)->needs(...)->give(...)`) | Chọn implementation cụ thể cho một class |
| `@Primary` | `bind(Interface::class, Concrete::class)` (binding mặc định) | Định nghĩa "mặc định" cho một interface |
| Inject `List<T>` (tất cả bean) | `tagged('group')` + resolve nhóm tag | Lấy một nhóm binding |
| `Optional<T>` / `ObjectProvider<T>` | Resolve có điều kiện / `App::bound(...)` kiểm tra | Phụ thuộc tùy chọn |
| Circular dependency error | Laravel cũng báo lỗi vòng resolve | Cả hai đều coi là sai thiết kế |

> 💡 **Khác biệt nhỏ nhưng quan trọng**: Laravel resolve interface phải có **binding tường minh** trong ServiceProvider (`bind(Interface, Concrete)`), vì PHP interface không tự biết implementation. Spring tự quét tất cả implementation qua component scan; khi có nhiều, bạn dùng `@Primary`/`@Qualifier` để chọn — tương tự `bind`/contextual binding của Laravel.

---

## 💻 Thực hành code

### Bước 1 — Xây dựng API đặt giá bằng Constructor Injection

Ta sẽ kết nối `AuctionController`, `BidService` và `BidRepository` với nhau.

1. Hoàn thiện `BidService` (sử dụng Constructor Injection thay vì Field Injection):

```java
// File: BidService.java
package com.example.auction;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class BidService {
    private final BidRepository bidRepository;

    // Điền annotation nào nếu có nhiều constructor? (Từ Spring 4.3+, 1 constructor thì không cần)
    // @___
    public BidService(BidRepository bidRepository) {
        this.bidRepository = bidRepository;
    }

    public Bid placeBid(long auctionId, String bidder, long amount) {
        // Validate cơ bản (chưa dùng @Valid)
        if (amount <= 0) ___ new IllegalArgumentException("Amount phải > 0"); // Điền từ khóa ném ngoại lệ
        
        Bid bid = ___ Bid(auctionId, bidder, amount); // Điền từ khóa khởi tạo đối tượng
        bidRepository.save(bid);
        return bid;
    }
    
    public List<Bid> getBids(long auctionId) {
        return bidRepository.findByAuctionId(auctionId); // Cần thêm method này vào repository
    }
}
```

2. Cập nhật `AuctionController` để gọi `BidService`:

```java
// File: AuctionController.java
package com.example.auction;

import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/auctions")
public class AuctionController {

    private final BidService bidService;

    // Spring tự tiêm BidService vào đây
    public AuctionController(___ bidService) { // Điền kiểu dữ liệu của service
        this.bidService = bidService;
    }

    @___("/{id}/bids") // Điền HTTP method tương ứng để TẠO MỚI
    public Bid placeBid(@PathVariable long id, @RequestBody BidRequest req) {
        return bidService.placeBid(id, req.bidder(), req.amount());
    }
    
    @___("/{id}/bids") // Điền HTTP method tương ứng để LẤY DANH SÁCH
    public List<Bid> getBids(@PathVariable long id) {
        return bidService.getBids(id);
    }
}

// DTO request — record ngắn gọn (Java 21)
record BidRequest(String bidder, long amount) {}
```

3. **Kiểm tra kết quả bằng curl:**

```bash
# Đặt một bid hợp lệ
curl -X POST http://localhost:8080/api/auctions/1/bids \
  -H "Content-Type: application/json" \
  -d '{"bidder":"Alice","amount":500000}'
# Expected: {"auctionId":1,"bidder":"Alice","amount":500000}

# Lấy danh sách bid của phiên số 1
curl http://localhost:8080/api/auctions/1/bids
# Expected: [{"auctionId":1,"bidder":"Alice","amount":500000}]
```

### Bước 2 — Nhiều `PricingStrategy`, chọn bằng `@Qualifier` / `@Primary`

Hãy cấu hình để Spring biết nên chọn chiến lược giá nào khi có nhiều implementation.

```java
// File: StandardPricingStrategy.java
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@___                          // Đánh dấu bean này là ưu tiên mặc định
@Component("standardPricing")
public class StandardPricingStrategy ___ PricingStrategy { // Điền từ khóa triển khai interface
    public long minIncrement(long currentPrice) {
        return Math.max(10_000, currentPrice / 100); 
    }
}
```

```java
// File: VipPricingStrategy.java
import org.springframework.stereotype.Component;

@Component("vipPricing")
public class VipPricingStrategy implements PricingStrategy {
    public long minIncrement(long currentPrice) {
        return Math.max(1_000, currentPrice / 1000); 
    }
}
```

```java
// File: AuctionService.java
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AuctionService {
    private final PricingStrategy pricing;

    // Chọn bean cụ thể tên là "vipPricing"
    public AuctionService(@___("vipPricing") PricingStrategy pricing) {
        this.pricing = pricing;
    }
}
```

### Bước 3 — CHALLENGE: Inject `List`/`Map` cho chuỗi validator (Thử thách)

> 🏆 Yêu cầu:
> 1. Tạo interface `BidValidator` có hàm `void validate(BidRequest req)`.
> 2. Tạo `AmountValidator` (ném lỗi nếu `amount <= 0`) và `BidderValidator` (ném lỗi nếu `bidder` rỗng). Cả hai đều là `@Component`.
> 3. Trong `BidService`, inject **tất cả** validator bằng `List<BidValidator>` và duyệt qua chúng trong hàm `placeBid`.
> 4. **Câu hỏi:** Khi bạn gửi request với `amount = -1` và `bidder = ""`, lỗi nào sẽ văng ra trước? Hãy dùng `@Order` trên các validator để thay đổi lỗi văng ra và quan sát bằng `curl`!


---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng field injection cho tiện rồi khổ lúc test.** `@Autowired private Foo foo;` khiến unit test phải dùng reflection. Hãy chuyển sang constructor injection.
- **`NoUniqueBeanDefinitionException`** khi có ≥2 bean cùng kiểu mà không `@Primary`/`@Qualifier`. Đọc message: Spring liệt kê đúng các bean ứng viên.
- **`@Qualifier` sai tên bean.** Tên bean mặc định là **tên class viết thường chữ đầu** (`StandardPricingStrategy` → `standardPricingStrategy`), trừ khi bạn đặt tên trong `@Component("...")`. Sai tên → `NoSuchBeanDefinitionException`.
- **Tiêm implementation cụ thể thay vì interface.** Hãy tiêm `PricingStrategy` (interface), đừng tiêm `StandardPricingStrategy` (class) — mất hết lợi ích đảo phụ thuộc.
- **Circular dependency với field injection bị che giấu.** Nó "chạy được" nhưng tạo object nửa vời, dễ NPE ngầm. Constructor injection báo lỗi sớm — đó là điều tốt.
- **Quên rằng phụ thuộc constructor là bắt buộc.** Nếu một phụ thuộc là tùy chọn, đừng để nó vào constructor như bắt buộc → dùng `Optional<T>` hoặc `ObjectProvider<T>`.
- **Lạm dụng `@Autowired` trên quá nhiều field** che giấu việc class đang gánh quá nhiều trách nhiệm (vi phạm SRP). Constructor dài là tín hiệu nên tách class.

---

## 🚀 Liên hệ Spring Boot / Production

- Spring Boot 3 + Lombok: dùng `@RequiredArgsConstructor` để tự sinh constructor cho các field `final` → giảm boilerplate mà vẫn giữ constructor injection. Đây là cách viết phổ biến nhất trong dự án thật.
- `@ConfigurationProperties` bean (Day 31) cũng được inject như mọi bean khác — bạn tiêm cả khối cấu hình qua constructor.
- Khi nâng cấp/refactor, constructor injection giúp **trình biên dịch** bắt lỗi thiếu phụ thuộc ngay (không chờ runtime) — an toàn hơn nhiều cho hệ thống lớn.
- Trong test tích hợp (`@SpringBootTest`), Spring vẫn dùng DI để wiring; trong **unit test thuần** bạn `new AuctionService(mockRepo, mockNotifier)` trực tiếp — đây là phần thưởng lớn nhất của constructor injection.
- Production tip: bật `spring.main.allow-circular-references=false` (mặc định Boot 2.6+) để app **từ chối khởi động** nếu có circular dependency — ép team sửa thiết kế thay vì né.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta **wiring toàn bộ tầng service đấu giá bằng constructor injection** và đưa API xử lý bid vào hoạt động.

**Nhiệm vụ Day 27:**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. `BidService` (`@Service`) nhận `BidRepository` qua constructor. Thêm method `placeBid` và `getBids` (như Bước 1).
3. `AuctionController` expose 2 endpoint `POST /api/auctions/{id}/bids` và `GET /api/auctions/{id}/bids`. Test bằng lệnh `curl`.
4. Giải quyết **CHALLENGE** ở Bước 3: Inject `List<BidValidator>` và thay đổi `@Order` để xem lỗi nào bị văng ra trước. Thử với request có cả amount âm và bidder rỗng.
5. `AuctionService` (`@Service`) nhận `BidService` + một `PricingStrategy` qua constructor. Áp dụng `@Qualifier` và `@Primary` như Bước 2.
6. Viết **một unit test thuần** (không Spring Boot) cho `BidService` bằng cách `new BidService(mockRepo)` với một `BidRepository` giả — chứng minh constructor injection giúp test dễ ra sao.
7. Ghi `notes/day-27.md`: "Vì sao constructor injection tốt hơn field injection?" và "Type-hint constructor của Laravel tương ứng kiểu DI nào của Spring?".

> 🎯 Tiêu chí đạt: API đặt giá và lấy danh sách chạy đúng, validation bằng List hoạt động và đổi thứ tự bằng @Order, unit test thuần chạy xanh.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Dependency Injection là gì? Quan hệ với IoC?**
> **Đáp:** DI là **kỹ thuật hiện thực IoC**: container tiêm các phụ thuộc đã tạo sẵn vào bean (qua constructor/setter/field) thay vì bean tự tạo. IoC là nguyên lý ("ai quản lý phụ thuộc"), DI là cách làm cụ thể ("tiêm vào bằng cách nào"). DI được ưa chuộng vì phụ thuộc lộ rõ, dễ test, không giấu coupling.

**Q2: Có mấy kiểu DI? Nên dùng kiểu nào?**
> **Đáp:** 3 kiểu: **constructor** (truyền qua hàm dựng — khuyến nghị), **setter** (qua setter — cho phụ thuộc tùy chọn), **field** (tiêm thẳng field bằng reflection — không nên). Nên dùng constructor injection vì cho phép `final` (bất biến), đảm bảo phụ thuộc bắt buộc, dễ test (chỉ cần `new` với mock), và lộ rõ phụ thuộc.

**Q3: `@Autowired` resolve bean theo gì? Xử lý sao khi có nhiều bean cùng kiểu?**
> **Đáp:** `@Autowired` resolve **theo kiểu (by type)**. Khi có nhiều bean cùng kiểu → `NoUniqueBeanDefinitionException`. Xử lý: dùng `@Primary` đánh dấu bean mặc định, hoặc `@Qualifier("tenBean")` chỉ định chính xác. Ngoài ra Spring còn xét khớp tên field/tham số với tên bean như phương án dự phòng.

### Mức Senior

**Q4: Vì sao field injection bị xem là practice xấu?**
> **Đáp:** Vì: (1) không `final` được → object có thể bị thay đổi/không bất biến; (2) ẩn phụ thuộc — nhìn constructor không biết class cần gì; (3) khó unit test (phải dùng reflection/`@InjectMocks`, không `new` được với mock); (4) dễ giấu việc class gánh quá nhiều phụ thuộc (vi phạm SRP); (5) không phát hiện circular dependency sớm. Spring chính thức khuyến nghị constructor injection.

**Q5: Circular dependency là gì? Constructor injection xử lý nó thế nào so với field injection?**
> **Đáp:** Là tình huống A cần B và B cần A. Với **constructor injection**, Spring không thể tạo bean nào trước → ném `BeanCurrentlyInCreationException` **ngay lúc startup** (fail-fast). Với **field/setter injection**, Spring tạo object nửa vời rồi inject sau, nên vòng lặp bị che giấu và dễ sinh bug ngầm. Vòng lặp là dấu hiệu thiết kế sai — nên tách trách nhiệm/abstraction; chữa tạm bằng `@Lazy` hoặc `ObjectProvider`.

**Q6: Khi nào dùng `List<T>`/`Map<String,T>` injection và `ObjectProvider<T>`?**
> **Đáp:** Inject `List<T>` để nhận **tất cả** bean cùng kiểu (mẫu Strategy/Chain — ví dụ chuỗi validator), `Map<String,T>` để tra theo tên bean; dùng `@Order` để định thứ tự. `ObjectProvider<T>` dùng cho phụ thuộc **tùy chọn** (0 hoặc nhiều bean), cho phép lấy bean **lazy** (trì hoãn) — hữu ích để phá vòng lặp hoặc tránh tạo bean nặng nếu chưa cần. `Optional<T>` cũng biểu diễn phụ thuộc tùy chọn nhưng không lazy.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được DI là cách hiện thực IoC
- [ ] Phân biệt 3 kiểu DI và biết vì sao constructor injection tốt nhất
- [ ] Dùng đúng `@Autowired`, `@Qualifier`, `@Primary`, `@Resource`
- [ ] Inject được `List`/`Map` bean và hiểu `@Order`
- [ ] Hiểu `ObjectProvider`/`Optional` cho phụ thuộc tùy chọn
- [ ] Hiểu circular dependency và cách constructor injection phát hiện sớm
- [ ] Ánh xạ được sang type-hint constructor của Laravel
- [ ] Dựng API POST và GET cho Bids, kiểm tra bằng `curl`
- [ ] Hoàn thành Challenge: Inject List validator và thử nghiệm với `@Order`
- [ ] Viết unit test thuần chứng minh lợi ích của constructor injection
- [ ] Hoàn thành Mini Project Day 27
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Dependency Injection" & "Autowiring Collaborators"
- Spring Blog — "Why field injection is evil" (Oliver Drotbohm)
- Baeldung — "Constructor Dependency Injection in Spring", "@Qualifier vs @Primary"
- Laravel Docs — "Service Container" → "Automatic Injection" & "Contextual Binding"
- Sách *Spring in Action* (Craig Walls) — chương về wiring beans
