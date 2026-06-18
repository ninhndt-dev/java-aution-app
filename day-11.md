# Day 11 - HashMap Internals

> **Giai đoạn:** Collections & Generics
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **cấu trúc bên trong** của `HashMap`: mảng bucket `Node<K,V>[] table`, chuỗi liên kết, và cây đỏ-đen.
- Nắm cơ chế **hàm băm + nhiễu** (`hash ^ (hash >>> 16)`) và công thức tính bucket `(n - 1) & hash`.
- Thuộc lòng **hợp đồng `hashCode()`/`equals()`** — vì sao phải override cả hai một cách nhất quán.
- Hiểu **load factor 0.75**, threshold, resize gấp đôi + rehash, và quá trình **treeify** khi va chạm nhiều.
- Hiểu vì sao **key nên immutable**, và vì sao `HashMap` **không thread-safe** (teaser `ConcurrentHashMap`).
- Đối chiếu với **associative array của PHP** (ordered hashtable) để chuyển tư duy cho đúng.

---

## 🧠 Lý thuyết cốt lõi

### 1. Bức tranh tổng thể: HashMap là một mảng bucket

`HashMap` về bản chất là một **mảng** (gọi là `table`), mỗi ô của mảng là một **bucket** (cái xô). Mỗi entry key/value được bọc trong một `Node<K,V>` chứa: `hash` (đã tính sẵn), `key`, `value`, và `next` (con trỏ tới node kế tiếp trong cùng bucket).

```
table  (Node<K,V>[]  — capacity = 16 mặc định)

 index ┌──────────────────────────────────────────┐
   0   │ null                                       │
   1   │ Node(hash, "apple", 3) ──► null            │   ← 1 phần tử
   2   │ null                                       │
   3   │ Node(h,"x",1) ─► Node(h,"y",2) ─► null     │   ← va chạm → chuỗi liên kết
   ...                                              │
   14  │ null                                       │
   15  │ Node(hash, "banana", 7) ──► null           │
       └──────────────────────────────────────────┘
```

- Khi `put(key, value)`: tính `hash` của key → tính `index` bucket → đặt node vào bucket đó.
- Khi `get(key)`: tính lại `index` → đi vào đúng bucket → so sánh từng node bằng `equals()` để tìm đúng key.

Nếu hàm băm phân bố đều, mỗi bucket có ~1 phần tử → `get`/`put` là **O(1) trung bình**. Nếu băm xấu (mọi key dồn về 1 bucket) → chuỗi dài → suy biến về **O(n)** (và Java 8+ cứu vãn bằng cách biến chuỗi thành cây — xem mục 6).

### 2. Hàm băm + nhiễu (perturbation): vì sao có `^ (h >>> 16)`

`HashMap` **không** dùng trực tiếp `key.hashCode()`. Nó "trộn thêm nhiễu":

```java
// Trích nguồn HashMap (OpenJDK)
static final int hash(Object key) {
    int h;
    return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
}
```

Ý nghĩa từng bước:
- `key.hashCode()` trả về một `int` 32 bit.
- `h >>> 16`: dịch phải **không dấu** 16 bit → đẩy **16 bit cao** xuống vị trí **16 bit thấp**.
- `h ^ (h >>> 16)`: XOR → trộn thông tin của nửa cao vào nửa thấp.

> 💡 **Vì sao cần trộn?** Khi `table` còn nhỏ (capacity 16 → mặt nạ `n-1 = 0b1111`), công thức tính bucket `(n-1) & hash` **chỉ dùng 4 bit thấp nhất**. Nếu hai key chỉ khác nhau ở các bit cao, chúng sẽ rơi vào **cùng một bucket** → va chạm nhiều. Bước XOR kéo bit cao xuống tham gia, làm 4 bit thấp "hỗn loạn" hơn → phân bố đều hơn → ít va chạm hơn. Đây là sự đánh đổi rẻ (1 phép dịch + 1 phép XOR) nhưng hiệu quả lớn.

### 3. Tính chỉ số bucket: `(n - 1) & hash` và vì sao capacity là lũy thừa 2

