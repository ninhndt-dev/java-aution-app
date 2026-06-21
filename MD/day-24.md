# Day 24 - Concurrent Collections

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã hiểu race condition, khóa, atomic (Day 18–22), giờ học **các collection thiết kế sẵn cho đa luồng** — công cụ hằng ngày để code concurrency đúng & nhanh.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu vì sao `HashMap`/`ArrayList` thường **không an toàn** đa luồng, và vì sao `Collections.synchronizedXxx` **chưa đủ tốt**.
- Nắm **`ConcurrentHashMap`**: khóa mức bucket (CAS + `synchronized` node), không khóa toàn bảng; thao tác gộp nguyên tử (`computeIfAbsent`/`merge`); không cho `null`.
- Hiểu **`CopyOnWriteArrayList`**: đọc nhiều ghi ít (snapshot bất biến khi đọc).
- Nắm **`BlockingQueue`** (`ArrayBlockingQueue`/`LinkedBlockingQueue`): nền của **producer-consumer**, `put`/`take` chặn.
- Ôn **Atomic classes** (`AtomicInteger/Long/Reference`, CAS) và **`LongAdder`** (đếm ghi-nhiều tối ưu).
- Biết `ConcurrentLinkedQueue` (non-blocking).
- Chọn đúng collection cho đúng tình huống.
- Liên hệ Laravel: PHP không có khái niệm này in-memory — tương đương ở tầng Redis/queue.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao `synchronizedXxx` chưa đủ tốt?

`HashMap`/`ArrayList` **không an toàn** đa luồng (ghi đồng thời gây mất dữ liệu, thậm chí hỏng cấu trúc nội bộ). Java cho `Collections.synchronizedMap/List(...)` — bọc **mọi method** trong `synchronized` trên **một khóa duy nhất**:

```java
Map<String,Integer> m = Collections.synchronizedMap(new HashMap<>());
```

Vấn đề:

| Nhược điểm | Giải thích |
|---|---|
| **Khóa toàn cục** | Mọi thao tác (kể cả đọc) tranh **một khóa** → thread chặn nhau dữ dội dưới tải cao (nghẽn cổ chai) |
| **Compound action vẫn không an toàn** | `if (m.get(k)==null) m.put(k,v)` — mỗi method khóa riêng, nhưng **giữa** chúng thread khác chen vào (check-then-act, Day 19) |
| **Iterate phải khóa thủ công** | Duyệt `for (e : m)` mà thread khác sửa → `ConcurrentModificationException`; phải `synchronized(m){...}` quanh vòng lặp |

> 💡 `synchronizedMap` chỉ làm **từng thao tác đơn** nguyên tử, không làm **chuỗi thao tác** nguyên tử, và khóa quá thô (toàn bảng). Gói `java.util.concurrent` ra đời để vừa **an toàn**, vừa **không khóa toàn cục** (scalable), vừa cung cấp **thao tác gộp nguyên tử**.

### 2. `ConcurrentHashMap` — ngôi sao của ngày

Map an toàn đa luồng, **scalable**, dùng hằng ngày thay cho `HashMap` khi chia sẻ giữa thread.

**Cơ chế (Java 8+):** thay vì khóa toàn bảng, nó khóa **ở mức bucket/node**:
- Khi thêm vào một bucket **rỗng**: dùng **CAS** (lock-free) — không khóa gì.
- Khi bucket **đã có node** (va chạm hash): chỉ `synchronized` trên **node đầu của bucket đó** — các bucket khác vẫn ghi song song thoải mái.
- Đọc (`get`) hầu như **không khóa** (dựa vào `volatile`/visibility).

```
HashMap thường (1 khóa toàn bảng — synchronizedMap):
   [bucket0][bucket1][bucket2]...[bucketN]   ← MỌI ghi tranh 1 KHÓA → nghẽn

ConcurrentHashMap (khóa mức bucket):
   [bucket0]🔒  [bucket1]   [bucket2]🔒  ...  ← chỉ khóa bucket đang ghi,
       T1 ghi      rỗng→CAS    T2 ghi             các bucket khác SONG SONG
```

**Thao tác gộp nguyên tử** (thay check-then-act — nhắc lại Day 19):

| Method | Ý nghĩa nguyên tử |
|---|---|
| `putIfAbsent(k, v)` | thêm nếu chưa có key |
| `computeIfAbsent(k, fn)` | tính & thêm nếu chưa có; **hàm chỉ chạy 1 lần/key** |
| `merge(k, v, fn)` | gộp value cũ với mới (đếm tần suất: `merge(k, 1, Integer::sum)`) |
| `compute(k, fn)` | cập nhật value theo (key, valueCũ) |

