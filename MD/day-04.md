# Day 04 - Garbage Collection

> **Giai đoạn:** Java Foundation & JVM
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Garbage Collector (GC)** quyết định object nào sống/chết dựa trên khái niệm **GC roots** và **reachability** (tính với tới được).
- Nắm thuật toán nền tảng **Mark – Sweep – Compact** và vì sao cần "compact".
- Hiểu **Weak Generational Hypothesis** → vì sao Heap chia **Young / Old**, phân biệt **Minor GC** và **Major/Full GC**.
- Biết **Stop-The-World (STW)** là gì, vì sao nó là kẻ thù của độ trễ (latency).
- Phân biệt các collector: **Serial, Parallel, G1, ZGC, Shenandoah** và biết khi nào dùng cái nào.
- Bật/đọc được **GC log** (`-Xlog:gc`), nhận diện **OutOfMemoryError** và **memory leak kiểu Java** — điều PHP gần như giấu bạn.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao Java cần Garbage Collector?

Trong C/C++ bạn tự `malloc`/`free`, quên `free` thì rò rỉ bộ nhớ, `free` hai lần thì crash. Java đi theo hướng khác: bạn chỉ việc `new`, còn **JVM tự thu hồi** object khi không ai dùng nữa. Bộ phận làm việc đó nằm trong JVM, gọi là **Garbage Collector**.

Câu hỏi cốt lõi GC phải trả lời: **"Object này còn ai dùng không?"** Java **không** đếm như PHP (xem mục đối chiếu), mà dùng cách **truy vết từ gốc** (tracing): bắt đầu từ một tập "gốc" rồi đi theo các tham chiếu. Object nào **đi tới được** thì còn sống; object nào **không đi tới được** (unreachable) là rác, sẽ bị thu hồi.

> 💡 GC quan tâm **reachability (với tới được)**, KHÔNG quan tâm "object có còn được dùng về mặt logic nghiệp vụ hay không". Một object bạn không bao giờ đụng tới nữa nhưng vẫn bị một biến nào đó tham chiếu → với GC nó vẫn "sống". Đây chính là mầm mống của **memory leak kiểu Java**.

### 2. GC roots — điểm xuất phát của việc truy vết

**GC roots** là tập các tham chiếu "luôn được coi là sống", JVM bắt đầu duyệt từ đây. Các loại GC root chính:

- **Biến local trên stack** của mọi **thread đang chạy** (tham số, biến cục bộ trong method đang thực thi).
- **Biến `static`** của các class đã được nạp (sống suốt vòng đời class loader).
- **JNI references** — object được giữ bởi code native (qua Java Native Interface).
- **Active threads** — bản thân các `Thread` đang chạy là root.
- (Còn vài loại khác như monitor đang `synchronized`, nhưng 4 loại trên là đủ để hiểu bản chất.)

Mọi object **với tới được** từ GC roots tạo thành **object graph** sống. Phần còn lại là rác:

```
        GC ROOTS
   ┌───────────────────────────────────┐
   │ stack thread main:  user ─────────┼──►  User ───► Address  (sống)
   │ static FIELD:       CONFIG ───────┼──►  Config ───► Map     (sống)
   │ JNI ref / threads:  ...           │
   └───────────────────────────────────┘
                                            Order ───► Item      (RÁC: không root nào với tới)
                                              ▲   không ai trỏ vào
                                              └── unreachable → GC thu hồi
```

> 💡 Khi method `main` chạy xong và biến `user` ra khỏi scope, tham chiếu từ stack mất → nếu không còn root nào khác trỏ vào `User`, nó trở thành rác ở lần GC kế tiếp.

### 3. Thuật toán nền tảng: Mark – Sweep – Compact

Hầu hết collector của Java đều dựa trên 3 bước này:

1. **Mark (đánh dấu):** Bắt đầu từ GC roots, duyệt toàn bộ object graph và **đánh dấu** mọi object với tới được là "sống". Bước này phải duyệt cả đồ thị nên là phần tốn công nhất.
2. **Sweep (quét/dọn):** Đi qua Heap, **thu hồi** mọi object KHÔNG được đánh dấu (rác), trả lại vùng nhớ đó cho JVM.
3. **Compact (nén):** Sau khi sweep, vùng nhớ bị **phân mảnh** (sống/chết xen kẽ). Compact **dồn các object sống về một đầu**, gom vùng trống thành một khối liền mạch → cấp phát object mới sau này nhanh (chỉ cần đẩy con trỏ "bump pointer").

```
Trước:   [A][rác][B][rác][rác][C]      ← phân mảnh, vùng trống rời rạc
Mark:    [A*][ ][B*][ ][ ][C*]         ← A,B,C được đánh dấu sống (*)
Sweep:   [A][ . ][B][ . ][ . ][C]      ← thu hồi rác, để lại "lỗ"
Compact: [A][B][C][ . . . . . . . ]    ← dồn sống về đầu, trống thành 1 khối
```

> ⚠️ Compact tốn chi phí (phải di chuyển object và cập nhật tham chiếu), nhưng nếu không compact thì Heap phân mảnh, cấp phát object lớn dễ thất bại dù tổng vùng trống vẫn đủ.

### 4. Weak Generational Hypothesis — vì sao chia Young/Old

Quan sát thực tế qua hàng chục năm: **đa số object chết rất trẻ** (object tạm: biến vòng lặp, DTO, kết quả trung gian...), còn số ít object sống lâu thì có xu hướng sống rất lâu. Đó là **Weak Generational Hypothesis**.

Tận dụng điều này, Heap chia theo **thế hệ (generation)**:

```
HEAP
┌──────────────────────── Young Generation ────────────────────────┐  ┌──── Old Gen ────┐
│   Eden          │  Survivor S0  │  Survivor S1                    │  │  (Tenured)       │
│ (object mới sinh)│ (sống sót 1) │ (sống sót, luân phiên copy)      │  │ object sống lâu  │
└──────────────────────────────────────────────────────────────────┘  └──────────────────┘
        object mới ──► Eden ──(sống sót Minor GC)──► Survivor ──(đủ "tuổi")──► Old (promote)
```

- **Eden:** nơi object mới `new` ra đời. Đa số chết tại đây trước cả lần GC đầu.
- **Survivor (S0/S1):** chứa object sống sót qua một vài lần Minor GC; mỗi lần sống sót tăng "tuổi".
- **Old Gen (Tenured):** object đủ già được **promote (thăng cấp)** lên đây.
- **Metaspace** (ngoài Heap): chứa metadata của class, không phải nơi chứa object thường — nhưng cũng có thể OOM riêng (xem mục OutOfMemoryError).

### 5. Minor GC vs Major / Full GC

| | **Minor GC** | **Major / Full GC** |
|---|---|---|
| Dọn vùng | Young Gen (Eden + Survivor) | Old Gen (Full GC = cả Young + Old + đôi khi Metaspace) |
| Tần suất | Thường xuyên | Hiếm hơn |
| Tốc độ | **Nhanh** (Young nhỏ, ít object sống) | **Chậm** (Old lớn, phải mark cả đồ thị lớn) |
| Hệ quả | Promote object sống sót sang Old | STW lâu hơn nhiều, dễ gây "khựng" app |

> 💡 Vì Young Gen nhỏ và đa số object đã chết, Minor GC chỉ phải **copy số ít object còn sống** sang Survivor/Old → rất nhanh. Đây là lợi ích cốt lõi của việc chia thế hệ.

> ⚠️ **Full GC liên tục** là tín hiệu nguy hiểm trong production: thường do Old Gen đầy mà GC không giải phóng được mấy (rò rỉ bộ nhớ, hoặc Heap quá nhỏ). Theo dõi log để phát hiện sớm.

### 6. Stop-The-World (STW)

Khi GC thực hiện những pha "nhạy cảm" (đặc biệt lúc mark/compact), JVM phải **tạm dừng TOÀN BỘ thread ứng dụng** để object graph không thay đổi giữa chừng. Đó là **Stop-The-World**.

