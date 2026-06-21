# Day 03 - Object Lifecycle

> **Giai đoạn:** Java Foundation & JVM
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm **đủ các cách tạo object** trong Java: `new`, reflection, `clone()`, deserialization, static factory — và biết khi nào dùng cái nào.
- Thuộc lòng **thứ tự khởi tạo** (initialization order) chính xác, kể cả khi có kế thừa cha–con — đây là câu phỏng vấn rất hay bị trả lời sai.
- Hiểu khái niệm **reachability** và phân biệt rạch ròi "**đủ điều kiện GC**" với "**đã bị thu hồi**".
- Phân biệt **4 loại reference**: Strong, Soft, Weak, Phantom — và biết `WeakHashMap` dùng để làm gì.
- Hiểu vì sao `finalize()` **đã bị khai tử** và thay nó bằng **try-with-resources (`AutoCloseable`)** và **`Cleaner`**.
- Liên hệ vòng đời object Java (do GC quyết định, phi xác định) với `__construct`/`__destruct` của PHP (xác định, theo từng request).

---

## 🧠 Lý thuyết cốt lõi

### 1. Các cách tạo một object

Trong Java, "đời" một object bắt đầu khi nó được cấp phát trên **Heap**. Có 5 con đường chính:

| Cách tạo | Cú pháp tiêu biểu | Gọi constructor? | Khi nào dùng |
|---|---|---|---|
| Toán tử `new` | `var u = new User("an")` | **Có** | 99% trường hợp thường ngày |
| Reflection | `clazz.getDeclaredConstructor().newInstance()` | **Có** | Framework (Spring, Jackson, JPA) tạo object lúc runtime |
| `clone()` | `obj.clone()` | **Không** (copy bit-by-bit) | Sao chép object có sẵn (ít dùng, dễ sai) |
| Deserialization | `objectInputStream.readObject()` | **Không** | Khôi phục object từ luồng byte/file/cache |
| Static factory | `User.of("an")`, `List.of(...)` | Gián tiếp (gọi `new` bên trong) | API sạch, kiểm soát việc tạo, có thể cache |

```
                    ┌─────────────────────────────┐
   new User(...) ──►│                             │
   reflection    ──►│   Object nằm trên HEAP      │──► được tham chiếu (reference)
   clone()       ──►│   (vùng nhớ JVM quản lý)    │
   readObject()  ──►│                             │
   factory       ──►└─────────────────────────────┘
```

**Reflection** — framework nào cũng dùng:

```java
Class<User> clazz = User.class;
// Lấy constructor không tham số rồi tạo instance
User u = clazz.getDeclaredConstructor().newInstance();
```

> 💡 Hiểu reflection là chìa khóa để hiểu Spring/Hibernate "ảo thuật" thế nào. Khi bạn khai báo `@Entity` hay một `@Component`, framework dùng reflection để **tự tạo object** mà bạn không gọi `new`. Đó là lý do JPA Entity thường cần **constructor không tham số**.

> ⚠️ `clone()` và deserialization **không chạy constructor** → mọi logic validate bạn đặt trong constructor sẽ **bị bỏ qua**. Đây là lỗ hổng kinh điển khiến object rơi vào trạng thái không hợp lệ.

**Static factory method** — nên ưu tiên hơn constructor public khi cần kiểm soát:

```java
public final class User {
    private final String name;

    private User(String name) {        // constructor riêng tư
        this.name = name;
    }

    public static User of(String name) { // điểm vào duy nhất, dễ kiểm soát
        return new User(name);
    }
}
```

Ưu điểm: có **tên gọi rõ nghĩa** (`User.of`, `User.guest`), có thể **cache/tái dùng** instance (như `Integer.valueOf`), có thể trả về subtype. Nhược điểm: class chỉ có constructor private thì **không kế thừa được**.

### 2. Thứ tự khởi tạo — phần quan trọng nhất hôm nay

Khi một class được **nạp và khởi tạo lần đầu**, rồi khi tạo instance, JVM chạy theo một thứ tự **cố định**. Chia làm hai nhóm:

**Nhóm static (chạy ĐÚNG MỘT LẦN, khi class được khởi tạo lần đầu):**

1. `static` field được gán giá trị mặc định (0/null/false).
2. `static` field initializer + `static {}` block — chạy theo **thứ tự xuất hiện trong code**.

