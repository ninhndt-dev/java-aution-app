# Day 35 - Proxy (Cơ chế Proxy của Spring)

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP — quen `__call` magic method, nay hiểu vì sao `@Transactional` "thần kỳ" và khi nào nó **không** ăn.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **Spring AOP dựa trên proxy** — mảnh ghép cuối nối Day 28 (BPP) và Day 29 (AOP).
- Phân biệt **JDK dynamic proxy** (cần interface) và **CGLIB** (kế thừa class con, không cần interface).
- Biết **giới hạn của proxy**: không proxy được method `final`/`private`/`static`.
- Nắm vì sao `@Transactional`/`@Cacheable`/`@Async` **hoạt động qua proxy**.
- Hiểu sâu **bẫy self-invocation** — gọi `this.method()` bỏ qua proxy khiến annotation "không ăn".
- Ánh xạ sang Laravel: không có proxy kiểu này, nhưng có `__call` magic method (gần về tinh thần "chặn lời gọi").

---

## 🧠 Lý thuyết cốt lõi

### 1. Proxy là gì và vì sao Spring cần nó?

Ở Day 28, ta học `BeanPostProcessor.postProcessAfterInitialization` có thể **trả về một object khác thay bean gốc**. Ở Day 29, ta học aspect cần "chen" logic trước/sau method. Mảnh ghép nối hai điều đó chính là **proxy**:

> **Proxy** là một object **bao quanh** (wrap) bean gốc, có **cùng kiểu/interface**, nhận lời gọi thay cho bean gốc, chèn logic (advice/transaction/cache), rồi mới chuyển tiếp xuống bean gốc.

```
   Code của bạn gọi:  auctionService.placeBid(...)
                              │
                              ▼
                  ┌────────────────────────┐
                  │   PROXY (Spring tạo)    │  ← bean container thực sự giữ là CÁI NÀY
                  │  - mở transaction       │
                  │  - ghi log / metric     │
                  │  - gọi target ──────────┼──► AuctionService THẬT (target).placeBid(...)
                  │  - commit / rollback    │
                  └────────────────────────┘
```

> 💡 Điều bất ngờ: khi bạn `@Autowired AuctionService`, biến bạn nhận được **thường là proxy, không phải object gốc**. Đó là lý do `@Transactional` "tự nhiên" hoạt động — proxy đã bọc logic transaction quanh method của bạn.

### 2. Hai loại proxy: JDK Dynamic Proxy vs CGLIB

Spring có hai cách tạo proxy:

| Tiêu chí | **JDK Dynamic Proxy** | **CGLIB** |
|---|---|---|
| Cơ chế | Tạo class hiện thực **các interface** của target | Tạo **class con** kế thừa target |
| Yêu cầu | Target **phải có interface** | Không cần interface |
| Lớp nền | `java.lang.reflect.Proxy` + `InvocationHandler` | Sinh bytecode subclass (thư viện CGLIB, nay nằm trong `spring-core`) |
| Proxy được gì | Chỉ method **khai báo trong interface** | Mọi method **không final/private/static** |
| Không proxy được | Method ngoài interface | Method `final`, `private`, `static` |
| Spring Boot mặc định | (cũ) khi có interface | **Mặc định dùng CGLIB** (`proxyTargetClass=true`) |

```
   JDK Dynamic Proxy                       CGLIB
   ─────────────────                       ─────
   interface BidService                    class AuctionService (không cần interface)
        ▲ implements                              ▲ extends
   Proxy$123  (do JDK sinh)                AuctionService$$SpringCGLIB$$0 (subclass sinh ra)
        │ InvocationHandler.invoke()             │ override method, chèn advice
        └─► target.placeBid()                    └─► super.placeBid()
```

> 💡 **Spring Boot mặc định bật `spring.aop.proxy-target-class=true`** → dùng **CGLIB** cả khi có interface, cho nhất quán. Spring Framework "thuần" (không Boot) thì mặc định JDK proxy nếu target có interface, CGLIB nếu không.

