# Day 18 - Synchronization

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã *thấy* race condition ở Day 16, nay học cách **bảo vệ** shared mutable state.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **vùng tới hạn (critical section)** là gì và vì sao phải bảo vệ nó.
- Nắm chắc từ khóa **`synchronized`**: synchronized method vs synchronized block, lock trên `this` vs lock trên `ClassName.class`.
- Hiểu **monitor / intrinsic lock** — mỗi object có một "ổ khóa" gắn liền, và `synchronized` chính là acquire ổ khóa đó.
- Hiểu tính **reentrant (vào lại)**: tại sao một thread đang giữ khóa có thể vào lại synchronized khác trên cùng object mà không tự deadlock.
- Dùng được **`ReentrantLock`** (`lock`/`unlock`/`tryLock`/`lockInterruptibly`/fairness) và biết **khi nào chọn nó thay cho `synchronized`**.
- Biết **`ReadWriteLock`** để tối ưu kịch bản "đọc nhiều, ghi ít".
- Hiểu khái niệm **lock granularity & contention** — đánh đổi giữa "đúng" và "nhanh".
- **Sửa được bug shared state của `Auction`** đã lộ ở Day 16.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vùng tới hạn (Critical Section) — gốc rễ của mọi thứ

**Vùng tới hạn** là đoạn code truy cập (đọc *và* ghi) **shared mutable state** — trạng thái dùng chung có thể thay đổi — mà nếu nhiều thread vào *đồng thời* thì kết quả sẽ sai.

Nhớ lại `placeBid` ở Day 16:

```java
if (amount > highestBid) {     // (1) ĐỌC highestBid
    highestBid    = amount;    // (2) GHI highestBid
    highestBidder = bidder;    // (3) GHI highestBidder
}
```

Ba dòng này **phải xảy ra như một khối liền mạch (atomic)**. Nhưng JVM không đảm bảo điều đó: scheduler có thể "cắt" thread ngay sau bước (1). Khi đó hai thread cùng đọc thấy `highestBid = 100`, cả hai cùng nghĩ "giá mình cao hơn", rồi lần lượt ghi đè → **một bid bị mất**.

```
Thread A: đọc highestBid=100 ──┐ (bị scheduler cắt ở đây)
Thread B: đọc highestBid=100 ──┤  cả hai cùng thấy 100
Thread A: ghi highestBid=150 ──┤  A ghi trước
Thread B: ghi highestBid=120 ──┘  B ghi sau, ĐÈ LÊN 150  => giá tụt còn 120 (SAI!)
```

> 💡 Định nghĩa cốt lõi: **đồng bộ hóa (synchronization) = đảm bảo tại một thời điểm CHỈ MỘT thread được vào vùng tới hạn** (loại trừ lẫn nhau — *mutual exclusion*). Mọi công cụ hôm nay (`synchronized`, `ReentrantLock`, `ReadWriteLock`) đều chỉ phục vụ mục tiêu này.

⚠️ Chỉ **shared mutable state** mới cần bảo vệ. Biến cục bộ (local variable) nằm trên Stack riêng của mỗi thread → **không bao giờ** là vùng tới hạn. Nhớ kim chỉ nam Day 16: *Stack riêng an toàn, Heap chung mới nguy hiểm.*

### 2. Monitor / Intrinsic Lock — "ổ khóa" gắn liền mỗi object

Trong Java, **mỗi object đều có sẵn một monitor** (còn gọi *intrinsic lock* — khóa nội tại). Bạn không tạo nó, JVM gắn sẵn cho mọi object. Monitor giống một **ổ khóa chỉ chứa được một chìa**:

- Thread muốn vào `synchronized` phải **acquire (giành) monitor** của object đó.
- Chỉ **một** thread giữ được monitor tại một thời điểm.
- Thread nào tới sau bị **chặn (BLOCKED)**, xếp hàng chờ.
- Khi thread đang giữ **release (nhả) monitor** (ra khỏi synchronized), một thread chờ được đánh thức để giành tiếp.

```
        Object  ████  (monitor: 1 chìa khóa)
                 │
   ┌─────────────┼──────────────┐
   │ Thread A    │   Thread B    │   Thread C
   │ acquire OK  │   BLOCKED     │   BLOCKED
   │ (đang chạy  │   (xếp hàng)  │   (xếp hàng)
   │  critical   │               │
   │  section)   │               │
   └─────────────┘               │
        │ release                │
        └────────► B được đánh thức, acquire ──► chạy ──► release ──► C ...
```

> 💡 "Synchronized" = "tôi xin giữ monitor của object X trong suốt khối này". Mọi `synchronized` cùng khóa trên **cùng một object** sẽ loại trừ lẫn nhau. Khóa trên *object khác nhau* thì **không** ngăn nhau — đây là nguồn của nhiều bug (xem mục 4).

### 3. Từ khóa `synchronized` — method vs block

**(a) Synchronized instance method** — khóa trên `this`:

```java
public synchronized void placeBid(...) { ... }   // tương đương:
public void placeBid(...) { synchronized (this) { ... } }
```

**(b) Static synchronized method** — khóa trên **Class object** (`ClassName.class`), KHÔNG phải `this`:

```java
public static synchronized void resetStats() { ... } // tương đương:
public static void resetStats() { synchronized (Auction.class) { ... } }
```

**(c) Synchronized block** — bạn **tự chọn lock object**, granularity nhỏ hơn:

```java
private final Object lock = new Object();   // lock riêng, không lộ ra ngoài

public void placeBid(...) {
    // ... code KHÔNG đụng shared state, chạy song song thoải mái ...
    synchronized (lock) {           // chỉ phần này bị tuần tự hóa
        if (amount > highestBid) { highestBid = amount; highestBidder = bidder; }
    }
    // ... phần còn lại lại song song ...
}
```

| Tiêu chí | `synchronized` method (instance) | `synchronized` method (static) | `synchronized` block |
|---|---|---|---|
| Lock trên | `this` | `ClassName.class` | Object bạn chỉ định |
| Phạm vi khóa | Toàn bộ method | Toàn bộ method | Chỉ đoạn trong `{}` |
| Granularity | To (kém song song) | To | **Nhỏ (tốt hơn)** |
| Khuyến nghị | OK cho class nhỏ | Cho static state | **Ưu tiên khi cần tối ưu** |

> 💡 **Best practice:** dùng `synchronized (private final Object lock)` thay vì `synchronized (this)`. Vì `this` lộ ra ngoài — code bên ngoài có thể `synchronized (auctionObject)` và vô tình can thiệp vào khóa của bạn. Một `private final Object lock` thì **không ai bên ngoài chạm tới được**, an toàn hơn. (Tương tự, đừng khóa trên `String` literal hay `Integer` đã được cache — chúng có thể bị chia sẻ ngoài ý muốn.)

### 4. BẪY LỚN: lock trên `this` vs lock trên Class object

Static synchronized method khóa trên `Auction.class`; instance synchronized method khóa trên `this`. **Đó là HAI ổ khóa KHÁC NHAU.**

```java
class Counter {
    private static int total;
    private        int local;

    public synchronized void incLocal()        { local++; }   // khóa: this
    public static synchronized void incTotal()  { total++; }   // khóa: Counter.class
}
```

Hai thread có thể chạy **đồng thời** `incLocal()` (giữ `this`) và `incTotal()` (giữ `Counter.class`) — chúng **không** loại trừ nhau! Nếu hai method này lỡ đụng cùng một biến, bạn vẫn dính race condition dù đã ghi `synchronized` ở cả hai.

```
Thread A → incLocal()  giành khóa [this]          ─┐ chạy SONG SONG
Thread B → incTotal()  giành khóa [Counter.class] ─┘ (hai ổ khóa khác nhau)
```

> ⚠️ Quy tắc vàng: **mọi đoạn truy cập cùng một shared state phải dùng CÙNG MỘT lock object.** Trộn lock trên `this` và lock trên Class object để bảo vệ *cùng một* dữ liệu là sai. Nếu bảo vệ static state → dùng static synchronized (hoặc một static lock object) cho tất cả.

### 5. Tính Reentrant (vào lại) — vì sao thread không tự deadlock

Monitor của Java có tính **reentrant**: nếu một thread **đã giữ** monitor của object X, nó có thể vào tiếp một `synchronized` khác **cũng khóa trên X** mà **không bị chặn**. JVM **đếm số lần** vào (hold count): mỗi lần vào +1, mỗi lần ra -1; chỉ khi đếm về 0 thì monitor mới thực sự được nhả.

```java
class ReentrantDemo {
    public synchronized void outer() {   // acquire this, hold=1
        System.out.println("outer");
        inner();                         // gọi method cũng synchronized trên this
    }
    public synchronized void inner() {   // CÙNG thread đang giữ this -> hold=2, KHÔNG chặn
        System.out.println("inner");
    }                                    // ra inner -> hold=1
}                                        // ra outer -> hold=0, nhả monitor
```

Nếu monitor **không** reentrant, dòng `inner()` sẽ tự chặn chính nó (thread đang giữ khóa lại chờ chính khóa đó) → **deadlock với chính mình**. Nhờ reentrant, điều đó không xảy ra.

> 💡 `ReentrantLock` (tên đã nói lên) cũng reentrant: một thread `lock()` hai lần thì phải `unlock()` đúng hai lần. Đây là lý do bạn **luôn** đếm cặp lock/unlock cho khớp.

### 6. `ReentrantLock` — khóa "có điều khiển" của `java.util.concurrent.locks`

`synchronized` là khóa "ngầm": JVM tự acquire/release theo khối, bạn không điều khiển được. `ReentrantLock` là khóa **tường minh (explicit)**, cho bạn nhiều quyền kiểm soát hơn:

```java
import java.util.concurrent.locks.ReentrantLock;

ReentrantLock lock = new ReentrantLock();

lock.lock();                 // giành khóa (chặn tới khi có)
try {
    // vùng tới hạn
} finally {
    lock.unlock();           // BẮT BUỘC nhả trong finally — nếu không sẽ kẹt vĩnh viễn
}
```

