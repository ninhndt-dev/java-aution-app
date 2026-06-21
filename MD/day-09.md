# Day 09 - ArrayList Internals

> **Giai đoạn:** Collections & Generics
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu `ArrayList` **không phải phép màu**: bên trong chỉ là một **mảng `Object[]` (backing array)** cộng vài quy tắc quản lý.
- Phân biệt rạch ròi **`size` (số phần tử thực)** và **`capacity` (độ dài mảng nền)** — nhầm hai cái này là gốc của rất nhiều hiểu lầm về hiệu năng.
- Nắm **cơ chế tăng trưởng (grow)** khi mảng đầy: capacity mới ≈ **1.5×** cũ, qua công thức dịch bit `oldCapacity + (oldCapacity >> 1)`.
- Phân tích **độ phức tạp thật sự** của `add`, `add(index, e)`, `remove(index)`, `get(index)` — và vì sao thêm cuối là **amortized O(1)**.
- Hiểu **fail-fast / `ConcurrentModificationException`** qua biến `modCount`, và **cache locality** giúp `ArrayList` nhanh trong thực tế.
- Biết **khi nào KHÔNG nên** dùng `ArrayList`, và đối chiếu đúng bản chất với **mảng PHP** (vốn là *ordered hashtable*).

---

## 🧠 Lý thuyết cốt lõi

### 1. Bên trong `ArrayList` chỉ là một mảng

Đừng để cái tên "List" đánh lừa. Mở mã nguồn JDK (`java.util.ArrayList`) bạn sẽ thấy hai trường cốt lõi:

```java
transient Object[] elementData; // mảng nền (backing array) chứa phần tử thật
private int size;               // SỐ phần tử đang dùng (KHÔNG phải độ dài mảng)
```

`ArrayList` là **mảng động (dynamic array)**: nó bọc một mảng Java thuần (`Object[]`) và tự thay mảng to hơn khi đầy. Mọi thứ "co giãn được" của nó đều quy về thao tác trên mảng nền này.

> 💡 Vì phần tử thật được lưu trong `Object[]`, generic kiểu `ArrayList<String>` thực chất chỉ là **kiểm tra kiểu lúc biên dịch** (compile-time). Lúc chạy, nó vẫn là `Object[]` (hệ quả của *type erasure* — sẽ học sâu hơn ở ngày Generics). Đây là lý do `ArrayList` không thể có `new T[]` bên trong.

### 2. `size` vs `capacity` — hiểu một lần, nhớ mãi

Đây là cặp khái niệm quan trọng nhất hôm nay.

- **`size`** = số phần tử bạn **đã thêm vào** (cái `list.size()` trả về).
- **`capacity`** = `elementData.length` = **độ dài mảng nền** = số ô đã cấp phát, kể cả ô **trống dự phòng**.

Luôn có bất biến: `0 <= size <= capacity`.

```
ArrayList<String> list = new ArrayList<>();  // capacity = 0 (mảng rỗng dùng chung)
list.add("A");  // lần add đầu cấp mảng dài 10 -> capacity = 10, size = 1
list.add("B");  // size = 2, capacity vẫn 10
list.add("C");  // size = 3, capacity vẫn 10

Mảng nền elementData (capacity = 10):
 index:   0    1    2    3    4    5    6    7    8    9
        ┌────┬────┬────┬────┬────┬────┬────┬────┬────┬────┐
        │"A" │"B" │"C" │null│null│null│null│null│null│null│
        └────┴────┴────┴────┴────┴────┴────┴────┴────┴────┘
          └──── size = 3 ────┘ └──── 7 ô trống dự phòng ────┘
```

> ⚠️ `size()` **không** cho bạn biết tốn bao nhiêu RAM. Một list `size = 3` nhưng `capacity = 1_000_000` (do từng thêm rồi xóa nhiều) vẫn giữ mảng 1 triệu ô trong Heap. Muốn cắt phần thừa phải gọi `trimToSize()` (xem mục 5).

> 💡 Mẹo nhỏ về capacity khởi tạo: `new ArrayList<>()` khởi đầu với mảng rỗng (capacity = 0) và chỉ cấp mảng dài **10** ở lần `add` đầu tiên (lazy init). Còn `new ArrayList<>(100)` cấp ngay mảng dài 100.

### 3. Cơ chế tăng trưởng (grow): vì sao là ~1.5×

