# Day 17 - Thread Lifecycle

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP lần đầu phải tự tay quản lý vòng đời thread trong Java.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm chắc **6 trạng thái** trong `Thread.State`: `NEW`, `RUNNABLE`, `BLOCKED`, `WAITING`, `TIMED_WAITING`, `TERMINATED` — câu hỏi phỏng vấn ruột.
- Vẽ được **sơ đồ chuyển trạng thái** (state transition diagram) và biết phương thức nào đẩy thread sang trạng thái nào.
- Phân biệt **`sleep()` vs `wait()`** — một bẫy kinh điển: ai nhả lock, ai không.
- Hiểu cơ chế **monitor**: `wait()` / `notify()` / `notifyAll()`, và vì sao luôn gọi `wait()` trong vòng `while`.
- Biết dùng `join()`, `yield()` đúng chỗ.
- Nắm **cách dừng thread ĐÚNG** bằng `interrupt()` + cờ ngắt + `InterruptedException`, và vì sao `stop()` / `suspend()` / `resume()` đã bị **deprecated**.

---

## 🧠 Lý thuyết cốt lõi

### 1. Thread không "sống mãi" — nó có một vòng đời

Một `Thread` trong Java đi qua các giai đoạn rất rõ ràng, từ lúc bạn `new` ra cho tới khi nó chạy xong. JVM mô hình hóa chính xác điều này bằng enum `java.lang.Thread.State` với **đúng 6 giá trị**. Bạn lấy trạng thái hiện tại bằng `thread.getState()`.

```
NEW  →  RUNNABLE  →  TERMINATED
            ↑↓
   (BLOCKED / WAITING / TIMED_WAITING)
```

> 💡 Đây là góc nhìn **của JVM**, không phải của hệ điều hành. OS còn tách nhỏ hơn (ready/running), nhưng ở mức Java ta chỉ nhìn thấy 6 trạng thái này.

### 2. Sáu trạng thái của `Thread.State` — mổ xẻ từng cái

| Trạng thái | Ý nghĩa | Vào khi nào | Ra khi nào |
|---|---|---|---|
| **NEW** | Đối tượng `Thread` đã tạo nhưng **chưa** `start()` | Ngay sau `new Thread(...)` | Khi gọi `start()` |
| **RUNNABLE** | Đang chạy **hoặc** sẵn sàng chạy, chờ CPU cấp slot | Sau `start()`, hoặc khi được đánh thức/giải phóng | Khi chặn lock, chờ, ngủ, hoặc chạy xong |
| **BLOCKED** | Bị chặn vì **đang chờ lấy monitor lock** để vào `synchronized` | Khi 2 thread tranh nhau cùng 1 lock | Khi giành được lock → quay lại `RUNNABLE` |
| **WAITING** | Chờ **vô thời hạn** một thread khác hành động | `wait()`, `join()`, `LockSupport.park()` (không timeout) | Bị `notify()`/`notifyAll()`, thread đích kết thúc, hoặc `unpark()` |
| **TIMED_WAITING** | Chờ nhưng **có hạn thời gian** | `sleep(t)`, `wait(t)`, `join(t)`, `parkNanos()` | Hết thời gian, bị đánh thức, hoặc bị interrupt |
| **TERMINATED** | Thread đã **chạy xong** (`run()` kết thúc hoặc văng exception) | Khi `run()` trả về | Không bao giờ — đây là trạng thái cuối, không thể `start()` lại |

**Chi tiết từng trạng thái:**

- **NEW** — Thread mới chỉ là một object Java bình thường, chưa có thread thật của OS phía sau. Gọi `start()` mới sinh ra thread OS và chạy `run()`. Gọi `run()` trực tiếp **KHÔNG** tạo thread mới — nó chạy ngay trên thread hiện tại như một method thường (bẫy hay gặp!).

