# Day 31 - Hibernate Persistence Context

> **Giai đoạn:** JPA & Hibernate
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP — nơi Eloquent KHÔNG có persistence context, nên đây là khác biệt tư duy cốt lõi.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Persistence Context** = **first-level cache** (cache cấp 1, theo transaction).
- Nắm **Identity Map**: một entity / một id trong một context là **cùng một object**.
- Thuộc **4 trạng thái entity** (transient, managed, detached, removed) và sơ đồ chuyển trạng thái.
- Hiểu **dirty checking** — Hibernate tự phát hiện thay đổi và sinh `UPDATE` lúc flush.
- Phân biệt **flush vs commit**.
- Hiểu vấn đề **N+1** và cách trị: `JOIN FETCH`, `@EntityGraph`, batch size.
- Phân biệt **LAZY vs EAGER** và `LazyInitializationException`.
- Sơ lược **second-level cache**.
- Đối chiếu: Eloquent **KHÔNG** có cơ chế này — phải `save()`/`isDirty()` tường minh.

---

## 🧠 Lý thuyết cốt lõi

### 1. Persistence Context = First-Level Cache (cache cấp 1)

`EntityManager` quản lý một vùng nhớ gọi là **Persistence Context** — nơi chứa các entity **đang được quản lý** trong phạm vi một transaction.

```
┌──────────────── Transaction (một @Transactional) ─────────────────┐
│                                                                    │
│   EntityManager ──► Persistence Context (First-Level Cache)        │
│                      ┌─────────────────────────────────────┐       │
│                      │  Auction#1  (managed)               │       │
│                      │  Bid#5      (managed)               │       │
│                      │  User#3     (managed)               │       │
│                      └─────────────────────────────────────┘       │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
        flush() → đồng bộ thay đổi xuống DB    commit() → kết thúc transaction
```

Tính chất quan trọng:
- **Phạm vi = một transaction.** Hết transaction → context bị xóa, entity trở thành detached.
- Là **cache cấp 1**: trong cùng transaction, `find(Auction, 1)` gọi hai lần → chỉ **một** query SQL; lần sau lấy từ context.

> 💡 Đây là thứ Eloquent **không có**. Trong Laravel, gọi `Auction::find(1)` hai lần thường là **hai query**. Trong JPA cùng transaction, lần hai lấy từ persistence context, không query lại.

### 2. Identity Map — một id, một object

Trong một persistence context, mỗi (entity type + id) chỉ tương ứng với **đúng một instance** trong bộ nhớ:

```java
@Transactional
public void demo() {
    Auction a1 = em.find(Auction.class, 1L);
    Auction a2 = em.find(Auction.class, 1L);
    // a1 == a2  → TRUE! Cùng một object trong bộ nhớ (identity map)
}
```

> 💡 Hệ quả: sửa `a1` cũng là sửa `a2` (cùng object). Bạn không bao giờ có hai bản sao "lệch nhau" của cùng một bản ghi trong một context. Eloquent không đảm bảo điều này — hai lần `find` cho ra hai object khác nhau.

### 3. Bốn trạng thái của entity & sơ đồ chuyển

```
                 new Auction(...)
                       │
                       ▼
                 ┌───────────┐
                 │ TRANSIENT │  (object mới, chưa có id, context KHÔNG biết)
                 └─────┬─────┘
            persist()  │
                       ▼
                 ┌───────────┐   dirty checking tự sinh UPDATE lúc flush
                 │  MANAGED  │◄──────────────────────────┐
                 │(persistent)│                          │
                 └──┬─────┬──┘                           │ merge()
              remove()│     │ detach() / clear() /        │
                      │     │ hết transaction             │
                      ▼     ▼                             │
                ┌─────────┐ ┌──────────┐                  │
                │ REMOVED │ │ DETACHED │──────────────────┘
                │(sẽ DELETE)│ │(context KHÔNG còn theo dõi)│
                └─────────┘ └──────────┘
```

| Trạng thái | Mô tả | Context theo dõi? |
|---|---|---|
| **Transient** | Object Java mới `new`, chưa gắn DB, chưa có id | Không |
| **Managed (Persistent)** | Đang trong persistence context, mọi thay đổi được theo dõi | **Có** |
| **Detached** | Từng managed nhưng context đã đóng/clear | Không (cần `merge` để gắn lại) |
| **Removed** | Được đánh dấu xóa, sẽ `DELETE` lúc flush | Có (đang chờ xóa) |

