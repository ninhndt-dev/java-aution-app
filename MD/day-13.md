# Day 13 - Exception Design

> **Giai đoạn:** Exception & Core Java
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn thiết kế hệ thống xử lý lỗi (exception) chuẩn chỉ, sạch sẽ trong Java.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm vững **cây phân cấp** `Throwable → Error / Exception → RuntimeException`, biết thứ nào nên bắt và thứ nào tuyệt đối không.
- Phân biệt **checked vs unchecked exception**, hiểu khi nào dùng cái nào theo tư duy hiện đại.
- Thành thạo **try-catch-finally**, **multi-catch**, và đặc biệt là **try-with-resources** (`AutoCloseable`).
- Tự thiết kế **custom exception** có ngữ cảnh (context) và biết **chaining** (giữ nguyên nhân gốc).
- Thuộc lòng các **best practice** xử lý lỗi production: fail fast, đừng nuốt lỗi, log đúng chỗ.
- Liên hệ với `app/Exceptions/Handler.php` của Laravel và teaser `@RestControllerAdvice` của Spring.

---

## 🧠 Lý thuyết cốt lõi

### 1. Cây phân cấp ngoại lệ trong Java

Mọi thứ "ném ra được" (throwable) đều kế thừa từ lớp `Throwable`. Đây là sơ đồ bạn **phải vẽ được** khi đi phỏng vấn:

```
                        java.lang.Throwable
                        (gốc của mọi thứ ném được)
                        /                    \
                   Error                   Exception
            (JVM/môi trường hỏng,      (lỗi ứng dụng,
             KHÔNG nên bắt)             nên xử lý)
              /      \                 /            \
  OutOfMemoryError   StackOverflowError        RuntimeException
  VirtualMachineError ...               (UNCHECKED)        |
                                        /     |     \      |  (mọi lớp con
                          NullPointerException |  IllegalState  RuntimeException
                          IllegalArgumentException  Exception   đều unchecked)
                                                              |
                              ───────── còn lại là CHECKED ──────────
                              IOException, SQLException, FileNotFoundException...
                              (con trực tiếp của Exception, KHÔNG qua RuntimeException)
```

Hai nhánh lớn cần khắc cốt ghi tâm:

- **`Error`**: lỗi nghiêm trọng ở tầng JVM/môi trường (hết bộ nhớ, tràn stack). Ứng dụng **không phục hồi được** → đừng `catch`.
- **`Exception`**: lỗi của tầng ứng dụng. Bên trong lại chia hai loại theo cách compiler đối xử:
  - **`RuntimeException` và con của nó** → **unchecked** (compiler không ép xử lý).
  - **Mọi `Exception` khác** (con trực tiếp của `Exception`, không đi qua `RuntimeException`) → **checked** (compiler ép xử lý).

> 💡 Mẹo nhớ: "Cứ là `RuntimeException` hoặc con cháu của nó thì **unchecked**. Còn lại của `Exception` là **checked**. `Error` thì miễn bàn — không đụng vào."

### 2. `Error` — đừng bắt, đừng cố cứu

```java
public class StackOverflowDemo {
    static int dem(int n) {
        return dem(n + 1); // đệ quy vô tận -> StackOverflowError
    }
    public static void main(String[] args) {
        dem(0);
    }
}
```

- `StackOverflowError`: stack của thread bị tràn (thường do đệ quy vô tận).
- `OutOfMemoryError`: Heap đầy, GC dọn không kịp, không cấp phát thêm được.

> ⚠️ Đừng viết `catch (OutOfMemoryError e)` để "cứu" rồi chạy tiếp như chưa có gì. Tại thời điểm đó JVM đã ở trạng thái bất định: object có thể chưa khởi tạo xong, log có thể không ghi nổi. Cùng lắm chỉ nên bắt ở mức rất cao để **ghi lại dấu vết rồi thoát có kiểm soát** — không phải để "tiếp tục bình thường".

