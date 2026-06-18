# Day 14 - Reflection

> **Giai đoạn:** Reflection & Annotations
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Reflection là gì**: khả năng một chương trình **tự soi & thao tác chính nó lúc chạy** (runtime) — đọc class, field, method, constructor mà không cần biết trước tên ở lúc biên dịch.
- Nắm vững **`Class<?>` object**: 3 cách lấy nó và vì sao nó là "cánh cửa" vào toàn bộ Reflection.
- Đọc được **field / method / constructor**; phân biệt cho chuẩn **`getXxx` vs `getDeclaredXxx`**.
- Gọi method động bằng **`Method.invoke`**, đọc/ghi field bằng **`Field.get/set`**, dùng **`setAccessible(true)`** để chọc vào `private` — và hiểu rào cản từ **module system** (Java 9+).
- Tạo object động qua **`Constructor.newInstance(...)`** (và vì sao `Class.newInstance()` bị deprecated).
- Hiểu **cái giá phải trả**: chậm hơn gọi trực tiếp, mất type-safety, phá vỡ encapsulation.
- Thấy rõ **vì sao mọi framework Java đều sống nhờ Reflection** (Spring DI, Hibernate, Jackson, JUnit) — và liên hệ với **Service Container của Laravel**.

---

## 🧠 Lý thuyết cốt lõi

### 1. Reflection là gì? Tại sao nó tồn tại?

Bình thường bạn viết code "tĩnh": gõ thẳng tên class, tên method, compiler kiểm tra hết.

```java
Auction a = new Auction("Đồng hồ cổ", 1_000_000);
a.placeBid(1_200_000);   // tên class, tên method biết trước lúc compile
```

**Reflection** lật ngược tình thế: lúc chạy, bạn cầm trong tay một **đối tượng mô tả class** (`Class`), rồi hỏi nó "mày có những field gì? method gì? constructor nào?", và **gọi/đọc/ghi** chúng — kể cả khi tới lúc compile bạn **chưa hề biết** tên class đó.

```
   Code thường (compile-time):  bạn  ──gọi thẳng──►  Auction.placeBid()
                                       (compiler kiểm tra)

   Reflection (runtime):        bạn  ──hỏi JVM──►  Class<Auction>
                                                       │
                                          "method tên placeBid?" ──► Method
                                                       │
                                              method.invoke(a, 1_200_000)
```

> 💡 Bytecode `.class` (Day 01) **giữ lại metadata** về cấu trúc class (tên field, kiểu, signature method...). Reflection chính là API để đọc lại đống metadata đó lúc chạy. Đây là lý do Java làm được Reflection mạnh, còn C/C++ thì gần như không.

**Gói chính:** `java.lang.reflect.*` (`Field`, `Method`, `Constructor`, `Modifier`...) và `java.lang.Class`.

### 2. `Class<?>` object — cánh cửa vào mọi thứ

Mỗi class được JVM nạp đều có **đúng một** đối tượng `Class` đại diện cho nó trong bộ nhớ. Lấy được `Class` là lấy được mọi metadata. Có **3 cách lấy**:

```java
// Cách 1: từ một instance đã có — getClass()
Auction a = new Auction("Tranh", 500_000);
Class<?> c1 = a.getClass();                 // kiểu thực tế lúc chạy

// Cách 2: từ tên class lúc compile — .class (class literal)
Class<Auction> c2 = Auction.class;          // an toàn nhất, không ném exception

// Cách 3: từ CHUỖI tên — Class.forName("...")  ← linh hoạt nhất
Class<?> c3 = Class.forName("com.auction.Auction");  // ném ClassNotFoundException
```

| Cách | Cú pháp | Khi nào dùng | Lưu ý |
|---|---|---|---|
| `getClass()` | `obj.getClass()` | Đã có sẵn object, muốn biết kiểu **thực tế** | Trả về kiểu runtime, không phải kiểu khai báo |
| Class literal | `Tên.class` | Biết tên class lúc compile | Không ném exception, compiler kiểm tra tên |
| `Class.forName(s)` | `Class.forName("a.b.C")` | Tên class chỉ có lúc **chạy** (đọc từ config, file...) | Ném `ClassNotFoundException`; **kích hoạt nạp class** (chạy static init) |