**Nhóm instance (chạy MỖI LẦN `new`):**

3. Instance field được gán mặc định.
4. Lời gọi `super(...)` — **constructor cha chạy xong trước** rồi mới tới con.
5. Instance field initializer + `{}` instance initializer block — chạy theo thứ tự trong code.
6. Thân constructor của chính class đó.

```
          ┌──────────────────── Khi class được LOAD/INITIALIZE (1 lần) ─────────────────────┐
          │  static field mặc định ──► static initializer & static block (theo thứ tự code) │
          └──────────────────────────────────────────────────────────────────────────────────┘
                                              │
                                              ▼
          ┌────────────────────────── Mỗi lần gọi  new  ──────────────────────────┐
          │  instance field mặc định ──► super(...) (cha xong trước)               │
          │       ──► instance initializer & instance block ──► thân constructor   │
          └────────────────────────────────────────────────────────────────────────┘
```

**Với kế thừa (cha `Animal`, con `Dog`)**, thứ tự thực thi đầy đủ khi gọi `new Dog()`:

```
1. static block của Animal   (cha)   ─┐  chỉ chạy MỘT LẦN
2. static block của Dog      (con)   ─┘  khi class load lần đầu
─────────────────────────────────────────  từ đây mỗi lần new đều chạy
3. instance init block của Animal    (cha)
4. constructor của Animal            (cha)
5. instance init block của Dog       (con)
6. constructor của Dog               (con)
```

> 💡 Mẹo nhớ: **static trước instance**, **cha trước con**, nhưng riêng *static block* thì **cả cha lẫn con đều xong trước mọi instance block**. Quy tắc vàng: "static (cha→con) → rồi tới chuỗi instance (cha→con)".

> ⚠️ Bẫy "this gọi method bị override": nếu constructor cha gọi một method mà con override, lúc đó **field của con CHƯA được khởi tạo** (bước 4 chạy trước bước 5–6). Method override sẽ thấy field con đang là `null`/`0`. Tuyệt đối tránh gọi method có thể bị override trong constructor.

### 3. Reachability — khi nào object "đủ điều kiện" bị GC thu hồi

Java **không có `delete`/`free`**. Garbage Collector (GC) tự dọn. Tiêu chí: **reachability** (khả năng với tới).

- GC bắt đầu từ tập **GC roots**: biến local trên stack, tham số method, biến `static`, thread đang chạy, JNI references...
- Đi theo các reference, đánh dấu mọi object **với tới được** (reachable) → còn sống.
- Object **không còn đường nào với tới** từ GC roots (unreachable) → **đủ điều kiện** bị thu hồi.

```
GC roots ──► A ──► B ──► C        (A, B, C: reachable → sống)

             X ──► Y              (không ai trỏ tới X → X, Y: unreachable
                                   → đủ điều kiện GC, kể cả khi X vẫn trỏ tới Y)
```

> ⚠️ **Phân biệt cực kỳ quan trọng:**
> - "**Đủ điều kiện GC** (eligible)": object đã unreachable, *có thể* bị thu hồi bất cứ lúc nào.
> - "**Đã bị thu hồi** (collected)": GC thực sự đã chạy và giải phóng bộ nhớ.
>
> Java **không bảo đảm thời điểm** thu hồi. Set một biến `= null` chỉ làm object *đủ điều kiện*, **không** nghĩa là nó bị xóa ngay.

> 💡 "Island of isolation": một cụm object trỏ vòng vào nhau (A↔B) nhưng **không cụm nào với tới được từ GC roots** → cả cụm vẫn bị thu hồi. GC của Java dùng tracing (đánh dấu từ roots), **không** dùng reference counting đơn thuần, nên vòng tham chiếu không gây leak (khác PHP cũ).

### 4. Bốn loại reference

Mặc định mọi reference bạn viết là **Strong**. Gói `java.lang.ref` cho 3 loại "yếu" hơn để hợp tác với GC.

| Loại | Class | GC thu hồi khi | Dùng để |
|---|---|---|---|
| **Strong** | (bình thường) | Khi object **unreachable** | Mọi reference thông thường |
| **Soft** | `SoftReference<T>` | Chỉ khi JVM **sắp hết bộ nhớ** | Cache "giữ được thì giữ" |
| **Weak** | `WeakReference<T>` | Ở **lần GC kế tiếp** nếu không còn strong ref | Metadata, key trong `WeakHashMap` |
| **Phantom** | `PhantomReference<T>` | Sau khi đã finalize, dùng để **biết object sắp bị dọn** | Dọn dẹp tài nguyên nâng cao (`Cleaner` dùng nó) |