### 3. Checked vs Unchecked — khác biệt cốt lõi

| Tiêu chí | Checked Exception | Unchecked (RuntimeException) |
|---|---|---|
| Compiler đối xử | **Bắt buộc** `try-catch` hoặc khai báo `throws` | Không bắt buộc gì cả |
| Lớp cha | Con của `Exception` (không qua `RuntimeException`) | Con của `RuntimeException` |
| Ví dụ | `IOException`, `SQLException`, `FileNotFoundException` | `NullPointerException`, `IllegalArgumentException`, `IllegalStateException` |
| Ý nghĩa | Lỗi **có thể lường trước & phục hồi**, caller buộc phải đối mặt | Lỗi do **bug / vi phạm tiền điều kiện** |
| Khi nào dùng | Khi caller có hành động phục hồi hợp lý (retry, fallback) | Khi lỗi là "lập trình sai" hoặc nghiệp vụ không thể tiếp tục |

**Khi nào dùng checked?** Khi bạn thật sự tin caller **có thể và nên** xử lý: ví dụ đọc file mạng (`IOException`) — caller có thể retry hoặc dùng cache.

**Khi nào dùng unchecked?** Khi lỗi báo hiệu **bug** (truyền `null` sai, gọi sai thứ tự) hoặc **vi phạm quy tắc nghiệp vụ** mà việc ép mọi caller `try-catch` chỉ làm code rối.

> 💡 **Quan điểm thực dụng hiện đại:** Phần lớn framework lớn (Spring, Hibernate) đều **ưu tiên unchecked** cho lỗi nghiệp vụ. Lý do: checked exception lan ra khắp chữ ký hàm (`throws` chồng chất), ép `try-catch` rỗng vô nghĩa, và cản trở lambda/stream (lambda không khai báo `throws` checked được). Spring biến cả `SQLException` (checked) thành `DataAccessException` (unchecked) chính vì lẽ này. **Lời khuyên:** với custom exception nghiệp vụ, hãy kế thừa `RuntimeException` trừ khi có lý do rõ ràng cần checked.

### 4. `try-catch-finally` và sự thật về `finally`

```
try {
    // code có thể ném exception
} catch (KiểuLỗiCụThể e) {
    // xử lý lỗi
} finally {
    // LUÔN LUÔN chạy: dù try thành công,
    // dù catch chạy, dù try có return/throw
}
```

`finally` chạy trong **mọi** trường hợp: kể cả khi trong `try` có `return` hay `throw`. JVM "tạm giữ" giá trị return / exception lại, chạy xong `finally`, rồi mới thực sự trả về / ném ra. Dùng `finally` để **dọn tài nguyên** (đóng file, đóng connection, nhả lock).

> ⚠️ **Bẫy chết người:** đặt `return` (hoặc `throw`) trong `finally`. Nó sẽ **ghi đè** giá trị/exception của `try` → **nuốt mất** exception gốc.

```java
int viDuSai() {
    try {
        throw new IllegalStateException("Lỗi thật");
    } finally {
        return 0; // NUỐT exception trên! Hàm trả về 0 như chưa hề có lỗi. KHÔNG BAO GIỜ làm vậy.
    }
}
```

### 5. Multi-catch — gộp nhánh xử lý

Khi nhiều loại lỗi xử lý giống nhau, gộp bằng dấu `|`:

```java
try {
    docDuLieu();
} catch (IOException | SQLException e) {   // gộp 2 loại, biến e là kiểu cha chung gần nhất
    log.error("Lỗi I/O hoặc DB khi đọc dữ liệu", e);
    throw new DataLoadException("Không tải được dữ liệu", e);
}
```

> ⚠️ Trong multi-catch, biến `e` ngầm hiểu là **final**, không gán lại được. Và **không** được liệt kê hai lớp có quan hệ cha-con (ví dụ `IOException | FileNotFoundException`) — vì thừa, compiler báo lỗi.