> ⚠️ `Class.forName` nhận **tên đầy đủ (fully-qualified)** kèm package: `"com.auction.Auction"`, **không** phải `"Auction"`. Đây là cách JDBC đời cũ nạp driver: `Class.forName("com.mysql.cj.jdbc.Driver")`.

### 3. Đọc cấu trúc: Field / Method / Constructor — `getXxx` vs `getDeclaredXxx`

Đây là phần **dễ sai nhất** và **hay bị hỏi phỏng vấn nhất**. Hai họ phương thức:

```
getFields()        → CHỈ field public, NHƯNG gồm cả field kế thừa từ class cha
getDeclaredFields()→ MỌI access modifier (private/protected/public), NHƯNG chỉ của
                     CHÍNH class đó (KHÔNG lấy của cha)
```

Áp dụng tương tự cho method và constructor:

| Mục đích | `getXxx()` | `getDeclaredXxx()` |
|---|---|---|
| Access modifier | Chỉ `public` | Mọi modifier (kể cả `private`) |
| Kế thừa | Lấy cả của lớp cha/interface | Chỉ của **chính class này** |
| Method | `getMethods()` | `getDeclaredMethods()` |
| Field | `getFields()` | `getDeclaredFields()` |
| Constructor | `getConstructors()` | `getDeclaredConstructors()` |
| Lấy 1 cái cụ thể | `getMethod("ten", KieuThamSo.class)` | `getDeclaredMethod("ten", KieuThamSo.class)` |

```
   class Animal { public int legs; private String name; }
   class Dog extends Animal { public String breed; private int age; }

   Dog.class.getFields()         → [legs (của Animal), breed]      ← public + kế thừa
   Dog.class.getDeclaredFields() → [breed, age]                    ← của Dog, mọi modifier
```

> 💡 Quy tắc nhớ: **`Declared` = "khai báo ngay trong thân class này"** (nên thấy cả private, nhưng không thấy của cha). Không có `Declared` = "công khai cho thế giới ngoài" (nên thấy cả của cha, nhưng chỉ public). Đa số framework dùng **`getDeclaredFields()` + `setAccessible(true)`** vì cần chọc cả vào field private.

### 4. Hành động: `invoke`, `get/set`, và `setAccessible(true)`

Đọc xong cấu trúc thì tới lúc **thao tác**:

```java
// Gọi method động
Method m = clazz.getDeclaredMethod("placeBid", long.class);
Object ketQua = m.invoke(targetObject, 1_200_000L);  // ~ targetObject.placeBid(1_200_000L)

// Đọc / ghi field
Field f = clazz.getDeclaredField("currentPrice");
f.setAccessible(true);              // bỏ kiểm tra access — chọc được vào private
long gia = (long) f.get(targetObject);   // đọc
f.set(targetObject, 2_000_000L);         // ghi
```

- `Method.invoke(obj, args...)`: tham số đầu là **object đích** (gọi method trên nó). Với method `static`, truyền `null`. Args là `Object...` → primitive bị **autobox** (`long` → `Long`).
- `Field.get(obj)` / `Field.set(obj, value)`: tương tự, field `static` truyền `null` làm obj.
- `setAccessible(true)`: **tắt kiểm tra access control** của JVM, cho phép đọc/ghi/gọi `private`, `protected`. Đây là "chìa khóa vạn năng" — nhưng có rào chắn (mục 5).

> ⚠️ `invoke` **bọc** exception gốc: nếu method bị gọi ném lỗi, bạn nhận `InvocationTargetException`, lỗi thật nằm ở `e.getCause()`. Đừng quên unwrap khi log.

### 5. Module System (Java 9+) — vì sao `setAccessible(true)` không còn "vạn năng"

Trước Java 9, `setAccessible(true)` chọc vào **bất cứ đâu**, kể cả nội bộ JDK. Từ **Java 9 (Project Jigsaw / module system)**, có **strong encapsulation**:

```
   App của bạn  ──setAccessible(true)──►  field private của class trong module KHÔNG mở
                                                │
                                                ▼
                                  InaccessibleObjectException  💥
```

- Một module chỉ cho phép reflection chọc vào package nếu nó **`opens`** package đó (trong `module-info.java`), hoặc khi chạy thêm cờ JVM:

```bash
java --add-opens java.base/java.lang=ALL-UNNAMED -jar app.jar
```

- Bạn hay gặp `InaccessibleObjectException` khi thư viện (cũ) cố reflect vào nội bộ JDK trên Java 17/21. Cách xử lý: thêm `--add-opens`, hoặc nâng cấp thư viện.
- Code **của chính bạn** (cùng module / classpath không có module-info) thì `setAccessible(true)` vẫn chạy bình thường. Rào cản chủ yếu nhắm vào việc chọc **nội bộ JDK** và **module khác đóng kín**.

> 💡 Spring/Hibernate đã xử lý chuyện này giúp bạn (chúng `opens` đúng chỗ hoặc reflect vào class của bạn — vốn nằm ở classpath mở). Bạn chỉ "đụng tường" khi tự reflect vào `java.*` hoặc thư viện đóng module.

### 6. Tạo instance động: `Constructor.newInstance(...)`

Để "new" một object mà không gõ `new`:

```java
Class<?> clazz = Class.forName("com.auction.Auction");

// Lấy đúng constructor theo signature tham số
Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, long.class);
ctor.setAccessible(true);                       // nếu constructor không public
Object obj = ctor.newInstance("Xe cổ", 50_000_000L);  // ~ new Auction("Xe cổ", 50_000_000L)
```

- `getDeclaredConstructor(...)` nhận **danh sách kiểu tham số** để chọn đúng constructor (overload).
- `newInstance(args...)` truyền **giá trị** đúng thứ tự.

> ⚠️ `Class.newInstance()` (gọi thẳng trên `Class`) đã **deprecated từ Java 9**. Lý do: nó chỉ gọi được constructor **không tham số** và **"nuốt" rồi ném lại exception sai cách** (lách qua kiểm tra checked-exception của compiler — một lỗ hổng an toàn). **Luôn dùng** `clazz.getDeclaredConstructor().newInstance()` thay thế.

### 7. Cái giá phải trả của Reflection

Reflection mạnh nhưng **không miễn phí**:

| Vấn đề | Giải thích |
|---|---|
| 🐢 **Chậm hơn gọi trực tiếp** | Phải tra cứu metadata, kiểm tra access, autobox tham số. JIT (Day 01) **khó tối ưu/inline** lời gọi reflective vì đích chỉ biết lúc chạy. |
| 🚫 **Mất type-safety** | Lỗi sai tên method / sai kiểu tham số **không bị compiler bắt** → nổ lúc **runtime** (`NoSuchMethodException`, `ClassCastException`). |
| 🔓 **Phá vỡ encapsulation** | `setAccessible(true)` chọc thẳng vào `private` → có thể đặt object vào **trạng thái không hợp lệ** mà constructor/setter vốn dùng để bảo vệ. |
| 🔧 **Khó refactor / khó đọc** | Tên method là **chuỗi** → IDE "rename" không lần ra; khó debug, khó trace. |

> 💡 Vì vậy: **đừng dùng Reflection trong code nghiệp vụ thường ngày**. Nó là công cụ của **framework / hạ tầng**, nơi tính linh hoạt đáng giá hơn tốc độ. Bản thân framework thường **cache** `Method`/`Field` đã tra cứu để giảm chi phí.

### 8. Vì sao framework nào cũng cần Reflection?

| Framework | Reflection làm gì |
|---|---|
| **Spring (DI)** | Quét class, đọc annotation, **new bean** qua constructor, **inject** dependency vào field/constructor — tất cả bằng reflection. |
| **Hibernate (ORM)** | Đọc cột DB → **set thẳng vào field** của entity (kể cả private), tạo entity qua constructor không tham số. |
| **Jackson / Gson (serialization)** | Đọc field/getter để **ghi JSON**; đọc JSON rồi **set ngược vào field** khi deserialize. |
| **JUnit (test)** | Quét method gắn `@Test`, **new test class**, rồi `invoke` từng method test. |

Tất cả đều cần "lúc chạy mới biết class của người dùng là gì" → bắt buộc dùng Reflection.

---

## 🔁 Đối chiếu với Laravel/PHP