- **RUNNABLE** — Điểm cốt lõi: Java **gộp** cả "đang chạy trên CPU" (running) lẫn "sẵn sàng nhưng đang đợi scheduler xếp lịch" (ready) vào **một** trạng thái `RUNNABLE`. JVM **không** tách riêng trạng thái "running". Vì vậy thấy `RUNNABLE` không có nghĩa thread đang thực sự dùng CPU ngay lúc đó.

- **BLOCKED** — Chỉ xảy ra với `synchronized`. Thread B muốn vào một khối `synchronized` mà thread A đang giữ lock → B rơi vào `BLOCKED` cho đến khi A nhả lock.

- **WAITING** — Chờ vô hạn, hoàn toàn "ngủ đông" cho đến khi có tín hiệu. `obj.wait()`, `t.join()`, `LockSupport.park()` đều dẫn vào đây.

- **TIMED_WAITING** — Giống `WAITING` nhưng có "đồng hồ hẹn giờ". Hết giờ là tự dậy mà không cần ai đánh thức.

- **TERMINATED** — Hết đời. Một thread đã `TERMINATED` **không thể tái sử dụng**; muốn chạy lại phải tạo `Thread` mới. Gọi `start()` lần hai trên cùng object → `IllegalThreadStateException`.

> ⚠️ `BLOCKED` (chờ lock vào `synchronized`) khác hẳn `WAITING` (đã vào critical section rồi chủ động gọi `wait()` để nhả lock và chờ tín hiệu). Đừng nhầm hai cái này — phỏng vấn rất hay gài.

### 3. Sơ đồ chuyển trạng thái (State Transition Diagram)

```
                         new Thread(r)
                              │
                              ▼
                         ┌─────────┐
                         │   NEW   │
                         └────┬────┘
                              │ start()
                              ▼
            ┌────────────────────────────────────┐
            │              RUNNABLE               │◄────────────┐
            │  (gồm cả "ready" và "running")      │             │
            └──┬──────────┬───────────┬──────┬────┘             │
   chờ lock    │          │           │      │ run() trả về     │
 (synchronized)│   wait() │   sleep(t)│      │                  │
               │  join()  │  wait(t)  │      ▼                  │
               │  park()  │  join(t)  │ ┌──────────┐            │
               ▼          ▼           ▼ │TERMINATED│            │
          ┌─────────┐ ┌────────┐ ┌──────────────┐ └──────────┘ │
          │ BLOCKED │ │WAITING │ │ TIMED_WAITING│              │
          └────┬────┘ └───┬────┘ └──────┬───────┘              │
               │          │             │                      │
       giành được   notify()/        hết giờ /                 │
         lock       notifyAll()/    notify()/                  │
               │    join xong/      interrupt                  │
               │    unpark()           │                       │
               └──────────┴────────────┴───────────────────────┘
```

Đọc sơ đồ:
- `NEW → RUNNABLE`: gọi `start()`.
- `RUNNABLE ↔ BLOCKED`: tranh lock để vào `synchronized`.
- `RUNNABLE ↔ WAITING`: `wait()` / `join()` / `park()` **không** timeout.
- `RUNNABLE ↔ TIMED_WAITING`: `sleep(t)` / `wait(t)` / `join(t)` **có** timeout.
- `RUNNABLE → TERMINATED`: `run()` chạy xong (hoặc văng exception không bắt).

### 4. `sleep(ms)` — ngủ nhưng KHÔNG nhả lock

`Thread.sleep(ms)` đưa thread hiện tại vào `TIMED_WAITING` trong `ms` mili-giây.

```java
synchronized (lock) {
    Thread.sleep(1000);   // VẪN giữ lock trong suốt 1 giây!
}
```

> ⚠️ **BẪY KINH ĐIỂN:** `sleep()` **không** nhả bất kỳ lock nào nó đang giữ. Nếu bạn `sleep()` bên trong khối `synchronized`, mọi thread khác chờ lock đó sẽ "chết đứng" trong `BLOCKED` suốt thời gian ngủ. Ngược lại, `wait()` **nhả** lock (xem mục 5). Đây là điểm khác biệt số 1 giữa `sleep` và `wait`.