### 6. `try-with-resources` + `AutoCloseable` — đóng tài nguyên tự động

Đây là **cách hiện đại** thay cho `finally` thủ công. Bất kỳ object nào implement `AutoCloseable` (hoặc `Closeable`) khai báo trong dấu `()` sẽ được **tự động gọi `close()`** khi rời khối — kể cả khi có exception, và theo **thứ tự ngược** (mở sau đóng trước).

```java
try (var in = new FileInputStream("a.txt");
     var out = new FileOutputStream("b.txt")) {   // mở: in -> out
    in.transferTo(out);
}   // tự đóng: out -> in (ngược thứ tự), kể cả khi transferTo ném lỗi
```

So với code cũ phải `finally { if (x != null) x.close(); }` lồng nhau rối rắm, `try-with-resources` gọn và **đúng** hơn nhiều.

**Suppressed exception (ngoại lệ bị "kèm theo"):** Nếu khối `try` ném exception A, rồi lúc `close()` lại ném exception B, thì B **không che mất** A. A được ném ra (chính), B được "đính kèm" và lấy lại qua `Throwable.getSuppressed()`. Đây là điều mà `finally` thủ công thường làm sai (B đè mất A).

```
try (...) {
    throw A;          // exception chính
}                     // close() ném B -> B trở thành "suppressed"
// Caller nhận A; e.getSuppressed() -> [B]
```

`Closeable` (cũ, trong `java.io`) là con của `AutoCloseable`; khác biệt nhỏ: `Closeable.close()` chỉ ném `IOException`, còn `AutoCloseable.close()` ném `Exception` chung. Tự viết tài nguyên thì implement `AutoCloseable` là đủ.

### 7. Custom exception + Exception chaining

Một custom exception tốt cần:
1. Kế thừa `RuntimeException` (unchecked) hoặc `Exception` (checked).
2. Có constructor `(String message)` và `(String message, Throwable cause)`.
3. Mang **trường ngữ cảnh** (id, tham số) để debug nhanh không cần đoán.

**Chaining (nối nguyên nhân):** khi bắt lỗi tầng dưới rồi ném lỗi nghiệp vụ tầng trên, hãy **truyền `cause`** để không mất stack trace gốc:

```java
try {
    repo.find(id);
} catch (SQLException e) {
    // SAI: throw new AuctionNotFoundException("..."); -> mất sạch dấu vết SQL gốc
    // ĐÚNG: truyền e làm cause -> stack trace giữ cả 2 tầng
    throw new AuctionRepositoryException("Lỗi truy vấn phiên đấu giá " + id, e);
}
```

`getCause()` lấy ra nguyên nhân; khi in stack trace bạn sẽ thấy dòng **`Caused by: ...`** — đó chính là chuỗi chaining. Mất `cause` = mất nửa bằng chứng khi điều tra sự cố production.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel / PHP | Java / Spring |
|---|---|---|
| Gốc của mọi lỗi | `Throwable` (PHP 7+) | `java.lang.Throwable` |
| Hai nhánh con | `Error` (lỗi engine) vs `Exception` | `Error` (lỗi JVM) vs `Exception` |
| Checked exception | **Không có** khái niệm này | Có — compiler ép `try-catch`/`throws` |
| Bắt mọi lỗi | `catch (\Throwable $e)` | `catch (Throwable e)` (hạn chế dùng) |
| Custom exception | `class XException extends Exception` | `class XException extends RuntimeException` |
| Xử lý lỗi tập trung | `app/Exceptions/Handler.php` | `@RestControllerAdvice` |
| Ghi log lỗi | `Handler::report()` | gọi `log.error(...)` trong handler |
| Render thành response | `Handler::render()` | `@ExceptionHandler` trả `ResponseEntity` |
| Lỗi nhanh theo HTTP | `abort(404)`, `abort(403)` | ném exception → handler map sang status |
| Dọn tài nguyên | `try/finally`, `defer` (không có sẵn) | `try-with-resources` (`AutoCloseable`) |

