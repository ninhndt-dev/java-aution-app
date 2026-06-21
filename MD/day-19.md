# Day 19 - Race Condition

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã nắm thread & `synchronized` (Day 18), giờ đào sâu vì sao concurrency lại "khó".

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **race condition** thực sự là gì: kết quả phụ thuộc vào **thứ tự/timing** thực thi không xác định của nhiều thread trên **shared mutable state**.
- Nhận diện được **gốc rễ** mọi vấn đề concurrency: **trạng thái khả biến được chia sẻ** (shared mutable state).
- Hiểu vì sao `count++` **không nguyên tử** (atomic) — nó là 3 bước read-modify-write — và vì sao điều đó gây **lost update** (mất cập nhật).
- Phân loại được các dạng race: **lost update**, **check-then-act**, **read-modify-write**.
- Biết 3 nhóm cách khắc phục: **(1)** khóa (`synchronized`/`Lock`), **(2)** biến nguyên tử (`Atomic*` + **CAS**), **(3)** thiết kế **bất biến / không chia sẻ** state.
- Phân biệt rạch ròi **`volatile` (visibility)** vs **atomicity** — sai lầm kinh điển của người mới.
- Liên hệ với Laravel: vì sao PHP "shared-nothing" hiếm gặp race in-memory, nhưng race vẫn xảy ra **ở tầng DB/cache**.

---

## 🧠 Lý thuyết cốt lõi

### 1. Race condition là gì? — Định nghĩa cho chuẩn

> **Race condition** (điều kiện tranh chấp) xảy ra khi **tính đúng đắn** của chương trình phụ thuộc vào **thứ tự hoặc thời điểm (timing)** mà nhiều thread truy cập/sửa đổi cùng một dữ liệu chia sẻ — trong khi thứ tự đó **không được đảm bảo**.

Từ khoá "race" (cuộc đua): nhiều thread cùng "đua" chạm vào một dữ liệu, ai "tới trước" quyết định kết quả. Vì lịch thread (thread scheduling) do **OS/JVM tự quyết**, ta **không kiểm soát được** ai thắng → kết quả **không xác định** (non-deterministic): chạy 5 lần có thể ra 5 kết quả khác nhau.

```
Thread A ──┐
           ├──►  [ shared mutable state ]   ← ai chạm vào lúc nào? Không ai biết!
Thread B ──┘
```

> 💡 Bug race condition cực khó debug vì nó **không tái hiện ổn định**: chạy trên máy bạn thì "ổn", lên production tải cao thì sai. Người ta gọi đùa là **Heisenbug** — cứ thêm log/debug vào (làm chậm code) thì nó... biến mất.

### 2. Gốc rễ của mọi vấn đề: Shared Mutable State

Mọi bug concurrency đều quy về **ba điều kiện cùng xảy ra**:

| Điều kiện | Giải thích | Phá vỡ nó bằng cách... |
|---|---|---|
| **Shared** (chia sẻ) | Nhiều thread cùng thấy một object/biến | Đừng chia sẻ — mỗi thread một bản (confinement, copy) |
| **Mutable** (khả biến) | Dữ liệu đó **thay đổi được** | Làm nó **bất biến** (immutable) — chỉ đọc |
| **Không đồng bộ hoá** | Truy cập không được bảo vệ | Dùng khóa hoặc biến nguyên tử |

Chỉ cần **phá vỡ một** trong ba là an toàn. Đây là tư duy cốt lõi:

```
       Shared  ✕  Mutable  ✕  Unsynchronized   =   💥 Race
         │           │              │
       (bỏ chia sẻ) (làm immutable) (đồng bộ hoá)  =  ✅ An toàn
```

> 💡 Câu thần chú của dân concurrency: *"State is the enemy."* Càng ít state khả biến được chia sẻ, code càng dễ đúng. Nhiều framework hiện đại (và cả PHP) đúng vì chúng **không chia sẻ state** giữa request.

### 3. Vì sao `count++` KHÔNG nguyên tử? — Trái tim của vấn đề

Nhìn thì `count++` là **một** lệnh. Thực tế nó là **3 bước** (read-modify-write):

```
count++   tương đương:
   1. READ   : đọc giá trị hiện tại của count vào thanh ghi  (tmp = count)
   2. MODIFY : cộng 1                                        (tmp = tmp + 1)
   3. WRITE  : ghi lại vào count                             (count = tmp)
```

Trong bytecode JVM (nhớ Day 01) nó là chuỗi `getfield → iadd → putfield` — **3 thao tác tách rời**, và thread có thể **bị ngắt giữa chừng** bởi scheduler.

**Sơ đồ timeline 2 thread xen kẽ (interleaving) gây LOST UPDATE:**

Giả sử `count = 0`, hai thread A và B cùng chạy `count++`. Kỳ vọng: kết quả = 2.

