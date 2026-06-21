# Day 16 - Thread Basics

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP lần đầu chạm tới đa luồng *trong cùng một tiến trình*.

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt rõ **Process vs Thread** — nền tảng để hiểu mọi thứ về concurrency.
- Hiểu **vì sao cần đa luồng**: tận dụng nhiều core CPU (song song) và xử lý I/O concurrency (không block toàn bộ chương trình khi chờ DB/network).
- Biết **3 cách tạo task chạy trên thread**: `extends Thread`, `implements Runnable` (ưu tiên), và teaser `Callable<V>`.
- Nắm chắc **bẫy kinh điển `start()` vs `run()`** — gọi nhầm là mất hết tác dụng đa luồng.
- Hiểu **daemon vs user thread**, cách JVM quyết định khi nào thoát.
- Làm quen **Virtual Thread (Java 21, Project Loom)** ở mức teaser.
- Nhận ra điểm bản lề: Java làm song song **trong cùng tiến trình** → sinh ra vấn đề **shared mutable state** mà PHP gần như không gặp.

---

## 🧠 Lý thuyết cốt lõi

### 1. Process vs Thread — phân biệt cho chuẩn

**Process (tiến trình):** một chương trình đang chạy, được OS cấp **không gian địa chỉ (address space) riêng**. Hai process **không** thấy bộ nhớ của nhau — muốn nói chuyện phải qua IPC (pipe, socket, shared memory được cấp phép...). Tạo process **nặng**: cấp phát bộ nhớ, bảng trang, file descriptor riêng.

**Thread (luồng):** một "dòng thực thi" **bên trong** một process. Nhiều thread của cùng một process **chia sẻ chung Heap** (object, biến static, metadata class), nhưng **mỗi thread có Stack riêng** (biến cục bộ, tham số, khung gọi hàm — call stack) và **Program Counter riêng** (đang ở lệnh nào). Tạo thread **nhẹ hơn** tạo process nhiều.

```
            PROCESS (1 JVM)
 ┌───────────────────────────────────────────────┐
 │   HEAP (CHIA SẺ chung cho mọi thread)          │
 │   ┌───────────────────────────────────────┐   │
 │   │  objects, static fields, class meta   │   │   <-- nguồn gốc của shared mutable state
 │   └───────────────────────────────────────┘   │
 │                                               │
 │   Thread-1        Thread-2        Thread-3     │
 │   ┌───────┐       ┌───────┐       ┌───────┐    │
 │   │ Stack │       │ Stack │       │ Stack │    │   <-- MỖI thread một Stack RIÊNG
 │   │  PC   │       │  PC   │       │  PC   │    │
 │   └───────┘       └───────┘       └───────┘    │
 └───────────────────────────────────────────────┘
```

| Tiêu chí | Process | Thread |
|---|---|---|
| Không gian địa chỉ | Riêng biệt | Chia sẻ (cùng Heap) |
| Stack | Riêng | Riêng (mỗi thread một stack) |
| Chi phí tạo | Nặng | Nhẹ |
| Giao tiếp | Qua IPC (chậm, có ranh giới) | Qua bộ nhớ chung (nhanh, nhưng nguy hiểm) |
| Crash | Một process chết không kéo process khác | Một thread làm hỏng state chung có thể kéo cả app |

> 💡 Điểm mấu chốt: **Stack riêng → biến cục bộ (local variable) luôn an toàn giữa các thread.** **Heap chung → object dùng chung mới là chỗ phát sinh race condition.** Hãy khắc cốt câu này, nó là kim chỉ nam cho cả tuần Concurrency.

### 2. Vì sao cần đa luồng?

Có **hai động cơ hoàn toàn khác nhau**, đừng nhầm:

1. **Song song tính toán (CPU-bound parallelism):** máy hiện đại có nhiều core. Nếu chỉ chạy 1 thread, bạn dùng đúng 1 core, các core còn lại "ngồi chơi". Chia việc nặng (xử lý ảnh, tính toán, sort lượng lớn) ra nhiều thread → chạy **đồng thời thật sự** trên nhiều core → nhanh hơn.

2. **I/O concurrency (chờ việc, không phải tính việc):** khi gọi DB, gọi HTTP API, đọc file... thread phần lớn thời gian **ngồi chờ**. Nếu single-thread, cả chương trình đứng im trong lúc chờ. Có nhiều thread → trong khi thread A chờ DB, thread B phục vụ request khác. Đây mới là tình huống phổ biến nhất trong web backend.