PHP cũng có Reflection (lớp `ReflectionClass`, `ReflectionMethod`, `ReflectionProperty`) — và **bạn đã dùng nó mỗi ngày mà không biết**: **Service Container của Laravel chạy bằng Reflection**.

Khi bạn type-hint dependency ở constructor và để Laravel "tự lo":

```php
// Laravel — bạn KHÔNG hề new BidRepository, container tự resolve
class AuctionService {
    public function __construct(private BidRepository $bids) {}
}
$service = app()->make(AuctionService::class);  // container dùng Reflection
```

Bên trong, container Laravel làm đúng việc của Reflection:

```php
$reflector  = new ReflectionClass(AuctionService::class);
$ctor       = $reflector->getConstructor();
$params     = $ctor->getParameters();        // đọc tham số
foreach ($params as $p) {
    $type = $p->getType();                   // đọc type-hint: BidRepository
    $deps[] = app()->make($type->getName()); // ĐỆ QUY resolve dependency
}
$instance = $reflector->newInstanceArgs($deps);  // tạo object
```

**Đây chính xác là cách Spring DI làm bằng Java Reflection.** Bạn đã hiểu autowiring của Laravel → bạn đã hiểu 80% Spring DI.

### Bảng so sánh API PHP ↔ Java

| Mục đích | PHP Reflection | Java Reflection |
|---|---|---|
| Lấy mô tả class từ tên | `new ReflectionClass('App\\Auction')` | `Class.forName("com.auction.Auction")` |
| Lấy từ object có sẵn | `new ReflectionObject($obj)` / `(new ReflectionClass($obj))` | `obj.getClass()` |
| Lấy từ literal | `Auction::class` (chỉ ra **chuỗi tên**) | `Auction.class` (ra **`Class` object**) |
| Liệt kê property/field | `$r->getProperties()` | `clazz.getDeclaredFields()` |
| Liệt kê method | `$r->getMethods()` | `clazz.getDeclaredMethods()` |
| Lấy 1 method | `$r->getMethod('placeBid')` | `clazz.getDeclaredMethod("placeBid", long.class)` |
| Gọi method động | `$method->invoke($obj, $args)` | `method.invoke(obj, args)` |
| Đọc/ghi property private | `$prop->setAccessible(true); $prop->getValue($obj)` | `field.setAccessible(true); field.get(obj)` |
| Tạo instance động | `$r->newInstanceArgs([...])` | `ctor.newInstance(...)` |
| Container/DI tự inject | `app()->make(X::class)` (autowire) | `@Autowired` / constructor injection (Spring) |

> 🧩 Khác biệt tư duy: PHP type-hint **không bắt buộc** và kiểu mất lúc runtime một phần (kiểu động). Java **biết chính xác kiểu của mọi tham số constructor** lúc chạy nhờ metadata trong `.class`, nên DI của Spring resolve theo kiểu **chắc chắn hơn**. Đổi lại, sai tên/sai kiểu ở Java nổ runtime y như PHP — Reflection bỏ qua lá chắn compiler ở cả hai ngôn ngữ.

---

## 💻 Thực hành code

Toàn bộ chạy được trên **Java 21**. Class mẫu (đã quen từ các ngày trước):

```java
// File: Auction.java
package com.auction;

public class Auction {
    private final String item;        // tên món hàng
    private long currentPrice;        // giá hiện tại (private!)
    public  int bidCount;             // số lượt đặt giá (public)

    public Auction(String item, long startingPrice) {
        this.item = item;
        this.currentPrice = startingPrice;
        this.bidCount = 0;
    }

    // constructor không tham số — nhiều framework cần cái này
    public Auction() {
        this("(chưa đặt tên)", 0L);
    }

    public long placeBid(long amount) {
        if (amount <= currentPrice)
            throw new IllegalArgumentException("Giá đặt phải cao hơn giá hiện tại");
        this.currentPrice = amount;
        this.bidCount++;
        return currentPrice;
    }

    private void resetSecret(long price) {   // method PRIVATE để demo
        this.currentPrice = price;
        this.bidCount = 0;
    }

    public long getCurrentPrice() { return currentPrice; }
    public String getItem()       { return item; }
}
```

### (a) Đọc & in TẤT CẢ field + method của một class