Sau khi có `hash`, vị trí bucket được tính:

```java
index = (n - 1) & hash;   // n = capacity, LUÔN là lũy thừa của 2
```

Vì capacity `n` luôn là lũy thừa của 2 (16, 32, 64, 128...), nên `n - 1` ở dạng nhị phân là một dãy bit 1 liên tiếp:

```
n      = 16  = 0001 0000
n - 1  = 15  = 0000 1111   ← "mặt nạ" 4 bit thấp
hash            ....  abcd
(n-1) & hash =  0000 abcd   ← chỉ giữ lại các bit thấp → giá trị 0..15
```

- `&` (AND theo bit) với mặt nạ này tương đương phép `hash % n` **nhưng nhanh hơn nhiều** (AND bit so với chia lấy dư).
- Phép `%` chỉ tương đương `&` khi `n` là lũy thừa 2. Đó là **lý do bắt buộc capacity phải là lũy thừa 2** — nếu không, không dùng được mánh `&` và phân bố cũng lệch.

> ⚠️ Khi bạn truyền `new HashMap<>(initialCapacity)` một số "xấu" như `7`, Java sẽ tự **làm tròn lên lũy thừa 2 gần nhất** (thành `8`) bằng hàm `tableSizeFor`. Bạn không thể ép capacity là số lẻ.

### 4. Hợp đồng `hashCode()` / `equals()` — xương sống của HashMap

Đây là phần **quan trọng nhất** ngày hôm nay. Hợp đồng (contract):

1. Nếu `a.equals(b)` là `true` → **bắt buộc** `a.hashCode() == b.hashCode()`.
2. Nếu `a.hashCode() == b.hashCode()` → `a.equals(b)` **không nhất thiết** true (cho phép va chạm).
3. Gọi `hashCode()` nhiều lần trên cùng object (không đổi trạng thái) phải trả **cùng giá trị**.

```
HashMap dùng 2 thứ này theo trình tự:
   put/get(key)
      │
      ├─► hashCode()  → xác định ĐÚNG BUCKET (đi nhanh tới xô)
      │
      └─► equals()    → so sánh trong xô để tìm ĐÚNG KEY
```

> ⚠️ **Quy tắc vàng:** Hễ override `equals()` thì **phải** override `hashCode()`, và cả hai phải dùng **cùng tập trường** (fields). Nếu `equals` so sánh `id` và `name` thì `hashCode` cũng phải băm từ `id` và `name`. Lệch nhau → map hỏng âm thầm.

Java cung cấp tiện ích an toàn để viết hai hàm này:

```java
@Override
public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProductKey other)) return false;   // pattern matching Java 16+
    return Objects.equals(this.code, other.code)
        && this.warehouse == other.warehouse;
}

@Override
public int hashCode() {
    return Objects.hash(code, warehouse);   // băm từ ĐÚNG các trường dùng trong equals
}
```

- `Objects.equals(a, b)`: so sánh null-safe (tránh `NullPointerException` khi a null).
- `Objects.hash(...)`: trộn nhiều trường thành một `int` hash, tự xử lý null.

### 5. `null` key và `null` value

- `HashMap` cho phép **đúng một** `null` key. Khi key là `null`, hàm `hash` trả `0` → nó luôn rơi vào **bucket index 0**.
- `HashMap` cho phép **nhiều** `null` value, với bao nhiêu key cũng được.

```java
Map<String, String> m = new HashMap<>();
m.put(null, "không có key");   // OK — 1 null key
m.put("a", null);              // OK — null value
m.put("b", null);              // OK — nhiều null value
```

> 💡 Đây là khác biệt với `Hashtable` (không cho null) và `ConcurrentHashMap` (cũng **không** cho null key/value — vì null gây nhập nhằng "không có key" vs "key map tới null" trong môi trường đa luồng).

### 6. Xử lý va chạm: Chaining → Treeify (cây đỏ-đen)

Khi hai key khác nhau rơi cùng bucket (va chạm), Java nối chúng thành **chuỗi liên kết** (linked list) qua con trỏ `next`.

