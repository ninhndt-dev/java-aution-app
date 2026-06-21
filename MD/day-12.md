# Day 12 - Generics

> **Giai đoạn:** Collections & Generics
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Generics tới tận gốc — từ type safety, wildcard, PECS cho tới type erasure trong JVM.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **vì sao Java có generics**: an toàn kiểu (type safety) ngay lúc **biên dịch**, bỏ ép kiểu thủ công, code tái sử dụng.
- Viết được **generic class**, **generic interface**, **generic method**, và đặt **bounded type parameter** (`<T extends ...>`).
- Nắm chắc **wildcard** `?`, `? extends T`, `? super T` và nguyên tắc **PECS** (Producer Extends, Consumer Super).
- Hiểu **type erasure**: trình biên dịch xóa thông tin kiểu generic lúc runtime — và mọi hệ quả của nó (`new T()` cấm, `instanceof T` cấm, raw type, bridge method).
- Đối chiếu với PHP/Laravel: PHP **không có generics ở runtime**, chỉ mô phỏng bằng PHPDoc + Psalm/PHPStan lúc phân tích tĩnh.
- Áp dụng vào Mini Project: dựng `Repository<T, ID>` generic tái sử dụng cho cả `Auction` lẫn `Bid`.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao có generics? Trước và sau Java 5

Trước Java 5 (2004), collection chỉ chứa `Object`. Bạn nhét gì vào cũng được, nhưng lúc lấy ra phải **ép kiểu (cast)** thủ công — và compiler không bảo vệ bạn:

```java
// THỜI TIỀN-GENERICS (raw type) — biên dịch OK nhưng nguy hiểm
List names = new ArrayList();   // List của "thứ gì đó" (Object)
names.add("Alice");
names.add(42);                  // Lỡ tay nhét Integer vào — compiler KHÔNG cảnh báo gì

String s = (String) names.get(1); // CRASH lúc chạy: ClassCastException!
```

Lỗi chỉ nổ **lúc runtime**, ở chỗ rất xa nơi gây ra — cực khó debug. Generics chuyển lỗi này lên **lúc biên dịch**:

```java
// SAU GENERICS — compiler là người gác cổng
List<String> names = new ArrayList<>();
names.add("Alice");
names.add(42);                  // ❌ LỖI BIÊN DỊCH ngay tại đây — không qua được javac
String s = names.get(0);        // Không cần cast, kiểu đã chắc chắn là String
```

Ba lợi ích cốt lõi:

| Lợi ích | Trước generics | Sau generics |
|---|---|---|
| **Type safety** | Lỗi kiểu nổ lúc *runtime* (`ClassCastException`) | Lỗi kiểu bị bắt lúc *biên dịch* |
| **Không cast thủ công** | `(String) list.get(i)` | `list.get(i)` trả thẳng `String` |
| **Tái sử dụng** | Viết riêng cho từng kiểu | Viết một lần, dùng cho mọi kiểu |

> 💡 Triết lý: **"Lỗi rẻ nhất là lỗi bị bắt sớm nhất."** Bắt lúc biên dịch rẻ hơn bắt lúc test, bắt lúc test rẻ hơn bắt lúc production. Generics kéo lỗi kiểu về phía bên trái — nơi rẻ nhất.

### 2. Type parameter `<T>` và quy ước đặt tên

`T` chỉ là **tham số kiểu** (type parameter) — một "biến đại diện cho một kiểu", giống như tham số `x` đại diện cho một giá trị. Quy ước cộng đồng (không bắt buộc nhưng nên theo):

| Ký hiệu | Ý nghĩa | Ví dụ |
|---|---|---|
| `T` | **T**ype — kiểu chung | `Box<T>`, `Optional<T>` |
| `E` | **E**lement — phần tử trong collection | `List<E>`, `Set<E>` |
| `K` | **K**ey — khóa | `Map<K, V>` |
| `V` | **V**alue — giá trị | `Map<K, V>` |
| `N` | **N**umber — kiểu số | `Stat<N extends Number>` |
| `R` | **R**eturn — kiểu trả về | `Function<T, R>` |

#### Generic class

```java
// T là tham số kiểu, khai báo sau tên class
public class Box<T> {
    private T content;                 // T dùng như một kiểu bình thường bên trong
    public void set(T content) { this.content = content; }
    public T get() { return content; }
}

Box<String> b1 = new Box<>();          // T = String
Box<Integer> b2 = new Box<>();         // T = Integer — cùng class Box, khác kiểu cụ thể
```

