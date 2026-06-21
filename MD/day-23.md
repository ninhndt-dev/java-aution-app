# Day 23 - Future & CompletableFuture

> **Giai đoạn:** Concurrency & Multithreading
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã nắm `ExecutorService` + `Future` (Day 22), giờ học lập trình **bất đồng bộ kiểu pipeline** — gọi nhiều việc song song rồi gộp, không chặn.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu hạn chế của `Future`: `get()` **chặn** (blocking), không có callback, không **compose** được nhiều future.
- Nắm `CompletableFuture`: tạo async (`supplyAsync`/`runAsync`), nối tiếp (`thenApply`/`thenAccept`/`thenRun`).
- Phân biệt **`thenCompose`** (flatMap — nối hai bước async phụ thuộc) vs **`thenCombine`** (zip — gộp hai future độc lập).
- Gộp nhiều future: **`allOf`** (đợi tất cả) / **`anyOf`** (đợi cái đầu tiên).
- Xử lý lỗi async: `exceptionally` / `handle` / `whenComplete`.
- Chỉ định `Executor` cho từng giai đoạn; hiểu vì sao biến *async* (không chặn).
- So sánh trực giác với **Promise / async-await của JavaScript** để dễ hình dung.
- Liên hệ Laravel: PHP ít async — neo vào Promise/async JS để hiểu.

---

## 🧠 Lý thuyết cốt lõi

### 1. Hạn chế của `Future` — vì sao cần thứ tốt hơn

`Future` (Day 22) chỉ là "biên nhận" cho một kết quả tương lai. Nhưng nó **nghèo nàn**:

```java
Future<Item> f = pool.submit(() -> loadItem(id));
Item item = f.get();    // ❌ CHẶN thread gọi tới khi xong — lãng phí
```

Các hạn chế:

| Hạn chế | Hệ quả |
|---|---|
| `get()` **blocking** | Thread gọi đứng chờ, không làm việc khác → tốn thread |
| Không có **callback** | Không thể bảo "khi xong thì làm tiếp X" — phải tự poll `isDone()` hoặc chặn |
| Không **compose** | Không nối "làm A xong rồi dùng kết quả làm B" mà không chặn giữa chừng |
| Không gộp nhiều | Không có cách thanh lịch để "đợi cả 3 future rồi gộp kết quả" |
| Xử lý lỗi thô | Lỗi gói trong `ExecutionException`, phải try-catch quanh `get()` |

> 💡 `Future` giống một "lời hứa câm": nó hứa sẽ có kết quả, nhưng cách duy nhất để dùng kết quả là **đứng chờ**. `CompletableFuture` (Java 8+) biến nó thành "lời hứa biết nói": bạn **đăng ký việc cần làm khi xong**, và các bước **tự chạy** mà không chặn thread nào.

### 2. `CompletableFuture` — neo vào Promise của JavaScript

Nếu bạn từng dùng JS (rất khả thi với dev Laravel làm fullstack), đây là cách hiểu nhanh nhất:

```
JavaScript Promise            ≈   Java CompletableFuture
────────────────────────────────────────────────────────────────
Promise.resolve(x)            ≈   CompletableFuture.completedFuture(x)
new Promise((res) => ...)     ≈   CompletableFuture.supplyAsync(() -> ...)
.then(v => transform(v))      ≈   .thenApply(v -> transform(v))
.then(v => anotherPromise(v)) ≈   .thenCompose(v -> anotherFuture(v))   (flatMap)
Promise.all([p1, p2])         ≈   CompletableFuture.allOf(f1, f2)
Promise.race([p1, p2])        ≈   CompletableFuture.anyOf(f1, f2)
.catch(err => ...)            ≈   .exceptionally(err -> ...)
.finally(() => ...)           ≈   .whenComplete((v, err) -> ...)
async/await                   ≈   (Java chưa có await; dùng chuỗi .then... hoặc .join())
```

> 💡 Tư duy then chốt giống JS: **đừng chờ — hãy mô tả "khi xong thì làm gì"**. Bạn xây một **pipeline** các bước, mỗi bước tự kích hoạt khi bước trước hoàn tất, tất cả không chặn thread chính.

### 3. Tạo một `CompletableFuture`

```java
// (a) Chạy async CÓ trả về kết quả → supplyAsync(Supplier<T>)
CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> loadUserName(42));

// (b) Chạy async KHÔNG trả về (chỉ làm việc) → runAsync(Runnable)
CompletableFuture<Void> f2 = CompletableFuture.runAsync(() -> sendEmail());

// (c) Đã có sẵn giá trị → completedFuture
CompletableFuture<Integer> f3 = CompletableFuture.completedFuture(100);
```