```
bucket 3:  Node(A) ──► Node(B) ──► Node(C) ──► Node(D) ── ... ── Node(H)
           (chuỗi càng dài, tìm kiếm càng chậm — O(số phần tử trong bucket))
```

Từ **Java 8**, nếu một bucket quá đông, Java chuyển chuỗi thành **cây đỏ-đen** (red-black tree) để tìm kiếm trong bucket đó từ O(n) xuống **O(log n)**. Điều kiện **treeify** (cả hai phải đồng thời thỏa):

| Điều kiện | Hằng số | Giá trị |
|---|---|---|
| Số phần tử trong **một** bucket ≥ 8 | `TREEIFY_THRESHOLD` | 8 |
| Tổng dung lượng bảng ≥ 64 | `MIN_TREEIFY_CAPACITY` | 64 |

- Nếu bucket đạt ≥ 8 **nhưng** `table` còn nhỏ (< 64) → Java **resize (nở bảng) thay vì treeify**. Lý do: bảng nhỏ thì nguyên nhân thật sự thường là chật chội, nở bảng + rehash sẽ phân tán bớt va chạm rẻ hơn việc dựng cây.
- Khi cây co lại còn ≤ **6 phần tử** (`UNTREEIFY_THRESHOLD`) trong lúc resize → Java **untreeify** trở lại chuỗi liên kết (cây tốn bộ nhớ hơn, không cần khi ít phần tử).

> 💡 Việc dựng cây đòi key có thể **so sánh** được; nếu key không `Comparable`, Java dùng hash + định danh để sắp xếp ổn định trong cây. Đây là "lưới an toàn" chống tấn công hash-collision (hash DoS) khiến mọi key dồn một bucket.

### 7. Load factor, threshold, resize và rehash

- **Load factor** mặc định `0.75`. Đây là "mức đầy" cho phép trước khi nở bảng.
- **threshold** = `capacity * loadFactor`. Với capacity 16 → threshold = `16 * 0.75 = 12`.
- Khi `size > threshold` → `HashMap` **resize**: tạo bảng mới **gấp đôi** capacity (16 → 32 → 64...), rồi **rehash** — phân bố lại toàn bộ node vào bảng mới.

```
size vượt 12 (với cap 16)
   ┌─────────────┐   resize    ┌──────────────────────────────┐
   │ cap = 16    │  ────────►  │ cap = 32, threshold = 24       │
   │ thr = 12    │  rehash     │ mọi node tính lại bucket index │
   └─────────────┘             └──────────────────────────────┘
```

**Đánh đổi của load factor:**
- Load factor **cao** (vd 0.9): bảng đầy hơn mới nở → tiết kiệm RAM, nhưng **nhiều va chạm** → chậm.
- Load factor **thấp** (vd 0.5): nở sớm → ít va chạm, tra cứu nhanh, nhưng **tốn RAM** và resize thường xuyên.
- `0.75` là điểm cân bằng kinh điển giữa thời gian và không gian.

> ⚠️ Resize **tốn kém** (rehash toàn bộ). Nếu biết trước cần chứa ~1000 phần tử, hãy khởi tạo `new HashMap<>(1334)` (≈ 1000 / 0.75) để tránh nhiều lần resize. Đừng truyền đúng `1000` — vì threshold sẽ chỉ là 750, vẫn phải resize.

### 8. Vì sao key NÊN immutable

Hash của key được **tính một lần lúc put** và lưu trong `Node.hash`. Bucket index cũng dựa trên hash đó. Nếu sau khi `put` bạn **sửa một trường** của key làm `hashCode()` đổi:

```
put(key)  → tính hash = H1 → đặt vào bucket B1
... rồi sửa key (đổi trường ảnh hưởng hashCode) → hashCode mới = H2
get(key)  → tính hash = H2 → đi tới bucket B2 (KHÁC B1) → KHÔNG thấy → trả null
```

→ Object "lạc bucket": vẫn còn trong map nhưng **không tìm lại được** bằng `get`, cũng không xóa được bằng `remove`. Một dạng rò rỉ bộ nhớ âm thầm.