### 3. Cơ chế JDK Dynamic Proxy (hiểu tận gốc)

JDK proxy dựa trên `InvocationHandler` — **mọi** lời gọi method đều đi qua một method `invoke()` duy nhất:

```java
public interface InvocationHandler {
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable;
}
```

Đây chính là điểm để chèn logic cross-cutting: trong `invoke()`, bạn làm gì đó (log, đo thời gian), rồi gọi `method.invoke(target, args)` để chuyển tiếp xuống target. (Phần code sẽ tự viết một cái.)

### 4. Giới hạn của proxy (rất hay bị hỏi)

| Trường hợp | JDK Proxy | CGLIB | Vì sao |
|---|---|---|---|
| Method `public` trong interface | ✅ | ✅ | |
| Method `public` ngoài interface | ❌ | ✅ | JDK chỉ thấy interface |
| Method `final` | ❌ | ❌ | CGLIB không override được `final` |
| Method `private` | ❌ | ❌ | Không thể override/intercept |
| Method `static` | ❌ | ❌ | Static gắn với class, không qua instance |
| Class `final` | ❌ | ❌ (CGLIB không kế thừa được class final) | |

> ⚠️ Vì vậy: **đừng để `final` lên class/method cần được AOP/`@Transactional`**, và **đừng đặt `@Transactional` lên method `private`** — nó sẽ bị bỏ qua âm thầm (không lỗi, chỉ là không ăn).

### 5. Vì sao `@Transactional`/`@Cacheable`/`@Async` cần proxy?

Tất cả các annotation này hoạt động bằng cách **proxy chèn logic quanh method**:

- `@Transactional` → proxy: `beginTransaction()` trước, `commit()`/`rollback()` sau.
- `@Cacheable` → proxy: kiểm tra cache trước; cache hit thì **không gọi** method gốc.
- `@Async` → proxy: đẩy việc gọi method gốc sang thread pool khác.

Vì logic này nằm ở **proxy**, nó chỉ kích hoạt khi lời gọi **đi qua proxy** — tức gọi từ **bean khác**. Đây là gốc rễ của bẫy tiếp theo.

### 6. Bẫy self-invocation (KINH ĐIỂN ⚠️⚠️⚠️)

Xét class sau:

```java
@Service
public class BidService {

    public void placeBidAndNotify(Bid bid) {
        save(bid);          // ❌ GỌI this.save(bid) -> KHÔNG qua proxy
    }

    @Transactional
    public void save(Bid bid) {  // @Transactional Ở ĐÂY SẼ KHÔNG ĂN khi gọi nội bộ!
        bidRepository.insert(bid);
    }
}
```

Khi `placeBidAndNotify` gọi `save(bid)`, đó thực chất là `this.save(bid)` — gọi **trực tiếp trên target**, **không qua proxy**. Proxy không hề biết lời gọi này → logic `@Transactional` **bị bỏ qua**. Method chạy **không có transaction**.

```
   Bên ngoài gọi placeBidAndNotify()
        │
        ▼
   PROXY ──► target.placeBidAndNotify()
                    │  gọi this.save()  ← this = TARGET, không phải proxy!
                    ▼
              target.save()   ← @Transactional KHÔNG kích hoạt (bỏ qua proxy)
```

**Cách khắc phục:**
1. **Tách `save()` sang một bean khác** (ví dụ `BidPersister`) rồi inject vào → lời gọi đi qua proxy của bean kia. (Khuyến nghị — sạch nhất.)
2. **Tự inject chính mình** (self-injection) và gọi qua tham chiếu proxy:
   ```java
   @Autowired @Lazy private BidService self; // self là PROXY
   public void placeBidAndNotify(Bid bid) { self.save(bid); } // đi qua proxy
   ```
3. Lấy proxy hiện tại qua `AopContext.currentProxy()` (cần `exposeProxy=true`) — ít dùng, rối.

> 💡 Quy tắc vàng: **annotation AOP (`@Transactional`, `@Cacheable`, `@Async`...) chỉ kích hoạt khi method được gọi từ NGOÀI bean (qua proxy)**, không bao giờ từ lời gọi nội bộ `this.x()`.

