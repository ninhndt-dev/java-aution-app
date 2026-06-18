# Day 06 - OOP Deep Dive

> **Giai đoạn:** OOP & Core Java
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm **4 trụ cột OOP** ở mức sâu: Đóng gói, Kế thừa, Đa hình, Trừu tượng — không phải định nghĩa thuộc lòng mà là *tại sao* và *khi nào* dùng.
- Phân biệt **static binding (overload)** quyết định lúc biên dịch vs **dynamic dispatch (override)** quyết định lúc chạy — hiểu cơ chế "virtual method / vtable".
- Hiểu **`this`, `super`, `final`**, **covariant return type** và `@Override` dùng để làm gì.
- Hiểu nguyên tắc **Composition over Inheritance** và vì sao kế thừa sâu là "con dao hai lưỡi" (fragile base class).
- Nắm **hợp đồng `equals()` / `hashCode()` / `toString()`** — vì sao override `equals` thì *bắt buộc* override `hashCode`, và hệ quả trong `HashMap`/`HashSet`.
- Liên hệ với OOP của PHP (traits, visibility, `==` vs `===`, `__toString`) để học nhanh nhờ cái đã biết.

---

## 🧠 Lý thuyết cốt lõi

### 1. Bốn trụ cột OOP — hiểu cho đến gốc

OOP không phải "viết class cho có". Bốn trụ cột là bốn công cụ giải quyết vấn đề khác nhau:

| Trụ cột | Bản chất | Giải quyết vấn đề gì |
|---|---|---|
| **Encapsulation** (Đóng gói) | Giấu state bên trong, chỉ lộ hành vi qua method | Bảo vệ **invariant** (ràng buộc luôn đúng) của object |
| **Inheritance** (Kế thừa) | Lớp con tái dùng + mở rộng lớp cha (`extends`) | Tái dùng code, mô hình quan hệ **"is-a"** |
| **Polymorphism** (Đa hình) | Một interface, nhiều cài đặt | Code gọi 1 cách, chạy nhiều hành vi khác nhau |
| **Abstraction** (Trừu tượng) | Lộ "**cái gì**" (what), giấu "**thế nào**" (how) | Tách hợp đồng khỏi chi tiết cài đặt |

**Encapsulation — đóng gói để bảo vệ invariant:** Field để `private`, truy cập qua getter/setter. Quan trọng không phải là "viết getter cho đẹp", mà là **setter/method là nơi duy nhất bạn ép luật**. Ví dụ giá đấu giá không được âm:

```java
public class Bid {
    private long amount;            // private: bên ngoài KHÔNG sờ trực tiếp

    public void setAmount(long amount) {
        if (amount <= 0) {          // invariant được ép tại 1 chỗ duy nhất
            throw new IllegalArgumentException("Giá đặt phải dương");
        }
        this.amount = amount;
    }
    public long getAmount() { return amount; }
}
```

> 💡 Nếu field là `public`, bất kỳ ai cũng gán `amount = -999` được, invariant vỡ. Đóng gói = "đặt một cái cổng duy nhất để mọi thay đổi state phải đi qua".

**Inheritance — quan hệ "is-a":** `class SavingsAccount extends Account` nghĩa là "tài khoản tiết kiệm **là một** tài khoản". Lớp con thừa hưởng field/method `public`, `protected` của cha và có thể thêm/override. Chỉ dùng kế thừa khi quan hệ "is-a" *thật sự* đúng (xem mục Composition bên dưới).

**Polymorphism — một lời gọi, nhiều hành vi:** Cùng câu lệnh `bid.effectivePrice()` nhưng nếu `bid` thực ra là `ProxyBid` thì chạy logic proxy, là `RegularBid` thì chạy logic thường. Người gọi *không cần biết* loại cụ thể.

**Abstraction — lộ what, giấu how:** `interface PaymentGateway { void charge(long amount); }` nói "tôi tính được tiền" mà không lộ là Stripe hay VNPay. Code phụ thuộc vào *hợp đồng*, không vào *cài đặt*.

### 2. Static binding vs Dynamic dispatch — trái tim của đa hình