Khi `size == capacity` mà bạn còn `add`, mảng nền **không thể nhét thêm**. `ArrayList` phải:

1. Tính capacity mới (lớn hơn).
2. Cấp một mảng `Object[]` mới to hơn.
3. **Copy** toàn bộ phần tử cũ sang mảng mới (qua `Arrays.copyOf`, bản chất là `System.arraycopy`).
4. Trỏ `elementData` sang mảng mới; mảng cũ thành rác cho GC dọn.

Công thức tăng trưởng (rút gọn từ method `grow()` trong JDK):

```java
int newCapacity = oldCapacity + (oldCapacity >> 1); // = oldCapacity * 1.5
```

**Vì sao `>> 1` lại là chia 2?** `>>` là **dịch bit phải**. Dịch phải 1 bit = bỏ đi bit thấp nhất = **chia nguyên cho 2**. Ví dụ `oldCapacity = 10` → nhị phân `1010` → dịch phải 1 → `0101` = 5. Vậy:

```
newCapacity = 10 + (10 >> 1) = 10 + 5 = 15
```

Dùng dịch bit thay cho phép chia vì nó nhanh và biểu đạt rõ ý "lấy một nửa". Dãy capacity điển hình khi thêm liên tục từ list rỗng:

```
0 → 10 → 15 → 22 → 33 → 49 → 73 → 109 → ...   (mỗi bước ≈ ×1.5)
```

> 💡 Vì sao 1.5× mà không phải 2×? Hệ số 1.5 cân bằng giữa **số lần resize** (đừng quá nhiều) và **bộ nhớ lãng phí** (đừng phình quá mức). Hệ số 1.5 còn giúp các vùng nhớ cũ có cơ hội được tái sử dụng tốt hơn so với việc luôn nhân đôi.

### 4. Độ phức tạp các thao tác — con số phải nhớ

| Thao tác | Độ phức tạp | Vì sao |
|---|---|---|
| `get(index)` / `set(index, e)` | **O(1)** | Truy cập ngẫu nhiên trực tiếp: `elementData[index]`. Không duyệt. |
| `add(e)` (thêm cuối) | **Amortized O(1)** | Thường chỉ gán 1 ô. Thỉnh thoảng phải resize (O(n)) nhưng *trung bình* vẫn O(1). |
| `add(index, e)` (chèn giữa) | **O(n)** | Phải **dịch** mọi phần tử từ `index` trở đi sang phải 1 ô. |
| `remove(index)` (xóa giữa) | **O(n)** | Phải **dịch** mọi phần tử sau `index` sang trái 1 ô để lấp lỗ. |
| `contains(e)` / `indexOf(e)` | **O(n)** | Duyệt tuyến tính, so sánh `equals`. |
| `size()` / `isEmpty()` | **O(1)** | Chỉ đọc trường `size`. |

**Amortized O(1) là gì?** "Amortized" (chi phí khấu hao) nghĩa là tính **trung bình trên nhiều thao tác**. Phần lớn các lần `add` cuối chỉ tốn O(1) (gán 1 ô). Lâu lâu mới gặp một lần resize tốn O(n) (copy cả mảng). Vì các lần resize ngày càng **thưa dần** (capacity nhân 1.5 mỗi lần), tổng chi phí của n lần add vẫn là O(n) → **trung bình mỗi lần là O(1)**.

Còn chèn/xóa ở **giữa hay đầu** thì luôn phải dịch mảng:

```
remove(index = 1) trên list [A, B, C, D]:

 Trước:  [ A ][ B ][ C ][ D ]
                ▲ xóa ô này
 Dịch trái các phần tử C, D:
         [ A ][ C ][ D ][ - ]   <- System.arraycopy dịch (size - index - 1) phần tử
 size giảm 1, ô cuối gán null cho GC.
```

> ⚠️ `remove(int index)` và `remove(Object o)` là **hai method khác nhau**. `list.remove(2)` xóa **phần tử ở index 2**. `list.remove(Integer.valueOf(2))` xóa **phần tử có giá trị 2**. Nhầm hai cái này với `List<Integer>` là bẫy kinh điển.

### 5. `ensureCapacity` và `trimToSize` — chỉnh tay khi cần