#### Generic interface

```java
public interface Repository<T, ID> {   // hai tham số kiểu
    T save(T entity);
    Optional<T> findById(ID id);
}
```

#### Generic method

Tham số kiểu của **method** được khai báo **đứng trước kiểu trả về**:

```java
//        ┌─ khai báo type param
//        │   ┌─ kiểu trả về
//        ▼   ▼
public static <T> T identity(T x) {   // nhận T, trả về T
    return x;
}

String s = identity("hello");          // T suy ra = String
Integer i = identity(42);              // T suy ra = Integer
```

> 💡 Compiler **tự suy luận** (type inference) `T` từ tham số truyền vào, nên hiếm khi phải viết tường minh `MyClass.<String>identity("x")`.

### 3. Bounded type parameter — giới hạn cận trên

Đôi khi `T` không thể là *bất kỳ* kiểu nào — bạn cần `T` có khả năng nào đó. Dùng `extends`:

```java
// T phải là Number HOẶC lớp con của Number (Integer, Double, Long...)
public static <T extends Number> double sum(List<T> list) {
    double total = 0;
    for (T n : list) {
        total += n.doubleValue();      // hợp lệ vì T chắc chắn có doubleValue() của Number
    }
    return total;
}
```

`<T extends Number>` nói: "T là Number hoặc con của nó", nên bên trong bạn gọi được mọi method của `Number`. Truyền `List<String>` vào sẽ **lỗi biên dịch**.

**Multiple bound** — một kiểu phải thỏa nhiều ràng buộc cùng lúc, nối bằng `&`:

```java
// T vừa là Number vừa implements Comparable
public static <T extends Number & Comparable<T>> T maxOf(T a, T b) {
    return a.compareTo(b) >= 0 ? a : b;
}
```

> ⚠️ Với multiple bound, nếu có **class** thì phải đặt **trước tiên**, các interface đặt sau: `<T extends SomeClass & Iface1 & Iface2>`. Lưu ý generics dùng `extends` cho cả interface (không dùng `implements`).

### 4. Wildcard `?` — khi bạn không quan tâm kiểu cụ thể

`?` (wildcard) nghĩa là "một kiểu nào đó chưa biết". Có ba dạng:

```
List<?>            ─ unbounded   ─ "List của thứ gì đó"
List<? extends T>  ─ upper bound ─ "List của T hoặc lớp con T"  (COVARIANT)
List<? super T>    ─ lower bound ─ "List của T hoặc lớp cha T"  (CONTRAVARIANT)
```

#### Vì sao cần wildcard? `List<String>` KHÔNG phải `List<Object>`

Đây là điểm bẫy lớn nhất. Generics **bất biến (invariant)**: dù `String` là con của `Object`, `List<String>` **không** là con của `List<Object>`:

```java
List<String> strings = new ArrayList<>();
List<Object> objects = strings;        // ❌ LỖI BIÊN DỊCH
```

Nếu cho phép, ta sẽ phá vỡ kiểu: `objects.add(42)` sẽ nhét Integer vào `List<String>`. Wildcard ra đời để nới lỏng có kiểm soát.

#### `? extends T` — COVARIANT — dùng để ĐỌC (producer)

```java
List<? extends Number> nums = new ArrayList<Integer>();  // OK — Integer là con Number

Number n = nums.get(0);   // ✅ ĐỌC được: lấy ra chắc chắn là Number (hoặc con)
nums.add(42);             // ❌ KHÔNG add được (trừ null)!
```

**Vì sao không add được?** Compiler chỉ biết `nums` là "List của *một kiểu con nào đó* của Number" — có thể là `List<Integer>`, cũng có thể là `List<Double>`. Nếu cho `add(42)` (Integer) mà thực ra nó là `List<Double>` thì hỏng. Không an toàn ⇒ cấm ghi. Chỉ `add(null)` được vì `null` thuộc mọi kiểu.

#### `? super T` — CONTRAVARIANT — dùng để GHI (consumer)

```java
List<? super Integer> sink = new ArrayList<Number>();    // OK — Number là cha Integer

sink.add(42);             // ✅ GHI được: Integer chắc chắn "vừa" vào List của cha Integer
sink.add(7);              // ✅
Object o = sink.get(0);   // chỉ lấy ra được Object — KHÔNG lấy ra Integer cụ thể
Integer x = sink.get(0);  // ❌ LỖI: không biết kiểu phần tử cụ thể là gì
```