> ⚠️ **`ConcurrentHashMap` KHÔNG cho phép `null`** (cả key lẫn value) — khác `HashMap`. Lý do: với `null`, không phân biệt được "key không tồn tại" và "key map tới null" trong môi trường đa luồng (`get` trả null mơ hồ). Cố `put(k, null)` → `NullPointerException`.

> 💡 Iterator của `ConcurrentHashMap` là **weakly consistent**: duyệt không ném `ConcurrentModificationException`, phản ánh trạng thái tại một thời điểm nào đó (có thể thấy hoặc không thấy thay đổi xảy ra trong lúc duyệt) — đánh đổi để duyệt mà không khóa.

### 3. `CopyOnWriteArrayList` — đọc nhiều, ghi ít

List an toàn đa luồng theo chiến lược **copy-on-write**: mỗi lần **ghi** (add/set/remove) nó **sao chép cả mảng** ra bản mới rồi thay tham chiếu; **đọc** thì đọc thẳng mảng hiện tại **không khóa**.

```
Đọc (rất nhiều):   ──► đọc mảng hiện tại, KHÔNG khóa, KHÔNG bao giờ CME
Ghi (hiếm):        ──► copy toàn mảng → sửa bản copy → thay tham chiếu (volatile)
```

| Ưu | Nhược |
|---|---|
| Đọc cực nhanh, không khóa | Ghi **đắt** (copy cả mảng mỗi lần) |
| Iterator chụp **snapshot** — không bao giờ `ConcurrentModificationException` | Tốn bộ nhớ khi list lớn & ghi thường xuyên |

> 💡 Hợp hoàn hảo cho **danh sách listener/observer/handler**: đăng ký (ghi) rất hiếm, gọi qua (đọc/duyệt) rất nhiều, và duyệt an toàn ngay cả khi ai đó vừa thêm/xóa. **Tuyệt đối không** dùng cho list ghi liên tục (mỗi ghi copy cả mảng → thảm họa).

### 4. `BlockingQueue` — nền của Producer-Consumer

Hàng đợi an toàn đa luồng với hai thao tác **chặn** kinh điển:
- `put(e)`: thêm phần tử; nếu queue **đầy** → **chặn** chờ tới khi có chỗ.
- `take()`: lấy phần tử; nếu queue **rỗng** → **chặn** chờ tới khi có hàng.

Đây là "băng chuyền" tự điều tiết giữa **producer** (sinh việc) và **consumer** (xử lý việc):

```
   Producer(s)  ──put()──►  [ BlockingQueue (có chặn) ]  ──take()──►  Consumer(s)
   (đẩy bid vào)             đầy → producer CHỜ            rỗng → consumer CHỜ
                            ⇒ tự cân bằng tốc độ 2 bên (back-pressure tự nhiên)
```

| Loại | Đặc điểm |
|---|---|
| `ArrayBlockingQueue(n)` | Mảng cố định, **bounded** (chặn dung lượng) — chống OOM, có back-pressure |
| `LinkedBlockingQueue` | Linked nodes, mặc định **không giới hạn** (`Integer.MAX_VALUE`) — cẩn thận OOM |
| `PriorityBlockingQueue` | Lấy theo độ ưu tiên (không FIFO) |
| `SynchronousQueue` | Dung lượng 0 — mỗi `put` chờ một `take` tương ứng (trao tay trực tiếp) |
| `DelayQueue` | Phần tử chỉ lấy được khi tới hạn (delay) |

> 💡 `BlockingQueue` chính là **bộ máy bên trong `ThreadPoolExecutor`** (Day 22) — `workQueue` chính là một `BlockingQueue`. Hiểu nó là hiểu cách pool nhận/giữ task. Việc chặn `put`/`take` giúp bạn **không phải tự viết wait/notify** (cách cũ, dễ sai) — đây là điểm nâng cấp lớn so với `Object.wait()/notify()`.

### 5. Atomic classes & `LongAdder` (ôn + nâng)

Ôn Day 19: `AtomicInteger/Long/Reference` thực hiện read-modify-write **nguyên tử lock-free** bằng **CAS**. Hôm nay bổ sung **`LongAdder`**:

```
AtomicLong (tranh chấp CAO):       LongAdder (phân tán):
   nhiều thread cùng CAS 1 biến       mỗi thread cộng vào 1 "cell" riêng
   → retry liên tục, nóng              → ít tranh chấp, nhanh khi ghi nhiều
   sum() = đọc trực tiếp (nhanh)       sum() = cộng gộp các cell (chậm hơn 1 chút)
```