`sleep()` là method `static` — luôn tác động lên thread **đang gọi** nó, không phải object bạn gọi nó lên. Viết `someThread.sleep(100)` chỉ ngủ thread hiện tại, một cái bẫy gây hiểu lầm.

### 5. Cơ chế monitor: `wait()` / `notify()` / `notifyAll()`

Mỗi object Java đều có một **monitor** (khóa nội tại). Bộ ba `wait/notify/notifyAll` là cách hai thread **phối hợp** với nhau qua monitor đó.

Quy tắc bắt buộc:
1. Phải gọi `wait()`, `notify()`, `notifyAll()` **bên trong** khối `synchronized` của chính object đó. Không thì văng `IllegalMonitorStateException`.
2. `wait()` **nhả lock** rồi đưa thread vào `WAITING` (hoặc `TIMED_WAITING` nếu là `wait(timeout)`).
3. `notify()` đánh thức **một** thread đang chờ; `notifyAll()` đánh thức **tất cả**. Thread được đánh thức phải **giành lại lock** mới chạy tiếp được (tức là đi qua `BLOCKED` trước khi về `RUNNABLE`).

```java
// Người sản xuất báo có hàng
synchronized (queue) {
    queue.add(item);
    queue.notifyAll();        // đánh thức consumer đang chờ
}

// Người tiêu thụ chờ có hàng
synchronized (queue) {
    while (queue.isEmpty()) {  // PHẢI là while, không phải if
        queue.wait();          // nhả lock, ngủ chờ
    }
    process(queue.poll());
}
```

> 💡 **Vì sao luôn dùng `while` chứ không phải `if`?** Vì **spurious wakeup** — JVM/OS cho phép một thread `wait()` bị đánh thức "vô cớ" mà không ai `notify()`. Ngoài ra với `notifyAll()`, nhiều thread cùng dậy nhưng chỉ một thread lấy được hàng; số còn lại phải **kiểm tra lại điều kiện** và ngủ tiếp. Vòng `while` đảm bảo điều kiện luôn được tái kiểm tra sau khi tỉnh.

### 6. `join()` — chờ một thread khác kết thúc

`t.join()` khiến thread đang gọi **chờ cho tới khi `t` chạy xong** (`TERMINATED`). Thread gọi rơi vào `WAITING`; nếu dùng `t.join(timeout)` thì là `TIMED_WAITING`.

```java
Thread worker = new Thread(job);
worker.start();
worker.join();          // main chờ worker xong rồi mới đi tiếp
System.out.println("Worker đã xong, main tiếp tục.");
```

Dùng `join()` rất nhiều khi bạn fork ra vài thread con tính toán song song rồi cần **gom kết quả** lại.

### 7. `yield()` — gợi ý nhường CPU

`Thread.yield()` chỉ là một **gợi ý** với scheduler: "tôi sẵn sàng nhường lượt cho thread khác cùng độ ưu tiên". Scheduler có quyền **phớt lờ**. Thread vẫn ở `RUNNABLE`. Trong code thực tế hầu như không dùng `yield()` để điều khiển logic — chỉ thấy nó trong vài thuật toán spin-wait. Đừng dựa vào nó để đảm bảo thứ tự thực thi.

### 8. `interrupt()` + `InterruptedException` — cách dừng thread ĐÚNG

Java **không** cho bạn "giết" thread từ bên ngoài. Thay vào đó là cơ chế **hợp tác** (cooperative): bạn gửi một **yêu cầu dừng**, thread đích tự quyết định khi nào dừng.

Ba mảnh ghép:

- `t.interrupt()` — **đặt cờ ngắt** (interrupt flag) của thread `t` lên `true`. Nó **không** ép `t` dừng.
- `Thread.currentThread().isInterrupted()` — đọc cờ, **không** xóa cờ.
- `Thread.interrupted()` — đọc cờ rồi **xóa cờ** (đặt về `false`). (Là method `static`, đọc trên thread hiện tại.)

