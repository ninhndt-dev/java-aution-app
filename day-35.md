# Day 35 - Security Internals

> **Giai đoạn:** Spring Internals
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Spring Security là một chuỗi filter như thế nào, và cấu hình theo kiểu Spring Security 6.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu Spring Security thực chất là **một chuỗi Servlet Filter** — không phải "ma thuật".
- Nắm các thành phần lõi: `DelegatingFilterProxy`, `FilterChainProxy`, `SecurityFilterChain`.
- Phân biệt rõ **Authentication (xác thực)** vs **Authorization (phân quyền)**.
- Hiểu `SecurityContextHolder`, đối tượng `Authentication`.
- Nắm chuỗi xác thực: `AuthenticationManager` → `AuthenticationProvider` → `UserDetailsService` + `PasswordEncoder` (BCrypt).
- Cấu hình theo **kiểu mới Spring Security 6**: bean `SecurityFilterChain` + lambda DSL.
- Phân quyền theo **URL** và theo **method** (`@PreAuthorize`); hiểu **CSRF**, sơ lược JWT/OAuth2.
- Đối chiếu với `guard`, middleware `auth`, Sanctum/Passport, Gate/Policy của Laravel.

---

## 🧠 Lý thuyết cốt lõi

### 1. Spring Security = một chuỗi Filter chèn trước app

Spring Security **không** can thiệp vào controller của bạn. Nó hoạt động hoàn toàn ở **tầng Servlet Filter**, **trước** `DispatcherServlet` (nhớ Day 34: Filter đứng trước MVC).

```
HTTP Request
   │
   ▼
[Servlet Container: Tomcat]
   │
   ▼
DelegatingFilterProxy ("springSecurityFilterChain")  ── cầu nối Servlet ↔ Spring
   │
   ▼
FilterChainProxy   ── chọn đúng SecurityFilterChain theo URL
   │
   ▼
┌──────── SecurityFilterChain (chuỗi ~15 filter, theo thứ tự) ────────┐
│  CsrfFilter → ... → UsernamePasswordAuthenticationFilter →          │
│  ... → ExceptionTranslationFilter → AuthorizationFilter            │
└─────────────────────────────────────────────────────────────────────┘
   │  (nếu qua hết)
   ▼
DispatcherServlet → Controller của bạn
```

- **`DelegatingFilterProxy`**: một Servlet Filter "trống", chỉ ủy quyền cho một bean Spring tên `springSecurityFilterChain`. Đây là cây cầu nối thế giới Servlet với thế giới Spring.
- **`FilterChainProxy`**: bean nhận ủy quyền, bên trong chứa một hoặc nhiều `SecurityFilterChain`. Nó chọn chuỗi phù hợp với request rồi chạy lần lượt các filter.
- **`SecurityFilterChain`**: danh sách các filter bảo mật theo thứ tự (CSRF, authentication, authorization...).

> 💡 Toàn bộ "bảo mật" của Spring chỉ là các filter chạy tuần tự. Hiểu điều này rồi bạn sẽ "đọc vị" được mọi lỗi 401/403: nó luôn xảy ra ở một filter cụ thể trong chuỗi.

### 2. Authentication vs Authorization — đừng nhầm

| | Authentication (Xác thực) | Authorization (Phân quyền) |
|---|---|---|
| Câu hỏi | "Bạn là **ai**?" | "Bạn được phép **làm gì**?" |
| Kết quả | Đối tượng `Authentication` (đã đăng nhập hay chưa) | Cho/từ chối truy cập resource |
| HTTP lỗi | **401** Unauthorized (chưa/đăng nhập sai) | **403** Forbidden (đã đăng nhập nhưng không đủ quyền) |
| Ví dụ | Kiểm tra username + password đúng | Chỉ ADMIN mới được đóng phiên đấu giá |

> ⚠️ Lỗi 401 và 403 hay bị nhầm. 401 = "tôi chưa biết bạn là ai" (xác thực thất bại). 403 = "tôi biết bạn rồi, nhưng bạn không có quyền" (phân quyền thất bại).