```
Mức "bám" giảm dần:   Strong  >  Soft  >  Weak  >  Phantom
GC "nỡ" thu hồi:      khó nhất         ...        dễ nhất
```

- **Strong:** `User u = new User();` — còn `u` trỏ tới thì object bất khả xâm phạm với GC.
- **Soft:** GC chỉ thu hồi khi đói bộ nhớ → cache ảnh, cache kết quả tính toán nặng.
- **Weak:** GC thu ngay lần quét sau nếu không còn strong ref → tránh giữ object sống quá lâu.
- **Phantom:** không lấy lại được object qua `get()` (luôn trả `null`); chỉ để được **báo** khi object đã được dọn, thay cho `finalize`.

**`WeakHashMap`** — key được giữ bằng *weak reference*:

```java
Map<Key, Value> cache = new WeakHashMap<>();
```

Khi không còn nơi nào (ngoài map) giữ strong ref tới `Key`, entry đó **tự bị xóa** khỏi map ở lần GC sau. Rất hợp cho cache gắn theo vòng đời của key, tránh memory leak.

> ⚠️ `WeakHashMap` là weak ở **key**, không phải value. Nếu value lại trỏ ngược về key, key vẫn "sống" → leak. Cẩn thận với liên kết chéo.

### 5. `finalize()` đã chết — dùng `try-with-resources` và `Cleaner`

`Object.finalize()` từng được dùng để "dọn dẹp trước khi bị GC". Nay nó **deprecated for removal** (Java 9 đánh dấu, Java 18 chính thức bỏ rơi). Vì sao tệ:

- **Không bảo đảm chạy**: nếu object không bao giờ bị GC (hoặc JVM tắt trước), `finalize` không chạy.
- **Không bảo đảm thời điểm**: có thể rất lâu sau khi object unreachable.
- **Hiệu năng tệ**: object có `finalize` cần **2 chu kỳ GC** mới dọn xong (sống lại để chạy finalize, rồi mới thu).
- **Nguy hiểm**: ngoại lệ trong `finalize` bị nuốt; có thể "hồi sinh" object (resurrection).

**Giải pháp đúng:**

1. **`try-with-resources` + `AutoCloseable`** — dọn tài nguyên **xác định, ngay lập tức** khi rời khối:

```java
try (var conn = dataSource.getConnection()) {   // implements AutoCloseable
    // dùng conn...
} // conn.close() tự gọi ở đây, kể cả khi có exception
```

2. **`java.lang.ref.Cleaner`** — mạng lưới an toàn (safety net) cho trường hợp người dùng *quên* gọi `close()`:

```java
private static final Cleaner CLEANER = Cleaner.create();
```

> 💡 Tư duy chuẩn: **tài nguyên (file, socket, connection) phải đóng tường minh bằng `try-with-resources`**. `Cleaner` chỉ là lưới dự phòng cuối cùng, không phải cơ chế dọn dẹp chính.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| Tạo object | `new User()` | `new User()`, reflection, clone, deserialize, factory |
| Hàm khởi tạo | `__construct()` | constructor `User()` |
| Hàm hủy | `__destruct()` (gọi khi object hết reference) | **Không có** destructor xác định |
| Cơ chế dọn dẹp | Reference counting + cycle collector | Tracing Garbage Collector |
| Thời điểm hủy | **Xác định**: hết ref là gọi ngay `__destruct` | **Phi xác định**: GC quyết định, không biết khi nào |
| Vòng tham chiếu | Cần cycle collector mới dọn | GC tracing dọn tự nhiên |
| Vòng đời tổng thể | Object chết khi **request kết thúc** | Object sống tới khi **unreachable** rồi đợi GC |
| Đóng tài nguyên | `try/finally`, đôi khi dựa `__destruct` | `try-with-resources` (`AutoCloseable`) |

**Khác biệt tư duy quan trọng nhất:**