Đây là phần lập trình viên hay học hời hợt. Có **hai thời điểm** Java quyết định "gọi method nào":

```
                       Lúc BIÊN DỊCH                 Lúc CHẠY
                    (compile time)                (runtime)
                          │                            │
  Overload  ─────────────┘                            │
  (cùng tên, khác tham số)                            │
  → STATIC binding: chọn theo                         │
    KIỂU KHAI BÁO + signature                         │
                                                      │
  Override  ──────────────────────────────────────────┘
  (cùng signature, lớp con ghi đè lớp cha)
  → DYNAMIC dispatch: chọn theo
    KIỂU THỰC của object (virtual method)
```

- **Static binding (early binding)** áp dụng cho **overload**, `static`, `private`, `final` method. Trình biên dịch nhìn **kiểu khai báo của biến** + danh sách tham số để chốt method ngay lúc compile.
- **Dynamic dispatch (late binding)** áp dụng cho **method instance bị override**. Lúc compile chỉ biết "sẽ gọi `effectivePrice()`"; *method nào thực sự chạy* được quyết lúc runtime dựa trên **kiểu thực của object** mà biến đang trỏ tới.

**Cơ chế vtable (khái niệm):** Mỗi class có một bảng method ảo (virtual method table). Object giữ con trỏ tới vtable của **lớp thực** của nó. Khi gọi `bid.effectivePrice()`, JVM tra vtable của object → nhảy tới cài đặt đúng. Vì vậy method instance trong Java mặc định là **virtual** (khác C++ phải khai báo `virtual`, khác PHP nơi mọi method non-static cũng late-bound).

```java
Bid b = new ProxyBid(...);   // kiểu khai báo: Bid | kiểu thực: ProxyBid
b.effectivePrice();          // DYNAMIC → chạy ProxyBid.effectivePrice() (kiểu thực)
```

> ⚠️ Field thì **KHÔNG** đa hình — field bind theo *kiểu khai báo* lúc compile (static). Chỉ **method instance** mới dynamic. Đừng bao giờ trông cậy vào "override field".

### 3. Overloading vs Overriding — phân biệt cho chuẩn

| Tiêu chí | **Overloading** (nạp chồng) | **Overriding** (ghi đè) |
|---|---|---|
| Quan hệ | Cùng class (hoặc kế thừa) | Bắt buộc cha–con (kế thừa/implement) |
| Tên method | Giống nhau | Giống nhau |
| Danh sách tham số | **Phải khác** (số lượng/kiểu/thứ tự) | **Phải y hệt** (signature trùng) |
| Kiểu trả về | Được phép khác | Phải giống **hoặc kiểu con** (covariant) |
| Phạm vi truy cập (access) | Tự do | **Không được thu hẹp** (vd cha `public` → con không thể `protected`) |
| Checked exception | Tự do | **Không ném rộng hơn** cha cho phép |
| Binding | **Static** (compile time) | **Dynamic** (runtime) |
| `@Override` | Không áp dụng | Nên dùng |

**`@Override` — vì sao nên luôn dùng:** Nó *không* đổi hành vi, nhưng yêu cầu compiler kiểm tra "method này có thật sự override method của cha không". Nếu bạn gõ sai signature (vd `equals(MyType o)` thay vì `equals(Object o)`), không có `@Override` thì compiler im lặng coi đó là *overload mới* → bug ngầm. Có `@Override` thì compiler báo lỗi ngay.

```java
class Animal { String sound() { return "..."; } }

class Dog extends Animal {
    @Override String sound() { return "Gâu"; }   // override hợp lệ
    String sound(int times) { return "Gâu".repeat(times); } // OVERLOAD (khác tham số)
}
```

**Covariant return type:** Khi override, kiểu trả về được phép *hẹp hơn* (kiểu con) so với cha. Rất hữu ích cho pattern builder/clone:

```java
class Animal { Animal reproduce() { return new Animal(); } }
class Dog extends Animal {
    @Override Dog reproduce() { return new Dog(); }   // trả về Dog (con của Animal) — hợp lệ
}
```

