# Day 42 - Testing (JUnit 5, Mockito, Spring Test, Testcontainers)

> **Giai đoạn:** Production Engineering
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn viết test cho Spring Boot tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **kim tự tháp test**: unit → integration → e2e, và vì sao tỉ lệ quan trọng.
- Thành thạo **JUnit 5**: `@Test`, `@BeforeEach`, `assertThrows`, `@ParameterizedTest`.
- Dùng **Mockito**: `mock`/`when`/`verify`, `@Mock`/`@InjectMocks`.
- Hiểu các **test slice** của Spring: `@WebMvcTest` (+ `MockMvc`), `@DataJpaTest`, `@SpringBootTest`.
- Dùng **Testcontainers** chạy Postgres/Redis/Kafka **thật** trong test.
- Viết assertion đẹp với **AssertJ** và test **concurrency**.
- Đối chiếu với PHPUnit, Feature vs Unit test, `RefreshDatabase`, `Http::fake` của Laravel.

---

## 🧠 Lý thuyết cốt lõi

### 1. Kim tự tháp test

```
            ▲  ÍT, CHẬM, ĐẮT
        ╱  e2e  ╲        (toàn hệ thống thật, qua HTTP)
      ╱ integration ╲    (nhiều bean thật, DB thật/Testcontainers)
    ╱     unit        ╲  NHIỀU, NHANH, RẺ (1 class, mock phần còn lại)
   ────────────────────
```

- **Unit test**: kiểm tra **một đơn vị** (thường một class/method) **cô lập**, mock mọi phụ thuộc. Nhanh (mili-giây), nhiều nhất.
- **Integration test**: kiểm tra **nhiều thành phần phối hợp** — service + repository + DB thật. Chậm hơn, ít hơn.
- **E2E test**: kiểm tra **toàn luồng** qua biên ngoài (HTTP endpoint → DB). Chậm nhất, ít nhất.

> 💡 Quy tắc: **nhiều unit, vừa phải integration, ít e2e**. Đảo ngược (nhiều e2e) → bộ test chậm, dễ vỡ, khó tìm nguyên nhân lỗi ("ice cream cone anti-pattern").

### 2. JUnit 5 — bộ khung test

| Annotation/API | Tác dụng |
|---|---|
| `@Test` | Đánh dấu method là test case |
| `@BeforeEach` / `@AfterEach` | Chạy trước/sau **mỗi** test (setup/teardown) |
| `@BeforeAll` / `@AfterAll` | Chạy **một lần** trước/sau cả class (method `static`) |
| `assertThrows(Ex.class, () -> ...)` | Khẳng định method ném exception |
| `@ParameterizedTest` + `@ValueSource`/`@CsvSource` | Chạy cùng test với nhiều bộ dữ liệu |
| `@DisplayName("...")` | Tên dễ đọc cho test |
| `@Nested` | Nhóm test con theo ngữ cảnh |

```java
@ParameterizedTest
@CsvSource({ "100, 90, false", "100, 110, true" })   // current, bid, hợp lệ?
void bid_should_be_valid_only_when_higher(long current, long bid, boolean valid) {
    assertThat(bid > current).isEqualTo(valid);
}
```

### 3. Mockito — giả lập phụ thuộc

Unit test cần **cô lập** class đang test khỏi DB, network... → thay phụ thuộc bằng **mock** (object giả ta điều khiển hành vi).

```java
@ExtendWith(MockitoExtension.class)
class BidServiceTest {
    @Mock AuctionRepository repo;          // tạo mock
    @InjectMocks BidService service;       // inject các @Mock vào service thật

    @Test
    void rejects_lower_bid() {
        var auction = new Auction("Tranh");
        auction.setHighestBid(100);
        when(repo.findById(1L)).thenReturn(Optional.of(auction));   // dạy mock trả gì

        assertThrows(IllegalStateException.class,
                () -> service.placeBid(1L, "bob", 90));

        verify(repo, never()).save(any());   // khẳng định KHÔNG lưu khi bid thấp
    }
}
```