**Khác biệt tư duy quan trọng nhất:**

- **PHP không có checked exception.** Trong Laravel bạn ném exception thoải mái, không ai ép `try-catch`. Java thì compiler có thể **chặn build** nếu bạn không xử lý checked exception. Đây là cú sốc đầu tiên — và lời khuyên là dùng unchecked cho nghiệp vụ để code gần với cảm giác PHP nhưng vẫn an toàn.
- **`Handler.php` tách đôi rất rõ:** `report()` lo **ghi log**, `render()` lo **biến lỗi thành HTTP response**. Spring tách y hệt: bạn `log.error()` để report và `return ResponseEntity` để render — nhưng gom trong các method `@ExceptionHandler` của một lớp `@RestControllerAdvice`.

> 🧩 Hệ quả: Ở Laravel bạn quen `abort(404, 'Không tìm thấy')` là xong. Ở Spring, bạn ném `AuctionNotFoundException`, rồi **một nơi duy nhất** (`@RestControllerAdvice`) map nó thành `404`. Controller sạch, không lẫn logic lỗi — đây là điều ta sẽ dựng ở các ngày Spring sau.

---

## 💻 Thực hành code

### Bước 1 — Thiết kế cây exception cho Auction

```java
// File: BidException.java
// Lớp gốc cho MỌI lỗi nghiệp vụ liên quan đặt giá (bid).
// Kế thừa RuntimeException -> UNCHECKED: caller không bị ép try-catch,
// hợp với tư duy hiện đại (Spring sẽ map sang HTTP ở ngày sau).
public class BidException ___ RuntimeException { // Điền từ khóa kế thừa lớp

    public BidException(String message) {
        super(message);
    }

    // Constructor có 'cause' để giữ nguyên nhân gốc (chaining).
    public BidException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
// File: BidTooLowException.java
// Lỗi cụ thể: giá đặt thấp hơn giá cao nhất hiện tại.
// Mang theo NGỮ CẢNH (currentHighest, attempted) để debug & dựng message chuẩn.
import java.math.BigDecimal;

public class BidTooLowException ___ BidException { // Điền từ khóa kế thừa lớp

    private final BigDecimal currentHighest;
    private final BigDecimal attempted;

    public BidTooLowException(BigDecimal currentHighest, BigDecimal attempted) {
        super("Giá đặt %s thấp hơn giá cao nhất hiện tại %s"
                .formatted(attempted, currentHighest));
        this.currentHighest = currentHighest;
        this.attempted = attempted;
    }

    public BigDecimal getCurrentHighest() { return currentHighest; }
    public BigDecimal getAttempted()      { return attempted; }
}
```

```java
// File: AuctionClosedException.java
// Lỗi cụ thể: phiên đấu giá đã đóng, không nhận bid nữa.
import java.time.Instant;

public class AuctionClosedException extends BidException {

    private final long auctionId;
    private final Instant closedAt;

    public AuctionClosedException(long auctionId, Instant closedAt) {
        super("Phiên đấu giá #%d đã đóng lúc %s".formatted(auctionId, closedAt));
        this.auctionId = auctionId;
        this.closedAt = closedAt;
    }

    public long getAuctionId()    { return auctionId; }
    public Instant getClosedAt()  { return closedAt; }
}
```

```java
// File: AuctionNotFoundException.java
// Lỗi cụ thể: không tìm thấy phiên đấu giá theo id (sẽ map sang HTTP 404).
public class AuctionNotFoundException extends BidException {

    private final long auctionId;

    public AuctionNotFoundException(long auctionId) {
        super("Không tìm thấy phiên đấu giá #" + auctionId);
        this.auctionId = auctionId;
    }

    public long getAuctionId() { return auctionId; }
}
```