> 💡 Vì thế **String** và các **wrapper** (`Integer`, `Long`, `Boolean`...) là key lý tưởng — chúng **immutable**, hash cố định. Khi tự làm key, hãy dùng `record` hoặc class bất biến (mọi trường `final`, không setter).

### 9. Teaser: HashMap KHÔNG thread-safe → ConcurrentHashMap

`HashMap` **không an toàn đa luồng**. Nếu nhiều thread `put` đồng thời và trúng lúc **resize**, ở Java 7 cũ có thể tạo **vòng lặp vô tận** trong chuỗi liên kết (CPU 100%); ở Java 8+ ít gặp loop hơn nhưng vẫn có thể **mất dữ liệu**, đọc ra giá trị rác, hoặc `size` sai.

Giải pháp đa luồng:
- `ConcurrentHashMap`: khóa **theo từng bucket/bin** và dùng **CAS** (compare-and-swap) → nhiều thread ghi song song các bucket khác nhau mà không chặn nhau. Đây là lựa chọn chuẩn cho production.
- `Collections.synchronizedMap(new HashMap<>())`: khóa **toàn cục** mỗi thao tác → đơn giản nhưng nghẽn cổ chai, hiếm dùng.

Ngày sau ta sẽ đào sâu `ConcurrentHashMap`. Hôm nay chỉ cần nhớ: **đa luồng → đừng dùng HashMap trần**.

---

## 🔁 Đối chiếu với Laravel/PHP

Trong PHP, "mảng kết hợp" (associative array) `['key' => 'value']` được dùng cho mọi thứ. Nhưng bên trong nó **khác** `HashMap` của Java khá nhiều:

| Khía cạnh | PHP associative array | Java `HashMap` |
|---|---|---|
| Bản chất | **Ordered hashtable** — vừa là hash, vừa **giữ thứ tự chèn** | Hashtable thuần — **KHÔNG đảm bảo thứ tự** |
| Tương đương Java gần nhất | `LinkedHashMap` (giữ thứ tự chèn) | `HashMap` (vô thứ tự) |
| Kiểu key | Chỉ `int` hoặc `string` (key khác bị ép kiểu) | **Bất kỳ object nào** (cần `equals`/`hashCode` đúng) |
| Băm key | PHP **tự lo**, lập trình viên không thấy | Bạn phải hiểu/đôi khi tự viết `hashCode()` |
| `equals`/`hashCode` | Không cần — PHP so sánh string/int sẵn | **Bắt buộc** override khi key là object tùy biến |
| null key | `null` bị ép thành key `""` (chuỗi rỗng) | Cho phép đúng 1 `null` key thật sự |
| Đa luồng | Không phải bận tâm (mỗi request một process) | Phải dùng `ConcurrentHashMap` khi share giữa thread |

**Khác biệt tư duy quan trọng nhất:**
- PHP **giấu** toàn bộ cơ chế băm. Bạn quăng bất cứ thứ gì vào `$arr['key']` và nó "chỉ hoạt động". Bạn cũng quen array **giữ thứ tự** — `foreach` ra đúng thứ tự đã chèn.
- Java buộc bạn **lộ ra** cơ chế: nếu key là class của bạn mà quên override `hashCode()`, map sẽ **âm thầm sai**. Và `HashMap` **không giữ thứ tự** — đừng giả định thứ tự lặp. Cần thứ tự chèn → `LinkedHashMap`; cần sắp xếp theo key → `TreeMap`.

> 🧩 Hệ quả: khi port code Laravel sang Java mà bạn thấy `foreach ($arr as $k => $v)` phụ thuộc thứ tự, đừng map thẳng sang `HashMap` — dùng `LinkedHashMap` để giữ đúng hành vi "thứ tự chèn" như PHP.

---

## 💻 Thực hành code

### Bước 1 — DEMO BUG: quên override `hashCode()`