- **`ensureCapacity(int min)`**: cấp trước capacity để **tránh resize nhiều lần**. Nếu bạn biết sắp thêm 1 triệu phần tử, hãy `new ArrayList<>(1_000_000)` hoặc `list.ensureCapacity(1_000_000)` **trước** vòng lặp → không phải copy mảng hàng chục lần.
- **`trimToSize()`**: cắt capacity về đúng `size`, **giải phóng RAM thừa**. Hữu ích khi list đã "ổn định" và không thêm nữa, mà trước đó capacity bị phình to.

```java
List<Integer> data = new ArrayList<>(); // capacity 0
((ArrayList<Integer>) data).ensureCapacity(1_000_000); // cấp 1 lần
for (int i = 0; i < 1_000_000; i++) data.add(i);        // 0 lần resize
```

> 💡 Trong vòng đời thật, 90% trường hợp bạn **không cần** đụng tới hai method này — `ArrayList` tự xoay xở tốt. Nhưng khi nạp dữ liệu lớn đã biết trước số lượng, `ensureCapacity` là tối ưu rẻ tiền mà hiệu quả.

### 6. Fail-fast & `ConcurrentModificationException` qua `modCount`

`ArrayList` giữ một bộ đếm **`modCount`** — tăng lên mỗi khi cấu trúc list bị **thay đổi** (add/remove làm đổi `size`). Khi bạn tạo iterator (kể cả vòng `for-each`), iterator **ghi nhớ** giá trị `modCount` lúc đó (`expectedModCount`). Mỗi bước lặp, nó so sánh; nếu khác nhau → ai đó đã sửa list giữa chừng → ném ngay **`ConcurrentModificationException`** (CME).

```java
List<String> list = new ArrayList<>(List.of("A", "B", "C"));
for (String s : list) {        // for-each => dùng iterator ngầm
    if (s.equals("B")) {
        list.remove(s);        // ❌ làm modCount != expectedModCount -> CME
    }
}
```

Đây là cơ chế **fail-fast**: phát hiện lỗi **sớm và rõ ràng** thay vì âm thầm chạy sai. Cách sửa: dùng `Iterator.remove()` (nó cập nhật cả `expectedModCount`), hoặc `list.removeIf(...)`:

```java
list.removeIf(s -> s.equals("B")); // ✅ an toàn, ngắn gọn
```

> ⚠️ CME **không phải** chỉ về đa luồng (multithread). Nó xảy ra ngay cả khi **một luồng duy nhất** sửa list trong khi đang iterate. Tên "Concurrent" gây hiểu lầm — hãy đọc là "modification while iterating".

### 7. Cache locality — vì sao thực tế `ArrayList` rất nhanh

Mảng nền nằm **liền kề trong bộ nhớ** (contiguous). Khi CPU đọc một phần tử, nó kéo cả một **cache line** (thường 64 byte) lên cache, kéo theo các phần tử kế bên. Lần truy cập tiếp theo gần như chắc chắn đã nằm sẵn trong cache → cực nhanh. CPU còn **prefetch** (đoán trước) khi thấy bạn duyệt tuần tự.

```
RAM:  [A][B][C][D][E][F] ...   <- liền kề, duyệt tuần tự = thân thiện cache
```

Trái lại, một danh sách liên kết (`LinkedList`) rải các node **khắp nơi** trong Heap; mỗi bước nhảy là một lần "cache miss" tốn kém. Đó là lý do vì sao **trong thực tế `ArrayList` thường thắng `LinkedList`** cả ở những phép mà lý thuyết Big-O tưởng như `LinkedList` lợi thế — hằng số ẩn của cache locality rất lớn.

### 8. Khi nào KHÔNG nên dùng `ArrayList`

- Cần **thêm/xóa nhiều ở đầu hoặc giữa** danh sách → mỗi thao tác O(n) do dịch mảng. Cân nhắc `ArrayDeque` (cho đầu/cuối) hoặc cấu trúc khác.
- Cần **hàng đợi (queue) / ngăn xếp (deque)** với thêm-xóa hai đầu hiệu quả → dùng **`ArrayDeque`** (nhanh hơn `LinkedList` cho mục đích này).
- Cần **tra cứu theo khóa** nhanh → dùng `HashMap`, không phải duyệt `ArrayList` O(n).
- Cần **không trùng lặp** → dùng `HashSet`/`LinkedHashSet`.

> 💡 Quy tắc ngón tay cái: nếu thao tác chủ yếu là **thêm-cuối + truy cập theo index + duyệt** → `ArrayList` gần như luôn là lựa chọn mặc định đúng.