Hai cách "lắng nghe" yêu cầu dừng:

```java
// Cách 1: vòng lặp tính toán — tự kiểm tra cờ
while (!Thread.currentThread().isInterrupted()) {
    doWork();
}

// Cách 2: khi đang sleep/wait/join — bắt InterruptedException
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    // QUAN TRỌNG: khi ném InterruptedException, cờ ngắt đã bị XÓA
    Thread.currentThread().interrupt();   // đặt LẠI cờ để code phía trên biết
    return;                                // dọn dẹp rồi thoát sạch
}
```

> ⚠️ Khi thread đang `sleep()` / `wait()` / `join()` mà bị `interrupt()`, JVM **ném `InterruptedException` VÀ tự động XÓA cờ ngắt**. Nếu bạn "nuốt" exception (chỉ log rồi đi tiếp) thì thông tin "đã bị yêu cầu dừng" bị mất. Quy tắc vàng: hoặc **ném lại** exception, hoặc **gọi lại `interrupt()`** để khôi phục cờ.

### 9. Vì sao `stop()`, `suspend()`, `resume()` bị DEPRECATED

| Method | Vấn đề chí mạng |
|---|---|
| `stop()` | Giết thread **ngay lập tức** tại bất kỳ điểm nào, để object đang sửa dở ở trạng thái **hỏng/không nhất quán**, và **nhả mọi lock** đột ngột → các thread khác thấy dữ liệu hỏng. |
| `suspend()` | Đóng băng thread **mà vẫn giữ nguyên mọi lock** đang cầm → thread khác cần lock đó treo vĩnh viễn → **deadlock**. |
| `resume()` | Đi cặp với `suspend()`; nếu `resume()` chạy trước `suspend()` (race condition) thì thread treo mãi. |

**Thay thế đúng:** dùng `interrupt()` + một cờ `volatile boolean running` để thread tự dừng tại điểm an toàn. Không bao giờ dùng `stop/suspend/resume` trong code mới.

```java
private volatile boolean running = true;
public void shutdown() { running = false; }

public void run() {
    while (running && !Thread.currentThread().isInterrupted()) {
        // làm việc theo từng chunk, kiểm tra cờ giữa các chunk
    }
}
```

> 💡 `volatile` đảm bảo khi một thread đổi `running`, các thread khác **nhìn thấy ngay** (visibility), không bị kẹt giá trị cũ trong cache CPU.

---

## 🔁 Đối chiếu với Laravel/PHP

Thành thật mà nói: chủ đề này **gần như mới hoàn toàn** với dân Laravel/PHP. Mô hình PHP truyền thống là **một request — một tiến trình ngắn**, sinh ra xử lý xong rồi chết, bạn **không bao giờ phải quản lý vòng đời thread**. Server (php-fpm) lo chuyện đó. Điểm chạm gần nhất là **queue worker daemon**.

| Khái niệm Java | Tương đương gần nhất ở Laravel/PHP | Khác biệt |
|---|---|---|
| Thread chạy lâu dài | Tiến trình `php artisan queue:work` (daemon) | PHP: 1 process xử lý tuần tự; Java: nhiều thread trong **một** process, chia sẻ RAM |
| `Thread.State` (6 trạng thái) | Không có khái niệm tương đương | PHP không cho bạn nhìn thấy/điều khiển trạng thái này |
| `interrupt()` để dừng êm | Tín hiệu **SIGTERM** → `queue:work` graceful shutdown | Cùng triết lý "yêu cầu dừng, để worker tự dừng tại điểm an toàn" |
| `wait()` / `notify()` (phối hợp trong RAM) | Polling Redis/DB của queue worker | Java đồng bộ trong bộ nhớ; PHP phối hợp qua hạ tầng ngoài |
| `join()` (chờ thread con xong) | Không phổ biến (PHP hiếm khi fork) | Java fork-join nội bộ là chuyện thường ngày |

