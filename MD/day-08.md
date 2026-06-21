# Day 08 - Collections

> **Giai đoạn:** Collections & Generics
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP, đã quen `array` đa năng của PHP, nay cần nắm hệ thống Collections rạch ròi và type-safe của Java.

---

## 🎯 Mục tiêu ngày hôm nay

- Vẽ và hiểu **cây phân cấp** của Java Collections Framework: `Iterable → Collection → List/Set/Queue`, và **`Map` là nhánh riêng** không kế thừa `Collection`.
- Phân biệt rạch ròi **List vs Set vs Queue vs Map**: cho phép trùng không? có thứ tự không? key-value không?
- Biết **chọn đúng implementation** (`ArrayList`, `LinkedList`, `HashSet`, `TreeMap`, `ArrayDeque`...) cho từng nhu cầu, gắn với **độ phức tạp Big-O**.
- Hiểu **Iterator / Iterable**, cơ chế **fail-fast** và lỗi kinh điển **`ConcurrentModificationException`** — tự gây lỗi rồi tự sửa.
- Nắm vững **`Comparable` vs `Comparator`** (natural ordering vs custom), dùng `Comparator.comparing`, `thenComparing`, `reversed`.
- Đối chiếu với **PHP `array`** và **Laravel `Collection`** để chuyển tư duy nhanh, không bị "ngợp" vì Java tách nhiều kiểu.

---

## 🧠 Lý thuyết cốt lõi

### 1. Cây phân cấp Collections Framework

Toàn bộ thư viện collection nằm trong package `java.util`. Hãy ghi nhớ sơ đồ này — nó giải thích "vì sao một số phương thức có ở chỗ này mà không có ở chỗ kia":

```
              Iterable<E>              <-- gốc: cái gì "lặp được" (for-each)
                  │
              Collection<E>            <-- nhóm phần tử: add/remove/size/contains
        ┌─────────┼─────────┐
        │         │         │
      List<E>   Set<E>    Queue<E>
        │         │         │
   ArrayList   HashSet   ArrayDeque
   LinkedList  LinkedHashSet  PriorityQueue
              TreeSet   LinkedList (cũng là Deque)


              Map<K,V>                 <-- NHÁNH RIÊNG, KHÔNG kế thừa Collection
        ┌─────────┼─────────┐
    HashMap   LinkedHashMap  TreeMap
```

> 💡 Điểm cực kỳ hay bị hỏi phỏng vấn: **`Map` KHÔNG phải là `Collection`**. Lý do: `Collection` mô hình hóa "một nhóm phần tử đơn lẻ", còn `Map` mô hình hóa "ánh xạ key → value" (cặp đôi), bản chất khác nhau. Vì thế `Map` không có `add(E)`, mà có `put(K, V)`. Tuy nhiên `Map` cho phép lấy ra các "view" là Collection: `keySet()` (Set), `values()` (Collection), `entrySet()` (Set của các `Map.Entry`).

> ⚠️ `LinkedList` vừa là `List` vừa là `Deque` (nên cũng là `Queue`). Đây là lý do nó "đa năng nhưng ít khi tối ưu" — sẽ nói ở phần dưới.

### 2. List vs Set vs Queue vs Map — phân biệt bằng 3 câu hỏi

| Kiểu | Cho phép trùng? | Có thứ tự / chỉ số? | Mô hình | Dùng khi |
|---|---|---|---|---|
| **List** | ✅ Có | ✅ Theo chỉ số (index 0..n) | Dãy có thứ tự | Cần giữ thứ tự chèn, truy cập theo vị trí, cho phép phần tử lặp |
| **Set** | ❌ Không (duy nhất) | Tùy loại (xem dưới) | Tập hợp toán học | Cần đảm bảo **không trùng** (email, ID đã thấy...) |
| **Queue / Deque** | ✅ Có | Theo luật FIFO/LIFO/ưu tiên | Hàng đợi | Xử lý theo thứ tự vào/ra, stack, hàng đợi công việc |
| **Map** | Key ❌ trùng, Value ✅ trùng | Tùy loại | Ánh xạ key→value | Tra cứu nhanh theo khóa (cache, từ điển, đếm tần suất) |