```
Thời gian │ Thread A              │ Thread B              │ count (RAM)
──────────┼───────────────────────┼───────────────────────┼────────────
   t1     │ READ count → tmpA=0   │                       │   0
   t2     │                       │ READ count → tmpB=0   │   0   ← B đọc trước khi A ghi!
   t3     │ tmpA = 0 + 1 = 1      │                       │   0
   t4     │                       │ tmpB = 0 + 1 = 1      │   0
   t5     │ WRITE count = 1       │                       │   1
   t6     │                       │ WRITE count = 1       │   1   ← GHI ĐÈ! Mất 1 lần ++
──────────┴───────────────────────┴───────────────────────┴────────────
                                              Kết quả = 1  (kỳ vọng 2) → LOST UPDATE
```

Cập nhật của A bị B **ghi đè** vì B đọc giá trị **cũ** (0) trước khi A kịp ghi (1). Một lần `++` "bốc hơi". Đây là **lost update** — dạng race phổ biến nhất.

> ⚠️ Đừng tưởng "lệnh ngắn thì an toàn". Ngay cả `count = count + 1` hay `balance -= amount` đều là read-modify-write **không nguyên tử**. `long`/`double` 64-bit thậm chí việc đọc/ghi cũng có thể bị xé đôi (word tearing) trên một số nền tảng cũ nếu không `volatile`.

### 4. Ví dụ kinh điển: Counter sai

```java
class Counter {
    private int count = 0;
    public void increment() { count++; }   // KHÔNG an toàn!
    public int get() { return count; }
}
```

Cho 10 thread, mỗi thread `increment()` 100_000 lần. Kỳ vọng cuối cùng: `1_000_000`. Thực tế ta thường nhận **một số nhỏ hơn**, và **mỗi lần chạy ra một số khác** — bằng chứng sống của race condition. (Code đầy đủ ở mục Thực hành.)

### 5. Phân loại race condition

Có 3 "khuôn mặt" hay gặp:

**(a) Lost Update (mất cập nhật)** — như mục 3: đọc–sửa–ghi đè lên nhau.

**(b) Read-modify-write** — tổng quát của (a): bất kỳ thao tác nào "đọc giá trị, tính toán dựa trên nó, rồi ghi lại" mà không nguyên tử:
```java
balance = balance - amount;   // trừ tiền
stock   = stock - 1;          // trừ tồn kho
max     = Math.max(max, x);   // cập nhật cực đại
```

**(c) Check-then-act (kiểm tra rồi hành động)** — kiểm tra một điều kiện, rồi hành động dựa trên kết quả kiểm tra đó; nhưng **giữa lúc kiểm tra và lúc hành động**, thread khác đã thay đổi state:

```java
// Ví dụ 1: "lazy init" / put-if-absent
if (map.get(key) == null) {     // CHECK: chưa có key
    map.put(key, value);        // ACT: thêm vào
}   // → 2 thread cùng thấy null → cùng put → ghi đè / tạo trùng

// Ví dụ 2: kiểm tra số dư rồi trừ tiền
if (balance >= amount) {        // CHECK: đủ tiền
    balance -= amount;          // ACT: trừ
}   // → 2 thread cùng thấy "đủ" → cùng trừ → âm tiền / chi vượt quỹ
```

```
Thread A: if (balance >= 100) ───────────────► balance -= 100
Thread B:        if (balance >= 100) ──► balance -= 100
                 ↑ cả hai cùng thấy "đủ" tại đây, balance=100
          → trừ 2 lần → balance = -100  (chi vượt!)
```

> 💡 Check-then-act là nguồn gốc các bug "bán quá số lượng", "trùng username khi 2 người đăng ký cùng lúc", "double-spending". Nó **kín đáo** hơn lost update nên dễ lọt qua review.

### 6. Khắc phục — Ba nhóm giải pháp

#### 6.1. Khóa: `synchronized` / `Lock` (đã học Day 18)

Biến đoạn read-modify-write thành **vùng tới hạn** (critical section) — chỉ một thread vào tại một thời điểm:

```java
public synchronized void increment() { count++; }   // an toàn, nhưng có chi phí khóa
```

Đúng nhưng **đắt** khi tranh chấp cao (thread phải chờ nhau). Dùng khi cần khóa **nhiều biến cùng lúc** hoặc logic phức tạp.

#### 6.2. Biến nguyên tử: `AtomicInteger` / `AtomicLong` / `AtomicReference` + CAS

Đây là **ngôi sao** của Day 19. Các lớp trong `java.util.concurrent.atomic` thực hiện read-modify-write **nguyên tử mà không cần khóa** (lock-free), nhờ chỉ thị phần cứng **CAS (Compare-And-Swap)**.

**CAS là gì?** Một thao tác nguyên tử ở mức CPU, ý nghĩa:
> "Tôi *nghĩ* giá trị hiện tại là `expected`. Nếu đúng vậy, hãy đổi nó thành `newValue` **một cách nguyên tử** và báo thành công. Nếu không (ai đó đã đổi rồi), **đừng làm gì** và báo thất bại."

```
CAS(địa_chỉ, expected, newValue):
    nếu *địa_chỉ == expected:
        *địa_chỉ = newValue
        return true        // thành công
    else:
        return false       // có người chen ngang → thử lại
```

**Vòng lặp CAS (CAS loop)** — mẫu thiết kế cốt lõi của lock-free: đọc giá trị cũ, tính giá trị mới, CAS; nếu thất bại thì lặp lại với giá trị mới nhất:

```java
int oldValue, newValue;
do {
    oldValue = atomic.get();          // đọc
    newValue = oldValue + 1;          // tính
} while (!atomic.compareAndSet(oldValue, newValue));   // thử ghi, fail thì lặp
```

`incrementAndGet()` chính là vòng lặp CAS này được đóng gói sẵn. Các phương thức quan trọng:

| Phương thức | Ý nghĩa |
|---|---|
| `incrementAndGet()` / `getAndIncrement()` | `++count` nguyên tử (trả về sau / trước khi tăng) |
| `addAndGet(n)` / `getAndAdd(n)` | cộng `n` nguyên tử |
| `compareAndSet(expect, update)` | CAS thủ công, trả `boolean` |
| `updateAndGet(x -> ...)` | cập nhật theo **hàm**, tự lặp CAS bên trong |
| `accumulateAndGet(y, (x,y)->...)` | gộp giá trị mới với hàm tích luỹ |

> 💡 So với khóa: dưới tranh chấp **vừa phải**, atomic **nhanh hơn nhiều** vì không có context switch, không có thread bị "ngủ". Dưới tranh chấp **cực cao**, vòng lặp CAS có thể retry nhiều (tốn CPU) — khi đó cân nhắc `LongAdder`.

#### 6.3. Thiết kế bất biến / không chia sẻ (thường là tốt nhất)

- **Immutable** (bất biến): object tạo ra rồi **không đổi** (mọi field `final`). Không có gì để "ghi đè" → không thể có race. `String`, `Integer`, `record` Java 21 là ví dụ. Muốn "đổi" thì tạo object mới.
- **Thread confinement** (giam giữ trong thread): không chia sẻ — biến cục bộ (local variable) trên stack vốn đã an toàn; mỗi request một object riêng.
- **`ThreadLocal`**: mỗi thread giữ một bản riêng của biến → không đụng nhau (ví dụ `SimpleDateFormat`, user context).
- **Copy-on-share**: muốn đưa dữ liệu cho thread khác thì **copy** một bản bất biến.

> 💡 Thứ tự ưu tiên khi thiết kế: **(1) không chia sẻ → (2) bất biến → (3) atomic → (4) khóa.** Khóa là "vũ khí cuối", không phải lựa chọn đầu tiên.

### 7. `volatile`: chỉ đảm bảo VISIBILITY, KHÔNG đảm bảo ATOMICITY

Đây là điểm gây nhầm lẫn bậc nhất. Hai khái niệm khác nhau:

| Khái niệm | Câu hỏi nó trả lời | `volatile` lo? | `synchronized`/atomic lo? |
|---|---|---|---|
| **Visibility** (khả kiến) | "Thread B có **thấy** giá trị mới nhất mà A vừa ghi không?" | ✅ Có | ✅ Có |
| **Atomicity** (nguyên tử) | "Thao tác read-modify-write có **không bị xen kẽ** không?" | ❌ **KHÔNG** | ✅ Có |

`volatile` đảm bảo: mỗi lần đọc/ghi biến đi **thẳng vào bộ nhớ chính** (không cache cục bộ ở thread), nên thay đổi của thread này **được thấy ngay** bởi thread khác. Nhưng nó **không** biến `count++` thành nguyên tử — vì `++` vẫn là 3 bước, vẫn bị xen kẽ:

```java
volatile int count = 0;
count++;   // VẪN SAI! volatile không cứu được read-modify-write
```

```
volatile  →  visibility  ✔   atomicity  ✘
                              (count++ vẫn lost update)
```

**Khi nào `volatile` đủ?** Khi ghi **không phụ thuộc giá trị cũ** — ví dụ một cờ `boolean running`:
```java
volatile boolean running = true;   // thread khác set false để dừng vòng lặp → OK
```
Đây là ghi đơn lẻ (set thẳng), không read-modify-write, nên `volatile` là đủ.

> ⚠️ Quy tắc nhớ đời: **`volatile` cho cờ (flag), atomic/lock cho bộ đếm (counter).** Nhầm hai cái này là bug production điển hình.

### 8. Atomic compound action với `ConcurrentHashMap`

Nhớ ví dụ check-then-act với `map`? `ConcurrentHashMap` cung cấp các thao tác **gộp nguyên tử** thay cho `if-check-then-put`:

| Thay vì... (check-then-act, KHÔNG an toàn) | Dùng... (nguyên tử, an toàn) |
|---|---|
| `if (map.get(k)==null) map.put(k,v);` | `map.putIfAbsent(k, v);` |
| khởi tạo lazy có tính toán nặng | `map.computeIfAbsent(k, key -> create(key));` |
| đọc–cộng–ghi vào value | `map.merge(k, 1, Integer::sum);` (đếm tần suất) |
| cập nhật value theo giá trị cũ | `map.compute(k, (key,old) -> ...);` |

```java
// SAI: 2 thread cùng thấy null → tạo 2 connection (rò rỉ tài nguyên)
if (pool.get(host) == null) pool.put(host, openConnection(host));

// ĐÚNG: computeIfAbsent đảm bảo hàm tạo chỉ chạy MỘT lần cho mỗi key
pool.computeIfAbsent(host, h -> openConnection(h));
```

