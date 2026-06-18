# Day 22 - ExecutorService

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã biết tạo `Thread` thủ công (Day 17–18), giờ học cách **quản lý thread chuyên nghiệp** bằng thread pool — nền tảng của mọi server Java.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu vì sao **không nên `new Thread()` thủ công** cho mỗi tác vụ: tạo/hủy thread **đắt**, không kiểm soát được số luồng → cạn tài nguyên.
- Nắm khái niệm **thread pool** và `ExecutorService` — "đội thợ" tái sử dụng thread, nhận task qua hàng đợi.
- Biết các factory `Executors` (`fixed`/`cached`/`single`/`scheduled`) và **cảnh báo về unbounded queue**.
- Hiểu cấu trúc nội bộ `ThreadPoolExecutor`: `corePoolSize`, `maximumPoolSize`, `workQueue`, `keepAlive`, `RejectedExecutionHandler` — và tự cấu hình pool đúng cách.
- Phân biệt `submit` vs `execute`, `Callable` + `Future`.
- Tắt pool đúng cách: `shutdown` vs `shutdownNow` + `awaitTermination`.
- Teaser **virtual threads** (Java 21) — `newVirtualThreadPerTaskExecutor`.
- Liên hệ Laravel: queue worker / Horizon là "thread pool ở mức tiến trình".

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao cần thread pool? — Vấn đề của `new Thread()` mỗi lần

Cách "ngây thơ": mỗi task tạo một thread mới.

```java
for (Bid bid : bids) {
    new Thread(() -> process(bid)).start();   // ❌ TỆ: tạo 1 thread/bid
}
```

Vì sao tệ:

| Vấn đề | Giải thích |
|---|---|
| **Tốn kém tạo/hủy** | Mỗi thread OS cần ~512KB–1MB stack, gọi system call để tạo/hủy. 10.000 bid → 10.000 lần tạo thread → chậm & ngốn RAM. |
| **Không kiểm soát số luồng** | 10.000 bid đến cùng lúc → 10.000 thread → context switch điên loạn, OS quá tải, **OutOfMemoryError: unable to create new native thread**. |
| **Không tái sử dụng** | Thread làm xong một task là chết, lãng phí công tạo ra nó. |
| **Khó quản lý** | Không có cách hủy hàng loạt, theo dõi, giới hạn, đặt tên... |

**Thread pool** giải quyết tất cả: tạo sẵn một **số thread cố định** (đội thợ), tái sử dụng chúng cho nhiều task; task xếp vào **hàng đợi** chờ tới lượt.

```
            submit(task)        ┌──────────── Thread Pool ────────────┐
  Tasks  ──────────────►  [ Work Queue ]  ──►  Worker-1 ──► chạy task
  (bid1,bid2,bid3,...)        (hàng đợi)        Worker-2 ──► chạy task
                                                Worker-3 ──► chạy task
                              thread được TÁI SỬ DỤNG, không tạo mới mỗi task
                            └─────────────────────────────────────────┘
```

> 💡 Tư duy cốt lõi: **tách "việc cần làm" (task) khỏi "ai làm" (thread)**. Bạn nộp task, pool lo phần thực thi. Số thread bị **chặn trên** (bounded) → bảo vệ hệ thống khỏi quá tải.

### 2. `Executor` → `ExecutorService` — bộ khung

Phân cấp interface:

```
Executor               : execute(Runnable)              — chỉ "chạy giùm cái này"
   └─ ExecutorService  : + submit/Callable/Future, shutdown, invokeAll/invokeAny
        └─ ScheduledExecutorService : + lập lịch (delay, định kỳ)
```

`ExecutorService` là interface bạn dùng hằng ngày. Bạn **không tạo trực tiếp** — dùng factory `Executors` hoặc `new ThreadPoolExecutor(...)`.

### 3. Các factory `Executors` — và CẢNH BÁO unbounded queue