```java
// File: InspectAuction.java
package com.auction;

import java.lang.reflect.*;

public class InspectAuction {
    public static void main(String[] args) {
        Class<?> clazz = Auction.class;                  // cách 2: class literal
        System.out.println("== Class: " + clazz.getName() + " ==");

        System.out.println("\n--- FIELDS (getDeclaredFields: mọi modifier, chỉ class này) ---");
        for (Field f : clazz.getDeclaredFields()) {
            // Modifier.toString cho ra "private final", "public"...
            System.out.printf("  %s %s %s%n",
                    Modifier.toString(f.getModifiers()),
                    f.getType().getSimpleName(),
                    f.getName());
        }

        System.out.println("\n--- METHODS (getDeclaredMethods) ---");
        for (Method m : clazz.getDeclaredMethods()) {
            System.out.printf("  %s %s %s(%d tham số)%n",
                    Modifier.toString(m.getModifiers()),
                    m.getReturnType().getSimpleName(),
                    m.getName(),
                    m.getParameterCount());
        }

        System.out.println("\n--- CONSTRUCTORS ---");
        for (Constructor<?> c : clazz.getDeclaredConstructors()) {
            System.out.printf("  %s(%d tham số)%n",
                    c.getName(), c.getParameterCount());
        }
    }
}
```

> 💡 Đổi `getDeclaredFields()` → `getFields()` và quan sát: `currentPrice` (private) **biến mất**, chỉ còn `bidCount` (public) — đúng như mục 3.

### (b) Gọi method động bằng TÊN (chuỗi)

```java
// File: InvokeDemo.java
package com.auction;

import java.lang.reflect.Method;

public class InvokeDemo {
    public static void main(String[] args) throws Exception {
        Auction a = new Auction("Đồng hồ Rolex", 10_000_000L);

        String tenMethod = "placeBid";           // tên đến từ chuỗi (có thể đọc từ config)
        // Lấy method theo tên + kiểu tham số (để phân biệt overload)
        Method m = a.getClass().getMethod(tenMethod, long.class);

        Object ketQua = m.invoke(a, 12_000_000L); // ~ a.placeBid(12_000_000L)
        System.out.println("Giá mới sau khi đặt: " + ketQua);   // 12000000
        System.out.println("Số lượt đặt: " + a.bidCount);       // 1
    }
}
```

### (c) Đọc / ghi field PRIVATE bằng `setAccessible(true)`

```java
// File: PrivateAccessDemo.java
package com.auction;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class PrivateAccessDemo {
    public static void main(String[] args) throws Exception {
        Auction a = new Auction("Tranh sơn dầu", 5_000_000L);

        // 1) Đọc field private currentPrice
        Field f = Auction.class.getDeclaredField("currentPrice");
        f.setAccessible(true);                       // bỏ kiểm tra access
        long gia = (long) f.get(a);                  // đọc giá trị từ object a
        System.out.println("Giá (đọc qua reflection): " + gia);  // 5000000

        // 2) GHI thẳng vào field private — phá vỡ encapsulation!
        f.set(a, 99_000_000L);
        System.out.println("Giá sau khi ghi đè: " + a.getCurrentPrice()); // 99000000

        // 3) Gọi method PRIVATE resetSecret
        Method secret = Auction.class.getDeclaredMethod("resetSecret", long.class);
        secret.setAccessible(true);
        secret.invoke(a, 1_000L);
        System.out.println("Giá sau resetSecret: " + a.getCurrentPrice()); // 1000
    }
}
```

> ⚠️ Bước (2) đặt giá thẳng mà **không qua `placeBid`** → bỏ qua mọi validation. Đây chính là sức mạnh (và sự nguy hiểm) của Reflection.

### (d) Tạo instance động bằng `Constructor.newInstance`