**Quy tắc "tính tương đương" (equality) quyết định trùng lặp:** `Set` và `Map` (phần key) coi hai phần tử là "giống nhau" dựa trên `equals()` + `hashCode()` (với loại hash) hoặc `compareTo()`/`Comparator` (với loại cây). Đây là lý do bạn **bắt buộc override `equals`/`hashCode`** khi cho object tự định nghĩa vào `HashSet`/`HashMap` (đã/sẽ học ở ngày về `equals & hashCode`).

### 3. Các implementation chính — dùng khi nào

**List:**

| Lớp | Cấu trúc bên trong | Mạnh ở | Yếu ở | Khi nào dùng |
|---|---|---|---|---|
| `ArrayList` | Mảng động (resizable array) | `get(i)` O(1), duyệt nhanh, ít tốn bộ nhớ | Chèn/xóa giữa O(n) (phải dịch phần tử) | **Mặc định 95% trường hợp** |
| `LinkedList` | Danh sách liên kết đôi | Thêm/xóa đầu-cuối O(1) | `get(i)` O(n), tốn bộ nhớ con trỏ | Khi cần Deque hai đầu; hiếm khi vì List |

**Set:**

| Lớp | Thứ tự | Đặc điểm | Khi nào dùng |
|---|---|---|---|
| `HashSet` | Không có thứ tự | Nhanh nhất O(1), dựa `hashCode` | Chỉ cần "duy nhất", không quan tâm thứ tự |
| `LinkedHashSet` | Thứ tự **chèn** | Hash + danh sách liên kết duy trì thứ tự | Cần duy nhất **và** giữ thứ tự thêm vào |
| `TreeSet` | Thứ tự **sắp xếp** | Cây đỏ-đen, O(log n), cần Comparable/Comparator | Cần phần tử luôn được sắp xếp tự động |

**Map:** tương ứng 1-1 với Set:

| Lớp | Thứ tự | Khi nào dùng |
|---|---|---|
| `HashMap` | Không | Tra cứu key→value nhanh nhất, không quan tâm thứ tự |
| `LinkedHashMap` | Thứ tự chèn (hoặc truy cập) | Giữ thứ tự; làm LRU cache (access-order) |
| `TreeMap` | Key được sắp xếp | Cần duyệt key theo thứ tự, truy vấn khoảng (`headMap`, `tailMap`, `subMap`) |

**Queue / Deque:**

| Lớp | Mô hình | Khi nào dùng |
|---|---|---|
| `ArrayDeque` | Deque hai đầu trên mảng vòng | **Stack & Queue mặc định** — nhanh hơn `Stack` và `LinkedList` |
| `PriorityQueue` | Heap (đống) | Luôn lấy ra phần tử "ưu tiên nhất" (nhỏ nhất/lớn nhất theo Comparator) |

> 💡 Mẹo nhớ: cần **List → `ArrayList`**, cần **Set → `HashSet`**, cần **Map → `HashMap`**, cần **stack/queue → `ArrayDeque`**. Chỉ đổi sang lựa chọn khác khi có lý do rõ ràng (cần thứ tự, cần sắp xếp, cần ưu tiên).

### 4. Iterable, Iterator và vòng lặp for-each

- `Iterable<E>` là gốc — bất cứ cái gì implement nó đều dùng được trong `for (E x : collection)`.
- Vòng `for-each` thực chất là **đường cú pháp (syntactic sugar)** cho `Iterator`:

```java
// Bạn viết:
for (String s : list) { ... }

// Trình biên dịch dịch thành:
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    String s = it.next();
    ...
}
```

- `Iterator` có 3 phương thức: `hasNext()`, `next()`, và `remove()` (xóa **an toàn** phần tử vừa duyệt).

### 5. Fail-fast vs Fail-safe và `ConcurrentModificationException`

Các collection trong `java.util` (`ArrayList`, `HashMap`...) là **fail-fast**: nếu cấu trúc collection bị **sửa đổi trong lúc đang duyệt** (thêm/xóa qua chính collection chứ không qua iterator), chúng phát hiện ngay và **ném `ConcurrentModificationException`** thay vì để chạy tiếp với kết quả khó lường.

```
Cơ chế: mỗi collection có biến đếm modCount (số lần sửa cấu trúc).
Iterator chụp lại expectedModCount lúc tạo.
Mỗi next() kiểm tra: modCount == expectedModCount ?
   ── khác nhau ──► ném ConcurrentModificationException (fail-fast)
```

> ⚠️ "Concurrent" ở đây **không nhất thiết là đa luồng**. Chỉ cần **một luồng** vừa `for-each` vừa gọi `list.remove(...)` là dính ngay. Đây là một trong những lỗi runtime phổ biến nhất với người mới.

