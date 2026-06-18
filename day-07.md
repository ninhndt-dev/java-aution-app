# Day 07 - Interface vs Abstract

> **Giai đoạn:** OOP & Core Java
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt dứt khoát **khi nào dùng `interface`** và **khi nào dùng `abstract class`** — đây là câu hỏi phỏng vấn ra hằng tuần.
- Hiểu `interface` từ Java 8 trở đi không còn "rỗng": có **`default` method**, **`static` method**, và (Java 9) **`private` method**.
- Nắm **đa kế thừa kiểu** (multiple inheritance of type) qua interface, và cách Java xử lý **diamond problem** (xung đột default method).
- Hiểu **functional interface** (SAM) — nền tảng của **lambda** và **method reference** trong Java hiện đại.
- Biết **marker interface** (`Serializable`, `Cloneable`) là gì và vì sao annotation dần thay thế nó.
- Liên hệ với **`trait`**, **`interface`**, **`abstract class`** của PHP/Laravel để nắm nhanh nhờ cái đã biết.

---

## 🧠 Lý thuyết cốt lõi

### 1. Hai cách trừu tượng hóa — và chúng trả lời hai câu hỏi khác nhau

Java cho bạn hai công cụ để định nghĩa "cái khung chung" rồi để lớp con điền vào chi tiết:

```
                  Câu hỏi nó trả lời              Quan hệ
  interface  ──►  "Đối tượng này LÀM ĐƯỢC gì?"   can-do   (capability / hợp đồng)
  abstract   ──►  "Đối tượng này LÀ cái gì?"      is-a     (cùng dòng dõi, dùng chung state)
```

- **`interface`** = một **hợp đồng hành vi**. Nó nói "ai ký hợp đồng này thì phải biết làm những việc sau". Nó tập trung vào **năng lực** (capability): `Comparable` (so sánh được), `Runnable` (chạy được trên thread), `AutoCloseable` (đóng được). Một class có thể ký **nhiều hợp đồng** cùng lúc.
- **`abstract class`** = một **lớp cơ sở chưa hoàn chỉnh**. Nó chia sẻ **state (field) + code chung** cho cả một họ lớp con có quan hệ "is-a" chặt chẽ. Ví dụ `AbstractAuction` là cha của `EnglishAuction`, `DutchAuction` — chúng *là* phiên đấu giá.

> 💡 Quy tắc đầu ngón tay: thấy chữ **"-able"** (Comparable, Iterable, Serializable) thì gần như chắc chắn đó là **interface** — vì nó mô tả một **năng lực**, không phải một danh tính.

### 2. Bảng so sánh chi tiết: `interface` vs `abstract class`

| Tiêu chí | `interface` | `abstract class` |
|---|---|---|
| **State (field thường)** | ❌ Không. Chỉ có hằng `public static final` | ✅ Có. Field instance, có thể `private`, `protected`, mutable |
| **Constructor** | ❌ Không có | ✅ Có (để lớp con gọi `super(...)` khởi tạo state) |
| **Đa kế thừa** | ✅ Một class `implements` **nhiều** interface | ❌ Chỉ `extends` **một** class |
| **Loại method được phép** | `abstract`, `default`, `static`, `private` (Java 9+) | `abstract` + method có thân hàm bình thường |
| **Access modifier của method** | Mặc định `public` (không có `protected`/`private` cho abstract method) | Đủ loại: `public`, `protected`, `private`, package-private |
| **Quan hệ ngữ nghĩa** | "can-do" / capability / hợp đồng | "is-a" / cùng dòng dõi |
| **Mục đích chính** | Định nghĩa **hành vi mà nhiều class không liên quan** đều cài được | Chia sẻ **code + state chung** cho một họ lớp gần nhau |
| **Tốc độ tiến hóa** | `default` method cho phép thêm hành vi mà **không phá** class đã implement | Thêm method có sẵn thân hàm cũng không phá lớp con |

> ⚠️ Sai lầm kinh điển: "interface không có field". Sai — interface **có** field, nhưng mọi field trong interface **ngầm định là `public static final`** (tức là **hằng**), không phải state của instance. Bạn không thể đặt biến mutable trong interface.