> 💡 `computeIfAbsent` không chỉ tránh race — nó còn đảm bảo **hàm khởi tạo chỉ chạy đúng một lần** cho mỗi key, rất hợp cho cache/connection pool.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là phần "lật ngược thế cờ" cho dân PHP: vì sao bạn **chưa từng gặp** race in-memory nhưng nó vẫn rình rập bạn.

**Mô hình "shared-nothing" của PHP:** Mỗi HTTP request được php-fpm xử lý trong một **process riêng**, có **bộ nhớ riêng**, và **chết** khi request xong. Hai request **không** chia sẻ biến trong RAM. Vì vậy:
- Một biến `$counter` trong code Laravel **không bao giờ** bị race giữa hai request — chúng ở hai vùng nhớ khác nhau.
- Điều kiện "shared mutable state" **không tồn tại in-memory** → race in-memory cực hiếm.

Java/Spring thì ngược lại: app là **một process chạy lâu dài**, nhiều thread (mỗi request một thread) **dùng chung Heap**. Một `@Service` singleton có field khả biến → tất cả request **chia sẻ** field đó → **race**.

**NHƯNG** — và đây là điểm mấu chốt — race **vẫn xảy ra trong Laravel**, chỉ là **dịch xuống tầng DB/cache**:

```
Java in-memory race           ≈   Laravel DB/cache race  (cùng bản chất!)
─────────────────────────────────────────────────────────────────────
2 thread cùng count++          ≈   2 request cùng UPDATE stock = stock - 1
2 thread cùng đọc balance      ≈   2 request cùng SELECT balance rồi trừ
shared mutable = field RAM     ≈   shared mutable = ROW trong DB / KEY trong Redis
```

Hai request Laravel cùng lúc cùng "kiểm tra tồn kho rồi trừ 1" → **bán quá số lượng**. Đó **chính xác** là check-then-act race, chỉ khác nơi xảy ra.

**Laravel khắc phục thế nào** (đối chiếu với Java):

| Vấn đề | Java (in-memory) | Laravel (DB/cache) |
|---|---|---|
| Read-modify-write | `AtomicInteger.incrementAndGet()` | `DB::table('p')->increment('stock')` / `DB::raw('stock - 1')` (nguyên tử ở DB) |
| Check-then-act / khóa bi quan | `synchronized` / `Lock` | `DB::transaction()` + `->lockForUpdate()` (pessimistic lock) |
| CAS (so sánh-rồi-đổi) | `compareAndSet(old, new)` | Optimistic lock: cột `version`, `UPDATE ... WHERE version = ?` |
| Khóa phân tán | (không cần nếu 1 JVM) | `Cache::lock('key')->get()` (atomic lock qua Redis) |
| Không chia sẻ state | local var / `ThreadLocal` | mỗi request một process (mặc định đã có sẵn) |

> 🧩 Insight chuyển ngữ: bạn **đã** xử lý race condition trong Laravel rồi — mỗi lần dùng `lockForUpdate()` hay `increment()` là bạn đang chống race ở tầng DB. Java chỉ kéo cuộc chiến đó **vào trong RAM**, nơi `AtomicInteger` đóng vai `DB::increment`, còn `synchronized` đóng vai `lockForUpdate`. Cùng một bài toán, khác tầng.

> ⚠️ Cảnh báo cho người mới chuyển: thói quen PHP "cứ để biến static/global thoải mái" là **chí mạng** ở Java. Một `private static Map cache` trong `@Service` Spring bị **mọi request chia sẻ** — phải dùng `ConcurrentHashMap`, không phải `HashMap`.

---

## 💻 Thực hành code

### (a) Tái hiện bug counter — lost update có thật

```java
import java.util.concurrent.*;

public class RaceCounterDemo {

    // Bộ đếm KHÔNG an toàn
    static int unsafeCount = 0;

    public static void main(String[] args) throws Exception {
        final int THREADS = 10;
        final int LOOPS   = 100_000;
        final int EXPECTED = THREADS * LOOPS;   // 1_000_000

        // Chạy nhiều lần để thấy kết quả KHÁC nhau mỗi lần
        for (int run = 1; run <= 5; run++) {
            unsafeCount = 0;
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch done = new CountDownLatch(THREADS);

            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < LOOPS; i++) {
                        unsafeCount++;          // ← read-modify-write, KHÔNG nguyên tử
                    }
                    done.countDown();
                });
            }
            done.await();                       // đợi tất cả thread xong
            pool.shutdown();

            int lost = EXPECTED - unsafeCount;
            System.out.printf("Lần %d | kỳ vọng=%d | thực tế=%d | MẤT=%d%n",
                    run, EXPECTED, unsafeCount, lost);
        }
    }
}
```

Kết quả mẫu (máy bạn sẽ ra **số khác**, và **mỗi lần khác nhau** — đó là bằng chứng race):

```
Lần 1 | kỳ vọng=1000000 | thực tế=947213 | MẤT=52787
Lần 2 | kỳ vọng=1000000 | thực tế=981044 | MẤT=18956
Lần 3 | kỳ vọng=1000000 | thực tế=903778 | MẤT=96222
...
```