**Fail-safe** (ví dụ `CopyOnWriteArrayList`, `ConcurrentHashMap`): duyệt trên một bản sao/snapshot nên **không ném** exception, nhưng có thể không thấy thay đổi mới nhất. Dùng trong ngữ cảnh đa luồng.

**Cách sửa fail-fast (sẽ code ở phần dưới):**
1. Dùng `Iterator.remove()` thay vì `collection.remove()`.
2. Dùng `collection.removeIf(predicate)` — ngắn gọn, an toàn, idiomatic Java 8+.

### 6. Comparable vs Comparator — sắp xếp như thế nào

| | `Comparable<T>` | `Comparator<T>` |
|---|---|---|
| Vị trí | Định nghĩa **bên trong** chính class (T tự so sánh) | Định nghĩa **bên ngoài**, là object riêng |
| Phương thức | `int compareTo(T other)` | `int compare(T a, T b)` |
| Ý nghĩa | **Natural ordering** (thứ tự "tự nhiên" mặc định) | Quy tắc sắp xếp **tùy biến**, nhiều kiểu khác nhau |
| Ví dụ có sẵn | `String` theo alphabet, `Integer` theo giá trị | Bạn tự viết: theo giá giảm dần, theo tên, theo ngày... |

**Quy ước giá trị trả về (cả hai giống nhau):**
- `< 0` nếu phần tử đầu **đứng trước** (nhỏ hơn).
- `== 0` nếu **bằng** nhau về thứ tự.
- `> 0` nếu phần tử đầu **đứng sau** (lớn hơn).

Java 8+ cho cú pháp viết `Comparator` cực gọn:

```java
Comparator<Bid> byAmount      = Comparator.comparing(Bid::amount);
Comparator<Bid> byAmountDesc  = Comparator.comparing(Bid::amount).reversed();
Comparator<Bid> complex       = Comparator.comparing(Bid::amount).reversed()
                                          .thenComparing(Bid::time);
```

> 💡 `thenComparing` là "tiêu chí phụ" khi tiêu chí chính bằng nhau (như `ORDER BY giá DESC, thời_gian ASC` trong SQL). `reversed()` đảo chiều toàn bộ comparator ngay trước nó.

### 7. Big-O — bảng tra nhanh

| Thao tác | `ArrayList` | `LinkedList` | `HashSet`/`HashMap` | `TreeSet`/`TreeMap` |
|---|---|---|---|---|
| `get(i)` / `get(key)` | **O(1)** | O(n) | O(1) trung bình | O(log n) |
| `add` (cuối) | O(1)* khấu hao | O(1) | O(1) trung bình | O(log n) |
| `add`/`remove` (giữa/đầu) | O(n) | O(1) nếu có con trỏ | — | O(log n) |
| `contains` | O(n) | O(n) | **O(1)** trung bình | O(log n) |

\* `ArrayList.add` cuối là O(1) **khấu hao** (amortized) — thỉnh thoảng phải cấp phát mảng lớn hơn và copy (O(n)), nhưng trung bình vẫn O(1).

> 💡 Bài học rút ra: cần kiểm tra "có tồn tại không" thường xuyên → đừng dùng `List.contains` (O(n)), hãy dùng `HashSet` (O(1)). Đây là tối ưu performance kinh điển.

### 8. Collections utility & immutable list

- Lớp tiện ích `java.util.Collections` (số nhiều, khác interface `Collection`): `sort`, `reverse`, `shuffle`, `max`, `min`, `unmodifiableList`, `emptyList`, `singletonList`...
- `List.of(...)`, `Set.of(...)`, `Map.of(...)` (Java 9+) tạo collection **bất biến (immutable)**:

```java
List<String> fixed = List.of("a", "b", "c");
fixed.add("d");   // ⚠️ ném UnsupportedOperationException — KHÔNG sửa được!
```

> ⚠️ `List.of()` không chỉ immutable mà còn **không cho phần tử `null`**. Nếu cần list rỗng có thể sửa, dùng `new ArrayList<>()`. Nếu chỉ cần một list cố định để đọc, `List.of(...)` an toàn và rõ ràng ý đồ hơn.

---

## 🔁 Đối chiếu với Laravel/PHP