Các phép chuyển:
- `persist(entity)`: transient → managed.
- `remove(entity)`: managed → removed.
- `detach(entity)` / `clear()` / hết transaction: managed → detached.
- `merge(detached)`: trả về một bản **managed** mới (sao chép state của detached vào).

> ⚠️ `merge()` KHÔNG biến chính object truyền vào thành managed — nó **trả về** một instance managed khác. Phải dùng giá trị trả về: `Auction managed = em.merge(detached);`.

### 4. Dirty Checking — phép màu "không cần gọi save()"

Đây là tính năng khiến JPA khác hẳn Eloquent. Với entity **managed**, bạn chỉ cần **đổi field** — Hibernate **tự phát hiện** và sinh `UPDATE` khi flush, **không cần gọi `save()`**:

```java
@Transactional
public void increasePrice(Long auctionId, long newPrice) {
    Auction auction = auctionRepository.findById(auctionId).orElseThrow();
    // auction giờ là MANAGED
    auction.setCurrentPrice(newPrice);
    // KHÔNG gọi save()! Khi transaction commit/flush, Hibernate phát hiện
    // currentPrice đã đổi → tự sinh: UPDATE auctions SET current_price = ? WHERE id = ?
}
```

Cơ chế: khi nạp entity, Hibernate chụp lại "snapshot" giá trị ban đầu. Lúc flush, nó so sánh state hiện tại với snapshot; field nào khác → đưa vào câu `UPDATE`.

> 💡 Đây là **khác biệt tư duy lớn nhất** so với Laravel. Ở Eloquent bạn **bắt buộc** `$auction->save()`. Ở JPA, với entity managed trong transaction, chỉ cần đổi field là đủ — gọi `save()` thường là thừa (dù không sai).

### 5. flush vs commit

```
flush()  ──► ĐỒNG BỘ các thay đổi (INSERT/UPDATE/DELETE) xuống DB,
             nhưng transaction VẪN MỞ (có thể rollback).
commit() ──► flush (nếu chưa) + KẾT THÚC transaction, chốt thay đổi vĩnh viễn.
```

- Hibernate thường **trì hoãn** ghi xuống DB (write-behind) tới lúc flush, để gom và tối ưu.
- `flush` mặc định xảy ra: trước khi chạy query có thể bị ảnh hưởng, và khi commit.
- `commit` luôn flush trước rồi đóng transaction.

> ⚠️ `flush` không phải `commit`. Sau flush mà rollback thì các thay đổi đã gửi xuống DB vẫn bị hủy (vì transaction chưa commit).

### 6. Vấn đề N+1 — kẻ giết hiệu năng số 1

Xảy ra khi bạn nạp một danh sách entity rồi truy cập quan hệ lazy của **từng phần tử**:

```java
List<Auction> auctions = auctionRepository.findAll();   // 1 query: SELECT * FROM auctions
for (Auction a : auctions) {
    System.out.println(a.getBids().size());              // MỖI vòng lặp: +1 query lấy bids!
}
// Tổng: 1 (auctions) + N (bids của từng auction) = N+1 query
```

```
findAll() ─► 1 query
   ├─ auction#1.getBids() ─► query #2
   ├─ auction#2.getBids() ─► query #3
   ├─ ...
   └─ auction#N.getBids() ─► query #(N+1)
```

**Cách trị:**

**(a) `JOIN FETCH` trong JPQL** — nạp quan hệ ngay trong một query:
```java
@Query("SELECT DISTINCT a FROM Auction a LEFT JOIN FETCH a.bids WHERE a.status = :status")
List<Auction> findWithBids(@Param("status") AuctionStatus status);
```

**(b) `@EntityGraph`** — khai báo quan hệ cần nạp sẵn, không cần viết JPQL:
```java
@EntityGraph(attributePaths = "bids")
List<Auction> findByStatus(AuctionStatus status);
```

**(c) Batch fetching** — gom các lazy load thành ít query hơn (theo lô):
```java
@OneToMany(mappedBy = "auction")
@org.hibernate.annotations.BatchSize(size = 20)   // nạp bids của tối đa 20 auction mỗi query
private List<Bid> bids;
```
hoặc cấu hình toàn cục: `spring.jpa.properties.hibernate.default_batch_fetch_size=20`.