### (b) Sửa bằng `AtomicInteger` — luôn đúng

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AtomicCounterDemo {

    static final AtomicInteger safeCount = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        final int THREADS = 10, LOOPS = 100_000;
        final int EXPECTED = THREADS * LOOPS;

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            CountDownLatch done = new CountDownLatch(THREADS);
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < LOOPS; i++) {
                        safeCount.___();   // Điền phương thức tăng nguyên tử
                    }
                    done.countDown();
                });
            }
            done.await();
        }   // try-with-resources tự shutdown ExecutorService (Java 21)

        System.out.printf("kỳ vọng=%d | thực tế=%d | đúng=%b%n",
                EXPECTED, safeCount.get(), safeCount.get() == EXPECTED);
        // → luôn in: thực tế=1000000 | đúng=true, chạy bao nhiêu lần cũng vậy
    }
}
```

> 💡 Lưu ý Java 21: `ExecutorService` đã `implements AutoCloseable`, dùng `try-with-resources` để tự `close()` (block tới khi mọi task xong rồi shutdown) — gọn hơn `shutdown()` + `awaitTermination()` thủ công.

### (c) Cập nhật có điều kiện bằng `compareAndSet` / `updateAndGet`

```java
import java.util.concurrent.atomic.AtomicInteger;

public class ConditionalUpdateDemo {

    // Cập nhật "giá trị lớn nhất từng thấy" — read-modify-write có điều kiện
    static final AtomicInteger maxSeen = new AtomicInteger(Integer.MIN_VALUE);

    // Cách 1: vòng lặp CAS thủ công
    static void updateMaxManual(int candidate) {
        int cur;
        do {
            cur = maxSeen.get();
            if (candidate <= cur) return;             // không lớn hơn → bỏ qua
        } while (!maxSeen.___(cur, candidate)); // Điền phương thức so sánh và thiết lập nguyên tử
    }

    // Cách 2: updateAndGet — gọn hơn, tự lặp CAS bên trong
    static void updateMaxLambda(int candidate) {
        maxSeen.___(cur -> Math.max(cur, candidate)); // Điền phương thức cập nhật và lấy giá trị
    }

    public static void main(String[] args) {
        updateMaxManual(10);
        updateMaxLambda(7);     // 7 < 10 → bỏ qua
        updateMaxLambda(25);    // 25 > 10 → cập nhật
        System.out.println("max = " + maxSeen.get());  // 25
    }
}
```

> ⚠️ Hàm truyền vào `updateAndGet`/`accumulateAndGet` **phải thuần (pure) & không có side-effect** — vì khi CAS thất bại nó sẽ **chạy lại nhiều lần**. Đừng đặt `System.out.println` hay ghi DB trong đó.

### (d) `ConcurrentHashMap.computeIfAbsent` thay check-then-act

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ComputeIfAbsentDemo {

    // Đếm số lần khởi tạo để CHỨNG MINH hàm tạo chỉ chạy 1 lần/key
    static final AtomicInteger initCalls = new AtomicInteger(0);
    static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    static String expensiveCreate(String key) {
        initCalls.incrementAndGet();
        return "value-of-" + key;
    }

    public static void main(String[] args) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            CountDownLatch done = new CountDownLatch(50);
            for (int i = 0; i < 50; i++) {
                pool.submit(() -> {
                    // 50 thread cùng tranh nhau cùng MỘT key "config"
                    cache.___("config", ComputeIfAbsentDemo::expensiveCreate); // Điền phương thức tính toán nếu vắng mặt
                    done.countDown();
                });
            }
            done.await();
        }
        System.out.println("Số lần init (mong đợi 1): " + initCalls.get());  // → 1
        System.out.println("Giá trị: " + cache.get("config"));
    }
}
```

So sánh: nếu dùng `HashMap` + `if (get==null) put(...)`, hàm `expensiveCreate` sẽ chạy **nhiều lần** (race) và `HashMap` còn có thể **hỏng cấu trúc nội bộ** khi ghi đồng thời (vòng lặp vô hạn ở Java cũ).

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng `count++` nguyên tử.** Nó là read-modify-write 3 bước → lost update. Dùng `AtomicInteger`.
- **Dùng `volatile` cho bộ đếm.** `volatile int count; count++;` **vẫn sai** — `volatile` chỉ lo visibility, không lo atomicity.
- **Check-then-act không khóa.** `if (get==null) put()`, `if (balance>=x) balance-=x` đều là race. Dùng `putIfAbsent`/`computeIfAbsent` hoặc bọc cả khối trong khóa.
- **Side-effect trong lambda `updateAndGet`.** Hàm sẽ chạy lại khi CAS fail → in trùng, ghi DB trùng. Giữ hàm thuần.
- **Đồng bộ trên đối tượng có thể đổi tham chiếu** hoặc dùng `Integer`/`String` làm khóa lock (bị cache/intern → nhiều nơi vô tình share cùng một lock). Khóa trên `private final Object lock`.
- **`HashMap` thay vì `ConcurrentHashMap` trong service dùng chung.** Ghi đồng thời gây mất dữ liệu, thậm chí treo CPU.
- **"Test trên máy thấy ổn → kết luận đúng".** Race **không tái hiện ổn định**. Phải test với **nhiều thread + nhiều vòng lặp + chạy nhiều lần** (như demo (a)), hoặc dùng công cụ như `jcstress`.
- **Đồng bộ hoá một nửa.** Nếu một nơi ghi có khóa nhưng nơi đọc không khóa → vẫn có thể đọc giá trị cũ/dở dang. Mọi truy cập tới shared state phải **nhất quán** về cơ chế đồng bộ.

