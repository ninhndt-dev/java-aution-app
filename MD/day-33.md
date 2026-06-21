# Day 33 - Spring MVC Internals

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu một HTTP request đi qua Spring MVC như thế nào, từ `DispatcherServlet` đến JSON trả về.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu kiến trúc **Front Controller** và vai trò **trung tâm** của `DispatcherServlet`.
- Nắm **luồng xử lý request** đầy đủ: `HandlerMapping` → `HandlerAdapter` → `HandlerInterceptor` → controller → `HttpMessageConverter` → response.
- Phân biệt `@RestController` vs `@Controller`; nắm các annotation định tuyến (`@GetMapping`, `@PostMapping`...).
- Dùng đúng `@RequestBody`, `@ResponseBody`, `@PathVariable`, `@RequestParam`, và `ResponseEntity`.
- Phân biệt **`HandlerInterceptor` vs `Filter`** — chọn cái nào khi nào.
- Xử lý lỗi tập trung với `@RestControllerAdvice` + `@ExceptionHandler`.
- Hiểu **content negotiation** (đàm phán định dạng trả về).
- Đối chiếu với pipeline của Laravel: routing → middleware → controller → response.

---

## 🧠 Lý thuyết cốt lõi

### 1. Front Controller: mọi request đi qua một cửa

Spring MVC theo mẫu **Front Controller**: thay vì mỗi URL ánh xạ thẳng tới một file/script, **tất cả** request đi qua **một servlet trung tâm** — `DispatcherServlet`. Servlet này điều phối (dispatch) request tới đúng controller.

```
Client ──HTTP──► [Servlet Container: Tomcat] ──► DispatcherServlet ──► controller phù hợp
```

Đây chính là tư duy giống `public/index.php` của Laravel: một điểm vào duy nhất, rồi router phân phối.

### 2. Luồng xử lý một request qua `DispatcherServlet`

```
                    ┌─────────────────── DispatcherServlet ───────────────────┐
HTTP Request ──────►│                                                          │
                    │ 1. HandlerMapping: URL + method nào → handler (method    │
                    │    controller) nào?                                      │
                    │            │                                             │
                    │            ▼                                             │
                    │ 2. HandlerAdapter: biết cách GỌI handler đó              │
                    │            │                                             │
                    │            ▼                                             │
                    │ 3. preHandle() của các HandlerInterceptor (trước)        │
                    │            │                                             │
                    │            ▼                                             │
                    │ 4. Gọi method controller                                 │
                    │      - bind @PathVariable/@RequestParam                  │
                    │      - @RequestBody: HttpMessageConverter (Jackson)      │
                    │        đọc JSON body → object                            │
                    │            │                                             │
                    │            ▼                                             │
                    │ 5. Controller trả về object / ResponseEntity             │
                    │            │                                             │
                    │            ▼                                             │
                    │ 6. HttpMessageConverter (Jackson) ghi object → JSON      │
                    │            │                                             │
                    │            ▼                                             │
                    │ 7. postHandle() / afterCompletion() của Interceptor      │
                    └──────────────────────────────┬───────────────────────────┘
                                                    ▼
                                          HTTP Response (JSON)
```

Các thành phần chính:
- **`HandlerMapping`**: ánh xạ (URL + HTTP method) → handler. Với annotation, đó là `RequestMappingHandlerMapping`.
- **`HandlerAdapter`**: biết cách *thực thi* handler đã chọn (gọi method controller, resolve tham số).
- **`HandlerInterceptor`**: chèn logic *trước/sau* khi gọi controller (auth, log, đo thời gian).
- **`HttpMessageConverter`**: chuyển đổi giữa body HTTP và object Java. Mặc định dùng **Jackson** cho JSON.

> 💡 Khi bạn `return` một object từ `@RestController`, Spring **không** render view — nó dùng `HttpMessageConverter` (Jackson) serialize object thành JSON. Đó là khác biệt cốt lõi giữa `@RestController` và `@Controller`.

### 3. `@RestController` vs `@Controller`

