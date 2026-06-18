# Day 20 - Deadlock

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã nắm `synchronized`/`Lock` (Day 18) và race condition (Day 19), giờ học mặt trái của khóa: **khóa quá tay → treo cứng**.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **deadlock** (bế tắc) là gì: hai (hoặc nhiều) thread **chờ nhau vĩnh viễn**, không ai nhả khóa, chương trình **đứng hình** mà không crash.
- Thuộc lòng **4 điều kiện Coffman** — cả 4 cùng đúng thì mới có deadlock; **phá vỡ một** là thoát.
- Tự tay **tạo deadlock cố ý** bằng 2 lock lấy ngược thứ tự, rồi **chẩn đoán** bằng `jstack`/thread dump và `ThreadMXBean.findDeadlockedThreads()`.
- Biết các kỹ thuật **phòng tránh**: **lock ordering** nhất quán, `tryLock` có **timeout**, thu hẹp phạm vi khóa, giảm số khóa.
- Phân biệt deadlock với hai "họ hàng": **livelock** (bận rộn mà không tiến triển) và **starvation** (đói tài nguyên).
- Liên hệ Laravel: vì sao bạn hiếm gặp deadlock in-memory, nhưng **deadlock ở tầng DB** thì rất thật (MySQL `Deadlock found when trying to get lock`).

---

## 🧠 Lý thuyết cốt lõi

### 1. Deadlock là gì? — "Ôm khóa rồi chờ nhau"

> **Deadlock** xảy ra khi một tập thread mỗi anh đang **giữ một khóa** và đồng thời **chờ một khóa khác** mà anh trong cùng tập đang giữ — tạo thành **vòng chờ khép kín**. Không ai chịu nhả, nên tất cả **chờ mãi mãi**.

Hình ảnh kinh điển: hai người đi qua một cây cầu hẹp từ hai đầu, gặp nhau ở giữa. A nói "anh lùi đi tôi mới qua được", B cũng nói y hệt. Cả hai đứng yên — **vĩnh viễn**.

```
   Thread A  ──giữ──►  Lock 1                Thread B  ──giữ──►  Lock 2
       │                                          │
       └──── đang chờ ────► Lock 2 (B giữ)        └──── đang chờ ────► Lock 1 (A giữ)

                    ┌──────────────────────────────────┐
                    │   A chờ B,  B chờ A  →  VÒNG CHỜ  │  →  💀 DEADLOCK
                    └──────────────────────────────────┘
```

> ⚠️ Deadlock **không ném exception**, **không crash**, **không tự thoát**. App vẫn "sống" nhưng những thread liên quan đứng cứng. Trong web server, vài request bị treo, thread pool dần cạn, rồi **cả service tê liệt** dù CPU gần 0%. Đây là kiểu sự cố production khó chịu nhất vì nó "im lặng".

### 2. Bốn điều kiện Coffman — kim chỉ nam

Deadlock chỉ xảy ra khi **cả 4 điều kiện sau cùng đúng** (Coffman conditions, 1971). Đây là khung tư duy bạn **phải thuộc** — vì phòng tránh = **phá vỡ ít nhất một điều kiện**.

| # | Điều kiện | Giải thích | Phá vỡ bằng cách... |
|---|---|---|---|
| 1 | **Mutual Exclusion** (loại trừ lẫn nhau) | Tài nguyên (khóa) tại một thời điểm chỉ **một** thread giữ được | Dùng tài nguyên **chia sẻ được** / lock-free (atomic, immutable) — không phải lúc nào cũng làm được |
| 2 | **Hold and Wait** (giữ và chờ) | Thread **đang giữ** một khóa lại **đi xin** khóa khác | Xin **tất cả khóa cùng lúc** (all-or-nothing), hoặc nhả hết trước khi xin tiếp |
| 3 | **No Preemption** (không tước đoạt) | Không thể **giật** khóa khỏi tay thread đang giữ; nó phải tự nhả | Cho phép "bỏ cuộc": `tryLock` có **timeout** → nhả những gì đang giữ rồi thử lại |
| 4 | **Circular Wait** (chờ vòng tròn) | Có một **chuỗi vòng** T1→T2→...→Tn→T1, mỗi anh chờ khóa anh kế giữ | **Lock ordering**: ép mọi thread lấy khóa theo **một thứ tự toàn cục** nhất định → không thể tạo vòng |

> 💡 Trong thực tế, đòn dễ nhất và mạnh nhất là **phá Circular Wait bằng lock ordering**: nếu mọi thread luôn lấy khóa theo cùng một thứ tự (ví dụ luôn lấy account có `id` nhỏ trước), thì **về mặt toán học không thể** hình thành vòng chờ. Đây là kỹ thuật số 1 cần nhớ.

