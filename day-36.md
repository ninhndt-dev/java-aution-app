# Day 36 - JPA Internals

> **Giai đoạn:** JPA & Hibernate
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn ánh xạ kiến thức Eloquent sang JPA/Hibernate một cách chắc chắn.

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt **JPA (spec) vs Hibernate (implementation)** — đây là câu hỏi phỏng vấn kinh điển.
- Hiểu `EntityManager` và `EntityManagerFactory`.
- Nắm các annotation mapping: `@Entity`, `@Id`, `@GeneratedValue`, `@Column`, `@Table`.
- Mapping quan hệ: `@OneToMany`, `@ManyToOne`, `@ManyToMany`, `@OneToOne` + `mappedBy` + join column.
- Phân biệt **JPQL vs native query**.
- Dùng **Spring Data JPA**: `JpaRepository`, query method, `@Query`, **DTO projection**.
- Đối chiếu **mạnh** với Eloquent của Laravel, và nêu khác biệt **Data Mapper (JPA) vs Active Record (Eloquent)**.

---

## 🧠 Lý thuyết cốt lõi

### 1. JPA là spec, Hibernate là implementation

Đây là phân biệt quan trọng nhất:

```
JPA (Jakarta Persistence API)  ── là MỘT BẢN ĐẶC TẢ (interface, annotation, quy tắc)
        │  được hiện thực bởi
        ├── Hibernate   (phổ biến nhất, mặc định trong Spring Boot)
        ├── EclipseLink
        └── OpenJPA
```

- **JPA**: tập hợp interface (`EntityManager`, `Query`...) và annotation (`@Entity`, `@OneToMany`...). Nó **không tự chạy** — chỉ là "hợp đồng".
- **Hibernate**: thư viện **thực thi** hợp đồng đó (và có thêm tính năng riêng vượt chuẩn JPA). Spring Boot mặc định dùng Hibernate.

> 💡 Khi viết `@Entity`, `@OneToMany`, `EntityManager` → bạn dùng **API của JPA**. Khi gặp tính năng đặc thù (`@org.hibernate.annotations.*`, batch fetching, second-level cache cụ thể) → đó là **Hibernate**. Lập trình theo JPA giúp code dễ đổi implementation.

### 2. `EntityManager` và `EntityManagerFactory`

```
EntityManagerFactory  ── nặng, tạo MỘT LẦN cho cả app (một per datasource)
        │  tạo ra
        ▼
EntityManager  ── nhẹ, MỘT cho mỗi transaction/đơn vị công việc
        │  quản lý
        ▼
Persistence Context  ── "không gian làm việc" chứa các entity đang quản lý (Day 37)
```

- **`EntityManagerFactory`**: tốn tài nguyên, khởi tạo một lần lúc app start (Spring Boot tự lo).
- **`EntityManager`**: "cửa ngõ" thao tác với DB qua JPA — `persist()`, `find()`, `merge()`, `remove()`, tạo query. Mỗi transaction có một `EntityManager` riêng.

Trong Spring Data JPA, bạn **hiếm khi** dùng trực tiếp `EntityManager` — repository làm thay. Nhưng phải hiểu nó tồn tại bên dưới.

### 3. Annotation mapping cơ bản

```java
import jakarta.persistence.*;
import java.time.Instant;

@Entity                              // đánh dấu đây là entity (ánh xạ tới một bảng)
@Table(name = "auctions")            // tên bảng (mặc định = tên class)
public class Auction {

    @Id                              // khóa chính
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // auto-increment ở DB
    private Long id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "start_price", nullable = false)
    private long startPrice;

    @Column(name = "current_price")
    private long currentPrice;

    @Enumerated(EnumType.STRING)     // lưu enum dưới dạng chuỗi "OPEN"/"CLOSED"
    private AuctionStatus status;

    @Column(name = "ends_at")
    private Instant endsAt;

    // JPA YÊU CẦU constructor không tham số (protected/public)
    protected Auction() {}
    // ... constructor khác, getter/setter ...
}
```

| Annotation | Ý nghĩa |
|---|---|
| `@Entity` | Class này ánh xạ tới một bảng. |
| `@Table(name=...)` | Tên bảng (tùy chọn). |
| `@Id` | Khóa chính. |
| `@GeneratedValue` | Cách sinh khóa: `IDENTITY` (auto-increment), `SEQUENCE`, `UUID`... |
| `@Column` | Cấu hình cột (tên, nullable, length, unique). |
| `@Enumerated(EnumType.STRING)` | Lưu enum dạng chuỗi (luôn ưu tiên STRING hơn ORDINAL). |

