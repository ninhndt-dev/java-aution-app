# Day 21 - volatile

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã hiểu race condition (Day 19) và phân biệt sơ bộ visibility vs atomicity, giờ đào sâu **mô hình bộ nhớ Java** và từ khóa `volatile`.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **visibility** (khả kiến) — vấn đề tách biệt với atomicity: "thread B có **thấy** giá trị mới nhất mà A vừa ghi không?".
- Hiểu vì sao **cache CPU + reorder** khiến một thread đọc **giá trị cũ** (stale) của thread khác — bug "vòng lặp không bao giờ dừng".
- Nắm `volatile` đảm bảo **2 thứ**: (1) **visibility** (đọc/ghi thẳng bộ nhớ chính), (2) **cấm reorder** quanh nó (memory barrier / happens-before) — nhưng **KHÔNG** đảm bảo **atomicity**.
- Biết **khi nào `volatile` đủ** (cờ `boolean` dừng thread, publish tham chiếu bất biến) và **khi nào không đủ** (`count++` → phải dùng `Atomic*`).
- Hiểu **happens-before** — luật nền tảng của Java Memory Model (JMM).
- Hiểu **double-checked locking** vì sao **bắt buộc** `volatile`.
- So sánh `volatile` với `synchronized`.
- Liên hệ Laravel: vì sao bạn chưa từng nghĩ về "mô hình bộ nhớ" — vì PHP shared-nothing không có vấn đề này.

---

## 🧠 Lý thuyết cốt lõi

### 1. Visibility — vấn đề "thread B không thấy thứ A vừa ghi"

Ở Day 19 ta học atomicity (thao tác bị xen kẽ). Hôm nay là **vấn đề khác hẳn**: ngay cả khi không có xen kẽ, một thread có thể **không bao giờ nhìn thấy** thay đổi của thread khác.

> **Visibility:** sau khi thread A ghi một giá trị, liệu thread B đọc biến đó có chắc chắn thấy giá trị **mới nhất** không? Trong Java, **mặc định là KHÔNG có bảo đảm** — B có thể đọc mãi một giá trị **cũ** (stale).

Vì sao? Vì giữa thread và RAM còn nhiều tầng:

```
   Thread A (core 1)          Thread B (core 2)
   ┌───────────────┐          ┌───────────────┐
   │ Registers      │          │ Registers      │
   │ L1/L2 cache    │  ◄─?─►   │ L1/L2 cache    │   ← A ghi vào CACHE của nó,
   └───────┬───────┘          └───────┬───────┘     B đọc từ CACHE của B → lệch nhau!
           └──────────┬───────────────┘
                ┌──────────────┐
                │  RAM (chính) │
                └──────────────┘
```

A ghi `running = false` nhưng giá trị mới có thể chỉ nằm trong **cache của core 1**, chưa "xả" (flush) xuống RAM. B trên core 2 vẫn đọc `running = true` từ cache của nó → **B chạy mãi không dừng**.

> ⚠️ Đây là một trong những bug khó chịu nhất với người mới: bạn set một cờ để dừng một thread, nhưng thread đó **chạy mãi**. Lý do: trình tối ưu JIT có thể **nâng (hoist)** việc đọc biến ra ngoài vòng lặp (vì nó "thấy" trong cùng thread biến không đổi), biến `while(running)` thành `while(true)`. Không có `volatile`, JVM **được phép** làm vậy.

### 2. Reordering — JVM/CPU sắp xếp lại lệnh

Để tối ưu, **compiler, JIT và CPU** đều có quyền **sắp xếp lại thứ tự lệnh** miễn là kết quả **trong một thread đơn** không đổi. Nhưng với **nhiều thread**, reorder làm lộ ra trạng thái "không thể xảy ra" theo trực giác:

```java
// Thread A:                  // Thread B:
data = 42;        // (1)       if (ready) {              // (3)
ready = true;     // (2)           System.out.println(data);  // (4)
                                }
```

Trực giác: nếu B thấy `ready == true` thì `data` chắc chắn = 42. **Sai!** Nếu không có rào cản bộ nhớ, lệnh (1) và (2) có thể bị đảo: `ready = true` chạy **trước** `data = 42`. Khi đó B có thể thấy `ready == true` nhưng `data == 0`. Đây là bug **publish dữ liệu chưa hoàn chỉnh**.