- PHP: nhờ **reference counting**, object bị hủy gần như **ngay khi** biến cuối cùng trỏ tới nó biến mất, và **chậm nhất** là khi request kết thúc — toàn bộ bộ nhớ request được giải phóng sạch. Bạn quen với việc "hết request là sạch".
- Java: app **chạy lâu dài**, object sống trong Heap qua **nhiều request**. GC dọn theo lịch của riêng nó. Vì vậy bạn phải chủ động **không giữ reference thừa** (ví dụ static collection cứ phình to) — đó là nguyên nhân leak phổ biến nhất ở Java.

> 🧩 Hệ quả: đừng mong `__destruct` ở Java. Muốn đóng tài nguyên *đúng lúc* thì **không dựa vào GC** — hãy dùng `try-with-resources`. GC chỉ lo bộ nhớ, **không** lo file/socket/connection.

---

## 💻 Thực hành code

### Bài 1 — In ra thứ tự khởi tạo (có kế thừa)

```java
// File: InitOrderDemo.java  (Java 21)
class Animal {
    // (A) static block của CHA
    static { System.out.println("1. static block - Animal (cha)"); }

    // (B) instance init block của CHA
    { System.out.println("3. instance init block - Animal (cha)"); }

    Animal() { System.out.println("4. constructor - Animal (cha)"); }
}

class Dog ___ Animal { // Điền từ khóa kế thừa lớp
    // static block của CON
    static { System.out.println("2. static block - Dog (con)"); }

    // instance init block của CON
    { System.out.println("5. instance init block - Dog (con)"); }

    Dog() {
        // super() được chèn ngầm ở đây -> chạy chuỗi của cha trước
        System.out.println("6. constructor - Dog (con)");
    }
}

public class InitOrderDemo {
    public static void main(String[] args) {
        System.out.println("=== new Dog() lần 1 ===");
        new Dog();
        System.out.println("=== new Dog() lần 2 ===");
        new Dog();   // chú ý: static block KHÔNG chạy lại
    }
}
```

Chạy:

```bash
javac InitOrderDemo.java
java InitOrderDemo
```

Kết quả mong đợi:

```
=== new Dog() lần 1 ===
1. static block - Animal (cha)
2. static block - Dog (con)
3. instance init block - Animal (cha)
4. constructor - Animal (cha)
5. instance init block - Dog (con)
6. constructor - Dog (con)
=== new Dog() lần 2 ===
3. instance init block - Animal (cha)
4. constructor - Animal (cha)
5. instance init block - Dog (con)
6. constructor - Dog (con)
```

> 💡 Quan sát: ở lần `new` thứ hai, **static block không chạy lại** (đã khởi tạo class rồi). Còn chuỗi instance (3→6) chạy lại đầy đủ mỗi lần `new`.

### Bài 2 — WeakReference và GC thu hồi

```java
// File: WeakRefDemo.java  (Java 21)
import java.lang.ref.WeakReference;

public class WeakRefDemo {
    public static void main(String[] args) {
        // strong reference: object chắc chắn sống
        Object strong = new Object();
        // weak reference bọc cùng object đó
        WeakReference<Object> weak = new WeakReference<>(strong);

        System.out.println("Trước khi bỏ strong, weak.get() = " + weak.get()); // != null

        // Bỏ strong reference -> chỉ còn weak trỏ tới object
        strong = null;

        // Gợi ý JVM chạy GC. LƯU Ý: System.gc() chỉ là "gợi ý",
        // không bảo đảm GC chạy ngay. Đây là cách minh họa, không dùng ở production.
        System.gc();

        // Cho GC chút thời gian (demo). Không phải cách làm chuẩn.
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        Object afterGc = weak.get();
        if (afterGc == null) {
            System.out.println("Sau GC, weak.get() = null  -> object ĐÃ bị thu hồi");
        } else {
            System.out.println("Sau GC, object vẫn còn (GC chưa chạy hết)");
        }
    }
}
```

Chạy:

```bash
javac WeakRefDemo.java
java WeakRefDemo
```

> ⚠️ `System.gc()` **chỉ là gợi ý** cho JVM, không ép GC chạy. Đừng bao giờ gọi nó trong code production để "ép dọn rác" — nó có thể gây stop-the-world và phản tác dụng. Ở đây ta chỉ dùng để **minh họa học thuật**.

> 💡 Thử đổi `WeakReference` thành `SoftReference`: object sẽ **không** bị thu hồi sau `System.gc()` ở ví dụ này, vì JVM còn dư bộ nhớ (soft ref chỉ buông khi sắp OOM). Đây là minh chứng sống cho khác biệt Weak vs Soft.

