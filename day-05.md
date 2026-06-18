# Day 05 - String Pool

> **Giai đoạn:** Java Foundation & JVM
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **String là immutable (bất biến)** trong Java và *vì sao* lại thiết kế như vậy — đây là gốc rễ của mọi thứ hôm nay.
- Nắm vững **String Constant Pool**: nó là gì, nằm ở đâu trong bộ nhớ, hoạt động ra sao và `intern()` làm gì.
- Phân biệt **literal `"abc"` vs `new String("abc")`** và hiểu thấu đáo `==` (so reference) vs `equals()` (so nội dung) — câu hỏi phỏng vấn kinh điển.
- Phân biệt **String vs StringBuilder vs StringBuffer**, biết khi nào dùng cái nào và vì sao **không bao giờ nối chuỗi bằng `+=` trong vòng lặp**.
- Biết dùng **Text Block** (`"""..."""`) để viết JSON/SQL/HTML nhiều dòng cho gọn, đẹp.
- Đối chiếu với cách PHP xử lý chuỗi (mutable, copy-on-write, toán tử `.`, `==` vs `===`) để hiểu nhanh nhờ cái đã biết.

---

## 🧠 Lý thuyết cốt lõi

### 1. String là immutable — nền tảng của mọi thứ

Trong Java, một khi `String` đã được tạo, **nội dung của nó không bao giờ thay đổi**. Mọi thao tác trông như "sửa chuỗi" thực ra đều **tạo ra một `String` mới**.

```java
String s = "Java";
s = s.concat(" 21");   // KHÔNG sửa "Java", mà tạo object mới "Java 21" rồi cho s trỏ tới
// Object "Java" cũ vẫn còn nguyên trong bộ nhớ (cho tới khi bị GC dọn)
```

Bên trong, `String` bọc một mảng `byte[]` (trước Java 9 là `char[]`) được khai báo `private final`, và bản thân class `String` cũng là `final` (không thể kế thừa để phá vỡ tính bất biến). Không có method nào cho phép ghi đè mảng đó.

**Vì sao Java thiết kế String bất biến? Bốn lý do cốt lõi:**

| Lý do | Giải thích |
|---|---|
| **Bảo mật (security)** | String thường làm tham số nhạy cảm: tên file, đường dẫn, URL, tên class cho ClassLoader, username/host trong kết nối DB. Nếu String khả biến, một thread khác có thể "đổi ruột" giá trị sau khi đã kiểm tra quyền (kiểu tấn công TOCTOU). Bất biến ⇒ đã kiểm tra rồi thì không ai sửa được nữa. |
| **Cache hashCode** | `hashCode()` của String được **tính một lần rồi cache lại** trong một field. Vì nội dung không đổi nên hash cũng không đổi → tính lại làm gì. Đây là lý do String là **key lý tưởng cho `HashMap`/`HashSet`**: tra cứu cực nhanh. |
| **An toàn đa luồng (thread-safe)** | Bất biến nghĩa là *mặc nhiên* thread-safe. Nhiều thread đọc chung một String mà không cần `synchronized`, không sợ race condition. |
| **Cho phép String Pool** | Vì không thể sửa, nhiều biến có thể **chia sẻ chung một object** trong pool mà không sợ biến này "vô tình" sửa làm hỏng biến kia. (Xem mục 2.) |

> 💡 Hãy ghi nhớ: **immutable là nguyên nhân, String Pool là hệ quả.** Vì String không thể bị sửa nên Java mới dám gom các literal trùng nhau lại dùng chung. Nếu String khả biến, việc chia sẻ này sẽ là thảm họa.

### 2. String Constant Pool — vùng cache literal

**String Pool** (còn gọi String Constant Pool / String intern pool) là một vùng nhớ đặc biệt do JVM quản lý, dùng để **cache lại các String literal**. Mục tiêu: literal nào trùng nội dung thì chỉ tồn tại **một** object duy nhất, tiết kiệm bộ nhớ.

```java
String a = "hello";
String b = "hello";   // KHÔNG tạo object mới — JVM thấy "hello" đã có trong pool, trả về cùng reference
// a và b trỏ tới CÙNG một object
System.out.println(a == b);   // true
```