### Bước 2 — Tài nguyên tự đóng: `AuctionLock implements AutoCloseable`

```java
// File: AuctionLock.java
// Mô phỏng một "khóa" trên phiên đấu giá (để tránh 2 người bid cùng lúc).
// Implement AutoCloseable -> dùng được trong try-with-resources, tự nhả khóa.
public class AuctionLock ___ AutoCloseable { // Điền từ khóa thực thi interface

    private final long auctionId;

    private AuctionLock(long auctionId) {
        this.auctionId = auctionId;
    }

    // Factory: "mở" (acquire) khóa và log lại.
    public static AuctionLock acquire(long auctionId) {
        System.out.println("[LOCK] Đã khóa phiên #" + auctionId);
        return new AuctionLock(auctionId);
    }

    @Override
    public void close() {
        // close() được try-with-resources gọi tự động khi rời khối,
        // kể cả khi bên trong ném exception.
        System.out.println("[LOCK] Đã NHẢ khóa phiên #" + auctionId);
    }
}
```

### Bước 3 — `AuctionService.placeBid(...)` ném đúng exception

```java
// File: AuctionService.java
import java.math.BigDecimal;
import java.time.Instant;

public class AuctionService {

    private final AuctionRepository repo;

    public AuctionService(AuctionRepository repo) {
        this.repo = repo;
    }

    public void placeBid(long auctionId, BigDecimal bidAmount) {
        // 1) Fail fast: kiểm tra tiền điều kiện -> unchecked IllegalArgumentException.
        if (bidAmount == null || bidAmount.signum() <= 0) {
            throw new IllegalArgumentException("Số tiền đặt phải > 0, nhận được: " + bidAmount);
        }

        // 2) Lấy phiên đấu giá. Bọc lỗi tầng repo thành lỗi nghiệp vụ (CHAINING).
        Auction auction;
        try {
            auction = repo.findById(auctionId);
        } catch (RepositoryException e) {
            // Giữ nguyên nhân gốc 'e' -> không mất stack trace tầng dưới.
            throw new BidException("Lỗi khi truy vấn phiên #" + auctionId, e);
        }
        if (auction == null) {
            throw new AuctionNotFoundException(auctionId);
        }

        // 3) Khóa trong suốt thao tác bid; try-with-resources tự nhả khóa.
        ___ (AuctionLock lock = AuctionLock.acquire(auctionId)) { // Điền từ khóa mở khối try-with-resources

            // 4) Phiên đã đóng?
            if (auction.isClosed()) {
                throw new AuctionClosedException(auctionId, auction.getClosedAt());
            }
            // 5) Giá thấp hơn giá cao nhất?
            if (bidAmount.compareTo(auction.getCurrentHighest()) <= 0) {
                throw new BidTooLowException(auction.getCurrentHighest(), bidAmount);
            }

            // 6) Hợp lệ -> cập nhật giá cao nhất.
            auction.setCurrentHighest(bidAmount);
            System.out.println("[BID] Phiên #" + auctionId + " giá mới = " + bidAmount);

        } // <-- dù bước 4/5/6 ném lỗi, lock.close() vẫn chạy: khóa luôn được nhả.
    }
}
```

### Bước 4 — Multi-catch khi gọi service

```java
// File: BidController (mô phỏng tầng gọi, chưa phải Spring).
import java.math.BigDecimal;

public class BidDemo {
    public static void main(String[] args) {
        var service = new AuctionService(new InMemoryAuctionRepository());
        try {
            service.placeBid(1L, new BigDecimal("50.00"));
        } ___ (BidTooLowException | AuctionClosedException e) { // Điền từ khóa bắt ngoại lệ
            // Gộp 2 loại lỗi "client sai" để cùng phản hồi (sẽ là HTTP 400/409).
            System.out.println("Bid bị từ chối: " + e.getMessage());
        } catch (AuctionNotFoundException e) {
            System.out.println("Không có phiên: " + e.getMessage()); // -> HTTP 404
        } catch (BidException e) {
            // Bắt lỗi nghiệp vụ chung còn lại, kèm in cause để soi chaining.
            System.out.println("Lỗi hệ thống đấu giá: " + e.getMessage());
            if (e.getCause() != null) {
                System.out.println("Nguyên nhân gốc: " + e.getCause());
            }
        }
    }
}
```