```
   4 điều kiện cùng đúng   =   💀 Deadlock có thể xảy ra
   ───────────────────────────────────────────────────
   Phá  Mutual Exclusion   →  dùng immutable / atomic (không khóa)
   Phá  Hold & Wait        →  lấy mọi khóa một lần, hoặc không giữ khi xin
   Phá  No Preemption      →  tryLock(timeout) → nhả & retry
   Phá  Circular Wait      →  LOCK ORDERING (mạnh & dễ nhất)  ✅
```

### 3. Ví dụ kinh điển: hai khóa lấy ngược thứ tự

Đây là **cách deadlock ra đời 90% trường hợp**. Hai thread cần cùng 2 khóa (`lockA`, `lockB`) nhưng lấy theo **thứ tự ngược nhau**:

```
Thread 1: lấy lockA  ───►  (giữ A)  ───►  xin lockB ... ⏳ chờ
Thread 2: lấy lockB  ───►  (giữ B)  ───►  xin lockA ... ⏳ chờ

Timeline tử thần:
  t1  Thread 1: synchronized(lockA)   → CHIẾM A
  t2  Thread 2: synchronized(lockB)   → CHIẾM B
  t3  Thread 1: synchronized(lockB)   → B đang bị T2 giữ → CHỜ
  t4  Thread 2: synchronized(lockA)   → A đang bị T1 giữ → CHỜ
      ───────────────────────────────────────────────────────
      T1 chờ B (T2 giữ), T2 chờ A (T1 giữ)  →  VÒNG CHỜ  →  💀
```

Bài toán tiền cọc cổ điển: `transfer(from, to)` khóa `from` rồi khóa `to`. Nếu cùng lúc có `transfer(X, Y)` và `transfer(Y, X)` → một thread khóa X chờ Y, thread kia khóa Y chờ X → **deadlock**. (Code đầy đủ ở mục Thực hành — đây chính là Mini Project hôm nay.)

> ⚠️ Thủ phạm là **thứ tự khóa không nhất quán**. Cùng một cặp khóa nhưng nơi này lấy `A→B`, nơi kia lấy `B→A` là đủ tạo bom hẹn giờ. Nguy hiểm hơn: hai khóa đó có thể nằm ở **hai class khác nhau**, lập trình viên không nhìn thấy chúng "đụng" nhau.

### 4. Phát hiện deadlock (Detection)

Deadlock không tự báo, nên bạn phải **chủ động soi**. Ba công cụ:

**(a) `jstack` / Thread Dump** — JVM tự phát hiện và in ra:

```bash
jps                 # tìm PID của tiến trình Java
jstack <PID>        # in thread dump; JVM tự dò và in mục "Found one Java-level deadlock"
```

Thread dump sẽ in rõ:
```
Found one Java-level deadlock:
=============================
"Thread-1":
  waiting to lock monitor 0x... (object 0x..., a java.lang.Object),
  which is held by "Thread-2"
"Thread-2":
  waiting to lock monitor 0x... (object 0x..., a java.lang.Object),
  which is held by "Thread-1"
```

> 💡 Trong Docker/k8s không có terminal? Gửi tín hiệu `kill -3 <PID>` (SIGQUIT) → JVM in thread dump ra **stdout/log**. Hoặc dùng `jcmd <PID> Thread.print`. Luôn lấy thread dump là **bước đầu tiên** khi service "treo nhưng không chết".

**(b) `ThreadMXBean.findDeadlockedThreads()`** — phát hiện bằng code, dùng cho watchdog/health-check:

```java
ThreadMXBean mx = ManagementFactory.getThreadMXBean();
long[] ids = mx.findDeadlockedThreads();   // null nếu không có deadlock
if (ids != null) { /* báo động! */ }
```

**(c) VisualVM / JConsole / JFR** — công cụ GUI có nút "Detect Deadlock", tiện khi dev local.

### 5. Phòng tránh (Prevention) — 3 vũ khí chính

#### 5.1. Lock Ordering — luôn lấy khóa theo MỘT thứ tự toàn cục

Phá **Circular Wait**. Quy ước: mọi thread, dù muốn khóa cặp nào, **luôn khóa theo cùng thứ tự** (ví dụ theo `id` tăng dần, hoặc `System.identityHashCode`). Khi đó không thể có vòng.

```
KHÔNG có thứ tự (deadlock):        CÓ lock ordering (an toàn):
  T1: lock(X) → lock(Y)              T1: lock(min) → lock(max)
  T2: lock(Y) → lock(X)             T2: lock(min) → lock(max)   ← cùng hướng!
       ↑ ngược → vòng                    → cả hai cùng tranh "min" trước,
                                             ai thua chỉ chờ "min", không tạo vòng
```

#### 5.2. `tryLock` có timeout — cho phép "bỏ cuộc rồi thử lại"

Phá **No Preemption**. `ReentrantLock.tryLock(timeout)` thử lấy khóa trong khoảng thời gian; nếu không được thì **trả `false`** thay vì chờ mãi. Thread chủ động **nhả các khóa đang giữ**, lùi lại (back-off) một chút rồi thử lại từ đầu → vòng chờ bị phá.