### 3. `volatile` giải quyết gì? — Visibility + cấm reorder

Khai báo một biến `volatile` ra lệnh cho JVM:

1. **Visibility:** mọi lần **đọc** `volatile` đọc thẳng từ **bộ nhớ chính** (không dùng giá trị cache cũ); mọi lần **ghi** `volatile` **flush ngay** xuống bộ nhớ chính. → Thread khác **thấy ngay** giá trị mới nhất.
2. **Cấm reorder (memory barrier):** chèn **hàng rào bộ nhớ** quanh truy cập `volatile`. Mọi lệnh **trước** lần ghi `volatile` không bị đẩy xuống sau nó; mọi lệnh **sau** lần đọc `volatile` không bị kéo lên trước. → Thiết lập quan hệ **happens-before**.

```
volatile ghi (store) ──► [StoreStore | StoreLoad barrier] ──► flush về RAM
volatile đọc  (load)  ──► [LoadLoad  | LoadStore barrier] ──► đọc tươi từ RAM
```

Áp dụng vào ví dụ mục 2: chỉ cần làm `ready` **`volatile`** là sửa được — vì ghi `data = 42` xảy ra **trước** ghi `ready = true` (cấm reorder), và khi B đọc `ready == true` (volatile) thì nó **bắt buộc** thấy mọi thứ A ghi trước đó, bao gồm `data = 42`.

> 💡 Ghi nhớ: `volatile` không chỉ làm biến đó "tươi", nó còn là một **rào cản** đảm bảo mọi ghi **trước** nó (kể cả biến thường) được nhìn thấy. Đây là cơ chế "piggyback": dùng một biến volatile để "publish" cả một cụm dữ liệu.

### 4. `volatile` KHÔNG đảm bảo atomicity — `count++` vẫn sai

Đây là sai lầm kinh điển. `volatile` lo **visibility**, **không** lo **atomicity**:

| Khái niệm | Câu hỏi | `volatile`? | `synchronized`/`Atomic`? |
|---|---|---|---|
| **Visibility** | "B có thấy giá trị mới nhất A ghi?" | ✅ Có | ✅ Có |
| **Ordering** (cấm reorder) | "Lệnh có bị đảo gây publish dở dang?" | ✅ Có | ✅ Có |
| **Atomicity** | "read-modify-write có bị xen kẽ?" | ❌ **KHÔNG** | ✅ Có |

```java
volatile int count = 0;
count++;   // VẪN LOST UPDATE! volatile không biến read-modify-write thành nguyên tử
```

`count++` vẫn là 3 bước (đọc–cộng–ghi). `volatile` đảm bảo mỗi bước đọc/ghi đều tươi, nhưng **hai thread vẫn có thể cùng đọc 5, cùng tính 6, cùng ghi 6** → mất 1 lần tăng. Visibility tốt cũng không cứu được vì vấn đề ở chỗ **3 bước rời rạc bị xen kẽ**.

```
volatile  →  visibility ✔   ordering ✔   atomicity ✘
                                          (count++ vẫn mất cập nhật)
```

### 5. Khi nào `volatile` ĐỦ?

`volatile` đủ khi **việc ghi không phụ thuộc giá trị cũ** (không phải read-modify-write). Hai trường hợp kinh điển:

**(a) Cờ `boolean` dừng thread** — ghi đơn lẻ, set thẳng:
```java
private volatile boolean running = true;
// Thread worker: while (running) { ... }
// Thread khác:   running = false;   // chỉ ghi đè, không đọc giá trị cũ → volatile đủ
```

**(b) Publish một tham chiếu tới object bất biến** — "đổi nguyên cả object":
```java
private volatile Config config;        // mỗi lần đổi config là gán một object Config MỚI, bất biến
public void reload() { config = loadFreshConfig(); }   // ghi đè tham chiếu → volatile đủ
```

> 💡 Quy tắc nhớ đời (nhắc lại từ Day 19): **`volatile` cho cờ/flag, atomic/lock cho bộ đếm/counter.** Nếu giá trị mới = hàm của giá trị cũ (`x = f(x)`) → KHÔNG dùng `volatile` đơn thuần; dùng `Atomic*` hoặc khóa.

### 6. Happens-before — luật nền của JMM