⚠️ **Khác biệt sống còn với `synchronized`:** `synchronized` tự nhả khóa khi ra khối (kể cả khi ném exception). `ReentrantLock` **KHÔNG tự nhả** — bạn phải gọi `unlock()` *bằng tay*, và **luôn đặt trong `finally`**. Quên `finally` mà code ném exception → khóa không bao giờ được nhả → mọi thread khác kẹt mãi mãi.

Các năng lực mà `synchronized` **không** có:

```java
import java.util.concurrent.TimeUnit;

// 1) tryLock(): thử giành, KHÔNG chặn nếu khóa đang bận -> tránh treo
if (lock.tryLock()) {
    try { /* ... */ } finally { lock.unlock(); }
} else {
    // không giành được -> làm việc khác / báo bận thay vì đứng chờ
}

// 2) tryLock có timeout: chờ tối đa 1 giây rồi bỏ cuộc
if (lock.tryLock(1, TimeUnit.SECONDS)) {     // ném InterruptedException
    try { /* ... */ } finally { lock.unlock(); }
}

// 3) lockInterruptibly(): trong lúc chờ khóa, vẫn phản hồi được interrupt
lock.lockInterruptibly();   // synchronized thì KHÔNG thể bị interrupt khi đang chờ

// 4) fairness: new ReentrantLock(true) -> cấp khóa theo thứ tự FIFO (công bằng)
ReentrantLock fair = new ReentrantLock(true);   // tránh "starvation" nhưng chậm hơn
```

| Tính năng | `synchronized` | `ReentrantLock` |
|---|---|---|
| Acquire/release | Tự động (theo khối) | **Thủ công** (`lock`/`unlock`) |
| Nhả khi exception | Tự nhả | Phải tự lo trong `finally` |
| Thử khóa không chặn (`tryLock`) | ❌ | ✅ |
| Chờ khóa có timeout | ❌ | ✅ |
| Phản hồi interrupt khi chờ | ❌ | ✅ (`lockInterruptibly`) |
| Fairness (FIFO) | ❌ (luôn unfair) | ✅ (`new ReentrantLock(true)`) |
| Reentrant | ✅ | ✅ |
| Cú pháp gọn, khó sai | ✅ (đơn giản) | ❌ (dễ quên `unlock`) |
| Hiệu năng | Rất tốt (JVM tối ưu mạnh) | Tốt |

> 💡 **Khi nào dùng cái nào?** Mặc định dùng `synchronized` — gọn, khó sai, JVM tối ưu rất tốt (lightweight locking). Chỉ chọn `ReentrantLock` khi bạn **thực sự cần** một trong: `tryLock`/timeout (tránh treo), `lockInterruptibly` (hủy được), fairness, hoặc nhiều `Condition` riêng biệt. "Đừng dùng dao mổ trâu khi chỉ cần con dao gọt."

### 7. `ReadWriteLock` — tối ưu "đọc nhiều, ghi ít"

`synchronized` và `ReentrantLock` đều loại trừ **tuyệt đối**: ngay cả hai thread chỉ *đọc* cũng phải xếp hàng — lãng phí khi đọc vốn không xung đột nhau. `ReentrantReadWriteLock` tách thành hai khóa:

- **readLock (chia sẻ):** nhiều thread có thể giữ **cùng lúc** — đọc song song thoải mái.
- **writeLock (độc quyền):** chỉ một thread giữ, và **loại trừ tất cả** reader lẫn writer khác.

```java
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

ReadWriteLock rw = new ReentrantReadWriteLock();

// ĐỌC: nhiều thread vào cùng lúc
rw.readLock().lock();
try { return highestBid; } finally { rw.readLock().unlock(); }

// GHI: độc quyền
rw.writeLock().lock();
try { highestBid = amount; } finally { rw.writeLock().unlock(); }
```

> 💡 Chỉ lợi khi **đọc áp đảo ghi** (ví dụ cache, bảng cấu hình đọc liên tục, ghi hiếm). Nếu ghi nhiều, chi phí quản lý phức tạp hơn của ReadWriteLock có thể **chậm hơn** `synchronized` đơn giản. Đo trước khi tối ưu.

### 8. Lock granularity & Contention — đánh đổi cốt lõi

- **Contention (tranh chấp):** khi nhiều thread cùng muốn một khóa, chúng phải xếp hàng → mất song song. Khóa càng bị tranh nhiều, throughput càng tụt.
- **Granularity (độ mịn của khóa):**
  - **Coarse-grained (khóa to):** khóa cả object/cả method. **Dễ đúng** (ít chỗ sai) nhưng **nghẽn** (mọi thao tác tuần tự hóa).
  - **Fine-grained (khóa nhỏ):** khóa từng phần nhỏ, hoặc nhiều khóa độc lập. **Song song cao** nhưng **dễ sai** và **dễ deadlock** (nhiều khóa → thứ tự giành khóa phức tạp).

```
Khóa TO  ──► an toàn cao, song song thấp   (ưu tiên ĐÚNG)
Khóa NHỎ ──► song song cao, rủi ro cao      (ưu tiên NHANH)
                       ▲
            phải cân bằng theo bài toán
```