```java
// File: FactoryDemo.java
package com.auction;

import java.lang.reflect.Constructor;

public class FactoryDemo {
    public static void main(String[] args) throws Exception {
        // Tên class chỉ biết lúc chạy (giả lập đọc từ file config)
        String className = "com.auction.Auction";
        Class<?> clazz = Class.forName(className);          // cách 3

        // Chọn đúng constructor (String, long)
        Constructor<?> ctor = clazz.getDeclaredConstructor(String.class, long.class);
        Object obj = ctor.newInstance("Xe Vespa cổ", 30_000_000L);

        Auction a = (Auction) obj;                          // ép kiểu (mất type-safety)
        System.out.println("Đã tạo: " + a.getItem() + " - giá " + a.getCurrentPrice());

        // KHÔNG dùng clazz.newInstance() (deprecated). Dùng cách chuẩn:
        Object obj2 = clazz.getDeclaredConstructor().newInstance(); // gọi ctor rỗng
        System.out.println("Object rỗng: " + ((Auction) obj2).getItem());
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Nhầm `getMethods()` với `getDeclaredMethods()`.** `getMethods` không thấy method `private`; `getDeclaredMethods` không thấy method kế thừa từ cha. Chọn sai → `NoSuchMethodException` khó hiểu.
- **Quên `setAccessible(true)` trước khi chọc private** → `IllegalAccessException`.
- **Truyền sai kiểu tham số khi lấy method.** `getMethod("placeBid", Long.class)` (wrapper) **khác** `getMethod("placeBid", long.class)` (primitive) → không tìm thấy. Phải khớp đúng signature.
- **Quên unwrap `InvocationTargetException`.** Khi method được invoke ném lỗi, bạn nhận `InvocationTargetException`; nguyên nhân thật ở `getCause()`. Log nhầm sẽ giấu mất lỗi gốc.
- **Dùng `Class.newInstance()`** (deprecated, bug exception). Luôn dùng `getDeclaredConstructor().newInstance()`.
- **Dùng `Class.forName("Auction")` thiếu package** → `ClassNotFoundException`. Phải dùng tên đầy đủ.
- **Lạm dụng Reflection trong code nghiệp vụ** → chậm, mất type-safety, khó refactor. Để dành cho hạ tầng.
- **`InaccessibleObjectException` trên Java 17/21** khi reflect vào nội bộ JDK / module đóng → thêm `--add-opens` hoặc nâng cấp thư viện.

---

## 🚀 Liên hệ Spring Boot / Production

- **Spring DI là Reflection ở quy mô lớn.** Khi Spring khởi động, nó quét classpath tìm class gắn `@Component`/`@Service`/`@Repository`, đọc constructor, **đệ quy resolve** từng dependency rồi `newInstance` — giống hệt ví dụ container Laravel ở trên, nhưng bằng `java.lang.reflect`.
- **`@Autowired` vào field** = Spring lấy `Field`, `setAccessible(true)`, rồi `field.set(bean, dependency)`. (Đây là lý do constructor injection được khuyên dùng hơn field injection: không cần phá encapsulation, dễ test.)
- **Hibernate** dùng reflection set thẳng giá trị cột vào field entity, và yêu cầu entity có **constructor không tham số** — chính là để gọi `getDeclaredConstructor().newInstance()`.
- **Jackson** (Spring Boot dùng mặc định cho REST) đọc/ghi field & getter của DTO qua reflection để serialize JSON.
- **Hiệu năng:** reflection chậm, nên Spring **cache** metadata sau lần đầu, và xu hướng mới (Spring 6 / Spring Boot 3 + **GraalVM Native Image**) là **AOT (Ahead-of-Time)**: tính sẵn thông tin reflection lúc build để giảm dùng reflection lúc chạy → khởi động nhanh, ăn ít RAM hơn.
- **Bảo mật:** `setAccessible(true)` có thể bị chặn bởi module system; trong môi trường siết chặt bạn cần khai báo `opens` hoặc cờ `--add-opens` cho đúng thư viện.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta tự viết một **mini DI Container** — `SimpleContainer` — y như "bộ não" của Spring/Laravel container, dùng Reflection để **tự resolve dependency của constructor (đệ quy)**. Qua đó bạn *thấy tận mắt* DI vận hành thế nào.

**Nhiệm vụ Day 14:**

```java
// File: SimpleContainer.java
package com.auction.di;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;

/** Mini DI container: tự tạo object và đệ quy resolve dependency ở constructor. */
public class SimpleContainer {