> ⚠️ **JPA bắt buộc có constructor không tham số.** Hibernate dùng nó để khởi tạo entity qua reflection. Quên là gặp lỗi lúc runtime.

### 4. Mapping quan hệ — phần dễ sai nhất

Quan hệ `Auction` (1) ── (N) `Bid`: một phiên có nhiều lượt đặt giá.

```java
@Entity
@Table(name = "bids")
public class Bid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private long amount;

    @ManyToOne(fetch = FetchType.LAZY)        // NHIỀU bid thuộc MỘT auction
    @JoinColumn(name = "auction_id")          // cột khóa ngoại nằm ở bảng bids
    private Auction auction;                  // phía "sở hữu" quan hệ (owning side)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User bidder;
}
```

```java
@Entity
@Table(name = "auctions")
public class Auction {
    // ... id, fields ...

    // mappedBy = "auction": phía NÀY chỉ "soi" quan hệ, KHÔNG sở hữu khóa ngoại
    @OneToMany(mappedBy = "auction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Bid> bids = new ArrayList<>();
}
```

**Quy tắc vàng về `mappedBy` và owning side:**

```
Quan hệ @OneToMany/@ManyToOne  ──►  KHÓA NGOẠI nằm ở bảng phía "Many" (bids.auction_id)
        │
        ├── Phía @ManyToOne (Bid.auction)  = OWNING SIDE — nơi đặt @JoinColumn,
        │                                     thay đổi ở đây mới được ghi vào DB
        └── Phía @OneToMany (Auction.bids) = INVERSE SIDE — dùng mappedBy="auction",
                                              chỉ để đọc/đồng bộ, không tự ghi FK
```

| Quan hệ | Annotation | FK nằm ở | mappedBy ở phía |
|---|---|---|---|
| Many-to-One / One-to-Many | `@ManyToOne` + `@OneToMany(mappedBy)` | bảng "Many" | phía `@OneToMany` |
| One-to-One | `@OneToOne` + `@JoinColumn` (một phía) | phía có `@JoinColumn` | phía kia (`mappedBy`) |
| Many-to-Many | `@ManyToMany` + `@JoinTable` | **bảng nối** trung gian | một phía dùng `mappedBy` |

> ⚠️ `mappedBy` luôn trỏ tới **tên field ở phía bên kia** (không phải tên cột). Đặt sai `mappedBy` là nguồn lỗi mapping kinh điển. Quy tắc: phía có `@JoinColumn` là owning side; phía có `mappedBy` là inverse side.

> 💡 **Luôn để quan hệ `@ManyToOne`/`@OneToMany` là `LAZY`** (mặc định `@ManyToOne` là EAGER — nên đặt lại LAZY rõ ràng) để tránh kéo cả cây dữ liệu. Vấn đề lazy/N+1 sẽ học kỹ ở Day 37.

### 5. JPQL vs Native Query

- **JPQL (Jakarta Persistence Query Language)**: giống SQL nhưng thao tác trên **entity và field** (không phải bảng/cột). Độc lập DB.
  ```java
  // Lưu ý: dùng tên ENTITY (Auction) và tên FIELD (status), không phải tên bảng/cột
  @Query("SELECT a FROM Auction a WHERE a.status = :status ORDER BY a.endsAt")
  List<Auction> findOpenAuctions(@Param("status") AuctionStatus status);
  ```
- **Native query**: SQL thuần, gắn với DB cụ thể. Dùng khi cần tính năng đặc thù DB.
  ```java
  @Query(value = "SELECT * FROM auctions WHERE current_price > ?1", nativeQuery = true)
  List<Auction> findExpensive(long min);
  ```

> 💡 Ưu tiên JPQL (portable, type-safe hơn). Chỉ dùng native query khi cần SQL đặc thù (window function, hint, tối ưu DB-specific).

### 6. Spring Data JPA — viết repository không cần code

Spring Data JPA sinh implementation repository **tự động** từ interface:

```java
public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // 1. Query method: Spring TỰ sinh câu query từ TÊN method
    List<Auction> findByStatus(AuctionStatus status);
    List<Auction> findByStatusAndCurrentPriceGreaterThan(AuctionStatus status, long price);
    Optional<Auction> findByTitle(String title);
    long countByStatus(AuctionStatus status);

    // 2. @Query: tự viết JPQL khi tên method quá phức tạp
    @Query("SELECT a FROM Auction a WHERE a.endsAt < :now AND a.status = 'OPEN'")
    List<Auction> findExpiredButOpen(@Param("now") Instant now);
}
```

`JpaRepository<Auction, Long>` đã có sẵn `save`, `findById`, `findAll`, `delete`, `count`, phân trang (`Pageable`), sắp xếp (`Sort`)...

**Quy ước đặt tên query method** (Spring parse tên thành query):
`findBy` + `<Field>` + `<Toán tử>` — ví dụ `findByCurrentPriceLessThanEqual`, `findByTitleContainingIgnoreCase`, `findByStatusOrderByEndsAtDesc`.

### 7. DTO Projection — chỉ lấy cột cần

Tránh nạp cả entity nặng khi chỉ cần vài trường. JPA hỗ trợ chiếu (projection) thẳng vào DTO:

```java
// DTO dạng record (Java 21)
public record AuctionSummary(Long id, String title, long currentPrice) {}

public interface AuctionRepository extends JpaRepository<Auction, Long> {
    // Constructor expression trong JPQL → tạo thẳng DTO, không nạp entity đầy đủ
    @Query("SELECT new com.example.auction.dto.AuctionSummary(a.id, a.title, a.currentPrice) " +
           "FROM Auction a WHERE a.status = :status")
    List<AuctionSummary> findSummariesByStatus(@Param("status") AuctionStatus status);
}
```

Cũng có **interface-based projection** (Spring tự sinh):
```java
public interface AuctionView {
    Long getId();
    String getTitle();
    long getCurrentPrice();
}
// List<AuctionView> findByStatus(AuctionStatus status);  → chỉ SELECT 3 cột
```

---

## 🔁 Đối chiếu với Laravel/PHP — Eloquent ↔ JPA

Đây là phần ánh xạ **quan trọng nhất** với bạn:

| Khái niệm | Eloquent (Laravel) | JPA / Hibernate |
|---|---|---|
| Định nghĩa model | `class Auction extends Model` | `@Entity class Auction` |
| Tên bảng | `protected $table = 'auctions'` | `@Table(name="auctions")` |
| Khóa chính | `protected $primaryKey = 'id'` | `@Id @GeneratedValue` |
| Cột gán hàng loạt | `$fillable` / `$guarded` | (không có "mass assignment guard"; map field tường minh) |
| Migration/schema | `Schema::create(...)` migration | `@Column`, hoặc Flyway/Liquibase riêng |
| 1-N | `hasMany(Bid::class)` | `@OneToMany(mappedBy="auction")` |
| N-1 | `belongsTo(Auction::class)` | `@ManyToOne @JoinColumn` |
| N-N | `belongsToMany(...)` | `@ManyToMany @JoinTable` |
| Lưu | `$auction->save()` | `repository.save(auction)` |
| Tìm | `Auction::find($id)` | `repository.findById(id)` |
| Query builder | `Auction::where('status','OPEN')->get()` | `findByStatus(OPEN)` / `@Query` |
| Eager load | `Auction::with('bids')->get()` | `@EntityGraph` / `JOIN FETCH` (Day 37) |
| Lazy load mặc định | quan hệ lazy (truy cập mới query) | `@ManyToOne` mặc định EAGER, `@OneToMany` LAZY |

**KHÁC BIỆT CỐT LÕI — Active Record vs Data Mapper:**

```
Eloquent = ACTIVE RECORD
   Model VỪA là dữ liệu VỪA biết tự lưu mình: $auction->save()
   Logic persistence nằm TRONG model (kế thừa Model).

JPA = DATA MAPPER
   Entity là object thuần (POJO), KHÔNG biết tự lưu.
   EntityManager / Repository (lớp riêng) chịu trách nhiệm persistence.
   repository.save(auction)  ← lớp ngoài thao tác trên entity.
```