> 💡 Quy tắc: cần **giá trị tức thời chính xác từng bước** (ví dụ ID sinh tuần tự) → `AtomicLong`. Cần **đếm ghi rất nhiều, đọc thưa** (metrics: số request, hit/miss) → **`LongAdder`** (nhanh hơn nhiều dưới tranh chấp cao vì phân tán việc cộng ra nhiều ô).

### 6. `ConcurrentLinkedQueue` — non-blocking queue

Queue an toàn đa luồng **không chặn** (lock-free, dựa CAS), **không giới hạn**. Khác `BlockingQueue`: `poll()` trả `null` ngay nếu rỗng (không chờ), `offer()` luôn thành công (không chặn). Hợp khi bạn **không muốn thread chờ** mà tự xử lý "rỗng thì làm việc khác".

### 7. Bảng chọn collection đúng

| Tình huống | Dùng |
|---|---|
| Map chia sẻ, đọc-ghi nhiều, cần scalable | **`ConcurrentHashMap`** |
| Đếm tần suất theo key | `ConcurrentHashMap` + `merge(k, 1, Integer::sum)` |
| List đọc rất nhiều, ghi rất hiếm (listeners) | **`CopyOnWriteArrayList`** |
| Producer-consumer, cần chặn & back-pressure | **`ArrayBlockingQueue`** (bounded) |
| Queue non-blocking, không muốn chờ | `ConcurrentLinkedQueue` |
| Đếm metrics ghi rất nhiều | **`LongAdder`** |
| Bộ đếm/ID cần giá trị từng bước | `AtomicInteger`/`AtomicLong` |
| Tham chiếu cập nhật có điều kiện (CAS) | `AtomicReference` |

---

## 🔁 Đối chiếu với Laravel/PHP

Như các ngày trước, PHP shared-nothing **không có** khái niệm "collection chia sẻ giữa thread in-memory" — mỗi request một vùng nhớ riêng, một `array` PHP không bao giờ bị thread khác đụng. Vì vậy bạn **chưa từng** cần `ConcurrentHashMap` trong Laravel.

Tuy nhiên, các **vai trò** mà những collection này đảm nhiệm thì bạn **đã làm** ở tầng hạ tầng:

```
Java in-memory concurrent collection   ≈   Laravel (ngoài process)
─────────────────────────────────────────────────────────────────────
ConcurrentHashMap (cache chia sẻ)      ≈   Redis / Cache::get/put (chia sẻ giữa request)
BlockingQueue (producer-consumer)      ≈   Redis/DB queue + queue:work (Horizon)
AtomicLong / LongAdder (đếm)           ≈   Redis::incr('counter')  (đếm nguyên tử)
CopyOnWriteArrayList                    ≈   (hiếm — gần với config cache đọc nhiều)
```

| Vai trò | Java (in-memory, 1 process) | Laravel (ngoài process, nhiều process) |
|---|---|---|
| Cache dùng chung | `ConcurrentHashMap` trong RAM | `Cache`/Redis |
| Hàng đợi việc | `BlockingQueue` + worker thread | Redis/DB queue + worker process |
| Bộ đếm nguyên tử | `AtomicLong`/`LongAdder` | `Redis::incr` |
| Đồng bộ đa luồng | bắt buộc trong code | storage tự lo (vì process tách biệt) |

> 🧩 Insight chuyển ngữ: trong Laravel, "trạng thái chia sẻ" luôn nằm **ngoài** process (Redis/DB) nên các storage đó lo tính nhất quán giùm bạn. Java một process giữ trạng thái **trong RAM**, nên bạn phải tự chọn cấu trúc dữ liệu an toàn — `ConcurrentHashMap` đóng vai Redis-trong-RAM, `BlockingQueue` đóng vai Redis-queue-trong-RAM, `AtomicLong` đóng vai `Redis::incr`. Cùng vai trò, khác nơi sống. Khi Java scale nhiều instance, bạn lại đẩy trạng thái ra Redis/DB — y hệt Laravel.

> ⚠️ Bẫy chuyển ngữ: đừng đem thói quen "dùng `array`/`HashMap` thoải mái" của PHP vào Spring bean (singleton, chia sẻ mọi request). Một `HashMap` field trong `@Service` bị ghi đồng thời sẽ mất dữ liệu hoặc hỏng. Luôn `ConcurrentHashMap` cho state chia sẻ trong bean.

---

## 💻 Thực hành code