### Bài 3 — try-with-resources thay cho finalize

```java
// File: ResourceDemo.java  (Java 21)
public class ResourceDemo {
    // Một tài nguyên giả lập, implements AutoCloseable
    static class Connection ___ AutoCloseable { // Điền từ khóa triển khai interface
        Connection() { System.out.println("Mở connection"); }
        void query()  { System.out.println("Đang query..."); }
        @Override public void close() { System.out.println("Đóng connection (tự động)"); }
    }

    public static void main(String[] args) {
        // close() được gọi tự động khi rời khối try, kể cả khi có exception
        try (Connection conn = new Connection()) {
            conn.query();
        }
        System.out.println("Đã ra khỏi khối try — connection chắc chắn đã đóng");
    }
}
```

Kết quả: `Mở connection` → `Đang query...` → `Đóng connection (tự động)` → dòng cuối. Việc đóng **xác định, đúng lúc**, không phụ thuộc GC.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng `obj = null` là xóa object ngay.** Sai — chỉ làm nó *đủ điều kiện* GC; thời điểm thu hồi do JVM quyết.
- **Gọi method bị override trong constructor cha.** Lúc đó field của con chưa khởi tạo → đọc ra `null`/`0`. Lỗi cực khó debug.
- **Dựa vào `finalize()` để đóng tài nguyên.** Nó deprecated, không bảo đảm chạy, chậm. Dùng `try-with-resources`.
- **Static collection phình mãi không xóa.** `static List`/`static Map` giữ strong reference suốt đời app → object không bao giờ unreachable → **memory leak**. Đây là leak phổ biến nhất ở Java.
- **Quên JPA Entity cần constructor không tham số.** Hibernate dùng reflection tạo entity rồi mới set field — thiếu constructor rỗng sẽ lỗi runtime.
- **Tưởng `clone()`/deserialize chạy constructor.** Không. Mọi validate trong constructor bị bỏ qua → object có thể không hợp lệ.
- **Nhầm `WeakHashMap` là weak ở value.** Nó weak ở **key**. Nếu value trỏ ngược về key thì leak.
- **Lạm dụng `System.gc()` trong production.** Gây stop-the-world, làm chậm app, không bảo đảm gì.

---

## 🚀 Liên hệ Spring Boot / Production

- **Spring tạo bean bằng reflection.** Mọi `@Component`, `@Service`, `@Repository` được container *new* qua reflection rồi inject dependency. Đó là lý do hiểu vòng đời object + reflection giúp bạn debug lỗi tạo bean.
- **Vòng đời bean ≠ vòng đời object Java.** Spring có scope `singleton` (mặc định — sống suốt app, giống biến static), `prototype`, `request`, `session`. Bean singleton **không bị GC** chừng nào context còn sống. Hook `@PostConstruct` chạy *sau* khi tạo + inject xong, `@PreDestroy` chạy khi context đóng — đây mới là "destructor" có ý nghĩa ở Spring.
- **Connection pool & `try-with-resources`.** `JdbcTemplate`/HikariCP trả connection về pool khi bạn `close()` (chứ không đóng thật). Luôn để framework hoặc `try-with-resources` lo việc trả connection — quên `close` là cạn pool.
- **Memory leak điển hình trong production:** cache không giới hạn, listener không gỡ, `ThreadLocal` không clear trong thread pool. Tất cả đều là "giữ strong reference quá lâu". Dùng `WeakHashMap`/`SoftReference` hoặc cache có TTL (Caffeine) để chữa.
- **`Cleaner`** được dùng trong JDK hiện đại (ví dụ dọn buffer off-heap) làm lưới an toàn — nhưng tài nguyên chính vẫn đóng tường minh.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Day 02 ta đã phác thảo class `Auction` và `Bid`. Hôm nay siết chặt **vòng đời** chúng: validate ngay trong constructor và làm `Bid` **bất biến (immutable)**.

**Nhiệm vụ Day 03:** viết constructor có validation, cân nhắc immutability.