| Factory | Tạo ra gì | Hàng đợi | Rủi ro |
|---|---|---|---|
| `newFixedThreadPool(n)` | `n` thread cố định | **`LinkedBlockingQueue` không giới hạn** | Task dồn vô hạn → **OOM** nếu producer nhanh hơn consumer |
| `newCachedThreadPool()` | Tạo thread theo nhu cầu, tái dùng, hết 60s thì hủy | `SynchronousQueue` (không chứa) | Số thread **không giới hạn** → bùng nổ thread → OOM khi tải cao |
| `newSingleThreadExecutor()` | Đúng 1 thread (chạy tuần tự) | `LinkedBlockingQueue` không giới hạn | Như fixed(1) + queue vô hạn |
| `newScheduledThreadPool(n)` | `n` thread, có lập lịch | `DelayedWorkQueue` | Lập lịch delay/định kỳ |
| `newVirtualThreadPerTaskExecutor()` | 1 **virtual thread**/task (Java 21) | — | Cho task I/O-bound số lượng cực lớn |

> ⚠️ **CẢNH BÁO QUAN TRỌNG NHẤT của ngày hôm nay:** `newFixedThreadPool` và `newSingleThreadExecutor` dùng **hàng đợi KHÔNG giới hạn** (`Integer.MAX_VALUE`). Nếu task đến nhanh hơn xử lý, hàng đợi phình mãi → **OutOfMemoryError**. `newCachedThreadPool` thì **số thread không giới hạn** → cũng OOM. Vì vậy **trong production nghiêm túc, đừng dùng các factory tiện lợi này** — hãy tự tạo `ThreadPoolExecutor` với **hàng đợi có chặn (bounded)** và **chính sách từ chối (reject policy)** rõ ràng. Đây là lời khuyên kinh điển của Brian Goetz và Google Java Style Guide.

### 4. `ThreadPoolExecutor` — trái tim, hiểu để cấu hình đúng

Mọi pool đều là một `ThreadPoolExecutor` bên dưới. Constructor đầy đủ:

```java
new ThreadPoolExecutor(
    int corePoolSize,            // số thread "thường trực" (giữ kể cả khi rảnh)
    int maximumPoolSize,         // số thread TỐI ĐA được phép tạo
    long keepAliveTime,          // thread vượt core, rảnh quá lâu thì bị hủy
    TimeUnit unit,
    BlockingQueue<Runnable> workQueue,           // hàng đợi chứa task chờ
    ThreadFactory threadFactory,                 // tạo thread (đặt tên, daemon...)
    RejectedExecutionHandler handler             // làm gì khi quá tải
);
```

**Luật điều phối task** (phải thuộc — câu hỏi phỏng vấn ưa thích):

```
Khi submit một task:
  1. Nếu số thread < corePoolSize        → TẠO thread mới chạy ngay
  2. Else nếu workQueue còn chỗ          → XẾP task vào hàng đợi
  3. Else nếu số thread < maximumPoolSize → TẠO thread (vượt core) chạy ngay
  4. Else (queue đầy & đã max thread)     → GỌI RejectedExecutionHandler
```

```
              ┌─ thread < core? ──yes──► tạo thread chạy ngay
  submit ──►  ├─ queue còn chỗ? ─yes──► xếp hàng đợi
              ├─ thread < max?  ─yes──► tạo thread vượt core
              └─ (đầy & max) ─────────► RejectedExecutionHandler  💥
```

**`RejectedExecutionHandler` — 4 chính sách built-in:**

| Policy | Hành vi khi quá tải |
|---|---|
| `AbortPolicy` (mặc định) | Ném `RejectedExecutionException` |
| `CallerRunsPolicy` | **Thread gọi `submit` tự chạy task** → tạo back-pressure, làm chậm producer (rất hữu ích!) |
| `DiscardPolicy` | Lặng lẽ bỏ task mới |
| `DiscardOldestPolicy` | Bỏ task **cũ nhất** trong queue, nhận task mới |

> 💡 `CallerRunsPolicy` là "vũ khí back-pressure": khi pool quá tải, thread submit phải tự xử lý task → nó **chậm lại tự nhiên**, không nhận thêm task mới quá nhanh → hệ thống tự điều tiết thay vì sụp đổ. Đây là lựa chọn production thông minh cho nhiều trường hợp.

### 5. `submit` vs `execute`, `Callable` + `Future`

| | `execute(Runnable)` | `submit(Runnable/Callable)` |
|---|---|---|
| Định nghĩa ở | `Executor` | `ExecutorService` |
| Trả về | `void` | `Future<?>` / `Future<T>` |
| Lấy kết quả | Không | Có (`future.get()`) |
| Exception trong task | Ném ra UncaughtExceptionHandler | **"Nuốt"** vào `Future`, chỉ lộ khi gọi `future.get()` |