| | `@Controller` | `@RestController` |
|---|---|---|
| Mục đích | Trả về **view** (template HTML, tên view) | Trả về **dữ liệu** (JSON/XML) |
| Giá trị return | Tên view (`String`) → `ViewResolver` render | Object → `HttpMessageConverter` serialize |
| Cần `@ResponseBody`? | Có (trên mỗi method muốn trả data) | Không (đã ngầm định cho mọi method) |
| Bản chất | annotation đơn | `@Controller` + `@ResponseBody` gộp lại |

```java
// @RestController = @Controller + @ResponseBody (mọi method tự serialize)
@RestController
public class AuctionController { ... }
```

### 4. Annotation định tuyến & trích tham số

```java
@RestController
@RequestMapping("/auctions")          // tiền tố chung cho mọi endpoint trong class
public class AuctionController {

    @GetMapping                        // GET /auctions
    public List<AuctionDto> list() { ... }

    @GetMapping("/{id}")               // GET /auctions/42
    public AuctionDto get(@PathVariable Long id) { ... }   // 42 → id

    @GetMapping("/search")             // GET /auctions/search?status=OPEN&page=2
    public List<AuctionDto> search(
            @RequestParam String status,                    // bắt buộc
            @RequestParam(defaultValue = "0") int page) { ... }

    @PostMapping                       // POST /auctions  (body JSON)
    public ResponseEntity<AuctionDto> create(@RequestBody @Valid CreateAuctionRequest req) {
        AuctionDto created = ...;
        return ResponseEntity.status(HttpStatus.CREATED).body(created);  // 201 + Location
    }
}
```

| Annotation | Lấy dữ liệu từ | Ví dụ |
|---|---|---|
| `@PathVariable` | Một phần của đường dẫn URL | `/auctions/{id}` → `id` |
| `@RequestParam` | Query string hoặc form field | `?status=OPEN` → `status` |
| `@RequestBody` | Toàn bộ body (JSON) → object | body `{...}` → `CreateAuctionRequest` |
| `@ResponseBody` | (return) object → body | object → JSON |

### 5. `ResponseEntity` — kiểm soát toàn bộ response

Khi trả thẳng object, Spring mặc định trả status 200. Khi cần kiểm soát **status code, header, body** → dùng `ResponseEntity`:

```java
return ResponseEntity
        .status(HttpStatus.CREATED)                 // 201
        .header("Location", "/auctions/" + id)      // header tùy biến
        .body(dto);                                 // body

return ResponseEntity.noContent().build();          // 204, không body
return ResponseEntity.notFound().build();           // 404
```

### 6. `HandlerInterceptor` vs `Filter` — chọn cái nào?

Cả hai chèn logic vào pipeline, nhưng ở **tầng khác nhau**:

```
HTTP ──► Servlet Filter (tầng Servlet, BIẾT request/response thô)
              │
              ▼
         DispatcherServlet
              │
              ▼
         HandlerInterceptor (tầng Spring MVC, BIẾT handler/controller nào sắp chạy)
              │
              ▼
         Controller
```

| | `Filter` (Servlet) | `HandlerInterceptor` (Spring MVC) |
|---|---|---|
| Tầng | Servlet container (trước DispatcherServlet) | Bên trong Spring MVC |
| Biết controller nào sẽ chạy? | Không | **Có** (biết `HandlerMethod`) |
| Truy cập gì | `ServletRequest/Response` thô | Có thêm context Spring MVC, handler |
| Dùng cho | CORS, nén gzip, security (Spring Security là filter!), logging thô | Auth theo controller, đo thời gian xử lý, set attribute cho view |
| Method | `doFilter()` | `preHandle()`, `postHandle()`, `afterCompletion()` |

> 💡 Quy tắc nhớ: việc cần làm **trước cả Spring MVC** (CORS, security, đọc/sửa body thô) → **Filter**. Việc cần biết "controller nào sắp chạy" → **Interceptor**. Spring Security hoạt động hoàn toàn ở tầng **Filter** (xem Day 34).

### 7. Xử lý lỗi tập trung: `@RestControllerAdvice`

Thay vì `try/catch` lặp lại trong từng controller, gom xử lý lỗi vào một nơi:

```java
@RestControllerAdvice    // = @ControllerAdvice + @ResponseBody, áp dụng toàn cục
public class GlobalExceptionHandler {

    @ExceptionHandler(BidTooLowException.class)
    public ResponseEntity<ApiError> handleBidTooLow(BidTooLowException ex) {
        var err = new ApiError("BID_TOO_LOW", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(err);  // 409
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)  // lỗi @Valid
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_ERROR", msg)); // 400
    }
}
```

### 8. Content Negotiation (đàm phán định dạng)

`DispatcherServlet` chọn `HttpMessageConverter` dựa trên header `Accept` của client và `Content-Type` của request. Ví dụ `Accept: application/json` → Jackson trả JSON; nếu có thư viện XML và `Accept: application/xml` → trả XML. Mặc định Spring Boot ưu tiên JSON.

---

## 🔁 Đối chiếu với Laravel/PHP

Laravel cũng theo Front Controller (`public/index.php`) với pipeline rất giống:

```
Laravel:  index.php → HTTP Kernel → middleware → Router → Controller → Response
Spring:   Tomcat   → DispatcherServlet → Filter/Interceptor → Handler → Controller → Converter
```

| Khái niệm | Laravel | Spring MVC |
|---|---|---|
| Điểm vào trung tâm | `public/index.php` + HTTP `Kernel` | `DispatcherServlet` |
| Định tuyến | `Route::get('/auctions/{id}', ...)` | `@GetMapping("/auctions/{id}")` |
| Tham số đường dẫn | `{id}` → tham số method | `@PathVariable Long id` |
| Query/form | `$request->query('status')` | `@RequestParam String status` |
| Body JSON → object | `$request->validated()` / DTO | `@RequestBody @Valid CreateAuctionRequest` |
| Trả JSON | `response()->json($data)` hoặc `return $model` | `return dto` (RestController) hoặc `ResponseEntity` |
| Middleware (toàn cục/nhóm) | `Kernel::$middleware`, route middleware | `Filter` (Servlet) hoặc `HandlerInterceptor` |
| Xử lý lỗi tập trung | `App\Exceptions\Handler::render()` | `@RestControllerAdvice` + `@ExceptionHandler` |
| Form Request validation | `class StoreAuctionRequest extends FormRequest` | DTO + Bean Validation `@Valid` + `@NotNull`... |

**Khác biệt tư duy quan trọng:**
- Ở **Laravel**, middleware là một danh sách thực thi tuần tự, bạn tự gọi `$next($request)`. Ở **Spring**, có **hai tầng** middleware: `Filter` (giống middleware Laravel, tầng thô) và `HandlerInterceptor` (sâu hơn, biết controller). Hiểu sự phân tầng này giúp đặt logic đúng chỗ.
- Laravel serialize Eloquent model thành JSON tự động (qua `toArray`/`JsonResource`). Spring serialize object qua **Jackson** (`HttpMessageConverter`) — bạn kiểm soát bằng annotation Jackson (`@JsonIgnore`, `@JsonProperty`) hoặc dùng **DTO** (khuyến nghị mạnh, tránh lộ entity).

> 🧩 `@RestControllerAdvice` ≈ `App\Exceptions\Handler`; `@ExceptionHandler` ≈ các nhánh `render()` xử lý từng loại exception.

---

## 💻 Thực hành code

### Bài 1 — REST controller CRUD cho Auction

```java
package com.example.auction.web;

import com.example.auction.dto.*;
import com.example.auction.service.AuctionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@___ // Điền annotation khai báo REST Controller
@___("/auctions") // Điền annotation quy định prefix chung cho URL
public class AuctionController {

    private final AuctionService auctionService;

    public AuctionController(AuctionService auctionService) {
        this.auctionService = auctionService;
    }

    @GetMapping                                   // GET /auctions
    public List<AuctionDto> list() {
        return auctionService.findAll();
    }

    @GetMapping("/{id}")                          // GET /auctions/42
    public AuctionDto get(@___ Long id) {         // Điền annotation ánh xạ tham số từ URL
        return auctionService.findById(id);       // ném AuctionNotFoundException nếu không có
    }

    @___                                          // Điền annotation định tuyến HTTP POST
    public ResponseEntity<AuctionDto> create(@___ @Valid CreateAuctionRequest req) { // Điền annotation đọc dữ liệu từ JSON body
        AuctionDto created = auctionService.create(req);
        return ResponseEntity
                .created(URI.create("/auctions/" + created.id()))  // 201 + Location
                .body(created);
    }

    @DeleteMapping("/{id}")                        // DELETE /auctions/42
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        auctionService.delete(id);
        return ResponseEntity.noContent().build(); // 204
    }
}
```