### 4. `this`, `super`, `final`

**`this`** — tham chiếu tới chính object hiện tại:
- Phân biệt field với tham số trùng tên: `this.amount = amount;`
- Gọi constructor khác *trong cùng class*: `this(arg1, arg2);` (phải là dòng đầu constructor).

**`super`** — tham chiếu tới phần lớp cha:
- Gọi method cha bị override: `super.sound();`
- Gọi constructor cha: `super(arg);` (phải là dòng đầu constructor con). Nếu không viết, Java tự chèn `super()` không tham số.

```java
class ProxyBid extends Bid {
    private final long maxLimit;
    ProxyBid(User u, long amount, long maxLimit) {
        super(u, amount);            // gọi constructor Bid (cha) trước
        this.maxLimit = maxLimit;
    }
}
```

**`final`** — "chốt lại, không cho đổi", có 3 ngữ cảnh:

| Dùng với | Ý nghĩa | Ví dụ thực tế |
|---|---|---|
| `final class` | Không kế thừa được | `String`, `Integer`, `LocalDate` (đảm bảo bất biến/an toàn) |
| `final method` | Không override được | Khoá hành vi cốt lõi của khung |
| `final field` | Gán đúng **một lần** | Field bất biến, hằng `static final` |

> 💡 `final field` + `private` + không có setter = nền tảng của **immutable object** (object bất biến) — cực quan trọng cho thread-safety. `record` (Day sau) chính là cú pháp gọn cho ý này.

### 5. Composition over Inheritance — ưu tiên "has-a" hơn "is-a"

Kế thừa rất hấp dẫn vì tái dùng nhanh, nhưng **kế thừa sâu gây cứng nhắc**:
- **Fragile base class:** Sửa lớp cha có thể vô tình làm vỡ tất cả lớp con. Lớp con bị buộc chặt vào *chi tiết cài đặt* của cha, không chỉ hợp đồng.
- Java **không đa kế thừa class** — kẹt một cây kế thừa, khó ghép nhiều hành vi.
- Kế thừa lộ toàn bộ API `protected/public` của cha ra con, dễ rò rỉ trừu tượng.

**Composition (thành phần):** thay vì "**là một**", dùng "**có một**". Object chứa object khác như một field và uỷ quyền (delegate):

```java
// ❌ Lạm dụng kế thừa: Auction "là một" ArrayList? Không hợp lý.
class Auction extends ArrayList<Bid> { ... }

// ✅ Composition: Auction "CÓ một" danh sách Bid và "CÓ một" Item
class Auction {
    private final Item item;            // has-a
    private final List<Bid> bids = new ArrayList<>();   // has-a
    public void place(Bid bid) { bids.add(bid); }       // uỷ quyền có kiểm soát
}
```

> 💡 Quy tắc thực dụng: **"is-a" thật sự + cùng dòng họ ổn định** → kế thừa. Còn lại (tái dùng code, ghép hành vi, cần linh hoạt) → composition. Spring khắp nơi dùng composition + interface (DI inject phụ thuộc vào nhau).

### 6. Access modifier — phạm vi truy cập

| Modifier | Cùng class | Cùng package | Lớp con (khác package) | Mọi nơi |
|---|---|---|---|---|
| `private` | ✅ | ❌ | ❌ | ❌ |
| *(mặc định)* `package-private` | ✅ | ✅ | ❌ | ❌ |
| `protected` | ✅ | ✅ | ✅ | ❌ |
| `public` | ✅ | ✅ | ✅ | ✅ |

> ⚠️ "Mặc định" (không ghi modifier) **không phải** `public` như nhiều người PHP tưởng — nó là **package-private** (chỉ trong cùng package). `protected` rộng hơn `private` nhưng hẹp hơn `public`, đặc biệt cho phép lớp con ở package khác truy cập.

### 7. Hợp đồng `equals()` / `hashCode()` / `toString()`

Mọi class kế thừa từ `Object`, có sẵn 3 method này — nhưng bản mặc định thường *không đúng ý bạn*.