---

## 🔁 Đối chiếu với Laravel/PHP

Điểm dễ vấp nhất khi từ PHP sang: **mảng PHP và `ArrayList` Java khác nhau về bản chất**, không chỉ cú pháp.

| Khái niệm | PHP / Laravel | Java `ArrayList` |
|---|---|---|
| Bản chất dữ liệu | **Ordered hashtable** (bảng băm có thứ tự chèn) | **Mảng động** chỉ số nguyên **liên tục** 0..size-1 |
| Khóa (key) | Bất kỳ: số **hoặc** chuỗi (`$a['name']`) | Chỉ **chỉ số nguyên** liên tục, không có khóa chuỗi |
| Thêm cuối | `$arr[] = $x;` | `list.add(x);` |
| Đếm phần tử | `count($arr)` | `list.size()` |
| Lấy theo vị trí | `$arr[2]` | `list.get(2)` |
| Xóa phần tử | `unset($arr[2])` → **để lại "lỗ hổng"**, index 2 biến mất nhưng 3,4 giữ nguyên | `list.remove(2)` → **dịch** các phần tử sau lên, index liền lại |
| Tái lập chỉ số | `array_values($arr)` để đánh số lại | Không cần — index luôn liên tục |
| Kiểu phần tử | Tùy ý, lẫn lộn được | Đồng nhất theo generic `ArrayList<T>` (kiểm lúc biên dịch) |

**Khác biệt tư duy quan trọng nhất:**

- PHP `$arr` cho phép `unset($arr[2])` rồi `$arr` còn các khóa `0, 1, 3, 4` — **index không liền nhau**, vì nó là bảng băm. Trong Java, `list.remove(2)` luôn **dồn** lại để index liền mạch `0, 1, 2, 3`; không bao giờ có "lỗ hổng index".
- PHP trộn lẫn key số và key chuỗi trong cùng một biến (`$arr[0]`, `$arr['name']`). Trong Java, cấu trúc "key tùy ý" đó tương ứng với **`Map`** (như `HashMap`/`LinkedHashMap`), **không phải** `ArrayList`. Nếu bạn thấy mình cần "key chuỗi", bạn đang cần `Map`, không phải `List`.
- `count($arr)` của PHP gần như O(1) (PHP lưu sẵn số phần tử), tương tự `size()`. Nhưng đừng suy ra rằng mọi thao tác đều cùng chi phí: `array_splice` chèn giữa của PHP cũng tốn kém như `add(index, e)` của Java.

> 🧩 Hệ quả: khi "dịch" code Laravel sang Java, hãy hỏi **"key là gì?"**. Key liên tục 0,1,2... → `ArrayList`. Key là chuỗi/định danh → `Map`. Chọn sai là gốc của hiệu năng tệ và code gượng ép.

---

## 💻 Thực hành code

### Bước 1 — Soi capacity tăng trưởng qua reflection

`capacity` không có getter công khai. Để "nhìn thấy" nó phình ra sao, ta dùng **reflection** đọc thẳng trường `elementData` rồi lấy `.length`.

```java
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class CapacitySpy {

    // Đọc capacity (độ dài mảng nền) của một ArrayList qua reflection.
    static int capacityOf(List<?> list) throws Exception {
        ___ f = ArrayList.class.getDeclaredField("elementData"); // Điền class dùng trong Reflection để lấy thuộc tính
        f.setAccessible(true); // bỏ qua kiểm soát truy cập private
        Object[] backingArray = (Object[]) f.get(list);
        return backingArray.length;
    }

    public static void main(String[] args) throws Exception {
        List<Integer> list = new ArrayList<>(); // capacity ban đầu = 0
        int prevCapacity = -1;

        System.out.printf("size=%d  capacity=%d%n", list.size(), capacityOf(list));

        for (int i = 0; i < 40; i++) {
            list.add(i);
            int cap = capacityOf(list);
            // Chỉ in khi capacity thay đổi -> thấy rõ các mốc grow().
            if (cap != prevCapacity) {
                System.out.printf("Sau add thứ %2d -> size=%2d  capacity=%2d  (GROW)%n",
                        i + 1, list.size(), cap);
                prevCapacity = cap;
            }
        }
    }
}
```

Kết quả minh họa (đúng với dãy 1.5×):