**Điểm neo dễ nhớ nhất:** Laravel xử lý SIGTERM cho `queue:work` bằng cách **đợi job hiện tại xong rồi mới thoát**, không cắt ngang giữa chừng. Đó **chính xác** là tinh thần của `interrupt()` trong Java: gửi yêu cầu dừng, để thread chọn điểm an toàn để dừng — chứ không "đùng một cái giết chết" như `stop()`.

> 🧩 Hệ quả tư duy: ở Laravel bạn quen "stateless, request chết là xong". Sang Java, một thread **sống dai** và **chia sẻ object** với thread khác — nên dừng sai cách (như `stop()`) sẽ để lại "rác trạng thái" mà PHP chưa từng bắt bạn lo.

---

## 💻 Thực hành code

### Bài 1 — Quan sát `Thread.State` đổi từ NEW → RUNNABLE → TERMINATED

```java
public class StateDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                System.out.println("  ... worker đang chạy vòng " + i);
            }
        });

        System.out.println("Trước start():   " + t.getState());  // NEW
        t.start();
        System.out.println("Ngay sau start(): " + t.getState()); // RUNNABLE (thường)

        t.join();  // chờ worker chạy xong
        System.out.println("Sau khi xong:    " + t.getState());  // TERMINATED
    }
}
```

### Bài 2 — Quan sát TIMED_WAITING khi `sleep()`

```java
public class SleepStateDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread sleeper = new Thread(() -> {
            try {
                Thread.sleep(2000);   // ngủ 2 giây
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        sleeper.start();
        Thread.sleep(200);  // cho sleeper kịp vào sleep()

        // Lúc này sleeper đang ngủ -> TIMED_WAITING
        System.out.println("Trạng thái sleeper: " + sleeper.getState()); // TIMED_WAITING

        sleeper.join();
        System.out.println("Sau join: " + sleeper.getState());           // TERMINATED
    }
}
```

### Bài 3 — `join()`: main chờ nhiều worker gom kết quả

```java
import java.util.concurrent.atomic.AtomicInteger;

public class JoinDemo {
    public static void main(String[] args) throws InterruptedException {
        AtomicInteger tong = new AtomicInteger(0);
        Thread[] workers = new Thread[3];

        for (int i = 0; i < workers.length; i++) {
            final int phan = (i + 1) * 10;
            workers[i] = new Thread(() -> tong.addAndGet(phan));
            workers[i].start();
        }

        for (Thread w : workers) {
            w.join();   // chờ TỪNG worker xong (main rơi vào WAITING)
        }

        System.out.println("Tổng sau khi gom: " + tong.get()); // 60
    }
}
```

### Bài 4 — `interrupt()` dừng một vòng lặp xử lý sạch sẽ

```java
public class InterruptDemo {
    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            int daXuLy = 0;
            // Lắng nghe cờ ngắt để dừng êm
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(300);            // mô phỏng xử lý 1 item
                    daXuLy++;
                    System.out.println("Đã xử lý item #" + daXuLy);
                } catch (InterruptedException e) {
                    // sleep bị interrupt -> cờ đã bị XÓA, đặt lại để thoát vòng while
                    System.out.println("Nhận yêu cầu dừng khi đang sleep, dọn dẹp...");
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("Worker dừng sạch sẽ sau khi xử lý " + daXuLy + " item.");
        });

        worker.start();
        Thread.sleep(1000);   // để worker chạy ~3 item
        System.out.println(">> Gửi interrupt()");
        worker.interrupt();   // yêu cầu dừng (KHÔNG ép chết)
        worker.join();
        System.out.println("Main kết thúc.");
    }
}
```