### 3. Interface không còn rỗng: `default`, `static`, `private` method

**Trước Java 8**, interface chỉ chứa được hai thứ:
- Các **abstract method** (không thân hàm) — ngầm `public abstract`.
- Các **hằng** `public static final`.

Từ Java 8 và 9, interface "mạnh" hơn hẳn:

```java
public interface BidValidator {

    // 1) abstract method — bắt buộc lớp implement phải viết (ngầm public abstract)
    boolean isValid(Bid bid);

    // 2) default method (Java 8): có sẵn thân hàm.
    //    Lớp implement KHÔNG cần override -> dùng để TIẾN HÓA interface
    //    mà không phá vỡ code cũ đang dùng nó.
    default String reject(Bid bid) {
        return "Bid của " + bid.bidder() + " bị từ chối";
    }

    // 3) static method (Java 8): tiện ích gắn liền interface, gọi qua tên interface.
    //    Không liên quan tới instance, không bị override.
    static BidValidator allowAll() {
        return bid -> true; // trả về một validator chấp nhận tất cả
    }

    // 4) private method (Java 9): tái dùng code NỘI BỘ giữa các default method,
    //    không lộ ra ngoài hợp đồng.
    private static String tag() {
        return "[BidValidator] ";
    }

    default String reject2(Bid bid) {
        return tag() + "loại bid " + bid.bidder(); // dùng lại private method
    }
}
```

**Vì sao `default` method ra đời?** Để giải bài toán **tiến hóa API**. Trước Java 8, nếu Java muốn thêm method `forEach` vào `Collection`, thì **mọi class trên thế giới** đang `implements Collection` sẽ vỡ vì thiếu method đó. Với `default method`, Java thêm `forEach` kèm thân hàm mặc định → mọi class cũ vẫn biên dịch và chạy bình thường, ai cần thì override.

> 💡 Đây chính là cách `java.util.Collection` có `stream()`, `removeIf()`; `List` có `sort()`; `Iterable` có `forEach()` — tất cả đều là `default method` được "tiêm" vào mà không phá code cũ.

### 4. Đa kế thừa kiểu & Diamond Problem

Java **không** cho đa kế thừa **class** (chỉ `extends` một class) để tránh mơ hồ về **state**. Nhưng Java **cho** đa kế thừa **kiểu** qua interface: một class `implements` nhiều interface cùng lúc.

```java
class FlashSaleAuction implements BidValidator, PricingStrategy, AutoCloseable {
    // class này "đa năng": vừa kiểm tra bid, vừa tính giá, vừa đóng được
}
```

Vấn đề nảy sinh khi **hai interface có default method TRÙNG signature** — JVM không biết chọn cái nào. Đây là **diamond problem**:

```
        Greetable           Notifiable
        default greet()     default greet()
              \                 /
               \               /
                AuctionService  ──► greet() của ai???
```

```java
interface Greetable {
    default String greet() { return "Xin chào từ Greetable"; }
}
interface Notifiable {
    default String greet() { return "Xin chào từ Notifiable"; }
}

// Java BẮT BUỘC bạn override để gỡ mơ hồ, nếu không -> lỗi biên dịch
class AuctionService implements Greetable, Notifiable {
    @Override
    public String greet() {
        // Gọi rõ default method của interface cụ thể bằng cú pháp Interface.super.method()
        return Greetable.super.greet() + " | " + Notifiable.super.greet();
    }
}
```

> ⚠️ Nếu hai interface có default method cùng signature mà bạn **không override**, code sẽ **không biên dịch được**. Cú pháp gỡ rối là `TênInterface.super.tênMethod()` — khác hẳn `super.method()` (gọi lên class cha).

### 5. Functional Interface — trái tim của lambda

**Functional interface** là interface có **đúng MỘT abstract method** (gọi là SAM — Single Abstract Method). Vì chỉ có một method cần cài, Java cho phép viết nó gọn bằng **lambda** hoặc **method reference**.