```java
// File: Bid.java  (Java 21)
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Một lượt đấu giá là BẤT BIẾN (immutable):
 * - tất cả field đều final, không có setter
 * - đã đặt giá rồi thì không sửa được -> an toàn, dễ suy luận, thread-safe
 */
public final class Bid {
    private final String bidder;       // người đặt giá
    private final BigDecimal amount;   // số tiền
    private final Instant placedAt;    // thời điểm đặt

    public Bid(String bidder, BigDecimal amount) {
        // Validate NGAY trong constructor -> object sinh ra luôn ở trạng thái hợp lệ
        this.bidder = Objects.requireNonNull(bidder, "Người đặt giá không được null");
        Objects.requireNonNull(amount, "Số tiền không được null");
        if (amount.signum() <= 0) {                // amount > 0
            throw new IllegalArgumentException("Số tiền bid phải > 0");
        }
        this.amount = amount;
        this.placedAt = Instant.now();
    }

    public String getBidder()    { return bidder; }
    public BigDecimal getAmount() { return amount; }
    public Instant getPlacedAt()  { return placedAt; }
    // KHÔNG có setter — đó là điểm mấu chốt của immutability
}
```

```java
// File: Auction.java  (Java 21)
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Auction {
    private final String title;             // tên phiên đấu giá (bất biến)
    private final BigDecimal startingPrice; // giá khởi điểm (bất biến)
    private final List<Bid> bids = new ArrayList<>();

    public Auction(String title, BigDecimal startingPrice) {
        this.title = Objects.requireNonNull(title, "Tên phiên không được null");
        Objects.requireNonNull(startingPrice, "Giá khởi điểm không được null");
        if (startingPrice.signum() <= 0) {       // giá khởi điểm > 0
            throw new IllegalArgumentException("Giá khởi điểm phải > 0");
        }
        this.startingPrice = startingPrice;
    }

    /** Thêm một lượt đấu giá hợp lệ: phải cao hơn giá khởi điểm và giá cao nhất hiện tại. */
    public void placeBid(Bid bid) {
        Objects.requireNonNull(bid, "Bid không được null");
        BigDecimal current = highestAmount();
        if (bid.getAmount().compareTo(current) <= 0) {
            throw new IllegalArgumentException(
                "Giá bid phải cao hơn giá hiện tại: " + current);
        }
        bids.add(bid);
    }

    private BigDecimal highestAmount() {
        return bids.isEmpty()
            ? startingPrice
            : bids.get(bids.size() - 1).getAmount();
    }

    public String getTitle()             { return title; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public List<Bid> getBids()           { return List.copyOf(bids); } // trả bản copy bất biến
}
```

> 💡 `List.copyOf(bids)` trả về một list **bất biến** — người gọi không thể chọc vào danh sách bid nội bộ. Đây là cách "đóng kín" trạng thái object (encapsulation) đi đôi với immutability.

> ⚠️ Vì `Bid` không có constructor rỗng và không setter, nếu sau này map sang JSON/DB qua Jackson/JPA bạn cần cấu hình thêm (constructor binding, `@JsonCreator`...). Đánh đổi: bất biến an toàn hơn nhưng tốn chút cấu hình với framework — sẽ gặp ở các ngày sau.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Có những cách nào tạo một object trong Java?**
> **Đáp:** Năm cách chính: (1) toán tử `new`; (2) reflection (`getDeclaredConstructor().newInstance()`); (3) `clone()`; (4) deserialization (`readObject`); (5) static factory method (gọi `new` bên trong). Lưu ý `clone()` và deserialization **không gọi constructor**.

**Q2: Thứ tự khởi tạo khi gọi `new` là gì (không kế thừa)?**
> **Đáp:** static field/static block chạy một lần khi class khởi tạo; rồi mỗi lần `new`: instance field về mặc định → `super()` → instance field initializer + instance init block (theo thứ tự code) → thân constructor.

**Q3: Khi nào một object bị Garbage Collector thu hồi?**
> **Đáp:** Khi nó **unreachable** — không còn đường nào với tới từ GC roots (biến local, static, thread...). Lúc đó nó *đủ điều kiện* thu hồi, nhưng JVM **không bảo đảm thời điểm** thực sự dọn.

**Q4: `__destruct` của PHP tương ứng gì ở Java?**
> **Đáp:** Java **không có destructor xác định**. PHP dùng reference counting nên hủy gần như ngay khi hết ref/khi hết request; Java để GC quyết định, phi xác định. Muốn dọn tài nguyên đúng lúc thì dùng `try-with-resources` (`AutoCloseable`), không dựa vào GC.