> ✅ **Bài tập tự giải thích:** Trong Bài 4, thử **bỏ** dòng `Thread.currentThread().interrupt();` trong khối `catch` rồi chạy lại. Vì sao worker **không chịu dừng** mà chạy mãi? (Gợi ý: cờ ngắt đã bị `InterruptedException` xóa, vòng `while` không còn thấy cờ).

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Gọi `run()` thay vì `start()`.** `run()` chạy ngay trên thread hiện tại (không tạo thread mới); chỉ `start()` mới sinh thread OS và đẩy sang `RUNNABLE`.
- **`start()` hai lần trên cùng object.** Văng `IllegalThreadStateException`. Thread đã `TERMINATED` không tái sử dụng được — phải tạo mới.
- **Tưởng `sleep()` nhả lock.** Sai. `sleep()` giữ nguyên mọi lock; chỉ `wait()` mới nhả lock.
- **Gọi `wait()`/`notify()` ngoài `synchronized`.** Văng `IllegalMonitorStateException` ngay.
- **Dùng `if` thay vì `while` quanh `wait()`.** Dính spurious wakeup hoặc đánh thức nhầm → xử lý trên điều kiện sai.
- **"Nuốt" `InterruptedException`** (chỉ `e.printStackTrace()` rồi đi tiếp). Cờ ngắt đã bị xóa, thread mất tín hiệu dừng. Luôn **ném lại** hoặc **gọi lại `interrupt()`**.
- **Vẫn dùng `stop()`/`suspend()`/`resume()`.** Đã deprecated, gây hỏng trạng thái và deadlock. Dùng `interrupt()` + cờ `volatile`.
- **Quên `volatile` cho cờ dừng.** Thread khác có thể kẹt giá trị cũ trong cache → không bao giờ thấy lệnh dừng.

---

## 🚀 Liên hệ Spring Boot / Production

- **Graceful shutdown của Spring Boot** chính là vòng đời thread ở quy mô lớn: `server.shutdown=graceful` cho phép request đang chạy **hoàn tất** rồi mới đóng — đúng tinh thần `interrupt()` chứ không phải `stop()`.
- **`ExecutorService.shutdown()` vs `shutdownNow()`:** `shutdown()` ngừng nhận task mới, chờ task hiện tại xong; `shutdownNow()` **gửi `interrupt()`** tới các thread đang chạy. Hiểu interrupt giúp bạn viết task biết "lắng nghe" lệnh dừng — nếu không task của bạn sẽ phớt lờ `shutdownNow()`.
- **Thread dump** (`jstack <pid>`) hiển thị trạng thái từng thread (`RUNNABLE`, `BLOCKED`, `WAITING`...). Khi service treo, bạn đọc dump để tìm thread `BLOCKED` đang chờ lock — kỹ năng debug production thực chiến dựa thẳng trên 6 trạng thái hôm nay.
- **`@Async` và `@Scheduled`** chạy trên thread pool; một task vô hạn không kiểm tra cờ ngắt sẽ chặn cả pool khi shutdown. Luôn viết loop `while (!Thread.currentThread().isInterrupted())`.
- Từ Spring Boot 3.2 + Java 21, **Virtual Threads** (`spring.threads.virtual.enabled=true`) thay đổi cách thread bị block (block không còn "đắt"), nhưng **mô hình vòng đời và interrupt vẫn y nguyên** — kiến thức hôm nay không lỗi thời.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta mô phỏng **vòng đời một thread đóng phiên đấu giá có thời hạn**. Một thread đếm ngược tự đóng phiên sau N giây (dùng `sleep`), nhưng có thể bị **interrupt** để hủy phiên sớm. Ta in trạng thái thread suốt quá trình.

**Nhiệm vụ Day 17:**
1. Viết class `Auction` có thời hạn (giây) và trạng thái `OPEN/CLOSED/CANCELLED`.
2. Viết một `Thread` đếm ngược đóng phiên; quan sát `TIMED_WAITING` khi nó đang chờ.
3. Cho phép `interrupt()` để hủy phiên sớm, dừng sạch sẽ (không dùng `stop()`).