```java
@FunctionalInterface                 // annotation tùy chọn nhưng nên dùng:
interface BidValidator {             // compiler sẽ BÁO LỖI nếu interface có > 1 abstract method
    boolean isValid(Bid bid);        // 1 abstract method duy nhất (SAM)

    // default & static method KHÔNG tính vào "1 abstract method"
    default BidValidator and(BidValidator other) {
        return bid -> this.isValid(bid) && other.isValid(bid);
    }
}
```

Cài đặt bằng lambda thay vì viết cả một class:

```java
// Lambda: thân lambda chính là phần cài đặt cho isValid(...)
BidValidator higherThanZero = bid -> bid.amount() > 0;

// Method reference: trỏ tới một method có chữ ký khớp
BidValidator notNull = Objects::nonNull;
```

Một số functional interface chuẩn bạn sẽ gặp liên tục:

| Interface (java.util.function / java.lang) | Method SAM | Ý nghĩa |
|---|---|---|
| `Runnable` | `void run()` | Một khối việc chạy được (thường trên thread) |
| `Comparator<T>` | `int compare(T a, T b)` | Quy tắc so sánh hai phần tử |
| `Predicate<T>` | `boolean test(T t)` | Một điều kiện đúng/sai trên `t` |
| `Function<T,R>` | `R apply(T t)` | Biến đổi `T` thành `R` |
| `Supplier<T>` | `T get()` | Cung cấp một giá trị (lazy) |
| `Consumer<T>` | `void accept(T t)` | Tiêu thụ `t`, không trả về |

> 💡 `@FunctionalInterface` **không bắt buộc** để dùng lambda, nhưng hãy luôn gắn nó: nó biến "ý định thiết kế" thành ràng buộc do compiler kiểm tra — ai lỡ thêm method abstract thứ hai sẽ bị báo lỗi ngay.

### 6. Marker Interface — interface rỗng để "đánh dấu"

**Marker interface** là interface **không có method nào**. Nó không yêu cầu hành vi gì, chỉ **gắn nhãn** một class để JVM hoặc thư viện biết "class này được phép làm X".

```java
public interface Serializable { }   // RỖNG — chỉ để đánh dấu
public interface Cloneable    { }   // RỖNG — báo hiệu cho Object.clone()
```

- `implements Serializable` → báo cơ chế serialization rằng class này được phép ghi/đọc thành byte stream. Không đánh dấu mà gọi `ObjectOutputStream.writeObject` sẽ ném `NotSerializableException`.
- `implements Cloneable` → báo `Object.clone()` rằng được phép clone; thiếu nó, `clone()` ném `CloneNotSupportedException`.

Code dùng marker thường kiểm tra bằng `instanceof`:

```java
if (obj instanceof Serializable) { /* được phép serialize */ }
```

> 💡 **Ngày nay annotation thường thay marker interface** (ví dụ `@Entity`, `@Deprecated`): annotation mang được **tham số**, gắn được lên method/field, và đọc bằng reflection. Marker interface vẫn tồn tại trong API cũ và có ưu điểm là được **kiểm tra kiểu lúc biên dịch** (`instanceof`, kiểu tham số), nhưng với code mới, phần lớn trường hợp annotation là lựa chọn hiện đại hơn.

---

## 🔁 Đối chiếu với Laravel/PHP

Bạn đã quen PHP 8 nên hãy neo vào đó. PHP cũng có `interface` và `abstract class`, và còn có một thứ Java không có tên gọi trực tiếp: **`trait`**.

| Khái niệm Java | Tương đương PHP / Laravel | Ghi chú khác biệt |
|---|---|---|
| `interface` (chỉ method + hằng) | `interface` của PHP | Rất giống nhau. Nhưng PHP **không có** `default method` trong interface |
| `default method` trong interface | **`trait`** của PHP | Cả hai đều "tiêm code dùng chung" vào nhiều class. Trait giải bài toán "đa kế thừa code" |
| `abstract class` | `abstract class` của PHP | Gần như y hệt: có state, constructor, method abstract |
| `implements` nhiều interface | `implements A, B` + `use TraitX` | PHP tách rõ: interface cho hợp đồng, trait cho code |
| `functional interface` + lambda | **Closure / arrow fn** (`fn($x) => ...`) | PHP **không** có khái niệm functional interface kiểu SAM. Lambda PHP là object `Closure`, không gắn vào một interface |
| `Comparator`, `Predicate`... | Truyền `callable`/`Closure` vào `usort`, `array_filter` | PHP truyền callable trực tiếp, không cần một interface đặt tên |