### (a) `HashMap` hỏng vs `ConcurrentHashMap` an toàn

```java
import java.util.*;
import java.util.concurrent.*;

public class MapSafetyDemo {
    public static void main(String[] args) throws Exception {
        // HashMap thường — ghi đồng thời → mất dữ liệu (size < kỳ vọng), có thể hỏng cấu trúc
        Map<Integer,Integer> unsafe = new ___<>(); // Điền class Map không an toàn đồng thời
        Map<Integer,Integer> safe   = new ___<>(); // Điền class Map an toàn đồng thời

        Runnable fill = () -> { for (int i = 0; i < 10_000; i++) {/* placeholder */} };

        runConcurrent(unsafe, "HashMap (KHÔNG an toàn)");
        runConcurrent(safe,   "ConcurrentHashMap (an toàn)");
    }

    static void runConcurrent(Map<Integer,Integer> map, String label) throws Exception {
        int THREADS = 8, PER = 10_000;
        try (___ pool = Executors.___(THREADS)) { // Điền class và method tạo thread pool
            ___ done = new ___(THREADS); // Điền class chốt đếm ngược
            for (int t = 0; t < THREADS; t++) {
                final int base = t * PER;
                pool.submit(() -> {
                    for (int i = 0; i < PER; i++) map.put(base + i, i);   // các key khác nhau
                    done.countDown();
                });
            }
            done.await();
        }
        int expected = THREADS * PER;
        System.out.printf("%-32s size=%d (kỳ vọng %d) → %s%n",
                label, map.size(), expected, map.size() == expected ? "OK" : "❌ MẤT dữ liệu");
        // HashMap thường ra size < expected (và có thể đã hỏng); ConcurrentHashMap luôn đúng
    }
}
```

### (b) Đếm tần suất an toàn bằng `merge` / `LongAdder`

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class FrequencyCountDemo {
    public static void main(String[] args) throws Exception {
        // Đếm số bid theo từng người dùng — compound action AN TOÀN bằng merge
        ___<String, Integer> bidsPerUser = new ___<>(); // Điền class Map an toàn đồng thời
        // Tổng số bid toàn hệ thống — ghi rất nhiều → LongAdder
        ___ totalBids = new ___(); // Điền class đếm hiệu suất cao

        String[] users = {"alice", "bob", "carol"};
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch done = new CountDownLatch(8);
            for (int t = 0; t < 8; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < 100_000; i++) {
                        String u = users[i % users.length];
                        bidsPerUser.___(u, 1, Integer::sum);   // Điền phương thức cập nhật nguyên tử: đọc-cộng-ghi value
                        totalBids.___();                   // Điền phương thức tăng nguyên tử
                    }
                    done.countDown();
                });
            }
            done.await();
        }
        System.out.println("Bid mỗi user: " + bidsPerUser);
        System.out.println("Tổng bid: " + totalBids.sum() + " (kỳ vọng 800000)");
    }
}
```

### (c) Producer-Consumer bằng `BlockingQueue`

```java
import java.util.concurrent.*;

public class ProducerConsumerDemo {
    record Bid(int id, long amount) {}
    // "viên thuốc độc" (poison pill) báo consumer dừng
    static final Bid POISON = new Bid(-1, -1);