```
SINGLE-THREAD (chờ tuần tự)            MULTI-THREAD (chờ chồng lấn)
T1: [--gọi DB chờ 100ms--][xử lý]      T1: [--chờ DB--][xử lý]
T2:                       [--gọi DB--] T2: [--chờ DB--][xử lý]   <-- chờ song song
=> tổng ~ 200ms+                       => tổng ~ 100ms+
```

> ⚠️ Phân biệt **concurrency** (xử lý nhiều việc *trong cùng khoảng thời gian*, có thể luân phiên trên 1 core) và **parallelism** (chạy *cùng lúc thật* trên nhiều core). Đa luồng giúp đạt cả hai, tùy bài toán.

### 3. Tạo thread — 3 cách

**Cách 1 — `extends Thread` (ít dùng):** kế thừa `Thread`, override `run()`.

```java
class DownloadThread extends Thread {
    @Override
    public void run() {
        System.out.println("Đang tải... trên " + Thread.currentThread().getName());
    }
}
// dùng: new DownloadThread().start();
```

Nhược điểm: Java **đơn kế thừa** — đã `extends Thread` thì không kế thừa class khác được nữa; lại còn **trộn lẫn "việc cần làm" với "cơ chế chạy"**.

**Cách 2 — `implements Runnable` (ƯU TIÊN):** tách **task** (việc gì) khỏi **cơ chế thread** (chạy thế nào). `Runnable` là interface một phương thức `run()` → dùng được **lambda**, **tái sử dụng với thread pool**.

```java
Runnable task = () -> System.out.println("Chạy trên " + Thread.currentThread().getName());
Thread t = new Thread(task);
t.start();
```

**Cách 3 — `Callable<V>` (teaser, học sâu ở các ngày sau):** giống `Runnable` nhưng `call()` **trả về kết quả** và **ném checked exception được**. Không truyền thẳng vào `Thread`, mà dùng với `ExecutorService` để nhận `Future<V>`.

```java
Callable<Integer> job = () -> 40 + 2;          // trả về 42
// ExecutorService es = Executors.newFixedThreadPool(2);
// Future<Integer> f = es.submit(job);
// Integer ketQua = f.get();                    // -> 42 (sẽ học ở Day về ExecutorService)
```

| Tiêu chí | `Thread` (extends) | `Runnable` (implements) | `Callable<V>` |
|---|---|---|---|
| Trả về kết quả | Không | Không | **Có** (`V`) |
| Ném checked exception | Không | Không | **Có** |
| Dùng lambda | Không | **Có** | **Có** |
| Dùng với thread pool | Gượng ép | **Tự nhiên** | **Tự nhiên** |
| Khuyến nghị | Tránh | **Ưu tiên** | Khi cần kết quả |

> 💡 Quy tắc: **luôn nghĩ theo task (Runnable/Callable), để framework lo phần thread.** Tự `new Thread()` chỉ nên dùng để học; production hầu như luôn dùng thread pool (ExecutorService) hoặc Virtual Thread.

### 4. Thread `main`, vòng đời và `start()` vs `run()`

Khi JVM khởi động, nó tạo sẵn một thread tên **`main`** để chạy phương thức `main()` của bạn. Mọi thread khác đều do bạn (hoặc framework) sinh ra từ đó.

**BẪY KINH ĐIỂN — `start()` vs `run()`:**

```
t.start()  ──► JVM tạo MỘT THREAD MỚI ở tầng OS ──► thread đó tự gọi run()
              => code chạy SONG SONG với thread hiện tại.

t.run()    ──► chỉ là gọi một method bình thường ──► chạy NGAY trên thread HIỆN TẠI
              => KHÔNG có thread mới nào cả, chạy tuần tự.
```

Gọi `t.run()` trực tiếp **biên dịch và chạy không lỗi**, nên rất dễ lọt — nhưng bạn mất sạch tác dụng đa luồng mà không hề có cảnh báo. Một thread chỉ `start()` được **đúng một lần**; gọi lần hai ném `IllegalThreadStateException`.

Vòng đời thread (sẽ đào sâu hơn ở ngày về `wait/notify`):