**Ánh xạ tư duy quan trọng nhất:**

- Trong PHP, khi muốn nhiều class **dùng chung một đoạn code** mà không có quan hệ cha-con, bạn dùng **`trait`** (`use SoftDeletes;` trong Eloquent là ví dụ kinh điển). Trong Java, đoạn code dùng chung đó thường nằm trong **`default method`** của interface, hoặc trong một abstract class.
- PHP `trait` cũng có **xung đột tên** khi `use` hai trait có method trùng — và PHP buộc bạn giải quyết bằng cú pháp `insteadof` / `as`. Điều này **rất giống** diamond problem của default method trong Java (chỉ khác cú pháp).

```php
// PHP: trait giống "default method" — chèn code dùng chung
trait Loggable {
    public function log(string $msg): void { /* ... */ }
}
class AuctionService {
    use Loggable;   // ~ implements interface có default method log()
}
```

> 🧩 Hệ quả tư duy: đừng cố tìm "functional interface" trong PHP — nó không có. PHP truyền hành vi bằng `Closure`/`callable` một cách "không tên". Java lại **luôn gắn lambda vào một interface kiểu cụ thể** (lambda Java luôn *là* một instance của một functional interface nào đó). Đây là khác biệt cốt lõi khi bạn đọc code Java functional.

---

## 💻 Thực hành code

### Bài 1 — Interface có `default` method (lớp không cần override)

```java
// File: NotificationDemo.java
interface Notifier {
    void send(String message);                 // abstract: bắt buộc cài

    // default method: có sẵn thân hàm -> lớp implement KHÔNG cần override
    default void sendUrgent(String message) {
        send("[KHẨN] " + message);             // tái dùng abstract method
    }
}

class EmailNotifier implements Notifier {
    @Override
    public void send(String message) {
        System.out.println("Email: " + message);
    }
    // KHÔNG override sendUrgent -> dùng bản default của interface
}

public class NotificationDemo {
    public static void main(String[] args) {
        Notifier n = new EmailNotifier();
        n.send("Bạn vừa thắng phiên đấu giá");
        n.sendUrgent("Phiên sắp đóng trong 30 giây");  // gọi default method
    }
}
```

### Bài 2 — Functional interface tự định nghĩa + lambda + method reference

```java
// File: ValidatorDemo.java
import java.util.Objects;

record Bid(String bidder, long amount) {}      // dùng record cho gọn (Day trước)

@FunctionalInterface
interface BidValidator {
    boolean isValid(Bid bid);                  // 1 abstract method duy nhất (SAM)

    // default method: ghép hai validator lại — trả về validator mới
    default BidValidator and(BidValidator other) {
        return bid -> this.isValid(bid) && other.isValid(bid);
    }

    // static method: tiện ích gắn với interface
    static BidValidator min(long floor) {
        return bid -> bid.amount() >= floor;
    }
}

public class ValidatorDemo {

    // method tĩnh dùng cho method reference
    static boolean hasBidder(Bid bid) {
        return bid.bidder() != null && !bid.bidder().isBlank();
    }

    public static void main(String[] args) {
        // (a) Lambda: thân lambda là cài đặt cho isValid(...)
        BidValidator positive = bid -> bid.amount() > 0;

        // (b) Method reference: trỏ tới method có chữ ký khớp boolean(Bid)
        BidValidator named = ValidatorDemo::hasBidder;

        // (c) Ghép validator bằng default method .and(...) + static method .min(...)
        BidValidator rule = positive.and(named).and(BidValidator.min(100));

        System.out.println(rule.isValid(new Bid("an", 150)));   // true
        System.out.println(rule.isValid(new Bid("an", 50)));    // false (dưới floor 100)
        System.out.println(rule.isValid(new Bid("", 150)));     // false (thiếu bidder)
    }
}
```

### Bài 3 — Abstract class có state + constructor + abstract method