> ✅ **Bài tập tự kiểm tra:** Cho `placeBid` ném lỗi ở bước 5 (giá thấp). Quan sát log: dòng `[LOCK] Đã NHẢ khóa` **vẫn** in ra trước khi exception lan tới `catch`. Đó là bằng chứng `try-with-resources` đóng tài nguyên kể cả khi có lỗi.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Nuốt lỗi (swallow):** `catch (Exception e) {}` rỗng — lỗi biến mất không dấu vết, debug địa ngục. Tối thiểu phải log kèm `e`.
- **`catch (Exception e)` / `catch (Throwable e)` chung chung:** bắt luôn cả `NullPointerException`, cả `Error`. Hãy bắt **cụ thể** loại bạn thật sự xử lý được.
- **`return`/`throw` trong `finally`:** nuốt mất exception gốc của `try`. Cấm tuyệt đối.
- **Quên truyền `cause`:** `throw new MyException(msg)` (không kèm `e`) → mất stack trace gốc, chỉ còn nửa câu chuyện.
- **Dùng exception cho luồng điều khiển bình thường:** ví dụ dùng exception để thoát vòng lặp. Việc dựng stack trace **đắt**; lỗi nên là "ngoại lệ" thật sự, không phải `if-else` trá hình.
- **Log rồi throw lại (log-and-throw):** bạn `log.error` rồi `throw`, tầng trên lại `log.error` tiếp → **log trùng** một sự cố nhiều lần, rối khi điều tra. Quy tắc: **hoặc** xử lý & log ở đây, **hoặc** ném lên cho tầng trên log — đừng cả hai.
- **Bắt `OutOfMemoryError` để "chạy tiếp":** JVM đã bất định, vô nghĩa và nguy hiểm.
- **Khai báo `throws Exception` cho gọn:** chữ ký quá chung, caller mất thông tin loại lỗi cụ thể. Khai báo loại hẹp nhất có thể.

---

## 🚀 Liên hệ Spring Boot / Production

Trong Spring Boot, ta **không** rải `try-catch` khắp controller. Thay vào đó tách xử lý lỗi ra một lớp `@RestControllerAdvice`:

```java
// Teaser — ta sẽ dựng đầy đủ ở ngày Spring sau.
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AuctionNotFoundException.class)
    public ProblemDetail handleNotFound(AuctionNotFoundException e) {
        // ProblemDetail (RFC 7807) — chuẩn body lỗi của Spring 6.
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BidTooLowException.class)
    public ProblemDetail handleTooLow(BidTooLowException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage()); // 400
    }

    @ExceptionHandler(AuctionClosedException.class)
    public ResponseEntity<String> handleClosed(AuctionClosedException e) {
        // 409 Conflict — trạng thái tài nguyên xung đột với yêu cầu.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
```

- **Tách bạch:** controller chỉ gọi `service.placeBid(...)` rồi ném exception; handler lo map sang HTTP. Đúng tinh thần `Handler::render()` của Laravel.
- **Map exception → status:** `AuctionNotFoundException → 404`, `BidTooLowException → 400`, `AuctionClosedException → 409`.
- **Hibernate/JDBC:** Spring đã bọc `SQLException` (checked) thành `DataAccessException` (unchecked) — minh chứng cho triết lý "ưu tiên unchecked".
- **Quan sát (observability):** log lỗi 5xx kèm **correlation/trace id** để soi qua hệ thống phân tán; lỗi 4xx (client sai) thường chỉ cần đếm metric, không cần stack trace ồn ào.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta xây **xương sống xử lý lỗi** cho Auction API — nền tảng để các ngày sau map sang HTTP.