**Happens-before** là quan hệ trừu tượng của Java Memory Model: nếu hành động X **happens-before** hành động Y, thì mọi ghi của X **được Y nhìn thấy**. Các luật chính:

| Luật | Ý nghĩa |
|---|---|
| **Program order** | Trong cùng một thread, lệnh trước happens-before lệnh sau |
| **Volatile** | Ghi một biến `volatile` happens-before mọi lần đọc **sau đó** của biến đó |
| **Monitor lock** | `unlock` một monitor happens-before `lock` tiếp theo của cùng monitor (`synchronized`) |
| **Thread start** | `thread.start()` happens-before mọi hành động trong thread mới |
| **Thread join** | Mọi hành động trong thread happens-before `thread.join()` trả về |

> 💡 Cả `volatile`, `synchronized`, và `Atomic*` đều "đúng" vì chúng **thiết lập happens-before**. Đó là điểm chung sâu nhất: an toàn bộ nhớ trong Java = **xây đúng chuỗi happens-before** giữa ghi và đọc.

### 7. Double-Checked Locking — vì sao BẮT BUỘC `volatile`

Mẫu khởi tạo lazy singleton "hai lần kiểm tra" để tránh khóa mỗi lần truy cập:

```java
public class Singleton {
    private static volatile Singleton instance;   // ← BẮT BUỘC volatile!

    public static Singleton getInstance() {
        if (instance == null) {                    // kiểm tra 1 (không khóa, nhanh)
            synchronized (Singleton.class) {
                if (instance == null) {            // kiểm tra 2 (trong khóa, chắc chắn)
                    instance = new Singleton();    // ← điểm nguy hiểm
                }
            }
        }
        return instance;
    }
}
```

**Vì sao cần `volatile`?** `instance = new Singleton()` **không nguyên tử**, gồm 3 bước có thể bị **reorder**:
```
1. cấp phát bộ nhớ cho object
2. gọi constructor (khởi tạo các field)
3. gán địa chỉ vào instance
```
Nếu bước 3 bị đảo lên **trước** bước 2, một thread khác (ở "kiểm tra 1", không khóa) có thể thấy `instance != null` nhưng object **chưa được constructor khởi tạo xong** → trả về một object **dở dang** → bug nửa-khởi-tạo. `volatile` **cấm reorder** này, đảm bảo khi `instance != null` thì object đã hoàn chỉnh.

> ⚠️ Trước Java 5, mẫu này **hỏng kể cả có `volatile`** (JMM cũ yếu). Từ Java 5+, `volatile` mới đủ mạnh để double-checked locking đúng. Ngày nay thường ưu tiên **holder idiom** (`static` inner class) hoặc `enum` singleton cho gọn, nhưng câu hỏi "vì sao DCL cần volatile" vẫn là kinh điển phỏng vấn.

### 8. `volatile` vs `synchronized`

| Tiêu chí | `volatile` | `synchronized` |
|---|---|---|
| Visibility | ✅ | ✅ |
| Ordering (cấm reorder) | ✅ | ✅ |
| Atomicity (read-modify-write) | ❌ | ✅ |
| Khóa lẫn nhau (mutual exclusion) | ❌ (không khóa) | ✅ |
| Chặn thread (blocking) | Không bao giờ | Có thể chặn/chờ |
| Chi phí | Rất nhẹ (chỉ rào bộ nhớ) | Nặng hơn (vào/ra monitor) |
| Dùng cho | Cờ, publish tham chiếu bất biến | Vùng tới hạn, bảo vệ nhiều biến/bất biến phức |

> 💡 `volatile` là "đồng bộ nhẹ": chỉ visibility + ordering, **không** mutual exclusion. Khi cần bảo vệ một **chuỗi thao tác** hay nhiều biến cùng lúc → phải `synchronized`/`Lock`. Khi chỉ cần "một thread set, các thread khác thấy ngay" → `volatile` rẻ và đủ.

---

## 🔁 Đối chiếu với Laravel/PHP

Đây là chủ đề mà dân PHP **gần như chưa bao giờ chạm tới**, và lý do rất sâu sắc:

**PHP shared-nothing → không có "mô hình bộ nhớ chia sẻ".** Mỗi request là một process riêng, biến sống trong RAM riêng và chết khi request xong. Không có chuyện "thread B đọc biến của thread A" → **không tồn tại** vấn đề visibility/reorder in-memory. Vì vậy:
- Bạn chưa từng phải nghĩ "biến này có cần `volatile` không".
- Không có khái niệm "cache CPU đọc giá trị cũ của thread khác" trong code PHP đời thường.