> 💡 Quy tắc: với danh sách + quan hệ, **chủ động fetch**. `JOIN FETCH`/`@EntityGraph` cho nạp đúng cái cần; batch size là "lưới an toàn" giảm N+1 thành N/batch.

> ⚠️ `JOIN FETCH` với collection (`@OneToMany`) làm kết quả nhân dòng (Cartesian) → dùng `DISTINCT`. Và **không** kết hợp `JOIN FETCH` collection với phân trang (`Pageable`) trong cùng query — Hibernate sẽ phân trang trong bộ nhớ (cảnh báo, rất tốn). Khi cần phân trang + collection, dùng `@EntityGraph` hoặc fetch hai bước.

### 7. LAZY vs EAGER & `LazyInitializationException`

- **LAZY**: quan hệ chỉ nạp khi truy cập lần đầu. Cần context còn mở (transaction chưa đóng).
- **EAGER**: nạp ngay cùng entity cha (dễ query thừa, N+1).

`LazyInitializationException` xảy ra khi bạn truy cập quan hệ **lazy** sau khi **transaction đã đóng** (entity đã detached):

```java
// SAI: service trả entity, transaction đóng, rồi controller mới truy cập lazy
Auction a = auctionService.findById(1L);  // transaction đã đóng ở đây
a.getBids().size();   // ❌ LazyInitializationException: no Session
```

**Cách tránh:**
- Nạp sẵn dữ liệu cần **trong** transaction (`JOIN FETCH`/`@EntityGraph`).
- Map sang **DTO** ngay trong tầng service (trong transaction) — đây là cách tốt nhất.
- **Tránh** mở rộng transaction tới tầng view (`spring.jpa.open-in-view`) — mặc định Spring Boot bật `open-in-view=true` (giữ session tới hết request, che lỗi nhưng gây query ẩn ở tầng view). Khuyến nghị **tắt** (`spring.jpa.open-in-view=false`) và fetch tường minh.

### 8. Second-Level Cache (sơ lược)

- **First-level cache** (persistence context): theo **transaction**, luôn bật, không tắt được.
- **Second-level cache**: theo **EntityManagerFactory** (dùng chung nhiều transaction/session), **không bật mặc định**. Cần provider (EhCache, Caffeine, Hazelcast...) + đánh dấu entity `@Cacheable` + `hibernate.cache.use_second_level_cache=true`.

> 💡 L2 cache hữu ích cho dữ liệu ít đổi, đọc nhiều (danh mục, cấu hình). Nhưng phức tạp về invalidation trong môi trường nhiều instance — cân nhắc kỹ, đừng bật bừa.

---

## 🔁 Đối chiếu với Laravel/PHP — KHÁC BIỆT CỐT LÕI

Đây là ngày mà sự khác biệt giữa hai thế giới rõ rệt nhất:

| Khái niệm | Eloquent (Laravel) | Hibernate/JPA |
|---|---|---|
| Persistence context / first-level cache | **KHÔNG có** | Có (theo transaction) |
| Identity map | **KHÔNG** (mỗi `find` ra object mới) | Có (một id → một object/context) |
| Theo dõi thay đổi | Có `isDirty()`/`getDirty()` nhưng **không tự lưu** | **Dirty checking tự động** sinh UPDATE |
| Lưu thay đổi | **BẮT BUỘC** `$model->save()` | Đổi field là đủ (managed) — `save()` thường thừa |
| Trạng thái entity | Không có khái niệm transient/managed/detached | 4 trạng thái rõ ràng |
| Eager load | `with('bids')` | `JOIN FETCH` / `@EntityGraph` |
| N+1 | Có (giải bằng `with()`) | Có (giải bằng fetch join/entity graph/batch) |
| Lazy load ngoài "session" | Luôn query được (model tự query) | `LazyInitializationException` nếu hết transaction |

**Khác biệt tư duy quan trọng nhất:**

```
ELOQUENT (tường minh):
   $auction = Auction::find(1);
   $auction->current_price = 1500;
   $auction->save();              // ← KHÔNG gọi thì KHÔNG lưu

HIBERNATE (ngầm — dirty checking):
   Auction a = repo.findById(1).get();   // managed
   a.setCurrentPrice(1500);
   // hết @Transactional → tự UPDATE, KHÔNG cần save()
```

> 🧩 Người từ Laravel rất hay sốc ở hai điểm:
> 1. **"Tôi đâu có gọi save() mà sao DB đổi?"** → đó là dirty checking. Entity managed + đổi field + commit = UPDATE tự động.
> 2. **"Sao truy cập quan hệ lại văng exception?"** → `LazyInitializationException`: ở Eloquent model luôn tự query được; ở JPA, quan hệ lazy cần session/transaction còn mở.

