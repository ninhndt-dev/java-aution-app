# Day 38 - Transactions (Giao dịch & quản lý đồng thời ở tầng DB)

> **Giai đoạn:** JPA & Hibernate
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu transaction trong Spring/JPA tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm chắc **ACID** và vì sao transaction là "hợp đồng tất-cả-hoặc-không-gì" với DB.
- Hiểu `@Transactional` của Spring hoạt động **bằng proxy** — và vì sao **self-invocation** lại làm nó "im lặng không chạy" (nối tiếp bẫy proxy đã gặp ở Day 30).
- Phân biệt **7 mức propagation** (`REQUIRED`, `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `MANDATORY`, `NEVER`, `NOT_SUPPORTED`) và khi nào dùng cái nào.
- Hiểu **isolation level** và 3 hiện tượng đọc bẩn: dirty read, non-repeatable read, phantom read.
- Nắm **rollback rules** (Spring mặc định chỉ rollback `RuntimeException`/`Error`) và cách ép bằng `rollbackFor`.
- Hiểu **lost update** và hai chiến lược chống: **optimistic locking** (`@Version`) vs **pessimistic locking** (`SELECT ... FOR UPDATE` / `@Lock`).
- Đối chiếu với `DB::transaction`, `lockForUpdate`, deadlock retry của Laravel.

---

## 🧠 Lý thuyết cốt lõi

### 1. Transaction là gì? ACID

Một **transaction** (giao dịch) là một nhóm thao tác DB được coi là **một đơn vị nguyên tử**: hoặc **tất cả** thành công và được ghi vĩnh viễn (`COMMIT`), hoặc **tất cả** bị huỷ bỏ như chưa từng xảy ra (`ROLLBACK`). Không có trạng thái "nửa vời".

Ví dụ kinh điển — chuyển tiền: trừ tài khoản A, cộng tài khoản B. Nếu trừ A xong mà cộng B lỗi, mà không rollback → tiền **bốc hơi**. Transaction đảm bảo điều đó không xảy ra.

**ACID** là 4 thuộc tính bảo chứng:

| Chữ | Tên | Ý nghĩa |
|---|---|---|
| **A** | Atomicity (Nguyên tử) | Tất cả-hoặc-không-gì. Lỗi giữa chừng → rollback toàn bộ. |
| **C** | Consistency (Nhất quán) | DB đi từ trạng thái hợp lệ này sang trạng thái hợp lệ khác (constraint, FK, trigger luôn được tôn trọng). |
| **I** | Isolation (Cô lập) | Các transaction chạy song song **không giẫm chân nhau** — như thể chạy tuần tự. Mức độ cô lập do *isolation level* quyết định. |
| **D** | Durability (Bền vững) | Đã `COMMIT` thì dữ liệu **không mất** kể cả mất điện (nhờ write-ahead log / WAL). |

```
        BEGIN
          │  UPDATE account SET balance = balance - 100 WHERE id = A;
          │  UPDATE account SET balance = balance + 100 WHERE id = B;
          │
     ┌────┴────┐
   COMMIT    ROLLBACK
 (ghi vĩnh   (huỷ sạch, như
  viễn cả 2)  chưa làm gì)
```

> 💡 Trong PostgreSQL/MySQL, ngay cả **một câu lệnh đơn** cũng chạy trong một transaction ngầm (auto-commit). Transaction tường minh chỉ cần khi bạn muốn **gộp nhiều lệnh** thành một đơn vị.

### 2. `@Transactional` của Spring hoạt động thế nào? (Proxy-based)

Bạn **không** tự gọi `connection.commit()`. Bạn chỉ gắn `@Transactional` lên method, Spring lo phần còn lại. Nhưng cơ chế bên trong là **AOP proxy** — chính xác là cái proxy bạn đã gặp ở Day 30:

```
Caller  ──►  [ Proxy của UserService ]  ──►  UserService thật
                     │
   (1) mở transaction (getConnection, setAutoCommit(false))
                     │
   (2) gọi method thật của bạn ──────────────►  code nghiệp vụ chạy
                     │
   (3) method trả về bình thường  ──►  COMMIT
       method ném RuntimeException ──►  ROLLBACK
```

Khi Spring khởi tạo bean có `@Transactional`, nó **bọc bean của bạn trong một proxy**. Proxy này chặn lời gọi method, mở transaction trước, đóng (commit/rollback) sau.

> ⚠️ **Bẫy self-invocation (nối tiếp Day 30):** Vì transaction được kích hoạt **ở proxy**, nếu một method **trong cùng class** gọi method `@Transactional` khác **bằng `this`**, lời gọi đó **không đi qua proxy** → `@Transactional` **bị bỏ qua hoàn toàn, không báo lỗi**.
>
> ```java
> @Service
> public class OrderService {
>     public void process() {
>         this.saveAtomically();   // ❌ gọi qua this -> KHÔNG có transaction!
>     }
>     @Transactional
>     public void saveAtomically() { ... }
> }
> ```
> Cách sửa: tách `saveAtomically` sang bean khác, hoặc tự inject chính mình, hoặc dùng `TransactionTemplate`. Đây là lỗi "âm thầm" cực nguy hiểm vì code vẫn chạy, chỉ là **không rollback khi cần**.

> ⚠️ Cùng lý do proxy: `@Transactional` mặc định **chỉ hiệu lực trên `public` method**. Đặt trên `private`/`protected` → vô tác dụng.

### 3. Propagation — transaction lồng nhau xử lý ra sao?

Khi method A (`@Transactional`) gọi method B (`@Transactional`), B nên **tham gia** transaction của A hay **tự mở** transaction riêng? `propagation` trả lời câu hỏi đó.

| Propagation | Hành vi khi đã có transaction | Khi chưa có transaction |
|---|---|---|
| **REQUIRED** *(mặc định)* | Tham gia transaction hiện tại | Tạo mới |
| **REQUIRES_NEW** | **Tạm dừng** cái hiện tại, mở transaction **mới độc lập** | Tạo mới |
| **NESTED** | Tạo **savepoint** lồng trong transaction hiện tại (rollback được phần con mà không huỷ cha) | Tạo mới |
| **SUPPORTS** | Tham gia | Chạy **không** transaction |
| **MANDATORY** | Tham gia | **Ném lỗi** (bắt buộc phải có sẵn) |
| **NEVER** | **Ném lỗi** | Chạy không transaction |
| **NOT_SUPPORTED** | **Tạm dừng** transaction, chạy không transaction | Chạy không transaction |

**Trực giác quan trọng:** với `REQUIRED` (mặc định), nếu B rollback thì **cả A cũng rollback** (chung một transaction vật lý). Muốn B độc lập — ví dụ "ghi log audit dù nghiệp vụ chính fail" — dùng **`REQUIRES_NEW`**: B có connection riêng, commit/rollback riêng.

```
REQUIRED:                      REQUIRES_NEW:
A ── BEGIN ──┐                 A ── BEGIN ───────────┐
   B (cùng tx)│                   B ── BEGIN tx mới ─┐│
   B fail ────┘ A cũng rollback     B commit ────────┘│  (B độc lập)
                                   A fail ─────────────┘  A rollback, B vẫn còn
```

> 💡 `REQUIRES_NEW` lấy **connection thứ hai** từ pool → coi chừng cạn pool nếu lồng sâu. `NESTED` chỉ dùng savepoint trên **cùng** connection (nhẹ hơn), nhưng cần driver hỗ trợ savepoint (Postgres OK, một số cấu hình thì không).

### 4. Isolation level & các hiện tượng đọc lỗi

Isolation quyết định một transaction "nhìn thấy" thay đổi chưa commit của transaction khác đến đâu. Càng cô lập cao → càng đúng đắn nhưng càng **giảm song song** (chậm hơn, dễ deadlock hơn).

Ba hiện tượng đọc lỗi, từ nhẹ tới nặng:

- **Dirty read** (đọc bẩn): đọc dữ liệu mà transaction khác **chưa commit** — nếu nó rollback thì bạn đã đọc dữ liệu "ma".
- **Non-repeatable read** (đọc không lặp lại): đọc cùng một dòng hai lần trong cùng transaction, **giá trị khác nhau** vì transaction khác đã update + commit ở giữa.
- **Phantom read** (đọc bóng ma): chạy cùng một câu `WHERE` hai lần, lần sau **xuất hiện dòng mới** do transaction khác `INSERT` + commit.

| Isolation | Dirty read | Non-repeatable | Phantom |
|---|---|---|---|
| **READ_UNCOMMITTED** | ❌ có thể | ❌ có thể | ❌ có thể |
| **READ_COMMITTED** | ✅ chặn | ❌ có thể | ❌ có thể |
| **REPEATABLE_READ** | ✅ chặn | ✅ chặn | ❌ có thể* |
| **SERIALIZABLE** | ✅ chặn | ✅ chặn | ✅ chặn |

> 💡 Mặc định: **PostgreSQL & Oracle = READ_COMMITTED**, **MySQL/InnoDB = REPEATABLE_READ**. (*MySQL InnoDB ở REPEATABLE_READ dùng next-key lock nên chặn được phần lớn phantom; chuẩn SQL thì không.) Trong Spring đặt bằng `@Transactional(isolation = Isolation.REPEATABLE_READ)` — nhưng **đa số nghiệp vụ nên giữ READ_COMMITTED** và chống lost update bằng locking (mục 6) thay vì nâng isolation lên SERIALIZABLE (đắt).

### 5. Rollback rules — cái bẫy lớn nhất của người mới

**Mặc định Spring CHỈ rollback khi method ném `RuntimeException` (unchecked) hoặc `Error`.** Nếu method ném một **checked exception** (như `IOException`, `Exception` tự định nghĩa kế thừa `Exception`), Spring sẽ **COMMIT** chứ không rollback!

```java
@Transactional
public void doWork() throws IOException {
    repo.save(x);
    throw new IOException("lỗi IO");   // ❌ Spring vẫn COMMIT! x bị lưu lại.
}
```

Cách sửa: khai báo rõ loại exception cần rollback.

```java
@Transactional(rollbackFor = Exception.class)   // rollback với MỌI exception
public void doWork() throws IOException { ... }

// hoặc loại trừ:
@Transactional(noRollbackFor = ValidationWarning.class)
```

> ⚠️ Quy tắc vàng: với nghiệp vụ tài chính/quan trọng, **luôn** ghi `rollbackFor = Exception.class` cho chắc, đừng tin vào mặc định.

### 6. Lost update & hai chiến lược chống

**Lost update** chính là bug bạn đã gặp ở Day 19 (CAS in-memory), nhưng nay ở **tầng DB**: hai transaction cùng đọc giá `highestBid = 100`, cùng tính giá mới, cùng `UPDATE` — cái sau **đè mất** cái trước.

#### a) Optimistic locking — `@Version` (lạc quan: "chắc ít va chạm")

Thêm cột `version`. Mỗi `UPDATE` của JPA tự động thành:

```sql
UPDATE auction SET highest_bid = ?, version = version + 1
WHERE id = ? AND version = ?      -- version ta đã đọc lúc đầu
```

Nếu `version` đã bị transaction khác tăng → `WHERE` không khớp dòng nào → JPA ném `OptimisticLockException` (Spring: `ObjectOptimisticLockingFailureException`). Ta **bắt và thử lại** (retry). Không khóa DB → **đồng thời cao**, phù hợp khi va chạm hiếm.

> 🧩 Đây đúng là **optimistic locking ở tầng DB** mà ghi chú Day 19 đã hứa hẹn — cùng tư duy compare-and-set, khác tầng lưu trữ.

#### b) Pessimistic locking — `SELECT ... FOR UPDATE` / `@Lock` (bi quan: "chắc nhiều va chạm")

Khóa hàng ngay lúc đọc; transaction khác muốn đọc-để-ghi phải **chờ**.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)   // sinh ra SELECT ... FOR UPDATE
@Query("SELECT a FROM Auction a WHERE a.id = :id")
Optional<Auction> findByIdForUpdate(@Param("id") Long id);
```

Đảm bảo tuyệt đối không lost update nhưng **giảm song song** (kẻ khác phải xếp hàng), nguy cơ **deadlock** và **lock timeout**. Hợp khi va chạm thường xuyên hoặc thao tác dài.

| | Optimistic (`@Version`) | Pessimistic (`FOR UPDATE`) |
|---|---|---|
| Khóa DB | Không | Có (row lock) |
| Va chạm | Phát hiện lúc commit, retry | Chặn từ đầu, chờ |
| Hợp với | Ít va chạm (đọc nhiều) | Nhiều va chạm (ghi nhiều) |
| Rủi ro | Retry storm | Deadlock, lock wait timeout |

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel/PHP | Spring/JPA |
|---|---|---|
| Mở transaction | `DB::transaction(fn () => ...)` (closure) | `@Transactional` trên method |
| Rollback tự động | Closure ném **bất kỳ** Throwable → tự rollback | Chỉ rollback `RuntimeException`/`Error` (⚠️ khác!) |
| Rollback thủ công | `DB::rollBack()` | `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` |
| Pessimistic lock | `->lockForUpdate()` / `->sharedLock()` | `@Lock(PESSIMISTIC_WRITE / PESSIMISTIC_READ)` |
| Optimistic lock | Không có sẵn — tự thêm cột & check thủ công | `@Version` **tự động hoàn toàn** |
| Deadlock retry | `DB::transaction($closure, $attempts)` — tham số thử lại | Tự viết retry (hoặc Spring Retry `@Retryable`) |
| Isolation | Cấu hình ở connection / `DB::connection()` | `@Transactional(isolation = ...)` ngay trên method |

**Khác biệt tư duy quan trọng nhất:**

```php
// Laravel: rollback với MỌI exception
DB::transaction(function () {
    Account::find($a)->decrement('balance', 100);
    throw new \Exception('lỗi'); // -> rollback chắc chắn
});
```
```java
// Spring: checked exception KHÔNG rollback mặc định -> dễ commit nhầm!
@Transactional(rollbackFor = Exception.class)  // phải khai báo!
public void transfer() throws BusinessException { ... }
```

> 💡 Người Laravel hay bị bug này nhất: tưởng "có `@Transactional` là rollback mọi lỗi như `DB::transaction`". KHÔNG. Java phân biệt checked/unchecked exception và Spring chỉ rollback unchecked theo mặc định. **Nhớ `rollbackFor = Exception.class`.**

> 💡 `@Version` của JPA là thứ Laravel **không có sẵn** — ở Laravel bạn phải tự thêm cột `version` và `WHERE version = ?` thủ công. JPA làm việc đó tự động cho bạn.

---

## 💻 Thực hành code

### Bước 1 — Entity có `@Version` (optimistic locking)

```java
// File: Auction.java
package com.example.auction.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "auctions")
public class Auction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String itemName;

    @Column(nullable = false)
    private long highestBid;

    private String highestBidder;

    private boolean closed;

    // ⭐ CỘT THEN CHỐT: Hibernate dùng nó cho optimistic locking.
    // Mỗi UPDATE -> version+1; UPDATE kèm WHERE version = <giá trị đã đọc>.
    @Version
    private long version;

    protected Auction() {}   // JPA cần constructor rỗng

    public Auction(String itemName) { this.itemName = itemName; this.highestBid = 0; }

    // getter/setter...
    public Long getId() { return id; }
    public long getHighestBid() { return highestBid; }
    public void setHighestBid(long v) { this.highestBid = v; }
    public String getHighestBidder() { return highestBidder; }
    public void setHighestBidder(String b) { this.highestBidder = b; }
    public boolean isClosed() { return closed; }
    public long getVersion() { return version; }
}
```

### Bước 2 — Service đặt giá an toàn với rollback + retry

```java
// File: BidService.java
package com.example.auction.service;

import com.example.auction.domain.Auction;
import com.example.auction.repository.AuctionRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BidService {

    private final AuctionRepository auctionRepo;
    private final BidEventRecorder eventRecorder;   // bean KHÁC -> qua proxy được

    public BidService(AuctionRepository auctionRepo, BidEventRecorder eventRecorder) {
        this.auctionRepo = auctionRepo;
        this.eventRecorder = eventRecorder;
    }

    /**
     * Đặt giá. rollbackFor = Exception.class để CHẮC CHẮN rollback mọi lỗi
     * (không tin vào mặc định "chỉ rollback RuntimeException").
     */
    @Transactional(rollbackFor = Exception.class)
    public void placeBid(Long auctionId, String bidder, long amount) {
        Auction auction = auctionRepo.findById(auctionId)
                .orElseThrow(() -> new IllegalArgumentException("Phiên không tồn tại"));

        if (auction.isClosed()) {
            throw new IllegalStateException("Phiên đã đóng");
        }
        if (amount <= auction.getHighestBid()) {
            throw new IllegalStateException(
                "Giá " + amount + " không cao hơn giá hiện tại " + auction.getHighestBid());
        }

        auction.setHighestBid(amount);
        auction.setHighestBidder(bidder);
        // Khi transaction commit, Hibernate flush:
        //   UPDATE auctions SET highest_bid=?, version=version+1 WHERE id=? AND version=?
        // Nếu version đã đổi -> ObjectOptimisticLockingFailureException -> rollback.

        // Ghi log audit ĐỘC LẬP: dù bid chính có thể fail, ta vẫn muốn lưu vết thử.
        eventRecorder.recordAttempt(auctionId, bidder, amount);
    }

    /**
     * Lớp bọc retry cho optimistic locking: gặp va chạm thì đọc lại & thử lại.
     * Giống tham số $attempts của DB::transaction trong Laravel.
     */
    public void placeBidWithRetry(Long auctionId, String bidder, long amount) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                placeBid(auctionId, bidder, amount);   // ✅ gọi qua proxy (Spring inject self? xem ghi chú)
                return;
            } catch (ObjectOptimisticLockingFailureException ex) {
                if (attempt == maxAttempts) {
                    throw new IllegalStateException("Quá nhiều va chạm, vui lòng thử lại", ex);
                }
                // chờ một chút rồi thử lại với snapshot version mới
            }
        }
    }
}
```

> ⚠️ Lưu ý self-invocation: trong ví dụ trên `placeBidWithRetry` gọi `placeBid` **trong cùng class** → vẫn dùng `this` → transaction của `placeBid` **không kích hoạt qua proxy**. Cách đúng: tách `placeBid` sang một bean riêng (vd `TxBidExecutor`) và inject nó vào, **hoặc** đặt `@Transactional` ở tầng ngoài. Mục "Bẫy thường gặp" nói kỹ.

### Bước 3 — Demo `REQUIRES_NEW`: audit độc lập

```java
// File: BidEventRecorder.java
package com.example.auction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BidEventRecorder {

    private final BidEventRepository repo;
    public BidEventRecorder(BidEventRepository repo) { this.repo = repo; }

    /**
     * REQUIRES_NEW: dùng transaction RIÊNG. Dù transaction đặt giá ở ngoài
     * có rollback, bản ghi audit này VẪN được commit (connection độc lập).
     * Tương tự: ở Laravel bạn phải DB::connection()->transaction() riêng.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(Long auctionId, String bidder, long amount) {
        repo.save(new BidEvent(auctionId, bidder, amount));
    }
}
```

### Bước 4 — Pessimistic locking trong repository

```java
// File: AuctionRepository.java
package com.example.auction.repository;

import com.example.auction.domain.Auction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // PESSIMISTIC_WRITE -> sinh ra "SELECT ... FOR UPDATE": khóa hàng ngay khi đọc.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") Long id);
}
```

### Bước 5 — Cấu hình `application.yml`

```yaml
spring:
  jpa:
    properties:
      hibernate:
        # Để DEMO thấy rõ câu UPDATE có WHERE version = ?
        format_sql: true
    show-sql: true
  datasource:
    url: jdbc:postgresql://localhost:5432/auction
    hikari:
      maximum-pool-size: 10   # nhớ: REQUIRES_NEW mượn thêm connection -> đừng để pool quá nhỏ
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Self-invocation nuốt `@Transactional`.** Gọi method `@Transactional` bằng `this` từ trong cùng class → proxy không chặn → **không có transaction**, code vẫn chạy nhưng **không rollback**. Tách bean hoặc inject self.
- **Tin vào rollback mặc định.** Spring **không** rollback với checked exception. Quên `rollbackFor = Exception.class` → dữ liệu lỗi vẫn được commit. Đây là bug số 1 của người từ Laravel.
- **`@Transactional` trên method `private`/`protected`.** Vô tác dụng (proxy chỉ bọc public). Cũng vô tác dụng nếu đặt trên method `final` (CGLIB không override được).
- **Quên `@Version` rồi ngạc nhiên vì lost update.** Không có `@Version` thì JPA cứ `UPDATE ... WHERE id = ?` — đè nhau thoải mái.
- **Lạm dụng `SERIALIZABLE`.** Cô lập cao nhất nhưng giảm throughput mạnh, sinh deadlock. Đa số nên dùng `READ_COMMITTED` + locking đúng chỗ.
- **Logic chậm (gọi API ngoài, gửi mail) nằm TRONG `@Transactional`.** Giữ connection & lock lâu → cạn pool, tăng deadlock. Đưa I/O ngoài DB ra **ngoài** transaction.
- **Bắt `OptimisticLockException` nhưng quên đọc lại entity.** Phải lấy lại snapshot version mới rồi mới thử lại — retry trên entity cũ sẽ fail mãi.
- **`REQUIRES_NEW` lồng sâu làm cạn connection pool.** Mỗi cấp mượn một connection; pool 10 mà lồng 11 cấp → deadlock pool.

---

## 🚀 Liên hệ Spring Boot / Production

- **Bật log transaction để debug:** `logging.level.org.springframework.transaction=DEBUG` và `org.hibernate.SQL=DEBUG` — thấy được lúc nào BEGIN/COMMIT/ROLLBACK, giống bật query log trong Laravel Telescope.
- **Spring Retry cho optimistic lock:** dùng `@Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3)` thay cho vòng lặp thủ công — nhưng nhớ đặt trên một bean khác để tránh self-invocation.
- **Timeout transaction:** `@Transactional(timeout = 5)` để giới hạn 5 giây — tránh transaction "treo" giữ lock vô hạn (cực quan trọng cho pessimistic lock trong hệ tải cao).
- **`readOnly = true`** cho method chỉ đọc: Hibernate bỏ qua dirty-checking, một số DB tối ưu (định tuyến tới read replica). Đặt cho mọi method "query thuần".
- **Lock wait timeout & deadlock** sẽ xuất hiện trong production: cấu hình `innodb_lock_wait_timeout` (MySQL) / `lock_timeout` (Postgres) và có chiến lược retry. Theo dõi qua metric (Day 43).
- **Đừng để transaction span qua nhiều microservice.** Distributed transaction (2PC) rất đắt; thực tế dùng **Saga + outbox pattern** (liên quan Kafka — Day 40).

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Day 19 ta đã chống lost update **in-memory** bằng `AtomicReference` + CAS. Hôm nay nâng lên **tầng DB**: đảm bảo "giá cao nhất" an toàn ngay cả khi nhiều instance app cùng ghi vào một DB — bằng **optimistic locking** với `@Version`.

**Nhiệm vụ Day 38:**

1. Thêm cột `@Version version` vào entity `Auction`. Bật `show-sql` và quan sát câu `UPDATE ... WHERE id=? AND version=?`.
2. Viết `BidService.placeBid` với `@Transactional(rollbackFor = Exception.class)`: kiểm tra `amount > highestBid`, cập nhật `highestBid`/`highestBidder`.
3. Viết một test **đa luồng**: 200 thread cùng đặt giá tăng dần lên một `auctionId`. Bắt `ObjectOptimisticLockingFailureException`, retry tối đa 3 lần. Khẳng định cuối cùng `highestBid` = giá lớn nhất thực sự, **không bao giờ bị tụt**.
4. Thêm `recordAttempt` với `REQUIRES_NEW`: chứng minh bản ghi audit **vẫn còn** dù bid chính rollback (cố tình ném exception sau khi gọi `recordAttempt`).
5. (Tuỳ chọn) Viết bản pessimistic dùng `findByIdForUpdate` và đo throughput so với bản optimistic dưới tải cao.

**Kết quả mong đợi:** chạy test 200 thread nhiều lần → `highestBid` **luôn** đúng bằng giá lớn nhất, không lần nào lost update; bản ghi audit `REQUIRES_NEW` luôn tồn tại độc lập với rollback của bid chính.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: ACID là gì?**
> **Đáp:** Bốn thuộc tính của transaction: **Atomicity** (tất cả-hoặc-không-gì), **Consistency** (DB luôn ở trạng thái hợp lệ, tôn trọng constraint), **Isolation** (các transaction song song không giẫm chân nhau), **Durability** (đã commit thì không mất kể cả mất điện, nhờ WAL).

**Q2: `@Transactional` của Spring mặc định rollback khi nào?**
> **Đáp:** Chỉ rollback khi method ném `RuntimeException` (unchecked) hoặc `Error`. Với **checked exception** thì **không** rollback (vẫn commit). Muốn rollback với mọi exception phải ghi `rollbackFor = Exception.class`. Đây là điểm khác với `DB::transaction` của Laravel (rollback với mọi throwable).

**Q3: Phân biệt optimistic và pessimistic locking?**
> **Đáp:** Optimistic (`@Version`): không khóa DB, cho phép cùng đọc, phát hiện va chạm **lúc commit** qua `WHERE version = ?`, fail thì retry — hợp khi ít va chạm. Pessimistic (`SELECT ... FOR UPDATE`/`@Lock`): khóa hàng ngay lúc đọc, kẻ khác phải chờ — đảm bảo tuyệt đối nhưng giảm song song, dễ deadlock, hợp khi nhiều va chạm.

**Q4: Dirty read, non-repeatable read, phantom read khác nhau thế nào?**
> **Đáp:** Dirty read = đọc dữ liệu **chưa commit** của transaction khác. Non-repeatable read = đọc lại cùng **một dòng** thấy giá trị khác (do update + commit ở giữa). Phantom read = chạy lại cùng `WHERE` thấy **xuất hiện dòng mới** (do insert + commit). READ_COMMITTED chặn dirty; REPEATABLE_READ chặn thêm non-repeatable; SERIALIZABLE chặn cả phantom.

### Mức Senior

**Q5: Vì sao self-invocation làm `@Transactional` không chạy, và sửa thế nào?**
> **Đáp:** `@Transactional` được kích hoạt bởi **AOP proxy** bọc quanh bean. Khi một method gọi method `@Transactional` khác **trong cùng class** bằng `this`, lời gọi đi thẳng tới object thật, **không qua proxy** → advice transaction không chạy → không có transaction (im lặng, không lỗi). Sửa: tách method sang bean khác và inject; hoặc tự inject chính mình (`@Lazy self`); hoặc dùng `TransactionTemplate` lập trình tường minh; hoặc bật AspectJ load-time weaving (không dùng proxy).

**Q6: Khi nào dùng `REQUIRES_NEW` thay vì `REQUIRED`, và rủi ro?**
> **Đáp:** Dùng khi cần một transaction **độc lập** với transaction ngoài — ví dụ ghi log audit/outbox phải commit dù nghiệp vụ chính rollback. `REQUIRES_NEW` tạm dừng transaction hiện tại, mở transaction mới trên **connection thứ hai**, commit/rollback riêng. Rủi ro: mượn thêm connection từ pool (lồng sâu → cạn pool, thậm chí deadlock pool), và mất tính nguyên tử "tất cả-hoặc-không-gì" giữa hai phần.

**Q7: Hệ thống đấu giá tải cao nên chọn optimistic hay pessimistic, vì sao?**
> **Đáp:** Tùy mức va chạm trên cùng một item. Nếu nhiều item, mỗi item ít người bid đồng thời → **optimistic** (`@Version` + retry) cho throughput cao, không khóa. Nếu một item "nóng" với hàng nghìn bid/giây → optimistic sinh **retry storm**; lúc đó cân nhắc **pessimistic** (`FOR UPDATE`) hoặc đẩy việc tranh chấp ra ngoài DB (vd hàng đợi/Redis atomic — Day 39, hoặc xử lý tuần tự theo partition Kafka — Day 40). Quan trọng: đo va chạm thực tế rồi mới chọn, kèm timeout + retry có giới hạn.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được ACID và transaction là "tất cả-hoặc-không-gì"
- [ ] Hiểu `@Transactional` là proxy-based và bẫy self-invocation
- [ ] Phân biệt 7 propagation, đặc biệt REQUIRED vs REQUIRES_NEW vs NESTED
- [ ] Hiểu 4 isolation level và 3 hiện tượng đọc lỗi
- [ ] Nhớ `rollbackFor = Exception.class` (đừng tin mặc định)
- [ ] Cài `@Version` chống lost update, biết khi nào dùng pessimistic lock
- [ ] Hoàn thành Mini Project: optimistic locking cho `highestBid` + demo REQUIRES_NEW
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Transaction Management" (Declarative, propagation, rollback rules)
- Baeldung — "Transaction Propagation and Isolation in Spring @Transactional"
- Baeldung — "JPA/Hibernate Optimistic Locking with @Version" và "Pessimistic Locking in JPA"
- PostgreSQL Docs — "Transaction Isolation" (chương 13: Concurrency Control)
- Vlad Mihalcea — blog về optimistic vs pessimistic locking, `OptimisticLockException` retry
- Laravel Docs — "Database: Getting Started → Database Transactions" (đối chiếu `DB::transaction`, `lockForUpdate`)