**Vì sao chỉ get ra Object?** `sink` là "List của *một kiểu cha nào đó* của Integer" — có thể là `List<Number>`, `List<Object>`... Phần tử lấy ra chỉ chắc chắn là `Object`. Ghi thì an toàn (Integer luôn là con của mọi cha của nó), đọc thì không.

```
            ┌───────────────────────────────────────────────┐
            │  ? extends T  → ĐỌC an toàn, GHI cấm           │
            │  ? super T    → GHI an toàn, ĐỌC chỉ ra Object │
            └───────────────────────────────────────────────┘
```

### 5. Nguyên tắc PECS — Producer Extends, Consumer Super

Joshua Bloch (Effective Java) đúc kết quy tắc vàng: **PECS**.

- Tham số là **Producer** (nơi bạn **lấy** dữ liệu RA) ⇒ dùng `extends`.
- Tham số là **Consumer** (nơi bạn **đổ** dữ liệu VÀO) ⇒ dùng `super`.

Ví dụ kinh điển — hàm `copy` chép từ `src` sang `dest`:

```java
// src là nguồn (producer → ta ĐỌC từ nó)   → extends
// dest là đích  (consumer → ta GHI vào nó)  → super
public static <T> void copy(List<? super T> dest, List<? extends T> src) {
    for (int i = 0; i < src.size(); i++) {
        dest.set(i, src.get(i));   // đọc T từ src, ghi T vào dest
    }
}
```

Nhờ PECS, hàm `copy` linh hoạt tối đa:

```java
List<Object> dest = new ArrayList<>(List.of(0, 0, 0));
List<Integer> src = List.of(1, 2, 3);
copy(dest, src);   // ✅ chép Integer (src) vào Object (dest) — vẫn an toàn kiểu
```

Đây chính là chữ ký của `Collections.copy`, `Collections.addAll`, `Stream.collect`... trong JDK.

> 💡 Mẹo nhớ: **"Lấy hàng từ nhà sản xuất (extends), giao hàng cho người tiêu dùng (super)."**

### 6. Type erasure — generics biến mất lúc runtime

Đây là phần "tận gốc" nhất. Generics của Java là **trò chơi của compiler**: sau khi biên dịch, **trình biên dịch XÓA sạch thông tin kiểu generic**, thay bằng kiểu bound (hoặc `Object` nếu unbounded) và **chèn cast tự động**. Cơ chế này gọi là **type erasure**.

```
   Code bạn viết            →   Sau erasure (bytecode thực tế)
   ─────────────────────        ─────────────────────────────
   class Box<T> {              class Box {
       T content;                  Object content;       // T → Object
       T get() {...}               Object get() {...}    // T → Object
   }                           }

   class Box<T extends Number>  class Box {
       T content;                  Number content;       // T → Number (bound)

   String s = box.get();   →   String s = (String) box.get();  // compiler chèn cast
```

Hệ quả thực tế, phải thuộc lòng:

```java
// 1. List<String> và List<Integer> CÙNG MỘT class lúc runtime
List<String>  ls = new ArrayList<>();
List<Integer> li = new ArrayList<>();
System.out.println(ls.getClass() == li.getClass());  // true! — đều là ArrayList.class

// 2. Không có List<String>.class — thông tin <String> đã bị xóa
// List<String>.class  → ❌ không biên dịch được

// 3. Không thể new T() — runtime không biết T là gì
public static <T> T create() {
    return new T();          // ❌ LỖI BIÊN DỊCH
}

// 4. Không thể new T[] — không tạo mảng generic trực tiếp
T[] arr = new T[10];         // ❌ LỖI BIÊN DỊCH

// 5. Không thể instanceof T
if (obj instanceof T) {...}  // ❌ LỖI — runtime không còn T để kiểm tra

// 6. Static field không được kiểu T (T thuộc về instance, static thì dùng chung)
class Box<T> {
    static T shared;         // ❌ LỖI BIÊN DỊCH
}
```

**Bridge method** — hệ quả tinh vi của erasure khi override generic:

```java
class Node<T> {
    public void set(T value) {...}     // sau erasure: set(Object)
}
class StringNode extends Node<String> {
    @Override public void set(String value) {...}   // set(String)
}
```