    /** Resolve một class: tạo instance, tự tìm & tạo các dependency cần thiết. */
    public <T> T make(Class<T> type) {
        try {
            // 1) Lấy constructor "đầu tiên" (đơn giản hóa: thực tế Spring chọn theo @Autowired)
            Constructor<?> ctor = type.getDeclaredConstructors()[0];
            ctor.setAccessible(true);

            // 2) Với mỗi tham số constructor → ĐỆ QUY resolve dependency
            Parameter[] params = ctor.getParameters();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> depType = params[i].getType();  // đọc kiểu tham số
                args[i] = make(depType);                 // đệ quy! (giống app()->make trong Laravel)
            }

            // 3) Tạo instance với các dependency đã resolve
            @SuppressWarnings("unchecked")
            T instance = (T) ctor.newInstance(args);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Không resolve được " + type.getName(), e);
        }
    }
}
```

```java
// File: AuctionWiringDemo.java
package com.auction.di;

// Các class nghiệp vụ — KHÔNG ai gọi "new" thủ công, container tự lo
class BidRepository {
    public void save(long amount) {
        System.out.println("  [Repo] Lưu lượt đặt giá: " + amount);
    }
}

class AuctionService {
    private final BidRepository repo;            // dependency type-hint ở constructor
    public AuctionService(BidRepository repo) {  // container sẽ tự inject
        this.repo = repo;
    }
    public void bid(long amount) {
        System.out.println("[Service] Nhận lượt đặt giá " + amount);
        repo.save(amount);
    }
}