Trong PHP, `array` là "con dao đa năng": vừa làm list (mảng chỉ số), vừa làm map (mảng kết hợp/associative) — thực chất là một **ordered hashtable**. Laravel bọc thêm `Illuminate\Support\Collection` với `map/filter/reduce/each` rất tiện. Java thì **tách rạch ròi từng kiểu** và ép **type-safe qua generics**.

| Nhu cầu | PHP / Laravel | Java |
|---|---|---|
| Mảng có thứ tự, cho trùng | `$a = [1, 2, 2, 3];` | `List<Integer> a = new ArrayList<>(List.of(1,2,2,3));` |
| Map key→value | `$m = ['vn' => 'Vietnam'];` | `Map<String,String> m = new HashMap<>();` |
| Tập hợp duy nhất | `array_unique($a)` (thủ công) | `Set<Integer> s = new HashSet<>(a);` (bản chất ngôn ngữ) |
| Stack / Queue | `array_push/array_pop`, `array_shift` | `Deque<T> d = new ArrayDeque<>();` |
| map/filter/reduce | `collect($a)->map(...)->filter(...)` | `a.stream().map(...).filter(...).toList()` (Day Stream) |
| Sắp xếp tùy biến | `usort($a, fn($x,$y) => ...)` | `a.sort(Comparator.comparing(...))` |
| Kiểm tra tồn tại key | `isset($m['vn'])` | `m.containsKey("vn")` / `m.get("vn") != null` |

**Khác biệt tư duy quan trọng nhất:**
- PHP: **một** kiểu `array` lo tất → linh hoạt nhưng dễ nhập nhằng (mảng này là list hay map?), và **không type-safe** (giá trị kiểu gì cũng nhét vào được).
- Java: **chọn đúng kiểu collection trước** dựa trên ngữ nghĩa (cần trùng? cần thứ tự? cần tra theo key?), rồi **generics khóa chặt kiểu phần tử** (`List<Bid>` chỉ chứa `Bid`). Đổi lại sự "rườm rà" là an toàn lúc biên dịch và tự tài liệu hóa ý đồ.

> 🧩 Laravel `Collection` lười (chainable, expressive) — tương đương gần nhất ở Java là **Stream API** (sẽ học ngày sau), KHÔNG phải interface `Collection`. Đừng nhầm: `java.util.Collection` là "cấu trúc dữ liệu", `Stream` mới là "pipeline xử lý" giống `Illuminate\Support\Collection`.

---

## 💻 Thực hành code

### Bước 1 — Khởi tạo & dùng từng loại collection

```java
import java.util.*;

public class CollectionsDemo {
    public static void main(String[] args) {
        // LIST: có thứ tự, cho phép trùng
        ___<String> products = new ___<>(); // Điền kiểu interface và class cho danh sách
        products.add("Đồng hồ cổ");
        products.add("Tranh sơn dầu");
        products.add("Đồng hồ cổ");            // cho phép trùng
        System.out.println("List: " + products);          // [Đồng hồ cổ, Tranh sơn dầu, Đồng hồ cổ]
        System.out.println("Phần tử index 1: " + products.get(1)); // truy cập theo chỉ số: O(1)

        // SET: duy nhất, HashSet không bảo đảm thứ tự
        ___<String> uniqueBidders = new ___<>(); // Điền kiểu interface và class cho tập hợp duy nhất
        uniqueBidders.add("alice");
        uniqueBidders.add("bob");
        uniqueBidders.add("alice");           // bị bỏ qua vì đã có
        System.out.println("Set kích thước: " + uniqueBidders.size()); // 2

        // LinkedHashSet giữ đúng thứ tự chèn
        Set<String> ordered = new LinkedHashSet<>(List.of("c", "a", "b", "a"));
        System.out.println("LinkedHashSet: " + ordered);   // [c, a, b]

        // TreeSet tự sắp xếp tăng dần
        Set<Integer> sorted = new TreeSet<>(List.of(5, 1, 3, 1));
        System.out.println("TreeSet: " + sorted);          // [1, 3, 5]

        // MAP: ánh xạ key -> value
        ___<String, Integer> bidCount = new ___<>(); // Điền kiểu interface và class cho ánh xạ key-value
        bidCount.put("alice", 3);
        bidCount.put("bob", 1);
        bidCount.merge("alice", 1, Integer::sum);          // tăng đếm: alice -> 4
        System.out.println("Map: " + bidCount);
        System.out.println("alice đã bid: " + bidCount.getOrDefault("alice", 0));

        // QUEUE / DEQUE: dùng ArrayDeque làm cả stack lẫn queue
        ___<String> queue = new ___<>(); // Điền kiểu interface và class cho hàng đợi hai đầu
        queue.offer("job1");      // thêm vào cuối (queue)
        queue.offer("job2");
        System.out.println("Lấy ra (FIFO): " + queue.poll()); // job1

        Deque<String> stack = new ArrayDeque<>();
        stack.push("a");          // thêm vào đầu (stack)
        stack.push("b");
        System.out.println("Lấy ra (LIFO): " + stack.pop());  // b
    }
}
```