| Khía cạnh | Java | Laravel/PHP |
|---|---|---|
| Mô hình bộ nhớ | Heap **chia sẻ** giữa nhiều thread → cần JMM, `volatile`, happens-before | Mỗi request một vùng nhớ riêng → không có vấn đề |
| "Thấy giá trị mới nhất" | Phải đảm bảo bằng `volatile`/khóa | Mặc định luôn thấy (chỉ có một thread/process đọc-ghi biến của mình) |
| Chia sẻ state giữa request | Qua field singleton → nguy hiểm | Qua **DB/Redis/cache** → có cơ chế nhất quán riêng của storage |
| Cờ "tắt service / bật/tắt tính năng" | `volatile boolean` trong process | Cờ trong DB/Redis (`Cache::get('feature_x')`), poll mỗi request |

> 🧩 Insight chuyển ngữ: trong Laravel, "cờ đóng/mở một tính năng" là một row/key trong DB/Redis, mỗi request đọc lại. Trong Java một process, cờ đó là một `volatile boolean` trong RAM mà mọi thread đọc trực tiếp. PHP "ngoại hóa" state ra storage (luôn nhất quán nhờ storage), còn Java giữ state trong RAM (phải tự lo nhất quán bằng `volatile`/atomic/khóa). Khi Java **scale nhiều instance**, cờ in-memory lại không đủ → quay về đúng kiểu Laravel: đẩy cờ ra Redis/DB cho mọi instance cùng thấy.

> ⚠️ Bẫy chuyển ngữ: thói quen "đặt biến static rồi sửa thoải mái" của PHP (vì process chết là xong) cực nguy ở Java. Một `static boolean flag` không `volatile` trong Spring bean có thể bị một thread set mà thread khác **không bao giờ thấy** → bug "tắt mãi không tắt".

---

## 💻 Thực hành code

### (a) BUG visibility — thread không bao giờ dừng (thiếu `volatile`)

```java
public class VisibilityBug {
    // KHÔNG volatile → thread worker có thể không bao giờ thấy stopRequested = true
    private static boolean stopRequested = false;

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            long i = 0;
            while (!stopRequested) {        // JIT có thể "hoist" thành while(true)!
                i++;                        // vòng lặp nóng, không có điểm đồng bộ
            }
            System.out.println("Worker dừng sau " + i + " vòng");
        });
        worker.start();

        Thread.sleep(1000);                 // để worker chạy 1 giây
        stopRequested = true;               // main set cờ dừng
        System.out.println("Main đã yêu cầu dừng...");
        // Trên nhiều JVM/CPU: worker VẪN CHẠY MÃI, không in "Worker dừng" → bug visibility
    }
}
```

### (b) SỬA bằng `volatile`

```java
public class VisibilityFixed {
    private static ___ boolean stopRequested = false;   // Điền từ khóa để đảm bảo visibility

    public static void main(String[] args) throws InterruptedException {
        Thread worker = new Thread(() -> {
            long i = 0;
            while (!stopRequested) { i++; }     // giờ mỗi lần đọc đều TƯƠI từ bộ nhớ chính
            System.out.println("Worker dừng sau " + i + " vòng");
        });
        worker.start();
        Thread.sleep(1000);
        stopRequested = true;                   // ghi flush ngay → worker thấy & dừng
        System.out.println("Main đã yêu cầu dừng...");
        worker.join();                          // worker dừng đúng → chương trình kết thúc
    }
}
```