> ⚠️ Eloquent có `$model->isDirty()` / `getChanges()` để **xem** thay đổi, nhưng việc ghi luôn cần `save()` tường minh. JPA đảo ngược: việc ghi là ngầm (tự động), còn việc "đừng ghi" mới cần chủ động (`detach`/đọc-only/`@Transactional(readOnly=true)`).

---

## 💻 Thực hành code

### Bài 1 — Minh họa dirty checking (không gọi save)

```java
@Service
public class AuctionService {
    private final AuctionRepository auctionRepository;
    // ... constructor ...

    @___ // Điền annotation bắt buộc phải có để Persistence Context (Cache cấp 1) hoạt động và mở ra
    public void renameAuction(Long id, String newTitle) {
        Auction auction = auctionRepository.findById(id).orElseThrow();  // Trạng thái lúc này là: MANAGED
        
        // Thay đổi giá trị của Entity đang được quản lý
        auction.setTitle(newTitle);
        
        // KHÔNG GỌI SAVE()! Khi method kết thúc → commit → Hibernate tự chạy Dirty Checking và sinh ra:
        // UPDATE auctions SET title = ? WHERE id = ?
    }
}
```

### Bài 2 — Chứng minh identity map (cùng object)

```java
@Transactional(readOnly = true)
public void proveIdentityMap(Long id) {
    Auction a1 = auctionRepository.findById(id).orElseThrow();
    Auction a2 = auctionRepository.findById(id).orElseThrow();
    System.out.println("Cùng object? " + (a1 == a2));  // Kết quả in ra là TRUE! (Và Hibernate chỉ chạy đúng 1 câu query SQL)
}
```

### Bài 3 — Tái hiện N+1 và sửa bằng JOIN FETCH

```java
public interface AuctionRepository extends JpaRepository<Auction, Long> {

    // Điền từ khóa của JPQL để Hibernate NẠP LUÔN dữ liệu bảng Bids cùng với Auction trong 1 câu SQL
    @Query("SELECT DISTINCT a FROM Auction a LEFT ___ ___ a.bids") 
    List<Auction> findAllWithBids();
}
```
```java
@Transactional(readOnly = true)
public void fixedWithFetch() {
    // 1 query duy nhất nhờ có từ khóa FETCH
    List<Auction> auctions = auctionRepository.findAllWithBids();  
    for (Auction a : auctions) {
        System.out.println(a.getTitle() + " có " + a.getBids().size() + " lượt");
    }
}
```

### Bài 4 — Sửa N+1 bằng `@EntityGraph`

```java
public interface AuctionRepository extends JpaRepository<Auction, Long> {

    @___(attributePaths = "bids")     // Điền annotation định nghĩa "Đồ thị đối tượng" cần nạp sẵn
    List<Auction> findByStatus(AuctionStatus status);
}
```

### Bài 5 — Batch size và Tắt Open In View (lưới an toàn)

```yaml
# application.yml
spring:
  jpa:
    ___: false      # Điền property để tắt OSIV: ép phải load data rõ ràng trong Service, tránh N+1 ngầm ở View
    properties:
      hibernate.default_batch_fetch_size: 20   # gom lazy load theo lô 20
```

### Bước 6 — CHALLENGE: Tái hiện và Khắc phục LazyInitializationException