> ⚠️ Nếu **không chỉ định Executor**, các method `*Async` dùng **`ForkJoinPool.commonPool()`** (pool chung của JVM). Pool này **chung cho cả app**, kích thước = số core − 1, và **không nên dùng cho task chặn/I/O** (chặn nó làm nghẽn mọi thứ khác dùng common pool, kể cả parallel streams). Production: **luôn truyền Executor riêng** cho task I/O.

### 4. Nối tiếp các bước: `thenApply` / `thenAccept` / `thenRun`

Đây là cách "khi xong thì làm tiếp" (transform pipeline):

| Method | Nhận vào | Trả ra | Dùng khi |
|---|---|---|---|
| `thenApply(fn)` | kết quả bước trước | giá trị mới (`T → U`) | **biến đổi** kết quả |
| `thenAccept(consumer)` | kết quả bước trước | `Void` | **tiêu thụ** kết quả (in/lưu), không trả gì |
| `thenRun(runnable)` | (bỏ qua kết quả) | `Void` | chạy việc cuối, không cần kết quả |

```java
CompletableFuture.supplyAsync(() -> loadItem(1))   // CompletableFuture<Item>
    .thenApply(item -> item.price())               // CompletableFuture<Long>  (biến đổi)
    .thenApply(price -> price * 1.1)               // áp thuế
    .thenAccept(finalPrice -> System.out.println("Giá: " + finalPrice))  // tiêu thụ
    .thenRun(() -> System.out.println("Hoàn tất pipeline"));
```

Mỗi method có **biến thể `*Async`** (`thenApplyAsync`...): bản thường chạy bước tiếp **trên thread vừa hoàn thành bước trước** (hoặc thread gọi nếu đã xong); bản `Async` đẩy bước tiếp sang pool (mặc định common pool, hoặc Executor bạn truyền).

### 5. `thenCompose` (flatMap) vs `thenCombine` (zip) — ĐIỂM MẤU CHỐT

Đây là chỗ hay nhầm nhất:

**`thenCompose` — nối hai bước async PHỤ THUỘC nhau (flatMap):**
Khi bước sau **cần kết quả bước trước** và bản thân bước sau **cũng trả về một future**.

```java
// Lấy user xong → DÙNG userId để lấy đơn hàng của user đó (2 bước nối tiếp)
CompletableFuture<User> userF = loadUserAsync(42);
CompletableFuture<List<Order>> ordersF =
    userF.thenCompose(user -> loadOrdersAsync(user.id()));   // KHÔNG lồng future trong future
```

Nếu dùng `thenApply` ở đây sẽ ra `CompletableFuture<CompletableFuture<List<Order>>>` (lồng nhau, xấu). `thenCompose` **làm phẳng** (flatten) — giống `flatMap` của Stream/Optional.

**`thenCombine` — gộp hai future ĐỘC LẬP (zip):**
Khi hai việc **không phụ thuộc nhau**, chạy **song song**, rồi gộp kết quả.

```java
// Lấy item và lấy giá hiện tại — ĐỘC LẬP, chạy song song, rồi gộp
CompletableFuture<Item> itemF  = loadItemAsync(1);
CompletableFuture<Long> priceF = loadCurrentPriceAsync(1);
CompletableFuture<String> view =
    itemF.thenCombine(priceF, (item, price) -> item.name() + " — giá " + price);  // gộp 2 kết quả
```

```
thenCompose (PHỤ THUỘC, tuần tự):     A ──► (dùng A) ──► B ──► kết quả
thenCombine (ĐỘC LẬP, song song):     A ─┐
                                          ├─► gộp(A,B) ──► kết quả
                                      B ─┘
```

> 💡 Quy tắc nhớ: **`thenCompose` = "rồi mới" (phụ thuộc, nối)** — bước sau cần dữ liệu bước trước. **`thenCombine` = "và" (độc lập, gộp)** — hai bước chạy song song rồi trộn. Hỏi mình: "việc thứ hai có cần kết quả việc thứ nhất không?" Có → compose; Không → combine.

### 6. Gộp nhiều future: `allOf` / `anyOf`