- `when(...).thenReturn(...)` / `.thenThrow(...)`: định nghĩa hành vi mock (stubbing).
- `verify(mock).method(...)`: khẳng định mock **được gọi** (interaction), kèm `times()`/`never()`.
- `@Mock` tạo mock; `@InjectMocks` tạo instance thật và tiêm các mock vào.

> 💡 **Mock vs Stub vs Spy:** mock = object giả kiểm tra tương tác; stub = trả giá trị cố định; spy = bọc object thật, chỉ override một phần. Mockito gộp khá nhiều trong một API.

### 4. Spring test slices — đừng load cả app khi không cần

`@SpringBootTest` nạp **toàn bộ** ApplicationContext (chậm). Nhiều khi chỉ cần một lát cắt:

| Slice | Nạp gì | Dùng để test |
|---|---|---|
| `@WebMvcTest(XController.class)` | Chỉ tầng web (controller, filter, `MockMvc`) — **mock service** | Controller, validation, JSON, status code |
| `@DataJpaTest` | Chỉ tầng JPA (repository, EntityManager) + DB nhúng/Testcontainers, **transaction rollback sau mỗi test** | Query repository, mapping entity |
| `@SpringBootTest` | **Toàn bộ** context (có thể kèm server thật `webEnvironment`) | Integration/e2e đầy đủ |

```java
// @WebMvcTest: chỉ controller, service được @MockBean
@WebMvcTest(AuctionController.class)
class AuctionControllerTest {
    @Autowired MockMvc mvc;
    @MockBean BidService bidService;

    @Test
    void returns_400_when_bid_too_low() throws Exception {
        doThrow(new IllegalStateException("thấp")).when(bidService)
            .placeBid(1L, "bob", 90);

        mvc.perform(post("/auctions/1/bids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidder\":\"bob\",\"amount\":90}"))
           .andExpect(status().isBadRequest());
    }
}
```

> 💡 Slice test **nhanh hơn nhiều** `@SpringBootTest` vì chỉ nạp phần cần. Đây là khác biệt lớn với Laravel (Feature test thường boot cả app).

### 5. Testcontainers — DB/Redis/Kafka THẬT trong test

Mock không kiểm được hành vi DB thật (SQL dialect, transaction, optimistic lock, ZSet Redis). **Testcontainers** khởi động **container thật** (Postgres/Redis/Kafka) trong lúc test, dọn sạch sau khi xong. Test chạy trên hạ tầng **giống production**, không phải H2 "gần giống".

```java
@SpringBootTest
@Testcontainers
class BidIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16");

    // Trỏ Spring tới container vừa khởi động (port động)
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", postgres::getJdbcUrl);
        reg.add("spring.datasource.username", postgres::getUsername);
        reg.add("spring.datasource.password", postgres::getPassword);
    }
    // ... test với Postgres THẬT ...
}
```

> 💡 Testcontainers là lý do bạn nên **bỏ H2** cho integration test: optimistic locking, `FOR UPDATE`, JSON column, ZSet... chỉ đúng trên DB thật.

### 6. AssertJ — assertion đọc như tiếng Anh

```java
assertThat(auction.getHighestBid()).isEqualTo(150);
assertThat(bidders).hasSize(3).contains("alice").doesNotContain("eve");
assertThatThrownBy(() -> service.placeBid(1L, "x", 0))
    .isInstanceOf(IllegalStateException.class)
    .hasMessageContaining("không cao hơn");
```

Chuỗi (fluent), thông báo lỗi rõ ràng hơn `assertEquals`. Là chuẩn de-facto trong dự án Spring.

### 7. Test concurrency

Bug đồng thời (Day 18-19, 38) không lộ ra với test tuần tự. Bắn nhiều thread cùng lúc để chứng minh không lost update:

```java
int threads = 200;
var pool = Executors.newFixedThreadPool(threads);
var latch = new CountDownLatch(threads);
for (int i = 0; i < threads; i++) {
    int price = 1000 + i;
    pool.submit(() -> { try { service.placeBidWithRetry(1L, "u"+price, price); }
                        finally { latch.countDown(); } });
}
latch.await();
assertThat(repo.findById(1L).orElseThrow().getHighestBid()).isEqualTo(1199);
```

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel/PHPUnit | Spring/JUnit |
|---|---|---|
| Khung test | PHPUnit | JUnit 5 |
| Test method | `public function test_x()` / `#[Test]` | `@Test` |
| Setup/teardown | `setUp()` / `tearDown()` | `@BeforeEach` / `@AfterEach` |
| Data provider | `#[DataProvider]` | `@ParameterizedTest` + `@CsvSource` |
| Mock | `Mockery::mock()` / `$this->mock()` | Mockito `@Mock` / `@InjectMocks` |
| Test HTTP | Feature test `$this->postJson(...)` | `@WebMvcTest` + `MockMvc` |
| DB sạch mỗi test | `RefreshDatabase` trait | `@DataJpaTest` (rollback) / Testcontainers |
| Mock HTTP ngoài | `Http::fake()` | Mockito mock client / WireMock |
| DB thật trong test | `mysql` test database | **Testcontainers** (container ephemeral) |
| Assertion | `$this->assertEquals(...)` | AssertJ `assertThat(...)` |

**Đối chiếu trực tiếp:**

```php
// Laravel Feature test
public function test_rejects_low_bid(): void {
    $this->postJson('/auctions/1/bids', ['bidder' => 'bob', 'amount' => 90])
         ->assertStatus(400);
}
```
```java
// Spring @WebMvcTest
@Test void rejects_low_bid() throws Exception {
    mvc.perform(post("/auctions/1/bids").contentType(APPLICATION_JSON)
            .content("{\"bidder\":\"bob\",\"amount\":90}"))
       .andExpect(status().isBadRequest());
}
```

> 💡 Khác biệt lớn: Laravel `RefreshDatabase` migrate/rollback DB thật mỗi test; `@DataJpaTest` của Spring **tự rollback transaction** sau mỗi test (nhanh, sạch). Còn cho integration test "y như production", Spring có **Testcontainers** — Laravel không có sẵn cơ chế tương đương tích hợp chặt như vậy.

---

## 💻 Thực hành code

## 💻 Thực hành code