---

## 🔁 Đối chiếu với Laravel/PHP

Laravel **không có** proxy kiểu Spring (không bọc tự động mọi bean). Nhưng PHP có công cụ chặn lời gọi gần về tinh thần:

| Khái niệm Spring | Laravel/PHP tương đương | Ghi chú |
|---|---|---|
| Proxy bọc bean | `__call` / `__callStatic` magic method | Chặn lời gọi method để chèn logic |
| `InvocationHandler.invoke()` | Bên trong `__call($name, $args)` | Cùng ý: "mọi lời gọi đi qua một chỗ" |
| Proxy của `@Transactional` | `DB::transaction(fn() => ...)` (tường minh) | Laravel làm transaction **bằng tay**, không "thần kỳ" |
| CGLIB subclass | (không có tương đương trực tiếp) | PHP không sinh subclass runtime kiểu này (trừ thư viện proxy như ocramius/proxy-manager) |
| Self-invocation bẫy transaction | **Không gặp** vì transaction là tường minh `DB::transaction(...)` | Đây là lợi thế "ít ma thuật" của Laravel |

Ví dụ `__call` của PHP (gần với proxy):

```php
class LoggingProxy
{
    public function __construct(private object $target) {}

    public function __call($name, $args)        // chặn MỌI lời gọi không khớp method thật
    {
        Log::info("Gọi $name", $args);          // ~ advice trước
        $result = $this->target->$name(...$args); // ~ method.invoke(target, args)
        Log::info("$name xong");                // ~ advice sau
        return $result;
    }
}
```

> 💡 **Bài học đối chiếu**: Laravel làm transaction **tường minh** (`DB::transaction(...)`) nên **không dính bẫy self-invocation**. Spring làm **ngầm qua proxy** nên gọn hơn, nhưng bạn **phải hiểu proxy** để không sa bẫy. Đây là đánh đổi "ma thuật vs tường minh" giữa hai framework — biết rõ giúp bạn dùng đúng.

---

## 💻 Thực hành code

### Bước 1 — Tự viết JDK Dynamic Proxy minh họa

```java
// File: TimedProxyDemo.java
package com.example.auction;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class TimedProxyDemo {

    interface BidService {
        String placeBid(String bidder, long amount);
    }

    // Target THẬT
    static class RealBidService ___ BidService { // Điền từ khóa kế thừa interface
        public String placeBid(String bidder, long amount) {
            return "Đã ghi bid của " + bidder + " = " + amount;
        }
    }

    // InvocationHandler: mọi lời gọi method qua proxy đều rơi vào invoke()
    static class TimingHandler implements InvocationHandler {
        private final Object target;
        TimingHandler(Object target) { this.target = target; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            long start = System.nanoTime();
            ___ { // Điền từ khóa bắt đầu khối xử lý ngoại lệ
                // Điền lệnh gọi method gốc thông qua Reflection
                Object result = ___.___(target, args); 
                return result;
            } ___ { // Điền từ khóa khối luôn thực thi sau try/catch
                long us = (System.nanoTime() - start) / 1_000;
                System.out.println("[proxy] " + method.getName() + " mất " + us + " µs");
            }
        }
    }

    public static void main(String[] args) {
        BidService real = new RealBidService();

        // Tạo proxy JDK: cần ClassLoader + danh sách interface + handler
        BidService proxy = (BidService) Proxy.newProxyInstance(
                BidService.class.getClassLoader(),
                new Class<?>[]{ ___ },   // Điền interface cần proxy
                new TimingHandler(real)
        );

        System.out.println(proxy.placeBid("an", 1_000_000));
        // In ra cả kết quả lẫn dòng [proxy] ... mất ... µs
        System.out.println("Proxy class: " + proxy.getClass().getName());
        // ~ com.sun.proxy.$Proxy0  (class sinh runtime)
    }
}
```

### Bước 2 — Demo self-invocation phá `@Transactional`

Ta sẽ mô phỏng một bug thực tế kinh điển.