```java
if (a.lock.tryLock(1, TimeUnit.SECONDS)) {
    try {
        if (b.lock.tryLock(1, TimeUnit.SECONDS)) {
            try { /* việc cần cả 2 khóa */ }
            finally { b.lock.unlock(); }
        } else { /* lấy b thất bại → nhả a, lùi lại, thử lại */ }
    } finally { a.lock.unlock(); }
}
```

#### 5.3. Thu hẹp phạm vi & giảm số khóa

Phá **Hold and Wait**. Càng giữ ít khóa, giữ càng ngắn, càng khó deadlock:
- **Đừng gọi code lạ (callback, listener, method override) khi đang giữ khóa** — nó có thể lại đi xin khóa khác (gây vòng chờ ngầm).
- Làm việc nặng (I/O, gọi mạng, query DB) **ngoài** vùng khóa.
- Cân nhắc thay khóa bằng **atomic/immutable** (xem Day 19) — không khóa thì không deadlock.

> 💡 Quy tắc vàng: **giữ ít khóa nhất, trong thời gian ngắn nhất, theo thứ tự nhất quán nhất.** Ba chữ "nhất" này né được phần lớn deadlock.

### 6. Livelock & Starvation — hai họ hàng dễ nhầm

Deadlock không phải kiểu "kẹt" duy nhất:

| Vấn đề | Mô tả | Ví von |
|---|---|---|
| **Deadlock** | Các thread **đứng yên** chờ nhau vĩnh viễn | Hai người đứng cứng giữa cầu |
| **Livelock** | Các thread **vẫn đang chạy/đổi trạng thái** nhưng **không tiến triển** — cứ "nhường nhau" mãi | Hai người trong hành lang cùng né sang trái, rồi cùng né sang phải, mãi không qua được |
| **Starvation** (đói) | Một thread **không bao giờ tới lượt** vì luôn bị thread khác (ưu tiên cao hơn, hoặc tham lam) chiếm tài nguyên | Người yếu thế ở quầy luôn bị người khác chen ngang |

```
Deadlock:   T1 ⏸  T2 ⏸    (cả hai bất động)
Livelock:   T1 ↔  T2 ↔    (cả hai bận rộn "nhường nhau" mà chẳng ai đi)
Starvation: T1 ▶▶▶  T2 ⏸  (T2 đói, cứ chờ mãi nhưng T1 chạy được)
```

> ⚠️ Cẩn thận: kỹ thuật `tryLock + retry` chống deadlock, nhưng nếu **back-off cố định** (mọi thread lùi cùng một khoảng) lại có thể tạo **livelock** — chúng cứ đồng loạt thử lại, đồng loạt thất bại. Cách chữa: dùng **back-off ngẫu nhiên** (randomized) để các thread lệch pha nhau. Còn starvation thì xử lý bằng **fair lock** (`new ReentrantLock(true)`) — xếp hàng theo thứ tự đến.

---

## 🔁 Đối chiếu với Laravel/PHP

Như Day 19, mô hình **shared-nothing** của PHP cứu bạn khỏi deadlock **in-memory**: mỗi request là một process riêng, không giữ chung khóa Mutex/Monitor trong RAM, nên hầu như **không bao giờ** gặp deadlock kiểu "2 thread ôm 2 lock". Đa số dev Laravel cả đời chưa thấy `jstack`.

**NHƯNG** deadlock **vẫn rình rập bạn ở tầng DB** — và nếu từng chạy hệ thống lớn, bạn chắc đã gặp:

```
MySQL/PostgreSQL:  Deadlock found when trying to get lock; try restarting transaction
```

Bản chất **y hệt** deadlock Java, chỉ khác "khóa" là **row lock** trong transaction:

```
Java in-memory deadlock          ≈   DB deadlock (Laravel)
──────────────────────────────────────────────────────────────────
T1: lock(A) rồi xin lock(B)      ≈   Tx1: UPDATE row A rồi UPDATE row B
T2: lock(B) rồi xin lock(A)      ≈   Tx2: UPDATE row B rồi UPDATE row A
khóa = monitor/ReentrantLock     ≈   khóa = row lock trong InnoDB
```

| Khía cạnh | Java (in-memory) | Laravel / DB |
|---|---|---|
| Đơn vị khóa | `synchronized` monitor / `ReentrantLock` | Row lock (`SELECT ... FOR UPDATE`, `UPDATE`) |
| Phát hiện | `jstack`, `findDeadlockedThreads()` | DB tự dò; `SHOW ENGINE INNODB STATUS` (MySQL) |
| Xử lý khi xảy ra | App treo → phải sửa code / restart | DB **tự kill một transaction** & trả lỗi → app **retry** |
| Phòng tránh chính | Lock ordering, `tryLock(timeout)` | **Cập nhật row theo thứ tự id nhất quán** (cùng lock ordering!), transaction ngắn |