```java
// allOf: đợi TẤT CẢ hoàn thành (trả CompletableFuture<Void>)
CompletableFuture<Void> all = CompletableFuture.allOf(f1, f2, f3);
all.join();                                  // chặn tới khi cả 3 xong
// allOf không gom kết quả → tự lấy sau: f1.join(), f2.join(), f3.join()

// anyOf: đợi CÁI ĐẦU TIÊN xong (trả CompletableFuture<Object>)
CompletableFuture<Object> any = CompletableFuture.anyOf(f1, f2, f3);
```

Mẫu phổ biến: gọi N dịch vụ song song rồi gom danh sách kết quả:

```java
List<CompletableFuture<Price>> futures = ids.stream()
    .map(id -> CompletableFuture.supplyAsync(() -> fetchPrice(id), pool))
    .toList();

CompletableFuture<List<Price>> allPrices =
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
```

### 7. Xử lý lỗi async: `exceptionally` / `handle` / `whenComplete`

Trong pipeline async, exception **lan truyền** xuống các bước sau. Ba cách bắt:

| Method | Nhận | Trả | Dùng khi |
|---|---|---|---|
| `exceptionally(fn)` | chỉ exception | giá trị **thay thế** (recover) | "nếu lỗi thì trả giá trị mặc định" |
| `handle((v, ex) -> ...)` | **cả** kết quả **và** exception | giá trị mới | xử lý cả thành công lẫn lỗi, biến đổi tiếp |
| `whenComplete((v, ex) -> ...)` | cả kết quả và exception | **giữ nguyên** kết quả/lỗi | side-effect (log, cleanup), **không** đổi kết quả |

```java
CompletableFuture.supplyAsync(() -> riskyCall())
    .thenApply(x -> x * 2)
    .exceptionally(ex -> {                       // lỗi ở bất kỳ bước trên → recover về -1
        log.error("Lỗi pipeline", ex);
        return -1;
    });

// handle: xử lý cả hai nhánh
.future.handle((result, ex) -> ex == null ? result : fallbackValue);

// whenComplete: log/cleanup nhưng KHÔNG đổi kết quả (lỗi vẫn lan tiếp)
.future.whenComplete((result, ex) -> {
    if (ex != null) log.error("hỏng", ex);
    else log.info("ok: {}", result);
});
```

> ⚠️ Phân biệt: `whenComplete` **không nuốt** exception — nó chỉ quan sát rồi để lỗi tiếp tục lan (giống `finally`). `exceptionally`/`handle` **có thể** biến lỗi thành giá trị (recover). Nhầm hai cái này dẫn tới "tưởng đã catch nhưng future vẫn fail".

### 8. Bất đồng bộ thực sự — vì sao "không chặn"

Sức mạnh thật: cả pipeline **không chặn thread nào** cho tới khi bạn cố tình lấy kết quả (`join()`/`get()`). Mỗi bước được lên lịch chạy khi bước trước hoàn tất, trên thread của pool. Thread chính rảnh tay đi làm việc khác.

```
Đồng bộ (blocking):  loadItem() ─chờ─► loadPrice() ─chờ─► loadUser() ─chờ─►  gộp
                     (tổng thời gian = t1 + t2 + t3)

Bất đồng bộ song song:  loadItem() ─┐
                        loadPrice() ─┼─ chạy CÙNG LÚC ─► gộp khi cả 3 xong
                        loadUser()  ─┘
                     (tổng thời gian ≈ max(t1, t2, t3) — nhanh hơn nhiều!)
```

> 💡 Đây là lợi ích lớn nhất: ba lời gọi I/O độc lập (item, user, price) chạy **song song** → tổng thời gian ≈ cái chậm nhất, thay vì tổng cả ba. Đúng kịch bản render một trang chi tiết cần dữ liệu từ nhiều nguồn — chính là Mini Project hôm nay.

---

## 🔁 Đối chiếu với Laravel/PHP

PHP truyền thống là **đồng bộ, tuần tự**: mỗi lời gọi (query, HTTP) **chặn** tới khi xong. Không có khái niệm "future/promise" trong code Laravel đời thường — vì model shared-nothing, một request một thread, chạy thẳng từ trên xuống.

```
Laravel (đồng bộ):              Java CompletableFuture (bất đồng bộ):
$item  = Item::find(1);          itemF  = supplyAsync(() -> loadItem(1), pool)
$user  = User::find($uid);       userF  = supplyAsync(() -> loadUser(uid), pool)
$price = getPrice(1);            priceF = supplyAsync(() -> loadPrice(1), pool)
// 3 query CHẠY LẦN LƯỢT          // 3 lời gọi CHẠY SONG SONG
// tổng = t1 + t2 + t3            // tổng ≈ max(t1, t2, t3)
```