**Nhiệm vụ Day 13:**
1. Thiết kế cây exception: `BidException` (base, `extends RuntimeException`) và 3 lớp con `BidTooLowException`, `AuctionClosedException`, `AuctionNotFoundException` — mỗi lớp con mang trường ngữ cảnh phù hợp.
2. Viết `AuctionLock implements AutoCloseable` in log lúc mở/đóng khóa.
3. Cài `AuctionService.placeBid(long auctionId, BigDecimal bidAmount)`:
   - `fail fast` với `IllegalArgumentException` nếu `bidAmount <= 0`.
   - Ném `AuctionNotFoundException` khi không thấy phiên.
   - Ném `AuctionClosedException` khi phiên đã đóng.
   - Ném `BidTooLowException` khi giá ≤ giá cao nhất hiện tại.
   - Dùng `try-with-resources` với `AuctionLock` để đảm bảo nhả khóa.
   - **Chaining:** bọc `RepositoryException` (tầng repo) thành `BidException` có `cause`.
4. Viết `main` dùng **multi-catch** gọi `placeBid` với vài kịch bản (giá thấp, phiên đóng, không tồn tại) và in kết quả.
5. Ghi `notes/day-13.md`: bảng "exception nào → HTTP status nào" (teaser cho ngày Spring: 400/404/409).

> 🔮 **Teaser:** Ngày Spring tới, ta sẽ thêm `@RestControllerAdvice` map đúng các exception này sang `400 / 404 / 409` mà **không sửa một dòng nào** trong `AuctionService` — đó là sức mạnh của tách lớp xử lý lỗi.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Phân biệt checked và unchecked exception?**
> **Đáp:** Checked là con của `Exception` (không qua `RuntimeException`), compiler **bắt buộc** `try-catch` hoặc khai báo `throws` (vd `IOException`, `SQLException`). Unchecked là `RuntimeException` và con của nó, compiler **không ép** xử lý (vd `NullPointerException`, `IllegalArgumentException`). Checked dùng cho lỗi có thể phục hồi mà caller buộc đối mặt; unchecked cho bug/vi phạm tiền điều kiện.

**Q2: `Error` khác `Exception` thế nào? Có nên bắt `Error` không?**
> **Đáp:** `Error` là lỗi nghiêm trọng tầng JVM/môi trường (`OutOfMemoryError`, `StackOverflowError`), thường **không phục hồi được** → **không nên bắt**. `Exception` là lỗi tầng ứng dụng, có thể và nên xử lý. Cả hai cùng kế thừa `Throwable`.

**Q3: `finally` có luôn chạy không? Trường hợp nào không?**
> **Đáp:** `finally` chạy trong gần như mọi trường hợp, kể cả khi `try`/`catch` có `return` hoặc `throw`. Nó chỉ **không** chạy nếu JVM bị tắt cứng (`System.exit()`), thread bị kill, hoặc gặp lỗi chí mạng (vd máy mất điện). Dùng `finally` để dọn tài nguyên — nhưng đừng `return`/`throw` trong đó vì sẽ nuốt exception gốc.

**Q4: `try-with-resources` là gì, lợi gì so với `finally`?**
> **Đáp:** Cú pháp khai báo tài nguyên (object implement `AutoCloseable`) trong dấu `()` của `try`; JVM **tự gọi `close()`** khi rời khối, theo thứ tự ngược, kể cả khi có lỗi. Lợi hơn `finally` thủ công ở chỗ: ngắn gọn, không quên `close()`, không lồng `if != null`, và xử lý đúng **suppressed exception** (lỗi lúc `close()` không che mất lỗi chính).

### Mức Senior