> 🧩 Insight chuyển ngữ: cái MySQL khuyên "luôn `UPDATE` các bản ghi theo cùng thứ tự khóa chính" **chính là lock ordering** mà ta vừa học. Bạn đã chống deadlock ở tầng DB rồi; Java chỉ kéo nó vào RAM. Một điểm khác lớn: **DB tự phát hiện và tự gỡ deadlock** (kill 1 tx, bạn chỉ cần `retry`); còn JVM **không tự gỡ** — deadlock in-memory treo cứng cho tới khi bạn restart. Vì vậy ở Java, **phòng tránh** quan trọng hơn nhiều so với "chữa".

> ⚠️ Lưu ý kép: ngay cả khi viết Java/Spring, bạn vẫn có thể dính **DB deadlock** (vì Spring vẫn nói chuyện với MySQL). Nên bạn sẽ phải xử lý **cả hai** loại: deadlock JVM (lock ordering trong code) **và** deadlock DB (thứ tự update + retry).

---

## 💻 Thực hành code

### (a) TẠO deadlock cố ý — hai khóa ngược thứ tự

```java
import java.util.concurrent.TimeUnit;

public class DeadlockDemo {

    // Hai "tài nguyên" cần khóa
    private static final Object lockA = new Object();
    private static final Object lockB = new Object();

    public static void main(String[] args) {

        // Thread 1: khóa A trước, rồi xin B
        Thread t1 = new Thread(() -> {
            synchronized (lockA) {
                System.out.println("T1: đã giữ lockA, ngủ chút...");
                sleep(100);                                  // tạo khe để T2 kịp giữ B
                System.out.println("T1: xin lockB...");
                synchronized (lockB) {                       // ← sẽ kẹt ở đây
                    System.out.println("T1: đã giữ cả A và B");
                }
            }
        }, "Thread-1");

        // Thread 2: khóa B trước, rồi xin A  (NGƯỢC thứ tự → deadlock)
        Thread t2 = new Thread(() -> {
            synchronized (lockB) {
                System.out.println("T2: đã giữ lockB, ngủ chút...");
                sleep(100);
                System.out.println("T2: xin lockA...");
                synchronized (lockA) {                       // ← sẽ kẹt ở đây
                    System.out.println("T2: đã giữ cả B và A");
                }
            }
        }, "Thread-2");

        t1.start();
        t2.start();
        // Chương trình sẽ TREO ở đây vĩnh viễn, không in "đã giữ cả A và B"
        System.out.println("Main: đã khởi động 2 thread, chờ deadlock...");
    }

    private static void sleep(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
```

Chạy chương trình này → nó **treo**. Mở terminal khác:

```bash
jps                          # giả sử PID = 12345
jstack 12345 | grep -A 20 "Found one"
```

Bạn sẽ thấy `Found one Java-level deadlock:` mô tả đúng T1 chờ T2 và ngược lại.

### (b) PHÁT HIỆN bằng code — watchdog dùng `ThreadMXBean`

```java
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

public class DeadlockDetector {

    /** Chạy thread nền kiểm tra deadlock định kỳ — đặt vào app production để cảnh báo. */
    public static void startWatchdog() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                long[] deadlocked = mx.findDeadlockedThreads();   // null nếu không có
                if (deadlocked != null) {
                    System.err.println("🚨 PHÁT HIỆN DEADLOCK! " + deadlocked.length + " thread:");
                    ThreadInfo[] infos = mx.getThreadInfo(deadlocked, true, true);
                    for (ThreadInfo info : infos) {
                        System.err.println("  - " + info.getThreadName()
                                + " đang chờ khóa giữ bởi thread "
                                + info.getLockOwnerName());
                    }
                    // production: bắn alert (Slack/PagerDuty), tăng metric, có thể tự restart
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            }
        }, "deadlock-watchdog");
        watchdog.setDaemon(true);   // không cản app tắt
        watchdog.start();
    }
}
```

### (c) SỬA bằng Lock Ordering — luôn khóa theo thứ tự toàn cục

```java
public class LockOrderingDemo {

    private static final Object lockA = new Object();
    private static final Object lockB = new Object();

    /** Mẹo: dùng identityHashCode để định ra "thứ tự toàn cục" giữa 2 object bất kỳ,
     *  rồi LUÔN khóa object có hash nhỏ trước → không bao giờ tạo vòng chờ. */
    static void doWork(Object first, Object second) {
        int h1 = System.identityHashCode(first);
        int h2 = System.identityHashCode(second);
        Object low  = (h1 <= h2) ? first : second;     // khóa cái "nhỏ" trước
        Object high = (h1 <= h2) ? second : first;

        synchronized (low) {
            synchronized (high) {
                System.out.println(Thread.currentThread().getName() + ": giữ cả 2 khóa an toàn");
            }
        }
    }

    public static void main(String[] args) {
        // Dù T1 gọi doWork(A,B) và T2 gọi doWork(B,A), cả hai vẫn khóa theo CÙNG thứ tự
        new Thread(() -> doWork(lockA, lockB), "T1").start();
        new Thread(() -> doWork(lockB, lockA), "T2").start();
    }
}
```