### 3. `SecurityContextHolder` và đối tượng `Authentication`

Sau khi xác thực thành công, Spring lưu thông tin người dùng vào `SecurityContextHolder` — mặc định gắn theo **thread** (`ThreadLocal`).

```java
// Lấy người dùng hiện tại ở bất cứ đâu trong luồng xử lý request
Authentication auth = SecurityContextHolder.getContext().getAuthentication();
String username = auth.getName();                          // "alice"
Collection<? extends GrantedAuthority> roles = auth.getAuthorities();  // [ROLE_USER]
boolean loggedIn = auth.isAuthenticated();
```

Đối tượng `Authentication` chứa:
- **Principal**: thường là `UserDetails` (thông tin người dùng).
- **Credentials**: mật khẩu (thường bị xóa sau xác thực).
- **Authorities**: danh sách quyền/role (`GrantedAuthority`, ví dụ `ROLE_ADMIN`).

> 💡 Vì context gắn theo `ThreadLocal`, nó tự "biến mất" khi thread kết thúc request — giống tinh thần stateless của Laravel mỗi request. Nhưng cẩn thận với code chạy async/thread khác (context không tự truyền sang).

### 4. Chuỗi xác thực: ai kiểm tra mật khẩu?

```
UsernamePasswordAuthenticationFilter (bắt được username/password từ request)
   │  tạo Authentication "chưa xác thực"
   ▼
AuthenticationManager (thường là ProviderManager)
   │  thử lần lượt các provider
   ▼
AuthenticationProvider (ví dụ DaoAuthenticationProvider)
   │  ┌──────────────────────────────────────────────┐
   │  │ 1. UserDetailsService.loadUserByUsername()    │ → lấy user (kèm hash mật khẩu) từ DB
   │  │ 2. PasswordEncoder.matches(raw, hash)         │ → so khớp mật khẩu (BCrypt)
   │  └──────────────────────────────────────────────┘
   ▼
Trả Authentication "đã xác thực" (có authorities) → lưu vào SecurityContextHolder
```

Các mảnh ghép bạn cần cung cấp:
- **`UserDetailsService`**: tải user theo username (từ DB, LDAP...). Trả về `UserDetails` (username, hash password, authorities).
- **`PasswordEncoder`**: mã hóa/so khớp mật khẩu. Khuyến nghị **`BCryptPasswordEncoder`** (chậm có chủ đích, có salt tự động).

> ⚠️ KHÔNG BAO GIỜ lưu mật khẩu dạng plaintext hay băm bằng MD5/SHA-1 trần. Dùng BCrypt (hoặc Argon2/PBKDF2). `PasswordEncoder.matches()` so khớp raw password với hash đã lưu.

### 5. Cấu hình kiểu mới — Spring Security 6 (lambda DSL)

Spring Security 6 **bỏ** `WebSecurityConfigurerAdapter` (cách cũ kế thừa class). Thay vào đó, bạn khai báo một **bean `SecurityFilterChain`** dùng lambda DSL:

```java
package com.example.auction.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 1. Phân quyền theo URL
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/auctions").permitAll()              // ai cũng xem được danh sách
                .requestMatchers("/auctions/*/bids").authenticated()   // đặt giá: phải đăng nhập
                .requestMatchers("/auctions/*/close").hasRole("ADMIN") // đóng phiên: chỉ ADMIN
                .anyRequest().authenticated()                          // còn lại: phải đăng nhập
            )
            // 2. Cách đăng nhập
            .httpBasic(Customizer.withDefaults())   // hoặc .formLogin(...) cho web UI
            // 3. CSRF: REST API stateless thường tắt (xem mục 7)
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();   // chuẩn để hash mật khẩu
    }
}
```

> 💡 Mỗi `SecurityFilterChain` bean là một cấu hình. Có thể khai báo nhiều bean (gắn `@Order`) cho các nhóm URL khác nhau (ví dụ một cho `/api/**`, một cho `/admin/**`).

### 6. Phân quyền: theo URL vs theo method

**Theo URL** (như trên, trong `authorizeHttpRequests`): khai báo tập trung theo pattern đường dẫn.