### Bước 1 — Phụ thuộc

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-test</artifactId>  <!-- JUnit5 + Mockito + AssertJ + MockMvc -->
  <scope>___</scope> <!-- Điền scope để thư viện test không lọt vào file JAR production -->
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
```

### Bước 2 — Unit test `BidService` bằng Mockito

```java
// File: BidServiceTest.java
package com.example.auction.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    @___ AuctionRepository repo; // Điền annotation để tạo object giả cho Interface Repository
    @___ BidEventRecorder recorder;
    @___ BidService service;     // Điền annotation để Inject các Mock ở trên vào BidService

    @Test
    @DisplayName("Bid thấp hơn giá hiện tại bị từ chối, không lưu DB")
    void rejects_lower_bid() {
        var auction = new Auction("Tranh"); auction.setHighestBid(100);
        ___(repo.findById(1L)).thenReturn(Optional.of(auction)); // Điền hàm định nghĩa hành vi (stubbing)

        assertThatThrownBy(() -> service.placeBid(1L, "bob", 90))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("không cao hơn");

        verify(repo, never()).save(any());   // không được lưu khi bid không hợp lệ
    }

    @Test
    @DisplayName("Bid cao hơn -> cập nhật highestBid & bidder")
    void accepts_higher_bid() {
        var auction = new Auction("Tranh"); auction.setHighestBid(100);
        when(repo.findById(1L)).thenReturn(Optional.of(auction));

        service.placeBid(1L, "alice", 150);

        assertThat(auction.getHighestBid()).isEqualTo(150);
        assertThat(auction.getHighestBidder()).isEqualTo("alice");
        verify(recorder).recordAttempt(1L, "alice", 150);   // có ghi audit
    }

    @ParameterizedTest
    @CsvSource({ "100, 90, false", "100, 100, false", "100, 101, true" })
    void higher_only(long current, long bid, boolean accepted) {
        var auction = new Auction("X"); auction.setHighestBid(current);
        when(repo.findById(1L)).thenReturn(Optional.of(auction));
        if (accepted) {
            service.placeBid(1L, "u", bid);
            assertThat(auction.getHighestBid()).isEqualTo(bid);
        } else {
            assertThatThrownBy(() -> service.placeBid(1L, "u", bid))
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
```

### Bước 3 — Test Slices (Controller & Repo)

```java
// File: AuctionControllerTest.java (Chỉ nạp Tầng Web)
package com.example.auction.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@___(AuctionController.class) // Điền Annotation để báo Spring Boot chỉ load mỗi Controller này
class AuctionControllerTest {

    @Autowired MockMvc mvc;
    @___ BidService bidService;  // Điền annotation để Inject mock dưới dạng Bean (vì context Web cần Bean)

    @Test
    void bid_too_low_returns_400() throws Exception {
        doThrow(new IllegalStateException("Giá không cao hơn"))
            .when(bidService).placeBid(1L, "bob", 90);

        mvc.perform(post("/auctions/1/bids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidder\":\"bob\",\"amount\":90}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void valid_bid_returns_201() throws Exception {
        mvc.perform(post("/auctions/1/bids")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bidder\":\"alice\",\"amount\":150}"))
           .andExpect(status().isCreated());
        verify(bidService).placeBid(1L, "alice", 150);
    }
}

// File: AuctionRepositoryTest.java (Chỉ nạp JPA và DB thật)
package com.example.auction.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@___ // Điền annotation để chỉ nạp Repository và Config JPA
@Testcontainers
class AuctionRepositoryTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired AuctionRepository repo;

    @Test
    void save_and_find_with_version() {
        var saved = repo.save(new Auction("Đồng hồ cổ"));
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isZero();   // @Version khởi tạo 0
    }
}
```

> 💡 `@DataJpaTest` mặc định dùng DB nhúng; thêm `@Testcontainers` + `@ServiceConnection` để chạy trên **Postgres thật** — bắt được lỗi dialect/optimistic lock mà H2 giấu.

### Bước 4 — Integration test luồng đặt giá (concurrency)

```java
// File: BidConcurrencyIT.java
package com.example.auction.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
class BidConcurrencyIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired BidService bidService;
    @Autowired AuctionRepository repo;

    @Test
    void no_lost_update_under_200_threads() throws Exception {
        Long id = repo.save(new Auction("Hot item")).getId();

        int n = 200;
        var pool = java.util.concurrent.Executors.___(32); // Tạo pool 32 threads
        var latch = new java.util.concurrent.___(n); // Điền class dùng để chờ N threads hoàn thành
        for (int i = 0; i < n; i++) {
            int price = 1000 + i;
            pool.submit(() -> {
                try { bidService.placeBidWithRetry(id, "u" + price, price); }
                catch (Exception ignored) {}     // bid bị vượt là bình thường
                finally { latch.countDown(); }
            });
        }
        latch.await();

        // Giá cao nhất PHẢI = 1199, không bao giờ tụt do lost update (Day 38 optimistic lock)
        assertThat(repo.findById(id).orElseThrow().getHighestBid()).isEqualTo(1199L);
    }
}
```

### Bước 5 — CHALLENGE: Tự động hóa Test

> 🏆 Yêu cầu:
> 1. Chạy tự động `mvn test` (hoặc `mvn verify`).
> 2. Đọc kết quả trong Console, tìm cách đọc Report Test (thường nằm ở `target/surefire-reports/`).
> 3. Tự làm 1 test case thất bại (VD sửa giá mong muốn thành 9999) và đọc log xem AssertJ mô tả lỗi ĐẸP như thế nào so với `assertEquals` cũ.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Mock cả thứ không cần (over-mocking).** Mock luôn cả những thứ đáng test thật → test "xanh" nhưng không chứng minh gì. Unit test logic thuần; integration test cho DB.
- **Test trên H2 thay vì DB thật.** H2 "gần giống" Postgres → query/optimistic-lock/JSON chạy khác production. Dùng Testcontainers cho integration.
- **Lạm dụng `@SpringBootTest`.** Nạp cả app cho mọi test → bộ test chậm lê thê. Dùng slice (`@WebMvcTest`, `@DataJpaTest`) khi đủ.
- **`@WebMvcTest` mà quên `@MockBean` service.** Controller cần bean service nhưng slice không nạp tầng dưới → context fail. Phải mock.
- **`verify` mọi tương tác (over-verification).** Test giòn, đổi chút implementation là vỡ. Chỉ verify tương tác **quan trọng** (vd "không lưu khi bid sai").
- **`when(...)` cho stub không dùng tới (UnnecessaryStubbingException).** Mockito strict báo lỗi stub thừa — xoá hoặc dùng `lenient()`.
- **Test phụ thuộc thứ tự / state chung.** Test phải độc lập; `@DataJpaTest` rollback giúp việc này, nhưng static state (cache, Redis) thì phải tự dọn.
- **Khởi động container Testcontainers cho từng test class riêng lẻ.** Chậm. Dùng `static` container (chia sẻ trong class) hoặc Singleton container pattern.
- **Quên Docker khi chạy CI.** Testcontainers cần Docker daemon. CI runner phải có Docker.

---

## 🚀 Liên hệ Spring Boot / Production

- **Coverage** đo bằng JaCoCo; đặt ngưỡng trong CI nhưng **đừng tôn thờ 100%** — coverage cao mà assertion yếu vẫn vô dụng.
- **Testcontainers reuse** (`testcontainers.reuse.enable=true`) tăng tốc local; **Singleton container** chia sẻ giữa nhiều test class.
- **`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`/`WebTestClient`** cho e2e qua HTTP thật.
- **Test Kafka** bằng `@EmbeddedKafka` (nhẹ) hoặc `KafkaContainer` (giống production); test consumer idempotent (Day 40) bằng cách gửi trùng event.
- **Mutation testing** (PIT) đánh giá chất lượng test sâu hơn coverage: cố tình "đột biến" code xem test có bắt không.
- **Contract testing** (Spring Cloud Contract / Pact) cho hệ microservice — đảm bảo producer/consumer event không phá vỡ hợp đồng.
- **CI**: chạy unit test ở mọi PR (nhanh), integration/Testcontainers ở stage riêng (chậm hơn) — phản ánh kim tự tháp.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Luồng đặt giá là trái tim của hệ thống (Day 18-19 concurrency, Day 38 optimistic lock). Hôm nay ta phủ test cho nó ở **cả ba tầng**: unit (logic), web (controller), integration (DB thật + concurrency).

**Nhiệm vụ Day 42:**

1. **Unit test** `BidService` theo định dạng điền khuyết: thêm `@Mock`, `@InjectMocks` cho class.
2. **`@WebMvcTest`** `AuctionController`: bid hợp lệ → 201, bid thấp → 400. Nhớ điền `@MockBean`.
3. **`@DataJpaTest`** + Testcontainers Postgres: lưu/đọc `Auction`, kiểm tra `@Version` khởi tạo 0 và tăng sau update.
4. **Integration test concurrency**: Hoàn thành `BidConcurrencyIT` sử dụng `Executors.newFixedThreadPool` và `CountDownLatch`, khẳng định `highestBid` cuối = giá lớn nhất, **không lost update** (chứng minh optimistic lock hoạt động trên DB thật).
5. Hoàn thành **CHALLENGE** ở Bước 5: Chạy `mvn test` và đọc report test fail với AssertJ.

**Kết quả mong đợi:** một bộ test phản ánh kim tự tháp (nhiều unit, ít integration), chạy nhanh, bắt được cả lỗi logic lẫn lỗi đồng thời; integration test chạy trên Postgres thật qua Testcontainers chứng minh hệ thống an toàn dưới tải song song.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Kim tự tháp test là gì?**
> **Đáp:** Mô hình phân bổ test: **nhiều unit** (nhanh, rẻ, cô lập một class), **vừa phải integration** (nhiều thành phần + DB thật), **ít e2e** (toàn hệ qua HTTP, chậm). Tỉ lệ này giúp bộ test nhanh, ổn định, dễ tìm nguyên nhân lỗi. Đảo ngược (nhiều e2e) là anti-pattern.

**Q2: Unit test khác integration test thế nào?**
> **Đáp:** Unit test kiểm tra **một đơn vị cô lập**, mock mọi phụ thuộc (DB, network) → nhanh, xác định lỗi chính xác. Integration test kiểm tra **nhiều thành phần phối hợp thật** (service + repository + DB) → bắt lỗi tích hợp/SQL/transaction mà unit test không thấy, nhưng chậm hơn.

**Q3: `@Mock` và `@InjectMocks` làm gì?**
> **Đáp:** `@Mock` tạo một object giả (Mockito) thay cho phụ thuộc. `@InjectMocks` tạo instance **thật** của class đang test rồi **tiêm các `@Mock`** vào (qua constructor/field). Kết hợp với `@ExtendWith(MockitoExtension.class)` để khởi tạo tự động.

**Q4: `@WebMvcTest` khác `@SpringBootTest` ở đâu?**
> **Đáp:** `@WebMvcTest` chỉ nạp **tầng web** (controller, filter, `MockMvc`), service phải `@MockBean` → nhanh, tập trung test controller/JSON/status. `@SpringBootTest` nạp **toàn bộ** ApplicationContext (có thể kèm server thật) → cho integration/e2e đầy đủ nhưng chậm.

### Mức Senior

**Q5: Vì sao nên dùng Testcontainers thay vì H2 cho integration test?**
> **Đáp:** H2 chỉ "gần giống" Postgres/MySQL — khác dialect, không hỗ trợ đầy đủ JSON column, `FOR UPDATE`, hành vi optimistic lock, sequence... → test xanh nhưng production lỗi. Testcontainers khởi động **DB thật** (cùng version production) trong container ephemeral, dọn sạch sau test → môi trường test **giống production**, bắt đúng lỗi.

**Q6: Làm sao test được bug đồng thời (lost update) một cách tin cậy?**
> **Đáp:** Bắn **nhiều thread** (vd 200) cùng thực hiện thao tác tranh chấp (đặt giá) qua `ExecutorService` + `CountDownLatch` để đồng bộ điểm bắt đầu/kết thúc, chạy trên **DB thật** (Testcontainers). Sau cùng khẳng định bất biến (`highestBid` = giá lớn nhất, không tụt). Chạy **nhiều lần** vì race condition không tất định. Đây là cách chứng minh optimistic lock (Day 38) thực sự chống lost update.

**Q7: Over-mocking và over-verification gây hại gì?**
> **Đáp:** Over-mocking: mock cả thứ đáng test thật → test "xanh" nhưng không chứng minh đúng đắn, bỏ lọt lỗi tích hợp. Over-verification: `verify` mọi tương tác nội bộ → test **giòn**, gắn chặt vào implementation, refactor nhỏ là vỡ hàng loạt dù hành vi không đổi. Nên test **hành vi/đầu ra**, chỉ verify tương tác thực sự quan trọng.

---

## ✅ Checklist hoàn thành

- [ ] Hiểu kim tự tháp test và tỉ lệ unit/integration/e2e
- [ ] Dùng được JUnit 5 (`@Test`, `@BeforeEach`, `assertThrows`, `@ParameterizedTest`)
- [ ] Dùng được Mockito (`@Mock`/`@InjectMocks`, `when`/`verify`)
- [ ] Dùng đúng slice: `@WebMvcTest` + MockMvc, `@DataJpaTest`, `@SpringBootTest`
- [ ] Chạy được Testcontainers Postgres trong integration test
- [ ] Viết assertion bằng AssertJ và test concurrency
- [ ] Hoàn thành Mini Project: bộ test luồng đặt giá (unit + web + integration + concurrency)
- [ ] Hoàn thành Challenge: Tự động hóa Test với Maven và đọc Report
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- JUnit 5 User Guide — annotations, assertions, parameterized tests
- Mockito Documentation — `@Mock`/`@InjectMocks`, `when`/`verify`, argument matchers
- Spring Boot Docs — "Testing" (test slices `@WebMvcTest`/`@DataJpaTest`, `@MockBean`, Testcontainers `@ServiceConnection`)
- Testcontainers Docs — modules Postgres/Redis/Kafka, Singleton container pattern
- AssertJ — "Core assertions guide"
- Laravel Docs — "Testing: Getting Started", "HTTP Tests", `RefreshDatabase`, `Http::fake` (đối chiếu)