### Bước 2 — `record Bid` và sắp xếp bằng Comparator

```java
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

// record (Java 16+): tự sinh constructor, getter (amount()), equals, hashCode, toString
public record Bid(Long userId, BigDecimal amount, Instant time) {}

public class BidSortingDemo {
    public static void main(String[] args) {
        List<Bid> bids = new ArrayList<>(List.of(
            new Bid(1L, new BigDecimal("100.00"), Instant.parse("2026-06-18T10:00:00Z")),
            new Bid(2L, new BigDecimal("150.00"), Instant.parse("2026-06-18T10:05:00Z")),
            new Bid(3L, new BigDecimal("150.00"), Instant.parse("2026-06-18T10:02:00Z")), // bằng giá #2
            new Bid(4L, new BigDecimal("120.00"), Instant.parse("2026-06-18T10:01:00Z"))
        ));

        // Yêu cầu: sắp xếp theo GIÁ giảm dần; nếu bằng giá thì THỜI GIAN tăng dần (bid sớm hơn xếp trước)
        ___<Bid> ranking = ___ // Điền interface và class tiện ích dùng để so sánh tùy biến
                .comparing(Bid::amount).reversed()   // giá cao nhất lên đầu
                .thenComparing(Bid::time);           // bằng giá -> ai đặt trước thắng

        bids.sort(ranking);  // sắp xếp tại chỗ (in-place) trên List

        System.out.println("Bảng xếp hạng bid:");
        bids.forEach(b -> System.out.printf(
                "  user=%d  giá=%s  lúc=%s%n", b.userId(), b.amount(), b.time()));
        // Kết quả mong đợi:
        //   user=3  giá=150.00  (10:02 - đặt trước user 2)
        //   user=2  giá=150.00  (10:05)
        //   user=4  giá=120.00
        //   user=1  giá=100.00

        Bid winner = bids.get(0);
        System.out.println("Người thắng tạm thời: user " + winner.userId());
    }
}
```

> 💡 Lưu ý dùng `BigDecimal` cho tiền (không dùng `double` — sai số dấu phẩy động). `Comparator.comparing(Bid::amount)` hoạt động được vì `BigDecimal` đã implement `Comparable`.

### Bước 3 — Tự gây lỗi `ConcurrentModificationException` rồi sửa

```java
import java.util.*;

public class FailFastDemo {
    public static void main(String[] args) {
        // ❌ CÁCH SAI: xóa qua list trong lúc for-each -> ConcurrentModificationException
        List<Integer> a = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        try {
            for (Integer x : a) {
                if (x % 2 == 0) {
                    a.remove(x);   // sửa cấu trúc list trong khi iterator đang chạy
                }
            }
        } catch (___ e) { // Điền ngoại lệ văng ra khi duyệt và sửa phần tử đồng thời
            System.out.println("Dính lỗi như dự đoán: " + e.getClass().getSimpleName());
        }

        // ✅ CÁCH SỬA 1: dùng Iterator.remove()
        List<Integer> b = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        Iterator<Integer> it = b.iterator();
        while (it.hasNext()) {
            if (it.next() % 2 == 0) {
                it.remove();       // an toàn: iterator tự cập nhật expectedModCount
            }
        }
        System.out.println("Sau Iterator.remove(): " + b); // [1, 3, 5]

        // ✅ CÁCH SỬA 2: removeIf() — ngắn gọn & idiomatic nhất
        List<Integer> c = new ArrayList<>(List.of(1, 2, 3, 4, 5));
        c.removeIf(x -> x % 2 == 0);
        System.out.println("Sau removeIf(): " + c);        // [1, 3, 5]
    }
}
```

### Bước 4 — Chọn collection để chống bid trùng