**Theo method** (`@PreAuthorize`): khai báo ngay tại service/controller, linh hoạt theo logic:

```java
@Configuration
@EnableMethodSecurity   // BẬT method security (cần thiết cho @PreAuthorize)
public class MethodSecurityConfig { }
```

```java
@Service
public class AuctionService {

    @PreAuthorize("hasRole('ADMIN')")               // chỉ ADMIN gọi được
    public void closeAuction(Long id) { ... }

    @PreAuthorize("#userId == authentication.principal.id")  // chỉ chính chủ
    public void cancelMyBid(Long userId, Long bidId) { ... }
}
```

| | Phân quyền theo URL | Phân quyền theo method (`@PreAuthorize`) |
|---|---|---|
| Nơi khai báo | `SecurityFilterChain` | Trên method (service/controller) |
| Phù hợp | Luật "thô" theo đường dẫn | Luật chi tiết, dùng cả tham số method |
| Biểu thức | matcher đơn giản | SpEL đầy đủ (`#param`, `authentication`...) |

### 7. CSRF — khi nào bật, khi nào tắt

CSRF (Cross-Site Request Forgery) là tấn công lợi dụng cookie phiên của người dùng. Spring Security **mặc định bật CSRF** cho request thay đổi state (POST/PUT/DELETE).

- **App web có session + cookie** (form login): **GIỮ** CSRF bật (Spring tự chèn token vào form).
- **REST API stateless** (xác thực bằng JWT/Bearer token, không dùng cookie session): thường **tắt** CSRF, vì không có cookie để bị lợi dụng — token Bearer không tự gửi kèm như cookie.

> ⚠️ "Tắt CSRF cho gọn" là sai lầm phổ biến. Chỉ tắt khi API thực sự stateless (không dựa cookie). Nếu vẫn dùng cookie/session → giữ CSRF.

### 8. JWT / OAuth2 (sơ lược)

- **JWT (JSON Web Token)**: token tự chứa (header.payload.signature), server không cần lưu session. Client gửi `Authorization: Bearer <token>`. Spring có sẵn resource-server hỗ trợ verify JWT.
- **OAuth2 / OIDC**: ủy quyền qua nhà cung cấp (Google, Keycloak...). Spring Security có `spring-security-oauth2-client` (login qua bên thứ ba) và `oauth2-resource-server` (bảo vệ API bằng token).

Ta sẽ dùng HTTP Basic/form cho đơn giản hôm nay; JWT là bước nâng cao tiếp theo.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel | Spring Security |
|---|---|---|
| Xác thực | `guard` (web/session, api/token) | `SecurityFilterChain` + `AuthenticationManager` |
| Middleware bảo vệ route | `->middleware('auth')` | `.authenticated()` trong `authorizeHttpRequests` |
| Lấy user hiện tại | `Auth::user()` / `$request->user()` | `SecurityContextHolder.getContext().getAuthentication()` |
| Token API | **Sanctum** / **Passport** (OAuth2) | JWT / OAuth2 resource-server |
| Hash mật khẩu | `Hash::make()` (bcrypt mặc định) | `BCryptPasswordEncoder.encode()` |
| Kiểm tra mật khẩu | `Hash::check($raw, $hash)` | `passwordEncoder.matches(raw, hash)` |
| Nạp user từ DB | `UserProvider` / `User` model | `UserDetailsService.loadUserByUsername()` |
| Phân quyền chi tiết | **Gate** / **Policy** (`Gate::allows`, `@can`) | `@PreAuthorize` / method security (SpEL) |
| Role/permission | `$user->hasRole()` (spatie/permission) | `GrantedAuthority` / `hasRole('ADMIN')` |
| CSRF | bật mặc định cho web (`@csrf` trong form) | bật mặc định, tắt cho REST stateless |