---

## 🚀 Liên hệ Spring Boot / Production

- **Singleton bean là cái bẫy số 1.** Mặc định mọi `@Service`, `@Component`, `@Repository` là **singleton** — một instance phục vụ **mọi request đồng thời**. Field khả biến trong bean = shared mutable state. Quy tắc: **bean không nên có state khả biến**; nếu buộc phải có (cache, counter, rate-limit) thì dùng `Atomic*` / `ConcurrentHashMap` / `LongAdder`.
- **Metrics & counters.** Đếm số request, hit/miss cache, số lỗi... dùng `LongAdder` (tối ưu cho ghi nhiều, đọc ít) hoặc Micrometer `Counter` (vốn lock-free bên trong).
- **Rate limiter / token bucket.** Logic "kiểm tra còn token → trừ token" là check-then-act điển hình; cài bằng `AtomicLong` + vòng lặp CAS, hoặc thư viện (Bucket4j, Resilience4j) đã lo sẵn.
- **Lazy init bean phụ trợ.** Khởi tạo client/connection theo nhu cầu: `concurrentMap.computeIfAbsent(key, k -> buildClient(k))` thay vì check-then-put.
- **Nhiều instance (scale-out).** Khi chạy **nhiều JVM/pod**, atomic in-memory **không còn đủ** — state lại bị chia sẻ qua DB/Redis. Lúc này quay về đúng mô hình Laravel: **optimistic lock (cột version)** hoặc **distributed lock (Redis/Redisson)**. Hiểu race in-memory giúp bạn hiểu ngay race phân tán.
- **`@Transactional` không phải là khóa.** Transaction lo ACID ở DB, không tự chống lost update giữa SELECT...rồi...UPDATE trong cùng app. Cần `SELECT ... FOR UPDATE` (pessimistic) hoặc `@Version` (optimistic, JPA) — đối ứng của `lockForUpdate()` và cột version trong Laravel.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 18. Trong đấu giá, **nhiều người đặt giá ĐỒNG THỜI**. Ta phải đảm bảo "giá cao nhất" (`highestBid`) **không bao giờ bị mất** dù tải song song. Hôm nay ta tái hiện bug rồi sửa bằng `AtomicReference` + vòng lặp CAS.

### Bước 1 — Model bất biến cho một lượt đặt giá

```java
// Bid là IMMUTABLE: tạo xong không đổi → an toàn để chia sẻ tham chiếu
public record Bid(String bidder, long amount) {}
```

### Bước 2 — TÁI HIỆN bug: lost update trên `highestBid`

```java
import java.util.concurrent.*;

public class BuggyAuction {
    // Lưu trực tiếp, đọc–so sánh–ghi KHÔNG nguyên tử → check-then-act race
    private volatile Bid highestBid = new Bid("init", 0);

    public void placeBid(Bid newBid) {
        if (newBid.amount() > highestBid.amount()) {   // CHECK
            // (giả lập độ trễ tính toán/validate giữa check và act → phóng đại race)
            Thread.onSpinWait();
            highestBid = newBid;                       // ACT: ghi đè
        }
    }
    public Bid getHighestBid() { return highestBid; }
}
```

Hai thread cùng thấy `highestBid = 100`, cùng vượt qua CHECK với giá 150 và 200; nếu thread-200 ghi trước, thread-150 ghi sau → **highestBid bị tụt về 150** → mất bid cao nhất 200. **Lost update**.

### Bước 3 — Test phơi bày bug (chạy nhiều thread)

```java
import java.util.concurrent.*;

public class AuctionRaceTest {
    public static void main(String[] args) throws Exception {
        BuggyAuction auction = new BuggyAuction();
        int N = 200;                                   // 200 người đặt giá đồng thời
        try (ExecutorService pool = Executors.newFixedThreadPool(N)) {
            CountDownLatch ready = new CountDownLatch(N);
            CountDownLatch go = new CountDownLatch(1);  // "cò súng" để mọi thread bắn cùng lúc
            for (int i = 0; i < N; i++) {
                final long price = (i + 1) * 100L;     // 100, 200, ..., 20000
                pool.submit(() -> {
                    ready.countDown();
                    try { go.await(); } catch (InterruptedException ignored) {}
                    auction.placeBid(new Bid("user-" + price, price));
                });
            }
            ready.await();
            go.countDown();                            // bắn! tối đa hoá tranh chấp
        }
        long expected = 20000L;
        Bid h = auction.getHighestBid();
        System.out.printf("Cao nhất ĐÚNG phải là %d | thực tế = %d (%s) → %s%n",
                expected, h.amount(), h.bidder(),
                h.amount() == expected ? "OK" : "❌ MẤT BID CAO NHẤT!");
    }
}
```