| Khía cạnh | Laravel/PHP | Java `CompletableFuture` |
|---|---|---|
| Mô hình mặc định | Đồng bộ, blocking, tuần tự | Có thể bất đồng bộ, non-blocking, song song |
| Gọi N việc song song | Khó (cần `Http::pool()`, hoặc đa tiến trình) | Tự nhiên (`allOf` + `supplyAsync`) |
| "Khi xong thì làm tiếp" | Viết tuần tự (vì blocking) | `thenApply`/`thenCompose` (callback) |
| Tương đương gần nhất | **`Http::pool()`** (gửi nhiều HTTP request song song), hoặc queue jobs | Pipeline `CompletableFuture` |
| Hiểu nhanh qua | JavaScript Promise/async-await (nếu bạn làm frontend) | (chính nó) |

> 🧩 Insight chuyển ngữ: Laravel có **`Http::pool()`** cho phép bắn nhiều HTTP request **song song** rồi gom kết quả — đó chính là `allOf` + `thenCombine` của Java ở một use-case hẹp. Còn nếu bạn từng viết frontend JS, `CompletableFuture` **chính là Promise** đội lốt Java: `.thenApply` = `.then`, `.exceptionally` = `.catch`, `allOf` = `Promise.all`. Neo vào đó, mọi thứ sáng tỏ.

> ⚠️ Khác biệt tư duy quan trọng: trong Laravel bạn hiếm khi cần điều này vì request đã đủ nhanh và scale bằng nhiều process. Ở Java (một process chạy lâu, gọi nhiều microservice/DB), gọi song song bằng `CompletableFuture` là kỹ thuật **giảm latency** then chốt — biến tổng thời gian từ "cộng dồn" thành "max".

---

## 💻 Thực hành code

### (a) `Future` (blocking) vs `CompletableFuture` (non-blocking)

```java
import java.util.concurrent.*;

public class FutureVsCompletable {
    static long slow(String name, long ms, long value) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        System.out.println("xong: " + name);
        return value;
    }

    public static void main(String[] args) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {

            // --- Future cũ: phải get() từng cái, chặn ---
            long t0 = System.currentTimeMillis();
            Future<Long> fa = pool.submit(() -> slow("A", 300, 1));
            Future<Long> fb = pool.submit(() -> slow("B", 300, 2));
            long sum = fa.get() + fb.get();      // chặn lần lượt (nhưng A,B vẫn chạy song song)
            System.out.println("Future sum=" + sum + " (" + (System.currentTimeMillis()-t0) + "ms)");

            // --- CompletableFuture: gộp bằng thenCombine, không lồng get() ---
            long t1 = System.currentTimeMillis();
            ___<Long> ca = ___.___(() -> slow("C", 300, 10), pool); // Điền class và method tạo async task trả kết quả
            ___<Long> cb = ___.___(() -> slow("D", 300, 20), pool);
            ca.___(cb, Long::sum) // Điền phương thức kết hợp 2 future
              .___(s -> System.out.println("CF sum=" + s // Điền phương thức tiêu thụ kết quả
                      + " (" + (System.currentTimeMillis()-t1) + "ms)"))
              .___(); // Điền phương thức đợi
        }
    }
}
```

### (b) Pipeline transform: `thenApply` → `thenCompose`

```java
import java.util.concurrent.*;

public class PipelineDemo {
    record User(long id, String name) {}
    record Order(long id, long userId, long total) {}

    static ___<User>  loadUser(long id, Executor ex) { // Điền class Future
        return ___.___(() -> { sleep(100); return new User(id, "U" + id); }, ex); // Điền method cung cấp task async
    }
    static ___<Order> loadLatestOrder(long userId, Executor ex) {
        return ___.___(() -> { sleep(100); return new Order(9, userId, 500); }, ex);
    }

    public static void main(String[] args) {
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            loadUser(42, pool)
                .___(user -> { System.out.println("Có user: " + user.name()); return user; })  // Điền phương thức biến đổi (map)
                .___(user -> loadLatestOrder(user.id(), pool))   // Điền phương thức gọi task phụ thuộc (flatMap)
                .___(order -> System.out.println("Đơn mới nhất tổng = " + order.total())) // Điền phương thức tiêu thụ
                .___(); // Điền phương thức đợi
        }
    }
    static void sleep(long ms){ try{ Thread.sleep(ms);}catch(InterruptedException ignored){} }
}
```