```java
Runnable r = () -> System.out.println("không trả về gì");
Callable<Integer> c = () -> 2 + 2;          // Callable<T> trả về T, được phép ném checked exception

Future<Integer> f = pool.submit(c);
Integer result = f.get();                    // BLOCKING: chờ tới khi xong, trả về 4
```

> ⚠️ Bẫy kinh điển: nếu một task submit qua `submit()` ném exception mà bạn **không bao giờ gọi `future.get()`**, exception đó bị **nuốt im lặng** — bạn không thấy gì cả. Với `execute()` thì exception nổi lên UncaughtExceptionHandler (thường log ra). Vì vậy luôn xử lý kết quả/lỗi của `Future`, hoặc dùng `execute` cho task fire-and-forget có log lỗi.

`Future` hạn chế (sẽ giải quyết ở Day 23): `get()` **chặn** (blocking), không compose được nhiều future, không có callback "khi xong thì làm tiếp". `CompletableFuture` ra đời để khắc phục.

### 6. Tắt pool đúng cách — `shutdown` vs `shutdownNow`

Pool **không tự tắt** — thread của nó (non-daemon) giữ JVM sống mãi nếu bạn quên tắt. Hai cách:

| Method | Hành vi |
|---|---|
| `shutdown()` | **Lịch sự**: ngừng nhận task mới, **chạy nốt** task đang chạy + đang chờ trong queue, rồi tắt |
| `shutdownNow()` | **Quyết liệt**: cố dừng task đang chạy (gửi `interrupt`), **bỏ** task còn trong queue, trả về danh sách task chưa chạy |
| `awaitTermination(t, unit)` | **Chờ** pool tắt xong trong giới hạn thời gian, trả `boolean` (đã tắt kịp chưa) |

**Mẫu shutdown chuẩn:**

```java
pool.shutdown();                                  // ngừng nhận task mới
try {
    if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {  // chờ tối đa 30s
        pool.shutdownNow();                       // chưa xong → ép dừng
    }
} catch (InterruptedException e) {
    pool.shutdownNow();
    Thread.currentThread().interrupt();
}
```

> 💡 **Java 21:** `ExecutorService implements AutoCloseable` — dùng `try-with-resources`, khi ra khỏi block nó tự gọi `close()` (≈ `shutdown()` + chờ mọi task xong). Gọn hơn nhiều, đã thấy ở Day 19:
> ```java
> try (var pool = Executors.newFixedThreadPool(4)) {
>     pool.submit(task);
> }   // tự shutdown + chờ xong tại đây
> ```

### 7. Teaser: Virtual Threads (Java 21)

Java 21 ra mắt **virtual threads** (Project Loom) — thread **siêu nhẹ** do JVM quản lý (không ánh xạ 1-1 với thread OS). Hàng triệu virtual thread chạy được trên vài thread OS.

```java
// Mỗi task một virtual thread — rẻ tới mức không cần pool!
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 1_000_000; i++) {
        executor.submit(() -> { callRemoteApi(); return null; });  // 1 triệu task OK
    }
}
```

- Hợp với task **I/O-bound** (gọi API, query DB, đọc file) số lượng cực lớn: khi virtual thread chờ I/O, nó "nhả" thread OS cho task khác chạy.
- **Không** thay thế pool cho task **CPU-bound** (tính toán nặng) — số lõi CPU mới là giới hạn thật, vẫn nên dùng pool cố định ~ số core.

> 💡 Virtual threads sẽ được đào sâu ở phase sau. Hôm nay chỉ cần biết: với I/O-bound, `newVirtualThreadPerTaskExecutor()` thường là lựa chọn đơn giản & hiệu quả nhất trong Java 21; với CPU-bound, vẫn dùng `ThreadPoolExecutor` cố định.

---

## 🔁 Đối chiếu với Laravel/PHP

PHP không có thread trong code đời thường, nhưng tư tưởng "đội thợ xử lý hàng đợi" thì **giống hệt** — chỉ khác ở **mức tiến trình** thay vì **mức thread**.

```
Java ExecutorService (trong 1 process)   ≈   Laravel Queue + Worker (nhiều process)
──────────────────────────────────────────────────────────────────────────────────
submit(task) → workQueue                 ≈   dispatch(Job) → hàng đợi (Redis/DB/SQS)
Worker thread lấy task từ queue          ≈   queue:work process lấy Job từ hàng đợi
corePoolSize = số thread                 ≈   số worker process (Horizon: numprocs)
RejectedExecutionHandler (quá tải)        ≈   max queue size / retry / failed_jobs
```