```
NEW ──start()──► RUNNABLE ──(scheduler chọn)──► RUNNING
                    ▲                              │
                    │                              ├──► WAITING / TIMED_WAITING (sleep, join, wait)
                    └──────────────────────────────┘
                                                   └──► TERMINATED (run() kết thúc)
```

### 5. Daemon thread vs User thread

JVM phân loại thread thành hai:

- **User thread (mặc định):** thread "công việc chính". **JVM chỉ thoát khi MỌI user thread đã kết thúc.**
- **Daemon thread:** thread "phục vụ nền" (ví dụ Garbage Collector). JVM **không chờ** daemon — khi user thread cuối cùng kết thúc, JVM **giết thẳng** mọi daemon đang chạy rồi thoát.

```java
Thread bg = new Thread(() -> { while (true) { /* việc nền */ } });
bg.setDaemon(true);   // PHẢI gọi TRƯỚC start()
bg.start();
```

> ⚠️ `setDaemon(true)` **bắt buộc gọi trước `start()`**. Gọi sau khi thread đã start sẽ ném `IllegalThreadStateException`. Và vì daemon bị giết đột ngột, **đừng** đặt logic phải hoàn tất (ghi file, đóng transaction, flush log) vào daemon — nó có thể bị cắt giữa chừng.

### 6. Tên thread và độ ưu tiên (priority)

- **Đặt tên:** `t.setName("bidder-1")` / `t.getName()`. Tên cực kỳ hữu ích khi đọc **log** và **thread dump** lúc debug production — luôn đặt tên có ý nghĩa.
- **Lấy thread hiện tại:** `Thread.currentThread()`.
- **Độ ưu tiên:** `t.setPriority(n)` với `n` từ `1` (`MIN_PRIORITY`) đến `10` (`MAX_PRIORITY`), mặc định `5` (`NORM_PRIORITY`).

> ⚠️ Priority chỉ là **gợi ý cho OS scheduler**, **không đảm bảo** thread ưu tiên cao chạy trước. Hành vi khác nhau tùy OS. **Đừng** dựa vào priority để đảm bảo tính đúng đắn của chương trình — đó là sai lầm phổ biến.

### 7. Teaser — Virtual Thread (Java 21, Project Loom)

Thread truyền thống (gọi là **platform thread**) ánh xạ 1-1 tới thread của OS → **nặng** (mỗi cái tốn ~1MB stack), không thể tạo cả triệu. Java 21 ra mắt **Virtual Thread**: thread **siêu nhẹ** do JVM quản lý, có thể tạo **hàng triệu**, rất hợp với tải **I/O-bound**.

```java
// Cách 1
Thread vt = Thread.ofVirtual().start(() ->
        System.out.println("Tôi là virtual thread: " + Thread.currentThread()));

// Cách 2 (ngắn gọn)
Thread.startVirtualThread(() -> System.out.println("Virtual thread chạy đây"));
```

> 💡 Ý tưởng: khi virtual thread gặp tác vụ chờ I/O, JVM **tháo (unmount)** nó khỏi OS thread để OS thread phục vụ việc khác → đạt I/O concurrency khổng lồ mà code vẫn viết kiểu "tuần tự" dễ đọc. **Sẽ học sâu ở ngày riêng về Loom** — hôm nay chỉ cần biết nó tồn tại.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là ngày **bản lề** về tư duy. Mô hình của PHP và Java **khác nhau tận gốc**:

| Khía cạnh | PHP / Laravel | Java |
|---|---|---|
| Đơn vị xử lý 1 request | Thường **1 process/thread riêng** (php-fpm worker) | 1 **thread** trong cùng tiến trình JVM |
| Bộ nhớ giữa các request | Cô lập, "sinh ra rồi chết" mỗi request | **Chia sẻ Heap** suốt vòng đời app |
| Xử lý nền (background) | Đẩy ra **queue worker** (Horizon/Redis/database) — process tách biệt | Tạo **thread/pool ngay trong tiến trình** |
| Đa luồng trong 1 tiến trình | Hiếm (cần ext như `parallel`/`pthreads`) | **Bình thường, là cốt lõi** |
| Shared mutable state | Gần như không phải lo | **Phải lo cực kỹ** (race condition) |