### (d) SỬA bằng `tryLock` có timeout — bỏ cuộc rồi thử lại

```java
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.ThreadLocalRandom;

public class TryLockDemo {

    static final ReentrantLock lockA = new ReentrantLock();
    static final ReentrantLock lockB = new ReentrantLock();

    /** Lấy CẢ HAI khóa; nếu không lấy đủ trong timeout thì nhả hết, lùi ngẫu nhiên rồi thử lại. */
    static void doWork(ReentrantLock first, ReentrantLock second) throws InterruptedException {
        while (true) {
            boolean gotFirst = false, gotSecond = false;
            try {
                gotFirst = first.tryLock(200, TimeUnit.MILLISECONDS);
                if (gotFirst) {
                    gotSecond = second.tryLock(200, TimeUnit.MILLISECONDS);
                }
                if (gotFirst && gotSecond) {
                    System.out.println(Thread.currentThread().getName() + ": giữ đủ 2 khóa, làm việc");
                    return;                               // thành công → thoát vòng lặp
                }
            } finally {
                if (gotSecond) second.unlock();
                if (gotFirst)  first.unlock();            // QUAN TRỌNG: luôn nhả những gì đã giữ
            }
            // Thất bại → lùi lại NGẪU NHIÊN (tránh livelock) rồi thử lại
            Thread.sleep(ThreadLocalRandom.current().nextInt(50, 150));
        }
    }

    public static void main(String[] args) {
        new Thread(() -> run(lockA, lockB), "T1").start();
        new Thread(() -> run(lockB, lockA), "T2").start();   // ngược thứ tự vẫn KHÔNG deadlock
    }
    static void run(ReentrantLock a, ReentrantLock b) {
        try { doWork(a, b); } catch (InterruptedException ignored) {}
    }
}
```

> 💡 So sánh hai cách sửa: **lock ordering** là giải pháp "sạch" nhất khi bạn **biết trước** quan hệ thứ tự giữa các khóa (như account id). **`tryLock` + timeout** linh hoạt hơn khi khó áp một thứ tự toàn cục, nhưng phải cẩn thận **luôn nhả khóa trong `finally`** và **back-off ngẫu nhiên** để khỏi sa vào livelock.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Lấy nhiều khóa theo thứ tự không nhất quán.** Đây là nguyên nhân số 1. Quy ước **một thứ tự khóa toàn cục** và tuân thủ tuyệt đối.
- **Gọi method/callback "lạ" khi đang giữ khóa.** Code đó có thể lại xin khóa khác → vòng chờ ngầm bạn không thấy. Hạn chế tối đa; nếu phải, gọi **ngoài** vùng khóa.
- **Quên nhả khóa với `ReentrantLock`.** Khác `synchronized` (tự nhả khi ra khỏi block), `lock()` phải đi kèm `unlock()` trong `finally`. Quên → khóa kẹt mãi → "deadlock một phía".
- **`tryLock` mà không nhả khóa đã lấy khi lấy khóa thứ hai thất bại.** Phải nhả `first` rồi mới retry, nếu không vẫn giữ-và-chờ.
- **Back-off cố định khi retry → livelock.** Mọi thread lùi cùng khoảng → đồng loạt thử lại, đồng loạt fail. Dùng **back-off ngẫu nhiên**.
- **Đồng bộ trên `String`/`Integer` (đã intern/cache).** Nhiều chỗ vô tình share cùng một monitor → vừa dễ deadlock vừa khó debug. Luôn khóa trên `private final Object lock`.
- **Nghĩ "test local không treo nghĩa là an toàn".** Như race condition, deadlock phụ thuộc timing — chỉ lộ ra dưới tải cao trên production. Phải review lock ordering bằng mắt + chuẩn bị watchdog.
- **Nested synchronized sâu.** Càng nhiều lớp khóa lồng nhau, càng dễ vô tình tạo vòng. Giữ "độ sâu khóa" tối thiểu.

---

## 🚀 Liên hệ Spring Boot / Production