### (c) Gọi N dịch vụ song song rồi gộp: `allOf`

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class AllOfDemo {
    static long fetchPrice(long itemId) {
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}   // mô phỏng I/O
        return itemId * 100;
    }

    public static void main(String[] args) {
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Long> ids = LongStream.rangeClosed(1, 10).boxed().toList();

            long t0 = System.currentTimeMillis();
            List<___<Long>> futures = ids.stream() // Điền class Future
                .map(id -> ___.___(() -> fetchPrice(id), pool))   // 10 lời gọi song song
                .toList();

            ___<List<Long>> allPrices =
                ___.___(futures.toArray(new ___[0]))      // Điền phương thức chờ tất cả
                    .___(v -> futures.stream().map(___::___).toList());  // Điền phương thức biến đổi và lấy kết quả

            List<Long> prices = allPrices.join();
            System.out.println("Giá: " + prices + " — tổng thời gian "
                    + (System.currentTimeMillis()-t0) + "ms (≈200ms vì SONG SONG, không phải 2000ms)");
        }
    }
}
```

### (d) Xử lý lỗi: `exceptionally` / `handle`

```java
import java.util.concurrent.*;

public class ErrorHandlingDemo {
    static long risky(long id) {
        if (id == 3) throw new IllegalStateException("Item " + id + " lỗi DB!");
        return id * 10;
    }

    public static void main(String[] args) {
        for (long id : new long[]{1, 3}) {
            long result = CompletableFuture.supplyAsync(() -> risky(id))
                .thenApply(x -> x + 1)
                .exceptionally(ex -> {                       // bắt lỗi từ bất kỳ bước trên
                    System.out.println("Recover từ lỗi: " + ex.getCause().getMessage());
                    return -1L;                              // giá trị thay thế
                })
                .join();
            System.out.println("id=" + id + " → result=" + result);
        }
        // handle: xử lý cả hai nhánh thành/bại trong một chỗ
        String msg = CompletableFuture.supplyAsync(() -> risky(3))
            .handle((value, ex) -> ex == null ? "OK:" + value : "FAIL:" + ex.getCause().getMessage())
            .join();
        System.out.println(msg);   // FAIL:Item 3 lỗi DB!
    }
}
```

> ⚠️ Lưu ý: exception trong task async bị bọc trong `CompletionException`/`ExecutionException`; lấy lỗi gốc qua `ex.getCause()`. Đó là lý do code dùng `ex.getCause().getMessage()`.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Dùng `thenApply` thay vì `thenCompose` cho bước trả về future.** Ra `CompletableFuture<CompletableFuture<T>>` lồng nhau. Bước sau trả future + phụ thuộc bước trước → dùng `thenCompose`.
- **Dùng common pool cho task I/O/chặn.** `*Async` không truyền Executor → dùng `ForkJoinPool.commonPool()` (chung toàn app, nhỏ). Chặn nó làm nghẽn parallel streams và mọi CompletableFuture khác. **Luôn truyền Executor riêng** cho I/O.
- **Lẫn `whenComplete` với `exceptionally`.** `whenComplete` chỉ quan sát (không recover) — lỗi vẫn lan tiếp. `exceptionally`/`handle` mới biến lỗi thành giá trị.
- **Quên xử lý lỗi → lỗi "im lặng".** Nếu không có `exceptionally`/`handle`/`join()`, exception trong pipeline có thể không bao giờ lộ ra. Luôn kết thúc pipeline bằng nhánh xử lý lỗi hoặc `join()`/`get()` có try-catch.
- **`join()` vs `get()`.** `get()` ném checked `ExecutionException`/`InterruptedException`; `join()` ném unchecked `CompletionException` (gọn hơn trong lambda). Đừng trộn lẫn vô ý.
- **Chặn ngay sau khi tạo future → mất tính song song.** `supplyAsync(...).join()` rồi mới tạo future tiếp = tuần tự. Hãy **tạo tất cả future trước**, rồi mới `allOf`/`join`.
- **Bom lồng callback.** Pipeline quá dài/lồng sâu khó đọc. Tách method, đặt tên rõ, dùng `thenCompose` để phẳng.
- **Không đặt timeout.** `join()` chờ mãi nếu một bước treo. Dùng `orTimeout(t, unit)` (Java 9+) hoặc `completeOnTimeout(default, t, unit)` để có giới hạn.

---

## 🚀 Liên hệ Spring Boot / Production

- **Giảm latency endpoint bằng gọi song song.** Một API tổng hợp dữ liệu từ 3 microservice/DB: thay vì gọi tuần tự (t1+t2+t3), dùng `CompletableFuture.supplyAsync` cho từng cái + `thenCombine`/`allOf` → latency ≈ max. Đây là tối ưu phổ biến nhất trong thực chiến.
- **`@Async` trả `CompletableFuture`.** Method `@Async` có thể trả `CompletableFuture<T>`; Spring chạy nó trên `ThreadPoolTaskExecutor` (Day 22). Nhớ **cấu hình pool riêng** và truyền nó vào `supplyAsync`, đừng để rơi vào common pool.
- **WebClient (Spring WebFlux) trả `Mono`/`Flux`** — mô hình reactive còn mạnh hơn cho I/O thuần non-blocking. `CompletableFuture` là bước đệm tốt để hiểu reactive; có thể chuyển đổi qua lại (`Mono.fromFuture`).
- **Resilience.** Kết hợp `orTimeout` + `exceptionally` (fallback) để xây pattern timeout/fallback — nền của circuit breaker (Resilience4j gói sẵn).
- **Theo dõi Executor.** Đặt tên thread (Day 22) để thread dump chỉ rõ bước nào treo. Giám sát queue/active của pool dùng cho CompletableFuture.
- **Đừng dùng cho fan-out cực lớn trên platform thread.** Với hàng nghìn lời gọi I/O song song, cân nhắc **virtual threads** (Java 21) + code blocking đơn giản thay vì pipeline CompletableFuture phức tạp — đôi khi dễ đọc và hiệu quả hơn.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Nối tiếp Day 22. Khi render trang chi tiết một phiên đấu giá, ta cần **3 mẩu dữ liệu độc lập**: thông tin **item** (từ catalog service), thông tin **người bán/người giữ giá cao nhất** (từ user service), và **giá hiện tại** (từ bidding service). Ba lời gọi này **không phụ thuộc nhau** → gọi **song song** bằng `CompletableFuture`, rồi gộp thành một `AuctionView`. Đây đúng kịch bản `thenCombine`/`allOf`.

### Bước 1 — Các "service" (mô phỏng I/O) trả về future

```java
import java.util.concurrent.*;