### Bài 2 — Endpoint đặt giá + exception nghiệp vụ

```java
// POST /auctions/42/bids  body: { "userId": 7, "amount": 1500000 }
@PostMapping("/{id}/bids")
public ResponseEntity<BidDto> placeBid(
        @PathVariable("id") Long auctionId,
        @RequestBody @Valid PlaceBidRequest req) {

    BidDto bid = auctionService.placeBid(auctionId, req);  // có thể ném BidTooLowException
    return ResponseEntity.status(HttpStatus.CREATED).body(bid);
}
```

```java
// Exception nghiệp vụ: giá đặt thấp hơn giá hiện tại
package com.example.auction.exception;

public class BidTooLowException extends RuntimeException {
    public BidTooLowException(long bid, long current) {
        super("Giá đặt " + bid + " phải lớn hơn giá hiện tại " + current);
    }
}
```

### Bài 3 — Global exception handler

```java
package com.example.auction.web;

import com.example.auction.exception.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@___ // Điền annotation khai báo Advice bắt Exception toàn cục cho REST
public class GlobalExceptionHandler {

    // 409 Conflict cho giá quá thấp
    @___(BidTooLowException.class) // Điền annotation chỉ định loại Exception bắt
    public ResponseEntity<ApiError> handleBidTooLow(BidTooLowException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("BID_TOO_LOW", ex.getMessage(), Instant.now()));
    }

    // 404 cho không tìm thấy phiên
    @ExceptionHandler(AuctionNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AuctionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("AUCTION_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    // 400 cho lỗi validation @Valid
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage()
                : "Dữ liệu không hợp lệ";
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", msg, Instant.now()));
    }
}

// Record làm response lỗi chuẩn (Java 21)
record ApiError(String code, String message, Instant timestamp) {}
```

### Bài 4 — Một `HandlerInterceptor` đo thời gian xử lý

```java
package com.example.auction.web;

import jakarta.servlet.http.*;
import org.springframework.web.servlet.HandlerInterceptor;

public class TimingInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute("startTime", System.currentTimeMillis());
        return true;  // true = cho phép request đi tiếp tới controller
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        long elapsed = System.currentTimeMillis() - (long) req.getAttribute("startTime");
        System.out.println(req.getMethod() + " " + req.getRequestURI() + " mất " + elapsed + "ms");
    }
}
```

```java
// Đăng ký interceptor
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TimingInterceptor()).addPathPatterns("/auctions/**");
    }
}
```

> ✅ **Bài tập tự giải thích:** Vì sao `preHandle` trả `false` lại chặn request không tới controller? Và vì sao logic auth của Spring Security lại nằm ở **Filter** chứ không phải Interceptor?

### Bước 5 — CHALLENGE: Exception Handler hoạt động (Thử thách)

> 🏆 Yêu cầu:
> 1. Chạy ứng dụng.
> 2. Gọi cURL đến `POST /auctions/42/bids` với body JSON bị thiếu tham số (hoặc giá nhỏ hơn giá hiện tại).
> 3. Quan sát HTTP Status trả về. Có phải nó trả về 400 hoặc 409 không?
> 4. Quan sát body response trả về. Nó có được định dạng đúng theo record `ApiError` mà bạn đã cấu hình trong `GlobalExceptionHandler` không?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Trả thẳng Entity JPA ra controller** thay vì DTO → lộ trường nhạy cảm, gây vòng lặp serialize (quan hệ hai chiều `Auction ↔ Bid`), và dễ kéo theo `LazyInitializationException`. Luôn map sang DTO.
- **Quên `@RequestBody`** → Spring tưởng tham số là `@RequestParam`/model attribute → object rỗng/null. Body JSON cần `@RequestBody`.
- **`@PathVariable` không khớp tên** giữa `{id}` và tham số → lỗi. Nếu khác tên, ghi rõ `@PathVariable("id") Long auctionId`.
- **Bắt `Exception` chung chung trong `@ExceptionHandler`** che mất lỗi cụ thể → trả 500 cho cả lỗi validation. Hãy xử lý từng loại exception riêng, từ cụ thể đến tổng quát.
- **Nhầm Filter và Interceptor.** Đặt logic cần thông tin controller vào Filter → không có; đặt security vào Interceptor → quá muộn (đã qua nhiều bước). Phân tầng đúng.
- **Quên `@Valid`** trên `@RequestBody` → các annotation validation (`@NotNull`, `@Min`) không chạy.
- **Trả status sai ngữ nghĩa** (ví dụ tạo mới mà trả 200 thay vì 201; lỗi nghiệp vụ trả 500 thay vì 409/400). REST cần status đúng.