**Pool nằm ở đâu?**
- **Từ Java 7 trở đi**: String Pool nằm trong **Heap** (vùng nhớ chính, được GC dọn dẹp). Nhờ đó pool có thể lớn và các literal không còn dùng có thể bị thu hồi.
- **Trước Java 7** (Java 6 trở về trước): pool nằm trong **PermGen** — vùng cố định, nhỏ, dễ tràn (`OutOfMemoryError: PermGen space`) nếu `intern()` quá nhiều. Đây là một lý do PermGen bị bỏ (thay bằng Metaspace từ Java 8).

```
                          HEAP
   ┌──────────────────────────────────────────────────┐
   │   String Constant Pool                            │
   │   ┌──────────────┐                                │
   │   │  "hello"     │ ◄──── a                         │
   │   │  (1 object)  │ ◄──── b                         │
   │   └──────────────┘                                │
   │                                                   │
   │   (vùng heap thường, ngoài pool)                  │
   │   ┌──────────────┐                                │
   │   │  "hello"     │ ◄──── c = new String("hello")  │
   │   │  (object MỚI)│                                │
   │   └──────────────┘                                │
   └──────────────────────────────────────────────────┘
```

### 3. literal vs `new String()` — tạo ra bao nhiêu object?

Đây là phần "ăn điểm" khi phỏng vấn. Phải phân biệt rạch ròi:

```java
String x = "abc";              // (1) lấy/đặt vào POOL
String y = "abc";              // (2) lấy lại từ pool → CÙNG object với x
String z = new String("abc");  // (3) LUÔN tạo object MỚI trên heap (ngoài pool)
```

- `"abc"` (literal): JVM tra pool, có rồi thì trả về reference cũ, chưa có thì tạo và đưa vào pool. ⇒ `x == y` là **true**.
- `new String("abc")`: từ khóa `new` **bắt buộc** cấp phát một object mới trên heap, hoàn toàn tách biệt khỏi pool. ⇒ `x == z` là **false**, dù nội dung giống hệt.

> ⚠️ Câu hỏi bẫy: `String z = new String("abc")` tạo ra **bao nhiêu** object? Đáp án: **1 hoặc 2**. Literal `"abc"` cần một object trong pool — nếu pool *chưa có* "abc" thì tạo thêm 1 object pool (tổng 2); nếu pool *đã có sẵn* "abc" thì chỉ tạo 1 object mới trên heap (object pool tái dùng). `new` luôn tạo đúng 1 object heap.

**Sơ đồ tham chiếu:**

```
   x ──┐
       ├──►  ┌───────────────┐   (trong String Pool)
   y ──┘     │   "abc"       │
             └───────────────┘

   z ───────►  ┌───────────────┐   (object riêng trên heap, NGOÀI pool)
               │   "abc"       │
               └───────────────┘

   x == y  →  true   (cùng object pool)
   x == z  →  false  (khác object)
   x.equals(z) → true (cùng NỘI DUNG)
```

**`==` vs `equals()`:**
- `==` so sánh **reference** (hai biến có trỏ vào cùng một ô nhớ không).
- `.equals()` (String đã override) so sánh **nội dung** (từng ký tự).

> 💡 **Quy tắc vàng:** Khi so sánh chuỗi trong Java, **luôn dùng `equals()`** (hoặc `equals` ngược để tránh NPE: `"abc".equals(bien)`, hoặc `Objects.equals(a, b)`). Dùng `==` cho String gần như luôn là bug — chỉ dùng khi bạn *cố ý* so reference.

**`intern()` làm gì?**

`s.intern()` đưa nội dung của `s` vào pool (nếu chưa có) và **trả về reference của object trong pool**. Nhờ đó ta có thể "kéo" một String tạo bằng `new` về dùng chung với literal:

```java
String z = new String("abc");
String w = z.intern();         // w trỏ tới object "abc" TRONG pool
System.out.println(z == w);    // false  (z là object heap riêng)
System.out.println("abc" == w);// true   (w chính là object pool)
```