**Khác biệt tư duy quan trọng:**
- Laravel dùng **guard** (web dựa session, api dựa token) cấu hình trong `config/auth.php`. Spring dùng **chuỗi filter** với `AuthenticationManager` — linh hoạt hơn nhưng nhiều mảnh ghép hơn.
- `Gate`/`Policy` của Laravel ≈ `@PreAuthorize` + SpEL của Spring. Cả hai đều cho phép viết luật phân quyền chi tiết theo tham số.
- **Sanctum (token đơn giản) ≈ JWT/opaque token**; **Passport (OAuth2 đầy đủ) ≈ Spring OAuth2**. Khi chuyển API Laravel + Sanctum sang Spring, hướng tự nhiên là JWT resource-server.

> 🧩 `BCryptPasswordEncoder` của Spring và `Hash::make()` của Laravel **đều là bcrypt** — về lý thuyết hash do Laravel tạo có thể được Spring verify (cùng thuật toán), miễn cùng cost factor và định dạng `$2y$`/`$2a$`.

---

## 💻 Thực hành code

### Bài 1 — `UserDetailsService` lấy user từ DB

```java
package com.example.auction.security;

import com.example.auction.repository.UserRepository;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
public class AuctionUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AuctionUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Không tìm thấy user: " + username));

        // Trả về UserDetails mà Spring Security hiểu được
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPasswordHash())   // hash BCrypt đã lưu trong DB
                .roles(user.getRole())              // "USER" → authority ROLE_USER
                .build();
    }
}
```

### Bài 2 — Cấu hình `SecurityFilterChain` bảo vệ endpoint đặt giá

```java
package com.example.auction.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity   // bật @PreAuthorize
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Xem danh sách & chi tiết phiên: công khai
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/auctions/**").permitAll()
                // Đặt giá: BẮT BUỘC đăng nhập
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/auctions/*/bids").authenticated()
                // Đóng phiên: chỉ ADMIN
                .requestMatchers("/auctions/*/close").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())   // demo: dùng Basic Auth
            .csrf(AbstractHttpConfigurer::disable);  // REST stateless → tắt CSRF
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### Bài 3 — Phân quyền theo method cho "đóng phiên"

```java
@Service
public class AuctionService {

    // Chỉ ADMIN mới được đóng phiên đấu giá (chốt người thắng)
    @PreAuthorize("hasRole('ADMIN')")
    public void closeAuction(Long auctionId) {
        // ... chốt giá thắng, đổi trạng thái CLOSED ...
    }
}
```

### Bài 4 — Lấy user hiện tại trong controller đặt giá

```java
@PostMapping("/{id}/bids")
public ResponseEntity<BidDto> placeBid(
        @PathVariable Long id,
        @RequestBody @Valid PlaceBidRequest req,
        Authentication authentication) {   // Spring tự inject user đang đăng nhập

    String username = authentication.getName();   // người đặt giá
    BidDto bid = auctionService.placeBid(id, username, req);
    return ResponseEntity.status(HttpStatus.CREATED).body(bid);
}
```

### Bài 5 — Mã hóa mật khẩu khi tạo user

```java
@Service
public class RegistrationService {
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    // ... constructor injection ...