```
App threads: ▓▓▓▓▓▓▓ [████ STW: app đứng hình ████] ▓▓▓▓▓▓▓
                       └ GC chạy, mọi request bị "treo" ─┘
```

- **Pause time (thời gian dừng)** quyết định **latency**. Với API, một STW 2 giây = mọi request trong 2 giây đó bị treo → timeout, p99 xấu.
- Toàn bộ lịch sử phát triển GC hiện đại là cuộc đua **giảm STW**: từ Serial (STW dài) → Parallel → G1 (STW có mục tiêu) → ZGC/Shenandoah (STW < 1ms).

### 7. Các collector — chọn cái nào?

| Collector | Cơ chế | Tối ưu cho | Khi nào dùng |
|---|---|---|---|
| **Serial** (`-XX:+UseSerialGC`) | 1 thread, STW toàn phần | Đơn giản, ít overhead | App nhỏ, container 1 CPU / ít RAM, batch ngắn |
| **Parallel / Throughput** (`-XX:+UseParallelGC`) | Nhiều thread GC, vẫn STW | **Throughput** (tổng việc/giây) | Job tính toán nặng, batch, chấp nhận pause để đạt thông lượng tối đa |
| **G1** (`-XX:+UseG1GC`) | Chia Heap thành **region**, dọn region nhiều rác trước ("Garbage First") | Cân bằng throughput & **pause có kiểm soát** | **Mặc định từ Java 9.** Web service phổ thông, Heap trung-lớn |
| **ZGC** (`-XX:+UseZGC`) | Phần lớn việc làm **đồng thời (concurrent)** với app | **Latency cực thấp**, pause **< 1ms** | Heap rất lớn (hàng chục GB→TB), yêu cầu latency khắt khe |
| **Shenandoah** (`-XX:+UseShenandoahGC`) | Concurrent compaction | Latency thấp, pause gần như không phụ thuộc kích thước Heap | Tương tự ZGC (phổ biến trên bản OpenJDK/Red Hat) |

- **G1** có flag đặc trưng: `-XX:MaxGCPauseMillis=200` — bạn đặt **mục tiêu pause**, G1 cố gắng (không bảo đảm tuyệt đối) tôn trọng bằng cách chọn dọn vừa đủ số region mỗi lần.
- **ZGC / Shenandoah** đánh đổi một ít throughput & CPU để đạt pause cực ngắn ngay cả với Heap khổng lồ.

> 💡 Quy tắc ngón tay cái: chưa biết chọn gì → để **G1 mặc định**. Latency là tối thượng và Heap lớn → cân nhắc **ZGC**. Chỉ chạy batch và muốn xong nhanh nhất → **Parallel**.

### 8. Đọc GC log (Unified Logging, Java 9+)

Từ Java 9, mọi log GC đi qua **Unified Logging** với flag `-Xlog`:

```bash
java -Xlog:gc        -Xmx256m -jar app.jar    # log GC gọn: mỗi lần GC 1 dòng
java -Xlog:gc*       -Xmx256m -jar app.jar    # chi tiết hơn (các pha con của GC)
java -Xlog:gc:file=gc.log -jar app.jar        # ghi log ra file gc.log
```

Một dòng log G1 điển hình đọc được:

```
[0,512s][info][gc] GC(7) Pause Young (Normal) (G1 Evacuation Pause) 52M->8M(256M) 3.114ms
                     │     │                                          │   │  │       │
                  số lần  loại GC (Minor=Young)              trước GC─┘   │  │   pause time
                                                                  sau GC ─┘  └─ tổng Heap
```

Đọc log để biết: GC chạy **bao lâu một lần**, **pause bao lâu**, Heap **trước→sau** có tụt nhiều không (nếu sau GC vẫn cao mãi → nghi rò rỉ).

> 💡 Flag chọn collector và flag bộ nhớ hay đi cùng nhau: `-Xms`/`-Xmx` (Heap nhỏ nhất/lớn nhất), `-XX:+UseG1GC`/`-XX:+UseZGC`/`-XX:+UseParallelGC`, `-Xlog:gc`.