- **Thread pool cạn vì deadlock.** Trong web server, mỗi request một thread từ pool (Tomcat mặc định ~200). Nếu vài request dính deadlock, thread không trả về pool; tải tiếp tục đến → pool cạn → **toàn bộ service treo** dù CPU ~0%. Triệu chứng: latency tăng vọt, request timeout hàng loạt, không có exception. **Phản xạ đầu tiên: lấy thread dump** (`kill -3`, `jcmd ... Thread.print`, hoặc actuator `/threaddump`).
- **Spring Boot Actuator** có endpoint `/actuator/threaddump` trả về thread dump dạng JSON — bật nó trên môi trường nội bộ để chẩn đoán nhanh khi service "đơ".
- **DB deadlock vẫn xảy ra trong Spring.** JPA/Hibernate ghi nhiều bảng trong một transaction; nếu thứ tự update không nhất quán → MySQL báo deadlock. Phòng: **update theo thứ tự id ổn định**, transaction ngắn, và cấu hình **retry** (ví dụ Spring Retry bắt `DeadlockLoserDataAccessException` để thử lại — đối ứng của "try restarting transaction").
- **`@Async` + tài nguyên dùng chung.** Khi bạn tự quản thread pool (Day 22) và nhiều task tranh khóa, deadlock in-memory thành rủi ro thật. Áp lock ordering ngay từ thiết kế.
- **Connection pool deadlock kiểu HikariCP.** Một biến thể nguy hiểm: một request giữ một DB connection rồi lại đợi một connection thứ hai từ cùng pool (ví dụ vòng lặp lồng nhau cần 2 connection) → khi pool cạn, tất cả ngồi chờ connection mà chẳng ai nhả → **deadlock tài nguyên** dù không có `synchronized` nào. Quy tắc: **một request không giữ quá một connection cùng lúc**.
- **Distributed deadlock.** Khi scale nhiều service + distributed lock (Redis/ZooKeeper), deadlock vượt khỏi một JVM. Distributed lock **phải có TTL/timeout** (chính là `tryLock(timeout)` ở quy mô phân tán) để không treo vĩnh viễn.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 19. Khi một phiên đấu giá kết thúc, hệ thống phải **chuyển tiền cọc** giữa các tài khoản (hoàn cọc cho người thua, trừ cọc người thắng, hoặc người dùng tự chuyển quỹ giữa hai ví). Thao tác `transfer(from, to, amount)` cần **khóa cả hai tài khoản** để số dư không bị race. Đây chính là cái bẫy deadlock kinh điển — và hôm nay ta tái hiện rồi sửa.

### Bước 1 — Model tài khoản có khóa riêng

```java
import java.util.concurrent.locks.ReentrantLock;

public class Account {
    private final long id;            // dùng làm khóa thứ tự toàn cục (lock ordering)
    private long balance;             // số dư (đồng cọc), bảo vệ bởi lock
    private final ReentrantLock lock = new ReentrantLock();

    public Account(long id, long initial) { this.id = id; this.balance = initial; }

    public long id()              { return id; }
    public long balance()         { return balance; }
    public ReentrantLock lock()   { return lock; }
    public void debit(long a)     { balance -= a; }
    public void credit(long a)    { balance += a; }
}
```

### Bước 2 — TÁI HIỆN bug: `transfer` khóa theo thứ tự (from, to)

```java
public class BuggyBank {
    /** SAI: luôn khóa `from` trước rồi `to`. Hai lệnh chuyển ngược chiều → deadlock. */
    public void transfer(Account from, Account to, long amount) {
        synchronized (from.lock()) {                 // T1: transfer(X,Y) khóa X
            synchronized (to.lock()) {               // T2: transfer(Y,X) khóa Y rồi xin X
                if (from.balance() >= amount) {
                    from.debit(amount);
                    to.credit(amount);
                }
            }
        }
    }
}
```

Nếu thread 1 chạy `transfer(X, Y)` và thread 2 chạy `transfer(Y, X)` cùng lúc → T1 giữ X chờ Y, T2 giữ Y chờ X → **deadlock**.

### Bước 3 — SỬA bằng lock ordering theo `id`

```java
public class SafeBank {

    /** ĐÚNG: LUÔN khóa tài khoản có id NHỎ trước → không thể tạo vòng chờ. */
    public boolean transfer(Account from, Account to, long amount) {
        if (from.id() == to.id()) return false;      // tránh tự chuyển & tự-deadlock

        // Xác định thứ tự khóa toàn cục theo id
        Account first  = from.id() < to.id() ? from : to;
        Account second = from.id() < to.id() ? to   : from;

        synchronized (first.lock()) {
            synchronized (second.lock()) {            // luôn cùng hướng id nhỏ → lớn
                if (from.balance() < amount) return false;   // không đủ cọc
                from.debit(amount);
                to.credit(amount);
                return true;
            }
        }
    }
}
```

### Bước 4 — Phiên bản `tryLock` (chống cả deadlock lẫn treo lâu)

