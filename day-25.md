# Day 25 - Auction Engine

> **Giai đoạn:** Concurrency Capstone
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn) — milestone lớn, có thể dành thêm.
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã đi hết phần concurrency (Day 16–24), giờ **ráp tất cả** thành một engine đấu giá thread-safe hoàn chỉnh.

---

## 🎯 Mục tiêu ngày hôm nay

- **Tổng hợp toàn bộ** kiến thức concurrency (Day 16–24) vào **một sản phẩm thật**: `AuctionEngine` đấu giá trong bộ nhớ, thread-safe.
- Đảm bảo **4 bất biến nghiệp vụ** dưới tải song song cực cao:
  1. **Không mất bid** (mọi bid hợp lệ đều được xét).
  2. **Giá cao nhất luôn thắng** (không bị lost update / ghi đè ngược).
  3. **Đóng phiên đúng hạn** (không nhận bid sau giờ đóng).
  4. **Trạng thái nhất quán** (winner = bid cao nhất tại thời điểm đóng).
- Áp dụng đúng công cụ cho đúng việc: `ExecutorService`, `BlockingQueue`, `ConcurrentHashMap`, `AtomicReference` + **CAS**, `volatile` flag, `CompletableFuture`, **Bid immutable**.
- Biết **kiểm thử dưới tải** nhiều thread để chứng minh tính đúng đắn (không phải "chạy local thấy ổn").
- So sánh tương phản với Laravel (DB lock/transaction) — và biết khi nào mô hình in-memory này phù hợp/không phù hợp.

---

## 🧠 Lý thuyết cốt lõi

### 1. Bài toán & các bất biến phải giữ

Một **phiên đấu giá** (auction): nhiều người **đặt giá đồng thời**, ai cao nhất thắng, phiên **đóng đúng hạn**. Nghe đơn giản, nhưng dưới đa luồng có đủ mọi cạm bẫy đã học:

| Bất biến nghiệp vụ | Cạm bẫy concurrency | Ngày học | Vũ khí |
|---|---|---|---|
| Không mất bid | Lost update khi nhiều thread cùng ghi | Day 19 | CAS / hàng đợi an toàn |
| Giá cao nhất luôn thắng | check-then-act race trên `highest` | Day 19 | `AtomicReference.compareAndSet` |
| Không nhận bid sau giờ đóng | visibility — thread không thấy cờ đóng | Day 21 | `volatile boolean closed` |
| Không treo / không deadlock | khóa sai thứ tự, giữ-và-chờ | Day 20 | lock-free (CAS), không khóa lồng |
| Kiểm soát tài nguyên dưới bão bid | tạo thread vô tội vạ, queue phình | Day 22, 24 | `ExecutorService` + bounded `BlockingQueue` |
| Tổng hợp nhiều phiên an toàn | ghi `HashMap` đồng thời | Day 24 | `ConcurrentHashMap` |

> 💡 Triết lý xuyên suốt (từ Day 19): **"State is the enemy."** Engine này tối thiểu hóa khóa, ưu tiên **bất biến** (`Bid` immutable) + **lock-free CAS**. Khóa chỉ là vũ khí cuối. Đây là cách viết concurrency vừa **đúng** vừa **nhanh**.

### 2. Kiến trúc tổng thể

```
   Nhiều Client (thread)                AuctionEngine (1 process)
   ─────────────────────                ───────────────────────────────────────────
   placeBid(auctionId, bid) ──┐
   placeBid(...)            ──┼──►  ConcurrentHashMap<Long, Auction>   (registry phiên)
   placeBid(...)            ──┘            │
                                          ▼  mỗi Auction giữ:
                                   ┌─────────────────────────────────────┐
                                   │ volatile boolean closed   (Day 21)  │  ← cờ đóng
                                   │ AtomicReference<Bid> highest (Day19)│  ← CAS giá cao nhất
                                   │ LongAdder bidCount        (Day 24)  │  ← đếm
                                   └─────────────────────────────────────┘
   Scheduler (1 thread) ──► sau N ms ──► auction.close()  (set volatile, mọi thread thấy ngay)
```

Hai biến thể kiến trúc, ta sẽ làm **cả hai** để so sánh:

- **(A) Lock-free trực tiếp:** `placeBid` cập nhật `AtomicReference<Bid>` bằng CAS ngay trên thread gọi. Đơn giản, độ trễ thấp, hợp khi xử lý bid nhẹ.
- **(B) Queue + worker:** `placeBid` đẩy bid vào `BlockingQueue`, worker thread xử lý tuần tự/song song. Hợp khi xử lý bid nặng (validate, ghi log, antifraud), có back-pressure.

### 3. Vì sao `Bid` phải IMMUTABLE