```java
// File: AuctionHierarchyDemo.java

// abstract class: có STATE (field) + CONSTRUCTOR + code chung + 1 method abstract
abstract class AbstractAuction {
    protected final String itemName;     // state dùng chung cho mọi loại đấu giá
    protected long currentPrice;

    // constructor: abstract class CÓ constructor để lớp con gọi super(...)
    protected AbstractAuction(String itemName, long startPrice) {
        this.itemName = itemName;
        this.currentPrice = startPrice;
    }

    // code chung — lớp con dùng lại, không viết lại
    public long currentPrice() { return currentPrice; }

    // method abstract: mỗi loại đấu giá tự định nghĩa luật ra giá tiếp theo
    public abstract long nextPrice();
}

// Đấu giá kiểu Anh: giá tăng dần
class EnglishAuction extends AbstractAuction {
    private final long step;
    EnglishAuction(String itemName, long startPrice, long step) {
        super(itemName, startPrice);     // gọi constructor lớp cha để set state
        this.step = step;
    }
    @Override
    public long nextPrice() { return currentPrice + step; }
}

// Đấu giá kiểu Hà Lan: giá giảm dần
class DutchAuction extends AbstractAuction {
    private final long step;
    DutchAuction(String itemName, long startPrice, long step) {
        super(itemName, startPrice);
        this.step = step;
    }
    @Override
    public long nextPrice() { return Math.max(0, currentPrice - step); }
}

public class AuctionHierarchyDemo {
    public static void main(String[] args) {
        AbstractAuction en = new EnglishAuction("Tranh cổ", 1000, 100);
        AbstractAuction du = new DutchAuction("Đồng hồ", 1000, 100);
        System.out.println("English next: " + en.nextPrice()); // 1100
        System.out.println("Dutch next:   " + du.nextPrice()); // 900
    }
}
```

> ✅ **Bài tập tự giải thích:** Trong Bài 3, vì sao `AbstractAuction` *phải* là abstract class chứ không thể là interface? (Gợi ý: nó có **state** `currentPrice` và **constructor** khởi tạo state — interface không có hai thứ này.)

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng "interface không có field".** Sai: interface có field nhưng chúng ngầm `public static final` (hằng), không phải state mutable. Đừng định nghĩa "biến trạng thái" trong interface.
- **Lạm dụng `default method` để nhét logic nghiệp vụ phức tạp.** `default` sinh ra để **tiến hóa API** và cung cấp hành vi mặc định nhỏ gọn. Đổ cả business logic có state vào đó là dấu hiệu nên dùng abstract class.
- **Quên override khi gặp diamond.** Hai interface có default method trùng chữ ký mà không override → **lỗi biên dịch**. Phải override và dùng `Interface.super.method()`.
- **Nhầm `super.method()` với `Interface.super.method()`.** `super` trỏ class cha; `Interface.super` trỏ default method của một interface cụ thể.
- **Thêm method abstract thứ hai vào functional interface.** Khi có `@FunctionalInterface`, compiler báo lỗi ngay. Nếu không có annotation, lambda cũ sẽ vỡ một cách khó hiểu. → Luôn gắn `@FunctionalInterface`.
- **Dùng abstract class chỉ để gom vài method tĩnh.** Nếu không có state, không có quan hệ "is-a", hãy cân nhắc interface với `static method`, hoặc một `final class` chỉ chứa tiện ích.
- **`implements Cloneable`/`Serializable` rồi tưởng "xong".** Marker interface chỉ **cho phép**; bạn vẫn phải cài đúng (`clone()` đúng cách, `serialVersionUID`, xử lý field `transient`...).

---

## 🚀 Liên hệ Spring Boot / Production