| Khái niệm | Java `ExecutorService` | Laravel Queue / Horizon |
|---|---|---|
| Đơn vị công việc | `Runnable`/`Callable` (task) | `Job` (class implements `ShouldQueue`) |
| Người thực thi | Worker **thread** (trong 1 JVM) | Worker **process** (`php artisan queue:work`) |
| Hàng đợi | `BlockingQueue` trong RAM | Redis / database / SQS (ngoài process) |
| Số lượng thợ | `corePoolSize`/`maximumPoolSize` | Số worker process (Horizon tự scale) |
| Kết quả task | `Future`/`CompletableFuture` | Thường fire-and-forget; kết quả ghi DB/event |
| Tồn tại sau restart | **Mất** (trong RAM) | **Bền** (queue lưu ngoài → job sống qua restart) |

> 🧩 Insight chuyển ngữ: bạn đã dùng "thread pool" rồi — đó chính là **Horizon/queue worker**. Khác biệt cốt lõi: Laravel queue **bền** (job nằm trong Redis/DB, restart không mất), còn `ExecutorService` giữ task **trong RAM** (process chết là mất sạch). Vì vậy trong Java, nếu task **quan trọng không được mất**, bạn vẫn cần một queue bền bên ngoài (Kafka, RabbitMQ, DB) — `ExecutorService` chỉ hợp cho xử lý **trong-process, ngắn hạn**.

> ⚠️ Một khác biệt tư duy: trong Laravel mỗi job chạy trong process riêng (shared-nothing) → ít lo race. Trong `ExecutorService`, nhiều task chạy **song song trong cùng JVM, chung Heap** → mọi bài học race condition/deadlock/visibility (Day 19–21) **đều áp dụng** cho code chạy trong pool.

---

## 💻 Thực hành code

### (a) Fixed pool xử lý nhiều bid

```java
import java.util.concurrent.*;

public class FixedPoolDemo {
    record Bid(String bidder, long amount) {}

    static void process(Bid bid) {
        // giả lập xử lý: validate, ghi log... (ở đây chỉ sleep nhẹ)
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        System.out.printf("[%s] xử lý bid %d của %s%n",
                Thread.currentThread().getName(), bid.amount(), bid.bidder());
    }

    public static void main(String[] args) {
        // try-with-resources (Java 21): tự shutdown + chờ mọi task xong
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 1; i <= 20; i++) {
                final Bid bid = new Bid("user-" + i, i * 100L);
                pool.submit(() -> process(bid));   // 20 task chia cho 4 thread
            }
        }   // tới đây mọi bid đã xử lý xong
        System.out.println("Đã xử lý xong toàn bộ bid.");
    }
}
```

### (b) `Callable` + `Future` — lấy kết quả

```java
import java.util.*;
import java.util.concurrent.*;

public class CallableFutureDemo {
    public static void main(String[] args) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {

            // Callable<T> trả về T (khác Runnable trả void)
            Callable<Long> task = () -> {
                Thread.sleep(100);
                return ThreadLocalRandom.current().nextLong(1000);   // "tính giá đề xuất"
            };

            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(task));

            long sum = 0;
            for (Future<Long> f : futures) {
                sum += f.get();          // BLOCKING: chờ từng future xong
            }
            System.out.println("Tổng giá đề xuất = " + sum);
        }
    }
}
```

### (c) Tự tạo `ThreadPoolExecutor` đúng chuẩn production (bounded + reject policy)

```java
import java.util.concurrent.*;

public class CustomPoolDemo {
    public static void main(String[] args) throws InterruptedException {

        // Thread factory đặt TÊN thread (rất quan trọng để debug thread dump)
        ThreadFactory factory = new ThreadFactory() {
            private int n = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "bid-worker-" + (n++));
                t.setDaemon(false);
                return t;
            }
        };

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2,                                  // corePoolSize
                4,                                  // maximumPoolSize
                30, TimeUnit.SECONDS,               // keepAlive cho thread vượt core
                new ArrayBlockingQueue<>(10),       // hàng đợi CÓ CHẶN (bounded!) — chống OOM
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy()   // quá tải → caller tự chạy (back-pressure)
        );

        for (int i = 0; i < 50; i++) {
            final int id = i;
            pool.submit(() -> {
                System.out.printf("[%s] task %d%n", Thread.currentThread().getName(), id);
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            });
        }

        // Shutdown chuẩn
        pool.shutdown();
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
        System.out.println("Hoàn tất. Tổng task đã chạy = " + pool.getCompletedTaskCount());
    }
}
```