```java
public class AuctionLifecycleDemo {

    enum AuctionStatus { OPEN, CLOSED, CANCELLED }

    static class Auction {
        final String name;
        final int durationSeconds;
        volatile AuctionStatus status = AuctionStatus.OPEN;

        Auction(String name, int durationSeconds) {
            this.name = name;
            this.durationSeconds = durationSeconds;
        }
    }

    public static void main(String[] args) throws InterruptedException {
        Auction auction = new Auction("iPhone 15 Pro", 5);

        Thread closer = new Thread(() -> {
            System.out.println("[Closer] Phiên '" + auction.name + "' mở, đóng sau "
                    + auction.durationSeconds + "s.");
            try {
                for (int s = auction.durationSeconds; s > 0; s--) {
                    System.out.println("[Closer] Còn lại " + s + "s...");
                    Thread.sleep(1000);   // mỗi giây -> TIMED_WAITING
                }
                auction.status = AuctionStatus.CLOSED;
                System.out.println("[Closer] HẾT GIỜ → phiên CLOSED. Chốt người thắng!");
            } catch (InterruptedException e) {
                // Bị hủy sớm: cờ đã bị xóa, đặt lại rồi dọn dẹp
                auction.status = AuctionStatus.CANCELLED;
                Thread.currentThread().interrupt();
                System.out.println("[Closer] Bị HỦY sớm → phiên CANCELLED. Hoàn tiền bid.");
            }
        }, "auction-closer");

        System.out.println("Trạng thái thread trước start: " + closer.getState()); // NEW
        closer.start();

        Thread.sleep(2500);  // theo dõi giữa chừng
        System.out.println(">> Kiểm tra: thread = " + closer.getState()
                + ", phiên = " + auction.status);   // TIMED_WAITING, OPEN

        // Đổi cờ huySom để thử 2 kịch bản
        boolean huySom = true;
        if (huySom) {
            System.out.println(">> Admin hủy phiên sớm, gửi interrupt()");
            closer.interrupt();
        }

        closer.join();
        System.out.println("Trạng thái thread cuối: " + closer.getState()  // TERMINATED
                + ", phiên = " + auction.status);
    }
}
```

> 🧩 Thử cả hai kịch bản: để `huySom = false` xem phiên đóng tự nhiên (`CLOSED` sau 5s), và `huySom = true` để hủy sớm (`CANCELLED` ở giây thứ ~2.5). Cùng một thread, cùng cơ chế `interrupt()` — đó là cách dừng thread **đúng chuẩn production**.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Liệt kê 6 trạng thái của `Thread.State` và ý nghĩa.**
> **Đáp:** `NEW` (đã tạo, chưa `start`), `RUNNABLE` (đang chạy hoặc sẵn sàng chạy), `BLOCKED` (chờ lock vào `synchronized`), `WAITING` (chờ vô hạn — `wait()`/`join()`/`park()`), `TIMED_WAITING` (chờ có hạn — `sleep(t)`/`wait(t)`/`join(t)`), `TERMINATED` (đã chạy xong).

**Q2: Khác nhau giữa `sleep()` và `wait()`?**
> **Đáp:** `sleep()` là static, **không nhả lock**, không cần `synchronized`, tự dậy khi hết giờ → `TIMED_WAITING`. `wait()` là method của Object, **phải gọi trong `synchronized`**, **nhả lock**, chờ `notify()`/`notifyAll()` đánh thức → `WAITING`/`TIMED_WAITING`.

**Q3: Vì sao Java gộp "running" và "ready" vào một trạng thái `RUNNABLE`?**
> **Đáp:** Vì việc một thread có thực sự chiếm CPU hay chỉ đang đợi scheduler xếp lịch do **OS** quyết định, không thuộc tầm kiểm soát của JVM. JVM chỉ cần biết thread "đủ điều kiện chạy" nên gộp cả hai thành `RUNNABLE`, không tách "running" riêng.