> 🏆 Yêu cầu:
> 1. Viết 1 Service không chứa `@Transactional` và gọi hàm `auctionRepository.findById(1)`.
> 2. Lấy object `Auction` vừa tìm được, gọi `auction.getBids().size()` -> Nhận lỗi `LazyInitializationException` đỏ lòm ở Console.
> 3. Khắc phục bằng cách gắn `@Transactional` vào Service.
> 4. Khắc phục bằng một cách khác mà không cần gắn `@Transactional` (Sử dụng EntityGraph hoặc JOIN FETCH trong Repository).

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng phải gọi `save()` để cập nhật.** Với entity managed trong transaction, đổi field là đủ (dirty checking). Ngược lại, **đổi field một entity detached rồi mong nó tự lưu** → không có gì xảy ra (phải `merge`).
- **`LazyInitializationException`** do truy cập quan hệ lazy sau khi transaction đóng. Fix: fetch trong transaction hoặc map sang DTO trong service.
- **Dựa vào OSIV (`open-in-view=true`) để "khỏi lỗi lazy".** Nó che lỗi nhưng đẩy query xuống tầng view, gây N+1 ẩn và giữ kết nối DB lâu. Nên tắt và fetch tường minh.
- **N+1 âm thầm** vì không bật `show-sql`/không đếm query. Luôn quan sát số query khi nạp danh sách + quan hệ.
- **`JOIN FETCH` nhiều collection cùng lúc** → tích Descartes bùng nổ. Chỉ fetch một collection mỗi query, hoặc dùng `@EntityGraph`/batch.
- **`JOIN FETCH` collection + `Pageable`** → Hibernate phân trang trong bộ nhớ (cảnh báo `HHH000104`), tốn RAM nghiêm trọng với dữ liệu lớn.
- **Quên `DISTINCT`** với fetch collection → kết quả lặp dòng cha.
- **Dùng `merge()` mà bỏ giá trị trả về** → object truyền vào vẫn detached, thay đổi không được lưu.

---

## 🚀 Liên hệ Spring Boot / Production

- **Tắt OSIV mặc định**: `spring.jpa.open-in-view=false` để lộ sớm các chỗ fetch thiếu (fail-fast) và giải phóng kết nối DB nhanh — chuẩn cho service hiệu năng cao.
- **`@Transactional(readOnly = true)`** cho thao tác chỉ đọc: Hibernate có thể bỏ dirty checking/flush, tối ưu nhẹ và rõ ý đồ.
- **DTO ở biên**: map entity → DTO **trong** transaction; không bao giờ trả entity ra controller (tránh lazy exception + lộ dữ liệu).
- **Giám sát query**: dùng `datasource-proxy`/p6spy hoặc Hibernate statistics để phát hiện N+1 và query chậm trong CI/observability.
- **Batch insert/update**: bật `hibernate.jdbc.batch_size` để gom nhiều INSERT/UPDATE thành batch, giảm round-trip DB.
- **L2 cache có chọn lọc**: chỉ cache dữ liệu đọc-nhiều-ít-đổi; cân nhắc invalidation khi nhiều instance.
- **Transaction ngắn**: giữ transaction càng ngắn càng tốt để giảm khóa và giữ pool kết nối khỏe.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta **tối ưu truy vấn** danh sách phiên + bid để tránh N+1, và minh họa dirty checking.

**Nhiệm vụ Day 31:**

1. Viết endpoint `GET /auctions/with-bids` trả danh sách phiên kèm số lượt đặt giá. **Đầu tiên** cố tình để N+1 (lazy trong vòng lặp), bật `show-sql`, đếm số query.
2. Sửa N+1 bằng hai cách: (a) `@Query ... JOIN FETCH a.bids` (Bài 3); (b) `@EntityGraph(attributePaths="bids")` (Bài 4). So sánh số query trước/sau.
3. Tắt `open-in-view` (Bài 5), map entity → DTO (`AuctionWithBidsDto`) **trong** tầng service để tránh `LazyInitializationException`.
4. Viết `renameAuction(id, title)` minh họa dirty checking (Bài 1), xác nhận log có `UPDATE`.
5. Hoàn thành **CHALLENGE** ở Bước 6: Tự chủ động tạo ra lỗi kinh điển `LazyInitializationException` và cách xử lý triệt để.

> 🎯 Tiêu chí đạt: Biết đếm số lượng SQL Query ở Log Console. Biết cách tắt N+1. Hiểu sâu tại sao Không dùng Save() mà DB vẫn tự update.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Persistence Context là gì?**
> **Đáp:** Là vùng nhớ do `EntityManager` quản lý, chứa các entity đang được quản lý (managed) trong phạm vi một transaction. Nó đóng vai trò first-level cache: trong cùng transaction, tìm cùng một entity nhiều lần chỉ tốn một query. Hết transaction, context bị xóa, entity thành detached.

**Q2: Dirty checking là gì?**
> **Đáp:** Là cơ chế Hibernate tự phát hiện thay đổi trên entity managed. Khi nạp entity, Hibernate chụp snapshot giá trị; lúc flush, so sánh với state hiện tại, field nào khác thì sinh câu `UPDATE` — không cần gọi `save()` thủ công.