```java
// File: BidService.java
package com.example.auction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public ___ BidService { // Điền từ khóa khai báo lớp

    private final BidRepository bidRepository;
    public BidService(BidRepository bidRepository) { this.bidRepository = bidRepository; }

    // ❌ CÁCH SAI: gọi nội bộ this.persist() -> KHÔNG qua proxy -> @Transactional bị bỏ qua
    public void placeBidWrong(Bid bid) {
        ___(bid); // Điền lệnh gọi method persist ở bên dưới (cách gây lỗi)
    }

    @___ // Điền annotation quản lý giao dịch
    public void persist(Bid bid) {
        bidRepository.save(bid);
        if (bid.amount() < 0) ___ ___ IllegalStateException("Bid âm -> đáng lẽ phải rollback"); // Điền từ khóa ném ngoại lệ và tạo mới đối tượng
        // Khi gọi qua placeBidWrong: KHÔNG có transaction -> KHÔNG rollback! (vẫn lưu vào DB)
    }
}
```

**Cách sửa lỗi (Self-injection):**
Sửa lại `BidService` bằng cách tiêm chính nó (bản proxy) vào để gọi vòng lại.

```java
@Service
public class BidService {
    private final BidRepository repo;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private BidService ___;  // Điền tên biến lưu proxy của chính nó (self)

    public BidService(BidRepository repo) { this.repo = repo; }

    // ✅ CÁCH ĐÚNG:
    public void placeBidRight(Bid bid) {
        ___.___(bid);  // Điền lệnh gọi persist thông qua proxy
    }

    @___ // Điền annotation quản lý giao dịch
    public void persist(Bid bid) { 
        repo.save(bid); 
        if (bid.amount() < 0) ___ ___ IllegalStateException("rollback đúng kỳ vọng"); // Điền từ khóa ném ngoại lệ và khởi tạo đối tượng
    }
}
```

> ✅ **Bài tập tự giải thích:** Chạy `placeBidWrong` với một bid âm trong khi có một bid hợp lệ trước đó cùng "đơn vị công việc". Vì transaction không kích hoạt, bid hợp lệ **không rollback**. Sau đó chạy qua `placeBidRight` để thấy rollback đúng. Vì sao chỉ khác ở chỗ "gọi từ đâu"?

### Bước 3 — CHALLENGE: Bẫy `@Async` Self-invocation (Thử thách)

> 🏆 Yêu cầu:
> 1. Thêm annotation `@EnableAsync` vào class cấu hình chính của ứng dụng.
> 2. Trong `AuctionService`, tạo một method tên là `sendEmail(String email)` được gắn `@Async`, bên trong `Thread.sleep(2000)` và in ra tên Thread hiện tại.
> 3. Trong cùng `AuctionService`, tạo một method `notifyWinner()`, và bên trong gọi `sendEmail(...)` trực tiếp (`this.sendEmail(...)`).
> 4. Tạo endpoint gọi `notifyWinner()`. In ra Thread hiện tại ở endpoint và trong method `sendEmail`.
> 5. Chạy thử. Bạn sẽ thấy hai method chạy trên **CÙNG MỘT THREAD** và request bị block 2 giây. Tại sao?
> 6. Áp dụng kỹ thuật Self-injection hoặc chuyển `sendEmail` sang một Bean khác (ví dụ `EmailService`) để sửa lỗi này. Quan sát tên Thread khác nhau.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Self-invocation phá `@Transactional`/`@Cacheable`/`@Async`.** Gọi `this.method()` nội bộ bỏ qua proxy → annotation không ăn, **không báo lỗi**. Tách bean hoặc self-inject proxy.
- **Đặt `@Transactional` trên method `private`/`final`/`static`.** Proxy không intercept được → bị bỏ qua âm thầm. Phải là `public` (với CGLIB là `public`/`protected`, nhưng cứ dùng `public` cho chắc), không `final`.
- **Class `final` cần CGLIB proxy.** CGLIB không kế thừa được class `final` → app báo lỗi không tạo được proxy. Bỏ `final` khỏi class bean cần AOP.
- **`ClassCastException` khi ép proxy về class cụ thể trong môi trường JDK proxy.** JDK proxy chỉ implement interface, không phải subclass → không ép về class concrete được. Hãy phụ thuộc vào **interface**, hoặc bật CGLIB.
- **Tưởng `@Autowired` cho bạn object gốc.** Bạn nhận **proxy** — `getClass()` sẽ thấy `...$$SpringCGLIB$$...` hoặc `$Proxy...`. Đừng so sánh kiểu cụ thể một cách máy móc.
- **Gọi method nội bộ rồi mong cache hit.** `@Cacheable` cũng qua proxy → gọi nội bộ luôn miss cache. Cùng gốc với bẫy transaction.
- **Quên rằng new object không có proxy.** Tự `new BidService()` → không proxy, không AOP, không transaction. Luôn để Spring tạo bean.