`Bid` được chia sẻ giữa nhiều thread (nằm trong `AtomicReference`, đi qua queue). Nếu khả biến, một thread có thể sửa `amount` của bid mà thread khác đang đọc → bất nhất. **Immutable** (record, mọi field `final`) → tạo xong không đổi → **chia sẻ tham chiếu an toàn tuyệt đối**, không cần đồng bộ để đọc.

```java
public record Bid(String bidder, long amount, long timestamp) {}   // bất biến, an toàn chia sẻ
```

> 💡 Đây là nguyên tắc số 1 trong thứ tự ưu tiên thiết kế (Day 19): **không chia sẻ → bất biến → atomic → khóa**. Engine này dùng "bất biến + atomic" làm chủ đạo, gần như không khóa.

### 4. Trái tim: cập nhật giá cao nhất bằng CAS (chống lost update)

Vấn đề kinh điển (Day 19): hai thread cùng thấy `highest = 100`, cùng vượt qua check với 150 & 200; nếu 200 ghi trước, 150 ghi sau → **highest tụt về 150** → mất bid cao nhất. Giải bằng **vòng lặp CAS**:

```java
boolean placeBid(Bid newBid) {
    Bid current;
    do {
        current = highest.get();                       // đọc snapshot
        if (newBid.amount() <= current.amount())
            return false;                              // không cao hơn → từ chối
    } while (!highest.compareAndSet(current, newBid)); // ai chen ngang → CAS fail → lặp lại
    return true;
}
```

`compareAndSet(current, newBid)` chỉ thành công nếu `highest` **vẫn đúng bằng** `current` ta đã đọc. Nếu thread khác đã nâng giá, CAS fail → lặp lại với giá trị mới nhất. **Giá cao nhất luôn thắng**, không ghi đè ngược, dù 1000 thread bắn cùng lúc.

### 5. Đóng phiên đúng hạn bằng `volatile` (chống visibility)

Cờ `closed` được **một thread (scheduler)** set, **mọi thread bid** phải thấy ngay → `volatile` (Day 21). Thiếu nó → một số thread đọc cờ cũ → nhận bid sau giờ đóng (bug nghiệp vụ).

```java
private volatile boolean closed = false;
public void close() { closed = true; }                 // ghi volatile → flush, mọi thread thấy
// trong placeBid: if (closed) return Result.REJECTED_CLOSED;  // đọc volatile → tươi
```

> ⚠️ **Race "đóng ngay lúc đặt giá":** kiểm tra `closed` rồi mới CAS — giữa hai bước, phiên có thể vừa đóng. Cần kiểm tra `closed` **một lần nữa sau khi CAS thành công**, hoặc chấp nhận ngữ nghĩa "bid lọt vào đúng micro-giây đóng vẫn tính". Engine bên dưới kiểm tra hai lần để chặt chẽ. Đây chính là **check-then-act** (Day 19) áp lên cờ đóng — phải xử lý có chủ đích.

### 6. Mô phỏng tải bằng `ExecutorService` (kiểm thử đúng)

Concurrency bug **không tái hiện ổn định** (Day 19). Để **chứng minh** engine đúng, ta phải ép tải tối đa:
- Nhiều thread (vượt số core) cùng bắn bid.
- Dùng **"cò súng"** (`CountDownLatch`) cho mọi thread **bắn cùng lúc** → tối đa hóa tranh chấp.
- Chạy **nhiều lần** và kiểm tra bất biến mỗi lần.
- Kiểm tra: `highest = max(mọi bid hợp lệ)`, tổng accepted + rejected = tổng gửi, không exception.

### 7. Tránh deadlock (Day 20) trong engine

Engine này **chủ động lock-free** nên rủi ro deadlock thấp. Nguyên tắc giữ vững:
- **Không khóa lồng nhau** (không có cặp lock lấy ngược thứ tự).
- Nếu buộc khóa nhiều phiên (ví dụ chuyển cọc giữa 2 tài khoản — Day 20), áp **lock ordering theo id**.
- Worker **không submit task vào chính pool rồi `get()` đồng bộ** (self-deadlock — Day 22).
- I/O/việc nặng làm **ngoài** vùng khóa.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là phần "tương phản triết lý" rõ nhất của cả phase. Cùng bài toán đấu giá, hai thế giới giải hoàn toàn khác:

```
Laravel (DB-centric, shared-nothing)        Java AuctionEngine (in-memory, 1 process)
──────────────────────────────────────────────────────────────────────────────────────
Mỗi bid = 1 HTTP request, process riêng     Mỗi bid = 1 lời gọi method, thread chung Heap
Trạng thái phiên nằm trong ROW của DB        Trạng thái nằm trong object RAM (AtomicReference)
Chống lost update = DB transaction +         Chống lost update = AtomicReference.compareAndSet
   lockForUpdate (pessimistic) HOẶC             (CAS lock-free, in-memory)
   cột version (optimistic)
Đóng phiên = cột status='CLOSED' trong DB    Đóng phiên = volatile boolean closed
Đếm bid = DB::increment / Redis::incr        Đếm bid = LongAdder
Bền qua restart (DB lưu)                     MẤT khi process chết (chỉ trong RAM)
Scale = thêm web server (stateless)          Scale 1 JVM dễ; nhiều JVM thì state phải ra ngoài
```