public class AuctionDataService {
    public record Item(long id, String name)        {}
    public record User(long id, String displayName) {}
    public record AuctionView(Item item, User topBidder, long currentPrice) {}

    private final Executor pool;
    public AuctionDataService(Executor pool) { this.pool = pool; }

    public CompletableFuture<Item> loadItem(long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(150);                                   // mô phỏng gọi catalog service
            return new Item(auctionId, "Đồng hồ cổ #" + auctionId);
        }, pool);
    }

    public CompletableFuture<User> loadTopBidder(long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(120);                                   // mô phỏng gọi user service
            return new User(7, "Nguyễn Văn A");
        }, pool);
    }

    public CompletableFuture<Long> loadCurrentPrice(long auctionId) {
        return CompletableFuture.supplyAsync(() -> {
            sleep(100);                                   // mô phỏng gọi bidding service
            return 5_000_000L;
        }, pool);
    }

    static void sleep(long ms){ try{ Thread.sleep(ms);}catch(InterruptedException ignored){} }
}
```

### Bước 2 — Gộp 3 future song song thành `AuctionView`

```java
import java.util.concurrent.*;

public class AuctionViewLoader {
    private final AuctionDataService svc;
    public AuctionViewLoader(AuctionDataService svc) { this.svc = svc; }

    /** Gọi 3 service SONG SONG rồi gộp. Có fallback nếu một nhánh lỗi. */
    public CompletableFuture<AuctionDataService.AuctionView> load(long auctionId) {
        var itemF   = svc.loadItem(auctionId);
        var bidderF = svc.loadTopBidder(auctionId)
                         .exceptionally(ex -> new AuctionDataService.User(0, "(ẩn danh)")); // fallback
        var priceF  = svc.loadCurrentPrice(auctionId)
                         .exceptionally(ex -> 0L);                                          // fallback

        // gộp item + bidder trước (thenCombine), rồi gộp tiếp với price
        return itemF
            .thenCombine(bidderF, (item, bidder) -> new Object[]{ item, bidder })
            .thenCombine(priceF, (pair, price) ->
                new AuctionDataService.AuctionView(
                    (AuctionDataService.Item) pair[0],
                    (AuctionDataService.User) pair[1],
                    price))
            .orTimeout(2, TimeUnit.SECONDS);    // bảo hiểm: không bao giờ chờ quá 2s
    }
}
```

### Bước 3 — Chạy thử & đo song song

```java
import java.util.concurrent.*;