### (c) MINH HỌA `volatile` KHÔNG cứu `count++`

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class VolatileNotAtomic {
    static ___ int volatileCount = 0;             // Điền từ khóa visibility
    static final ___ atomicCount = new ___(0); // Điền class đếm nguyên tử

    public static void main(String[] args) throws Exception {
        final int THREADS = 10, LOOPS = 100_000;
        final int EXPECTED = THREADS * LOOPS;          // 1_000_000

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            CountDownLatch done = new CountDownLatch(THREADS);
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    for (int i = 0; i < LOOPS; i++) {
                        volatileCount++;                // ← read-modify-write → LOST UPDATE
                        atomicCount.___();  // Điền phương thức tăng nguyên tử
                    }
                    done.countDown();
                });
            }
            done.await();
        }
        System.out.printf("volatile : kỳ vọng=%d | thực tế=%d  → %s%n",
                EXPECTED, volatileCount, volatileCount == EXPECTED ? "OK" : "❌ MẤT cập nhật");
        System.out.printf("atomic   : kỳ vọng=%d | thực tế=%d  → %s%n",
                EXPECTED, atomicCount.get(), atomicCount.get() == EXPECTED ? "OK" : "SAI");
    }
}
```

Kết quả: `volatile` ra số **nhỏ hơn 1_000_000** và **khác nhau mỗi lần** (bằng chứng visibility ≠ atomicity); `atomic` luôn = 1_000_000.

### (d) Publish an toàn object bất biến qua `volatile`

```java
public class ConfigHolder {
    // Đổi cấu hình = gán nguyên một object Config MỚI, bất biến → volatile đủ để publish an toàn
    private ___ Config current; // Điền từ khóa publish an toàn

    public ConfigHolder(Config initial) { this.current = initial; }
    public Config get()            { return current; }     // đọc tươi
    public void reload(Config c)   { this.current = c; }    // ghi đè tham chiếu (atomic cho reference)

    // Config phải IMMUTABLE: mọi field final, không setter
    public record Config(int maxBid, long sessionMillis) {}
}
```

> 💡 Lý do an toàn: gán một **tham chiếu** (reference) trong Java vốn đã nguyên tử (trừ `long`/`double` không volatile trên vài nền tảng cũ). `volatile` thêm visibility + happens-before, đảm bảo khi thread khác đọc `current`, nó thấy object `Config` **đã khởi tạo xong** (immutable nên không ai sửa được sau đó). Đây là mẫu "copy-on-write reference" rất hợp cho hot-reload cấu hình.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng `volatile` cho bộ đếm.** `volatile int count; count++;` **vẫn lost update**. `volatile` chỉ lo visibility, không lo atomicity. Dùng `AtomicInteger`.
- **Quên `volatile` cho cờ dừng thread.** `boolean running` không volatile → thread có thể chạy mãi (JIT hoist hoặc cache cũ). Triệu chứng: "set cờ rồi mà thread không dừng".
- **Tưởng `volatile` thay được `synchronized`.** `volatile` không khóa lẫn nhau, không bảo vệ một **chuỗi** thao tác hay nhiều biến liên quan. Cần bất biến phức → dùng khóa.
- **`volatile` trên mảng/object khả biến tưởng bảo vệ nội dung.** `volatile int[] arr` chỉ làm **tham chiếu** `arr` volatile, **không** làm việc ghi `arr[i] = x` có visibility/atomicity. Phần tử bên trong vẫn cần đồng bộ riêng (hoặc dùng `AtomicIntegerArray`).
- **Double-checked locking không có `volatile`.** Có thể trả về object nửa-khởi-tạo do reorder. Trường `instance` phải `volatile` (Java 5+).
- **Lạm dụng `volatile` cho mọi thứ "cho chắc".** Mỗi truy cập volatile có rào bộ nhớ, mất một phần tối ưu; với biến chỉ-một-thread-dùng thì thừa.
- **Nghĩ `volatile` đảm bảo `a` và `b` được cập nhật cùng lúc.** Hai biến volatile riêng lẻ vẫn có thể bị đọc lệch pha (đọc `a` mới, `b` cũ). Muốn cập nhật nhiều biến như một khối nhất quán → gói vào một object bất biến rồi publish qua một tham chiếu volatile, hoặc dùng khóa.

---

## 🚀 Liên hệ Spring Boot / Production

- **Cờ bật/tắt (feature flag, graceful shutdown) trong một process.** `volatile boolean acceptingRequests` cho phép một thread (signal handler / actuator endpoint) set, mọi worker thread thấy ngay để ngừng nhận việc mới. Đây là nền của **graceful shutdown**.
- **Hot-reload cấu hình.** Mẫu (d): giữ một `volatile` reference tới object cấu hình **bất biến**; khi config đổi, build object mới rồi gán đè. Mọi request đọc cấu hình "tươi" mà không cần khóa. Spring `@RefreshScope` (Spring Cloud) làm điều tương tự ở mức cao hơn.
- **Lazy singleton / bean khởi tạo nặng.** Spring tự quản singleton lifecycle nên bạn ít phải tự viết DCL; nhưng khi tự cache client/connection theo nhu cầu, nhớ holder idiom hoặc `computeIfAbsent` (Day 19) thay vì DCL tay.
- **Đừng dùng `volatile` cho metrics/counter.** Đếm request/lỗi là read-modify-write → dùng `LongAdder`/`AtomicLong`/Micrometer `Counter`, không phải `volatile long`.
- **Scale nhiều instance.** `volatile` chỉ có ý nghĩa **trong một JVM**. Khi chạy nhiều pod, cờ in-memory không đồng bộ giữa các instance → đẩy cờ ra **Redis/DB/config server** để mọi instance cùng thấy (đúng mô hình Laravel feature flag).
- **64-bit `long`/`double` không volatile.** Trên một số nền tảng, đọc/ghi một `long`/`double` thường có thể bị "xé đôi" (word tearing) — đọc nửa cũ nửa mới. Khai báo `volatile long`/`volatile double` đảm bảo đọc/ghi nguyên tử 64-bit (nhưng vẫn không làm `++` nguyên tử).

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 19–20. Một phiên đấu giá có trạng thái **mở** hay **đóng**: khi đang mở thì nhận bid, khi đóng thì từ chối mọi bid mới. Cờ "đóng/mở" này được **một thread (bộ hẹn giờ kết thúc phiên)** set, nhưng **rất nhiều thread xử lý bid** phải thấy ngay. Đây chính là use-case kinh điển của `volatile` — và là chỗ người mới hay quên dẫn tới "vẫn nhận bid sau khi phiên đã đóng".

### Bước 1 — Cờ phiên đấu giá bằng `volatile`

```java
import java.util.concurrent.atomic.AtomicReference;