> ⚠️ `intern()` hữu ích để gom các chuỗi lặp lại (ví dụ đọc hàng triệu dòng có nhiều giá trị trùng) nhằm tiết kiệm RAM, nhưng **lạm dụng** dễ làm chậm và tốn vùng pool. Đa số trường hợp **không cần tự gọi `intern()`** — để JVM lo.

### 4. String vs StringBuilder vs StringBuffer

Vì String bất biến, mỗi lần "nối" là một object mới → tốn kém nếu làm nhiều lần. Java cho hai lớp **khả biến** để xây chuỗi hiệu quả:

| Lớp | Khả biến? | Thread-safe? | Tốc độ | Khi nào dùng |
|---|---|---|---|---|
| **String** | ❌ Bất biến | ✅ (vì bất biến) | — | Giá trị cố định, làm key, truyền tham số |
| **StringBuilder** | ✅ Khả biến | ❌ Không (không `synchronized`) | **Nhanh nhất** | **Mặc định** khi cần xây chuỗi trong 1 thread |
| **StringBuffer** | ✅ Khả biến | ✅ (mọi method `synchronized`) | Chậm hơn (do khóa) | Khi *thực sự* nhiều thread cùng ghi vào 1 buffer |

```java
StringBuilder sb = new StringBuilder();
sb.append("Item: ").append("Đồng hồ cổ");   // sửa TRỰC TIẾP trên buffer, không tạo object mới mỗi lần
sb.append(" | Giá: ").append(1500);
String result = sb.toString();                // chỉ tạo String 1 lần ở cuối
```

> 💡 **Quy tắc thực dụng:** Hầu hết code chạy trong một thread → dùng **StringBuilder**. Chỉ dùng **StringBuffer** khi bạn *chia sẻ một đối tượng builder* giữa nhiều thread (rất hiếm — thường ta thiết kế để mỗi thread có builder riêng, vẫn nhanh hơn). `StringBuffer` về cơ bản là "StringBuilder + `synchronized`".

### 5. Nối chuỗi trong vòng lặp — cái bẫy hiệu năng O(n²)

```java
// ❌ SAI: nối bằng += trong vòng lặp
String s = "";
for (int i = 0; i < 100_000; i++) {
    s += i;   // mỗi vòng tạo MỘT String mới = copy lại toàn bộ ký tự cũ + thêm phần mới
}
```

Mỗi lần `s += i`, JVM phải tạo một object mới chứa toàn bộ nội dung cũ rồi cộng thêm. Vòng thứ `n` phải copy ~`n` ký tự ⇒ tổng công việc là `1 + 2 + ... + n ≈ n²/2`. Với 100.000 vòng, đó là **hàng tỷ phép copy ký tự** và hàng trăm nghìn object rác cho GC dọn.

```java
// ✅ ĐÚNG: dùng StringBuilder
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 100_000; i++) {
    sb.append(i);   // append vào buffer khả biến, KHÔNG copy lại từ đầu, độ phức tạp ~O(n)
}
String s = sb.toString();
```

> ⚠️ **Lưu ý tinh tế:** Nối chuỗi *đơn giản, một dòng* như `"a" + b + "c"` **không phải** vấn đề — từ **Java 9**, compiler dịch nó qua `invokedynamic` + `StringConcatFactory` rất tối ưu (trước Java 9 thì compiler tự thay bằng `StringBuilder`). Nhưng phép tối ưu này **không vượt qua được ranh giới vòng lặp**: trong loop, mỗi lần lặp là một biểu thức `+` riêng → vẫn sinh object trung gian. Vì vậy: **trong vòng lặp luôn dùng `StringBuilder`.**

### 6. Text Block — chuỗi nhiều dòng (Java 15+)

Trước đây viết JSON/SQL/HTML nhiều dòng rất khổ vì phải `\n` và escape dấu `"`. Từ **Java 15** (chính thức), **Text Block** dùng ba dấu nháy kép `"""` giải quyết gọn:

```java
String json = """
        {
            "item": "Đồng hồ cổ",
            "currentPrice": 1500,
            "bids": 7
        }
        """;
```