> 🧩 Hệ quả thực tế:
> - Eloquent: `$auction->bids` truy cập là tự query (lazy ngầm). Tiện nhưng dễ N+1.
> - JPA: entity là POJO; muốn lưu phải qua repository/`EntityManager`. Tách bạch hơn, dễ test (entity không phụ thuộc DB), nhưng dài dòng hơn.
> - Eloquent map cột tự động theo tên; JPA **tường minh** hơn (khai báo `@Column`, kiểu, quan hệ rõ ràng) — đổi lại an toàn kiểu và rõ ý đồ.

> ⚠️ Đừng tìm "`$fillable`" trong JPA — không có khái niệm mass-assignment. Trong JPA bạn map field rõ ràng và thường dùng **DTO** để nhận dữ liệu từ client (tránh gán thẳng vào entity).

---

## 💻 Thực hành code

### Bài 1 — Entity `User`, `Auction`, `Bid`

```java
package com.example.auction.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role = "USER";

    protected User() {}
    public User(String username, String passwordHash, String role) {
        this.username = username; this.passwordHash = passwordHash; this.role = role;
    }
    // getters...
}
```

```java
@Entity
@Table(name = "auctions")
public class Auction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "start_price", nullable = false)
    private long startPrice;

    @Column(name = "current_price", nullable = false)
    private long currentPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuctionStatus status = AuctionStatus.OPEN;

    @Column(name = "ends_at")
    private Instant endsAt;

    @OneToMany(mappedBy = "auction", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Bid> bids = new ArrayList<>();

    protected Auction() {}

    public Auction(String title, long startPrice, Instant endsAt) {
        this.title = title;
        this.startPrice = startPrice;
        this.currentPrice = startPrice;
        this.endsAt = endsAt;
    }

    // Helper giữ đồng bộ cả hai phía quan hệ (rất quan trọng!)
    public void addBid(Bid bid) {
        bids.add(bid);
        bid.setAuction(this);        // đồng bộ owning side
        this.currentPrice = bid.getAmount();
    }
    // getters/setters...
}
```

```java
@Entity
@Table(name = "bids")
public class Bid {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long amount;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;          // owning side

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User bidder;

    protected Bid() {}
    public Bid(long amount, User bidder) { this.amount = amount; this.bidder = bidder; }
    public void setAuction(Auction a) { this.auction = a; }
    public long getAmount() { return amount; }
    // getters/setters...
}
```

### Bài 2 — Repository với query method

```java
package com.example.auction.repository;

import com.example.auction.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    List<Auction> findByStatus(AuctionStatus status);

    List<Auction> findByStatusOrderByEndsAtAsc(AuctionStatus status);

    long countByStatus(AuctionStatus status);

    @Query("SELECT a FROM Auction a WHERE a.endsAt < :now AND a.status = 'OPEN'")
    List<Auction> findExpiredButOpen(@Param("now") Instant now);
}

public interface BidRepository extends JpaRepository<Bid, Long> {
    List<Bid> findByAuctionIdOrderByAmountDesc(Long auctionId);
    Optional<Bid> findTopByAuctionIdOrderByAmountDesc(Long auctionId);  // giá cao nhất
}
```

### Bài 3 — Service đặt giá dùng repository

```java
@Service
public class AuctionService {

    private final AuctionRepository auctionRepository;
    private final UserRepository userRepository;

    public AuctionService(AuctionRepository auctionRepository, UserRepository userRepository) {
        this.auctionRepository = auctionRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public Bid placeBid(Long auctionId, String username, long amount) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionNotFoundException(auctionId));

        if (amount <= auction.getCurrentPrice()) {
            throw new BidTooLowException(amount, auction.getCurrentPrice());
        }

        User bidder = userRepository.findByUsername(username).orElseThrow();
        Bid bid = new Bid(amount, bidder);
        auction.addBid(bid);                 // cascade ALL → bid được lưu cùng
        auctionRepository.save(auction);     // (thực ra dirty checking tự lo — xem Day 37)
        return bid;
    }
}
```

### Bài 4 — Cấu hình kết nối DB

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/auction
    username: auction
    password: secret
  jpa:
    hibernate:
      ddl-auto: validate        # validate schema; KHÔNG dùng update/create-drop ở production
    show-sql: true              # in SQL (chỉ bật khi dev)
    properties:
      hibernate.format_sql: true