### 9. OutOfMemoryError — không phải lỗi nào cũng giống nhau

`OutOfMemoryError` (OOM) là `Error`, không phải `Exception` — thường **không nên** cố `catch` để chạy tiếp. Các biến thể hay gặp:

- **`java.lang.OutOfMemoryError: Java heap space`** — Heap đầy, GC không giải phóng đủ. Nguyên nhân: Heap quá nhỏ, hoặc **rò rỉ bộ nhớ**.
- **`GC overhead limit exceeded`** — JVM dành **> 98% thời gian** chạy GC mà chỉ thu lại **< 2%** Heap. Triệu chứng kinh điển của leak: GC quay cuồng vô ích.
- **`Metaspace`** — vùng metadata class đầy (nạp quá nhiều class, ví dụ redeploy nhiều lần làm rò rỉ class loader, hoặc sinh class động). Không liên quan tới Heap object.

### 10. Memory leak kiểu Java — "có GC vẫn rò rỉ"

Nghịch lý: Java **có** GC nhưng vẫn **rò rỉ bộ nhớ được**. Vì GC chỉ thu hồi object **unreachable**. Nếu bạn vô tình giữ một tham chiếu **mãi mãi**, object không bao giờ unreachable → GC không bao giờ đụng tới nó:

```java
// LEAK kinh điển: collection static cứ phình mà không bao giờ dọn
public class EventBus {
    // static => là GC ROOT, sống suốt vòng đời class loader
    private static final List<byte[]> HISTORY = new ArrayList<>();

    public void publish(byte[] event) {
        HISTORY.add(event);   // chỉ ADD, không bao giờ REMOVE
    }                          // => mọi event giữ mãi => Heap phình => OOM
}
```

Các nguồn leak phổ biến: **`static` collection** cứ add không remove, **cache không giới hạn** (không TTL/size), listener đăng ký mà không hủy, `ThreadLocal` không `remove()` trong thread pool.

> ⚠️ Quy tắc vàng: bất cứ thứ gì **`static` + collection** đều phải tự hỏi *"ai và khi nào dọn nó?"*. Nếu câu trả lời là "không ai" → bạn vừa viết một leak.

---

## 🔁 Đối chiếu với Laravel/PHP

PHP và Java quản lý bộ nhớ theo hai triết lý khác hẳn nhau:

| Khía cạnh | PHP / Laravel | Java |
|---|---|---|
| Cơ chế chính | **Reference counting** (đếm tham chiếu): biến trỏ vào → refcount++, hết trỏ → refcount-- ; về 0 là giải phóng ngay | **Tracing GC**: truy vết từ GC roots, không đếm |
| Vòng tham chiếu (A↔B) | Refcount không về 0 → cần **cycle collector** (`gc_collect_cycles()`) dọn riêng | Tracing tự xử lý vòng vì chỉ xét "với tới được từ root" hay không |
| Hết request | **Giải phóng TẤT CẢ** bộ nhớ của request (process/worker reset) | Tiến trình chạy lâu, object còn root vẫn sống xuyên nhiều request |
| Tuning GC | Hiếm khi cần — "chết là sạch" sau mỗi request | **Rất quan trọng**: chọn collector, đặt Heap, đọc GC log, săn leak |
| Rò rỉ bộ nhớ | Hiếm gặp ở app web (mỗi request reset); chỉ lo ở worker chạy dài (queue) | Có thật, qua tham chiếu giữ mãi (static collection, cache vô hạn...) |

**Khác biệt tư duy quan trọng nhất:**
- PHP/Laravel: mỗi HTTP request **sinh ra rồi chết**, hết request là Zend Engine giải phóng sạch toàn bộ. Vì vậy lập trình viên PHP **hiếm khi lo GC tuning** hay memory leak ở tầng web.
- Java/Spring Boot: tiến trình **chạy liên tục hàng ngày/tháng**. Object tích lũy trong Heap, GC dọn nền. Vì vậy bạn **buộc phải** hiểu Minor/Full GC, STW, biết đọc GC log và nhận diện leak.