Chạy `BuggyAuction` vài lần → thường in `❌ MẤT BID CAO NHẤT!` với một con số **nhỏ hơn 20000** và **khác nhau mỗi lần**.

### Bước 4 — SỬA bằng `AtomicReference` + vòng lặp CAS

```java
import java.util.concurrent.atomic.AtomicReference;

public class SafeAuction {
    private final AtomicReference<Bid> highestBid =
            new AtomicReference<>(new Bid("init", 0));

    /** Chỉ cập nhật nếu bid mới CAO HƠN. Lock-free, không bao giờ mất bid cao nhất. */
    public boolean placeBid(Bid newBid) {
        Bid current;
        do {
            current = highestBid.get();                 // đọc snapshot hiện tại
            if (newBid.amount() <= current.amount()) {
                return false;                           // không cao hơn → từ chối
            }
            // nếu CAS fail nghĩa là ai đó vừa đổi highestBid → lặp lại với giá trị mới nhất
        } while (!highestBid.___(current, newBid)); // Điền phương thức so sánh và thiết lập nguyên tử
        return true;                                    // đặt giá thành công
    }

    public Bid getHighestBid() { return highestBid.get(); }
}
```

Hoặc gọn hơn bằng `updateAndGet` (tự lặp CAS bên trong):

```java
public void placeBid(Bid newBid) {
    highestBid.___(cur -> // Điền phương thức cập nhật và lấy nguyên tử bằng hàm
        newBid.amount() > cur.amount() ? newBid : cur);  // chọn bid cao hơn
}
```

**Vì sao đúng?** `compareAndSet(current, newBid)` chỉ thành công nếu `highestBid` **vẫn đúng bằng `current`** mà ta đã đọc. Nếu thread khác đã nâng giá lên trong lúc đó, CAS **thất bại** → ta lặp lại, đọc giá trị mới, so sánh lại. Kết quả: **giá cao nhất luôn thắng**, không lần `placeBid` nào ghi đè mất một bid cao hơn — kể cả với 200 thread bắn cùng lúc.

### Bước 5 — Xác minh

Đổi `new BuggyAuction()` thành `new SafeAuction()` trong `AuctionRaceTest`, chạy lại nhiều lần → **luôn** in `... thực tế = 20000 ... OK`.

> 🧩 Liên hệ thực chiến: ngoài đời `highestBid` thường nằm trong DB. Vòng lặp CAS in-memory này chính là **optimistic locking** ở tầng DB: `UPDATE auction SET highest=?, version=version+1 WHERE id=? AND version=?` — fail thì đọc lại & thử lại. Cùng một tư duy, khác tầng lưu trữ.

**Nhiệm vụ Day 19:**
1. Chạy `AuctionRaceTest` với `BuggyAuction`, ghi lại 5 lần kết quả (chứng minh sai & không ổn định).
2. Thay bằng `SafeAuction`, chạy lại 5 lần, xác nhận luôn = 20000.
3. Viết phiên bản `placeBid` bằng `updateAndGet` và so sánh độ rõ ràng với vòng lặp CAS thủ công.
4. Ghi `notes/day-19.md`: giải thích bằng lời của bạn vì sao `compareAndSet` chống được lost update.
5. Điền các chỗ trống `___` trong code thực hành ở trên.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Race condition là gì?**
> **Đáp:** Là tình huống mà tính đúng đắn của chương trình phụ thuộc vào **thứ tự/timing** thực thi không xác định của nhiều thread trên **dữ liệu chia sẻ khả biến**. Vì lịch thread do OS/JVM quyết, kết quả trở nên **không xác định** (chạy nhiều lần ra kết quả khác nhau).

**Q2: Vì sao `count++` không an toàn với nhiều thread?**
> **Đáp:** Vì nó không nguyên tử — thực chất gồm 3 bước read-modify-write (đọc count, +1, ghi lại). Hai thread có thể cùng đọc giá trị cũ rồi cùng ghi đè, làm "mất" một lần tăng (**lost update**).

**Q3: `volatile` có làm `count++` an toàn không?**
> **Đáp:** **Không.** `volatile` chỉ đảm bảo **visibility** (thread thấy giá trị mới nhất), **không** đảm bảo **atomicity**. `count++` vẫn là 3 bước có thể bị xen kẽ. `volatile` chỉ đủ cho ghi đơn lẻ không phụ thuộc giá trị cũ (ví dụ một cờ `boolean`).

**Q4: Kể vài cách khắc phục race condition.**
> **Đáp:** (1) Khóa: `synchronized`/`Lock` bọc vùng tới hạn. (2) Biến nguyên tử: `AtomicInteger.incrementAndGet()`, `compareAndSet` (dùng CAS, lock-free). (3) Thiết kế **bất biến** hoặc **không chia sẻ** state (immutable object, local var, `ThreadLocal`). Ưu tiên: không chia sẻ → bất biến → atomic → khóa.

**Q5: Check-then-act là gì? Cho ví dụ.**
> **Đáp:** Là mẫu "kiểm tra điều kiện rồi hành động dựa trên kết quả kiểm tra", nhưng giữa lúc kiểm tra và hành động, thread khác đã đổi state. Ví dụ: `if (map.get(k)==null) map.put(k,v)` (2 thread cùng put), hay `if (balance>=amount) balance-=amount` (chi vượt quỹ). Khắc phục bằng thao tác gộp nguyên tử như `putIfAbsent`/`computeIfAbsent` hoặc bọc khóa.