```java
import java.util.*;

public class UniqueBidderDemo {
    public static void main(String[] args) {
        // List để lưu lịch sử bid (cần thứ tự, cho trùng)
        List<Long> bidHistory = new ArrayList<>(List.of(1L, 2L, 1L, 3L, 2L));

        // Cần biết "có bao nhiêu người ĐÃ từng bid" -> dùng Set để loại trùng O(1)
        Set<Long> distinctBidders = new HashSet<>(bidHistory);
        System.out.println("Số người tham gia: " + distinctBidders.size()); // 3

        // contains trên Set là O(1) — tốt hơn nhiều so với List.contains O(n)
        System.out.println("User 3 đã bid? " + distinctBidders.contains(3L)); // true
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Sửa list trong lúc `for-each`** → `ConcurrentModificationException`. Dùng `Iterator.remove()` hoặc `removeIf()`.
- **Gọi `.add()` trên list bất biến** (`List.of(...)`, `Arrays.asList(...)`, `Collections.unmodifiableList(...)`) → `UnsupportedOperationException`. Nếu cần sửa, bọc lại: `new ArrayList<>(List.of(...))`.
- **Nhét `null` vào `List.of()` / `Map.of()`** → `NullPointerException`. Các factory này cấm `null`.
- **Dùng `List.contains()` trong vòng lặp lớn** (O(n) × n = O(n²)) — chuyển sang `HashSet` để tra O(1).
- **Cho object vào `HashSet`/`HashMap` mà chưa override `equals`/`hashCode`** → coi như mọi object đều khác nhau (so sánh theo địa chỉ), trùng vẫn lọt. `record` xử lý sẵn điều này cho bạn.
- **Dùng `TreeSet`/`TreeMap` với phần tử không `Comparable` và không cấp `Comparator`** → `ClassCastException` lúc thêm phần tử thứ hai.
- **Nhầm `Collections` (lớp tiện ích) với `Collection` (interface).** Tên chỉ khác chữ "s".
- **Tưởng `LinkedList.get(i)` nhanh.** Nó là O(n) vì phải đi từ đầu. Cần truy cập theo chỉ số → `ArrayList`.
- **Đổi key của object đang nằm trong `HashMap`/`HashSet`** (làm `hashCode` thay đổi) → "mất" phần tử, không tìm thấy được nữa.

---

## 🚀 Liên hệ Spring Boot / Production

- Tầng Service/Repository trả về `List<T>`, `Set<T>`, `Map<K,V>` khắp nơi. Hiểu khác biệt giúp chọn đúng kiểu cho DTO (ví dụ trả `Set<RoleName>` để đảm bảo quyền không trùng).
- Spring Data JPA: query trả `List<Entity>`; quan hệ `@OneToMany` thường map vào `Set` hoặc `List` — chọn `Set` khi tập con không cho trùng và bạn đã override `equals/hashCode` đúng (lưu ý kinh điển về entity + `Set`).
- **`HashMap` không thread-safe.** Trong bean singleton (sống suốt vòng đời app, nhiều luồng dùng chung), nếu cần map chia sẻ có sửa đổi, dùng **`ConcurrentHashMap`**, đừng dùng `HashMap` trần → tránh hỏng dữ liệu/loop vô hạn.
- Cache đơn giản trong RAM hay làm bằng `ConcurrentHashMap` hoặc `LinkedHashMap` (access-order) cho LRU; production lớn thì dùng Caffeine/Redis.
- Trả collection **bất biến** ra khỏi service (`List.copyOf(...)`, `Collections.unmodifiableList(...)`) để bên ngoài không sửa lung tung trạng thái nội bộ — nguyên tắc đóng gói (encapsulation) quan trọng.
- `PriorityQueue` hữu ích cho hàng đợi công việc theo độ ưu tiên; `ArrayDeque` cho hàng đợi tác vụ FIFO nhẹ.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp nối mạch Auction API. Hôm nay ta dựng tầng dữ liệu trong bộ nhớ cho phiên đấu giá và áp dụng đúng collection.

**Nhiệm vụ Day 08:**
0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Tạo `record Bid(Long userId, BigDecimal amount, Instant time) {}`.
2. Tạo class `Auction` lưu danh sách bid bằng **`List<Bid>`** (cần thứ tự lịch sử và cho phép nhiều bid). Thêm method `placeBid(Bid b)`.
3. Method `currentLeaderboard()` trả về danh sách bid đã sắp theo **giá giảm dần, bằng giá thì thời gian tăng dần** (dùng `Comparator.comparing(...).reversed().thenComparing(...)`). Trả ra **bản bất biến** (`List.copyOf(...)`).
4. Method `highestBid()` trả `Optional<Bid>` (đỉnh của leaderboard).
5. Dùng một **`Set<Long>`** để theo dõi tập người tham gia (đếm `distinctBidders`).
6. Viết một method `removeBidsBelow(BigDecimal min)` xóa các bid dưới ngưỡng — **bắt buộc dùng `removeIf`** (tránh `ConcurrentModificationException`).
7. Trong `main`, thêm 4-5 bid (có cặp bằng giá), in leaderboard, in người thắng, in số người tham gia.

**Gợi ý khung code:**

```java
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record Bid(Long userId, BigDecimal amount, Instant time) {}