> 🧩 Hệ quả thực tế: ở Laravel bạn có thể nhét đủ thứ vào một biến `static`/singleton trong vòng đời request mà không sao (request chết là xong). Ở Java, một `static List` add hoài chính là **bom hẹn giờ OOM** — vì nó không bao giờ reset như PHP.

---

## 💻 Thực hành code

### Bài 1 — Tạo nhiều object để kích hoạt GC và quan sát log

```java
// File: GcDemo.java  (Java 21)
import java.util.ArrayList;
import java.util.List;

public class GcDemo {
    public static void main(String[] args) {
        // Mỗi vòng tạo ~1MB rồi VỨT đi (không giữ tham chiếu) => thành rác ngay.
        // GC sẽ phải dọn liên tục ở Young Gen (Minor GC).
        for (int i = 0; i < 50_000; i++) {
            byte[] tam = ___ byte[1024 * 1024]; // Điền từ khóa khởi tạo mảng
            tam[0] = 1;                          // chạm vào để JVM không tối ưu bỏ
            // hết vòng lặp: 'tam' ra khỏi scope => unreachable => rác
            if (i % 10_000 == 0) {
                System.out.println("Đã tạo " + i + " object 1MB...");
            }
        }
        System.out.println("Xong. Quan sát GC log phía trên.");
    }
}
```

Biên dịch và chạy với GC log + Heap nhỏ để GC chạy thường xuyên cho dễ thấy:

```bash
javac GcDemo.java
# Heap chỉ 64MB => buộc GC dọn liên tục; -Xlog:gc in mỗi lần GC một dòng
java -Xlog:gc -Xmx64m GcDemo

# Muốn thử collector khác:
java -Xlog:gc -Xmx64m -XX:+UseParallelGC GcDemo
java -Xlog:gc -Xmx64m -XX:+UseG1GC      GcDemo   # G1 là mặc định, nêu rõ cho chắc
```

Bạn sẽ thấy hàng loạt dòng `Pause Young` (Minor GC) với pattern `XXM->YYM(64M)` — Heap phình lên rồi tụt xuống mỗi lần GC dọn rác. Đó chính là **chia thế hệ + Minor GC** đang hoạt động.

> ✅ **Bài tập tự giải thích:** Vì sao gần như **không** thấy `Full GC` ở ví dụ này? (Gợi ý: object chết trẻ, hầu hết được dọn ngay ở Young Gen, rất ít promote sang Old.)

### Bài 2 — Cố tình tạo memory leak tới khi OutOfMemoryError

```java
// File: LeakDemo.java  (Java 21)
import java.util.ArrayList;
import java.util.List;

public class LeakDemo {
    // static => GC ROOT, sống mãi. Cứ add mà KHÔNG remove => leak.
    private ___ final List<byte[]> LEAK = new ArrayList<>(); // Điền từ khóa tĩnh để biến thuộc về class

    public static void main(String[] args) {
        int mb = 0;
        try {
            while (true) {
                LEAK.add(new byte[1024 * 1024]); // giữ tham chiếu mãi mãi
                mb++;
                if (mb % 50 == 0) {
                    System.out.println("Đang giữ " + mb + " MB (không bao giờ dọn được)...");
                }
            }
        } ___ (OutOfMemoryError e) { // Điền từ khóa bắt ngoại lệ
            // Bắt để in minh hoạ; thực tế KHÔNG nên catch OOM để chạy tiếp.
            System.out.println("💥 OutOfMemoryError sau khi giữ ~" + mb + " MB");
            System.out.println("Lý do: object reachable từ static LEAK => GC không thu hồi được.");
        }
    }
}
```

```bash
javac LeakDemo.java
java -Xmx128m LeakDemo
# => in dần rồi: java.lang.OutOfMemoryError: Java heap space (~128MB)
```