**Q4: Vì sao `wait()` luôn phải đặt trong vòng `while`?**
> **Đáp:** Chống **spurious wakeup** (thread tỉnh dậy vô cớ không ai notify) và xử lý trường hợp `notifyAll()` đánh thức nhiều thread nhưng chỉ một thread thỏa điều kiện. Vòng `while` buộc tái kiểm tra điều kiện mỗi khi tỉnh, đảm bảo chỉ chạy tiếp khi điều kiện thực sự đúng.

### Mức Senior

**Q5: Giải thích cơ chế `interrupt()`. Khi đang `sleep()` mà bị interrupt thì điều gì xảy ra với cờ ngắt?**
> **Đáp:** `interrupt()` chỉ **đặt cờ ngắt** lên `true`, không ép thread dừng — đây là mô hình hợp tác. Nếu thread đang `sleep()`/`wait()`/`join()`, JVM **ném `InterruptedException` và XÓA cờ** về `false`. Do đó trong `catch`, nếu muốn tầng trên biết đã có yêu cầu dừng, phải **gọi lại `Thread.currentThread().interrupt()`** để khôi phục cờ, hoặc ném lại exception. `isInterrupted()` đọc cờ không xóa; `Thread.interrupted()` đọc và xóa cờ.

**Q6: Vì sao `stop()`, `suspend()`, `resume()` bị deprecated? Thay thế bằng gì?**
> **Đáp:** `stop()` giết thread tức thì, để object dở dang ở trạng thái không nhất quán và nhả lock đột ngột → thread khác thấy dữ liệu hỏng. `suspend()` đóng băng thread **mà vẫn giữ lock** → thread khác cần lock đó deadlock. `resume()` dễ dính race condition khiến thread treo mãi. Thay thế: dùng `interrupt()` + cờ `volatile boolean` để thread tự dừng tại **điểm an toàn**.

**Q7: Một service Java treo, thread dump cho thấy nhiều thread ở `BLOCKED`. Bạn suy luận gì?**
> **Đáp:** `BLOCKED` nghĩa là các thread đó đang **chờ lấy monitor lock** để vào `synchronized` mà một thread khác đang giữ. Nhiều thread `BLOCKED` trên cùng một lock là dấu hiệu **lock contention** (tranh chấp khóa), hoặc tệ hơn là **deadlock** nếu có vòng chờ. Cần tìm thread đang **giữ** lock đó (thường đang `RUNNABLE` chạy lâu, hoặc cũng `BLOCKED`/`WAITING` ở chỗ khác) và kiểm tra thứ tự lấy lock để loại deadlock, hoặc thu hẹp phạm vi `synchronized`.

---

## ✅ Checklist hoàn thành

- [ ] Kể đủ 6 trạng thái `Thread.State` và mô tả khi nào vào/ra mỗi trạng thái
- [ ] Vẽ lại được sơ đồ chuyển trạng thái từ trí nhớ
- [ ] Giải thích rõ vì sao `sleep()` không nhả lock còn `wait()` thì có
- [ ] Viết đúng cặp `wait()`/`notifyAll()` trong `synchronized` với vòng `while`
- [ ] Dùng `join()` để gom kết quả nhiều thread con
- [ ] Viết được loop dừng sạch bằng `interrupt()` + xử lý `InterruptedException` đúng (đặt lại cờ)
- [ ] Giải thích được vì sao `stop()`/`suspend()`/`resume()` bị bỏ
- [ ] Hoàn thành Mini Project (cả hai kịch bản đóng tự nhiên và hủy sớm)
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Javadoc — `java.lang.Thread` và enum `Thread.State` (đọc kỹ phần mô tả từng state)
- Oracle Java Tutorials — "Concurrency: Thread Objects", "Interrupts", "Guarded Blocks"
- Baeldung — "Life Cycle of a Thread in Java", "How to Stop a Thread in Java"
- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 7 "Cancellation and Shutdown" (về interrupt)
- JEP 425 — Virtual Threads (đọc lướt để biết hướng tương lai của vòng đời thread)
- `man jstack` — đọc thread dump để soi trạng thái thread lúc production treo