**`equals(Object)` mặc định** so sánh **địa chỉ tham chiếu** (giống `==`). Muốn so sánh *giá trị* (vd hai `Bid` cùng id là "bằng nhau") phải override. 4 quy tắc hợp đồng `equals`:

1. **Reflexive (phản xạ):** `x.equals(x)` luôn `true`.
2. **Symmetric (đối xứng):** `x.equals(y)` ⇔ `y.equals(x)`.
3. **Transitive (bắc cầu):** `x.equals(y)` và `y.equals(z)` ⇒ `x.equals(z)`.
4. **Consistent (nhất quán):** gọi nhiều lần vẫn ra kết quả như nhau (nếu object không đổi). Và `x.equals(null)` luôn `false`.

**Quy tắc vàng:** *Override `equals` thì BẮT BUỘC override `hashCode`*, vì hợp đồng: **hai object `equals` nhau PHẢI có `hashCode` bằng nhau.**

```
Vì sao bắt buộc? Cấu trúc băm (HashMap/HashSet) hoạt động 2 bước:
   1) Tính hashCode để tìm "ngăn" (bucket)
   2) Trong ngăn đó, dùng equals để so khớp chính xác

Nếu hai object equals nhưng hashCode khác → chúng rơi vào 2 bucket khác nhau
→ HashSet chứa cả hai (tưởng trùng mà vẫn lọt!), HashMap.get() tra nhầm ngăn → trả null.
```

**`toString()`** mặc định in `ClassName@hexHash` (vô dụng khi debug/log). Override để in trạng thái có ý nghĩa.

```java
import java.util.Objects;

public final class User {
    private final long id;
    private final String name;

    public User(long id, String name) { this.id = id; this.name = name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;                 // phản xạ + tối ưu
        if (!(o instanceof User other)) return false; // pattern matching (Java 16+)
        return id == other.id;                       // định danh bằng id
    }

    @Override
    public int hashCode() { return Objects.hash(id); }   // nhất quán với equals (cùng field)

    @Override
    public String toString() { return "User{id=" + id + ", name='" + name + "'}"; }
}
```

> 💡 Dùng `Objects.equals(a, b)` để so sánh null-safe và `Objects.hash(...)` để gộp nhiều field — idiomatic, tránh tự viết tay dễ sai. **Field nào đưa vào `equals` thì đưa đúng field đó vào `hashCode`.**

---

## 🔁 Đối chiếu với Laravel/PHP

PHP cũng OOP đầy đủ, nhưng có vài khác biệt cốt tử:

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| Khai báo class | `class Bid { ... }` | `class Bid { ... }` |
| Kế thừa | `class A extends B` (đơn kế thừa) | `class A extends B` (đơn kế thừa) |
| Đa kế thừa hành vi | Không có → dùng **traits** (`use SomeTrait;`) | Không có → dùng `interface` + `default method` |
| Interface | `interface I {}` + `implements I` | `interface I {}` + `implements I` |
| Abstract class | `abstract class`, `abstract function` | `abstract class`, `abstract method` |
| Visibility | `public` / `protected` / `private` | `public` / `protected` / `private` / *package-private* |
| Mặc định khi không ghi | `public` | **package-private** (khác!) |
| Hằng | `const X = 1;` | `static final int X = 1;` |
| So sánh "bằng giá trị" | `$a == $b` (cùng class + cùng property) | `a.equals(b)` (do bạn định nghĩa) |
| So sánh "cùng instance" | `$a === $b` (cùng object) | `a == b` (so tham chiếu) |
| In object | `__toString()` | `toString()` |
| Băm trong tập | `SplObjectStorage`, mảng key | `hashCode()` + `equals()` cho `HashMap/HashSet` |

**Khác biệt tư duy quan trọng nhất:**
- **Traits của PHP** là cơ chế "dán code" (mixin) lúc biên dịch — ghép nhiều trait vào một class để tái dùng hành vi, lấp chỗ trống của "không đa kế thừa". Java *không* có trait; cách tương đương gần nhất là **`interface` có `default method`** (Java 8+), nhưng trait PHP còn cho mang theo *state/property* còn interface Java thì không.
- **`==` vs `===` trong PHP:** `==` của PHP với hai object là true khi *cùng class và cùng giá trị property* — gần với `equals()` của Java. Còn `===` là *cùng đúng một instance* — gần với `==` của Java (so tham chiếu). **Đảo ngược thói quen:** trong Java, `==` cho object = so tham chiếu, *muốn so giá trị phải dùng `.equals()`*.