    public static void main(String[] args) throws Exception {
        // Bounded queue → producer nhanh sẽ bị CHẶN khi đầy (back-pressure tự nhiên)
        ___<Bid> queue = new ___(100); // Điền interface và class BlockingQueue
        int CONSUMERS = 3;

        try (ExecutorService pool = Executors.newFixedThreadPool(CONSUMERS + 1)) {
            // 1 Producer: sinh 1000 bid rồi gửi poison cho từng consumer
            pool.submit(() -> {
                try {
                    for (int i = 1; i <= 1000; i++) {
                        queue.___(new Bid(i, i * 100L));    // Điền phương thức thêm (chặn nếu đầy)
                    }
                    for (int c = 0; c < CONSUMERS; c++) queue.put(POISON);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });

            // N Consumer: lấy bid xử lý tới khi gặp poison
            for (int c = 0; c < CONSUMERS; c++) {
                pool.submit(() -> {
                    try {
                        while (true) {
                            Bid bid = queue.___();          // Điền phương thức lấy (chặn nếu rỗng)
                            if (bid == POISON) break;        // tín hiệu dừng
                            // xử lý bid (ở đây chỉ in mẫu thưa)
                            if (bid.id() % 250 == 0)
                                System.out.printf("[%s] xử lý bid %d%n",
                                        Thread.currentThread().getName(), bid.id());
                        }
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                });
            }
        }   // try-with-resources chờ producer + consumers xong
        System.out.println("Producer-consumer hoàn tất, không mất bid.");
    }
}
```

### (d) `CopyOnWriteArrayList` cho danh sách listener

```java
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class ListenerRegistryDemo {
    // Đăng ký listener (ghi) hiếm; phát sự kiện (đọc/duyệt) rất nhiều → CopyOnWrite hợp lý
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public void subscribe(Consumer<String> l) { listeners.add(l); }     // ghi (copy mảng)

    public void publish(String event) {
        // Duyệt KHÔNG khóa, KHÔNG bao giờ ConcurrentModificationException dù ai đó vừa subscribe
        for (Consumer<String> l : listeners) l.accept(event);
    }

    public static void main(String[] args) {
        ListenerRegistryDemo bus = new ListenerRegistryDemo();
        bus.subscribe(e -> System.out.println("Logger nhận: " + e));
        bus.subscribe(e -> System.out.println("Mailer nhận: " + e));
        bus.publish("PHIÊN_ĐẤU_GIÁ_ĐÓNG");
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng `HashMap`/`ArrayList` cho state chia sẻ.** Ghi đồng thời mất dữ liệu, hỏng cấu trúc. Dùng `ConcurrentHashMap`/`CopyOnWriteArrayList`.
- **Tưởng `synchronizedMap` làm compound action an toàn.** `if(get==null) put()` vẫn race (mỗi method khóa riêng). Dùng `computeIfAbsent`/`putIfAbsent` của `ConcurrentHashMap`.
- **`put(k, null)` vào `ConcurrentHashMap`.** Ném `NullPointerException` — nó cấm null. Đừng dựa vào "value null nghĩa là vắng mặt".
- **Dùng `CopyOnWriteArrayList` cho list ghi nhiều.** Mỗi ghi copy cả mảng → cực chậm, ngốn RAM. Chỉ dùng khi đọc ≫ ghi.
- **`LinkedBlockingQueue` không giới hạn làm hàng đợi producer-consumer.** Producer nhanh hơn consumer → queue phình → OOM. Ưu tiên `ArrayBlockingQueue` (bounded) để có back-pressure.
- **Iterate `ConcurrentHashMap` rồi mong "ảnh chụp nhất quán tuyệt đối".** Iterator weakly consistent — có thể thấy/không thấy thay đổi đang diễn ra. Đừng giả định tính nhất quán mạnh khi duyệt.
- **`computeIfAbsent` với hàm chạy lâu/đệ quy lên cùng map.** Hàm chạy trong khi giữ khóa bucket; gọi lại `computeIfAbsent` cùng key (đệ quy) có thể deadlock/treo. Giữ hàm ngắn, không tự gọi lại map.
- **Dùng `AtomicLong` cho metrics tranh chấp cực cao.** Vòng lặp CAS nóng. Dùng `LongAdder`.
- **Quên rằng concurrent collection chỉ an toàn cho **chính thao tác của nó**.** Một chuỗi nhiều thao tác (get rồi tính rồi put) vẫn cần thao tác gộp nguyên tử (`compute`/`merge`) hoặc khóa ngoài.

---

## 🚀 Liên hệ Spring Boot / Production

- **Cache in-memory trong bean.** Cache cục bộ (config, lookup table, rate-limit token) dùng `ConcurrentHashMap` field trong `@Service` singleton; thêm/đọc bằng `computeIfAbsent` để tránh tính trùng. (Cache lớn/chia sẻ nhiều instance thì dùng Caffeine/Redis.)
- **Đếm metrics.** Số request, hit/miss cache, lỗi → `LongAdder` hoặc Micrometer `Counter` (lock-free bên trong). Đừng `synchronized` quanh một `long`.
- **Producer-consumer trong process.** Pipeline xử lý nội bộ (gom event, ghi batch xuống DB) dùng `BlockingQueue` + worker thread — đúng mô hình `ThreadPoolExecutor` (workQueue chính là một `BlockingQueue`).
- **Danh sách listener/observer.** Event publisher (Spring `ApplicationListener` tự lo, nhưng nếu bạn tự viết) nên dùng `CopyOnWriteArrayList` cho danh sách handler để publish an toàn lúc đang đăng ký.
- **Registry/session map.** Bản đồ "phiên đang mở", "kết nối WebSocket đang sống" → `ConcurrentHashMap`, key là id, thao tác bằng `compute`/`merge` cho cập nhật nguyên tử.
- **Scale nhiều instance.** Concurrent collection chỉ sống **trong một JVM**. Nhiều pod → trạng thái phải ra Redis/Hazelcast/DB. `ConcurrentHashMap` không tự đồng bộ giữa các instance.
- **HikariCP & các pool.** Bên trong nhiều thư viện hạ tầng dùng concurrent collection + `BlockingQueue` để quản tài nguyên — hiểu chúng giúp đọc & tinh chỉnh pool.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 22. Ở Day 22 ta "đẩy bid vào pool" — hôm nay làm rõ phần hàng đợi đó bằng **`BlockingQueue` tường minh** (producer = client gửi bid, consumer = worker xử lý), và giữ **bảng giá cao nhất của từng phiên** bằng **`ConcurrentHashMap`**. Đây là bài tổng hợp: hàng đợi bid an toàn + tổng hợp giá cao nhất đa luồng.

### Bước 1 — Engine: hàng đợi bid + bảng giá cao nhất theo phiên

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

public class BidQueueEngine {
    public record Bid(long auctionId, String bidder, long amount) {}   // bất biến
    private static final Bid POISON = new Bid(-1, "", -1);

    // Hàng đợi bid CÓ CHẶN (bounded) → back-pressure khi bão bid
    private final BlockingQueue<Bid> queue = new ArrayBlockingQueue<>(1000);

    // Giá cao nhất hiện tại của TỪNG phiên — map an toàn đa luồng
    private final ConcurrentHashMap<Long, Long> highestByAuction = new ConcurrentHashMap<>();

    private final LongAdder processed = new LongAdder();   // đếm bid đã xử lý (ghi nhiều)
    private final ExecutorService workers;
    private final int consumerCount;

    public BidQueueEngine(int consumerCount) {
        this.consumerCount = consumerCount;
        this.workers = Executors.newFixedThreadPool(consumerCount);
        for (int i = 0; i < consumerCount; i++) workers.submit(this::consumeLoop);
    }

    /** Producer gọi: đẩy bid vào hàng đợi (CHẶN nếu đầy). */
    public void offer(Bid bid) throws InterruptedException { queue.put(bid); }

    /** Worker loop: lấy bid, cập nhật giá cao nhất của phiên đó một cách NGUYÊN TỬ. */
    private void consumeLoop() {
        try {
            while (true) {
                Bid bid = queue.take();                 // CHẶN nếu rỗng
                if (bid == POISON) break;
                // merge: nếu chưa có → đặt amount; nếu có → giữ giá LỚN hơn. Tất cả nguyên tử.
                highestByAuction.merge(bid.auctionId(), bid.amount(), Math::max);
                processed.increment();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public long highestOf(long auctionId) { return highestByAuction.getOrDefault(auctionId, 0L); }
    public long processedCount()          { return processed.sum(); }

    /** Đóng engine: gửi poison cho mỗi consumer rồi chờ tắt. */
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < consumerCount; i++) queue.put(POISON);
        workers.shutdown();
        workers.awaitTermination(30, TimeUnit.SECONDS);
    }
}
```

### Bước 2 — Mô phỏng nhiều client bắn bid vào nhiều phiên

```java
import java.util.concurrent.*;

public class BidQueueDemo {
    public static void main(String[] args) throws Exception {
        BidQueueEngine engine = new BidQueueEngine(4);    // 4 worker tiêu thụ
        int AUCTIONS = 5, BIDS_PER_AUCTION = 2000;

        try (ExecutorService clients = Executors.newFixedThreadPool(10)) {
            CountDownLatch sent = new CountDownLatch(AUCTIONS * BIDS_PER_AUCTION);
            for (long a = 1; a <= AUCTIONS; a++) {
                final long auctionId = a;
                for (int b = 1; b <= BIDS_PER_AUCTION; b++) {
                    final long amount = b;                // giá tăng dần 1..2000
                    clients.submit(() -> {
                        try { engine.offer(new BidQueueEngine.Bid(auctionId, "u" + amount, amount)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        finally { sent.countDown(); }
                    });
                }
            }
            sent.await();                                  // tất cả bid đã vào hàng đợi
        }
        engine.shutdown();                                 // chờ worker xử lý hết

        for (long a = 1; a <= AUCTIONS; a++) {
            System.out.printf("Phiên %d → giá cao nhất = %d (kỳ vọng %d)%n",
                    a, engine.highestOf(a), BIDS_PER_AUCTION);
        }
        System.out.println("Tổng bid đã xử lý = " + engine.processedCount()
                + " (kỳ vọng " + (AUCTIONS * BIDS_PER_AUCTION) + ")");
    }
}
```

### Bước 3 — Điểm cần quan sát

- **Mỗi phiên: giá cao nhất luôn = 2000** (bid lớn nhất) dù 4 worker xử lý song song — nhờ `merge(..., Math::max)` nguyên tử trên `ConcurrentHashMap`.
- **Tổng xử lý = 10.000** — không bid nào mất (BlockingQueue an toàn, không drop).
- **Bounded queue (1000)** → khi producer (10 client) bắn nhanh hơn 4 worker, `put` **chặn** producer → tự cân bằng, không OOM.

> 🧩 Liên hệ thực chiến: kiến trúc này tương đương Laravel `dispatch(bid)` → Redis queue → Horizon worker `merge` vào Redis hash. Khác biệt: ở đây tất cả trong RAM (nhanh, mất khi crash); Laravel bền qua Redis. `BlockingQueue` đóng vai Redis-queue, `ConcurrentHashMap.merge` đóng vai `Redis::hset`/`Redis::incr` có điều kiện. Day 25 (capstone) sẽ ghép tất cả thành một `AuctionEngine` hoàn chỉnh.

**Nhiệm vụ Day 24:**
0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Chạy `MapSafetyDemo`, xác nhận `HashMap` mất dữ liệu còn `ConcurrentHashMap` luôn đúng size.
2. Chạy `ProducerConsumerDemo` và Mini Project, xác nhận không mất bid và giá cao nhất đúng cho mọi phiên.
3. Thử đổi `ArrayBlockingQueue(1000)` thành `LinkedBlockingQueue` không giới hạn và quan sát khác biệt về back-pressure (queue phình).
4. Ghi `notes/day-24.md`: bảng "tình huống → collection nên dùng" bằng lời của bạn, kèm vì sao `ConcurrentHashMap` scalable hơn `synchronizedMap`.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Vì sao `Collections.synchronizedMap` chưa đủ tốt?**
> **Đáp:** Nó bọc mọi method trong `synchronized` trên **một khóa toàn cục** → mọi thao tác (kể cả đọc) tranh một khóa, nghẽn dưới tải cao. Nó cũng **không** làm compound action (check-then-act) nguyên tử, và duyệt phải tự khóa thủ công kẻo `ConcurrentModificationException`. `ConcurrentHashMap` khóa mức bucket (scalable) và có thao tác gộp nguyên tử.

**Q2: `ConcurrentHashMap` có cho `null` không? Vì sao?**
> **Đáp:** **Không** (cả key lẫn value) → `NullPointerException`. Vì trong môi trường đa luồng, `get` trả `null` sẽ mơ hồ giữa "key không tồn tại" và "key map tới null", không thể phân biệt an toàn (không có `containsKey` nguyên tử kèm theo). Cấm null để loại bỏ mơ hồ.

**Q3: `BlockingQueue` dùng để làm gì? `put`/`take` khác `offer`/`poll` thế nào?**
> **Đáp:** Là hàng đợi an toàn đa luồng, nền của producer-consumer. `put(e)` **chặn** nếu đầy, `take()` **chặn** nếu rỗng → tự cân bằng tốc độ hai bên (back-pressure). `offer`/`poll` không chặn (trả `false`/`null` ngay, hoặc có biến thể timeout). Nó giúp bạn khỏi phải tự viết `wait()/notify()` dễ sai.

**Q4: Khi nào dùng `CopyOnWriteArrayList`?**
> **Đáp:** Khi **đọc/duyệt rất nhiều, ghi rất hiếm** — ví dụ danh sách listener/observer. Đọc không khóa, iterator chụp snapshot nên không bao giờ `ConcurrentModificationException`. Không dùng khi ghi thường xuyên vì mỗi ghi copy cả mảng (đắt).

### Mức Senior

**Q5: `ConcurrentHashMap` (Java 8) đạt thread-safety mà vẫn scalable bằng cách nào?**
> **Đáp:** Thay vì khóa toàn bảng, nó khóa **ở mức bucket/node**: thêm vào bucket rỗng dùng **CAS** (lock-free), bucket đã có node thì `synchronized` chỉ trên **node đầu bucket đó** — các bucket khác vẫn ghi song song. `get` hầu như không khóa (dựa visibility/`volatile`). (Java 7 cũ dùng segment/lock striping; Java 8 mịn hơn ở mức bucket + dùng red-black tree khi bucket quá dài.) Nhờ vậy nhiều thread ghi vào key khác nhau gần như không tranh chấp.

**Q6: `AtomicLong` vs `LongAdder` — khi nào dùng cái nào?**
> **Đáp:** `AtomicLong` dùng một biến + CAS; dưới tranh chấp **cực cao** (nhiều thread cùng ghi), vòng lặp CAS retry liên tục thành điểm nóng. `LongAdder` **phân tán** việc cộng ra nhiều cell (mỗi thread cộng vào cell riêng), `sum()` mới cộng gộp → nhanh hơn nhiều khi **ghi rất nhiều, đọc thưa** (metrics). Dùng `AtomicLong` khi cần giá trị chính xác từng bước (ID tuần tự); `LongAdder` khi chỉ cần tổng cuối và ghi cường độ cao.

**Q7: `computeIfAbsent` đảm bảo gì, và bẫy của nó là gì?**
> **Đáp:** Đảm bảo hàm khởi tạo chỉ chạy **đúng một lần cho mỗi key** dù nhiều thread tranh nhau (thay check-then-act → tránh tạo trùng tài nguyên). Bẫy: hàm chạy **trong khi giữ khóa bucket**, nên nếu hàm chạy lâu sẽ chặn cập nhật bucket đó; tệ hơn, nếu hàm **gọi lại** `computeIfAbsent`/`compute` trên **cùng map** (đặc biệt cùng/đệ quy key) có thể gây treo/deadlock. Giữ hàm ngắn, thuần, không tự gọi lại map.

**Q8: Iterator của `ConcurrentHashMap` và `CopyOnWriteArrayList` khác nhau về tính nhất quán thế nào?**
> **Đáp:** `ConcurrentHashMap` — **weakly consistent**: duyệt không ném CME, phản ánh trạng thái tại thời điểm nào đó, có thể thấy hoặc không thấy thay đổi xảy ra trong lúc duyệt (không khóa toàn bảng). `CopyOnWriteArrayList` — **snapshot**: iterator chụp ảnh mảng tại thời điểm tạo, hoàn toàn không thấy thay đổi sau đó (và không hỗ trợ `remove` qua iterator). Cả hai đều an toàn duyệt khi có ghi đồng thời, nhưng "nhìn thấy" thay đổi khác nhau.

**Q9: Trong một Spring `@Service` singleton, bạn cần một cache đếm số lần truy cập theo key. Bạn dùng gì và vì sao?**
> **Đáp:** `ConcurrentHashMap<Key, LongAdder>` (hoặc `ConcurrentHashMap<Key, AtomicLong>`): dùng `map.computeIfAbsent(key, k -> new LongAdder()).increment()` — `computeIfAbsent` đảm bảo mỗi key có đúng một counter (không tạo trùng dù tranh chấp), `LongAdder.increment()` đếm nguyên tử tối ưu ghi nhiều. Tránh `HashMap` (mất dữ liệu khi ghi đồng thời trong singleton chia sẻ mọi request) và tránh `synchronized` quanh `HashMap` (nghẽn). Nếu cần chia sẻ giữa nhiều instance thì chuyển sang Redis/Caffeine.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích vì sao `HashMap` không an toàn và `synchronizedMap` chưa đủ tốt
- [ ] Hiểu cơ chế khóa mức bucket (CAS + synchronized node) của `ConcurrentHashMap`
- [ ] Dùng `computeIfAbsent`/`merge` cho compound action nguyên tử; nhớ "không null"
- [ ] Biết khi nào dùng `CopyOnWriteArrayList` (đọc nhiều ghi ít)
- [ ] Cài producer-consumer bằng `BlockingQueue` (`put`/`take` chặn, poison pill)
- [ ] Phân biệt `AtomicLong` vs `LongAdder`, dùng đúng cho metrics
- [ ] Chọn đúng collection cho từng tình huống (thuộc bảng chọn)
- [ ] Hoàn thành Mini Project: hàng đợi bid an toàn + bảng giá cao nhất bằng `ConcurrentHashMap`
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 5 "Building Blocks" (concurrent collections, blocking queues, producer-consumer)
- Javadoc gói `java.util.concurrent` — `ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`/`ArrayBlockingQueue`/`LinkedBlockingQueue`, `LongAdder`, `ConcurrentLinkedQueue`
- Oracle Java Tutorials — "Concurrency: Concurrent Collections"
- Baeldung — "Guide to ConcurrentHashMap", "Guide to java.util.concurrent.BlockingQueue", "LongAdder and LongAccumulator", "CopyOnWriteArrayList"
- Doug Lea — "Overview of package util.concurrent" (tác giả gốc của JUC, đọc để hiểu triết lý thiết kế)