- **Spring sống bằng interface.** Bạn khai báo `interface UserRepository extends JpaRepository<User, Long>` mà **không viết một dòng cài đặt nào** — Spring Data sinh proxy lúc chạy. Hợp đồng (interface) tách rời cài đặt là triết lý cốt lõi.
- **`@Service` interface + `@Service` impl.** Mẫu phổ biến: `interface AuctionService` (hợp đồng) và `class AuctionServiceImpl implements AuctionService` (cài đặt). Nhờ vậy bạn **mock interface** dễ dàng khi viết test, và đổi cài đặt mà không sửa nơi gọi.
- **Strategy pattern qua interface.** Tiêm nhiều cài đặt của cùng một interface và chọn lúc chạy — đúng những gì Mini Project hôm nay làm với `PricingStrategy`. Spring có thể inject `List<PricingStrategy>` hoặc `Map<String, PricingStrategy>` cho bạn.
- **Functional interface + lambda khắp nơi.** `RestTemplate`/`WebClient` nhận lambda; `@FunctionalInterface` như `RowMapper` (JdbcTemplate), `Comparator` để sort kết quả; callback của `TransactionTemplate`... Hiểu functional interface giúp đọc code Spring trôi chảy.
- **`default method` trong interface Spring.** Nhiều interface của Spring (ví dụ `WebMvcConfigurer`) toàn `default method` rỗng — bạn chỉ override đúng method mình cần, không phải cài hết. Đây chính là sức mạnh của `default method` ở quy mô framework.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp các ngày trước: ta đã có `User`, `Item`, `Auction`, `Bid` (Day 06). Hôm nay ta tách **luật kiểm tra bid** và **chiến lược tính giá** thành các **interface** để áp dụng **Strategy pattern**.

**Nhiệm vụ Day 07:**

1. Định nghĩa functional interface `BidValidator` (kiểm tra một lượt bid hợp lệ) — có thêm `default method` để ghép luật.
2. Định nghĩa interface `PricingStrategy` (tính phí/giá theo từng kiểu đấu giá) — Strategy pattern.
3. Cung cấp vài cài đặt: `MinIncrementValidator` (class), một validator bằng lambda, `EnglishAuctionPricing`, `FlatFeePricing`.
4. Viết `AuctionEngine` nhận một `BidValidator` và một `PricingStrategy` qua constructor (dependency injection thủ công).

```java
// File: AuctionStrategyDemo.java
import java.util.List;

record User(String name) {}
record Bid(User bidder, long amount) {}

// ===== Interface 1: hợp đồng KIỂM TRA bid (functional interface) =====
@FunctionalInterface
interface BidValidator {
    boolean isValid(Bid bid, long currentPrice);

    // default method: ghép nhiều luật thành một (giống Predicate.and)
    default BidValidator and(BidValidator other) {
        return (bid, price) -> this.isValid(bid, price) && other.isValid(bid, price);
    }

    // static method: luật "phải cao hơn giá hiện tại"
    static BidValidator higherThanCurrent() {
        return (bid, price) -> bid.amount() > price;
    }
}

// Cài đặt bằng CLASS: phải đúng bước giá tối thiểu
class MinIncrementValidator implements BidValidator {
    private final long minIncrement;
    MinIncrementValidator(long minIncrement) { this.minIncrement = minIncrement; }
    @Override
    public boolean isValid(Bid bid, long currentPrice) {
        return bid.amount() - currentPrice >= minIncrement;
    }
}

// ===== Interface 2: hợp đồng TÍNH GIÁ/PHÍ (Strategy pattern) =====
interface PricingStrategy {
    long finalPrice(long hammerPrice);   // giá cuối người thắng phải trả

    // default method: mô tả ngắn gọn, lớp con có thể override
    default String describe() { return "Pricing: " + getClass().getSimpleName(); }
}

// Đấu giá kiểu Anh: cộng phí hoa hồng 10%
class EnglishAuctionPricing implements PricingStrategy {
    @Override
    public long finalPrice(long hammerPrice) {
        return Math.round(hammerPrice * 1.10);   // +10% buyer premium
    }
}

// Phí cố định: cộng một khoản phí phẳng
class FlatFeePricing implements PricingStrategy {
    private final long fee;
    FlatFeePricing(long fee) { this.fee = fee; }
    @Override
    public long finalPrice(long hammerPrice) { return hammerPrice + fee; }
}

// ===== Engine: nhận strategy qua constructor (DI thủ công) =====
class AuctionEngine {
    private final BidValidator validator;
    private final PricingStrategy pricing;
    private long currentPrice;

    AuctionEngine(long startPrice, BidValidator validator, PricingStrategy pricing) {
        this.currentPrice = startPrice;
        this.validator = validator;
        this.pricing = pricing;
    }

    boolean placeBid(Bid bid) {
        if (!validator.isValid(bid, currentPrice)) {
            System.out.println("✗ Từ chối bid " + bid.amount() + " của " + bid.bidder().name());
            return false;
        }
        currentPrice = bid.amount();
        System.out.println("✓ Chấp nhận bid " + bid.amount() + " của " + bid.bidder().name());
        return true;
    }

    long settle() {
        long total = pricing.finalPrice(currentPrice);
        System.out.println(pricing.describe() + " -> giá cuối: " + total);
        return total;
    }
}

public class AuctionStrategyDemo {
    public static void main(String[] args) {
        // Ghép luật: phải cao hơn giá hiện tại VÀ đúng bước giá >= 100 (lambda + class + default .and)
        BidValidator rule = BidValidator.higherThanCurrent()
                .and(new MinIncrementValidator(100))
                .and((bid, price) -> bid.amount() <= 1_000_000); // lambda: trần giá

        AuctionEngine engine = new AuctionEngine(1000, rule, new EnglishAuctionPricing());

        engine.placeBid(new Bid(new User("an"), 1050));   // ✗ chỉ tăng 50 < 100
        engine.placeBid(new Bid(new User("binh"), 1200)); // ✓
        engine.placeBid(new Bid(new User("an"), 1250));   // ✗ chỉ tăng 50
        engine.placeBid(new Bid(new User("cuong"), 1400));// ✓

        engine.settle();   // English pricing -> 1400 * 1.10 = 1540

        // Đổi chiến lược giá mà KHÔNG sửa engine logic -> sức mạnh của Strategy
        List<PricingStrategy> options = List.of(new EnglishAuctionPricing(), new FlatFeePricing(50));
        options.forEach(p -> System.out.println(p.describe() + " cho 1400 = " + p.finalPrice(1400)));
    }
}
```