> ⚠️ Lời khuyên thực chiến: **bắt đầu bằng khóa to cho đúng, rồi mới thu nhỏ khi đo thấy nghẽn.** Đừng tối ưu khóa quá sớm — "premature optimization" trong concurrency dễ đẻ ra deadlock cực khó debug. Giữ critical section **càng ngắn càng tốt** (đừng gọi I/O/sleep/network *bên trong* khóa).

---

## 🔁 Đối chiếu với Laravel/PHP

Đây tiếp tục là vùng tư duy **mới hoàn toàn** với người PHP. Lý do: **PHP-FPM mỗi request là một process/worker tách biệt, KHÔNG chia sẻ bộ nhớ trong cùng tiến trình.** Vì vậy khái niệm "critical section / monitor / synchronized" — vốn nói về nhiều thread tranh một vùng nhớ chung — gần như **không tồn tại** trong PHP đời thường.

Thay vào đó, khi PHP cần đồng bộ, nó đồng bộ ở **tầng tài nguyên ngoài** (DB, Redis), chứ không phải trong RAM:

| Vấn đề cần đồng bộ | Java (trong bộ nhớ tiến trình) | Laravel/PHP (qua tài nguyên ngoài) |
|---|---|---|
| Loại trừ lẫn nhau | `synchronized` / `ReentrantLock` (khóa trên object trong Heap) | `Cache::lock()` (atomic lock qua Redis/Memcached) |
| Bảo vệ bản ghi khỏi ghi đè | Lock object bao quanh read-modify-write | **Pessimistic lock**: `SELECT ... FOR UPDATE` trong transaction DB |
| Phát hiện cập nhật xung đột | (thường vẫn dùng lock, hoặc atomic ở Day 19) | **Optimistic lock**: cột `version`, `UPDATE ... WHERE version = ?` |
| Đọc nhiều ghi ít | `ReadWriteLock` | Cache + invalidation (không có "read lock" trong RAM) |
| Phạm vi khóa | Trong **một tiến trình JVM** | Xuyên **nhiều worker / nhiều server** |

Ví dụ song song trực tiếp với critical section của ta:

```php
// Laravel: bảo vệ "đặt giá cao nhất" — khóa qua Redis (xuyên nhiều worker)
Cache::lock('auction:42', 5)->block(3, function () use ($amount, $bidder) {
    $auction = Auction::find(42);
    if ($amount > $auction->highest_bid) {       // <-- critical section
        $auction->update(['highest_bid' => $amount, 'highest_bidder' => $bidder]);
    }
});

// Hoặc pessimistic lock ở tầng DB:
DB::transaction(function () use ($amount, $bidder) {
    $auction = Auction::lockForUpdate()->find(42); // SELECT ... FOR UPDATE
    if ($amount > $auction->highest_bid) { /* update */ }
});
```

**Khác biệt tư duy quan trọng nhất:**
- **PHP:** vì các request *không* chia sẻ RAM, bạn đồng bộ ở **biên giới tài nguyên dùng chung thực sự** = database/Redis. Khóa của bạn xuyên qua nhiều worker và nhiều server. Bạn (gần như) không bao giờ nghĩ tới "monitor của một object".
- **Java:** nhiều thread chia sẻ Heap *ngay trong một tiến trình*, nên bạn phải đồng bộ **trong bộ nhớ** trước tiên (`synchronized`/lock). Nhưng ⚠️ khi scale **nhiều instance JVM** (nhiều pod Kubernetes), `synchronized` chỉ khóa trong **một** JVM — không xuyên instance! Lúc đó Java cũng phải quay lại đúng kiểu Laravel: **distributed lock** (Redisson, DB lock). Hai thế giới gặp nhau ở đây.

> 🧩 Tóm gọn: Laravel dạy bạn "khóa qua tài nguyên ngoài"; Java thêm cho bạn một tầng nữa "khóa trong bộ nhớ". Hiểu cả hai, bạn sẽ chọn đúng công cụ: trong-1-JVM dùng `synchronized`; xuyên-nhiều-JVM dùng distributed lock.

---

## 💻 Thực hành code