> 🧩 Hệ quả thực tế hay dính bug: dân PHP quen viết `if (str1 == str2)` để so chuỗi. Trong Java, `==` so *tham chiếu* — phải dùng `str1.equals(str2)`. Tương tự với mọi object: `==` gần như luôn là sai khi bạn thật sự muốn so *nội dung*.

---

## 💻 Thực hành code

### Bước 1 — Đa hình với các loại Bid khác nhau

Một interface/lớp cha `Bid`, nhiều cài đặt; vòng lặp gọi *cùng* method nhưng chạy *khác* hành vi.

```java
// File: BidDemo.java  (Java 21)
import java.util.List;

// Lớp cha trừu tượng: lộ "cái gì" (effectivePrice), giấu "thế nào"
abstract class Bid {
    protected final String bidder;
    protected final long base;            // giá đặt cơ bản
    protected Bid(String bidder, long base) { this.bidder = bidder; this.base = base; }

    // method ABSTRACT: mỗi loại Bid tự tính giá hiệu lực theo cách riêng
    public abstract long effectivePrice();

    public String bidder() { return bidder; }
}

// Đặt giá thường: giá hiệu lực = giá đặt
class RegularBid extends Bid {
    RegularBid(String bidder, long base) { super(bidder, base); }
    @Override public long effectivePrice() { return base; }   // DYNAMIC dispatch
}

// Proxy/Auto bid: hệ thống tự nâng tới trần, giá hiệu lực = min(base, trần)
class ProxyBid extends Bid {
    private final long maxLimit;
    ProxyBid(String bidder, long base, long maxLimit) {
        super(bidder, base);
        this.maxLimit = maxLimit;
    }
    @Override public long effectivePrice() { return Math.min(base, maxLimit); }
}

public class BidDemo {
    public static void main(String[] args) {
        // Khai báo kiểu cha, đối tượng thực khác nhau → ĐA HÌNH
        List<Bid> bids = List.of(
            new RegularBid("an", 100_000),
            new ProxyBid("binh", 500_000, 300_000),   // bị trần kéo về 300k
            new RegularBid("chi", 250_000)
        );

        // Cùng 1 lời gọi effectivePrice(), nhưng mỗi object chạy logic riêng (vtable)
        for (Bid b : bids) {
            System.out.printf("%s -> giá hiệu lực: %d%n", b.bidder(), b.effectivePrice());
        }
    }
}
```

Chạy:

```bash
javac BidDemo.java && java BidDemo
# an   -> giá hiệu lực: 100000
# binh -> giá hiệu lực: 300000   (proxy bị trần kéo xuống)
# chi  -> giá hiệu lực: 250000
```

### Bước 2 — Override `toString` / `equals` / `hashCode` và chứng minh trong `HashSet`

```java
// File: BidIdentityDemo.java  (Java 21)
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

final class Bid {
    private final long id;          // định danh nghiệp vụ
    private final String bidder;
    private final long amount;

    Bid(long id, String bidder, long amount) {
        this.id = id; this.bidder = bidder; this.amount = amount;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bid b)) return false;
        return id == b.id && amount == b.amount && Objects.equals(bidder, b.bidder);
    }

    // PHẢI dùng đúng các field đã dùng trong equals
    @Override public int hashCode() { return Objects.hash(id, bidder, amount); }

    @Override public String toString() {
        return "Bid{id=%d, bidder='%s', amount=%d}".formatted(id, bidder, amount);
    }
}

public class BidIdentityDemo {
    public static void main(String[] args) {
        Bid a = new Bid(1, "an", 100_000);
        Bid b = new Bid(1, "an", 100_000);   // CÙNG giá trị, KHÁC instance

        System.out.println(a == b);          // false  (khác tham chiếu)
        System.out.println(a.equals(b));     // true   (bằng theo giá trị)
        System.out.println(a.hashCode() == b.hashCode()); // true (bắt buộc!)
        System.out.println(a);               // Bid{id=1, bidder='an', amount=100000}

        Set<Bid> set = new HashSet<>();
        set.add(a);
        set.add(b);                           // bị coi là trùng → KHÔNG thêm
        System.out.println("Số phần tử trong set: " + set.size()); // 1
    }
}
```