> 💡 Để ý: `BidValidator` là **functional interface** nên ta trộn thoải mái lambda, method reference, và class — tất cả đều là `BidValidator`. Còn `PricingStrategy` *không* phải functional interface (có default method `describe` + nhiều cài đặt) nhưng vẫn là một hợp đồng Strategy hoàn hảo. Đây là minh họa sống cho "khi nào interface, khi nào functional interface".

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Khi nào dùng `interface`, khi nào dùng `abstract class`?**
> **Đáp:** Dùng **interface** khi muốn định nghĩa một **năng lực/hợp đồng** ("can-do") mà nhiều class không liên quan đều cài được, và khi cần đa kế thừa kiểu (một class implement nhiều interface). Dùng **abstract class** khi có quan hệ **"is-a"** chặt chẽ và cần **chia sẻ state (field) + constructor + code chung** cho một họ lớp con. Quy tắc nhanh: cần state/constructor → abstract class; cần đa kế thừa kiểu / chỉ mô tả hành vi → interface.

**Q2: `default method` là gì và nó giải quyết vấn đề gì?**
> **Đáp:** Là method trong interface **có sẵn thân hàm** (Java 8). Lớp implement không bắt buộc override. Nó sinh ra để giải bài toán **tiến hóa API**: thêm method mới vào interface mà **không phá vỡ** các class đã implement trước đó (ví dụ thêm `forEach` vào `Iterable`, `stream` vào `Collection`).

**Q3: Functional interface là gì? Cho ví dụ.**
> **Đáp:** Là interface có **đúng một abstract method** (SAM — Single Abstract Method), thường gắn `@FunctionalInterface`. Vì chỉ có một method cần cài, ta viết gọn bằng **lambda** hoặc **method reference**. Ví dụ chuẩn: `Runnable` (`run`), `Comparator` (`compare`), `Predicate` (`test`), `Function` (`apply`). Lưu ý `default`/`static` method **không** tính vào số abstract method.

**Q4: Interface có thể có field không? Loại nào?**
> **Đáp:** Có, nhưng mọi field trong interface ngầm là `public static final` — tức là **hằng số**, không phải state của instance. Không thể đặt biến mutable trong interface; còn `abstract class` thì có state thật.

### Mức Senior