So sánh hai bài: cùng tạo object liên tục, nhưng **Bài 1 sống khoẻ** (vứt tham chiếu → GC dọn), **Bài 2 chết OOM** (giữ tham chiếu trong static → GC bó tay). Đây là bài học cốt lõi: **GC mạnh đến đâu cũng không cứu được tham chiếu bạn cố tình giữ.**

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng "Java có GC nên không bao giờ leak".** Sai — GC chỉ dọn object **unreachable**. Giữ tham chiếu (static collection, cache vô hạn) = leak chắc chắn.
- **Gọi `System.gc()` trong code production.** Đây chỉ là **gợi ý**, JVM có thể bỏ qua; tệ hơn nó thường ép một **Full GC (STW)** không đúng lúc, làm app khựng. Để JVM tự quyết. Có thể chặn hẳn bằng `-XX:+DisableExplicitGC`.
- **Cố `catch (OutOfMemoryError e)` để app chạy tiếp.** OOM là `Error`, JVM thường đã ở trạng thái không lành; nên để app chết và restart thay vì cố sống dở.
- **Đặt `-Xmx` quá nhỏ "cho tiết kiệm".** Heap chật → GC chạy quá thường xuyên → tốn CPU, dễ `GC overhead limit exceeded`. Quá lớn cũng không tốt: pause Full GC dài hơn.
- **Đặt finalizer/`finalize()` để "dọn dẹp".** `finalize()` đã **deprecated**, chạy không xác định thời điểm, còn làm chậm GC. Dùng `try-with-resources`/`AutoCloseable` để giải phóng tài nguyên.
- **Quên `ThreadLocal.remove()` trong thread pool.** Thread sống lâu (pool) giữ giá trị ThreadLocal mãi → leak âm thầm.
- **Nhầm Metaspace với Heap.** OOM `Metaspace` không sửa bằng tăng `-Xmx`; nó liên quan số lượng class được nạp.

---

## 🚀 Liên hệ Spring Boot / Production

- App **Spring Boot** là tiến trình **long-running**: bean Singleton, cache, connection pool... sống suốt vòng đời app. Mọi tham chiếu giữ trong chúng đều **không reset như Laravel** → phải để mắt tới bộ nhớ.
- Chạy production thường set flag bộ nhớ + collector + log:
  ```bash
  java -Xms512m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
       -Xlog:gc:file=/var/log/app/gc.log -jar app.jar
  ```
- **Trong container (Docker/K8s):** JVM hiện đại tự nhận giới hạn cgroup, nhưng nên kiểm soát bằng `-XX:MaxRAMPercentage=75` thay cho `-Xmx` cứng để Heap co giãn theo RAM cấp cho pod. Đặt `-Xmx` sát limit pod dễ bị **OOMKilled** (kernel giết tiến trình, khác với OOM của JVM).
- **Quan sát (observability):** Spring Boot Actuator + Micrometer xuất metric `jvm_gc_pause_seconds`, `jvm_memory_used_bytes`... lên Prometheus/Grafana. Dashboard GC pause & Heap usage là thứ phải có trong production.
- **Săn leak khi đã xảy ra:** chụp **heap dump** (`jmap -dump:live,format=b,file=heap.hprof <pid>` hoặc tự dump khi OOM bằng `-XX:+HeapDumpOnOutOfMemoryError`), rồi mở bằng **Eclipse MAT** xem "dominator tree" để tìm ai giữ nhiều bộ nhớ nhất.
- **Chọn collector theo SLA:** API latency-critical, Heap lớn → cân nhắc **ZGC**; service phổ thông → **G1** mặc định là đủ; batch xử lý nặng → **Parallel**.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 03 (đã có `Auction` và `Bid` cùng validation). Hôm nay ta soi **vòng đời object trong bộ nhớ** và phòng leak.

**Bối cảnh:** Mỗi `Auction` nhận rất nhiều `Bid`. Một thiết kế ngây thơ giữ **toàn bộ lịch sử mọi lượt đấu giá** trong một collection `static` để "tiện thống kê":