```
size=0  capacity=0
Sau add thứ  1 -> size= 1  capacity=10  (GROW)   <- lần add đầu cấp mảng dài 10
Sau add thứ 11 -> size=11  capacity=15  (GROW)   <- 10 + (10>>1) = 15
Sau add thứ 16 -> size=16  capacity=22  (GROW)   <- 15 + (15>>1) = 22
Sau add thứ 23 -> size=23  capacity=33  (GROW)   <- 22 + (22>>1) = 33
Sau add thứ 34 -> size=34  capacity=49  (GROW)   <- 33 + (33>>1) = 49
```

> ⚠️ Reflection ở đây chỉ để **học/quan sát**. Đừng dùng kiểu này trong code production để "lén" đọc nội bộ JDK — tên trường có thể đổi giữa các phiên bản, và module system (JPMS) có thể chặn `setAccessible`.

### Bước 2 — Benchmark: thêm cuối (nhanh) vs chèn đầu (chậm)

```java
import java.util.ArrayList;
import java.util.List;

public class AddBenchmark {

    static final int N = 100_000;

    public static void main(String[] args) {
        // --- Thêm vào CUỐI: amortized O(1) ---
        ___<Integer> tail = new ___<>(); // Điền interface và class cho mảng động
        long t0 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            tail.add(i);             // thêm cuối
        }
        long tailMs = (System.nanoTime() - t0) / 1_000_000;

        // --- Chèn vào ĐẦU: mỗi lần O(n) vì phải dịch toàn bộ mảng sang phải ---
        List<Integer> head = new ArrayList<>();
        long t1 = System.nanoTime();
        for (int i = 0; i < N; i++) {
            head.add(0, i);          // chèn đầu -> dịch (size) phần tử mỗi lần
        }
        long headMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf("Thêm cuối  %d phần tử: %4d ms%n", N, tailMs);
        System.out.printf("Chèn đầu   %d phần tử: %4d ms%n", N, headMs);
        System.out.printf("=> Chèn đầu chậm hơn khoảng %dx%n",
                headMs / Math.max(tailMs, 1));
    }
}
```

Bạn sẽ thấy chèn-đầu chậm hơn hàng trăm tới hàng nghìn lần: thêm-cuối tổng chi phí **O(n)**, còn chèn-đầu là **O(n²)** (mỗi lần dịch ~n phần tử, lặp n lần).

> 💡 Đây không phải benchmark "khoa học" (chưa warm-up JIT, chưa dùng JMH như đã nhắc ở Day 01). Nhưng khoảng cách lớn tới mức xu hướng O(n) vs O(n²) vẫn hiện rõ. Mục tiêu là **cảm nhận** sự khác biệt độ phức tạp.

### Bước 3 — Tái hiện và sửa `ConcurrentModificationException`