**Khác biệt tư duy quan trọng nhất:**
- Ở **Laravel**, hai request chạy trên **hai php-fpm worker tách biệt**, mỗi worker bootstrap lại từ đầu, **không thấy biến của nhau**. Muốn chia sẻ state phải qua Redis/DB. Nhờ vậy bạn **hiếm khi** gặp race condition trong code PHP.
- Ở **Java/Spring Boot**, hai request thường chạy trên **hai thread của cùng một tiến trình**, **chia sẻ chung Heap**. Một Singleton bean, một biến `static`, một `HashMap` dùng chung... **đều bị nhiều thread truy cập đồng thời**. Đây là **tư duy MỚI** bạn phải tập: lúc nào nhiều thread cùng đọc/ghi một state có thể thay đổi (mutable), bạn phải kiểm soát.

> 🧩 Hệ quả: bug "request này lẫn dữ liệu sang request khác" mà Laravel gần như miễn nhiễm thì ở Java **hoàn toàn có thể xảy ra** nếu bạn để state mutable dùng chung mà không đồng bộ. Đây chính là cánh cửa dẫn vào **race condition / synchronized / atomic** ở các ngày tiếp theo (Day 18/19).

---

## 💻 Thực hành code

### Bài (a) — Tạo thread bằng Runnable + lambda, in tên thread

```java
public class ThreadBasics {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("[" + Thread.currentThread().getName() + "] bắt đầu main");

        ___ task = () -> { // Điền interface đại diện cho một công việc không trả về kết quả
            String me = Thread.currentThread().getName();
            for (int i = 1; i <= 3; i++) {
                System.out.println("[" + me + "] bước " + i);
            }
        };

        Thread t1 = ___ Thread(task, "worker-1"); // Điền từ khóa tạo đối tượng
        Thread t2 = new Thread(task, "worker-2");

        t1.___();   // Điền phương thức tạo thread mới rồi gọi run() trên đó
        t2.___();

        t1.___();    // Điền phương thức để main chờ t1 chạy xong
        t2.join();    // main chờ t2 chạy xong
        System.out.println("[" + Thread.currentThread().getName() + "] kết thúc main");
    }
}
```

> 💡 `join()` khiến thread hiện tại (`main`) **chờ** cho tới khi thread kia kết thúc. Không có `join()`, `main` có thể in "kết thúc" trước khi worker chạy xong. Thứ tự dòng in của `worker-1`/`worker-2` **không xác định** — đó là bản chất đa luồng.

### Bài (b) — So sánh `start()` vs `run()`

```java
public class StartVsRun {
    public static void main(String[] args) {
        Runnable task = () ->
            System.out.println("Task chạy trên: " + Thread.currentThread().getName());

        System.out.println("--- Dùng run() (SAI, chạy trên main) ---");
        new Thread(task, "thread-A").run();   // KHÔNG tạo thread mới -> in "main"

        System.out.println("--- Dùng start() (ĐÚNG, thread mới) ---");
        new Thread(task, "thread-B").___(); // Điền phương thức tạo thread mới -> in "thread-B"
    }
}
```

Kết quả minh họa (dòng `run()` luôn in `main`, còn `start()` in tên thread mới):

```
--- Dùng run() (SAI, chạy trên main) ---
Task chạy trên: main
--- Dùng start() (ĐÚNG, thread mới) ---
Task chạy trên: thread-B
```

### Bài (c) — Daemon thread chạy nền

```java
public class DaemonDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread heartbeat = new Thread(() -> {
            while (true) {
                System.out.println("  [daemon] tích tắc...");
                try { Thread.sleep(200); } catch (InterruptedException e) { return; }
            }
        }, "heartbeat");

        heartbeat.___(true);   // Điền phương thức set thành luồng chạy nền, PHẢI trước start()
        heartbeat.start();

        System.out.println("main làm việc 1 giây rồi thoát...");
        Thread.sleep(1000);
        System.out.println("main xong -> JVM thoát, daemon bị giết tự động");
        // Không cần dừng daemon thủ công: user thread (main) kết thúc -> JVM kết liễu daemon.
    }
}
```

> ⚠️ Thử đổi `setDaemon(true)` thành `false` (hoặc bỏ dòng đó): chương trình sẽ **không bao giờ tự thoát** vì `heartbeat` là user thread chạy `while(true)`. Đây là cách nhận ra khác biệt user vs daemon.

### Bài (d) — Teaser Virtual Thread (Java 21)