| Khía cạnh | Laravel | Java AuctionEngine |
|---|---|---|
| Nơi giữ trạng thái | DB/Redis (ngoài process) | RAM (trong process) |
| Chống race | `DB::transaction` + `lockForUpdate` / `@Version` | CAS (`compareAndSet`) / immutable |
| Đóng phiên | row `status` | `volatile` flag |
| Tốc độ một bid | round-trip DB (~ms) | thao tác RAM (~ns–µs) |
| Độ bền | Cao (lưu đĩa) | Thấp (mất khi crash) |
| Phù hợp khi | Hệ thống chuẩn, cần bền & scale ngang | Throughput cực cao, latency cực thấp, chấp nhận in-memory (có thể kèm event sourcing/WAL để bền) |

> 🧩 Insight chuyển ngữ then chốt: bạn đã giải bài này trong Laravel bằng **DB lock/transaction**. Java cho phép kéo toàn bộ "cuộc chiến" vào RAM, nơi `compareAndSet` thay `lockForUpdate`, `volatile` thay cột `status`, `LongAdder` thay `Redis::incr`. Ưu điểm: **nhanh hơn nhiều bậc** (RAM vs DB). Nhược điểm: **mất khi crash** và **khó scale ngang** (state in-memory không chia sẻ giữa nhiều JVM). Đó là lý do sàn giao dịch/đấu giá real-time tốc độ cao (như LMAX) dùng engine in-memory **một thread** + event log để bền — chính tư duy này.

> ⚠️ Khi nào KHÔNG dùng in-memory engine: nếu mất một bid là mất tiền thật và bạn không có cơ chế bền (event log/WAL/replication), thì phải dựa DB transaction như Laravel. In-memory engine tỏa sáng khi cần **throughput/latency cực hạn** và có chiến lược bền riêng.

---

## 💻 Thực hành code — `AuctionEngine` hoàn chỉnh

### Bước 1 — Model bất biến

```java
// Bid IMMUTABLE: an toàn chia sẻ giữa nhiều thread, không cần đồng bộ khi đọc
public record Bid(String bidder, long amount, long timestamp) {
    public Bid {
        if (amount <= 0) throw new IllegalArgumentException("amount phải > 0");
        if (bidder == null || bidder.isBlank())
            throw new IllegalArgumentException("bidder không được rỗng");
    }
    public static Bid of(String bidder, long amount) {
        return new Bid(bidder, amount, System.nanoTime());
    }
}
```

```java
// Kết quả đặt giá — enum rõ ràng, dễ test & log
public enum BidResult {
    ACCEPTED,          // bid mới trở thành giá cao nhất
    REJECTED_LOW,      // không cao hơn giá hiện tại
    REJECTED_CLOSED    // phiên đã đóng
}
```

### Bước 2 — Một phiên đấu giá thread-safe (`Auction`)

```java
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public class Auction {
    private final long id;
    private final long startMillis = System.currentTimeMillis();

    // Cờ đóng: 1 thread set (scheduler), N thread đọc → volatile (Day 21)
    private volatile boolean closed = false;

    // Giá cao nhất: read-modify-write có điều kiện → AtomicReference + CAS (Day 19)
    private final AtomicReference<Bid> highest;

    // Đếm bid hợp lệ — ghi rất nhiều → LongAdder (Day 24)
    private final LongAdder acceptedCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    public Auction(long id, long startingPrice) {
        this.id = id;
        // bid "khởi điểm" — bidder hệ thống, là sàn giá tối thiểu
        this.highest = new AtomicReference<>(new Bid("SYSTEM", startingPrice, System.nanoTime()));
    }

    /**
     * Đặt giá — LOCK-FREE, thread-safe, không bao giờ mất bid cao nhất.
     * Trả về kết quả rõ ràng để caller xử lý.
     */
    public BidResult placeBid(Bid newBid) {
        if (closed) {                              // đọc cờ volatile → tươi
            rejectedCount.increment();
            return BidResult.REJECTED_CLOSED;
        }
        Bid current;
        do {
            current = highest.get();               // snapshot giá cao nhất hiện tại
            if (newBid.amount() <= current.amount()) {
                rejectedCount.increment();
                return BidResult.REJECTED_LOW;     // không cao hơn → từ chối
            }
            // CAS fail = ai đó vừa nâng giá → lặp lại với giá trị mới nhất
        } while (!highest.compareAndSet(current, newBid));

        // CAS thành công, nhưng phiên có thể vừa ĐÓNG giữa lúc CAS (check-then-act trên cờ)
        if (closed) {
            // ngữ nghĩa chặt: nếu vừa đóng, vẫn coi như lọt phút chót — tùy nghiệp vụ.
            // Ở đây ta CHẤP NHẬN bid này (đã trở thành highest hợp lệ trước thời điểm close thực sự).
            // Nếu muốn từ chối tuyệt đối, cần đồng bộ close() và placeBid() bằng StampedLock.
        }
        acceptedCount.increment();
        return BidResult.ACCEPTED;
    }

    /** Đóng phiên — gọi bởi scheduler. Ghi volatile → mọi thread bid thấy ngay. */
    public void close() { this.closed = true; }

    public long id()              { return id; }
    public boolean isClosed()     { return closed; }
    public Bid highest()          { return highest.get(); }
    public long acceptedCount()   { return acceptedCount.sum(); }
    public long rejectedCount()   { return rejectedCount.sum(); }
}
```