    public void register(String username, String rawPassword) {
        String hash = passwordEncoder.encode(rawPassword);  // BCrypt hash + salt
        userRepository.save(new User(username, hash, "USER"));
    }
}
```

> ✅ **Bài tập tự giải thích:** Gọi `POST /auctions/1/bids` khi **chưa** đăng nhập → bạn nhận status nào? Đăng nhập với user thường rồi gọi `POST /auctions/1/close` → status nào? Giải thích 401 vs 403.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tắt CSRF vô tội vạ "cho hết lỗi"** dù app vẫn dùng cookie/session → mở cửa cho tấn công CSRF. Chỉ tắt khi API thực sự stateless (Bearer token).
- **Lưu mật khẩu plaintext hoặc dùng `NoOpPasswordEncoder`.** Luôn dùng BCrypt. `NoOpPasswordEncoder` chỉ để demo, **cấm** dùng thật.
- **Nhầm `hasRole('ADMIN')` và `hasAuthority('ADMIN')`.** `hasRole('ADMIN')` ngầm thêm tiền tố → kiểm tra authority `ROLE_ADMIN`. Nếu authority bạn lưu không có tiền tố `ROLE_`, sẽ không khớp.
- **Quên `@EnableMethodSecurity`** → `@PreAuthorize` bị bỏ qua âm thầm (không lỗi, nhưng không bảo vệ gì).
- **Dùng `WebSecurityConfigurerAdapter` (đã bị xóa ở Security 6).** Phải chuyển sang bean `SecurityFilterChain`.
- **Thứ tự matcher sai** trong `authorizeHttpRequests`: matcher cụ thể phải đặt **trước** matcher tổng quát (`anyRequest()`). Spring áp dụng theo thứ tự khai báo.
- **Tưởng `SecurityContext` tự truyền qua thread async.** `ThreadLocal` không tự truyền — cần `DelegatingSecurityContextExecutor` hay cấu hình propagation.

---

## 🚀 Liên hệ Spring Boot / Production

- **JWT resource-server**: với microservices, dùng `spring-boot-starter-oauth2-resource-server` để verify JWT do Identity Provider (Keycloak, Auth0, Cognito) phát hành — stateless, scale tốt.
- **Method security cho domain logic**: `@PreAuthorize`/`@PostAuthorize` cho phép đặt luật ngay tại tầng service, gần với nghiệp vụ (giống Policy của Laravel).
- **Audit & rate limiting**: kết hợp filter tùy biến để log đăng nhập thất bại, chặn brute-force (Bucket4j), và tích hợp với hệ thống quan sát.
- **HTTPS & secure headers**: production luôn ép HTTPS, bật HSTS, cấu hình CSP, `Strict-Transport-Security` (qua `headers()` DSL).
- **Tách `SecurityFilterChain` theo nhóm**: một chain cho `/api/**` (JWT, stateless) và một chain cho `/admin/**` (form login, session) — dùng `@Order` để ưu tiên.
- **Đừng tự cuộn (roll your own) crypto/auth.** Dựa vào Spring Security đã được kiểm thử kỹ thay vì tự viết logic băm/so khớp.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta thêm bảo mật: phải đăng nhập mới được đặt giá; chỉ admin mới đóng được phiên.

**Nhiệm vụ Day 35:**
1. Tạo entity/bảng `User` (username, password hash, role) và `UserRepository.findByUsername`.
2. Viết `AuctionUserDetailsService implements UserDetailsService` nạp user từ DB.
3. Khai báo `SecurityConfig` với bean `SecurityFilterChain`:
   - `GET /auctions/**` → `permitAll()`.
   - `POST /auctions/*/bids` → `authenticated()`.
   - `/auctions/*/close` → `hasRole("ADMIN")`.
4. Khai báo `BCryptPasswordEncoder` làm `PasswordEncoder`.
5. Bật `@EnableMethodSecurity`, gắn `@PreAuthorize("hasRole('ADMIN')")` lên `closeAuction`.
6. Trong controller đặt giá, inject `Authentication` để gắn người đặt giá vào bid.
7. Test: đặt giá khi chưa đăng nhập → **401**; user thường gọi đóng phiên → **403**; admin đóng phiên → thành công.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Spring Security hoạt động ở tầng nào?**
> **Đáp:** Ở tầng Servlet Filter, đứng trước `DispatcherServlet`. `DelegatingFilterProxy` ủy quyền cho `FilterChainProxy`, bên trong chứa `SecurityFilterChain` — một chuỗi filter bảo mật chạy tuần tự (CSRF, authentication, authorization...) trước khi request tới controller.

**Q2: Authentication và Authorization khác nhau thế nào?**
> **Đáp:** Authentication (xác thực) trả lời "bạn là ai" — kiểm tra danh tính (username/password), thất bại trả 401. Authorization (phân quyền) trả lời "bạn được làm gì" — kiểm tra quyền truy cập resource, thất bại trả 403.

**Q3: `UserDetailsService` và `PasswordEncoder` để làm gì?**
> **Đáp:** `UserDetailsService.loadUserByUsername()` nạp thông tin user (username, hash password, authorities) từ DB/nguồn khác. `PasswordEncoder` (thường `BCryptPasswordEncoder`) mã hóa mật khẩu khi đăng ký và so khớp khi đăng nhập (`matches(raw, hash)`).

**Q4: Cách bảo vệ một endpoint chỉ cho ADMIN truy cập?**
> **Đáp:** Theo URL: `.requestMatchers("/auctions/*/close").hasRole("ADMIN")` trong `authorizeHttpRequests`. Theo method: `@PreAuthorize("hasRole('ADMIN')")` (cần `@EnableMethodSecurity`).