---

## 🚀 Liên hệ Spring Boot / Production

- Hiểu proxy là chìa khóa debug lỗi "vì sao `@Transactional` của tôi không rollback?" — câu trả lời 90% là **self-invocation** hoặc method không `public`.
- Spring Boot mặc định `proxyTargetClass=true` (CGLIB) cho nhất quán — nên bạn không cần interface để dùng AOP/transaction, nhưng nhớ tránh `final`.
- Khi viết test, gọi method `@Transactional` từ **một bean khác** (hoặc qua HTTP trong `@SpringBootTest`) để transaction thực sự kích hoạt; gọi trực tiếp trong cùng class sẽ cho kết quả sai lệch.
- `@Async` cũng dính y hệt bẫy này — method async gọi nội bộ sẽ chạy **đồng bộ** (không sang thread khác). Đây là lỗi rất hay gặp trong production.
- Một số thư viện (Spring Data, Spring Security `@PreAuthorize`) đều dựa proxy — kiến thức này áp dụng xuyên suốt, không chỉ transaction.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta hiểu **vì sao phải chú ý proxy** để các tính năng nâng cao (như `@Transactional` và `@Async`) ăn được, cứu bạn khỏi bug khó nhằn.

**Nhiệm vụ Day 35:**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. Viết method lỗi `placeBidWrong` (tự gọi `this.persist()`) mô phỏng bẫy self-invocation (Bước 2). Chạy thử với bid âm để thấy dữ liệu **vẫn bị insert** mà không rollback.
3. Sửa lỗi bằng kỹ thuật tự inject chính mình (self-injection) và lặp lại kịch bản lỗi → lần này **rollback đúng**.
4. Hoàn thành **CHALLENGE** ở Bước 3: Mô phỏng bẫy self-invocation với `@Async` và quan sát cách nó vô tình chạy đồng bộ, sau đó tự sửa.
5. Thử đặt `@Transactional` lên một method `private` và quan sát nó **không ăn**; đổi sang `public` để sửa.
6. Ghi `notes/day-30.md`: giải thích "vì sao gọi từ bean khác thì transaction ăn còn `this.x()` thì không"; đối chiếu với việc Laravel làm transaction tường minh (`DB::transaction`).

> 🎯 Tiêu chí đạt: Bạn điền đúng code JDK Proxy. Tái hiện được bug self-invocation cả với `@Transactional` và `@Async`, hiểu nguyên nhân cốt lõi và biết cách sửa.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Proxy trong Spring AOP là gì? Vì sao cần nó?**
> **Đáp:** Proxy là object **bao quanh** bean gốc, cùng kiểu/interface, nhận lời gọi thay bean gốc để **chèn logic cross-cutting** (transaction, cache, log) rồi chuyển tiếp xuống bean gốc. Spring cần proxy vì AOP weave ở runtime — bean container giữ thực ra là proxy, nhờ vậy các annotation như `@Transactional` hoạt động mà không cần sửa code nghiệp vụ.