### Bước 3 — `AuctionEngine`: quản nhiều phiên + scheduler đóng tự động

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class AuctionEngine implements AutoCloseable {
    // Registry nhiều phiên — map an toàn đa luồng (Day 24)
    private final ConcurrentHashMap<Long, Auction> auctions = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();      // sinh id phiên (Day 19)

    // 1 thread hẹn giờ để đóng phiên đúng hạn
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "auction-scheduler");
                t.setDaemon(true);
                return t;
            });

    /** Tạo phiên mới, tự đóng sau durationMillis. */
    public Auction createAuction(long startingPrice, long durationMillis) {
        long id = idSeq.incrementAndGet();
        Auction auction = new Auction(id, startingPrice);
        // computeIfAbsent đảm bảo không ghi đè (Day 19/24); ở đây id duy nhất nên put cũng được
        auctions.put(id, auction);
        scheduler.schedule(auction::close, durationMillis, TimeUnit.MILLISECONDS);  // đóng đúng hạn
        return auction;
    }

    /** Đặt giá vào một phiên theo id. */
    public BidResult placeBid(long auctionId, Bid bid) {
        Auction auction = auctions.get(auctionId);
        if (auction == null) return BidResult.REJECTED_CLOSED;   // không có phiên
        return auction.placeBid(bid);
    }

    public Auction get(long auctionId)   { return auctions.get(auctionId); }

    @Override
    public void close() {
        scheduler.shutdownNow();          // dừng hẹn giờ khi tắt engine
    }
}
```

### Bước 4 — (Biến thể B) Queue + worker cho xử lý bid nặng

```java
import java.util.concurrent.*;

/** Bọc engine bằng một hàng đợi + pool worker — dùng khi xử lý bid TỐN KÉM. */
public class QueuedAuctionEngine implements AutoCloseable {
    public record IncomingBid(long auctionId, Bid bid) {}
    private static final IncomingBid POISON = new IncomingBid(-1, null);

    private final AuctionEngine engine = new AuctionEngine();
    // Bounded queue → back-pressure khi bão bid (Day 22/24)
    private final BlockingQueue<IncomingBid> queue = new ArrayBlockingQueue<>(10_000);
    private final ExecutorService workers;
    private final int workerCount;