### Mức Senior

**Q5: Mô tả chuỗi xác thực từ filter đến khi lưu vào SecurityContext.**
> **Đáp:** Filter (vd `UsernamePasswordAuthenticationFilter`) trích username/password, tạo `Authentication` chưa xác thực, gọi `AuthenticationManager` (`ProviderManager`). Manager thử các `AuthenticationProvider`; `DaoAuthenticationProvider` gọi `UserDetailsService.loadUserByUsername()` lấy user, rồi `PasswordEncoder.matches()` so mật khẩu. Nếu khớp, trả `Authentication` đã xác thực (kèm authorities), lưu vào `SecurityContextHolder` (ThreadLocal).

**Q6: Khi nào bật/tắt CSRF?**
> **Đáp:** Bật khi app dùng cookie/session (form login) — vì cookie tự gửi kèm nên dễ bị CSRF, Spring chèn token để chống. Tắt khi API hoàn toàn stateless dùng Bearer token (JWT) trong header — token không tự gửi như cookie nên không có bề mặt CSRF. Tắt CSRF khi vẫn dùng cookie là lỗ hổng.

**Q7: Vì sao BCrypt phù hợp lưu mật khẩu hơn SHA-256?**
> **Đáp:** BCrypt được thiết kế **chậm có chủ đích** (cost factor điều chỉnh được) và có **salt tích hợp**, làm brute-force/rainbow-table tốn kém. SHA-256 nhanh, không salt mặc định → dễ bị tấn công từ điển hàng loạt. Các thuật toán chuyên cho mật khẩu (BCrypt, Argon2, PBKDF2) mới phù hợp.

**Q8: Khác biệt phân quyền theo URL và theo method? Khi nào dùng cái nào?**
> **Đáp:** Theo URL (`authorizeHttpRequests`) khai báo tập trung theo pattern đường dẫn — tốt cho luật thô, dễ nhìn tổng quan. Theo method (`@PreAuthorize` + SpEL) đặt luật ngay tại service, dùng được tham số method (`#userId == authentication.principal.id`) — tốt cho luật chi tiết theo dữ liệu. Thường kết hợp cả hai: URL chặn lớp ngoài, method chốt luật nghiệp vụ.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được Spring Security là chuỗi filter (DelegatingFilterProxy → FilterChainProxy → SecurityFilterChain)
- [ ] Phân biệt rõ Authentication (401) vs Authorization (403)
- [ ] Hiểu chuỗi AuthenticationManager → Provider → UserDetailsService + PasswordEncoder
- [ ] Cấu hình được bean `SecurityFilterChain` kiểu Security 6 (lambda DSL)
- [ ] Dùng được phân quyền theo URL và `@PreAuthorize` theo method
- [ ] Hiểu khi nào bật/tắt CSRF
- [ ] Đối chiếu đúng với guard/Sanctum/Gate/Policy của Laravel
- [ ] Hoàn thành Mini Project: đăng nhập mới đặt giá, admin mới đóng phiên
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Security Reference — "Architecture", "Servlet Authentication", "Authorization"
- Spring Security Reference — "Migrating to Security 6" (bỏ `WebSecurityConfigurerAdapter`)
- Spring Security Reference — "Method Security (@PreAuthorize)", "CSRF", "OAuth2 Resource Server"
- Baeldung — "Spring Security: SecurityFilterChain", "BCryptPasswordEncoder"
- Laravel Docs — "Authentication", "Sanctum", "Authorization (Gates & Policies)" (để đối chiếu)