- Giữ nguyên xuống dòng và định dạng, **không cần `\n`**.
- **Không cần escape** dấu `"` bên trong.
- JVM tự "cắt lề" (incidental whitespace) dựa trên dòng thụt ít nhất → code thụt vào cho đẹp mà chuỗi vẫn sạch.

> 💡 Cực hữu ích cho: câu SQL dài, payload JSON test, template HTML/email. Vẫn là một `String` bất biến bình thường — chỉ là *cách viết* tiện hơn.

---

## 🔁 Đối chiếu với Laravel/PHP

Bạn quen PHP nên hãy neo vào đó — nhưng cẩn thận, cơ chế chuỗi của hai ngôn ngữ **khác nhau căn bản**:

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| Tính chất chuỗi | **Khả biến (mutable)** — sửa được tại chỗ `$s[0] = 'X';` | **Bất biến (immutable)** — không sửa được nội dung |
| Cơ chế bộ nhớ | **Copy-on-write**: gán `$b = $a` chưa copy, chỉ copy khi một bên bị sửa | Không copy-on-write; gán chỉ là copy *reference* |
| Toán tử nối | Dấu chấm `.` và `.=` (ví dụ `$s .= "x";`) | Dấu cộng `+` và `+=` |
| String Pool | **Không có** pool literal kiểu Java | Có **String Constant Pool** trong Heap |
| Xây chuỗi lớn | Nối `.=` (PHP tối ưu được vì mutable), hoặc `implode()` | Dùng **StringBuilder** (vì String bất biến) |
| So sánh **nội dung** | `==` (lỏng, có ép kiểu) hoặc `===` (chặt, đúng kiểu) | `.equals()` |
| So sánh **danh tính/kiểu** | `===` so cả kiểu lẫn giá trị | `==` so **reference** (địa chỉ object) |

**Cái bẫy chuyển ngữ quan trọng nhất — so sánh chuỗi:**

```php
// PHP: so sánh hai chuỗi
$a = "abc";
$b = "abc";
var_dump($a == $b);   // true  — so giá trị
var_dump($a === $b);  // true  — so giá trị + kiểu (đều là string)
```

```java
// Java: cùng ý đồ "so giá trị" nhưng phải dùng equals!
String a = new String("abc");
String b = new String("abc");
System.out.println(a == b);        // false ❗ — == so REFERENCE, hai object khác nhau
System.out.println(a.equals(b));   // true   — equals so NỘI DUNG
```

> ⚠️ **Lỗi kinh điển của dev PHP mới sang Java:** dùng `==` để so chuỗi vì tưởng nó giống `===` của PHP. Trong PHP `===` so *giá trị + kiểu*; trong Java `==` so *địa chỉ ô nhớ*. Hai cái hoàn toàn khác. Ở Java, "so giá trị" = `equals()`.

> 🧩 Hệ quả tư duy: ở PHP bạn vô tư `$s .= ...` trong vòng lặp vì chuỗi mutable + copy-on-write nên rẻ. Ở Java, `s += ...` trong vòng lặp là **O(n²)** vì mỗi lần tạo object mới — đây là khác biệt sống còn về hiệu năng, phải đổi thói quen sang `StringBuilder`.

---

## 💻 Thực hành code

### Bài 1 — Chứng minh `==` vs `equals()` vs `intern()`

```java
// File: StringIdentityDemo.java  (Java 21)
public class StringIdentityDemo {
    public static void main(String[] args) {
        // --- Nhóm literal: dùng chung object trong String Pool ---
        String a = "abc";
        String b = "abc";                 // lấy lại từ pool -> cùng object với a
        System.out.println("a == b          : " + (a == b));          // true  (cùng reference pool)
        System.out.println("a.equals(b)     : " + a.equals(b));       // true  (cùng nội dung)

        // --- new String: luôn tạo object MỚI ngoài pool ---
        String c = new String("abc");     // object riêng trên heap
        System.out.println("a == c          : " + (a == c));          // false (khác reference)
        System.out.println("a.equals(c)     : " + a.equals(c));       // true  (cùng nội dung)

        // --- intern(): kéo nội dung về object trong pool ---
        String d = c.intern();            // d trỏ tới object "abc" TRONG pool (chính là a)
        System.out.println("a == d          : " + (a == d));          // true  (cùng object pool)
        System.out.println("c == d          : " + (c == d));          // false (c vẫn là object heap riêng)

        // --- Ghép literal là hằng số: compiler gộp tại thời điểm biên dịch ---
        String e = "ab" + "c";            // "ab"+"c" đều là hằng -> compiler tính ra "abc" và đặt vào pool
        System.out.println("a == e          : " + (a == e));          // true  (compile-time constant folding)

        // --- Ghép có biến: tính lúc chạy -> object MỚI, không vào pool tự động ---
        String pre = "ab";
        String f = pre + "c";             // pre là biến -> ghép lúc runtime -> object mới ngoài pool
        System.out.println("a == f          : " + (a == f));          // false
        System.out.println("a == f.intern() : " + (a == f.intern())); // true (intern kéo về pool)
    }
}
```