> 💡 Để ý: với `ArrayBlockingQueue(10)` + `CallerRunsPolicy`, khi 50 task ập đến mà pool + queue đầy, **thread `main` tự chạy task** → main chậm lại, ngừng submit ồ ạt → hệ thống tự điều tiết. So với `newFixedThreadPool` (queue vô hạn) thì cách này **an toàn dưới tải cao**.

### (d) Teaser virtual threads (Java 21)

```java
import java.util.concurrent.*;

public class VirtualThreadDemo {
    public static void main(String[] args) throws InterruptedException {
        try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100_000; i++) {     // 100k task I/O-bound: virtual thread "gánh" được
                vexec.submit(() -> {
                    Thread.sleep(50);               // mô phỏng I/O (gọi API/DB) — virtual thread nhả carrier
                    return null;
                });
            }
        }   // chờ 100k task xong
        System.out.println("Xong 100k task trên virtual threads.");
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng `newFixedThreadPool`/`newSingleThreadExecutor` với queue vô hạn trong production.** Task dồn → OOM. Dùng `ThreadPoolExecutor` với **bounded queue** + reject policy.
- **Dùng `newCachedThreadPool` dưới tải cao.** Số thread không giới hạn → bùng nổ thread → OOM "unable to create new native thread".
- **Quên shutdown pool.** Thread non-daemon giữ JVM sống mãi (chương trình không thoát). Luôn `shutdown()` hoặc dùng try-with-resources (Java 21).
- **Nuốt exception trong `submit`.** Task ném exception nhưng không ai gọi `future.get()` → lỗi biến mất im lặng. Luôn xử lý `Future` hoặc dùng `execute` có UncaughtExceptionHandler.
- **Không đặt tên thread.** Thread dump toàn `pool-1-thread-3` → khó chẩn đoán. Dùng `ThreadFactory` đặt tên có ý nghĩa.
- **Đặt `corePoolSize` quá lớn cho task CPU-bound.** Quá số core → context switch tốn kém, chậm hơn. Quy tắc: CPU-bound ≈ số core; I/O-bound có thể nhiều hơn (hoặc dùng virtual threads).
- **Submit task chặn (blocking) vào pool nhỏ → cạn pool.** Nếu mọi thread trong pool đều đang chờ I/O/khóa, không còn thread chạy task khác → "treo logic". Tách pool theo loại tải, hoặc dùng virtual threads cho I/O.
- **Một pool dùng chung cho cả task nhanh và task chậm.** Task chậm chiếm hết thread → task nhanh chờ dài. Phân tách pool theo SLA.
- **`shutdownNow()` rồi tưởng task dừng ngay.** Nó chỉ **gửi `interrupt`** — task phải tự kiểm tra `Thread.interrupted()` mới dừng được. Code không kiểm tra interrupt sẽ chạy tiếp.

---

## 🚀 Liên hệ Spring Boot / Production

- **`@Async` chạy trên một `Executor`.** Khi bạn đánh `@Async` lên method, Spring chạy nó trên một thread pool. **Mặc định Spring Boot cũ dùng `SimpleAsyncTaskExecutor` (tạo thread mới mỗi lần — nguy hiểm!)**, nên hãy **tự định nghĩa bean `ThreadPoolTaskExecutor`** với core/max/queue/reject rõ ràng và trỏ `@Async("myExecutor")` vào nó.
- **`ThreadPoolTaskExecutor` của Spring** bọc `ThreadPoolExecutor`, cấu hình `corePoolSize`, `maxPoolSize`, `queueCapacity`, `rejectedExecutionHandler`, `threadNamePrefix` — kiến thức hôm nay map 1-1.
- **Tomcat/web server cũng là một thread pool.** `server.tomcat.threads.max` (mặc định 200) chính là số worker thread xử lý HTTP request. Hiểu pool giúp bạn tinh chỉnh nó và đọc `/actuator/metrics/executor...`.
- **Đừng chặn thread của Tomcat lâu.** Request nặng/blocking nên đẩy sang pool riêng (`@Async`) để không cạn pool HTTP → giữ throughput.
- **Tách pool theo bounded context.** Pool gửi email, pool gọi API ngoài, pool xử lý batch... mỗi cái có cấu hình riêng, để một loại task chậm không kéo sập loại khác.
- **Virtual threads trong Spring Boot 3.2+ (Java 21).** Bật `spring.threads.virtual.enabled=true` để Tomcat/`@Async` chạy mỗi request trên một virtual thread — đơn giản hóa lớn cho service I/O-bound.
- **Giám sát.** Theo dõi `getActiveCount()`, `getQueue().size()`, `getCompletedTaskCount()`, số task bị reject (qua Micrometer). Queue phình hoặc reject tăng = dấu hiệu quá tải cần scale.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 19–21. Khi một phiên đấu giá "hot", **hàng loạt bid đến gần như đồng thời**. Ta không thể tạo một thread cho mỗi bid (sập). Thay vào đó dựng một **bid processing pool**: nhận bid, đẩy vào pool, các worker xử lý song song (validate + cập nhật giá cao nhất). Phối hợp với `AtomicReference` (Day 19) và cờ `volatile` (Day 21).

### Bước 1 — Dịch vụ xử lý bid bằng pool

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

public class BidProcessor {
    public record Bid(String bidder, long amount) {}   // bất biến

    private final AtomicReference<Bid> highest = new AtomicReference<>(new Bid("init", 0));
    private final AtomicLong accepted = new AtomicLong();   // đếm bid hợp lệ (lock-free)
    private final AtomicLong rejected = new AtomicLong();
    private volatile boolean open = true;                   // cờ phiên (Day 21)

    private final ThreadPoolExecutor pool;

    public BidProcessor() {
        // Pool production: bounded queue + caller-runs để back-pressure khi bão bid
        this.pool = new ThreadPoolExecutor(
                4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                r -> { Thread t = new Thread(r, "bid-worker"); return t; },
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** Nhận bid: nộp vào pool để xử lý bất đồng bộ. */
    public void submitBid(Bid bid) {
        if (!open) { rejected.incrementAndGet(); return; }
        pool.execute(() -> handle(bid));
    }

    /** Logic chạy trong worker thread: validate + cập nhật giá cao nhất bằng CAS. */
    private void handle(Bid bid) {
        if (!open) { rejected.incrementAndGet(); return; }
        Bid cur;
        do {
            cur = highest.get();
            if (bid.amount() <= cur.amount()) { rejected.incrementAndGet(); return; }
        } while (!highest.compareAndSet(cur, bid));   // không mất bid cao nhất (Day 19)
        accepted.incrementAndGet();
    }

    public void close() { open = false; }
    public Bid highest()    { return highest.get(); }
    public long accepted()  { return accepted.get(); }
    public long rejected()  { return rejected.get(); }

    public void shutdown() throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(30, TimeUnit.SECONDS)) pool.shutdownNow();
    }
}
```