**Q2: JDK dynamic proxy và CGLIB khác nhau thế nào?**
> **Đáp:** JDK dynamic proxy tạo class **implement các interface** của target (nên target **phải có interface**), dựa trên `java.lang.reflect.Proxy` + `InvocationHandler`. CGLIB tạo **class con kế thừa** target (không cần interface) bằng cách sinh bytecode subclass. CGLIB proxy được mọi method không `final`/`private`/`static`; JDK chỉ proxy method khai báo trong interface. Spring Boot mặc định dùng CGLIB.

**Q3: Những method nào không proxy được?**
> **Đáp:** Method `final` (CGLIB không override được), `private` (không intercept được), `static` (gắn với class không qua instance). Với JDK proxy còn thêm: method **không khai báo trong interface**. Class `final` cũng không CGLIB-proxy được. Vì vậy method cần AOP/`@Transactional` phải `public` và không `final`.

### Mức Senior

**Q4: Giải thích bẫy self-invocation và cách khắc phục.**
> **Đáp:** Khi một method gọi method khác **trong cùng bean** bằng `this.method()`, lời gọi đi **thẳng trên target**, không qua proxy → mọi annotation AOP (`@Transactional`, `@Cacheable`, `@Async`) trên method được gọi **bị bỏ qua âm thầm**. Vì logic của các annotation đó nằm ở proxy, chỉ kích hoạt khi gọi từ ngoài bean. Khắc phục: (1) tách method sang bean khác rồi inject (sạch nhất); (2) self-injection (`@Autowired @Lazy private Self self;`) và gọi `self.method()`; (3) `AopContext.currentProxy()` với `exposeProxy=true` (ít dùng).

**Q5: Vì sao `@Async` gọi nội bộ lại chạy đồng bộ?**
> **Đáp:** Cùng gốc với self-invocation. `@Async` được hiện thực bằng proxy: proxy đẩy lời gọi method sang thread pool. Khi gọi `this.asyncMethod()` trong cùng bean, lời gọi không qua proxy → không có ai đẩy sang thread khác → method chạy **đồng bộ** trên thread hiện tại. Khắc phục như bẫy self-invocation: gọi từ bean khác.

**Q6: Spring Boot mặc định dùng JDK proxy hay CGLIB? Hệ quả?**
> **Đáp:** Spring Boot mặc định bật `spring.aop.proxy-target-class=true` → dùng **CGLIB** ngay cả khi bean có interface, cho nhất quán. Hệ quả: (1) bạn không cần interface để dùng AOP/transaction; (2) **không được** đặt `final` lên class/method bean (CGLIB không override được); (3) `@Autowired` trả về subclass proxy (`...$$SpringCGLIB$$...`), nên đừng so sánh kiểu concrete một cách máy móc. Spring Framework thuần (không Boot) thì mặc định JDK proxy khi có interface.

---

## ✅ Checklist hoàn thành

- [ ] Hiểu proxy là gì và vì sao bean container giữ thường là proxy
- [ ] Phân biệt JDK dynamic proxy vs CGLIB (điều kiện, giới hạn)
- [ ] Biết method nào không proxy được (`final`/`private`/`static`)
- [ ] Hiểu vì sao `@Async`/`@Cacheable` cũng dính bẫy tự gọi proxy này
- [ ] Đối chiếu với `__call` của PHP và transaction tường minh của Laravel
- [ ] Tự viết JDK Proxy và chạy thử nghiệm (Bước 1)
- [ ] Tái hiện và khắc phục được bẫy Self-invocation (Bước 2)
- [ ] Hoàn thành Challenge: Sửa lỗi `@Async` đồng bộ (Bước 3)
- [ ] Hoàn thành Mini Project Day 35
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Proxying Mechanisms" (`core.html#aop-proxying`)
- Baeldung — "JDK Dynamic Proxies vs CGLIB", "Spring @Transactional Self-Invocation"
- JavaDoc — `java.lang.reflect.Proxy`, `InvocationHandler`
- Laravel/PHP Docs — magic method `__call` (đối chiếu cơ chế chặn lời gọi)
- Bài viết "Understanding Spring AOP Proxies" — đào sâu khi cần