**Kết quả mong đợi (giải thích ngắn từng dòng):**

```
a == b          : true     // hai literal "abc" dùng chung object pool
a.equals(b)     : true     // dĩ nhiên, cùng nội dung
a == c          : false    // new String tạo object mới -> khác reference
a.equals(c)     : true     // nội dung vẫn giống
a == d          : true     // intern() trả về object trong pool == a
c == d          : false    // c là object heap, d là object pool
a == e          : true     // "ab"+"c" là hằng -> gộp lúc biên dịch vào pool
a == f          : false    // pre+"c" tính lúc chạy -> object mới
a == f.intern() : true     // intern() đưa "abc" runtime về pool, trùng a
```

### Bài 2 — Đo hiệu năng nối chuỗi: `+=` vs `StringBuilder`

```java
// File: StringConcatBenchmark.java  (Java 21)
public class StringConcatBenchmark {

    static final int N = 100_000;   // 100 nghìn vòng

    public static void main(String[] args) {
        // (Trong production nên dùng JMH để loại nhiễu JIT, ở đây làm nhanh để cảm nhận)

        // --- Cách SAI: nối bằng += trong vòng lặp -> O(n^2) ---
        long t1 = System.nanoTime();
        String s = "";
        for (int i = 0; i < N; i++) {
            s += i;   // mỗi vòng tạo String mới = copy lại toàn bộ phần trước
        }
        long t2 = System.nanoTime();
        System.out.printf("String +=        : %,d ms (độ dài cuối = %d)%n",
                (t2 - t1) / 1_000_000, s.length());

        // --- Cách ĐÚNG: dùng StringBuilder -> ~O(n) ---
        long t3 = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < N; i++) {
            sb.append(i);   // append vào buffer khả biến, không copy lại từ đầu
        }
        String s2 = sb.toString();
        long t4 = System.nanoTime();
        System.out.printf("StringBuilder    : %,d ms (độ dài cuối = %d)%n",
                (t4 - t3) / 1_000_000, s2.length());

        // Kiểm chứng hai cách cho cùng kết quả
        System.out.println("Hai kết quả giống nhau? " + s.equals(s2));
    }
}
```

> 💡 Chạy thử và quan sát: `String +=` thường mất **hàng trăm tới hàng nghìn ms**, còn `StringBuilder` chỉ **vài ms** — chênh nhau hàng trăm lần. Tăng `N` lên `200_000` thì khoảng cách càng giãn ra đúng theo bản chất `O(n²)` vs `O(n)`.

### Bài 3 — Text Block

```java
// File: TextBlockDemo.java  (Java 21)
public class TextBlockDemo {
    public static void main(String[] args) {
        String sql = """
                SELECT id, name, current_price
                FROM auctions
                WHERE status = 'OPEN'
                ORDER BY current_price DESC
                """;
        System.out.println(sql);   // giữ nguyên xuống dòng, không cần \n hay escape
    }
}
```