```java
import java.util.ArrayList;
import java.util.List;

public class VirtualThreadDemo {
    public static void main(String[] args) throws ___ { // Điền ngoại lệ khi bị ngắt
        Thread vt = Thread.ofVirtual().name("vt-1").___(() -> // Điền phương thức để bắt đầu luồng ảo
            System.out.println("Virtual? " + Thread.currentThread().isVirtual()
                               + " | " + Thread.currentThread()));
        vt.join();

        // Tạo 1000 virtual thread "thoải mái" — điều bất khả thi với platform thread thật
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            final int id = i;
            threads.add(Thread.startVirtualThread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                if (id % 250 == 0) System.out.println("vthread #" + id + " xong");
            }));
        }
        for (Thread t : threads) t.join();
        System.out.println("1000 virtual thread đã chạy xong nhẹ nhàng.");
    }
}
```

> ✅ **Bài tập tự giải thích:** Vì sao tạo 1.000.000 platform thread (`new Thread`) thường làm sập máy/OutOfMemory, còn 1.000.000 virtual thread thì không?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Gọi `run()` thay vì `start()`.** Bẫy số 1. Code chạy không lỗi nhưng **tuần tự trên thread hiện tại**, mất sạch đa luồng. Luôn dùng `start()`.
- **Gọi `start()` hai lần** trên cùng một `Thread` → `IllegalThreadStateException`. Thread không tái sử dụng được; muốn chạy lại phải tạo `Thread` mới (hoặc dùng pool).
- **`setDaemon(true)` sau khi đã `start()`** → `IllegalThreadStateException`. Phải set **trước** `start()`.
- **Dựa vào `setPriority` để đảm bảo thứ tự chạy.** Priority chỉ là gợi ý, OS có quyền lờ đi. Đừng dùng nó cho tính đúng đắn.
- **Tưởng thứ tự in/chạy của các thread là cố định.** Hoàn toàn không — scheduler quyết định, mỗi lần chạy có thể khác.
- **Quên `join()` rồi đọc kết quả quá sớm**, tưởng thread đã xong. Cần `join()` (hoặc `Future.get()`) để đảm bảo hoàn tất.
- **Để logic quan trọng trong daemon thread.** Daemon bị giết đột ngột khi JVM thoát → mất dữ liệu/ghi dở.
- **Truy cập biến mutable dùng chung từ nhiều thread mà không đồng bộ** → race condition. (Sẽ xử lý ở Day 18/19 — hôm nay chỉ cần *nhận diện*.)

---

## 🚀 Liên hệ Spring Boot / Production

- **Mỗi HTTP request là một thread.** Tomcat/Jetty nhúng trong Spring Boot có một **thread pool** (mặc định ~200 thread với Tomcat). Mỗi request được giao cho một thread trong pool — nghĩa là controller của bạn **chạy đa luồng** dù bạn không viết `new Thread` dòng nào.
- **Vì thế bean Singleton phải stateless (hoặc thread-safe).** Spring bean mặc định là **singleton** — một instance phục vụ mọi request/thread. Đặt field mutable vào nó là tự rước race condition. Đây là lý do controller/service Spring thường không có state đáng kể.
- **Đặt tên thread để debug.** Thread pool nên đặt tiền tố tên (`http-nio-8080-exec-*`, hay custom qua `ThreadFactory`) để đọc **thread dump** (`jstack <pid>`) và log truy vết dễ dàng khi production treo.
- **`@Async` của Spring** chạy method trên một thread khác (qua `TaskExecutor`) — chính là Runnable + thread pool được đóng gói gọn. Hiểu thread basics giúp bạn cấu hình pool đúng.
- **Virtual Thread + Spring Boot 3.2+ (Java 21):** bật `spring.threads.virtual.enabled=true` để mỗi request chạy trên một virtual thread → chịu tải I/O cao hơn nhiều với cùng tài nguyên. Nhưng phải hiểu nền tảng trước khi bật.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp mạch Auction API. Hôm nay ta cho **nhiều người đặt giá (bidder) chạy SONG SONG**, mỗi bidder là một `Runnable` đặt giá lên cùng một `Auction`. Mục tiêu kép: (1) luyện tạo nhiều thread; (2) **cố ý để lộ vấn đề shared mutable state** — làm tiền đề cho Day 18/19.