```java
import java.util.concurrent.TimeUnit;

public class ResilientBank {
    public boolean transfer(Account from, Account to, long amount) throws InterruptedException {
        if (from.id() == to.id()) return false;
        // Vẫn áp lock ordering ĐỂ KÉP an toàn, kết hợp timeout để không treo
        Account first  = from.id() < to.id() ? from : to;
        Account second = from.id() < to.id() ? to   : from;

        if (first.lock().tryLock(500, TimeUnit.MILLISECONDS)) {
            try {
                if (second.lock().tryLock(500, TimeUnit.MILLISECONDS)) {
                    try {
                        if (from.balance() < amount) return false;
                        from.debit(amount);
                        to.credit(amount);
                        return true;
                    } finally { second.lock().unlock(); }
                }
            } finally { first.lock().unlock(); }
        }
        return false;   // không lấy đủ khóa kịp → báo thất bại để caller retry
    }
}
```

### Bước 5 — Test phơi bày deadlock & xác minh bản sửa

```java
import java.util.concurrent.*;

public class BankDeadlockTest {
    public static void main(String[] args) throws Exception {
        Account x = new Account(1, 10_000);
        Account y = new Account(2, 10_000);
        SafeBank bank = new SafeBank();              // đổi thành BuggyBank để thấy TREO

        int N = 100;
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            CountDownLatch done = new CountDownLatch(2 * N);
            for (int i = 0; i < N; i++) {
                pool.submit(() -> { bank.transfer(x, y, 1); done.countDown(); }); // X→Y
                pool.submit(() -> { bank.transfer(y, x, 1); done.countDown(); }); // Y→X (ngược!)
            }
            // Với BuggyBank: done.await() sẽ KẸT (deadlock). Với SafeBank: xong nhanh.
            boolean ok = done.await(5, TimeUnit.SECONDS);
            System.out.println(ok ? "✅ Không deadlock, hoàn tất"
                                  : "💀 Nghi deadlock — lấy jstack ngay!");
        }
        // Tổng tiền bảo toàn (không bị race) → luôn = 20000
        System.out.println("Tổng số dư = " + (x.balance() + y.balance()));
    }
}
```

Với `BuggyBank` → in `💀 Nghi deadlock`, lấy `jstack` thấy vòng chờ. Với `SafeBank`/`ResilientBank` → in `✅ Không deadlock` và tổng = 20000.

> 🧩 Liên hệ thực chiến: y hệt MySQL khuyên "khi chuyển khoản giữa 2 row, luôn `UPDATE` theo thứ tự khóa chính tăng dần". Lock ordering theo `id` ở Java và "update theo id tăng dần" ở DB là **cùng một nguyên lý** phòng deadlock.

**Nhiệm vụ Day 20:**
1. Chạy `BankDeadlockTest` với `BuggyBank`, để nó treo, lấy `jstack` và đọc mục "Found one Java-level deadlock".
2. Đổi sang `SafeBank`, chạy lại, xác nhận hoàn tất và tổng tiền bảo toàn.
3. Thêm `DeadlockDetector.startWatchdog()` vào đầu `main`, chạy lại bản Buggy để xem watchdog tự báo deadlock.
4. Ghi `notes/day-20.md`: liệt kê 4 điều kiện Coffman, đánh dấu điều kiện nào bị lock ordering phá vỡ và vì sao.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Deadlock là gì?**
> **Đáp:** Là tình huống nhiều thread chờ nhau vĩnh viễn: mỗi thread giữ một khóa và đồng thời chờ một khóa khác đang bị thread trong cùng nhóm giữ, tạo thành vòng chờ khép kín. Không ai nhả khóa nên tất cả treo cứng; JVM không tự gỡ và không ném exception.

**Q2: Nguyên nhân phổ biến nhất gây deadlock?**
> **Đáp:** Lấy **nhiều khóa theo thứ tự không nhất quán** — ví dụ thread 1 khóa A rồi xin B, thread 2 khóa B rồi xin A. Đủ tạo vòng chờ. Cách sửa kinh điển là **lock ordering**: mọi thread luôn lấy khóa theo cùng một thứ tự toàn cục.

**Q3: Làm sao phát hiện deadlock trong một app Java đang chạy?**
> **Đáp:** Lấy **thread dump** bằng `jstack <PID>` (hoặc `jcmd <PID> Thread.print`, hoặc gửi `kill -3`); JVM tự dò và in mục "Found one Java-level deadlock" kèm thread nào chờ khóa của thread nào. Trong code có thể dùng `ThreadMXBean.findDeadlockedThreads()` làm watchdog. Spring Boot có `/actuator/threaddump`.

**Q4: Phân biệt deadlock, livelock, starvation.**
> **Đáp:** **Deadlock**: các thread đứng yên chờ nhau mãi mãi. **Livelock**: các thread vẫn chạy/đổi trạng thái nhưng "nhường nhau" liên tục nên không tiến triển (ví dụ retry đồng loạt rồi đồng loạt fail). **Starvation**: một thread mãi không tới lượt vì luôn bị thread khác chiếm tài nguyên (ưu tiên thấp hoặc lock không công bằng).

### Mức Senior