**Q5: Diamond problem trong Java là gì và Java xử lý ra sao?**
> **Đáp:** Khi một class implement hai interface có **default method trùng chữ ký**, compiler không biết chọn bản nào → **lỗi biên dịch**. Java buộc lập trình viên **override** method đó để gỡ mơ hồ, và có thể gọi rõ bản của một interface cụ thể bằng cú pháp `TênInterface.super.tênMethod()`. Java cho đa kế thừa **kiểu/hành vi** qua interface nhưng kiểm soát chặt xung đột này.

**Q6: Vì sao Java không cho đa kế thừa class nhưng lại cho implement nhiều interface?**
> **Đáp:** Đa kế thừa **class** gây mơ hồ về **state** và việc gọi constructor (kế thừa hai bản copy field, gọi super nào trước...). Interface trước Java 8 **không có state, không có constructor**, nên implement nhiều cái không gây mơ hồ về dữ liệu. Từ Java 8, `default method` mở ra khả năng xung đột *hành vi*, nhưng Java giải quyết bằng quy tắc override bắt buộc + `Interface.super.method()` — vẫn không có xung đột state, nên an toàn hơn đa kế thừa class.

**Q7: Marker interface là gì? Ngày nay nên dùng nó hay annotation?**
> **Đáp:** Marker interface là interface **rỗng** (không method) dùng để **gắn nhãn** một class, báo cho JVM/thư viện một khả năng — ví dụ `Serializable`, `Cloneable`. Code kiểm tra bằng `instanceof`. Ngày nay **annotation** thường được ưu tiên cho code mới vì mang được tham số, gắn lên method/field, đọc qua reflection. Marker interface vẫn có lợi thế: được kiểm tra **kiểu lúc biên dịch** (dùng làm bound generic, `instanceof`). Tóm lại: API hệ thống cũ và khi cần ràng buộc kiểu thì marker interface còn chỗ đứng, còn lại annotation hiện đại hơn.

**Q8: `static` và `private` method trong interface dùng để làm gì?**
> **Đáp:** `static` method (Java 8) là **tiện ích gắn với interface**, gọi qua tên interface (`Validator.allowAll()`), không liên quan instance, không bị override — tránh phải tạo class `XxxUtils` riêng. `private` method (Java 9) dùng để **tái sử dụng code nội bộ** giữa các `default`/`static` method mà không lộ ra hợp đồng công khai, giúp default method gọn và không lặp lại logic.

**Q9: `abstract class` có constructor để làm gì khi không thể `new` trực tiếp nó?**
> **Đáp:** Đúng là không `new AbstractAuction()` được, nhưng constructor của abstract class **được lớp con gọi qua `super(...)`** để **khởi tạo state dùng chung** (ví dụ set `itemName`, `currentPrice`). Đây là lý do cốt lõi để chọn abstract class thay interface khi cần state có quy trình khởi tạo. Interface không có constructor nên không làm được việc này.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được khi nào dùng interface, khi nào dùng abstract class (bằng lời của mình)
- [ ] Viết được interface có `default`, `static`, `private` method và hiểu mục đích từng loại
- [ ] Tự dựng được tình huống diamond problem và gỡ bằng `Interface.super.method()`
- [ ] Viết được một functional interface + cài bằng lambda và method reference
- [ ] Giải thích được marker interface và so sánh với annotation
- [ ] Đối chiếu được `trait` PHP ~ `default method` Java
- [ ] Hoàn thành Mini Project: `BidValidator` + `PricingStrategy` (Strategy pattern) chạy được
- [ ] Trả lời được 9 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Interfaces" và "Abstract Methods and Classes"
- Oracle Tutorials — "Default Methods" (lý do ra đời, cách giải xung đột)
- Baeldung — "Java Interface vs Abstract Class", "Functional Interfaces in Java", "Marker Interfaces in Java"
- *Effective Java* (Joshua Bloch) — Item 20 "Prefer interfaces to abstract classes", Item 21 "Design interfaces for posterity"
- JLS (Java Language Specification) — mục về interface member và default method resolution (tham khảo khi cần đào sâu)
- PHP Manual — "Traits" và "Interfaces" (để đối chiếu với Java)