**Nhiệm vụ Day 16:**
1. Viết class `Auction` giữ giá cao nhất hiện tại (`highestBid`) và người giữ giá (`highestBidder`).
2. Mỗi bidder là một `Runnable` đặt giá nhiều lần lên `Auction`.
3. Chạy nhiều bidder **song song** bằng nhiều `Thread`, `join()` chờ tất cả, rồi in kết quả.
4. Chạy nhiều lần, **quan sát kết quả khác nhau** giữa các lần — đó là bằng chứng của race condition (chưa cần sửa).
5. Điền các chỗ trống `___` trong code thực hành ở trên.

```java
import java.util.concurrent.ThreadLocalRandom;

___ Auction { // Điền từ khóa khai báo lớp
    private long highestBid = 0;
    private String highestBidder = "(chưa có)";

    // CỐ Ý chưa đồng bộ -> để lộ race condition
    public void placeBid(String bidder, long amount) {
        if (amount > highestBid) {              // (1) đọc
            // mô phỏng "khoảng trống" giữa kiểm tra và cập nhật -> dễ lộ race
            Thread.yield();
            highestBid = amount;                // (2) ghi
            highestBidder = bidder;             // (3) ghi
        }
    }

    public long getHighestBid()       { return highestBid; }
    public String getHighestBidder()  { return highestBidder; }
}

public class AuctionConcurrencyDemo {
    public static void main(String[] args) throws InterruptedException {
        Auction auction = new Auction();
        int soBidder = 5;
        Thread[] bidders = new Thread[soBidder];

        for (int i = 0; i < soBidder; i++) {
            String ten = "bidder-" + (i + 1);
            Runnable task = () -> {
                for (int lan = 0; lan < 10; lan++) {
                    long gia = ThreadLocalRandom.current().nextLong(1, 1000);
                    auction.placeBid(ten, gia);
                }
            };
            bidders[i] = new Thread(task, ten);
        }

        for (Thread t : bidders) t.___();   // Điền phương thức để chạy SONG SONG
        for (Thread t : bidders) t.___();    // Điền phương thức để chờ tất cả xong

        System.out.println("Giá cao nhất: " + auction.getHighestBid()
                           + " bởi " + auction.getHighestBidder());
    }
}
```

> ⚠️ **Quan sát:** chạy `AuctionConcurrencyDemo` vài lần. Đôi khi `highestBidder` **không khớp** với người thực sự đặt giá cao nhất, hoặc kết quả đổi giữa các lần chạy. Lý do: `placeBid` không nguyên tử (atomic) — hai thread có thể cùng vượt qua bước (1) rồi ghi đè lẫn nhau ở (2)/(3). **Đây chính là race condition.** Ngày 18/19 ta sẽ sửa bằng `synchronized` / `AtomicLong` / lock. Hôm nay chỉ cần *thấy* nó.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Process và Thread khác nhau thế nào?**
> **Đáp:** Process là chương trình đang chạy, có không gian địa chỉ riêng, hai process không thấy bộ nhớ của nhau; tạo process nặng. Thread là dòng thực thi bên trong process; nhiều thread chia sẻ chung Heap nhưng mỗi thread có Stack và Program Counter riêng; tạo thread nhẹ hơn. Vì chia sẻ Heap nên thread mới sinh ra vấn đề shared mutable state.

**Q2: `start()` và `run()` khác nhau ra sao?**
> **Đáp:** `start()` yêu cầu JVM tạo một thread mới ở tầng OS rồi thread đó tự gọi `run()` → code chạy song song. Gọi `run()` trực tiếp chỉ là gọi method bình thường, chạy ngay trên thread hiện tại, **không** có thread mới → mất tác dụng đa luồng. Đây là bẫy kinh điển vì gọi `run()` không hề báo lỗi.

**Q3: Vì sao nên dùng `Runnable` thay vì `extends Thread`?**
> **Đáp:** `Runnable` tách "việc cần làm" (task) khỏi "cơ chế chạy" (thread); không chiếm suất đơn kế thừa của Java; dùng được lambda; và quan trọng nhất là tái sử dụng tự nhiên với thread pool/ExecutorService. `extends Thread` trộn lẫn task với cơ chế và khóa mất khả năng kế thừa class khác.