> ✅ **Bài tập tự giải thích:** Trong Bài 1, vì sao `a == e` là `true` nhưng `a == f` là `false`, dù cả hai đều "ghép ra abc"? (Gợi ý: hằng số biên dịch vs tính lúc chạy.)

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **So chuỗi bằng `==`.** Lỗi phổ biến nhất của dev mới (đặc biệt từ PHP). `==` so reference, không so nội dung → khi gặp `new String` hoặc chuỗi tạo lúc runtime sẽ ra `false` dù nội dung giống. **Luôn dùng `equals()`.**
- **`equals()` gây `NullPointerException`.** `bien.equals("abc")` ném NPE nếu `bien == null`. Viết ngược: `"abc".equals(bien)` hoặc dùng `Objects.equals(a, b)` (null-safe).
- **Nối chuỗi bằng `+=` trong vòng lặp.** Tưởng vô hại như PHP, nhưng là `O(n²)` và sinh rác cho GC. Dùng `StringBuilder`.
- **Tưởng `String.replace`/`trim`/`toUpperCase` "sửa" chuỗi gốc.** Chúng **trả về String mới**; chuỗi gốc không đổi. `s.trim();` mà không gán lại (`s = s.trim();`) là vô nghĩa.
- **Lạm dụng `intern()`.** Gọi `intern()` bừa bãi cho hàng triệu chuỗi *khác nhau* không giúp gì mà còn làm phình pool và chậm. Chỉ dùng khi có nhiều chuỗi **trùng lặp** cần gom.
- **Dùng `StringBuffer` "cho chắc".** Hầu hết trường hợp một thread → `StringBuffer` chỉ làm chậm vì khóa thừa. Mặc định nên là `StringBuilder`.
- **Quên `final` của String là về reference, không phải nội dung.** Thực ra String *vốn đã* bất biến; nhưng nhầm "biến String" với "nội dung String" dẫn tới hiểu sai khi đọc code.

---

## 🚀 Liên hệ Spring Boot / Production

- **Log và String concatenation.** Đừng `log.debug("user=" + user + " order=" + order)` — chuỗi vẫn bị ghép *kể cả khi log level tắt*. Dùng placeholder của SLF4J: `log.debug("user={} order={}", user, order)` — chỉ ghép khi thực sự cần in.
- **String là key trong `@Cacheable`, `HashMap`, Redis.** Nhờ String bất biến + cache hashCode mà việc dùng String làm cache key (Caffeine, Redis) cực nhanh và an toàn. Hiểu điều này giúp bạn yên tâm chọn String làm key.
- **`intern()` để tiết kiệm RAM khi parse dữ liệu lớn.** Khi đọc file CSV/JSON hàng triệu dòng có nhiều giá trị lặp (mã quốc gia, trạng thái...), `intern()` các giá trị này gom về một object → giảm đáng kể bộ nhớ. Nhưng đo trước khi tối ưu.
- **Text Block cho SQL/JSON trong test và config.** Viết native query (`@Query`), payload test (`MockMvc`), hay JSON mẫu bằng text block giúp code đọc dễ, ít lỗi escape.
- **Dữ liệu nhạy cảm dùng `char[]`, không dùng `String`.** Mật khẩu nên giữ trong `char[]` để có thể *xóa thủ công* sau khi dùng. Vì String bất biến và nằm trong pool, nó **tồn tại trong bộ nhớ lâu**, dễ bị lộ qua heap dump (đây cũng là lý do `JPasswordField.getPassword()` trả `char[]`).
- **`StringBuilder` khi build response/CSV/report động.** Khi ghép báo cáo, export CSV, dựng message lớn theo vòng lặp → luôn `StringBuilder`.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp nối Day 04 (đã có `Auction` và `Bid`). Hôm nay ta dùng kiến thức String để **in ra bản tóm tắt một phiên đấu giá** đẹp, đúng cách.

**Nhiệm vụ Day 05:**
1. Viết method `buildSummary()` tạo chuỗi mô tả phiên đấu giá nhiều dòng (tên item, giá hiện tại, số lượt bid, người dẫn đầu) bằng **StringBuilder**.
2. Viết thêm bản `buildSummaryTextBlock()` dùng **Text Block** với `formatted(...)` cho gọn, đẹp.
3. So sánh hai cách, ghi chú khi nào nên dùng cái nào.