public class AuctionViewDemo {
    public static void main(String[] args) {
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            var svc = new AuctionDataService(pool);
            var loader = new AuctionViewLoader(svc);

            long t0 = System.currentTimeMillis();
            var view = loader.load(1001).join();          // chặn 1 lần ở cuối để lấy kết quả
            long elapsed = System.currentTimeMillis() - t0;

            System.out.println("View: " + view);
            // 3 lời gọi 150/120/100ms chạy SONG SONG → tổng ≈ 150ms (max), KHÔNG phải 370ms (tổng)
            System.out.println("Thời gian: " + elapsed + "ms (≈150ms vì song song)");
        }
    }
}
```

### Bước 4 — Điểm cần quan sát

- **Tổng thời gian ≈ 150ms** (cái chậm nhất) thay vì 370ms (tổng) — bằng chứng 3 lời gọi chạy **song song**.
- **`exceptionally` fallback** cho mỗi nhánh → một service lỗi không làm sập cả trang (vẫn render với "(ẩn danh)" / giá 0).
- **`orTimeout`** đảm bảo trang luôn phản hồi trong giới hạn, dù một service treo.

> 🧩 Liên hệ thực chiến: trong Laravel bạn có thể đạt điều tương tự (ở mức HTTP) bằng `Http::pool()` để bắn 3 request song song rồi gom. Trong Java, `CompletableFuture` cho phép làm điều đó với **bất kỳ** tác vụ (DB, cache, API), tổ hợp tùy ý, với xử lý lỗi/timeout từng nhánh — mạnh và linh hoạt hơn nhiều.

**Nhiệm vụ Day 23:**
0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Chạy `AllOfDemo`, xác nhận 10 lời gọi 200ms tổng thời gian ≈ 200ms (song song) chứ không 2000ms.
2. Chạy Mini Project, đo thời gian, xác nhận ≈ max chứ không phải tổng.
3. Cố tình ném exception trong `loadCurrentPrice`, xác nhận fallback `0L` hoạt động và trang vẫn render.
4. Ghi `notes/day-23.md`: giải thích bằng lời khi nào dùng `thenCompose` (phụ thuộc) vs `thenCombine` (độc lập), và map `CompletableFuture` ↔ Promise JS.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `Future` có hạn chế gì khiến `CompletableFuture` ra đời?**
> **Đáp:** `Future.get()` **chặn** thread gọi, không có callback "khi xong làm tiếp", không compose được nhiều future, không gộp thanh lịch, xử lý lỗi thô (try-catch quanh `get`). `CompletableFuture` (Java 8) thêm callback (`thenApply`...), compose (`thenCompose`/`thenCombine`), gộp (`allOf`/`anyOf`), xử lý lỗi async (`exceptionally`/`handle`) — tất cả không chặn.

**Q2: Phân biệt `thenApply`, `thenAccept`, `thenRun`.**
> **Đáp:** Cả ba chạy "khi bước trước xong". `thenApply(fn)` **biến đổi** kết quả và trả giá trị mới (`T→U`). `thenAccept(consumer)` **tiêu thụ** kết quả (in/lưu), trả `Void`. `thenRun(runnable)` chạy việc cuối, **bỏ qua** kết quả, trả `Void`.

**Q3: `thenCompose` khác `thenCombine` thế nào?**
> **Đáp:** `thenCompose` (flatMap) nối **hai bước phụ thuộc**: bước sau cần kết quả bước trước và bản thân trả về một future → làm phẳng tránh future lồng future. `thenCombine` (zip) gộp **hai future độc lập** chạy song song rồi trộn kết quả. Hỏi: "việc hai có cần kết quả việc một không?" Có → compose; Không → combine.

**Q4: `CompletableFuture` giống gì trong JavaScript?**
> **Đáp:** Giống **Promise**: `supplyAsync` ≈ `new Promise`, `thenApply` ≈ `.then`, `thenCompose` ≈ `.then` trả promise, `allOf` ≈ `Promise.all`, `anyOf` ≈ `Promise.race`, `exceptionally` ≈ `.catch`, `whenComplete` ≈ `.finally`. Cùng tư tưởng: mô tả "khi xong làm gì" thay vì đứng chờ.

### Mức Senior

**Q5: Mặc định `supplyAsync` chạy trên pool nào? Rủi ro gì? Khắc phục?**
> **Đáp:** Nếu không truyền Executor, dùng `ForkJoinPool.commonPool()` — pool **chung toàn JVM**, kích thước ≈ số core − 1, dùng chung với parallel streams. Chặn nó bằng task I/O làm **nghẽn cả app**. Khắc phục: **luôn truyền Executor riêng** (một `ThreadPoolTaskExecutor`/`ThreadPoolExecutor` cấu hình cho I/O) cho mọi `*Async` xử lý I/O.

**Q6: `exceptionally`, `handle`, `whenComplete` khác nhau ra sao?**
> **Đáp:** `exceptionally(fn)` chỉ chạy **khi lỗi**, biến lỗi thành **giá trị thay thế** (recover). `handle((v,ex)->...)` chạy **luôn**, nhận cả kết quả lẫn lỗi, trả giá trị mới (xử lý cả hai nhánh). `whenComplete((v,ex)->...)` chạy luôn nhưng **không đổi** kết quả/lỗi — chỉ side-effect (log/cleanup), lỗi **vẫn lan tiếp** (giống `finally`). Nhầm `whenComplete` với `exceptionally` dẫn tới tưởng đã recover nhưng future vẫn fail.

**Q7: Làm sao gọi N dịch vụ song song rồi gom danh sách kết quả?**
> **Đáp:** Tạo `List<CompletableFuture<T>>` bằng `supplyAsync` (truyền Executor), rồi `CompletableFuture.allOf(futures...)` để đợi tất cả, sau đó `.thenApply(v -> futures.stream().map(CompletableFuture::join).toList())` để gom (lúc này mọi future đã xong nên `join` không chặn). Lưu ý tạo **tất cả future trước** rồi mới `allOf` — nếu `join` từng cái ngay khi tạo thì mất tính song song.

**Q8: Làm sao thêm timeout và fallback cho một pipeline async?**
> **Đáp:** `orTimeout(t, unit)` (Java 9+) làm future fail với `TimeoutException` nếu quá hạn; `completeOnTimeout(default, t, unit)` hoàn thành bằng giá trị mặc định khi quá hạn. Kết hợp `exceptionally`/`handle` để fallback khi lỗi/timeout. Đây là nền tảng của pattern timeout + fallback (circuit breaker). Ví dụ: `future.orTimeout(2, SECONDS).exceptionally(ex -> defaultValue)`.

**Q9: Khi nào KHÔNG nên dùng `CompletableFuture`, mà chọn cách khác?**
> **Đáp:** (1) Khi tác vụ **tuần tự đơn giản** — pipeline async chỉ làm phức tạp, cứ code thẳng. (2) Khi cần fan-out **rất lớn** task I/O — Java 21 **virtual threads** + code blocking thường dễ đọc/hiệu quả hơn pipeline lồng. (3) Khi đã ở stack reactive (WebFlux) — dùng `Mono`/`Flux` nhất quán. (4) Khi cần backpressure/stream dữ liệu — reactive phù hợp hơn. `CompletableFuture` mạnh nhất ở "gọi vài việc độc lập song song rồi gộp, có xử lý lỗi/timeout".

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được hạn chế của `Future` và vì sao cần `CompletableFuture`
- [ ] Phân biệt `thenApply`/`thenAccept`/`thenRun`
- [ ] Phân biệt rạch ròi `thenCompose` (phụ thuộc) vs `thenCombine` (độc lập)
- [ ] Dùng `allOf`/`anyOf` gộp nhiều future
- [ ] Phân biệt `exceptionally`/`handle`/`whenComplete`
- [ ] Biết truyền Executor riêng, tránh common pool cho I/O
- [ ] Map được `CompletableFuture` ↔ Promise JS
- [ ] Hoàn thành Mini Project: load item + user + price song song khi render phiên đấu giá
- [ ] Trả lời được các câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Javadoc `java.util.concurrent.CompletableFuture` — đọc kỹ `thenApply`/`thenCompose`/`thenCombine`/`allOf`/`exceptionally`
- Sách *Modern Java in Action* (Urma, Fusco, Mycroft) — chương 16 "CompletableFuture: composable asynchronous programming"
- Oracle Java Tutorials & JEP — phần CompletableFuture (Java 8) và bổ sung Java 9 (`orTimeout`, `completeOnTimeout`)
- Baeldung — "Guide to CompletableFuture", "CompletableFuture Combine", "Exception Handling in CompletableFuture"
- MDN — "Using Promises" (đối chiếu trực giác Promise ↔ CompletableFuture)
- Spring Framework Docs — `@Async` trả `CompletableFuture`, `ThreadPoolTaskExecutor`