public class AuctionSession {
    // Cờ đóng/mở: 1 thread set (khi hết giờ), N thread đọc (khi đặt giá) → volatile là CHUẨN
    private ___ boolean open = true; // Điền từ khóa visibility

    // Giá cao nhất: read-modify-write có điều kiện → KHÔNG dùng volatile, dùng AtomicReference (Day 19)
    private final ___<Bid> highest = new ___<>(new Bid("init", 0)); // Điền class wrapper nguyên tử

    public record Bid(String bidder, long amount) {}   // bất biến

    /** Đặt giá: chỉ chấp nhận khi phiên còn MỞ và giá cao hơn. */
    public boolean placeBid(Bid bid) {
        if (!open) return false;                        // đọc cờ volatile → thấy ngay khi đã đóng
        Bid cur;
        do {
            if (!open) return false;                    // kiểm tra lại: phiên có thể vừa đóng
            cur = highest.get();
            if (bid.amount() <= cur.amount()) return false;
        } while (!highest.___(cur, bid));     // Điền phương thức CAS
        return true;
    }

    /** Đóng phiên: gọi bởi thread hẹn giờ. Ghi volatile → mọi thread bid thấy ngay. */
    public void close() { open = false; }

    public boolean isOpen()   { return open; }
    public Bid getHighest()   { return highest.get(); }
}
```

### Bước 2 — Bộ hẹn giờ đóng phiên (một thread set cờ)

```java
import java.util.concurrent.*;