    public QueuedAuctionEngine(int workerCount) {
        this.workerCount = workerCount;
        this.workers = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "bid-worker");
            return t;
        });
        for (int i = 0; i < workerCount; i++) workers.submit(this::consumeLoop);
    }

    public Auction createAuction(long start, long durationMillis) {
        return engine.createAuction(start, durationMillis);
    }

    /** Nhận bid: đẩy vào queue (CHẶN nếu đầy → producer tự chậm lại). */
    public void submitBid(long auctionId, Bid bid) throws InterruptedException {
        queue.put(new IncomingBid(auctionId, bid));
    }

    private void consumeLoop() {
        try {
            while (true) {
                IncomingBid in = queue.take();           // CHẶN nếu rỗng
                if (in == POISON) break;
                // (chỗ này có thể chèn validate nặng, antifraud, ghi log...)
                engine.placeBid(in.auctionId(), in.bid());  // cập nhật lock-free
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public Auction get(long id) { return engine.get(id); }

    @Override
    public void close() throws InterruptedException {
        for (int i = 0; i < workerCount; i++) queue.put(POISON);   // báo worker dừng
        workers.shutdown();
        workers.awaitTermination(30, TimeUnit.SECONDS);
        engine.close();
    }
}
```

### Bước 5 — (Tùy chọn) `CompletableFuture` cho thao tác bất đồng bộ

```java
import java.util.concurrent.*;

/** Ví dụ wrap placeBid thành async cho API non-blocking (Day 23). */
public class AsyncBidFacade {
    private final AuctionEngine engine;
    private final Executor pool;
    public AsyncBidFacade(AuctionEngine engine, Executor pool) {
        this.engine = engine; this.pool = pool;
    }
    public CompletableFuture<BidResult> placeBidAsync(long auctionId, Bid bid) {
        return CompletableFuture.supplyAsync(() -> engine.placeBid(auctionId, bid), pool)
                .orTimeout(1, TimeUnit.SECONDS)
                .exceptionally(ex -> BidResult.REJECTED_CLOSED);   // fallback an toàn
    }
}
```

---

## 💻 Test đa luồng — chứng minh tính đúng đắn

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AuctionEngineStressTest {

    public static void main(String[] args) throws Exception {
        for (int run = 1; run <= 5; run++) {            // chạy NHIỀU LẦN (race không ổn định)
            runOnce(run);
        }
    }

    static void runOnce(int run) throws Exception {
        try (AuctionEngine engine = new AuctionEngine()) {
            // Phiên mở đủ lâu để mọi bid được xét trong test này
            Auction auction = engine.createAuction(0, 60_000);
            long auctionId = auction.id();

            final int THREADS = 200;                    // 200 người đặt giá đồng thời
            final int BIDS_PER_THREAD = 500;
            final int TOTAL = THREADS * BIDS_PER_THREAD; // 100_000 bid

            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            long expectedMax = (long) TOTAL;            // giá lớn nhất sẽ được bắn ra

            try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
                CountDownLatch ready = new CountDownLatch(THREADS);
                CountDownLatch go = new CountDownLatch(1);   // "cò súng" bắn cùng lúc

                // Mỗi thread bắn các giá DUY NHẤT để biết trước max kỳ vọng
                for (int t = 0; t < THREADS; t++) {
                    final int threadIdx = t;
                    pool.submit(() -> {
                        ready.countDown();
                        try { go.await(); } catch (InterruptedException ignored) {}
                        for (int i = 0; i < BIDS_PER_THREAD; i++) {
                            long amount = (long) threadIdx * BIDS_PER_THREAD + i + 1;  // 1..TOTAL, duy nhất
                            BidResult r = engine.placeBid(auctionId, Bid.of("u" + amount, amount));
                            if (r == BidResult.ACCEPTED) accepted.incrementAndGet();
                            else rejected.incrementAndGet();
                        }
                    });
                }
                ready.await();
                go.countDown();                          // BẮN! tối đa hóa tranh chấp
            }

            // ===== KIỂM TRA CÁC BẤT BIẾN =====
            Bid finalHighest = auction.highest();
            boolean okMax     = finalHighest.amount() == expectedMax;                 // giá cao nhất đúng
            boolean okNoLoss  = (accepted.get() + rejected.get()) == TOTAL;           // không mất bid
            boolean okCounter = auction.acceptedCount() + auction.rejectedCount() == TOTAL; // đếm khớp

            System.out.printf(
                "Run %d | highest=%d (kỳ vọng %d) %s | accepted=%d rejected=%d tổng=%d %s | counters %s%n",
                run, finalHighest.amount(), expectedMax, okMax ? "✅" : "❌ LOST UPDATE",
                accepted.get(), rejected.get(), accepted.get() + rejected.get(),
                okNoLoss ? "✅" : "❌ MẤT BID",
                okCounter ? "✅" : "❌ ĐẾM SAI");
        }
    }
}
```

**Kết quả mong đợi (mọi lần chạy):**
```
Run 1 | highest=100000 (kỳ vọng 100000) ✅ | accepted=... rejected=... tổng=100000 ✅ | counters ✅
Run 2 | highest=100000 (kỳ vọng 100000) ✅ | ...
...
```
- `highest` **luôn = 100000** (giá lớn nhất) — CAS chống lost update thành công.
- `accepted + rejected = 100000` — **không bid nào biến mất**.
- Số bid `ACCEPTED` chính là số lần một bid trở thành giá cao nhất mới (xấp xỉ log-ish, biến thiên theo thứ tự đến), nhưng **tổng luôn khớp**.

### Test đóng phiên đúng hạn

```java
import java.util.concurrent.*;

public class AuctionCloseTest {
    public static void main(String[] args) throws Exception {
        try (AuctionEngine engine = new AuctionEngine()) {
            Auction auction = engine.createAuction(0, 300);   // tự đóng sau 300ms
            long id = auction.id();
            long start = System.currentTimeMillis();

            try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
                for (int t = 0; t < 8; t++) {
                    pool.submit(() -> {
                        long amount = 1;
                        // bắn liên tục trong 1 giây — sau 300ms phiên phải đóng
                        while (System.currentTimeMillis() - start < 1000) {
                            BidResult r = engine.placeBid(id, Bid.of("u", amount++));
                            if (r == BidResult.REJECTED_CLOSED) break;   // thấy cờ đóng → ngừng
                        }
                    });
                }
            }
            System.out.println("Phiên đóng? " + auction.isClosed());      // true
            System.out.println("Giá cao nhất chốt = " + auction.highest().amount());
            // Mọi bid sau ~300ms đều bị REJECTED_CLOSED → không "lọt" sau giờ đóng (nhờ volatile)
        }
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng `synchronized`/khóa toàn cục cho `placeBid`.** Đúng nhưng nghẽn dưới tải cao (mọi bid xếp hàng). Engine này lock-free (CAS) nên scale tốt — đừng "thoái lui" về một khóa to.
- **Quên `volatile` cho cờ `closed`.** Thread bid không thấy phiên đã đóng → nhận bid sau giờ (Day 21). Bug nghiệp vụ nghiêm trọng, khó tái hiện.
- **`Bid` khả biến.** Thread khác sửa `amount` khi đang nằm trong `AtomicReference`/queue → bất nhất. Phải immutable (record, field final).
- **Side-effect trong vòng lặp CAS.** Vòng lặp `placeBid` có thể chạy lại khi CAS fail; đừng đặt log/ghi DB bên trong (sẽ trùng — Day 19). Side-effect (đếm) đặt **sau** khi CAS thành công.
- **`LinkedBlockingQueue` không giới hạn ở biến thể B.** Producer nhanh hơn worker → queue phình → OOM. Dùng `ArrayBlockingQueue` bounded để có back-pressure (Day 22/24).
- **Worker `get()` đồng bộ một task submit vào chính pool.** Self-deadlock (Day 20/22). Worker chỉ tiêu thụ queue, không tự submit-rồi-chờ.
- **Test chỉ chạy một lần rồi kết luận đúng.** Race không ổn định — phải nhiều thread + cò súng + chạy nhiều lần (Day 19).
- **Giả định scale ngang miễn phí.** Engine in-memory chỉ đúng **trong một JVM**. Nhiều instance → mỗi instance có `highest` riêng → sai. Phải đẩy state ra Redis/DB hoặc partition phiên theo instance.
- **Bỏ qua độ bền.** Process chết = mất sạch bid. Nếu nghiệp vụ cần bền, thêm event log/WAL hoặc dựa DB.

---

## 🚀 Liên hệ Spring Boot / Production

- **`AuctionEngine` là một `@Component` singleton.** Nó **giữ state khả biến** (registry phiên), nên **mọi field phải thread-safe** (`ConcurrentHashMap`, `AtomicReference`, `volatile`) — đúng những gì ta đã làm. Đây là minh họa hoàn hảo cho cảnh báo "singleton bean + state khả biến" (Day 19).
- **Tách pool theo tải.** Pool xử lý bid (CPU-light, CAS) tách khỏi pool gọi I/O (thanh toán, gửi mail) để không nghẽn lẫn nhau (Day 22).
- **Virtual threads (Java 21).** Nếu `placeBid` có chặn I/O (kiểm tra số dư qua DB), cân nhắc virtual threads cho worker để gánh fan-out lớn.
- **Độ bền & event sourcing.** Production thật ghi mỗi bid vào **append-only log** (Kafka/WAL) trước/sau khi áp vào engine in-memory → khởi động lại thì replay log để khôi phục state. Đây là kiến trúc **LMAX Disruptor**: engine in-memory một thread cực nhanh + event log để bền & audit.
- **Quan sát.** Expose metrics qua Micrometer: số bid/giây, độ trễ `placeBid`, số CAS-retry (đo tranh chấp), số phiên đang mở. Queue size/active của pool báo quá tải.
- **Đóng phiên & phát kết quả.** Khi phiên đóng, phát event "winner" (Spring `ApplicationEventPublisher` / Kafka) để các service khác (thanh toán, thông báo) xử lý — tách biệt engine khỏi side-effect.
- **Idempotency & antifraud.** Trước khi vào engine, lọc bid trùng/gian lận; engine lo phần "ai cao nhất thắng" còn validate nghiệp vụ nên ở lớp trước.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> **Đây CHÍNH LÀ milestone:** `AuctionEngine` ở trên là Mini Project hôm nay — đỉnh cao của chuỗi Day 16–24. Bạn đã ráp: Bid immutable (Day 19), CAS chống lost update (Day 19), `volatile` đóng phiên (Day 21), `ExecutorService` mô phỏng tải (Day 22), `CompletableFuture` async (Day 23), `ConcurrentHashMap`/`BlockingQueue`/`LongAdder` (Day 24), tránh deadlock (Day 20).

**Nhiệm vụ Day 25 (capstone):**
1. **Ráp & chạy** `AuctionEngine` + `AuctionEngineStressTest`. Xác nhận **5 lần liên tiếp** đều `✅` cả ba bất biến (highest đúng, không mất bid, đếm khớp).
2. **Chạy** `AuctionCloseTest`. Xác nhận sau ~300ms mọi bid đều `REJECTED_CLOSED` — không bid nào lọt sau giờ đóng.
3. **Phá thử để hiểu:** tạm bỏ `volatile` ở `closed` (thành `boolean` thường) và chạy lại close test nhiều lần → quan sát bid "lọt" sau giờ đóng. Khôi phục `volatile`.
4. **Phá thử lost update:** thay `AtomicReference` + CAS bằng `volatile Bid highest` với `if (bid > highest) highest = bid` (check-then-act) → chạy stress test → quan sát `highest < 100000` (lost update). Khôi phục CAS.
5. **Biến thể B:** chạy `QueuedAuctionEngine` với bounded queue, mô phỏng bão bid, xác nhận back-pressure (producer chậm lại) và không mất bid.
6. **Đo tranh chấp:** thêm một `LongAdder casRetries` đếm số lần CAS fail trong `placeBid` (tăng trong nhánh lặp lại) để thấy mức tranh chấp thực tế.
7. Ghi `notes/day-25.md`: liệt kê 4 bất biến nghiệp vụ và **chính xác công cụ concurrency nào** bảo vệ từng cái, kèm so sánh với cách Laravel giải (DB lock/transaction).

> 🧩 Tổng kết phase Concurrency: bạn vừa đi từ "race condition là gì" (Day 19) tới một engine thực thụ chịu tải 100.000 bid đồng thời mà vẫn đúng tuyệt đối. Tư duy cốt lõi không phải "thuộc API", mà là: **xác định shared mutable state → chọn vũ khí rẻ nhất đủ dùng (không chia sẻ → bất biến → atomic → khóa) → kiểm thử dưới tải thật.** Đây là kỹ năng phân biệt junior với senior Java.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Trong engine đấu giá, làm sao đảm bảo "giá cao nhất luôn thắng" khi nhiều người đặt giá đồng thời?**
> **Đáp:** Dùng `AtomicReference<Bid>` cho giá cao nhất và cập nhật bằng **vòng lặp CAS** (`compareAndSet`): đọc snapshot hiện tại, nếu bid mới cao hơn thì CAS; CAS chỉ thành công khi không ai chen ngang, fail thì lặp lại với giá trị mới nhất. Nhờ vậy không có lost update — bid cao hơn không bao giờ bị bid thấp hơn ghi đè, dù hàng trăm thread bắn cùng lúc.

**Q2: Vì sao `Bid` nên là immutable?**
> **Đáp:** Vì `Bid` được chia sẻ giữa nhiều thread (nằm trong `AtomicReference`, đi qua queue). Immutable (record, field final) nghĩa là tạo xong không đổi → nhiều thread đọc cùng lúc luôn an toàn, không cần đồng bộ. Đây là cách rẻ nhất để an toàn (ưu tiên cao trong "không chia sẻ → bất biến → atomic → khóa").

**Q3: Vì sao cờ đóng phiên phải `volatile`?**
> **Đáp:** Vì một thread (scheduler) set cờ đóng, còn nhiều thread bid phải thấy ngay. Không `volatile`, thay đổi có thể kẹt trong cache CPU của thread set → thread bid đọc giá trị cũ (`closed = false`) → nhận bid sau giờ đóng. `volatile` đảm bảo visibility: ghi flush về bộ nhớ chính, đọc luôn tươi.

**Q4: Vì sao không nên bọc cả `placeBid` trong `synchronized`?**
> **Đáp:** Đúng về tính an toàn nhưng tạo khóa toàn cục: mọi bid phải xếp hàng qua một khóa → nghẽn nghiêm trọng dưới tải cao (100.000 bid tuần tự hóa). Cách lock-free bằng CAS cho phép nhiều thread tiến triển song song, chỉ retry khi thực sự đụng độ → throughput cao hơn nhiều.

### Mức Senior

**Q5: Map từng bất biến nghiệp vụ của engine sang công cụ concurrency tương ứng.**
> **Đáp:** (1) *Không mất bid* → hàng đợi an toàn (`BlockingQueue`) hoặc CAS đảm bảo mọi bid được xét. (2) *Giá cao nhất luôn thắng* → `AtomicReference.compareAndSet` (chống lost update, Day 19). (3) *Đóng đúng hạn* → `volatile boolean closed` + `ScheduledExecutorService` (visibility, Day 21). (4) *Kiểm soát tài nguyên* → `ExecutorService` + bounded `BlockingQueue` (Day 22/24). (5) *Tổng hợp nhiều phiên* → `ConcurrentHashMap` (Day 24). (6) *Đếm* → `LongAdder`. (7) *Không deadlock* → lock-free, không khóa lồng (Day 20). Mỗi công cụ là vũ khí rẻ nhất đủ giải từng bất biến.

**Q6: Engine này có race "đặt giá đúng lúc đóng phiên" không? Xử lý ra sao?**
> **Đáp:** Có — đó là check-then-act trên cờ `closed`: kiểm tra `closed` rồi CAS, giữa hai bước phiên có thể vừa đóng. Tùy ngữ nghĩa: (a) chấp nhận bid "lọt phút chót" nếu CAS thành công trước thời điểm close thực sự (đơn giản, thường ổn); (b) nếu cần từ chối tuyệt đối, đồng bộ `close()` và `placeBid()` bằng một `StampedLock`/`ReadWriteLock` hoặc một `AtomicReference` trạng thái (OPEN/CLOSED) để CAS trạng thái + giá cùng nhau. Quan trọng là **chọn ngữ nghĩa có chủ đích** và test nó, thay vì để mơ hồ.

**Q7: Vì sao engine in-memory này không scale ngang ra nhiều JVM như Laravel? Giải pháp?**
> **Đáp:** Vì state (highest, closed) nằm trong RAM của **một** JVM. Nhiều instance → mỗi cái có bản `highest` riêng, không nhất quán → sai. Giải pháp: (a) **partition** — mỗi phiên đấu giá gắn cứng vào một instance (sticky routing theo auctionId), state vẫn in-memory nhưng chỉ một nơi giữ; (b) **đẩy state ra ngoài** — Redis/Hazelcast với atomic op (`Redis WATCH/MULTI` hoặc Lua script làm CAS), về đúng mô hình DB của Laravel; (c) **một engine trung tâm** + event log (kiểu LMAX) và replicate. Đánh đổi tốc độ in-memory lấy khả năng scale/bền.

**Q8: Làm sao kiểm thử engine để TIN nó đúng dưới concurrency?**
> **Đáp:** Bug race không ổn định nên: (1) nhiều thread vượt số core; (2) **cò súng** (`CountDownLatch go`) cho mọi thread bắn cùng lúc → tối đa tranh chấp; (3) chạy **nhiều lần liên tiếp**, assert bất biến mỗi lần (highest = max, accepted+rejected = total); (4) dùng giá trị bid **duy nhất** để biết trước max kỳ vọng; (5) thử **phá** (bỏ volatile / thay CAS bằng check-then-act) để xác nhận test thực sự bắt được bug; (6) công cụ chuyên dụng `jcstress` để dò interleaving; (7) đo CAS-retry để biết mức tranh chấp. "Chạy local thấy ổn" không phải bằng chứng.

**Q9: Vì sao kiến trúc "in-memory engine một thread + event log" (LMAX Disruptor) lại nhanh và đáng tin?**
> **Đáp:** Xử lý nghiệp vụ trên **một thread đơn** loại bỏ hoàn toàn khóa, contention, và cache-coherency traffic → tốc độ cực cao (LMAX đạt hàng triệu op/giây), và logic tuần tự **dễ suy luận đúng**. Tính bền & audit đến từ **event log append-only**: mọi lệnh ghi vào log trước; crash thì replay log để khôi phục state. Tách "tốc độ xử lý" (in-memory, single-thread) khỏi "độ bền" (log) là insight cốt lõi — bạn không phải đánh đổi giữa nhanh và an toàn. Đây là sự tương phản thú vị với mô hình DB-transaction của Laravel: thay vì mỗi thao tác trả giá round-trip DB, ta gom độ bền vào một dòng log tuần tự.

---

## ✅ Checklist hoàn thành

- [ ] Ráp được `AuctionEngine` hoàn chỉnh từ các mảnh Day 16–24
- [ ] Map được 4 bất biến nghiệp vụ → đúng công cụ concurrency bảo vệ từng cái
- [ ] Giải thích vòng lặp CAS chống lost update trên giá cao nhất
- [ ] Giải thích vì sao cờ đóng cần `volatile` và `Bid` cần immutable
- [ ] Chạy stress test 5 lần, mọi bất biến đều `✅`
- [ ] Chạy close test, xác nhận không bid nào lọt sau giờ đóng
- [ ] **Phá thử** (bỏ volatile / thay CAS bằng check-then-act) để thấy bug xuất hiện
- [ ] Hiểu giới hạn (mất khi crash, không scale ngang) và hướng khắc phục (event log, partition, Redis)
- [ ] So sánh tương phản với mô hình DB-lock/transaction của Laravel
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit milestone cho phase Concurrency

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — toàn bộ, đặc biệt chương 4 "Composing Objects", 5 "Building Blocks", 15 "Atomic Variables and Nonblocking Synchronization"
- Martin Thompson & LMAX team — "The LMAX Architecture" và "Disruptor" (in-memory engine + event log, kinh điển về low-latency)
- Javadoc `java.util.concurrent` — `AtomicReference`, `ConcurrentHashMap`, `BlockingQueue`, `LongAdder`, `ScheduledExecutorService`, `CompletableFuture`
- `jcstress` (OpenJDK) — stress-test interleaving để kiểm chứng engine
- Aleksey Shipilëv — bài viết về JMM & lock-free programming
- Spring Framework Docs — quản lý bean stateful an toàn, `@Async`, `ApplicationEventPublisher`
- Martin Fowler — "Event Sourcing" (chiến lược bền cho in-memory state)