**Q5: Immutability mang lại lợi ích gì?**
> **Đáp:** Object bất biến (final fields, không setter) thì **thread-safe tự nhiên**, an toàn khi chia sẻ, dễ suy luận, dùng làm key trong map/set an toàn, và luôn ở trạng thái hợp lệ sau khi tạo (validate một lần trong constructor).

### Mức Senior

**Q6: Trình bày đầy đủ thứ tự khởi tạo khi có kế thừa.**
> **Đáp:** `new Dog()` (Dog extends Animal): (1) static block Animal → (2) static block Dog — chạy một lần khi class load; rồi (3) instance init block Animal → (4) constructor Animal → (5) instance init block Dog → (6) constructor Dog. Quy tắc: static trước instance, cha trước con; toàn bộ static (cha→con) xong rồi mới tới chuỗi instance (cha→con).

**Q7: Phân biệt 4 loại reference và khi nào GC thu hồi từng loại.**
> **Đáp:** *Strong* — thu khi object unreachable (mặc định). *Soft* — chỉ thu khi JVM sắp hết bộ nhớ, hợp làm cache. *Weak* — thu ở lần GC kế tiếp nếu không còn strong ref, dùng cho `WeakHashMap`/metadata. *Phantom* — `get()` luôn null, chỉ để được thông báo object đã được dọn, phục vụ cleanup nâng cao (cơ sở của `Cleaner`). Mức bám giảm dần: Strong > Soft > Weak > Phantom.

**Q8: Vì sao không nên dùng `finalize()`? Thay bằng gì?**
> **Đáp:** `finalize` không bảo đảm chạy, không bảo đảm thời điểm, làm chậm GC (cần 2 chu kỳ), nuốt exception, có thể hồi sinh object — nên đã deprecated for removal. Thay bằng `try-with-resources` + `AutoCloseable` (đóng tài nguyên xác định) và `java.lang.ref.Cleaner` làm lưới an toàn dự phòng.

**Q9: Vì sao vòng tham chiếu (A↔B) không gây leak ở Java mà có thể gây ở PHP cũ?**
> **Đáp:** GC của Java dùng **tracing** — đánh dấu từ GC roots. Một cụm object trỏ vòng vào nhau nhưng không cụm nào với tới được từ roots ("island of isolation") vẫn bị thu hồi. PHP truyền thống dùng **reference counting**, vòng tham chiếu giữ count > 0 nên cần cycle collector riêng mới dọn được.

**Q10: Một static `Map` cache đang gây tăng bộ nhớ theo thời gian. Phân tích và hướng xử lý?**
> **Đáp:** `static` là GC root → mọi entry trong map là reachable → không bao giờ bị thu hồi dù không còn ai dùng → leak. Hướng xử lý: giới hạn kích thước (LRU/Caffeine có maxSize, TTL), hoặc dùng `WeakHashMap`/`SoftReference` để GC tự dọn khi cần, và đảm bảo gỡ entry khi key hết vòng đời.

---

## ✅ Checklist hoàn thành

- [ ] Kể được 5 cách tạo object và biết cách nào không gọi constructor
- [ ] Vẽ lại được thứ tự khởi tạo có kế thừa (static cha→con → instance cha→con)
- [ ] Phân biệt "đủ điều kiện GC" và "đã bị thu hồi"
- [ ] Phân biệt 4 loại reference và biết `WeakHashMap` dùng làm gì
- [ ] Giải thích được vì sao không dùng `finalize`, thay bằng `try-with-resources`/`Cleaner`
- [ ] Chạy được Bài 1, 2, 3 và đối chiếu output thực tế với dự đoán
- [ ] Hoàn thành Mini Project: constructor có validation + `Bid` immutable
- [ ] Điền các chỗ trống `___` trong code thực hành ở trên
- [ ] Trả lời được 10 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Initializing Fields" & "Object Creation"
- *Effective Java* (Joshua Bloch) — Item 1 (static factory), Item 8 (tránh finalizer/cleaner), Item 17 (immutability)
- Baeldung — "WeakReference vs SoftReference vs PhantomReference", "Guide to WeakHashMap"
- Javadoc gói `java.lang.ref` — `Reference`, `Cleaner`
- Oracle docs — "Try-with-resources Statement" & interface `AutoCloseable`
- OpenJDK — tài liệu GC (G1/ZGC) khi cần đào sâu phần reachability