### Bước 2 — Mô phỏng bão bid đồng thời

```java
import java.util.concurrent.*;

public class BidProcessorDemo {
    public static void main(String[] args) throws Exception {
        BidProcessor proc = new BidProcessor();
        int N = 10_000;

        // Dùng một pool RIÊNG để mô phỏng nhiều client gửi bid cùng lúc
        try (ExecutorService clients = Executors.newFixedThreadPool(20)) {
            CountDownLatch done = new CountDownLatch(N);
            for (int i = 1; i <= N; i++) {
                final long price = i;                      // giá tăng dần 1..N
                clients.submit(() -> {
                    proc.submitBid(new BidProcessor.Bid("u" + price, price));
                    done.countDown();
                });
            }
            done.await();                                  // đã nộp xong toàn bộ bid
        }
        proc.shutdown();                                   // chờ worker xử lý hết

        System.out.println("Giá cao nhất chốt = " + proc.highest());   // mong đợi amount = N
        System.out.println("Hợp lệ = " + proc.accepted() + ", Từ chối = " + proc.rejected());
        System.out.println("Tổng = " + (proc.accepted() + proc.rejected()) + " (mong đợi " + N + ")");
    }
}
```

### Bước 3 — Điểm cần quan sát

- **Giá cao nhất luôn = N** (bid lớn nhất) dù 10.000 bid xử lý song song trên pool — nhờ CAS không mất cập nhật.
- **Tổng hợp lệ + từ chối = N** — không bid nào "biến mất" (mọi task đều chạy hết).
- **Pool chặn số thread ở 8** dù có 10.000 bid → tài nguyên kiểm soát; bounded queue + `CallerRunsPolicy` chống OOM khi producer quá nhanh.