```java
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FailFastDemo {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>(List.of("A", "B", "C", "D"));

        // CÁCH SAI: sửa list trong for-each -> CME
        try {
            for (String s : list) {
                if (s.equals("B")) list.remove(s);
            }
        } catch (java.util.___ e) { // Điền ngoại lệ văng ra khi sửa collection lúc duyệt
            System.out.println("Bắt được CME như dự đoán: " + e);
        }

        // CÁCH ĐÚNG 1: dùng Iterator.remove()
        List<String> a = new ArrayList<>(List.of("A", "B", "C", "D"));
        Iterator<String> it = a.iterator();
        while (it.hasNext()) {
            if (it.next().equals("B")) it.remove(); // cập nhật expectedModCount
        }
        System.out.println("Sau Iterator.remove(): " + a); // [A, C, D]

        // CÁCH ĐÚNG 2: removeIf -> ngắn gọn, an toàn
        List<String> b = new ArrayList<>(List.of("A", "B", "C", "D"));
        b.removeIf(s -> s.equals("B"));
        System.out.println("Sau removeIf(): " + b);        // [A, C, D]
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Nhầm `size` với `capacity`.** `size()` là số phần tử, không phải RAM đã cấp. List `size=2` vẫn có thể giữ mảng capacity rất lớn nếu từng phình to.
- **`remove(int)` vs `remove(Object)` trên `List<Integer>`.** `list.remove(2)` xóa **index 2**; muốn xóa **giá trị 2** phải `list.remove(Integer.valueOf(2))`.
- **Sửa list khi đang for-each.** Gây `ConcurrentModificationException` (kể cả đơn luồng). Dùng `Iterator.remove()` hoặc `removeIf`.
- **Chèn/xóa ở đầu trong vòng lặp lớn.** Biến thuật toán thành O(n²) mà không nhận ra. Nếu thật sự cần thao tác hai đầu, đổi sang `ArrayDeque`.
- **Quên cấp capacity trước khi nạp dữ liệu lớn đã biết số lượng.** Để mặc định → hàng chục lần resize + copy mảng vô ích. Dùng `new ArrayList<>(n)` hoặc `ensureCapacity(n)`.
- **Tưởng `ArrayList` an toàn đa luồng.** **Không.** Nhiều thread cùng `add` có thể làm hỏng `elementData`/`size`. Dùng `Collections.synchronizedList`, `CopyOnWriteArrayList`, hoặc khóa riêng khi cần.
- **`list.toArray()` trả về `Object[]`.** Muốn mảng đúng kiểu phải `list.toArray(new String[0])`.

---

## 🚀 Liên hệ Spring Boot / Production

- **Trả JSON từ Controller:** một endpoint trả `List<BidDto>` được Jackson serialize thành mảng JSON. `ArrayList` là kiểu mặc định lý tưởng vì giữ **thứ tự** và serialize nhanh nhờ duyệt tuần tự (cache-friendly).
- **Kết quả từ JPA/Hibernate:** `repository.findAll()` thường trả về `List` (cài đặt nền là `ArrayList`). Khi map kết quả truy vấn lớn, nếu bạn ước lượng được số dòng, cân nhắc cấp capacity để giảm resize trong vòng map thủ công.
- **Bộ nhớ trong service long-running:** không như PHP (mỗi request reset), bean Spring **sống suốt vòng đời app**. Một `ArrayList` field bị nhồi mãi mà không dọn → **memory leak**. Nếu chỉ dùng tạm, hãy giải phóng hoặc dùng cấu trúc giới hạn kích thước.
- **DTO bất biến:** khi trả ra ngoài API, cân nhắc bọc `List.copyOf(list)` hoặc `Collections.unmodifiableList(...)` để chống sửa ngoài ý muốn — quan trọng với code đa luồng.
- **Đo trước khi tối ưu:** `ArrayList` nhanh tới mức hiếm khi là nút thắt. Đừng đổi sang cấu trúc lạ chỉ vì cảm tính — hãy đo bằng profiler trước.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp mạch Auction API. Hôm nay ta lưu **lịch sử ra giá (bid history)** của một phiên đấu giá bằng `ArrayList`, và **phân tích chi phí** từng thao tác.

**Bối cảnh:** Mỗi `AuctionItem` có một danh sách `Bid` theo **thứ tự thời gian** — bid mới luôn vào **cuối**. Đây đúng là kịch bản "vàng" cho `ArrayList`: thêm-cuối + truy cập theo index + duyệt.

```java
import java.util.ArrayList;
import java.util.List;

// Một lần ra giá: ai, bao nhiêu, lúc nào (epoch millis cho gọn).
record Bid(String bidder, long amount, long timestamp) {}

class AuctionItem {
    private final String name;
    private final List<Bid> bidHistory;

    AuctionItem(String name, int expectedBids) {
        this.name = name;
        // Cấp trước capacity nếu BIẾT TRƯỚC số bid lớn -> tránh resize nhiều lần.
        this.bidHistory = new ArrayList<>(Math.max(expectedBids, 1));
    }

    // Thêm bid mới: vào CUỐI -> amortized O(1).
    void placeBid(Bid bid) {
        if (!bidHistory.isEmpty()) {
            Bid last = bidHistory.get(bidHistory.size() - 1); // get cuối: O(1)
            if (bid.amount() <= last.amount()) {
                throw new IllegalArgumentException(
                        "Bid phải cao hơn giá hiện tại: " + last.amount());
            }
        }
        bidHistory.add(bid); // O(1) khấu hao
    }

    // Lấy bid theo thứ tự thời gian (index): O(1) truy cập ngẫu nhiên.
    Bid bidAt(int index) {
        return bidHistory.get(index);
    }

    // Bid cao nhất hiện tại = bid cuối (vì ta ép tăng dần): O(1).
    Bid highestBid() {
        if (bidHistory.isEmpty()) return null;
        return bidHistory.get(bidHistory.size() - 1);
    }