### Bài (a) — Counter KHÔNG đồng bộ cho kết quả SAI

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UnsafeCounter {
    private int count = 0;

    public void increment() {
        count++;   // KHÔNG nguyên tử: thực ra là read -> +1 -> write (3 bước)
    }

    public int getCount() { return count; }

    public static void main(String[] args) throws InterruptedException {
        UnsafeCounter c = new UnsafeCounter();
        int soThread = 10, soLan = 100_000;

        ExecutorService pool = Executors.newFixedThreadPool(soThread);
        for (int i = 0; i < soThread; i++) {
            pool.submit(() -> { for (int j = 0; j < soLan; j++) c.increment(); });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);   // chờ mọi task xong

        System.out.println("Mong đợi: " + (soThread * soLan));  // 1.000.000
        System.out.println("Thực tế : " + c.getCount());        // < 1.000.000 (đếm THIẾU!)
    }
}
```

> ⚠️ Chạy nhiều lần: kết quả "Thực tế" gần như **luôn nhỏ hơn** 1.000.000, và **mỗi lần một con số khác**. Vì `count++` là ba thao tác (đọc → cộng → ghi); hai thread cùng đọc một giá trị rồi cùng ghi đè → mất một lần đếm. Đây là race condition trên thao tác *read-modify-write*.

### Bài (b) — Thêm `synchronized` → ĐÚNG

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SafeCounter {
    private int count = 0;
    private final Object lock = new Object();   // lock riêng, không lộ this

    public void increment() {
        ___ (lock) { // Điền từ khóa đồng bộ block
            count++;
        }
    }

    public int getCount() {
        ___ (lock) { return count; } // Đọc cũng cần đồng bộ
    }

    public static void main(String[] args) throws InterruptedException {
        SafeCounter c = new SafeCounter();
        int soThread = 10, soLan = 100_000;

        ExecutorService pool = Executors.newFixedThreadPool(soThread);
        for (int i = 0; i < soThread; i++) {
            pool.submit(() -> { for (int j = 0; j < soLan; j++) c.increment(); });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        System.out.println("Mong đợi: " + (soThread * soLan));  // 1.000.000
        System.out.println("Thực tế : " + c.getCount());        // ĐÚNG = 1.000.000, mọi lần chạy
    }
}
```

> 💡 Lưu ý cả `getCount()` cũng `synchronized`. Ngoài "loại trừ lẫn nhau", `synchronized` còn đảm bảo **visibility** — thread đọc thấy được giá trị mới nhất do thread khác ghi (sẽ học sâu ở Day 19 với `volatile`/memory model). Bảo vệ cả đọc lẫn ghi mới đúng trọn vẹn.

### Bài (c) — `ReentrantLock` với `tryLock` + `finally`

```java
import java.util.concurrent.locks.ReentrantLock;

public class LockCounter {
    private long count = 0;
    private final ReentrantLock lock = new ReentrantLock();

    public void increment() {
        lock.___(); // Điền phương thức giành khóa (chặn tới khi có)
        try {
            count++;
        } ___ { // Điền khối luôn thực thi
            lock.___(); // Điền phương thức BẮT BUỘC để nhả khóa
        }
    }

    /** Thử tăng nhưng KHÔNG đứng chờ nếu khóa đang bận. Trả về true nếu thành công. */
    public boolean tryIncrement() {
        if (lock.___()) { // Điền phương thức thử giành khóa, không giành được -> trả false ngay
            try {
                count++;
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;                // bận -> bỏ qua lần này (tùy nghiệp vụ)
    }

    public long getCount() {
        lock.lock();
        try { return count; } finally { lock.unlock(); }
    }

    public static void main(String[] args) {
        LockCounter c = new LockCounter();
        c.increment();
        System.out.println("tryIncrement: " + c.tryIncrement());  // true
        System.out.println("count = " + c.getCount());            // 2
    }
}
```

> ⚠️ Mẫu `lock(); try { ... } finally { lock.unlock(); }` là **bất di bất dịch**. Quên `finally` mà thân khóa ném exception → `unlock()` không chạy → khóa kẹt vĩnh viễn → toàn bộ thread khác BLOCKED mãi mãi (một dạng "deadlock do rò khóa").

### Bài (d) — `ReadWriteLock` (đọc nhiều, ghi ít)

```java
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Cache cấu hình: đọc liên tục, ghi hiếm -> ReadWriteLock cho đọc song song. */
public class ConfigCache {
    private final Map<String, String> data = new HashMap<>();
    private final ReadWriteLock rw = new ReentrantReadWriteLock();

    public String get(String key) {
        rw.___().lock(); // Điền phương thức lấy khóa đọc
        try {
            return data.get(key);
        } finally {
            rw.___().unlock(); // Nhớ nhả khóa
        }
    }

    public void put(String key, String value) {
        rw.___().lock(); // Điền phương thức lấy khóa ghi
        try {
            data.put(key, value);
        } finally {
            rw.___().unlock(); // Nhớ nhả khóa
        }
    }
}
```

> 💡 Nhiều thread `get()` chạy đồng thời (read lock chia sẻ) → throughput đọc cao. Chỉ khi `put()` (write lock) thì mọi người mới phải xếp hàng. Đúng kịch bản "đọc áp đảo ghi".

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Quên `unlock()` trong `finally` với `ReentrantLock`.** Bẫy số 1. Exception giữa khối → khóa kẹt vĩnh viễn. `synchronized` không dính lỗi này (tự nhả), đây là cái giá của "tường minh".
- **Đồng bộ trên lock object KHÁC NHAU cho cùng một state.** Hai method khóa hai object khác nhau → không loại trừ nhau → race condition vẫn còn. Quy tắc: *một state, một lock.*
- **Trộn instance `synchronized` (khóa `this`) và static `synchronized` (khóa `Class`) cho cùng dữ liệu.** Hai ổ khóa khác nhau → chạy song song → vẫn sai.
- **Chỉ đồng bộ lúc GHI, quên lúc ĐỌC.** Reader có thể thấy giá trị cũ/dở dang (visibility). Đọc shared mutable state cũng cần đồng bộ (hoặc `volatile`/atomic — Day 19).
- **Khóa trên object KHÔNG bất biến hoặc bị thay thế.** Nếu biến lock bị gán lại (`lock = new Object()`), các thread đang khóa object cũ và thread mới khóa object mới → không loại trừ nhau. Luôn `private final` cho lock.
- **Khóa trên `String` literal / `Integer` cache / boxed types.** Chúng có thể được JVM dùng chung ở nơi khác → khóa "rò" sang code không liên quan. Dùng `new Object()` riêng.
- **Critical section quá to / gọi I/O bên trong khóa.** Giữ khóa trong lúc gọi DB/network/`sleep` → các thread khác chờ dài → throughput sập. Giữ critical section **ngắn nhất có thể**.
- **Lạm dụng `ReentrantLock` khi `synchronized` là đủ.** Phức tạp hơn, dễ quên `unlock`, không nhanh hơn đáng kể. Chỉ dùng khi cần tính năng đặc thù (`tryLock`/timeout/interrupt/fairness).
- **Tưởng `synchronized` khóa xuyên nhiều JVM.** Nó chỉ khóa trong **một** tiến trình. Scale nhiều instance → cần distributed lock.