**Q5: Kể 4 điều kiện Coffman và cách phá từng điều kiện.**
> **Đáp:** (1) **Mutual exclusion** — khóa độc quyền; phá bằng tài nguyên chia sẻ được/atomic/immutable. (2) **Hold and wait** — giữ khóa rồi xin khóa khác; phá bằng lấy mọi khóa một lần hoặc không giữ khi xin. (3) **No preemption** — không giật được khóa; phá bằng `tryLock` có timeout để chủ động nhả & thử lại. (4) **Circular wait** — vòng chờ; phá bằng **lock ordering** (thứ tự khóa toàn cục). Chỉ cần phá một điều kiện là hết deadlock; phá circular wait bằng lock ordering thường là cách thực tế nhất.

**Q6: `tryLock` chống deadlock thế nào, và nó có thể tạo vấn đề gì mới?**
> **Đáp:** `tryLock(timeout)` phá điều kiện **no preemption**: thread chỉ chờ khóa trong một khoảng; nếu không lấy được, nó nhả những khóa đang giữ rồi thử lại → vòng chờ không hình thành. Vấn đề mới: nếu mọi thread **back-off cố định** cùng khoảng, chúng có thể rơi vào **livelock** (đồng loạt thử lại, đồng loạt thất bại). Khắc phục bằng **back-off ngẫu nhiên** và giới hạn số lần retry.

**Q7: Một service Java đột nhiên "treo" — latency vọt, request timeout, nhưng CPU gần 0% và không có exception. Bạn nghi gì và làm gì?**
> **Đáp:** Nghi **deadlock** (hoặc tài nguyên cạn kiểu connection pool/thread pool). CPU ~0% là dấu hiệu thread đang **chờ** chứ không chạy. Hành động: lấy **thread dump** ngay (`jcmd Thread.print` / `kill -3` / actuator `/threaddump`), tìm "Found one Java-level deadlock" và các thread `BLOCKED`/`WAITING` trên monitor. Kiểm tra cả connection pool (HikariCP) xem có request giữ >1 connection. Khắc phục gốc: lock ordering, giảm độ sâu khóa, không gọi I/O/code lạ khi giữ khóa.

**Q8: Deadlock ở DB (MySQL "Deadlock found...") khác gì deadlock JVM? Xử lý ra sao?**
> **Đáp:** Bản chất giống nhau (vòng chờ trên các khóa — ở DB là row lock trong transaction). Khác biệt then chốt: **DB tự phát hiện và tự gỡ** bằng cách kill một transaction rồi trả lỗi, nên ứng dụng chỉ cần **retry** transaction. JVM **không tự gỡ** — deadlock in-memory treo mãi cho tới khi restart. Vì vậy: ở DB tập trung vào **update theo thứ tự id nhất quán + transaction ngắn + cơ chế retry**; ở JVM tập trung vào **phòng tránh** (lock ordering) vì không có cứu cánh tự động.

**Q9: Vì sao "không gọi code lạ khi đang giữ khóa" là quy tắc quan trọng?**
> **Đáp:** Vì đoạn code lạ (callback, listener, method được override, gọi sang module khác) có thể tự nó đi xin một khóa khác mà bạn không nhìn thấy ở chỗ gọi. Khi đó bạn vô tình tạo ra việc "giữ khóa A và xin khóa B" — nếu nơi khác giữ B xin A thì thành deadlock. Hệ quả còn là giữ khóa quá lâu (I/O/mạng trong vùng khóa) làm tăng tranh chấp. Giải pháp: chỉ làm việc tối thiểu trong vùng khóa, gọi code lạ ở **ngoài** khóa.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được deadlock và vẽ sơ đồ vòng chờ 2 thread bằng lời của mình
- [ ] Thuộc 4 điều kiện Coffman và cách phá từng điều kiện
- [ ] Tự tay tạo deadlock cố ý rồi đọc nó qua `jstack`
- [ ] Phát hiện deadlock bằng `ThreadMXBean.findDeadlockedThreads()`
- [ ] Sửa deadlock bằng lock ordering và bằng `tryLock` có timeout
- [ ] Phân biệt deadlock / livelock / starvation, hiểu back-off ngẫu nhiên & fair lock
- [ ] Hoàn thành Mini Project: `transfer` tiền cọc không deadlock (lock ordering theo id)
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 10 "Avoiding Liveness Hazards" (deadlock, lock ordering, livelock)
- Oracle Java Tutorials — "Concurrency: Liveness" (Deadlock, Starvation and Livelock)
- Coffman, Elphick, Shoshani (1971) — "System Deadlocks" (4 điều kiện kinh điển, đọc tóm tắt)
- Javadoc `java.lang.management.ThreadMXBean` — `findDeadlockedThreads`, `getThreadInfo`
- `man jstack`, `jcmd <pid> Thread.print` — công cụ thread dump
- MySQL Reference Manual — "Deadlocks in InnoDB" & "How to Minimize and Handle Deadlocks"
- Baeldung — "Deadlock in Java" và "Guide to jstack"