    // Hủy một bid ở GIỮA (ví dụ gian lận): O(n) vì phải dịch mảng.
    Bid cancelBidAt(int index) {
        return bidHistory.remove(index); // remove(index) dịch các phần tử sau lên
    }

    int totalBids() { return bidHistory.size(); }
    String name() { return name; }
}

public class AuctionDemo {
    public static void main(String[] args) {
        AuctionItem laptop = new AuctionItem("Laptop cũ", 1000); // dự kiến ~1000 bid

        laptop.placeBid(new Bid("an",  1_000_000, 1_000));
        laptop.placeBid(new Bid("binh", 1_200_000, 2_000));
        laptop.placeBid(new Bid("chi",  1_500_000, 3_000));

        System.out.println("Sản phẩm: " + laptop.name());
        System.out.println("Tổng số bid: " + laptop.totalBids());          // 3
        System.out.println("Bid đầu tiên (index 0): " + laptop.bidAt(0));  // O(1)
        System.out.println("Bid cao nhất hiện tại: " + laptop.highestBid()); // O(1)

        // Hủy bid giữa (index 1) -> O(n), index dồn lại liền mạch.
        Bid cancelled = laptop.cancelBidAt(1);
        System.out.println("Đã hủy bid: " + cancelled);
        System.out.println("Tổng số bid sau hủy: " + laptop.totalBids());  // 2
    }
}
```

**Nhiệm vụ Day 09:**
0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Cài đặt `AuctionItem` với `ArrayList<Bid>` lưu lịch sử ra giá theo thứ tự thời gian.
2. Viết comment cạnh **mỗi** method ghi rõ độ phức tạp: `placeBid` (cuối, **amortized O(1)**), `bidAt`/`highestBid` (index, **O(1)**), `cancelBidAt` (giữa, **O(n)**).
3. Khi khởi tạo, dùng **`ensureCapacity`/constructor capacity** nếu ước lượng số bid lớn; giải thích trong notes vì sao điều đó giảm số lần resize.
4. Tái hiện `ConcurrentModificationException` bằng cách thử xóa bid trong `for-each`, rồi sửa lại bằng `removeIf` (ví dụ "hủy mọi bid của một người gian lận").
5. Ghi vào `notes/day-09.md`: với phiên đấu giá có nhiều lượt hủy bid **ở giữa**, `ArrayList` còn là lựa chọn tốt không? Vì sao? (Gợi ý: cân nhắc tần suất thao tác).

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `size` và `capacity` của `ArrayList` khác nhau thế nào?**
> **Đáp:** `size` là **số phần tử thực** đang có (`size()` trả về). `capacity` là **độ dài mảng nền** `elementData.length` — số ô đã cấp phát, gồm cả ô trống dự phòng. Luôn có `size <= capacity`. `size` không phản ánh RAM đã dùng; capacity mới quyết định điều đó.

**Q2: `ArrayList` lớn lên thế nào khi đầy?**
> **Đáp:** Khi `size == capacity` mà còn add, nó gọi `grow()`: tính capacity mới ≈ 1.5× cũ qua `oldCapacity + (oldCapacity >> 1)`, cấp mảng `Object[]` mới to hơn, `Arrays.copyOf` chép phần tử cũ sang, rồi trỏ `elementData` sang mảng mới. Mảng cũ thành rác cho GC.

**Q3: Vì sao `add` cuối là amortized O(1) nhưng `add(index, e)` ở giữa lại O(n)?**
> **Đáp:** Thêm cuối thường chỉ gán 1 ô; chỉ thỉnh thoảng phải resize (O(n)) nhưng vì resize thưa dần nên trung bình mỗi lần là O(1) (amortized). Chèn giữa luôn phải **dịch** mọi phần tử từ `index` trở đi sang phải 1 ô (qua `System.arraycopy`) → O(n) mỗi lần.

**Q4: `get(index)` nhanh cỡ nào và vì sao?**
> **Đáp:** O(1). Vì backing là mảng liền kề, truy cập `elementData[index]` là phép tính địa chỉ trực tiếp (random access), không cần duyệt.

### Mức Senior

**Q5: `>> 1` trong công thức tăng trưởng nghĩa là gì, và vì sao chọn hệ số 1.5 thay vì 2?**
> **Đáp:** `>> 1` là dịch bit phải 1 vị trí = chia nguyên cho 2, nên `oldCapacity + (oldCapacity >> 1) = 1.5 × oldCapacity`. Hệ số 1.5 cân bằng giữa **số lần resize** (đừng quá nhiều, vì mỗi lần copy O(n)) và **bộ nhớ lãng phí** (đừng phình quá mức như khi nhân đôi). 1.5 cũng cho phép tái sử dụng vùng nhớ cũ tốt hơn so với việc luôn ×2.

**Q6: Giải thích cơ chế fail-fast và `ConcurrentModificationException`. Nó có liên quan tới đa luồng không?**
> **Đáp:** `ArrayList` giữ `modCount`, tăng mỗi khi cấu trúc đổi. Iterator ghi nhớ `expectedModCount` lúc tạo; mỗi bước lặp so sánh, nếu khác → ném CME ngay (fail-fast). Nó **không** chỉ về đa luồng: chỉ cần **một luồng** sửa list trong khi iterate là đã CME. Cách an toàn: `Iterator.remove()`, `removeIf`, hoặc `CopyOnWriteArrayList` cho ngữ cảnh đa luồng đọc-nhiều-ghi-ít.

**Q7: Vì sao trong thực tế `ArrayList` thường nhanh hơn `LinkedList` ngay cả ở vài thao tác mà Big-O tưởng `LinkedList` lợi?**
> **Đáp:** Nhờ **cache locality**. Mảng nền liền kề trong RAM; CPU kéo cả cache line và prefetch khi duyệt tuần tự → ít cache miss. `LinkedList` rải node khắp Heap, mỗi bước nhảy là một cache miss đắt đỏ, cộng overhead cấp phát/GC mỗi node. Hằng số ẩn của cache locality lớn tới mức lấn át lợi thế Big-O lý thuyết của `LinkedList`.

**Q8: Khi nào bạn KHÔNG chọn `ArrayList`, và chọn gì thay thế?**
> **Đáp:** Khi thao tác chủ yếu là thêm/xóa ở **đầu hoặc giữa** (mỗi lần O(n)) → dùng `ArrayDeque` cho hai đầu. Cần **queue/stack/deque** hiệu quả → `ArrayDeque`. Cần tra cứu theo khóa → `HashMap`. Cần phần tử không trùng → `HashSet`/`LinkedHashSet`. Cần đa luồng ghi nhiều → cấu trúc concurrent chuyên dụng. `ArrayList` là mặc định cho: thêm-cuối + get theo index + duyệt.

**Q9: `ensureCapacity` và `trimToSize` giải quyết vấn đề gì trong production?**
> **Đáp:** `ensureCapacity(n)` (hoặc constructor `new ArrayList<>(n)`) cấp trước mảng khi đã biết số phần tử lớn → loại bỏ hàng loạt resize+copy, giảm áp lực GC khi nạp dữ liệu khối lượng lớn. `trimToSize()` cắt capacity về đúng `size`, giải phóng RAM thừa cho list đã ổn định — hữu ích khi list từng phình to rồi co lại và sống lâu trong service.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được `size` vs `capacity` bằng lời của mình (không nhìn tài liệu)
- [ ] Vẽ được sơ đồ backing array và mô tả cơ chế `grow()` ≈ 1.5×
- [ ] Hiểu vì sao `add` cuối là amortized O(1) còn chèn/xóa giữa là O(n)
- [ ] Tự chạy code reflection quan sát capacity tăng qua từng lần add
- [ ] Tái hiện và sửa được `ConcurrentModificationException`
- [ ] Giải thích được cache locality và khi nào KHÔNG dùng `ArrayList`
- [ ] Đối chiếu đúng bản chất mảng PHP (ordered hashtable) vs `ArrayList`
- [ ] Hoàn thành nhiệm vụ Mini Project Day 09
- [ ] Trả lời được 9 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Docs — `java.util.ArrayList`, `java.util.List` (đọc phần mô tả về capacity)
- Mã nguồn OpenJDK — `ArrayList.java` (đọc method `grow()`, `add()`, `remove()`, biến `modCount`)
- Baeldung — "Guide to the Java ArrayList" và "ArrayList vs LinkedList"
- Baeldung — "ConcurrentModificationException in Java"
- Sách *Java Generics and Collections* (Naftalin & Wadler) — chương về List
- Bài viết về **cache locality** và "Why ArrayList beats LinkedList in practice" (tham khảo khi cần đào sâu)