```java
// File: AuctionSummary.java  (Java 21)
public class AuctionSummary {

    // Day 04 đã có dữ liệu này (ở đây hard-code cho gọn để minh hoạ String)
    private final String itemName  = "Đồng hồ cổ Thuỵ Sĩ";
    private final long   currentPrice = 1_500;     // đơn vị: nghìn đồng
    private final int    totalBids   = 7;
    private final String topBidder   = "ninh.dev";

    // Cách 1: StringBuilder — linh hoạt, thêm dòng có điều kiện dễ
    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== TÓM TẮT PHIÊN ĐẤU GIÁ =====\n");
        sb.append("Sản phẩm     : ").append(itemName).append('\n');
        sb.append("Giá hiện tại : ").append(currentPrice).append(" nghìn đ\n");
        sb.append("Số lượt bid  : ").append(totalBids).append('\n');
        if (totalBids > 0) {
            sb.append("Đang dẫn đầu : ").append(topBidder).append('\n');
        } else {
            sb.append("Đang dẫn đầu : (chưa có lượt bid nào)\n");
        }
        sb.append("=================================");
        return sb.toString();   // chỉ tạo String 1 lần ở cuối
    }

    // Cách 2: Text Block + formatted — đẹp cho khuôn cố định
    public String buildSummaryTextBlock() {
        return """
                ===== TÓM TẮT PHIÊN ĐẤU GIÁ =====
                Sản phẩm     : %s
                Giá hiện tại : %d nghìn đ
                Số lượt bid  : %d
                Đang dẫn đầu : %s
                =================================""".formatted(
                itemName, currentPrice, totalBids,
                totalBids > 0 ? topBidder : "(chưa có lượt bid nào)");
    }

    public static void main(String[] args) {
        AuctionSummary a = new AuctionSummary();
        System.out.println(a.buildSummary());
        System.out.println();
        System.out.println(a.buildSummaryTextBlock());
    }
}
```

> 💡 **Khi nào dùng cái nào?** Text Block hợp với khuôn **cố định** (đẹp, dễ đọc). StringBuilder hợp khi cần **logic động** (vòng lặp danh sách bid, thêm/bớt dòng theo điều kiện). Trong thực tế Auction API, danh sách lịch sử bid biến động → dùng StringBuilder; còn header tĩnh → text block.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Vì sao String trong Java là immutable (bất biến)?**
> **Đáp:** Bốn lý do: (1) **Bảo mật** — String hay dùng làm path, URL, tên class, tham số kết nối; bất biến để sau khi kiểm tra không ai sửa được. (2) **Cache hashCode** — tính một lần dùng nhiều lần, làm key `HashMap` cực nhanh. (3) **Thread-safe** — bất biến nên nhiều thread đọc chung không cần đồng bộ. (4) **Cho phép String Pool** — vì không sửa được nên nhiều biến chia sẻ chung một object an toàn.

**Q2: `==` và `equals()` khác nhau thế nào khi so sánh String?**
> **Đáp:** `==` so sánh **reference** (hai biến có trỏ cùng một object không). `equals()` (String đã override) so sánh **nội dung** từng ký tự. So chuỗi luôn dùng `equals()`; `==` chỉ ra true tình cờ khi hai biến cùng trỏ object pool, dễ thành bug.

**Q3: `new String("abc")` tạo ra bao nhiêu object?**
> **Đáp:** **1 hoặc 2**. Literal `"abc"` cần một object trong pool: nếu pool chưa có thì tạo thêm (tổng 2 object), nếu đã có sẵn thì tái dùng (chỉ 1 object mới trên heap). Từ khóa `new` luôn tạo đúng 1 object heap nằm ngoài pool.

**Q4: String Pool là gì và nằm ở đâu?**
> **Đáp:** Là vùng cache các String literal để literal trùng nội dung dùng chung một object, tiết kiệm bộ nhớ. Từ **Java 7** pool nằm trong **Heap** (được GC dọn); trước đó nằm trong **PermGen** (cố định, dễ tràn). Literal tự động vào pool; `intern()` đưa chuỗi runtime vào pool thủ công.