---

## 🚀 Liên hệ Spring Boot / Production

- **Singleton bean + field mutable = phải đồng bộ.** Spring bean mặc định singleton, phục vụ mọi request (mỗi request một thread). Nếu buộc phải giữ state mutable trong bean (ví dụ counter/cache nội bộ), hãy bảo vệ bằng `synchronized`/lock — hoặc tốt hơn là dùng cấu trúc thread-safe (`ConcurrentHashMap`, `AtomicLong`) sẽ học ở Day 19.
- **Ưu tiên thiết kế *không cần khóa*.** Cách "Spring" nhất là giữ service **stateless**, đẩy state có thể thay đổi xuống DB/Redis và để chúng lo đồng bộ. Khóa trong RAM chỉ nên dùng cho state cục bộ thật sự cần thiết.
- **`synchronized` ≠ khóa phân tán.** Trong môi trường nhiều pod (Kubernetes), `synchronized` chỉ giữ trong một JVM. Cần loại trừ xuyên instance → dùng **Redisson `RLock`**, `ShedLock` (cho scheduled job chạy một lần duy nhất), hoặc DB pessimistic lock (`@Lock(LockModeType.PESSIMISTIC_WRITE)` của JPA).
- **JPA/Hibernate đã gói sẵn hai mô hình khóa DB:** *optimistic* (`@Version` — cột version, ném `OptimisticLockException` khi xung đột) và *pessimistic* (`SELECT ... FOR UPDATE`). Đây chính là phiên bản "đối chiếu Laravel" ở tầng DB — bạn đã quen từ thời PHP.
- **Đo contention thật.** Khi service chậm dưới tải, dùng **thread dump** (`jstack`) tìm thread `BLOCKED on monitor`, hoặc profiler (async-profiler, JFR) xem "lock contention". Khóa to chặn nghẽn là thủ phạm hiệu năng kinh điển.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta **SỬA bug shared state** đã cố ý để lộ ở Day 16: nhiều bidder đặt giá song song khiến `highestBid` bị ghi đè, mất bid. Cách sửa: bọc phần **so sánh-và-cập nhật** (compare-and-set) vào critical section. Ta làm **hai phiên bản**: `synchronized` và `ReentrantLock`. (Day 19 sẽ nâng cấp lên `AtomicReference` để *không cần khóa*.)

**Nhiệm vụ Day 18:**
1. Sửa `Auction.placeBid` để toàn bộ read-modify-write nằm trong một critical section.
2. Làm bản `synchronized` và bản `ReentrantLock`, so sánh.
3. Chạy nhiều bidder song song bằng `ExecutorService`, xác minh **không còn mất bid** — `highestBid` luôn bằng giá lớn nhất thực sự, qua mọi lần chạy.
4. Điền các chỗ trống `___` trong code thực hành ở trên.