```java
// ⚠️ THIẾT KẾ LEAK: static => GC root, không bao giờ dọn => Heap phình theo thời gian
public class BidHistory {
    private static final List<Bid> ALL_BIDS = new ArrayList<>();   // sống mãi!
    public static void record(Bid bid) { ALL_BIDS.add(bid); }      // chỉ add
}
```

Vấn đề: auction đã **kết thúc** từ lâu nhưng mọi `Bid` của nó vẫn reachable qua `ALL_BIDS` → GC không thu hồi → sau vài tháng chạy = OOM.

**Cách phòng tránh (chọn theo yêu cầu):**
1. **Dọn khi auction kết thúc:** khi `Auction` đóng, xoá Bid khỏi vùng "đang hoạt động" (đẩy sang DB/data warehouse nếu cần lưu lâu dài, đừng giữ trong RAM).
2. **Giới hạn lịch sử trong RAM:** chỉ giữ N lượt bid gần nhất mỗi auction (ví dụ dùng cấu trúc bounded/ring buffer), phần cũ ghi xuống storage.
3. **Cache có TTL/size:** thay `static List` bằng cache có hạn mức, ví dụ Caffeine:
   ```java
   Cache<Long, List<Bid>> recentBids = Caffeine.newBuilder()
       .maximumSize(10_000)                      // giới hạn số entry
       .expireAfterWrite(Duration.ofHours(1))    // tự hết hạn => entry trở thành unreachable
       .build();
   ```
   Khi entry hết hạn/bị evict, không còn tham chiếu giữ → GC thu hồi tự nhiên.

**Nhiệm vụ Day 04:**
1. Vẽ **object graph**: từ GC root nào (`static`/biến local trong service) các `Bid` đang được giữ? Đánh dấu đâu là tham chiếu khiến chúng "bất tử".
2. Viết một class `BidHistory` phiên bản **leak** (static list, chỉ add) và chạy thử cho phình bộ nhớ với `-Xmx64m -Xlog:gc` để thấy Heap leo dần và Full GC xuất hiện.
3. Refactor sang phiên bản **an toàn**: dọn bid của auction đã đóng HOẶC dùng cache có `maximumSize` + TTL.
4. Ghi vào `notes/day-04.md`: "Vì sao Java có GC mà code này vẫn leak, và mình đã cắt tham chiếu ở đâu để hết leak?"
5. Điền các chỗ trống `___` trong code thực hành ở trên.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: GC roots là gì? Object như thế nào thì bị thu hồi?**
> **Đáp:** GC roots là tập tham chiếu luôn được coi là sống, gồm: biến local trên stack của thread đang chạy, biến `static`, JNI references, và các thread đang chạy. GC truy vết từ roots theo object graph; object **với tới được (reachable)** thì sống, **không với tới được (unreachable)** thì là rác và bị thu hồi.

**Q2: Phân biệt Minor GC và Major/Full GC?**
> **Đáp:** Minor GC dọn **Young Gen** (Eden + Survivor) — nhanh và thường xuyên vì Young nhỏ, đa số object đã chết; object sống sót được promote dần sang Old. Major/Full GC dọn **Old Gen** (Full GC chạm cả Heap) — chậm hơn nhiều, STW dài hơn, ít xảy ra hơn. Full GC liên tục là dấu hiệu của leak hoặc Heap quá nhỏ.

**Q3: Mark – Sweep – Compact làm những gì?**
> **Đáp:** **Mark**: từ GC roots đánh dấu mọi object sống. **Sweep**: thu hồi object không được đánh dấu. **Compact**: dồn object sống về một đầu để chống phân mảnh, giúp cấp phát object mới nhanh và tránh thất bại khi cấp object lớn dù tổng vùng trống đủ.

**Q4: Stop-The-World là gì và vì sao cần quan tâm?**
> **Đáp:** STW là lúc GC tạm dừng **toàn bộ** thread ứng dụng để object graph không đổi giữa chừng. Trong thời gian đó app "đứng hình" → mọi request bị treo. Pause time quyết định latency (p99), nên giảm STW là mục tiêu của các collector hiện đại.