> 🧩 Liên hệ thực chiến: kiến trúc này (nhận → đẩy vào pool → worker xử lý) chính là mô hình **producer-consumer** ngay trong process. Ở Laravel bạn làm điều tương tự bằng `dispatch(new ProcessBidJob($bid))` rồi để Horizon worker xử lý. Khác biệt: bid trong Java nằm trong RAM (nhanh, mất khi crash), còn job Laravel bền trong Redis. Day 24 ta sẽ thay phần "đẩy vào pool" bằng `BlockingQueue` tường minh để hiểu sâu hơn cơ chế hàng đợi.

**Nhiệm vụ Day 22:**
1. Chạy `FixedPoolDemo` và `CallableFutureDemo`, quan sát thread được tái sử dụng (in tên thread).
2. So sánh `CustomPoolDemo` (bounded + CallerRuns) với một bản dùng `newFixedThreadPool` rồi submit 1 triệu task — quan sát rủi ro OOM/queue phình.
3. Chạy `BidProcessorDemo` nhiều lần, xác nhận giá cao nhất luôn = N và tổng = N.
4. Ghi `notes/day-22.md`: vẽ luật điều phối task của `ThreadPoolExecutor` (core → queue → max → reject) và giải thích vì sao bounded queue an toàn hơn.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Vì sao nên dùng thread pool thay vì `new Thread()` mỗi task?**
> **Đáp:** Tạo/hủy thread tốn kém (mỗi thread cần stack ~1MB + system call), và `new Thread()` mỗi task khiến số thread không kiểm soát → context switch điên loạn, OOM "unable to create native thread". Thread pool tái sử dụng một số thread cố định, xếp task vào hàng đợi → kiểm soát tài nguyên, nhanh hơn, dễ quản lý (shutdown, giám sát, đặt tên).

**Q2: `submit` khác `execute` thế nào?**
> **Đáp:** `execute(Runnable)` trả `void`, không lấy được kết quả, exception nổi lên UncaughtExceptionHandler. `submit(Runnable/Callable)` trả `Future`, lấy được kết quả qua `future.get()`, và exception bị "nuốt" vào Future (chỉ lộ khi gọi `get()`). `Callable<T>` trả về `T` và được ném checked exception, còn `Runnable` thì không.

**Q3: Phân biệt `shutdown()` và `shutdownNow()`.**
> **Đáp:** `shutdown()` lịch sự: ngừng nhận task mới, chạy nốt task đang chạy và đang chờ trong queue rồi tắt. `shutdownNow()` quyết liệt: gửi `interrupt` cho task đang chạy, bỏ task còn trong queue, trả về danh sách task chưa chạy. Thường: `shutdown()` → `awaitTermination(timeout)` → nếu chưa xong thì `shutdownNow()`.

**Q4: `newFixedThreadPool` có rủi ro gì?**
> **Đáp:** Nó dùng `LinkedBlockingQueue` **không giới hạn**. Nếu task đến nhanh hơn xử lý, hàng đợi phình vô hạn → **OutOfMemoryError**. Production nên tự tạo `ThreadPoolExecutor` với hàng đợi **bounded** + `RejectedExecutionHandler` rõ ràng.

### Mức Senior

**Q5: Mô tả luật điều phối task của `ThreadPoolExecutor` (core, max, queue, reject).**
> **Đáp:** Khi submit: (1) nếu số thread < `corePoolSize` → tạo thread mới chạy ngay; (2) ngược lại nếu `workQueue` còn chỗ → xếp vào queue; (3) ngược lại nếu số thread < `maximumPoolSize` → tạo thread vượt core chạy ngay; (4) ngược lại (queue đầy & đã max) → gọi `RejectedExecutionHandler`. Lưu ý hệ quả: với **unbounded queue**, bước (2) luôn thành công nên `maximumPoolSize` **không bao giờ** được dùng tới — pool dừng ở core. Muốn pool co giãn tới max thì phải dùng **bounded queue**.