---

## 🚀 Liên hệ Spring Boot / Production

- **DTO + MapStruct/manual mapping**: chuẩn production là tách entity khỏi lớp web. Day 30-32 ta dùng JPA entity, nhưng controller luôn nói chuyện qua DTO.
- **Chuẩn hóa lỗi với `ProblemDetail` (RFC 7807):** Spring 6 hỗ trợ sẵn `ProblemDetail`/`ErrorResponse`. Có thể dùng thay cho `ApiError` tự chế để theo chuẩn ngành.
- **Logging & tracing**: kết hợp Interceptor/Filter với MDC để gắn `traceId` vào mỗi request — phục vụ quan sát trong hệ phân tán.
- **Validation nâng cao**: dùng nhóm validation (`groups`), validation tùy biến (`ConstraintValidator`) cho luật nghiệp vụ phức tạp.
- **CORS**: cấu hình ở tầng Filter (`CorsFilter`) hoặc `@CrossOrigin`/`WebMvcConfigurer` — chạy **trước** controller.
- **Async/streaming**: trả `CompletableFuture`, `DeferredResult`, hoặc `StreamingResponseBody` cho response dài/lớn mà không giữ thread.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta dựng tầng web REST cho Auction: CRUD phiên đấu giá + đặt giá, với xử lý lỗi chuẩn.

**Nhiệm vụ Day 33:**

1. Tạo `AuctionController` với CRUD và các endpoint cơ bản. Hãy điền các annotation tương ứng (Bài 1).
2. Thêm endpoint đặt giá `POST /auctions/{id}/bids` và định nghĩa custom exception (Bài 2).
3. Viết `GlobalExceptionHandler` bắt các Exception ném ra và trả về cấu trúc lỗi chung (Bài 3).
4. Đăng ký `TimingInterceptor` và thử gọi API, kiểm tra console xem có in thời gian xử lý không (Bài 4).
5. Hoàn thành **CHALLENGE** ở Bước 5: Kiểm thử Exception Handler bằng cURL và xác nhận body phản hồi trả về JSON đúng chuẩn `ApiError`.
6. Ghi `notes/day-34.md` phân tích sự khác nhau giữa `@Controller` và `@RestController`.

> 🎯 Tiêu chí đạt: Controller hứng dữ liệu chính xác. Interceptor đo thời gian hiển thị log. Handler bắt lỗi thành công và response đúng format HTTP Status code.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `DispatcherServlet` là gì?**
> **Đáp:** Là Front Controller trung tâm của Spring MVC — mọi HTTP request đi qua nó. Nó điều phối request: dùng `HandlerMapping` tìm controller phù hợp, `HandlerAdapter` gọi controller, `HttpMessageConverter` chuyển đổi body/return, và quản lý các interceptor.

**Q2: `@RestController` khác `@Controller` thế nào?**
> **Đáp:** `@Controller` mặc định trả về tên view để render HTML. `@RestController` = `@Controller` + `@ResponseBody`, nên mọi method tự serialize object thành JSON/XML qua `HttpMessageConverter` thay vì render view.