```

> ✅ **Bài tập tự giải thích:** Vì sao đặt `@ManyToOne(fetch = LAZY)` mà không để mặc định EAGER? Và vì sao `ddl-auto: update` nguy hiểm ở production?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Quên constructor không tham số** → Hibernate không khởi tạo được entity, lỗi runtime.
- **`mappedBy` trỏ sai field** → quan hệ không đồng bộ, FK không được ghi, hoặc tạo bảng nối thừa. `mappedBy` trỏ tới **tên field** ở phía owning, không phải tên cột.
- **Quên đồng bộ cả hai phía quan hệ** (chỉ `bids.add(bid)` mà quên `bid.setAuction(this)`) → khi flush, FK `auction_id` bị null.
- **Để `@ManyToOne` EAGER (mặc định)** → mỗi lần nạp entity kéo theo cả quan hệ → query thừa, dễ N+1. Đặt `LAZY` rõ ràng.
- **Dùng `ddl-auto: update`/`create-drop` ở production** → Hibernate tự đổi/xóa schema, cực kỳ nguy hiểm. Dùng `validate` + migration tool (Flyway/Liquibase).
- **Lưu enum bằng `EnumType.ORDINAL` (mặc định)** → lưu số thứ tự; chỉ cần đảo thứ tự enum là dữ liệu sai nghĩa. Luôn dùng `EnumType.STRING`.
- **Trả entity ra API** → lỗi serialize vòng lặp + `LazyInitializationException` (Day 37). Dùng DTO/projection.
- **Nhầm tên trong JPQL**: JPQL dùng tên **entity/field** (`Auction`, `a.status`), không phải tên bảng/cột (`auctions`, `status`).

---

## 🚀 Liên hệ Spring Boot / Production

- **Quản lý schema bằng Flyway/Liquibase**, đặt `ddl-auto: validate`. Versioned migration giống `php artisan migrate` nhưng chặt chẽ hơn (mỗi thay đổi là một file version).
- **Connection pool HikariCP** (mặc định Spring Boot): cấu hình `spring.datasource.hikari.maximum-pool-size` theo tải. Khác PHP (mỗi request một kết nối ngắn) — Java giữ pool kết nối lâu dài.
- **Phân trang & sắp xếp**: dùng `Pageable`/`Page<T>` thay vì load toàn bộ — bắt buộc với danh sách lớn.
- **DTO ở biên service/web**: không bao giờ để entity rò rỉ ra ngoài transaction; map sang DTO trong tầng service.
- **Index & query plan**: JPA không tự tạo index hợp lý cho bạn; vẫn phải thiết kế index ở migration. `show-sql` chỉ bật khi debug (tốn hiệu năng).
- **Transaction biên giới rõ ràng**: `@Transactional` ở tầng service (Day 37 đào sâu persistence context gắn với transaction).

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta chuyển lưu trữ từ bộ nhớ sang **DB thật qua JPA + repository**.

**Nhiệm vụ Day 36:**
1. Tạo entity `User`, `Auction`, `Bid` với mapping quan hệ: `Auction 1—N Bid`, `Bid N—1 User`, `Bid N—1 Auction`.
2. Tạo `AuctionRepository`, `BidRepository`, `UserRepository` (`extends JpaRepository`).
3. Viết query method: `findByStatus`, `findByStatusOrderByEndsAtAsc`, `findTopByAuctionIdOrderByAmountDesc` (giá cao nhất), `countByStatus`.
4. Thêm `@Query` JPQL `findExpiredButOpen` (phiên đã hết hạn nhưng còn OPEN).
5. Tạo DTO projection `AuctionSummary` qua constructor expression JPQL.
6. Chuyển `AuctionService.placeBid` sang dùng repository + `@Transactional`, giữ logic `BidTooLowException`.
7. Cấu hình DB (PostgreSQL/H2), đặt `ddl-auto: validate` + Flyway tạo schema; chạy thử CRUD và đặt giá.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: JPA và Hibernate khác nhau thế nào?**
> **Đáp:** JPA (Jakarta Persistence API) là **đặc tả** — gồm interface và annotation, không tự chạy. Hibernate là **implementation** phổ biến nhất của đặc tả đó (Spring Boot mặc định dùng), có thêm tính năng riêng vượt chuẩn. Lập trình theo JPA giúp dễ đổi implementation.

**Q2: `@OneToMany`, `@ManyToOne`, `mappedBy` dùng thế nào?**
> **Đáp:** `@ManyToOne` ở phía "nhiều" (Bid) giữ `@JoinColumn` (khóa ngoại) — đây là owning side. `@OneToMany(mappedBy="auction")` ở phía "một" (Auction) là inverse side, chỉ để đọc/đồng bộ. `mappedBy` trỏ tới tên field ở phía owning. FK nằm ở bảng phía "nhiều".

**Q3: Spring Data JPA giúp gì?**
> **Đáp:** Tự sinh implementation repository từ interface `JpaRepository`. Đã có sẵn CRUD, phân trang, sắp xếp. Ngoài ra hỗ trợ query method (sinh query từ tên method như `findByStatus`) và `@Query` (tự viết JPQL/native).

**Q4: JPQL khác native query thế nào?**
> **Đáp:** JPQL thao tác trên **entity và field** (độc lập DB, portable). Native query là SQL thuần thao tác trên **bảng và cột** (gắn với DB cụ thể). Ưu tiên JPQL; chỉ dùng native khi cần tính năng đặc thù DB.

### Mức Senior

**Q5: So sánh mô hình Active Record (Eloquent) và Data Mapper (JPA).**
> **Đáp:** Active Record (Eloquent): model vừa chứa dữ liệu vừa biết tự lưu (`$model->save()`), logic persistence nằm trong model. Data Mapper (JPA): entity là POJO thuần không biết persistence; một lớp riêng (`EntityManager`/repository) đảm nhận việc lưu/đọc. Data Mapper tách bạch domain khỏi persistence (dễ test, dễ thay tầng lưu trữ) nhưng dài dòng hơn; Active Record tiện và nhanh nhưng gắn chặt model với DB.

**Q6: Vì sao nên ưu tiên `FetchType.LAZY` cho quan hệ?**
> **Đáp:** EAGER kéo theo quan hệ mỗi lần nạp entity, gây query thừa và dễ N+1 (đặc biệt với danh sách). LAZY chỉ nạp khi thực sự truy cập, và khi cần ta chủ động dùng `JOIN FETCH`/`@EntityGraph` để nạp đúng lúc, đúng lượng. `@ManyToOne` mặc định EAGER nên cần đặt LAZY rõ ràng.

**Q7: DTO projection là gì và vì sao quan trọng?**
> **Đáp:** Là cách truy vấn trả thẳng về DTO/interface chỉ chứa các trường cần, thay vì nạp toàn bộ entity. Lợi: chỉ SELECT cột cần (giảm I/O), tránh nạp quan hệ thừa, tránh `LazyInitializationException`, và tách lớp web khỏi entity. Dùng constructor expression JPQL (`new ...Dto(...)`) hoặc interface-based projection.

**Q8: Vì sao không nên dùng `ddl-auto: update` ở production?**
> **Đáp:** `update` để Hibernate tự suy luận và thay đổi schema dựa trên entity — không kiểm soát được, có thể tạo cột/khóa ngoài ý muốn, không rollback được, không versioned, và không an toàn khi nhiều instance chạy song song. Production phải dùng migration tool (Flyway/Liquibase) với `ddl-auto: validate` để Hibernate chỉ kiểm tra khớp schema.

---

## ✅ Checklist hoàn thành

- [ ] Phân biệt rõ JPA (spec) vs Hibernate (impl)
- [ ] Hiểu vai trò EntityManager / EntityManagerFactory
- [ ] Map được entity với `@Entity`/`@Id`/`@GeneratedValue`/`@Column`
- [ ] Map đúng quan hệ `@OneToMany`/`@ManyToOne` + `mappedBy` (biết owning vs inverse side)
- [ ] Viết được query method và `@Query` JPQL; dùng DTO projection
- [ ] Giải thích được Active Record (Eloquent) vs Data Mapper (JPA)
- [ ] Hoàn thành Mini Project chuyển lưu trữ sang JPA + repository
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Jakarta Persistence Specification (đọc lướt phần Entity, Relationships)
- Hibernate ORM User Guide — "Entity", "Associations", "Fetching"
- Spring Data JPA Reference — "Defining Query Methods", "@Query", "Projections"
- Baeldung — "JPA/Hibernate Relationships", "Spring Data JPA Query Methods"
- Laravel Docs — "Eloquent: Getting Started", "Relationships" (để đối chiếu)