Sau erasure, lớp cha có `set(Object)`, lớp con có `set(String)` — chữ ký khác nhau, đáng lẽ **không** phải override. Để giữ tính đa hình, compiler tự sinh một **bridge method** `set(Object)` trong `StringNode` chỉ để gọi sang `set(String)`. Bạn không thấy nó trong source, nhưng nó hiện ra khi `javap`.

#### Raw type và unchecked warning

Dùng generic class mà **bỏ trống** type parameter gọi là **raw type** — vết tích tương thích ngược:

```java
List raw = new ArrayList();   // raw type — compiler cảnh báo "unchecked"
raw.add("x");                 // unchecked warning: không kiểm tra được kiểu
```

> ⚠️ Đừng bao giờ chủ động viết raw type trong code mới. Nó tắt mọi sự bảo vệ của generics, đưa bạn về thời tiền-Java-5.

### 7. Vì sao Java chọn erasure? — Tương thích ngược

Câu hỏi tự nhiên: sao không giữ thông tin kiểu lúc runtime (như C# "reified generics")? Lý do là **migration compatibility**: năm 2004, đã có hàng triệu dòng code và bytecode dùng `List`, `Map` raw type từ thời Java 1.4. Erasure cho phép code generic mới và code raw cũ **cùng chạy trên một JVM**, file `.class` cũ vẫn hợp lệ, không phải biên dịch lại cả thế giới. Cái giá phải trả là những giới hạn `new T()`, `instanceof T`... ở mục 6.

---

## 🔁 Đối chiếu với Laravel/PHP

Điểm mấu chốt: **PHP KHÔNG có generics thật**. Cú pháp `array<T>`, `Collection<T>` bạn thấy trong code Laravel chỉ là **docblock** — tồn tại trong comment, được **công cụ phân tích tĩnh** (Psalm, PHPStan) đọc lúc *kiểm tra*, còn **runtime PHP hoàn toàn không biết gì** về nó.

```php
/**
 * @template T
 * @param T $item
 * @param int $times
 * @return array<int, T>
 */
function repeat($item, int $times): array {
    return array_fill(0, $times, $item);
}

// Psalm/PHPStan suy ra: repeat("a", 3) trả về array<int, string>
// Nhưng RUNTIME: chỉ là array thường, không có thông tin kiểu nào cả
$r = repeat(42, "ba");   // runtime KHÔNG chặn — chỉ Psalm/PHPStan cảnh báo nếu bạn chạy tool
```

| Khía cạnh | Java | PHP / Laravel |
|---|---|---|
| Generics tồn tại ở đâu | Trong **ngôn ngữ**, do `javac` cưỡng chế | Chỉ trong **docblock** `@template`/`@param T` |
| Ai kiểm tra | **Compiler** (bắt buộc, chặn build) | Psalm / PHPStan (tùy chọn, chạy riêng) |
| Vi phạm kiểu thì sao | **Không biên dịch được** | Vẫn chạy; tool chỉ *cảnh báo* nếu bạn chạy nó |
| Lúc runtime | **Bị xóa** (type erasure) | **Không tồn tại** (chưa từng có) |
| `List<String>` vs `List<Integer>` | Cùng class lúc runtime | `array` là `array`, không phân biệt |

**Khác biệt tư duy quan trọng:**

- Điểm **giống** nhau thú vị: cả hai đều **"không có generic ở runtime"**. Java *xóa* nó đi (từng có lúc biên dịch), PHP *chưa từng có* nó.
- Điểm **khác** cốt lõi: Java **cưỡng chế** (enforced) ngay lúc biên dịch — sai kiểu là build đỏ, không deploy được. PHP chỉ **cảnh báo** qua tool tĩnh — nếu team không chạy PHPStan/Psalm (hoặc để mức lỏng) thì sai kiểu vẫn lọt ra production.

> 🧩 Hệ quả khi chuyển sang Java: bạn sẽ không còn cảm giác "nhét đại gì cũng được rồi sửa sau". Compiler bắt bạn khai báo kiểu chuẩn ngay từ đầu — khó chịu lúc đầu nhưng cứu bạn khỏi cả lớp bug `ClassCastException` mà Laravel hay gặp dưới dạng "Trying to get property of non-object".

---

## 💻 Thực hành code

> Toàn bộ code dưới đây biên dịch được trên **Java 21**.

### Bước 1 — Generic class `Box<T>`

```java
// File: Box.java
public ___ Box<T> { // Điền từ khóa khai báo lớp
    private T content;

    public void set(T content) {
        this.content = content;
    }

    public T get() {
        return content;
    }

    public boolean isEmpty() {
        return content == null;
    }

    @Override
    public String toString() {
        return "Box[" + content + "]";
    }
}
```

```java
Box<String> name = new Box<>();
name.set("Auction #1");
String s = name.get();        // không cần cast — trả thẳng String
System.out.println(s);        // Auction #1

Box<Integer> count = new Box<>();
count.set(5);
// count.set("oops");         // ❌ lỗi biên dịch — bảo vệ kiểu
```

### Bước 2 — Generic method `repeat`

```java
import java.util.ArrayList;
import java.util.List;

public class GenericUtils {

    // <T> đứng trước kiểu trả về List<T>
    public static ___ List<T> repeat(T item, int n) { // Điền type parameter cho phương thức
        List<T> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(item);
        }
        return result;
    }
}
```

```java
List<String> hellos = GenericUtils.repeat("hi", 3);   // T suy ra = String
System.out.println(hellos);                            // [hi, hi, hi]

List<Integer> zeros = GenericUtils.repeat(0, 4);       // T suy ra = Integer
System.out.println(zeros);                             // [0, 0, 0, 0]
```

### Bước 3 — Bounded type parameter: `max` trên `Comparable`

```java
import java.util.List;

public class GenericUtils {

    // T phải so sánh được với chính nó → mới gọi compareTo được
    public static <T ___ Comparable<T>> T max(List<T> list) { // Điền từ khóa giới hạn kiểu (bound)
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Danh sách rỗng, không có max");
        }
        T best = list.get(0);
        for (T item : list) {
            if (item.compareTo(best) > 0) {   // hợp lệ nhờ bound Comparable<T>
                best = item;
            }
        }
        return best;
    }
}
```

```java
System.out.println(GenericUtils.max(List.of(3, 9, 1, 7)));        // 9
System.out.println(GenericUtils.max(List.of("a", "z", "m")));    // z
// GenericUtils.max(List.of(new Object()));   // ❌ Object không Comparable → lỗi biên dịch
```

### Bước 4 — PECS: hàm `copy`

```java
import java.util.List;

public class GenericUtils {

    // dest = consumer → super ; src = producer → extends
    public static <T> void copy(List<? ___ T> dest, List<? ___ T> src) { // Điền wildcard cho Consumer và Producer
        if (dest.size() < src.size()) {
            throw new IndexOutOfBoundsException("dest nhỏ hơn src");
        }
        for (int i = 0; i < src.size(); i++) {
            dest.set(i, src.get(i));   // đọc T từ src, ghi T vào dest
        }
    }
}
```

```java
List<Object> dest = new java.util.ArrayList<>(List.of(0, 0, 0));
List<Integer> src = List.of(10, 20, 30);
GenericUtils.copy(dest, src);          // chép Integer (src) vào Object (dest)
System.out.println(dest);              // [10, 20, 30]
```

### Bước 5 — Vượt giới hạn erasure: tạo mảng generic an toàn

Vì erasure cấm `new T[]`, ta phải **truyền thêm thông tin kiểu**. Hai cách chuẩn:

```java
import java.util.function.IntFunction;

public class ArrayFactory {

    // Cách 1: truyền Class<T> để dùng reflection tạo mảng
    @SuppressWarnings("unchecked")
    public static <T> T[] newArrayViaClass(Class<T> type, int size) {
        return (T[]) java.lang.reflect.Array.newInstance(type, size);
    }

    // Cách 2 (idiomatic, không cần reflection): truyền tham chiếu constructor mảng
    public static <T> T[] newArray(IntFunction<T[]> generator, int size) {
        return generator.apply(size);
    }
}
```

```java
String[] a = ArrayFactory.newArray(String[]::new, 3);   // gọn, type-safe
System.out.println(a.length);   // 3

Integer[] b = ArrayFactory.newArrayViaClass(Integer.class, 5);
System.out.println(b.length);   // 5
```

> 💡 Cách `IntFunction<T[]>` với `String[]::new` chính là kỹ thuật `Stream.toArray(String[]::new)` dùng — không cần reflection, không cảnh báo unchecked.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng `List<String>` là `List<Object>`.** Generics **bất biến** — `List<String>` KHÔNG phải con của `List<Object>`. Muốn linh hoạt phải dùng wildcard.
- **Cố `add` vào `List<? extends T>`.** Không được (trừ `null`). `? extends` chỉ để ĐỌC. Nhầm chiều PECS là lỗi kinh điển.
- **Mong `get` ra kiểu cụ thể từ `List<? super T>`.** Chỉ lấy được `Object`. `? super` chỉ để GHI.
- **Viết raw type** (`List list = ...`) trong code mới — tắt hết bảo vệ generics, sinh unchecked warning, dễ `ClassCastException` ngầm.
- **Thử `new T()`, `new T[]`, `obj instanceof T`, hay `static T field`** — đều cấm vì erasure. Phải truyền `Class<T>` hoặc factory (`Supplier<T>`, `IntFunction<T[]>`).
- **Lạm dụng `@SuppressWarnings("unchecked")`** để bịt cảnh báo thay vì sửa gốc — che giấu lỗi kiểu thật sự, đẩy `ClassCastException` về runtime.
- **Quên `Comparable<T>` cần đúng kiểu.** `<T extends Comparable>` (raw) hoạt động nhưng yếu; nên viết `<T extends Comparable<T>>` để chặt chẽ.
- **Nhầm `extends` của bound với kế thừa class.** Trong generics, `extends` dùng cho cả interface (`<T extends Comparable<T>>`), không phải `implements`.

---

## 🚀 Liên hệ Spring Boot / Production

- **Spring Data JPA** là generics tận xương: `JpaRepository<Auction, Long>` chính là `Repository<T, ID>` mở rộng. Bạn khai báo interface, Spring sinh implement lúc runtime — không thể hiểu nó nếu mơ hồ về `<T, ID>`.
- **`ResponseEntity<T>`, `Optional<T>`, `List<T>`** xuất hiện ở mọi controller/service. Trả `ResponseEntity<AuctionDto>` hay `ResponseEntity<List<AuctionDto>>` đều dựa trên generics.
- **`RestTemplate`/`WebClient` + `ParameterizedTypeReference<T>`.** Vì type erasure xóa `List<AuctionDto>` thành `List`, khi deserialize JSON về một generic type bạn phải dùng `new ParameterizedTypeReference<List<AuctionDto>>() {}` — một mẹo "siêu kiểu ẩn danh" (anonymous subclass) để *giữ* thông tin generic vượt qua erasure. Hiểu erasure mới hiểu vì sao cần nó.
- **Jackson serialize generic.** Cùng lý do erasure, `objectMapper.readValue(json, List.class)` mất kiểu phần tử; phải dùng `TypeReference<List<AuctionDto>>(){}`.
- **`@Bean` factory & generic injection.** Spring 4+ phân giải bean theo generic type (`Converter<Auction, AuctionDto>`), tận dụng metadata generic compiler vẫn lưu trong signature của class/field.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp mạch Auction API. Hôm nay ta trừu tượng hóa tầng lưu trữ thành một **`Repository<T, ID>` generic** — viết một lần, tái sử dụng cho cả `Auction` lẫn `Bid`. Đây chính là phôi thai của Spring Data `JpaRepository<T, ID>` mà các ngày sau sẽ dùng.

**Nhiệm vụ Day 12:**

1. Định nghĩa interface `Repository<T, ID>` generic với 4 method: `save`, `findById` (trả `Optional<T>`), `findAll` (trả `List<T>`), `deleteById`.
2. Cài `InMemoryRepository<T, ID>` dùng `Map<ID, T>` (`LinkedHashMap` để giữ thứ tự chèn).
3. Tạo `record Auction` và `record Bid`, rồi dùng **cùng** `InMemoryRepository` cho cả hai — chứng minh tính tái sử dụng của generics.

```java
// File: Repository.java
import java.util.List;
import java.util.Optional;

public ___ Repository<T, ID> { // Điền từ khóa khai báo interface
    T save(ID id, T entity);            // lưu (hoặc cập nhật) entity theo id
    Optional<T> findById(ID id);        // tìm theo id — Optional tránh trả null
    List<T> findAll();                  // lấy tất cả
    boolean deleteById(ID id);          // xóa, trả true nếu có xóa
}
```

```java
// File: InMemoryRepository.java
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryRepository<T, ID> ___ Repository<T, ID> { // Điền từ khóa thực thi interface

    // Map<ID, T>: khóa là ID generic, giá trị là entity T generic
    private final Map<ID, T> store = new LinkedHashMap<>();

    @Override
    public T save(ID id, T entity) {
        store.put(id, entity);          // put: thêm mới hoặc ghi đè
        return entity;
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));  // null-safe
    }

    @Override
    public List<T> findAll() {
        return new ArrayList<>(store.values());     // copy phòng thủ
    }

    @Override
    public boolean deleteById(ID id) {
        return store.remove(id) != null;            // remove trả về giá trị cũ (hoặc null)
    }
}
```

```java
// File: Auction.java và Bid.java
import java.math.BigDecimal;

public record Auction(Long id, String title, BigDecimal startPrice) {}

public record Bid(Long id, Long auctionId, String bidder, BigDecimal amount) {}
```

```java
// File: AuctionApp.java — chứng minh cùng một repo generic dùng cho 2 entity khác nhau
import java.math.BigDecimal;

public class AuctionApp {
    public static void main(String[] args) {

        // Repo cho Auction: T = Auction, ID = Long
        Repository<Auction, Long> auctions = new InMemoryRepository<>();
        auctions.save(1L, new Auction(1L, "Đồng hồ cổ", new BigDecimal("1000")));
        auctions.save(2L, new Auction(2L, "Tranh sơn dầu", new BigDecimal("5000")));

        // Repo cho Bid: cùng class InMemoryRepository, T = Bid, ID = Long
        Repository<Bid, Long> bids = new InMemoryRepository<>();
        bids.save(10L, new Bid(10L, 1L, "Alice", new BigDecimal("1100")));
        bids.save(11L, new Bid(11L, 1L, "Bob", new BigDecimal("1250")));

        // findById trả Optional<T> — không lo NullPointerException
        auctions.findById(1L)
                .ifPresent(a -> System.out.println("Tìm thấy: " + a.title()));

        System.out.println("Tổng số phiên: " + auctions.findAll().size());   // 2
        System.out.println("Tổng số bid:  " + bids.findAll().size());        // 2

        bids.deleteById(10L);
        System.out.println("Còn lại bid:  " + bids.findAll().size());        // 1
    }
}
```

> 🎯 Điểm rút ra: **một** `InMemoryRepository<T, ID>` phục vụ **mọi** entity — không phải viết `AuctionRepository`, `BidRepository` riêng với code trùng lặp. Đây đúng tinh thần generics: *viết một lần, dùng kiểu nào cũng được, mà vẫn an toàn kiểu lúc biên dịch.*

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Generics là gì và giải quyết vấn đề gì?**
> **Đáp:** Generics là cơ chế cho phép tham số hóa kiểu (parameterize types) — viết class/method/interface làm việc với "một kiểu T bất kỳ". Nó mang lại type safety lúc biên dịch (bắt lỗi kiểu sớm thay vì `ClassCastException` lúc runtime), bỏ ép kiểu thủ công, và cho phép tái sử dụng code cho nhiều kiểu khác nhau.

**Q2: Quy ước đặt tên `T`, `E`, `K`, `V`, `N` nghĩa là gì?**
> **Đáp:** Chỉ là quy ước (không bắt buộc): `T`=Type (kiểu chung), `E`=Element (phần tử collection), `K`=Key, `V`=Value (map), `N`=Number. Giúp đọc code dễ hiểu ý đồ.

**Q3: Generic method khai báo type parameter ở đâu?**
> **Đáp:** Ngay **trước kiểu trả về**: `public static <T> T identity(T x)`. Compiler thường tự suy luận T từ tham số nên hiếm khi phải chỉ định tường minh.

**Q4: Bounded type parameter là gì? Cho ví dụ.**
> **Đáp:** Ràng buộc T phải là một kiểu nhất định hoặc con của nó, bằng `extends`. Ví dụ `<T extends Number>` cho phép gọi method của `Number` bên trong. Multiple bound nối bằng `&`: `<T extends Number & Comparable<T>>` (class phải đứng trước interface).

**Q5: Phân biệt `? extends T` và `? super T`. Khi nào dùng cái nào?**
> **Đáp:** `? extends T` (covariant, upper bound) dùng để **ĐỌC** — lấy ra chắc chắn là T, nhưng không add được (trừ null). `? super T` (contravariant, lower bound) dùng để **GHI** — add T an toàn, nhưng đọc ra chỉ được Object. Theo PECS: Producer Extends, Consumer Super.

### Mức Senior

**Q6: Type erasure là gì và tại sao Java chọn nó?**
> **Đáp:** Type erasure là việc compiler **xóa** thông tin kiểu generic sau khi biên dịch, thay `T` bằng bound (hoặc `Object` nếu unbounded) và chèn cast tự động. Hệ quả: `List<String>` và `List<Integer>` cùng class lúc runtime, không có `List<String>.class`. Java chọn erasure vì **migration compatibility** — để code generic mới và bytecode raw từ thời Java 1.4 cùng chạy trên một JVM mà không phải biên dịch lại.

**Q7: Vì sao không thể `new T()`, `new T[]`, `instanceof T`, hay khai báo `static T field`?**
> **Đáp:** Tất cả đều cần thông tin kiểu T **lúc runtime**, mà erasure đã xóa mất. `new T()` không biết constructor nào để gọi; `new T[]` không biết kiểu mảng; `instanceof T` không còn T để so; `static T field` thì T thuộc về instance còn static dùng chung nên vô nghĩa. Giải pháp: truyền `Class<T>` (reflection) hoặc factory như `Supplier<T>`, `IntFunction<T[]>`.

**Q8: Bridge method là gì? Khi nào compiler sinh ra nó?**
> **Đáp:** Khi một lớp con override method generic của lớp cha với kiểu cụ thể (ví dụ `Node<String>` override `set(T)` thành `set(String)`), sau erasure lớp cha có `set(Object)` còn lớp con có `set(String)` — chữ ký khác nhau nên đáng lẽ không override. Compiler tự sinh **bridge method** `set(Object)` trong lớp con (gọi sang `set(String)`) để giữ tính đa hình. Thấy được bằng `javap`.

**Q9: Vì sao `List<String>` không phải là `List<Object>`, dù `String` là con của `Object`?**
> **Đáp:** Vì generics **bất biến (invariant)**. Nếu cho phép, ta có thể gán `List<String>` cho biến `List<Object>` rồi `add(someInteger)` — phá vỡ an toàn kiểu của list gốc. Để nới lỏng có kiểm soát mà vẫn an toàn, Java dùng wildcard (`? extends`/`? super`).

**Q10: Trong Spring, vì sao deserialize JSON về `List<AuctionDto>` cần `ParameterizedTypeReference`/`TypeReference`?**
> **Đáp:** Vì type erasure xóa `List<AuctionDto>` thành `List` lúc runtime, thư viện (Jackson) không biết kiểu phần tử để map. `ParameterizedTypeReference`/`TypeReference` là một anonymous subclass — kỹ thuật "super type token" — giữ lại thông tin generic trong signature của class con để đọc qua reflection, vượt qua giới hạn erasure.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được 3 lợi ích của generics (type safety, bỏ cast, tái sử dụng)
- [ ] Viết được generic class, generic method, bounded type parameter
- [ ] Hiểu và áp dụng đúng `? extends` / `? super` theo PECS
- [ ] Giải thích được type erasure và các giới hạn của nó (`new T()`, `new T[]`, bridge method)
- [ ] Đối chiếu được generics Java vs docblock/PHPStan của PHP
- [ ] Điền các chỗ trống `___` trong code thực hành ở trên
- [ ] Hoàn thành Mini Project: `Repository<T, ID>` + `InMemoryRepository` dùng cho cả Auction và Bid
- [ ] Trả lời được 10 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Generics" (Lesson đầy đủ: Type Inference, Wildcards, Type Erasure, Restrictions)
- Baeldung — "The Basics of Java Generics", "Java Generics PECS", "Type Erasure in Java"
- Sách *Effective Java* (Joshua Bloch) — Item 26-33 (chương Generics; đọc kỹ Item 28 "list vs array", Item 31 "bounded wildcards/PECS")
- Javadoc — `java.util.Collections.copy`, `java.lang.reflect.Array.newInstance`, `org.springframework.core.ParameterizedTypeReference`
- JLS (Java Language Specification) §4.5 (Parameterized Types) & §8.4.8.3 (bridge methods) — đọc khi cần đào tận gốc