```java
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Bản 1 — dùng synchronized block với lock riêng. */
class SafeAuction {
    private long   highestBid    = 0;
    private String highestBidder = "(chưa có)";
    private final Object lock = new Object();   // private final -> an toàn

    public void placeBid(String bidder, long amount) {
        ___ (lock) { // Điền từ khóa tạo vùng đồng bộ
            if (amount > highestBid) {          // (1) đọc
                highestBid    = amount;         // (2) ghi
                highestBidder = bidder;         // (3) ghi
            }
        }
    }

    public long getHighestBid() {
        synchronized (lock) { return highestBid; }       // đọc cũng đồng bộ
    }

    public String getHighestBidder() {
        synchronized (lock) { return highestBidder; }
    }
}

/** Bản 2 — dùng ReentrantLock (tương đương, nhưng mở đường cho tryLock/timeout). */
class LockAuction {
    private long   highestBid    = 0;
    private String highestBidder = "(chưa có)";
    private final ReentrantLock lock = new ReentrantLock();

    public void placeBid(String bidder, long amount) {
        lock.___(); // Điền phương thức giành khóa
        try {
            if (amount > highestBid) {
                highestBid    = amount;
                highestBidder = bidder;
            }
        } ___ { // Điền từ khóa khối kết thúc
            lock.___(); // Điền phương thức nhả khóa
        }
    }

    public long getHighestBid() {
        lock.lock();
        try { return highestBid; } finally { lock.unlock(); }
    }

    public String getHighestBidder() {
        lock.lock();
        try { return highestBidder; } finally { lock.unlock(); }
    }
}

public class AuctionSyncDemo {
    public static void main(String[] args) throws InterruptedException {
        SafeAuction auction = new SafeAuction();
        int soBidder = 8, soLanMoiBidder = 5_000;

        // Theo dõi giá lớn nhất THỰC SỰ được đặt, để đối chiếu kết quả.
        AtomicLong giaLonNhatThucTe = new AtomicLong(0);

        ExecutorService pool = Executors.newFixedThreadPool(soBidder);
        for (int i = 0; i < soBidder; i++) {
            String ten = "bidder-" + (i + 1);
            pool.submit(() -> {
                for (int lan = 0; lan < soLanMoiBidder; lan++) {
                    long gia = ThreadLocalRandom.current().nextLong(1, 1_000_000);
                    auction.placeBid(ten, gia);
                    giaLonNhatThucTe.accumulateAndGet(gia, Math::max);  // ghi nhận max thật
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        System.out.println("Giá lớn nhất THỰC SỰ đặt : " + giaLonNhatThucTe.get());
        System.out.println("Auction ghi nhận         : " + auction.getHighestBid()
                           + " bởi " + auction.getHighestBidder());

        boolean dung = auction.getHighestBid() == giaLonNhatThucTe.get();
        System.out.println(dung ? "✅ KHỚP — không mất bid nào!" : "❌ MẤT BID — vẫn còn race!");
    }
}
```

> ✅ **Quan sát:** chạy `AuctionSyncDemo` nhiều lần — dòng cuối **luôn** in `✅ KHỚP`. `auction.getHighestBid()` luôn bằng giá lớn nhất thực sự được đặt. So với Day 16 (bản chưa đồng bộ, kết quả nhảy lung tung và sai), giờ `placeBid` đã nguyên tử. Hãy **thử lại bản chưa đồng bộ của Day 16** với `soLanMoiBidder` lớn để thấy `❌ MẤT BID` xuất hiện — chính là bằng chứng race condition đã được synchronization dập tắt.
>
> 🔁 **Đổi sang `LockAuction`** (thay `new SafeAuction()` bằng `new LockAuction()`, đổi kiểu biến `auction`) và chạy lại — kết quả y hệt, đúng đắn như nhau. Đây là minh chứng `synchronized` và `ReentrantLock` tương đương về *tính đúng*; chúng chỉ khác về *tính năng điều khiển*. Day 19 sẽ thay khóa bằng `AtomicReference` để đạt cùng kết quả mà **không khóa**.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Critical section (vùng tới hạn) là gì? Vì sao phải bảo vệ?**
> **Đáp:** Là đoạn code truy cập (đọc và ghi) shared mutable state mà nếu nhiều thread vào đồng thời sẽ cho kết quả sai. Phải bảo vệ để đảm bảo **loại trừ lẫn nhau** — tại một thời điểm chỉ một thread vào — nhằm tránh race condition (đọc-sửa-ghi đè lên nhau, mất cập nhật). Biến cục bộ trên Stack riêng không phải critical section; chỉ state dùng chung trên Heap mới cần.

**Q2: `synchronized` method khóa trên cái gì?**
> **Đáp:** Instance synchronized method khóa trên `this` (object hiện tại). Static synchronized method khóa trên **Class object** (`ClassName.class`), KHÔNG phải `this`. Đây là hai khóa khác nhau, nên một static-sync method và một instance-sync method có thể chạy đồng thời — nếu chúng đụng cùng dữ liệu thì vẫn dính race.

**Q3: Monitor (intrinsic lock) là gì?**
> **Đáp:** Mỗi object trong Java có sẵn một monitor — một "ổ khóa" chỉ một thread giữ được tại một thời điểm. `synchronized (obj)` nghĩa là acquire monitor của `obj`; thread khác muốn vào synchronized cùng object phải xếp hàng (BLOCKED) tới khi thread đang giữ nhả khóa. Nó là cơ chế nền tảng để Java làm loại trừ lẫn nhau.

**Q4: Tính reentrant nghĩa là gì?**
> **Đáp:** Một thread đang giữ monitor của object X có thể vào tiếp các `synchronized` khác cũng khóa trên X mà không bị chặn — JVM đếm số lần vào (hold count), vào +1, ra -1, về 0 mới nhả thật. Nhờ vậy gọi method synchronized này từ method synchronized khác (cùng object) không gây tự-deadlock. `ReentrantLock` cũng có tính chất này: `lock()` n lần thì phải `unlock()` n lần.

### Mức Senior