Ta tạo một class key tự định nghĩa nhưng **chỉ override `equals()`, quên `hashCode()`** để thấy map hỏng.

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HashMapBugDemo {

    // ❌ Key SAI: chỉ override equals, QUÊN hashCode
    static class ProductKeyBuggy {
        final String code;
        final int warehouse;

        ProductKeyBuggy(String code, int warehouse) {
            this.code = code;
            this.warehouse = warehouse;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProductKeyBuggy other)) return false;
            return warehouse == other.warehouse && Objects.equals(code, other.code);
        }
        // KHÔNG có hashCode() → dùng hashCode() mặc định của Object (theo địa chỉ bộ nhớ)
    }

    public static void main(String[] args) {
        Map<ProductKeyBuggy, Integer> stock = new HashMap<>();

        ProductKeyBuggy k1 = new ProductKeyBuggy("SKU-1", 10);
        ProductKeyBuggy k2 = new ProductKeyBuggy("SKU-1", 10); // "trùng" về mặt logic

        stock.put(k1, 100);

        // k1.equals(k2) == true, nhưng hashCode mặc định KHÁC nhau (2 object khác địa chỉ)
        System.out.println("k1.equals(k2) = " + k1.equals(k2));        // true
        System.out.println("k1.hashCode() = " + k1.hashCode());        // VD: 12345
        System.out.println("k2.hashCode() = " + k2.hashCode());        // VD: 67890 (khác!)

        // get bằng k2 → tính bucket theo hashCode KHÁC → không tới được node của k1
        System.out.println("get(k2) = " + stock.get(k2));              // null (BUG!)

        stock.put(k2, 200);   // tưởng ghi đè, hóa ra THÊM MỚI
        System.out.println("size = " + stock.size());                 // 2 (phình to — BUG!)
    }
}
```

**Kết quả & giải thích:** `k1.equals(k2)` là `true`, nhưng vì không override `hashCode()`, hai object dùng hash mặc định (theo địa chỉ) → **khác nhau** → rơi bucket khác → `get(k2)` không thấy `k1` (trả `null`), và `put(k2)` không ghi đè mà **thêm entry mới** khiến `size = 2`. Map "trùng key mà vẫn nhân đôi" — đúng kiểu bug âm thầm.

### Bước 2 — SỬA BUG: override `hashCode()` đúng

```java
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HashMapFixDemo {

    // ✅ Key ĐÚNG: override CẢ equals VÀ hashCode, cùng tập trường (code, warehouse)
    static final class ProductKey {
        private final String code;
        private final int warehouse;

        ProductKey(String code, int warehouse) {
            this.code = code;
            this.warehouse = warehouse;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ProductKey other)) return false;
            return warehouse == other.warehouse && Objects.equals(code, other.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, warehouse); // CÙNG các trường với equals
        }
    }

    public static void main(String[] args) {
        Map<ProductKey, Integer> stock = new HashMap<>();
        ProductKey k1 = new ProductKey("SKU-1", 10);
        ProductKey k2 = new ProductKey("SKU-1", 10);

        stock.put(k1, 100);
        System.out.println("get(k2) = " + stock.get(k2)); // 100 ✅ (tìm thấy)

        stock.put(k2, 200);                                // ghi đè đúng
        System.out.println("size = " + stock.size());      // 1 ✅
        System.out.println("value = " + stock.get(k1));    // 200 ✅
    }
}
```

### Bước 3 — Gọn hơn với `record` (Java 16+ tự sinh equals/hashCode)

Java `record` **tự sinh** `equals()`, `hashCode()`, `toString()` từ các thành phần — và chúng **bất biến** sẵn. Đây là cách viết key idiomatic nhất ở Java hiện đại:

```java
// 1 dòng thay cho cả class trên — tự có equals/hashCode đúng, immutable
public record ProductKey(String code, int warehouse) { }
```

```java
Map<ProductKey, Integer> stock = new HashMap<>();
stock.put(new ProductKey("SKU-1", 10), 100);
System.out.println(stock.get(new ProductKey("SKU-1", 10))); // 100 ✅
```

> 💡 Khi cần một composite key (key gồm nhiều trường), hãy **ưu tiên `record`**. Nó loại bỏ hoàn toàn lớp bug "quên hashCode" và đảm bảo key immutable.

### Bước 4 — `computeIfAbsent` và `merge` để gom dữ liệu

Hai phương thức cực hữu ích khi dùng map làm chỉ mục (index):

```java
import java.util.*;