**Q3: `@RequestBody`, `@PathVariable`, `@RequestParam` lấy dữ liệu từ đâu?**
> **Đáp:** `@PathVariable` lấy từ một phần đường dẫn URL (`/auctions/{id}`); `@RequestParam` lấy từ query string hoặc form field (`?status=OPEN`); `@RequestBody` đọc toàn bộ body (thường JSON) và convert thành object qua Jackson.

**Q4: `ResponseEntity` dùng để làm gì?**
> **Đáp:** Để kiểm soát toàn bộ response: status code, header, và body. Ví dụ trả 201 Created kèm header Location, 204 No Content, hay 404 Not Found — thay vì luôn mặc định 200.

### Mức Senior

**Q5: Mô tả luồng xử lý một request từ DispatcherServlet đến response JSON.**
> **Đáp:** (1) `HandlerMapping` ánh xạ URL+method → handler method. (2) `HandlerAdapter` chuẩn bị gọi handler. (3) `preHandle()` của các interceptor chạy. (4) Resolve tham số: `@PathVariable`/`@RequestParam` được bind, `@RequestBody` được `HttpMessageConverter` (Jackson) đọc từ JSON. (5) Controller trả object/`ResponseEntity`. (6) `HttpMessageConverter` serialize object → JSON theo content negotiation. (7) `postHandle()`/`afterCompletion()` của interceptor chạy. (8) Response gửi về client.

**Q6: Khi nào dùng Filter, khi nào dùng HandlerInterceptor?**
> **Đáp:** Filter ở tầng Servlet, chạy trước cả DispatcherServlet, thao tác request/response thô, không biết controller nào sẽ chạy — dùng cho CORS, security (Spring Security là filter), nén, logging thô. Interceptor ở trong Spring MVC, biết `HandlerMethod` sắp chạy — dùng cho auth theo controller, đo thời gian, set attribute. Nguyên tắc: cần thông tin trước cả MVC → Filter; cần biết controller → Interceptor.

**Q7: Vì sao không nên trả entity JPA trực tiếp ra controller?**
> **Đáp:** Vì lộ cấu trúc nội bộ/trường nhạy cảm; quan hệ hai chiều gây vòng lặp serialize; truy cập quan hệ lazy ngoài transaction gây `LazyInitializationException`; và làm API gắn chặt với schema DB. Dùng DTO để tách lớp web khỏi tầng persistence.

**Q8: `@RestControllerAdvice` hoạt động ra sao và lợi ích?**
> **Đáp:** Đây là một bean toàn cục chứa các `@ExceptionHandler`. Khi controller ném exception, DispatcherServlet tìm handler phù hợp (theo kiểu exception) trong advice để tạo response lỗi chuẩn. Lợi ích: gom xử lý lỗi về một nơi, trả status + body nhất quán, tránh `try/catch` lặp lại; có thể giới hạn phạm vi theo package/annotation.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ lại được luồng request qua DispatcherServlet (HandlerMapping → Adapter → Interceptor → Converter)
- [ ] Phân biệt `@RestController` vs `@Controller`
- [ ] Dùng đúng `@RequestBody`/`@PathVariable`/`@RequestParam`/`ResponseEntity`
- [ ] Phân biệt được Filter vs HandlerInterceptor và biết chọn đúng
- [ ] Viết được `@RestControllerAdvice` xử lý nhiều loại lỗi với status đúng
- [ ] Đối chiếu đúng với pipeline middleware/router của Laravel
- [ ] Implement `AuctionController` với các annotation đúng
- [ ] Cấu hình Global Exception Handler
- [ ] Đăng ký Interceptor cho log timing
- [ ] Hoàn thành Challenge: Test cURL check exception HTTP Status và JSON Format
- [ ] Hoàn thành Mini Project CRUD + endpoint đặt giá + xử lý lỗi
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Web MVC: DispatcherServlet", "Handler Mapping/Adapter", "Interceptors"
- Spring Framework Reference — "Annotated Controllers", "Exception Handling (ControllerAdvice)"
- Baeldung — "Spring MVC Tutorial", "Spring @RequestMapping", "Spring ResponseEntity"
- RFC 7807 — Problem Details for HTTP APIs (và `ProblemDetail` của Spring 6)
- Laravel Docs — "Middleware", "Routing", "Error Handling" (để đối chiếu)