> ✅ **Bài tập tự kiểm chứng:** Xoá method `hashCode()` đi, chạy lại — `set.size()` sẽ thành `2` (hai object "bằng nhau" nhưng rơi vào hai bucket khác nhau). Đây là minh chứng sống cho "override `equals` phải override `hashCode`".

### Bước 3 — Composition: Auction "has-a" Item (không kế thừa)

```java
// File: CompositionDemo.java  (Java 21)
import java.util.ArrayList;
import java.util.List;

class Item {                         // thành phần độc lập
    private final String name;
    Item(String name) { this.name = name; }
    public String name() { return name; }
}

class Auction {
    private final Item item;                                  // has-a Item
    private final List<String> bidders = new ArrayList<>();   // has-a danh sách

    Auction(Item item) { this.item = item; }                 // composition lúc khởi tạo

    public void addBidder(String name) { bidders.add(name); } // uỷ quyền có kiểm soát
    public String summary() {
        return "Đấu giá '%s' với %d người tham gia".formatted(item.name(), bidders.size());
    }
}

public class CompositionDemo {
    public static void main(String[] args) {
        Auction a = new Auction(new Item("iPhone 15"));
        a.addBidder("an");
        a.addBidder("binh");
        System.out.println(a.summary()); // Đấu giá 'iPhone 15' với 2 người tham gia
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **So object bằng `==` thay vì `.equals()`.** Thói quen từ PHP (`==`). Trong Java `==` so *tham chiếu*; với chuỗi/đối tượng phải dùng `.equals()`.
- **Override `equals` mà quên `hashCode`.** `HashSet`/`HashMap` hỏng âm thầm: phần tử "trùng" vẫn lọt, `map.get(key)` trả `null`. Luôn override *cặp đôi*.
- **`hashCode` và `equals` không cùng tập field.** Vi phạm hợp đồng → bug băm. Field nào trong `equals` thì *đúng* field đó trong `hashCode`.
- **Sai signature khi override (thiếu `@Override`).** Viết `equals(Bid o)` thay vì `equals(Object o)` → vô tình tạo overload mới, `equals` mặc định vẫn được gọi. Luôn dán `@Override`.
- **Tưởng field cũng đa hình.** Chỉ method instance mới dynamic dispatch. Field bind theo kiểu khai báo (static).
- **Lạm dụng kế thừa.** Kế thừa chỉ để "tái dùng vài dòng code" → cây kế thừa sâu, fragile base class. Khi nghi ngờ, dùng composition.
- **Để field `public`.** Phá vỡ đóng gói, invariant không được bảo vệ. Mặc định `private` + lộ qua method.
- **Quên `super(...)` đúng tham số.** Nếu lớp cha không có constructor mặc định, lớp con *bắt buộc* gọi `super(...)` đúng — nếu không sẽ lỗi biên dịch.

---

## 🚀 Liên hệ Spring Boot / Production

- **DI dựa trên đa hình + trừu tượng:** Bạn inject `PaymentGateway` (interface), Spring tiêm cài đặt cụ thể (`StripeGateway`). Code nghiệp vụ phụ thuộc *hợp đồng*, đổi cài đặt không sửa code gọi — đây chính là Abstraction + Polymorphism ở quy mô framework.
- **Composition là kiến trúc mặc định của Spring:** Service inject Repository, Repository inject DataSource... tất cả là "has-a" qua constructor injection, gần như không dùng kế thừa cho logic nghiệp vụ.
- **`equals`/`hashCode` cho JPA Entity là chủ đề nhạy cảm:** Entity bị quản bởi Hibernate, dùng làm key trong `Set` quan hệ. Khuyến nghị thực tế: `equals/hashCode` dựa trên **business key hoặc id ổn định**, *không* dựa trên field thay đổi để tránh vỡ `Set` sau khi persist.
- **`final` cho thread-safety:** Bean Singleton dùng chung mọi request; field bất biến (`private final`) giúp an toàn đa luồng mà không cần khoá. DTO/value object nên immutable.
- **`toString()` cho log/observability:** Override `toString` (hoặc dùng Lombok `@ToString`) để log object có nghĩa — nhưng *cẩn thận không log dữ liệu nhạy cảm* (mật khẩu, token).

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 03 (Auction/Bid đã có **validation** giá dương, tăng dần). Hôm nay ta tái cấu trúc theo OOP cho chuẩn.

**Nhiệm vụ Day 06:**
1. Mô hình hoá phân cấp đối tượng dùng **đóng gói** (field `private` + getter) và **composition**:
   - `User { long id; String name; }`
   - `Item { long id; String title; long startPrice; }`
   - `Bid { long id; User bidder; long amount; }` — *tham chiếu* `User`.
   - `Auction` **has-a** `Item` và **has-a** `List<Bid>` (không kế thừa `List`).
2. Override `equals()` / `hashCode()` hợp lý:
   - `User` định danh theo `id`.
   - `Bid` định danh theo `id` (hoặc `id + bidder + amount` tuỳ nghiệp vụ).
   - Dùng `Objects.equals` / `Objects.hash`, kèm `@Override`.
3. Override `toString()` cho cả 4 class để log dễ đọc (đừng in object thô).
4. Trong `Auction`, viết `place(Bid)` *uỷ quyền* thêm vào list nhưng **giữ lại validation Day 03** (giá phải dương và lớn hơn giá hiện tại).
5. Chứng minh đa hình: tạo `RegularBid` và `ProxyBid` (kế thừa/implement `Bid` hoặc interface `Biddable`), gom vào `List`, lặp gọi `effectivePrice()`.
6. Ghi vào `notes/day-06.md`: trả lời bằng lời của bạn — "vì sao `Auction` *không nên* `extends ArrayList<Bid>`".

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Phân biệt overloading và overriding?**
> **Đáp:** Overloading = cùng tên method nhưng **khác danh sách tham số**, trong cùng class, quyết định lúc **biên dịch** (static binding); kiểu trả về được phép khác. Overriding = lớp con định nghĩa lại method của cha với **signature y hệt**, quyết định lúc **chạy** (dynamic dispatch); không thu hẹp access, không ném checked exception rộng hơn, kiểu trả về giống hoặc là kiểu con (covariant).

**Q2: Bốn trụ cột OOP là gì?**
> **Đáp:** Encapsulation (đóng gói — giấu state, lộ hành vi, bảo vệ invariant); Inheritance (kế thừa — quan hệ "is-a", tái dùng/mở rộng); Polymorphism (đa hình — một interface nhiều cài đặt, một lời gọi nhiều hành vi); Abstraction (trừu tượng — lộ "cái gì", giấu "thế nào", qua abstract class/interface).

**Q3: Vì sao override `equals` thì phải override `hashCode`?**
> **Đáp:** Vì hợp đồng: hai object `equals` nhau **phải** có `hashCode` bằng nhau. `HashMap`/`HashSet` tìm phần tử theo 2 bước — dùng `hashCode` chọn bucket rồi `equals` so khớp. Nếu hai object bằng nhau mà `hashCode` khác, chúng rơi vào hai bucket khác → tập tưởng không trùng nên chứa cả hai, `map.get` tra nhầm bucket trả `null`.

**Q4: `final` dùng để làm gì? Ba ngữ cảnh?**
> **Đáp:** `final class` không kế thừa được (vd `String`); `final method` không override được; `final field` chỉ gán một lần (nền của immutable object và hằng `static final`). Mục đích: khoá hành vi/giá trị, đảm bảo bất biến và an toàn đa luồng.

**Q5: `==` và `.equals()` khác nhau thế nào trong Java?**
> **Đáp:** Với object, `==` so **tham chiếu** (cùng một instance hay không); `.equals()` so theo **logic do bạn định nghĩa** (thường là giá trị). Đối chiếu PHP: `==` của Java ≈ `===` của PHP; còn `.equals()` của Java ≈ `==` của PHP. Với chuỗi luôn dùng `.equals()`, không dùng `==`.

### Mức Senior

**Q6: Giải thích static binding vs dynamic dispatch và cơ chế vtable.**
> **Đáp:** Static binding (early) chốt method lúc **biên dịch** dựa trên kiểu khai báo + signature — áp dụng cho overload, `static`, `private`, `final`. Dynamic dispatch (late) chốt lúc **chạy** dựa trên **kiểu thực** của object — áp dụng cho method instance bị override. Mỗi class có một virtual method table (vtable); object trỏ tới vtable của lớp thực, lời gọi method ảo tra vtable để nhảy tới cài đặt đúng. Method instance trong Java mặc định là virtual. Lưu ý: field bind theo kiểu khai báo (static), không đa hình.

**Q7: Khi nào chọn composition thay vì inheritance? Vì sao?**
> **Đáp:** Ưu tiên composition (has-a) khi chỉ cần tái dùng code hoặc ghép hành vi, khi quan hệ không thực sự "is-a", hoặc khi cần linh hoạt. Kế thừa sâu gây **fragile base class** (sửa cha vỡ con vì con phụ thuộc chi tiết cài đặt của cha), lộ API cha ra con, và Java không đa kế thừa class nên bị kẹt một cây. Chỉ kế thừa khi "is-a" đúng thật và dòng họ ổn định. Spring/JPA dùng composition + interface (DI) là chính.

**Q8: Covariant return type là gì? Cho ví dụ ứng dụng.**
> **Đáp:** Khi override, kiểu trả về được phép là **kiểu con** của kiểu trả về ở method cha. Ví dụ `Animal reproduce()` ở cha, `Dog reproduce()` ở con — hợp lệ. Ứng dụng: pattern clone/builder/factory ở lớp con trả về đúng kiểu con mà không cần ép kiểu, giúp API gọn và an toàn kiểu.

**Q9: Một class override `equals` nhưng `hashCode` dựa trên field *mutable* sẽ gây vấn đề gì trong HashSet?**
> **Đáp:** Nếu thêm object vào `HashSet` rồi *thay đổi* field tham gia tính `hashCode`, object sẽ "lạc bucket": nó vẫn nằm ở bucket cũ (theo hashCode lúc add) nhưng tra cứu lại tính bucket mới → `contains`/`remove` trả sai, object thành "rác không xoá được". Vì vậy key của tập/map nên **immutable**, hoặc `equals/hashCode` dựa trên business key ổn định (đặc biệt với JPA Entity).

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được 4 trụ cột OOP bằng lời của mình (không nhìn tài liệu)
- [ ] Phân biệt rõ static binding (overload) vs dynamic dispatch (override) + vtable
- [ ] Tự viết được override `equals`/`hashCode`/`toString` đúng hợp đồng
- [ ] Tự kiểm chứng "xoá `hashCode` → `HashSet` chứa phần tử trùng"
- [ ] Hiểu khi nào dùng composition thay kế thừa
- [ ] Nắm `this`/`super`/`final` và covariant return type
- [ ] Hoàn thành nhiệm vụ Mini Project Day 06
- [ ] Trả lời được 9 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Object-Oriented Programming Concepts" và "Interfaces and Inheritance"
- Sách *Effective Java* (Joshua Bloch) — Item 10–12 (`equals`/`hashCode`/`toString`), Item 18 ("Favor composition over inheritance")
- Baeldung — "Java equals() and hashCode() Contracts", "Method Overloading vs Overriding"
- JLS (Java Language Specification) — mục 8.4.8 (Inheritance & Overriding), 15.12 (Method Invocation)
- PHP Manual — "Traits", "Object Comparison" (`==` vs `===`) để đối chiếu