**Q3: Bốn trạng thái của entity là gì?**
> **Đáp:** Transient (mới `new`, context không biết), Managed/Persistent (trong context, được theo dõi), Detached (từng managed nhưng context đã đóng), Removed (đã đánh dấu xóa, sẽ DELETE lúc flush). Chuyển trạng thái qua `persist`, `merge`, `remove`, `detach`.

**Q4: N+1 query là gì và cách tránh?**
> **Đáp:** Là khi nạp danh sách N entity (1 query) rồi truy cập quan hệ lazy của từng phần tử (thêm N query), tổng N+1. Tránh bằng `JOIN FETCH` trong JPQL, `@EntityGraph`, hoặc batch fetching (`default_batch_fetch_size`).

### Mức Senior

**Q5: flush khác commit thế nào?**
> **Đáp:** `flush` đồng bộ các thay đổi (INSERT/UPDATE/DELETE) xuống DB nhưng transaction vẫn mở (vẫn rollback được). `commit` thực hiện flush (nếu chưa) rồi kết thúc transaction, chốt thay đổi vĩnh viễn. Sau flush mà rollback thì thay đổi vẫn bị hủy vì transaction chưa commit.

**Q6: `LazyInitializationException` xảy ra khi nào? Cách xử lý đúng?**
> **Đáp:** Khi truy cập quan hệ lazy của một entity đã **detached** (transaction/session đã đóng) — không còn session để query thêm. Xử lý đúng: nạp sẵn quan hệ trong transaction bằng `JOIN FETCH`/`@EntityGraph`, hoặc map sang DTO ngay trong tầng service. Không nên dựa vào OSIV (`open-in-view=true`) vì nó che lỗi và gây query ẩn ở tầng view.

**Q7: Vì sao nên tắt Open Session In View (`open-in-view=false`)?**
> **Đáp:** OSIV giữ persistence context mở tới hết request (cả tầng view), khiến lazy load có thể chạy ở controller/template — gây N+1 ẩn, giữ kết nối DB lâu, khó kiểm soát. Tắt OSIV buộc lập trình viên fetch tường minh trong transaction (fail-fast), giải phóng kết nối sớm, hiệu năng và rõ ràng hơn.

**Q8: Khác biệt cốt lõi giữa Eloquent và Hibernate về theo dõi thay đổi là gì?**
> **Đáp:** Eloquent (Active Record) yêu cầu gọi `$model->save()` tường minh để ghi; nó có `isDirty()` để xem thay đổi nhưng không tự lưu. Hibernate (Data Mapper) có dirty checking: entity managed trong transaction, đổi field là đủ — tự sinh UPDATE lúc commit. Ngoài ra Hibernate có persistence context (first-level cache) và identity map mà Eloquent không có; bù lại Hibernate có rủi ro `LazyInitializationException` ngoài transaction.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được Persistence Context = first-level cache theo transaction
- [ ] Hiểu identity map (một id → một object trong context)
- [ ] Thuộc 4 trạng thái entity và các phép chuyển (persist/merge/remove/detach)
- [ ] Hiểu dirty checking và vì sao thường không cần gọi save()
- [ ] Phân biệt flush vs commit
- [ ] Tái hiện được N+1 và sửa bằng JOIN FETCH / @EntityGraph / batch size
- [ ] Hiểu LAZY vs EAGER và cách tránh LazyInitializationException (tắt OSIV, DTO)
- [ ] Nêu được khác biệt cốt lõi với Eloquent (dirty tracking, identity map, lazy)
- [ ] Thực hành Dirty Checking không cần hàm `save()`
- [ ] Chứng minh được Identity Map (First Level Cache) của Hibernate
- [ ] Tái hiện và Khắc phục lỗi N+1 Query (bằng FETCH / EntityGraph)
- [ ] Setup cấu hình Batch Fetch Size và Tắt OSIV
- [ ] Hoàn thành Challenge: Xử lý LazyInitializationException
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Hibernate ORM User Guide — "Persistence Context", "Flushing", "Fetching", "Caching"
- Jakarta Persistence Specification — "Entity Operations", "Entity Lifecycle"
- Vlad Mihalcea — "The best way to handle the LazyInitializationException", "N+1 query problem"
- Baeldung — "Hibernate Dirty Checking", "JPA Entity Lifecycle", "Spring Data JPA @EntityGraph"
- Spring Boot Reference — "Open EntityManager in View" (`spring.jpa.open-in-view`)
- Laravel Docs — "Eloquent: isDirty / wasChanged" (để thấy rõ khác biệt)