**Q5: Khi nào nên thiết kế custom exception là checked, khi nào unchecked? Triết lý hiện đại nghiêng về đâu?**
> **Đáp:** Checked khi caller có hành động phục hồi rõ ràng và ta muốn **ép** họ đối mặt (hiếm trong nghiệp vụ web). Unchecked cho phần lớn lỗi nghiệp vụ vì tránh `throws` lan tràn, tránh `try-catch` rỗng, và tương thích lambda/stream. Triết lý hiện đại (Spring, Hibernate) **nghiêng mạnh về unchecked**: Spring bọc `SQLException` checked thành `DataAccessException` unchecked. Lời khuyên: custom exception nghiệp vụ kế thừa `RuntimeException` mặc định.

**Q6: Exception chaining là gì và vì sao quan trọng trong production?**
> **Đáp:** Là việc truyền nguyên nhân gốc qua constructor `(message, cause)` hoặc `initCause()`. Khi bọc lỗi tầng thấp (vd `SQLException`) thành lỗi nghiệp vụ tầng cao, truyền `cause` giúp giữ nguyên **stack trace gốc** — in ra dưới dạng `Caused by:`. Mất `cause` = mất nửa bằng chứng, điều tra sự cố production rất khó vì không biết lỗi thật bắt nguồn từ đâu.

**Q7: Vì sao dùng exception cho luồng điều khiển bình thường là phản pattern về hiệu năng?**
> **Đáp:** Tạo một exception phải **dựng stack trace** (`fillInStackTrace`) — duyệt toàn bộ stack frame, khá tốn CPU và bộ nhớ. Nếu lạm dụng exception thay cho `if/return` trong vòng lặp nóng, chi phí này nhân lên rất lớn. Exception nên dành cho trường hợp **ngoại lệ thật sự**, không phải logic thường ngày. (Có thể tắt stack trace bằng constructor `Throwable(msg, cause, false, false)` cho exception hiệu năng cao, nhưng đó là tối ưu hiếm gặp.)

**Q8: Trong kiến trúc nhiều tầng, nên log lỗi ở đâu để tránh log trùng?**
> **Đáp:** Quy tắc "**log một lần, ở một nơi**". Tầng dưới chỉ nên ném lỗi (kèm `cause`), **không** log rồi ném lại — vì tầng trên (thường là `@RestControllerAdvice` ở biên ứng dụng) sẽ log đầy đủ kèm ngữ cảnh request/trace id. Nếu mỗi tầng đều log thì một sự cố xuất hiện 3-4 lần trong log, gây nhiễu và khó đếm metric chính xác. Log kèm ngữ cảnh (id, tham số), không log thông tin nhạy cảm.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ được cây `Throwable → Error / Exception → RuntimeException` từ trí nhớ
- [ ] Phân biệt checked vs unchecked và biết khi nào dùng cái nào
- [ ] Hiểu `finally` luôn chạy và bẫy `return`/`throw` trong `finally`
- [ ] Dùng được `try-with-resources` và giải thích suppressed exception
- [ ] Tự viết custom exception có ngữ cảnh + chaining (`cause`)
- [ ] Điền các chỗ trống `___` trong code thực hành ở trên
- [ ] Hoàn thành cây exception + `AuctionService.placeBid` của Mini Project Day 13
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Exceptions" (Lesson trong Essential Java Classes)
- Baeldung — "Checked and Unchecked Exceptions in Java", "Java try-with-resources"
- *Effective Java* (Joshua Bloch) — Item 69-77 (chương Exceptions, đọc kỹ Item 70, 72, 73)
- Spring Docs — "Error Handling" & `@RestControllerAdvice`, `ProblemDetail` (RFC 7807)
- Javadoc — `java.lang.Throwable`, `java.lang.AutoCloseable`, `java.io.Closeable`
- Laravel Docs — "Error Handling" (`app/Exceptions/Handler.php`) để đối chiếu