**Q5: StringBuilder và StringBuffer khác nhau ra sao, khi nào dùng cái nào?**
> **Đáp:** Cả hai đều khả biến để xây chuỗi. **StringBuffer** đồng bộ (mọi method `synchronized`) → thread-safe nhưng chậm hơn. **StringBuilder** không đồng bộ → nhanh hơn, dùng trong một thread. Mặc định nên dùng **StringBuilder**; chỉ dùng StringBuffer khi *thực sự* chia sẻ một builder giữa nhiều thread (hiếm).

### Mức Senior

**Q6: Vì sao không nên nối chuỗi bằng `+=` trong vòng lặp, mà ngoài vòng lặp thì `+` lại ổn?**
> **Đáp:** Vì String bất biến, `s += x` tạo object mới copy lại toàn bộ nội dung cũ; trong vòng lặp `n` lần, tổng công việc là `1+2+...+n ≈ O(n²)` và sinh nhiều rác cho GC → dùng **StringBuilder** (~O(n)). Ngoài vòng lặp, một biểu thức `"a"+b+"c"` được compiler tối ưu: trước Java 9 thay bằng `StringBuilder` ngầm, từ **Java 9** dùng `invokedynamic` + `StringConcatFactory`. Nhưng tối ưu này không vượt qua ranh giới vòng lặp (mỗi lần lặp là một biểu thức riêng), nên loop vẫn phải tự dùng StringBuilder.

**Q7: `intern()` làm gì, và khi nào nên (không nên) dùng?**
> **Đáp:** `s.intern()` đưa nội dung của `s` vào String Pool nếu chưa có, rồi trả về reference của object **trong pool**. Nhờ đó chuỗi tạo bằng `new`/runtime có thể dùng chung object với literal (`==` thành true). Nên dùng khi xử lý dữ liệu lớn có **nhiều giá trị trùng lặp** để giảm RAM. Không nên lạm dụng cho chuỗi đa phần khác nhau — làm phình pool và chậm; trước Java 7 còn dễ gây `OutOfMemoryError: PermGen`.

**Q8: Tại sao String bất biến lại tốt cho `HashMap`, và liên quan gì tới việc cache `hashCode`?**
> **Đáp:** `HashMap` xác định vị trí phần tử dựa trên `hashCode()` của key. Nếu key thay đổi nội dung sau khi đặt vào map, hash sẽ lệch → không tìm lại được. String bất biến đảm bảo hash *không đổi*, và String còn **cache hashCode** (tính lần đầu rồi lưu) nên tra cứu lặp lại rất nhanh, không phải tính lại. Đó là lý do String là key lý tưởng và an toàn cho mọi cấu trúc băm.

**Q9: Vì sao mật khẩu nên giữ trong `char[]` thay vì `String`?**
> **Đáp:** Vì String bất biến và có thể nằm trong String Pool, nó **tồn tại trong bộ nhớ lâu**, không thể chủ động xóa — dễ bị lộ qua heap dump nếu bị tấn công. `char[]` cho phép ghi đè (`Arrays.fill(pwd, '\0')`) ngay sau khi dùng xong, thu hẹp cửa sổ rủi ro. Đây là lý do các API bảo mật (như `JPasswordField.getPassword()`, JDBC một số chỗ) trả/dùng `char[]`.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được 4 lý do String immutable bằng lời của mình
- [ ] Phân biệt rõ String Pool, literal vs `new String`, và `==` vs `equals()`
- [ ] Chạy được Bài 1 và giải thích từng dòng true/false
- [ ] Chạy Bài 2 và thấy chênh lệch hiệu năng `+=` vs StringBuilder
- [ ] Hiểu và viết được Text Block cho SQL/JSON
- [ ] Hoàn thành Mini Project (StringBuilder + Text Block cho Auction summary)
- [ ] Trả lời được 9 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Strings" và Javadoc `java.lang.String`, `StringBuilder`, `StringBuffer`
- Baeldung — "Guide to Java String Pool", "StringBuilder vs StringBuffer", "Java String.intern()"
- JEP 378 — Text Blocks (chính thức từ Java 15)
- JEP 280 — Indify String Concatenation (`invokedynamic`, từ Java 9)
- Sách *Effective Java* (Joshua Bloch) — Item về String và immutability
- OpenJDK source — `java.base/java/lang/String.java` (đọc field `value`, `hash` để thấy cache hashCode)