**Q4: Daemon thread là gì, khác user thread chỗ nào?**
> **Đáp:** User thread là thread công việc chính; JVM chỉ thoát khi mọi user thread kết thúc. Daemon là thread nền (như GC); JVM không chờ daemon, khi user thread cuối cùng xong thì JVM giết hết daemon rồi thoát. `setDaemon(true)` phải gọi trước `start()`. Không nên đặt logic phải hoàn tất vào daemon vì nó có thể bị cắt giữa chừng.

### Mức Senior

**Q5: `Runnable` và `Callable` khác nhau thế nào, dùng khi nào?**
> **Đáp:** `Runnable.run()` không trả về kết quả và không ném checked exception. `Callable<V>.call()` trả về `V` và có thể ném checked exception. `Runnable` truyền thẳng vào `Thread` được; `Callable` thì không — phải submit vào `ExecutorService` để nhận `Future<V>` rồi `get()` lấy kết quả (đồng thời lan truyền exception qua `ExecutionException`). Dùng `Callable` khi cần kết quả/đẩy lỗi từ tác vụ bất đồng bộ; dùng `Runnable` cho việc "chạy rồi quên".

**Q6: Thread priority có đảm bảo thứ tự thực thi không? Vì sao không nên dựa vào nó?**
> **Đáp:** Không. `setPriority` (1–10) chỉ là **gợi ý** cho OS scheduler và được ánh xạ khác nhau tùy hệ điều hành; nhiều nền tảng gần như bỏ qua. Không có gì đảm bảo thread priority cao chạy trước hay nhiều hơn. Dựa vào priority để bảo đảm tính đúng đắn là sai lầm — phải dùng cơ chế đồng bộ (lock, hàng đợi, latch) nếu cần thứ tự.

**Q7: Tại sao ở Spring Boot, Singleton bean với field mutable lại nguy hiểm, trong khi Laravel hiếm khi gặp?**
> **Đáp:** Spring bean mặc định singleton — một instance phục vụ mọi request, mà mỗi request chạy trên một thread khác trong cùng tiến trình, chia sẻ chung Heap. Nếu bean có field mutable, nhiều thread cùng đọc/ghi → race condition, dữ liệu request này lẫn sang request khác. Laravel/PHP mỗi request thường là một php-fpm worker tách biệt, không chia sẻ bộ nhớ, nên gần như miễn nhiễm. Cách xử lý ở Java: giữ bean stateless, dùng biến cục bộ, hoặc đồng bộ/atomic cho state dùng chung.

**Q8: Virtual Thread (Java 21) giải quyết hạn chế gì của platform thread?**
> **Đáp:** Platform thread ánh xạ 1-1 với OS thread, mỗi cái tốn nhiều bộ nhớ (stack ~1MB) và việc chuyển ngữ cảnh ở tầng OS tốn kém → không thể tạo hàng triệu, giới hạn số kết nối I/O đồng thời. Virtual thread do JVM quản lý, siêu nhẹ; khi gặp tác vụ chờ I/O, JVM tháo nó khỏi OS thread (carrier) để phục vụ việc khác, rồi gắn lại khi I/O xong. Nhờ vậy xử lý hàng triệu tác vụ I/O-bound đồng thời mà code vẫn viết kiểu tuần tự dễ đọc, không cần callback/reactive.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được Process vs Thread (Heap chung, Stack riêng) bằng lời của mình
- [ ] Phân biệt rõ hai động cơ đa luồng: song song CPU và I/O concurrency
- [ ] Tạo thread bằng `Runnable` + lambda, hiểu vì sao ưu tiên `Runnable`
- [ ] Tự tay tái hiện bẫy `start()` vs `run()` và giải thích được
- [ ] Hiểu daemon vs user thread, và quy tắc `setDaemon` trước `start()`
- [ ] Biết priority chỉ là gợi ý, không đảm bảo
- [ ] Chạy được teaser virtual thread Java 21
- [ ] Hoàn thành Mini Project và *quan sát* được race condition của `Auction`
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Concurrency" (Defining and Starting a Thread, Pausing Execution)
- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 1–2 (đọc lấy tư duy nền)
- Baeldung — "How to Start a Thread in Java", "Daemon Threads in Java"
- JEP 444 — Virtual Threads (đọc lướt phần Motivation để hiểu Loom)
- `Thread` Javadoc (Java 21) — đọc qua `start`, `run`, `setDaemon`, `setPriority`, `ofVirtual`