### Mức Senior

**Q6: CAS (Compare-And-Swap) hoạt động thế nào? Ưu/nhược điểm so với khóa?**
> **Đáp:** CAS là một chỉ thị nguyên tử mức CPU: "nếu giá trị tại địa chỉ đúng bằng `expected` thì đổi thành `newValue` và báo thành công, ngược lại không làm gì và báo thất bại". Atomic dùng CAS trong **vòng lặp** (đọc–tính–CAS, fail thì lặp lại). **Ưu:** lock-free, không context switch, không deadlock, nhanh dưới tranh chấp vừa phải. **Nhược:** dưới tranh chấp cực cao, vòng lặp retry nhiều gây phí CPU (livelock-ish); và dính **vấn đề ABA** (giá trị đổi A→B→A khiến CAS tưởng "không đổi") — xử lý bằng `AtomicStampedReference`.

**Q7: Phân biệt visibility và atomicity. Cái nào `synchronized` lo, cái nào `volatile` lo?**
> **Đáp:** **Visibility** = thread khác có thấy giá trị vừa ghi không. **Atomicity** = thao tác có bị xen kẽ không. `volatile` đảm bảo **visibility** (và cấm reorder quanh nó) nhưng **không** atomicity. `synchronized`/`Lock`/`Atomic*` đảm bảo **cả hai** (vào/ra khóa có hàng rào bộ nhớ → visibility, và loại trừ lẫn nhau → atomicity). Nhớ: `volatile` cho **cờ**, atomic/lock cho **bộ đếm**.

**Q8: `AtomicInteger` so với `synchronized` về hiệu năng? Khi nào dùng `LongAdder`?**
> **Đáp:** Dưới tranh chấp thấp–vừa, `AtomicInteger` (lock-free CAS) thường **nhanh hơn** `synchronized` vì tránh blocking/context switch. Dưới tranh chấp **cực cao** (nhiều thread cùng ghi một biến), vòng lặp CAS retry liên tục thành điểm nóng. Khi đó dùng **`LongAdder`**: nó **phân tán** việc cộng ra nhiều ô (cells), mỗi thread cộng vào ô riêng (giảm tranh chấp), chỉ cộng gộp khi `sum()`. Lý tưởng cho metrics/counter ghi-nhiều-đọc-ít.

**Q9: Trong Spring Boot, vì sao singleton bean dễ gây race? Chiến lược tránh?**
> **Đáp:** Vì bean mặc định là singleton, **một instance phục vụ mọi request đồng thời**, nên field khả biến của nó là shared mutable state. Chiến lược: ưu tiên bean **stateless**; nếu cần state thì dùng `Atomic*`/`ConcurrentHashMap`/`LongAdder`; tránh field khả biến trần. Với state mức request, dùng local var hoặc scope `prototype`/`request`. Khi scale nhiều instance, chuyển sang **optimistic lock (`@Version`)** hoặc **distributed lock (Redis)** vì atomic in-memory không còn đủ.

**Q10: Làm sao kiểm thử/debug một race condition?**
> **Đáp:** Race không tái hiện ổn định nên test thường: tăng **số thread + số vòng lặp**, dùng "cò súng" (`CountDownLatch go`) cho mọi thread bắn cùng lúc để **tối đa hoá tranh chấp**, chạy lặp nhiều lần. Công cụ chuyên dụng: **`jcstress`** (Java Concurrency Stress) để dò interleaving; phân tích bằng thread dump/`jstack`, profiler. Về thiết kế, áp nguyên tắc "mọi shared mutable state phải có chiến lược đồng bộ nhất quán và được ghi rõ".

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được race condition & shared mutable state bằng lời của mình
- [ ] Vẽ được timeline 2 thread `count++` gây lost update
- [ ] Phân biệt rạch ròi visibility (`volatile`) vs atomicity (atomic/lock)
- [ ] Phân loại được lost update / read-modify-write / check-then-act
- [ ] Giải thích được CAS & vòng lặp CAS, biết `compareAndSet`/`updateAndGet`
- [ ] Tự chạy demo (a) thấy counter sai, demo (b) thấy `AtomicInteger` đúng
- [ ] Dùng `computeIfAbsent` thay check-then-act
- [ ] Hoàn thành Mini Project: tái hiện rồi sửa lost update trên `highestBid`
- [ ] Trả lời được 10 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 2 "Thread Safety" & chương 15 "Atomic Variables and Nonblocking Synchronization" (kinh điển, đọc kỹ)
- Oracle Java Tutorials — "Concurrency: Atomic Variables" và "Synchronization"
- Javadoc gói `java.util.concurrent.atomic` — `AtomicInteger`, `AtomicReference`, `LongAdder`
- Baeldung — "Introduction to Atomic Variables in Java", "CAS — Compare and Swap"
- Java Language Specification — chương 17 "Threads and Locks" (Java Memory Model, phần atomicity/visibility)
- `jcstress` (OpenJDK) — công cụ stress-test concurrency để tự kiểm chứng race