public class AuctionSessionDemo {
    public static void main(String[] args) throws Exception {
        AuctionSession session = new AuctionSession();

        // 1 thread hẹn giờ: 500ms sau thì ĐÓNG phiên (set cờ volatile)
        ___ timer = Executors.___(); // Điền class/method tạo thread pool hẹn giờ
        timer.___(session::close, 500, TimeUnit.MILLISECONDS); // Điền phương thức hẹn giờ

        // N thread liên tục đặt giá tăng dần
        long start = System.currentTimeMillis();
        try (ExecutorService bidders = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) {
                final int id = t;
                bidders.submit(() -> {
                    long price = id * 1000L + 1;
                    while (System.currentTimeMillis() - start < 1000) {  // bắn trong 1 giây
                        boolean ok = session.placeBid(new AuctionSession.Bid("u" + id, price));
                        if (!ok && !session.isOpen()) break;   // phiên đóng → ngừng
                        price += 8000;                          // nhảy bước để vẫn có giá cao hơn
                    }
                });
            }
        }
        timer.shutdown();
        System.out.println("Phiên đã đóng. Giá cao nhất chốt = " + session.getHighest());
        System.out.println("Còn mở? " + session.isOpen());   // false
    }
}
```

### Bước 3 — Điểm cần chứng minh

- **Nếu bỏ `volatile` khỏi `open`:** một số bidder thread có thể **không bao giờ thấy** `open = false` → tiếp tục đặt giá sau khi phiên đã đóng (bug nghiệp vụ nghiêm trọng: "chốt giá sai vì có bid lọt sau giờ đóng"). Thử bỏ `volatile` và chạy nhiều lần để quan sát.
- **Phân vai rõ ràng:** `open` dùng `volatile` (cờ, ghi đơn lẻ); `highest` dùng `AtomicReference` + CAS (read-modify-write có điều kiện). **Đúng công cụ cho đúng việc** — đây là bài học cốt lõi nối Day 19 và Day 21.

> 🧩 Liên hệ thực chiến: ngoài đời cờ "phiên đóng" thường nằm ở DB (`status = 'CLOSED'`) hoặc Redis, mỗi request kiểm tra. Khi gom xử lý trong một JVM, `volatile boolean` đóng đúng vai đó — một thread đổi trạng thái, mọi thread thấy ngay, không cần round-trip xuống storage.

**Nhiệm vụ Day 21:**
0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Chạy `VisibilityBug` (bản (a)). Nếu worker treo không dừng → bạn đã chứng kiến bug visibility thật. Sửa bằng `volatile` (bản (b)).
2. Chạy `VolatileNotAtomic` (bản (c)), ghi lại số `volatile` sai và `atomic` đúng qua nhiều lần.
3. Chạy Mini Project, thử **xóa** `volatile` ở `open` rồi chạy lại nhiều lần để quan sát bid "lọt" sau khi đóng.
4. Ghi `notes/day-21.md`: giải thích bằng lời của bạn 3 thứ `volatile` đảm bảo (visibility, ordering) và 1 thứ nó KHÔNG đảm bảo (atomicity), kèm khi nào dùng cờ vs counter.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `volatile` để làm gì?**
> **Đáp:** Đảm bảo **visibility** (mọi thread đọc/ghi biến đi thẳng bộ nhớ chính, thấy ngay giá trị mới nhất) và **cấm reorder** quanh truy cập biến (memory barrier, thiết lập happens-before). Nó **không** đảm bảo atomicity cho read-modify-write.

**Q2: `volatile` có làm `count++` an toàn không? Vì sao?**
> **Đáp:** **Không.** `count++` là read-modify-write 3 bước, hai thread vẫn có thể cùng đọc giá trị cũ rồi cùng ghi đè → lost update. `volatile` chỉ lo visibility/ordering, không lo atomicity. Đếm phải dùng `AtomicInteger`/khóa.

**Q3: Khi nào `volatile` là đủ?**
> **Đáp:** Khi việc ghi **không phụ thuộc giá trị cũ** — ví dụ một cờ `boolean running` (set thẳng `false`), hoặc publish một **tham chiếu tới object bất biến** (gán đè cả object). Đó là ghi đơn lẻ, không read-modify-write, nên `volatile` đủ.

**Q4: Phân biệt visibility và atomicity.**
> **Đáp:** **Visibility** = thread khác có thấy giá trị vừa ghi không (vấn đề cache/flush). **Atomicity** = một thao tác có bị xen kẽ giữa chừng không (vấn đề read-modify-write). `volatile` lo visibility (+ordering) nhưng không lo atomicity; `synchronized`/`Atomic*` lo cả hai.

### Mức Senior

**Q5: Giải thích `volatile` đảm bảo những gì ở mức Java Memory Model.**
> **Đáp:** Hai thứ: (1) **Visibility** — ghi volatile flush về bộ nhớ chính, đọc volatile đọc tươi từ đó, nên thread khác thấy ngay. (2) **Ordering** — chèn memory barrier cấm reorder: mọi ghi **trước** một ghi volatile được nhìn thấy bởi thread đọc volatile đó (luật happens-before của volatile). Nhờ (2), volatile có thể "publish" cả một cụm dữ liệu (piggyback): set cờ volatile cuối cùng đảm bảo mọi dữ liệu ghi trước đó hiển thị đầy đủ. Nó **không** cung cấp atomicity hay mutual exclusion.

**Q6: Happens-before là gì? Kể vài quan hệ.**
> **Đáp:** Là quan hệ trừu tượng của JMM: nếu X happens-before Y thì mọi ghi của X được Y nhìn thấy. Các luật chính: program order (trong một thread), volatile write happens-before mọi volatile read sau đó của cùng biến, monitor unlock happens-before lock kế tiếp, `thread.start()` happens-before mọi việc trong thread con, mọi việc trong thread happens-before `join()` trả về. An toàn bộ nhớ = xây đúng chuỗi happens-before giữa ghi và đọc.

**Q7: Trong double-checked locking, vì sao trường `instance` phải `volatile`?**
> **Đáp:** Vì `instance = new Singleton()` gồm 3 bước (cấp phát, chạy constructor, gán tham chiếu) có thể bị **reorder**: gán tham chiếu xảy ra trước khi constructor chạy xong. Một thread khác ở lần kiểm tra đầu (không khóa) có thể thấy `instance != null` nhưng object **chưa khởi tạo xong** → trả về object dở dang. `volatile` cấm reorder này (Java 5+), đảm bảo khi `instance != null` thì object đã hoàn chỉnh. (Thực tế thường ưu tiên holder idiom hoặc enum singleton.)

**Q8: So sánh `volatile` và `synchronized` về visibility, atomicity, chi phí.**
> **Đáp:** Cả hai đều cho **visibility** và **ordering** (đều thiết lập happens-before). Khác biệt: `synchronized` còn cho **atomicity** và **mutual exclusion** (chỉ một thread vào vùng khóa), có thể chặn/chờ và đắt hơn (vào/ra monitor). `volatile` **không** khóa, không atomic cho read-modify-write, nhưng rất nhẹ (chỉ rào bộ nhớ). Dùng `volatile` cho cờ/publish reference bất biến; dùng `synchronized` khi cần bảo vệ chuỗi thao tác hoặc nhiều biến liên quan.

**Q9: Vì sao bug visibility khó tái hiện, và làm sao test/debug?**
> **Đáp:** Vì nó phụ thuộc cache CPU, kiến trúc (x86 vs ARM — ARM yếu hơn nên lộ bug dễ hơn), mức tải, và việc JIT có hoist đọc biến hay không (thường lộ khi code đã chạy "ấm"). Trên máy dev x86 nhẹ tải có thể "ổn", lên production tải cao thì hỏng. Cách xử lý: review mọi shared mutable state xem có chiến lược đồng bộ nhất quán không; dùng công cụ như `jcstress` để dò interleaving/visibility; nguyên tắc thiết kế: mọi cờ/biến chia sẻ giữa thread phải `volatile`/atomic/khóa, ghi rõ chiến lược.

---

## ✅ Checklist hoàn thành

- [ ] Phân biệt rạch ròi visibility, ordering, atomicity bằng lời của mình
- [ ] Giải thích được vì sao thiếu `volatile` khiến thread "không bao giờ dừng"
- [ ] Hiểu reorder và vì sao publish dữ liệu chưa hoàn chỉnh có thể xảy ra
- [ ] Biết khi nào `volatile` đủ (cờ, publish reference bất biến) vs không đủ (counter)
- [ ] Giải thích happens-before và các luật chính của nó
- [ ] Giải thích vì sao double-checked locking cần `volatile`
- [ ] Tự chạy demo (a)(b)(c) thấy bug visibility và `volatile` không cứu `count++`
- [ ] Hoàn thành Mini Project: cờ `volatile` đóng/mở phiên đấu giá
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Sách *Java Concurrency in Practice* (Brian Goetz) — chương 3 "Sharing Objects" (visibility, publication, `volatile`) & chương 16 "The Java Memory Model"
- Java Language Specification — chương 17.4 "Memory Model" và 17.7 (happens-before, volatile)
- Oracle Java Tutorials — "Concurrency: Atomic Access" và phần về `volatile`
- Baeldung — "Guide to the volatile Keyword in Java", "Java Memory Model", "Double-Checked Locking"
- Aleksey Shipilëv — "Close Encounters of The Java Memory Model Kind" (bài blog kinh điển về JMM)
- `jcstress` (OpenJDK) — stress-test visibility/ordering để tự kiểm chứng