**Q5: So sánh `synchronized` và `ReentrantLock`. Khi nào nên chọn cái nào?**
> **Đáp:** `synchronized` là khóa ngầm: JVM tự acquire/release theo khối, tự nhả kể cả khi exception, cú pháp gọn, khó sai, JVM tối ưu rất tốt. `ReentrantLock` là khóa tường minh: phải `lock()`/`unlock()` thủ công (luôn `unlock` trong `finally`), nhưng cho thêm `tryLock()` (không chặn), `tryLock(timeout)`, `lockInterruptibly()` (hủy khi đang chờ), và fairness (`new ReentrantLock(true)`). **Mặc định dùng `synchronized`**; chỉ chọn `ReentrantLock` khi thật sự cần một trong các năng lực đó (tránh treo, hủy được, công bằng, nhiều Condition). Cả hai đều reentrant; về hiệu năng gần tương đương trong đa số trường hợp.

**Q6: Vì sao phải đặt `unlock()` trong `finally`? Điều gì xảy ra nếu quên?**
> **Đáp:** `ReentrantLock` không tự nhả khóa như `synchronized`. Nếu thân khóa ném exception mà không có `finally`, dòng `unlock()` bị bỏ qua → khóa không bao giờ được nhả → mọi thread khác chờ khóa đó BLOCKED vĩnh viễn (rò khóa, tương đương deadlock). Vì vậy mẫu chuẩn là `lock.lock(); try { ... } finally { lock.unlock(); }` — bất di bất dịch.

**Q7: `ReadWriteLock` tối ưu được gì so với khóa thường? Đánh đổi là gì?**
> **Đáp:** Tách read lock (chia sẻ — nhiều reader vào cùng lúc) và write lock (độc quyền — chặn mọi reader/writer khác). Khi đọc áp đảo ghi (cache, cấu hình), reader chạy song song → throughput cao hơn nhiều so với khóa tuyệt đối vốn bắt cả reader xếp hàng. Đánh đổi: cơ chế phức tạp hơn, chi phí quản lý cao hơn; nếu ghi nhiều, nó có thể *chậm hơn* `synchronized` đơn giản, và còn rủi ro writer bị "đói" (starvation) nếu reader liên tục. Phải đo trước khi dùng.

**Q8: Lock granularity ảnh hưởng thế nào tới đúng-đắn và hiệu năng? Chiến lược thực chiến?**
> **Đáp:** Khóa to (coarse-grained, ví dụ khóa cả object) dễ đúng vì ít chỗ sai, nhưng tuần tự hóa mọi thao tác → contention cao → throughput thấp. Khóa nhỏ (fine-grained, nhiều khóa độc lập) cho song song cao nhưng dễ sai và dễ deadlock do thứ tự giành nhiều khóa phức tạp. Chiến lược: **bắt đầu bằng khóa to cho đúng, giữ critical section ngắn nhất có thể (không gọi I/O trong khóa), rồi chỉ thu nhỏ khóa khi đo (thread dump/profiler) thấy contention thật**. Tối ưu khóa quá sớm dễ đẻ deadlock cực khó tái hiện.

**Q9: Trong môi trường nhiều JVM (nhiều pod), `synchronized` còn đủ không?**
> **Đáp:** Không. `synchronized`/`ReentrantLock` chỉ khóa trong **một** tiến trình JVM — hai pod khác nhau có hai Heap riêng, monitor không chia sẻ. Muốn loại trừ lẫn nhau xuyên nhiều instance phải dùng **distributed lock**: Redisson `RLock`, `ShedLock` (đảm bảo scheduled job chạy một lần), hoặc khóa ở tầng DB (pessimistic `SELECT ... FOR UPDATE`, hoặc optimistic qua cột version). Đây cũng chính là cách Laravel/PHP vốn làm — đồng bộ qua tài nguyên ngoài.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được critical section và mục tiêu "loại trừ lẫn nhau" bằng lời của mình
- [ ] Phân biệt synchronized method (this) vs static synchronized (Class object) vs synchronized block
- [ ] Hiểu monitor/intrinsic lock và vẽ được sơ đồ hai thread tranh monitor
- [ ] Giải thích được tính reentrant và vì sao nó tránh tự-deadlock
- [ ] Dùng được `ReentrantLock` với `tryLock`/`finally`, biết khi nào chọn nó thay `synchronized`
- [ ] Biết `ReadWriteLock` dùng cho kịch bản đọc nhiều ghi ít
- [ ] Hiểu lock granularity & contention, và lời khuyên "khóa to trước, thu nhỏ sau"
- [ ] Chạy được counter unsafe (sai) → thêm synchronized (đúng)
- [ ] Sửa xong bug shared state của `Auction` (cả bản synchronized và ReentrantLock), xác minh "không mất bid"
- [ ] Trả lời được 9 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Concurrency": Synchronized Methods, Intrinsic Locks and Synchronization, Reentrant Synchronization
- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 2 (Thread Safety) & 11 (Performance/Contention)
- Javadoc `java.util.concurrent.locks` — `ReentrantLock`, `ReadWriteLock`, `ReentrantReadWriteLock`, `Condition`
- Baeldung — "Guide to the synchronized Keyword", "A Guide to ReentrantLock", "ReadWriteLock in Java"
- Laravel Docs — "Cache: Atomic Locks" và "Database: Pessimistic Locking" (để đối chiếu mô hình PHP)
- Redisson Wiki — Distributed Locks (tham khảo khi scale nhiều JVM)