public class AuctionWiringDemo {
    public static void main(String[] args) {
        SimpleContainer container = new SimpleContainer();

        // Chỉ yêu cầu AuctionService — container TỰ tạo BidRepository và inject vào!
        AuctionService service = container.make(AuctionService.class);
        service.bid(15_000_000L);

        // Kết quả:
        // [Service] Nhận lượt đặt giá 15000000
        //   [Repo] Lưu lượt đặt giá: 15000000
    }
}
```

**Mở rộng (tự làm để hiểu sâu hơn):**
1. Thêm một **loader đọc tên class từ file** `config.txt` (ví dụ dòng `com.auction.Auction`), dùng `Class.forName` + `newInstance` để tạo — giả lập cách Spring đọc cấu hình.
2. Cache lại instance đã tạo trong một `Map<Class<?>, Object>` để biến nó thành **singleton scope** (đúng như Spring bean mặc định).
3. Phát hiện **circular dependency** (A cần B, B cần A) và ném lỗi rõ ràng — bài toán kinh điển của mọi container.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Reflection là gì? Cho 3 cách lấy `Class` object.**
> **Đáp:** Reflection là khả năng chương trình **tự soi & thao tác class/field/method/constructor lúc chạy** mà không cần biết trước tên lúc compile. 3 cách lấy `Class`: (1) `obj.getClass()` — từ instance, ra kiểu runtime; (2) `Tên.class` — class literal, an toàn, không ném exception; (3) `Class.forName("đầy.đủ.Tên")` — từ chuỗi tên, linh hoạt nhất, ném `ClassNotFoundException`.

**Q2: Phân biệt `getDeclaredFields()` và `getFields()`.**
> **Đáp:** `getFields()` chỉ trả field **public** nhưng **gồm cả field kế thừa** từ class cha/interface. `getDeclaredFields()` trả **mọi access modifier** (private/protected/public) nhưng **chỉ của chính class đó** (không lấy của cha). Áp dụng tương tự cho `getMethods/getDeclaredMethods` và constructor. Framework hay dùng `getDeclaredFields()` + `setAccessible(true)` để chọc cả vào private.

**Q3: `setAccessible(true)` để làm gì?**
> **Đáp:** Tắt kiểm tra access control của JVM, cho phép đọc/ghi field hoặc gọi method `private`/`protected` từ bên ngoài. Cần thiết để framework (Hibernate, Jackson, Spring) set thẳng giá trị vào field private. Đổi lại nó **phá vỡ encapsulation** và có thể bị **module system (Java 9+)** chặn nếu package không được `opens`.

**Q4: Vì sao `Class.newInstance()` bị deprecated? Thay bằng gì?**
> **Đáp:** Vì nó chỉ gọi được constructor **không tham số** và **lách qua kiểm tra checked-exception của compiler** (ném lại exception sai cách → lỗ hổng an toàn). Thay bằng `clazz.getDeclaredConstructor(...).newInstance(...)` — chọn được constructor theo signature và xử lý exception đúng (bọc trong `InvocationTargetException`).

### Mức Senior

**Q5: Reflection ảnh hưởng hiệu năng thế nào và framework giảm thiểu ra sao?**
> **Đáp:** Lời gọi reflective chậm hơn gọi trực tiếp do phải tra cứu metadata, kiểm tra access, autobox tham số; quan trọng hơn, **JIT khó inline/tối ưu** vì đích chỉ xác định lúc chạy. Framework giảm thiểu bằng cách **cache** đối tượng `Method`/`Field`/`Constructor` sau lần đầu tra cứu, dùng `MethodHandle`/`LambdaMetafactory` cho đường nóng, và xu hướng **AOT + GraalVM Native Image** (Spring Boot 3) tính sẵn metadata lúc build để gần như loại reflection lúc chạy.

**Q6: Module system (Java 9+) thay đổi gì với `setAccessible(true)`?**
> **Đáp:** Java 9 đưa ra **strong encapsulation**: một module chỉ cho reflection chọc vào package nếu nó **`opens`** package đó. Cố reflect vào package đóng (đặc biệt nội bộ JDK) sẽ ném `InaccessibleObjectException`. Xử lý: khai báo `opens` trong `module-info.java`, hoặc chạy với cờ `--add-opens module/package=ALL-UNNAMED`, hoặc nâng cấp thư viện. Code cùng module / trên classpath (unnamed module) phần lớn không bị ảnh hưởng.

**Q7: Hãy mô tả Spring DI resolve một bean bằng Reflection (đối chiếu container Laravel).**
> **Đáp:** Spring đọc `Class` của bean, lấy constructor (ưu tiên cái gắn `@Autowired` hoặc constructor duy nhất), đọc `getParameterTypes()`, rồi **đệ quy** resolve từng dependency từ context, cuối cùng `constructor.newInstance(deps)`. Field injection thì lấy `Field`, `setAccessible(true)`, `field.set(bean, dep)`. Đây đúng là cơ chế Service Container của Laravel: `app()->make()` dùng `ReflectionClass`/`ReflectionParameter` đọc type-hint constructor và `newInstanceArgs` — chỉ khác là Java biết kiểu tham số chắc chắn nhờ metadata trong `.class`.

**Q8: Khi nào KHÔNG nên dùng Reflection?**
> **Đáp:** Trong code nghiệp vụ thông thường nơi đã biết kiểu lúc compile — vì mất type-safety (lỗi nổ runtime), chậm, khó refactor (tên là chuỗi, IDE rename không lần ra), khó đọc/debug. Reflection chỉ nên dùng ở tầng **framework/hạ tầng** (DI, ORM, serialization, test runner) nơi tính linh hoạt "lúc chạy mới biết class" đáng giá hơn các chi phí trên. Ưu tiên các giải pháp an toàn kiểu (generics, interface, `MethodHandle`, annotation processing lúc compile) khi có thể.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được Reflection là gì và vì sao framework cần nó (bằng lời của mình)
- [ ] Nêu được 3 cách lấy `Class` và khi nào dùng cách nào
- [ ] Phân biệt rạch ròi `getXxx` vs `getDeclaredXxx`
- [ ] Tự code: in field/method, `invoke` method, đọc/ghi private field, `Constructor.newInstance`
- [ ] Hiểu rào cản module system + `setAccessible(true)` + `--add-opens`
- [ ] Biết vì sao `Class.newInstance()` deprecated và cách thay
- [ ] Hoàn thành `SimpleContainer` (mini DI) và chạy được `AuctionWiringDemo`
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "The Reflection API" (Trail: Reflection)
- Javadoc — `java.lang.Class`, `java.lang.reflect.{Field, Method, Constructor, Modifier}`
- Baeldung — "Guide to Java Reflection" và "java.lang.reflect.Constructor"
- JEP 261 / Project Jigsaw — Module System (đọc phần strong encapsulation, `--add-opens`)
- Laravel Docs — "Service Container" (đọc để thấy autowiring bằng PHP Reflection)
- Spring Framework Reference — "The IoC Container" (cơ chế DI dựa trên reflection)
- PHP Manual — `ReflectionClass`, `ReflectionMethod`, `ReflectionProperty` (đối chiếu)