**Q6: Kể các `RejectedExecutionHandler` và khi nào dùng `CallerRunsPolicy`.**
> **Đáp:** `AbortPolicy` (mặc định, ném exception), `CallerRunsPolicy` (thread submit tự chạy task), `DiscardPolicy` (bỏ task mới im lặng), `DiscardOldestPolicy` (bỏ task cũ nhất trong queue). `CallerRunsPolicy` hữu ích để tạo **back-pressure**: khi pool quá tải, thread submit phải tự xử lý → nó chậm lại, ngừng nhận task mới quá nhanh → hệ thống tự điều tiết thay vì sụp đổ hoặc mất task.

**Q7: Cấu hình pool cho task CPU-bound vs I/O-bound khác nhau thế nào?**
> **Đáp:** CPU-bound (tính toán nặng): số thread ≈ số core (`Runtime.getRuntime().availableProcessors()`), vì thêm thread chỉ tăng context switch chứ không tăng throughput. I/O-bound (chờ mạng/DB/disk): thread phần lớn thời gian **chờ**, nên dùng nhiều thread hơn số core (công thức Little: threads ≈ core × (1 + wait/compute)). Java 21: với I/O-bound số lượng cực lớn, **virtual threads** (`newVirtualThreadPerTaskExecutor`) thường tối ưu và đơn giản hơn. Quan trọng: **tách pool** theo loại tải để không lẫn lộn.

**Q8: Virtual threads (Java 21) khác platform thread thế nào? Khi nào dùng?**
> **Đáp:** Platform thread ánh xạ 1-1 với thread OS, nặng (~1MB stack), số lượng giới hạn. Virtual thread do JVM quản lý, siêu nhẹ, hàng triệu cái chạy trên vài thread OS; khi virtual thread chặn ở I/O, nó "nhả" carrier thread OS cho thread khác. Dùng cho task **I/O-bound số lượng lớn** (`newVirtualThreadPerTaskExecutor`). **Không** giúp ích cho CPU-bound (giới hạn vẫn là số core) — vẫn dùng pool cố định. Cảnh báo: "pinning" khi virtual thread chặn bên trong `synchronized` (Java 21) làm mất lợi ích — ưu tiên `ReentrantLock` trong code chạy trên virtual thread.

**Q9: Một service Java có pool 50 thread, dưới tải mọi request đột nhiên treo dù task không nặng. Bạn nghi gì?**
> **Đáp:** Nghi **cạn pool do task chặn**: nếu mọi thread trong pool đều đang chờ I/O/khóa/connection (ví dụ tất cả chờ DB connection từ một pool nhỏ hơn, hoặc chờ kết quả từ chính pool đó — self-deadlock), thì không còn thread chạy task mới → treo. Lấy **thread dump** xem các thread đang `WAITING/BLOCKED` ở đâu. Khắc phục: tách pool theo loại tải, đặt timeout cho I/O, đảm bảo connection pool đủ lớn, không submit task vào chính pool rồi `get()` đồng bộ trong cùng pool (deadlock kiểu Day 20), cân nhắc virtual threads cho I/O.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được vì sao thread pool tốt hơn `new Thread()` mỗi task
- [ ] Phân biệt các factory `Executors` và hiểu rủi ro unbounded queue / unbounded thread
- [ ] Thuộc luật điều phối `ThreadPoolExecutor` (core → queue → max → reject)
- [ ] Biết 4 reject policy và khi nào dùng `CallerRunsPolicy`
- [ ] Phân biệt `submit`/`execute`, dùng `Callable` + `Future`
- [ ] Shutdown pool đúng cách (`shutdown` + `awaitTermination` + `shutdownNow`) hoặc try-with-resources
- [ ] Hiểu teaser virtual threads và khi nào dùng
- [ ] Hoàn thành Mini Project: bid processing pool xử lý hàng loạt bid an toàn
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 6 "Task Execution" & chương 8 "Applying Thread Pools"
- Javadoc `java.util.concurrent` — `ExecutorService`, `ThreadPoolExecutor`, `Executors`, `Future`
- Oracle Java Tutorials — "Concurrency: Executors" và "Thread Pools"
- JEP 444 — "Virtual Threads" (Java 21) — đọc phần motivation & khi nào dùng
- Spring Framework Docs — "Task Execution and Scheduling" (`ThreadPoolTaskExecutor`, `@Async`)
- Baeldung — "A Guide to the Java ExecutorService", "ThreadPoolExecutor", "Java Virtual Threads"