public class GroupingDemo {
    public static void main(String[] args) {
        // Gom danh sách giá trị theo key — thay cho "if chưa có thì tạo list rồi add"
        Map<String, List<String>> byCategory = new HashMap<>();
        record Item(String category, String name) {}
        List<Item> items = List.of(
            new Item("fruit", "apple"),
            new Item("fruit", "banana"),
            new Item("veg", "carrot")
        );
        for (Item it : items) {
            // computeIfAbsent: nếu key chưa có → tạo ArrayList mới rồi trả về để .add
            byCategory.computeIfAbsent(it.category(), k -> new ArrayList<>())
                      .add(it.name());
        }
        System.out.println(byCategory); // {veg=[carrot], fruit=[apple, banana]}

        // merge: cộng dồn số đếm — nếu chưa có dùng 1, có rồi thì cộng
        Map<String, Integer> count = new HashMap<>();
        for (Item it : items) {
            count.merge(it.category(), 1, Integer::sum);
        }
        System.out.println(count); // {veg=1, fruit=2}
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Override `equals()` mà quên `hashCode()`** (hoặc ngược lại). Map sai âm thầm: `get` trả null, `size` phình to. Luôn override **cả hai**, dùng **cùng trường**.
- **`equals` và `hashCode` dùng tập trường khác nhau.** VD `equals` so `id` nhưng `hashCode` băm `name` → vi phạm hợp đồng → hành vi không xác định.
- **Dùng key mutable rồi sửa nó sau khi put.** Object "lạc bucket", không get/remove được — rò rỉ bộ nhớ.
- **Giả định `HashMap` giữ thứ tự.** Nó **không**. Quen tay từ PHP rất dễ dính. Cần thứ tự chèn → `LinkedHashMap`; cần sắp theo key → `TreeMap`.
- **Dùng `HashMap` trong môi trường đa luồng.** Có thể hỏng dữ liệu/loop. Dùng `ConcurrentHashMap`.
- **Khởi tạo capacity sai khi biết trước kích thước.** Truyền đúng số phần tử (vd 1000) vẫn bị resize vì threshold = 750. Truyền `(số / 0.75) + 1`.
- **Sửa map trong lúc đang lặp** (trừ qua `iterator.remove()`) → `ConcurrentModificationException`.

---

## 🚀 Liên hệ Spring Boot / Production

- **Cache nội bộ / lookup table:** Rất nhiều bean Spring giữ một `Map` tra cứu (vd map mã lỗi → thông điệp). Nếu chia sẻ giữa các request (bean singleton) và có thread ghi → **bắt buộc `ConcurrentHashMap`**, vì controller chạy đa luồng.
- **DTO làm key:** Khi gom kết quả theo một khóa ghép (vd `(userId, date)`), hãy dùng `record` làm key — đúng `equals/hashCode`, immutable, hết bug.
- **Entity JPA và `hashCode/equals`:** Đây là cái bẫy kinh điển. Entity có `@Id` tự sinh sẽ `null` trước khi persist; nếu bạn băm theo `id`, hash đổi sau khi lưu → vi phạm "hashCode bất biến". Quy ước phổ biến: dùng business key ổn định, hoặc `hashCode()` trả hằng số/loại entity, `equals` so `id` khi đã có.
- **Tham số cấu hình:** `@ConfigurationProperties` ánh xạ YAML thành `Map<String, ...>`. Hiểu rằng thứ tự không đảm bảo nếu là `HashMap`.
- **Hash DoS:** API nhận key từ client (vd form params) đổ vào map có nguy cơ bị tấn công va chạm. Java 8 treeify giảm rủi ro này; vẫn nên giới hạn số tham số đầu vào.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp mạch Auction API. Hôm nay ta dùng `HashMap` để **đánh chỉ mục bid theo người dùng**.

**Bối cảnh:** Mỗi phiên đấu giá nhận nhiều `Bid` (lượt trả giá). Ta cần truy vấn nhanh: "tất cả bid của một user" và "bid cao nhất của mỗi user".

**Nhiệm vụ Day 11:**
1. Tạo `record Bid(long userId, long amount, long timestamp) {}` — `record` nên thuộc tính immutable, key `userId` kiểu `Long` immutable (lý tưởng làm key).
2. Tạo `BidIndex` với:
   - `Map<Long, List<Bid>> bidsByUser` — gom tất cả bid của mỗi user bằng `computeIfAbsent`.
   - `Map<Long, Bid> highestByUser` — giữ bid cao nhất mỗi user bằng `merge`.
3. Viết phương thức `addBid(Bid b)` cập nhật cả hai map, và `highestOf(long userId)` trả bid cao nhất của user.

```java
import java.util.*;

public class BidIndex {

    public record Bid(long userId, long amount, long timestamp) {}

    // Long là wrapper immutable → key lý tưởng cho HashMap
    private final Map<Long, List<Bid>> bidsByUser = new HashMap<>();
    private final Map<Long, Bid> highestByUser = new HashMap<>();

    public void addBid(Bid b) {
        // Gom mọi bid của user: chưa có user → tạo list mới
        bidsByUser.computeIfAbsent(b.userId(), id -> new ArrayList<>()).add(b);

        // Giữ bid cao nhất: chưa có → dùng b; có rồi → so sánh giữ cái lớn hơn
        highestByUser.merge(
            b.userId(),
            b,
            (cur, incoming) -> incoming.amount() > cur.amount() ? incoming : cur
        );
    }

    public List<Bid> bidsOf(long userId) {
        return bidsByUser.getOrDefault(userId, List.of()); // null-safe
    }

    public Optional<Bid> highestOf(long userId) {
        return Optional.ofNullable(highestByUser.get(userId));
    }

    public static void main(String[] args) {
        BidIndex idx = new BidIndex();
        idx.addBid(new Bid(1L, 100, 1));
        idx.addBid(new Bid(1L, 150, 2));
        idx.addBid(new Bid(2L, 120, 3));

        System.out.println(idx.bidsOf(1L));        // 2 bid của user 1
        System.out.println(idx.highestOf(1L));     // Bid amount=150
        System.out.println(idx.highestOf(99L));    // Optional.empty
    }
}
```

> ✅ **Bài tập mở rộng:** Vì sao ta chọn `Long` (immutable) làm key mà không bọc `userId` trong một class mutable? Hãy thử đổi key thành một class mutable, sửa `userId` sau khi put, rồi quan sát `highestOf` trả sai.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: HashMap lưu trữ dữ liệu bên trong như thế nào?**
> **Đáp:** Bằng một mảng `Node<K,V>[] table` (các bucket). Mỗi `put` tính hash của key → tính index `(n-1) & hash` → đặt `Node` (chứa hash, key, value, next) vào bucket đó. Va chạm thì nối thành chuỗi liên kết, và treeify thành cây đỏ-đen khi bucket quá đông. Trung bình get/put là O(1).

**Q2: Vì sao phải override cả `equals()` và `hashCode()`?**
> **Đáp:** Vì HashMap dùng `hashCode()` để tìm đúng bucket, rồi `equals()` để tìm đúng key trong bucket. Hợp đồng: `a.equals(b)` đúng thì `a.hashCode()` phải bằng `b.hashCode()`. Nếu chỉ override `equals` mà quên `hashCode`, hai key "bằng nhau" lại rơi bucket khác → `get` trả null, `put` không ghi đè mà thêm mới (size phình to).

**Q3: HashMap có cho phép null key/value không?**
> **Đáp:** Cho **đúng một** null key (luôn ở bucket index 0, vì hàm hash trả 0 cho null) và **nhiều** null value. Khác với `Hashtable` và `ConcurrentHashMap` (cả hai không cho null).

**Q4: Load factor 0.75 nghĩa là gì?**
> **Đáp:** Khi số phần tử vượt `capacity * 0.75` (threshold), HashMap nở bảng gấp đôi và rehash. 0.75 là điểm cân bằng: cao hơn thì tiết kiệm RAM nhưng nhiều va chạm (chậm); thấp hơn thì nhanh nhưng tốn RAM và resize thường xuyên.

### Mức Senior

**Q5: Giải thích hàm băm `(h = key.hashCode()) ^ (h >>> 16)` và vì sao capacity là lũy thừa 2.**
> **Đáp:** Bước `^ (h >>> 16)` XOR 16 bit cao xuống 16 bit thấp ("perturbation") để trộn bit. Lý do: index bucket tính bằng `(n-1) & hash`, khi table nhỏ chỉ vài bit thấp được dùng; nếu key chỉ khác ở bit cao thì va chạm. Trộn bit cao xuống làm phân bố đều hơn. Capacity là lũy thừa 2 để `(n-1)` thành mặt nạ bit liên tiếp, khiến `& hash` tương đương `% n` nhưng nhanh hơn nhiều (AND bit thay cho chia lấy dư).

**Q6: Treeify diễn ra khi nào và để làm gì?**
> **Đáp:** Khi một bucket đạt ≥ 8 phần tử (`TREEIFY_THRESHOLD`) **và** dung lượng bảng ≥ 64 (`MIN_TREEIFY_CAPACITY`), Java chuyển chuỗi liên kết thành cây đỏ-đen → tìm kiếm trong bucket từ O(n) xuống O(log n). Nếu bảng < 64 thì resize thay vì treeify. Khi cây co về ≤ 6 phần tử (`UNTREEIFY_THRESHOLD`) trong lúc resize thì untreeify lại thành chuỗi. Mục đích: chống suy biến và chống hash-collision DoS.

**Q7: Vì sao key của HashMap nên immutable? Điều gì xảy ra nếu sửa key sau khi put?**
> **Đáp:** Hash được tính và lưu lúc put; bucket index dựa trên hash đó. Nếu sửa trường ảnh hưởng `hashCode()`, lần `get` sau tính ra hash mới → đi tới bucket khác → không thấy node dù nó vẫn nằm trong map ("lạc bucket"). Object không get/remove được → rò rỉ. Vì thế String/wrapper (immutable) là key lý tưởng; tự làm key thì dùng `record` hoặc class final bất biến.

**Q8: Vì sao HashMap không thread-safe và thay bằng gì?**
> **Đáp:** Khi nhiều thread put đồng thời trúng lúc resize, ở Java 7 có thể tạo vòng lặp vô tận trong chuỗi (CPU 100%); Java 8+ ít loop hơn nhưng vẫn mất dữ liệu/size sai. Đa luồng dùng `ConcurrentHashMap` — khóa theo từng bucket/bin và CAS nên nhiều thread ghi bucket khác nhau không chặn nhau. `Collections.synchronizedMap` khóa toàn cục, đơn giản nhưng nghẽn.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ được sơ đồ bucket array + chuỗi liên kết của HashMap bằng lời của mình
- [ ] Giải thích được `^ (h >>> 16)` và `(n-1) & hash`, vì sao capacity là lũy thừa 2
- [ ] Thuộc hợp đồng `equals`/`hashCode` và biết dùng `Objects.hash`/`Objects.equals`
- [ ] Chạy được DEMO BUG (quên hashCode) rồi sửa bằng override hoặc `record`
- [ ] Hiểu treeify (≥8 & cap≥64), untreeify (≤6), load factor 0.75 và resize
- [ ] Hoàn thành Mini Project: `BidIndex` với `computeIfAbsent` và `merge`
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Docs — `java.util.HashMap`, `java.util.Objects`
- OpenJDK source — `HashMap.java` (đọc hàm `hash()`, `putVal()`, `resize()`, `treeifyBin()`)
- Baeldung — "Java HashMap Under the Hood"
- Baeldung — "Guide to hashCode() and equals()"
- Sách *Effective Java* (Joshua Bloch) — Item 10 (equals), Item 11 (hashCode), Item 17 (immutability)
- JEP / tài liệu `record` (Java 16) — tự sinh `equals`/`hashCode`