class Auction {
    private final List<Bid> bids = new ArrayList<>();

    public void placeBid(Bid b) { bids.add(b); }

    public List<Bid> currentLeaderboard() {
        List<Bid> sorted = new ArrayList<>(bids);
        sorted.sort(Comparator.comparing(Bid::amount).reversed()
                              .thenComparing(Bid::time));
        return List.copyOf(sorted);   // trả bản bất biến
    }

    public Optional<Bid> highestBid() {
        return currentLeaderboard().stream().findFirst();
    }

    public Set<Long> distinctBidders() {
        Set<Long> ids = new HashSet<>();
        for (Bid b : bids) ids.add(b.userId());
        return ids;
    }

    public void removeBidsBelow(BigDecimal min) {
        bids.removeIf(b -> b.amount().compareTo(min) < 0);
    }
}
```

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Khác nhau cơ bản giữa List, Set và Map?**
> **Đáp:** `List` là dãy có thứ tự theo chỉ số, **cho phép trùng** (`ArrayList`, `LinkedList`). `Set` là tập hợp, **không cho phần tử trùng** (`HashSet`, `TreeSet`). `Map` ánh xạ **key → value**, key không trùng (`HashMap`, `TreeMap`). Đặc biệt: `Map` **không** kế thừa `Collection`.

**Q2: `ArrayList` và `LinkedList` khác nhau ra sao, khi nào chọn cái nào?**
> **Đáp:** `ArrayList` dựa trên mảng động: `get(i)` O(1), duyệt nhanh, tốn ít bộ nhớ — dùng cho 95% trường hợp. `LinkedList` là danh sách liên kết đôi: thêm/xóa đầu-cuối O(1) nhưng `get(i)` O(n) và tốn bộ nhớ con trỏ. Chỉ chọn `LinkedList` khi thực sự cần thao tác Deque hai đầu liên tục; còn cần truy cập theo chỉ số luôn chọn `ArrayList`.

**Q3: Khi nào dùng `HashSet`, `LinkedHashSet`, `TreeSet`?**
> **Đáp:** `HashSet` khi chỉ cần "duy nhất" và không quan tâm thứ tự (nhanh nhất, O(1)). `LinkedHashSet` khi cần duy nhất **và giữ thứ tự chèn**. `TreeSet` khi cần phần tử **luôn được sắp xếp** (O(log n), yêu cầu `Comparable` hoặc `Comparator`).

**Q4: `Comparable` và `Comparator` khác nhau thế nào?**
> **Đáp:** `Comparable` định nghĩa **bên trong** class qua `compareTo(T)` — là "thứ tự tự nhiên" mặc định, chỉ một kiểu. `Comparator` là object **bên ngoài** với `compare(a, b)` — cho phép nhiều quy tắc sắp xếp tùy biến mà không sửa class gốc. Cả hai đều trả `<0`, `0`, `>0`. Java 8+ viết gọn bằng `Comparator.comparing(...).thenComparing(...).reversed()`.

**Q5: Tại sao `List.of(...)` gọi `add` lại ném `UnsupportedOperationException`?**
> **Đáp:** Vì `List.of(...)` tạo **list bất biến (immutable)** — không hỗ trợ thao tác sửa đổi nên `add`/`remove`/`set` ném `UnsupportedOperationException`. Muốn list sửa được, bọc lại: `new ArrayList<>(List.of(...))`.

### Mức Senior

**Q6: `ConcurrentModificationException` xảy ra do đâu, fail-fast hoạt động thế nào, và cách tránh?**
> **Đáp:** Collection trong `java.util` giữ biến `modCount` đếm số lần sửa cấu trúc. Khi tạo `Iterator`, nó chụp `expectedModCount`. Mỗi `next()` kiểm tra `modCount == expectedModCount`; nếu collection bị sửa trực tiếp (không qua iterator) trong lúc duyệt thì hai số lệch nhau → ném ngay (**fail-fast**), kể cả trong **một luồng**. Cách tránh: dùng `Iterator.remove()`, `removeIf(...)`, gom phần tử cần xóa rồi xóa sau, hoặc dùng collection fail-safe (`CopyOnWriteArrayList`, `ConcurrentHashMap`) trong môi trường đa luồng.

**Q7: Vì sao cần override `equals`/`hashCode` khi dùng object làm phần tử `HashSet` hay key `HashMap`?**
> **Đáp:** `HashSet`/`HashMap` xác định vị trí và tính trùng dựa trên `hashCode()` (chọn bucket) rồi `equals()` (so trong bucket). Nếu không override, mặc định so theo địa chỉ object → hai object "giống về nội dung" vẫn bị coi là khác nhau, gây trùng lặp logic hoặc không `get` lại được. Phải đảm bảo: bằng nhau theo `equals` thì cùng `hashCode`. `record` sinh sẵn cả hai theo các field.

**Q8: `HashMap` có an toàn đa luồng không? Trong Spring Boot nên dùng gì?**
> **Đáp:** Không. `HashMap` không thread-safe; sửa đồng thời có thể hỏng cấu trúc (trước Java 8 từng gây vòng lặp vô hạn khi resize, nay vẫn mất/ghi đè dữ liệu). Trong bean singleton dùng chung nhiều luồng, dùng **`ConcurrentHashMap`** (khóa phân đoạn, hiệu năng cao). `Collections.synchronizedMap` cũng an toàn nhưng khóa toàn cục nên chậm hơn dưới tải cao.

**Q9: Giải thích độ phức tạp `add` của `ArrayList` và "amortized O(1)".**
> **Đáp:** Thêm vào cuối thường là O(1). Khi mảng đầy, `ArrayList` cấp phát mảng mới (thường gấp ~1.5 lần) và copy toàn bộ — thao tác đó O(n). Nhưng vì việc nới chỉ thỉnh thoảng xảy ra, tổng chi phí chia đều ra mỗi phần tử vẫn là hằng số → gọi là **O(1) khấu hao (amortized)**. Đặt `initialCapacity` hợp lý khi biết trước kích thước để giảm số lần copy.

**Q10: `TreeMap`/`TreeSet` cho khả năng gì mà `HashMap`/`HashSet` không có?**
> **Đáp:** Vì dựa trên cây đỏ-đen giữ key luôn sắp xếp, `TreeMap`/`TreeSet` hỗ trợ **truy vấn theo thứ tự và theo khoảng**: `first()/last()`, `floor()/ceiling()`, `headMap()/tailMap()/subMap()`, duyệt key theo thứ tự tăng/giảm. Đổi lại mọi thao tác là O(log n) thay vì O(1). Dùng khi cần "phần tử gần nhất", "khoảng giá trị", hoặc duyệt có thứ tự.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ lại được cây phân cấp `Iterable → Collection → List/Set/Queue` và nhớ `Map` là nhánh riêng
- [ ] Phân biệt được List/Set/Queue/Map qua 3 câu hỏi (trùng? thứ tự? key-value?)
- [ ] Biết chọn đúng implementation cho từng nhu cầu kèm Big-O
- [ ] Tự gây và sửa được `ConcurrentModificationException` (Iterator.remove + removeIf)
- [ ] Viết được Comparator nhiều tiêu chí (`comparing().reversed().thenComparing()`)
- [ ] Hoàn thành nhiệm vụ Mini Project Day 08 (Auction với leaderboard)
- [ ] Trả lời được 10 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Collections" (Trail: Collections)
- Javadoc các interface: `Collection`, `List`, `Set`, `Map`, `Queue`, `Deque`, `Comparator`, `Comparable`
- Baeldung — "Java Collections", "Comparator and Comparable in Java", "ConcurrentModificationException"
- Baeldung — "Guide to `ArrayDeque`", "A Guide to `TreeMap`"
- Sách *Effective Java* (Joshua Bloch) — Item 14 (Comparable), Item 11 (equals/hashCode), các item về collection
- Big-O Cheat Sheet cho Java Collections (tham khảo nhanh độ phức tạp)