### Mức Senior

**Q5: So sánh G1 và ZGC, khi nào chọn cái nào?**
> **Đáp:** **G1** chia Heap thành region, ưu tiên dọn region nhiều rác trước, cho phép đặt mục tiêu pause `-XX:MaxGCPauseMillis`; là mặc định từ Java 9, cân bằng tốt cho web service Heap trung-lớn. **ZGC** làm phần lớn việc **đồng thời** với app, đạt pause **< 1ms** gần như độc lập kích thước Heap, hợp với Heap rất lớn (hàng chục GB→TB) và yêu cầu latency khắt khe, đổi lại tốn thêm CPU và một ít throughput. Chọn: phổ thông → G1; latency tối thượng + Heap khổng lồ → ZGC; batch tối đa throughput → Parallel.

**Q6: Java có GC, vậy có bị memory leak không? Kiểu nào?**
> **Đáp:** Có. GC chỉ thu hồi object **unreachable**; nếu code giữ tham chiếu mãi thì object không bao giờ unreachable → leak. Các kiểu kinh điển: `static` collection cứ add không remove, cache không giới hạn (không TTL/size), listener không hủy đăng ký, `ThreadLocal` không `remove()` trong thread pool, class loader leak (Metaspace). Khắc phục: cắt tham chiếu khi không cần (remove, giới hạn size/TTL, weak references khi phù hợp), và phân tích heap dump bằng Eclipse MAT.

**Q7: Vì sao không nên gọi `System.gc()`?**
> **Đáp:** Nó chỉ là **gợi ý**, JVM có thể bỏ qua. Khi không bỏ qua, nó thường ép một **Full GC (STW)** không đúng thời điểm, gây pause dài làm hỏng latency, và che giấu vấn đề thật (Heap quá nhỏ/leak). Hãy để JVM tự lập lịch GC; nếu cần, chặn explicit GC bằng `-XX:+DisableExplicitGC`.

**Q8: Các loại OutOfMemoryError khác nhau và cách phân biệt nguyên nhân?**
> **Đáp:** **`Java heap space`** — Heap đầy do leak hoặc `-Xmx` nhỏ; phân tích heap dump để tìm thủ phạm. **`GC overhead limit exceeded`** — JVM tốn >98% thời gian cho GC mà thu lại <2% Heap, dấu hiệu kinh điển của leak. **`Metaspace`** — vùng metadata class đầy (nạp quá nhiều class / class loader leak khi redeploy), không sửa bằng tăng `-Xmx` mà chỉnh `-XX:MaxMetaspaceSize` và xử lý nguồn nạp class. Phân biệt qua đúng dòng thông báo trong stack trace.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được GC roots và khái niệm reachability bằng lời của mình
- [ ] Mô tả được Mark – Sweep – Compact và vì sao cần Compact
- [ ] Phân biệt Young/Old, Minor GC vs Full GC, và promote là gì
- [ ] Hiểu Stop-The-World và ảnh hưởng tới latency
- [ ] So sánh được Serial / Parallel / G1 / ZGC / Shenandoah và khi nào dùng
- [ ] Chạy được Bài 1 với `-Xlog:gc -Xmx64m` và đọc hiểu log Minor GC
- [ ] Tái hiện được OutOfMemoryError ở Bài 2 và giải thích vì sao leak
- [ ] Refactor Mini Project Auction từ phiên bản leak sang an toàn (dọn/cache TTL)
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle — "HotSpot Virtual Machine Garbage Collection Tuning Guide"
- Baeldung — "JVM Garbage Collectors" và "Understanding Memory Leaks in Java"
- Sách *Java Performance* (Scott Oaks) — chương Garbage Collection
- OpenJDK Wiki — G1, ZGC, Shenandoah (đọc khi cần đào sâu)
- PHP Manual — "Garbage Collection" (reference counting + `gc_collect_cycles`) để đối chiếu
- Công cụ: Eclipse MAT (Memory Analyzer), VisualVM, `jmap`, `jstat`, `-Xlog:gc`
