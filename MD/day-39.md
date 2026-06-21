# Day 39 - Redis Caching (Spring Cache & Redis)

> **Giai đoạn:** Infrastructure & Integration
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu caching & Redis trong Spring tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **vì sao cần cache**: giảm tải DB, giảm latency, tăng throughput.
- Nắm **Spring Cache abstraction**: `@EnableCaching`, `@Cacheable`, `@CachePut`, `@CacheEvict` + `key`/`condition`/`unless`.
- Hiểu **CacheManager** và `RedisCacheManager`, cách Spring serialize value (JSON/Jackson).
- Nắm **cache-aside pattern**, **TTL**, thiết kế **key**, và bài toán **cache invalidation**.
- Hiểu các "bệnh" của cache: **stampede**, **penetration**, **avalanche** — và cách chữa.
- Biết **Redis là gì** và các kiểu dữ liệu (String/Hash/List/Set/Sorted Set), dùng **ZSet** làm bảng xếp hạng giá.
- Đối chiếu với `Cache` facade, Redis driver, `Cache::remember()` của Laravel.

---

## 🧠 Lý thuyết cốt lõi

### 1. Vì sao cần cache?

DB là tài nguyên **đắt và hữu hạn**: mỗi query tốn round-trip mạng + parse + I/O đĩa. Khi cùng một dữ liệu được đọc đi đọc lại (thông tin item đấu giá, profile user...), việc hỏi DB mỗi lần là lãng phí.

**Cache** = lưu kết quả ở nơi đọc **rất nhanh** (RAM, in-memory) để lần sau khỏi hỏi DB.

```
KHÔNG cache:                  CÓ cache (cache-aside):
client → app → DB (~5ms)      client → app → cache (~0.3ms) ── hit ──► trả ngay
        (mỗi request)                       │
                                            miss ──► DB (~5ms) ──► ghi lại cache
```

Lợi ích: **giảm latency** (RAM nhanh hơn đĩa cả trăm lần), **giảm tải DB** (DB rảnh tay phục vụ ghi), **tăng throughput** (phục vụ nhiều request hơn cùng phần cứng).

> 💡 Quy tắc vàng: cache cái **đọc nhiều, đổi ít, chấp nhận hơi cũ một chút** (eventual consistency). Đừng cache dữ liệu phải luôn chính xác tuyệt đối từng mili-giây (như số dư tài khoản đang giao dịch).

### 2. Cache-aside pattern (đọc-bên-cạnh)

Mẫu phổ biến nhất, và là cái Spring `@Cacheable` cài sẵn:

```
1. Đọc cache theo key.
2. HIT  -> trả luôn.
3. MISS -> đọc DB -> ghi vào cache (kèm TTL) -> trả.
```

Khi ghi/sửa dữ liệu: hoặc **cập nhật lại cache** (`@CachePut`) hoặc **xoá entry** (`@CacheEvict`) để lần đọc sau nạp lại từ DB. Đây là phần "khó nhất của khoa học máy tính" — **cache invalidation**.

### 3. Spring Cache abstraction

Spring tách **khai báo cache** khỏi **công nghệ cache cụ thể**. Bạn dùng annotation; backend có thể là Redis, Caffeine, EhCache... mà code không đổi.

Bật bằng `@EnableCaching` trên một `@Configuration`. Ba annotation chính:

| Annotation | Khi nào chạy | Tác dụng |
|---|---|---|
| `@Cacheable` | Trước method | Có trong cache → trả luôn, **không gọi method**. Miss → gọi method rồi lưu kết quả. |
| `@CachePut` | **Luôn** gọi method | Gọi method rồi **ghi đè** cache bằng kết quả mới. Dùng cho update. |
| `@CacheEvict` | Quanh method | **Xoá** entry (hoặc cả cache với `allEntries = true`). Dùng cho delete/invalidate. |

```java
@Cacheable(value = "auctions", key = "#id",
           condition = "#id > 0",        // chỉ cache khi điều kiện đúng (xét TRƯỚC khi gọi)
           unless = "#result == null")    // KHÔNG cache nếu kết quả null (xét SAU khi gọi)
public AuctionDto getAuction(Long id) { ... }
```

- **`key`**: biểu thức SpEL sinh khoá. Mặc định ghép từ tham số. Có thể `#root.methodName`, `#item.id`...
- **`condition`**: xét **trước** khi gọi method (dựa trên tham số) — false thì không dùng cache.
- **`unless`**: xét **sau** khi có `#result` — true thì **không lưu** kết quả (vd không cache `null`).

> ⚠️ `@Cacheable` cũng là **proxy-based** như `@Transactional` (Day 35, Day 32). **Self-invocation cũng nuốt cache!** Gọi method `@Cacheable` bằng `this` từ trong cùng class → cache không hoạt động.

### 4. CacheManager & RedisCacheManager

Phía sau annotation là một **`CacheManager`** — nhà máy tạo/quản lý các `Cache`. Với Redis, Spring Boot tự cấu hình `RedisCacheManager` khi bạn có `spring-boot-starter-data-redis` + `spring-boot-starter-cache`.

```
@Cacheable ──► CacheInterceptor ──► CacheManager ──► RedisCache ──► Lettuce ──► Redis server
```

`RedisCacheManager` cho phép đặt **TTL mặc định**, **serializer**, và **TTL riêng cho từng cache name**.

### 5. Serialization (JSON/Jackson)

Redis lưu **bytes**. Object Java phải được serialize. Mặc định Spring dùng **JDK serialization** (object phải `Serializable`, output nhị phân khó đọc, dễ vỡ khi đổi class). **Khuyến nghị mạnh:** chuyển sang **JSON (Jackson)** — dễ đọc, ngôn ngữ-trung lập, các service khác đọc được.

```java
RedisCacheConfiguration.defaultCacheConfig()
    .serializeValuesWith(SerializationPair.fromSerializer(
        new GenericJackson2JsonRedisSerializer()));   // value dạng JSON
```

> 💡 Dùng JSON còn giúp bạn `redis-cli GET key` ra đọc được ngay khi debug — giống xem cache trong Laravel.

### 6. TTL & thiết kế key

- **TTL (Time To Live):** thời gian sống của entry. Hết hạn → Redis tự xoá. TTL chống "cache thiu vĩnh viễn". Đặt theo độ "đổi" của dữ liệu: item đấu giá đang chạy → TTL ngắn (vài giây); profile user → TTL dài (vài phút/giờ).
- **Thiết kế key:** đặt prefix theo namespace để tránh đụng và dễ xoá hàng loạt: `auction:item:42`, `auction:leaderboard:42`. Spring tự thêm prefix `<cacheName>::` (vd `auctions::42`).

### 7. Các "bệnh" của cache & cách chữa

| Bệnh | Là gì | Cách chữa |
|---|---|---|
| **Cache penetration** | Query key **không tồn tại** liên tục → mọi lần đều miss, đập thẳng DB (kẻ tấn công lợi dụng) | Cache cả giá trị "không tồn tại" (null sentinel, TTL ngắn) hoặc Bloom filter |
| **Cache stampede / dog-pile** | Một key **hot hết hạn** đồng thời → hàng nghìn request cùng miss, cùng đập DB nạp lại | Lock nạp lại (chỉ 1 request nạp, số còn lại chờ); hoặc làm mới sớm trước khi hết hạn (early refresh) |
| **Cache avalanche** | Nhiều key hết hạn **cùng lúc** (TTL giống nhau) → DB bị sóng thần | Thêm jitter ngẫu nhiên vào TTL; warm-up cache |

### 8. Redis là gì? Các kiểu dữ liệu

**Redis** = kho dữ liệu **in-memory** (chạy trong RAM), single-thread cho lệnh (atomic tự nhiên), cực nhanh. Không chỉ là "key-value string" mà có nhiều cấu trúc:

| Kiểu | Mô tả | Dùng cho |
|---|---|---|
| **String** | Chuỗi/số (có `INCR` atomic) | Cache value, counter, rate-limit |
| **Hash** | Map field→value trong một key | Lưu object nhiều trường (`HSET user:1 name ...`) |
| **List** | Danh sách có thứ tự (push/pop 2 đầu) | Hàng đợi đơn giản, timeline |
| **Set** | Tập không trùng | Tag, quan hệ "đã xem" |
| **Sorted Set (ZSet)** | Set kèm **score**, tự sắp xếp theo score | **Bảng xếp hạng (leaderboard)**, top-N, hàng đợi ưu tiên |

> 💡 **ZSet là ngôi sao cho bài đấu giá:** lưu (bidder → giá) với giá là score. `ZADD leaderboard:42 1500 alice` rồi `ZREVRANGE leaderboard:42 0 9 WITHSCORES` lấy ngay **top 10 người trả giá cao nhất** — O(log N), không cần `ORDER BY` đập DB.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel/PHP | Spring/Redis |
|---|---|---|
| Lấy-hoặc-tính-rồi-cache | `Cache::remember($key, $ttl, fn () => ...)` | `@Cacheable(value, key)` |
| Ghi cache | `Cache::put($key, $val, $ttl)` | `@CachePut` |
| Xoá cache | `Cache::forget($key)` / `Cache::flush()` | `@CacheEvict(key=...)` / `allEntries=true` |
| Driver Redis | `CACHE_DRIVER=redis`, `predis`/`phpredis` | `spring-boot-starter-data-redis` (Lettuce) |
| Thao tác Redis thô | `Redis::zadd(...)`, `Redis::zrevrange(...)` | `RedisTemplate` / `StringRedisTemplate` `.opsForZSet()` |
| TTL | tham số `$ttl` (giây hoặc `Carbon`) | `entryTtl(Duration.ofSeconds(...))` |
| Tag cache | `Cache::tags(['x'])->...` | Theo `cacheName` (mỗi tên là một nhóm) |

**Đối chiếu trực tiếp:**

```php
// Laravel: cache-aside trong một dòng
$item = Cache::remember("auction:item:$id", 60, fn () => Auction::find($id));
```
```java
// Spring: khai báo, không phải gọi tay
@Cacheable(value = "auctions", key = "#id", unless = "#result == null")
public AuctionDto getAuction(Long id) { return repo.findById(id)...; }
```

```php
// Laravel: leaderboard bằng Redis ZSet thô
Redis::zadd("auction:leaderboard:$id", $amount, $bidder);
$top = Redis::zrevrange("auction:leaderboard:$id", 0, 9, ['withscores' => true]);
```
```java
// Spring: y hệt, qua RedisTemplate
redis.opsForZSet().add("auction:leaderboard:" + id, bidder, amount);
Set<TypedTuple<String>> top =
    redis.opsForZSet().reverseRangeWithScores("auction:leaderboard:" + id, 0, 9);
```

> 💡 Tư duy giống hệt Laravel. Khác lớn nhất: Spring thiên về **khai báo qua annotation** (`@Cacheable`) cho cache-aside, còn thao tác ZSet thì vẫn dùng API thô (`RedisTemplate`) như `Redis::` facade.

---

## 💻 Thực hành code

## 💻 Thực hành code

### Bước 1 — Phụ thuộc & cấu hình

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>___</artifactId> <!-- Điền dependency để kết nối Redis -->
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>___</artifactId> <!-- Điền dependency bật chức năng Caching của Spring -->
</dependency>
```

```yaml
# application.yml
spring:
  data:
    redis:
      host: localhost
      port: 6379
  cache:
    type: redis
    redis:
      time-to-live: 60000   # TTL mặc định 60s (mili-giây)
```

### Bước 2 — Bật cache & cấu hình serializer JSON + TTL riêng từng cache

```java
// File: CacheConfig.java
package com.example.auction.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.*;
import java.time.Duration;
import java.util.Map;

@Configuration
@___   // Điền annotation BẮT BUỘC để bật toàn bộ cơ chế @Cacheable/@CachePut/@CacheEvict
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory cf) {

        // Cấu hình mặc định: value dạng JSON (dễ đọc, không cần Serializable)
        RedisCacheConfiguration defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(60))
            .disableCachingNullValues()   // không cache null -> chống lưu rác
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        // TTL RIÊNG cho từng cache name: item nóng sống ngắn hơn
        Map<String, RedisCacheConfiguration> perCache = Map.of(
            "auctions", defaultCfg.entryTtl(Duration.ofSeconds(10)),   // item đấu giá đổi nhanh
            "users",    defaultCfg.entryTtl(Duration.ofMinutes(30))    // profile đổi chậm
        );

        return RedisCacheManager.builder(cf)
            .cacheDefaults(defaultCfg)
            .withInitialCacheConfigurations(perCache)
            .build();
    }
}
```

### Bước 3 — Cache / Ghi đè / Xoá cache cho thông tin item

```java
// File: AuctionQueryService.java
package com.example.auction.service;

import org.springframework.cache.annotation.*;
import org.springframework.stereotype.Service;

@Service
public class AuctionQueryService {

    private final AuctionRepository repo;
    public AuctionQueryService(AuctionRepository repo) { this.repo = repo; }

    // ĐỌC: miss -> query DB rồi lưu vào cache "auctions" với key = id
    @___(value = "auctions", key = "#id", unless = "#result == null") // Điền annotation cho việc Đọc/Lưu cache
    public AuctionDto getAuction(Long id) {
        System.out.println("⛏️  MISS -> query DB cho auction " + id);
        return repo.findById(id).map(AuctionDto::from).orElse(null);
    }

    // UPDATE: luôn chạy method, rồi GHI ĐÈ cache bằng kết quả mới
    @___(value = "auctions", key = "#result.id") // Điền annotation cho việc GHI ĐÈ cache (Dùng khi update DB)
    public AuctionDto updateTitle(Long id, String title) {
        var a = repo.findById(id).orElseThrow();
        a.setItemName(title);
        return AuctionDto.from(repo.save(a));
    }

    // ĐÓNG phiên: xoá entry để lần đọc sau nạp lại trạng thái mới nhất
    @___(value = "auctions", key = "#id") // Điền annotation cho việc XOÁ cache
    public void closeAuction(Long id) {
        var a = repo.findById(id).orElseThrow();
        a.setClosed(true);
        repo.save(a);
    }
}
```

### Bước 4 — Leaderboard giá bằng Redis Sorted Set (ZSet)

```java
// File: BidLeaderboardService.java
package com.example.auction.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;

@Service
public class BidLeaderboardService {

    private final StringRedisTemplate redis;
    public BidLeaderboardService(StringRedisTemplate redis) { this.redis = redis; }

    private String key(Long auctionId) { return "auction:leaderboard:" + auctionId; }

    /**
     * Ghi nhận giá của một bidder. ZADD ghi đè score cũ nếu bidder đã có
     * -> mỗi bidder chỉ giữ giá CAO NHẤT của họ.
     */
    public void recordBid(Long auctionId, String bidder, long amount) {
        redis.___.add(key(auctionId), bidder, amount);   // Điền method lấy object thao tác với Sorted Set (ZSet)
    }

    /** Top N người trả giá cao nhất, giảm dần theo score (giá). */
    public List<TypedTuple<String>> topBidders(Long auctionId, int n) {
        Set<TypedTuple<String>> top =
            redis.opsForZSet().reverseRangeWithScores(key(auctionId), 0, n - 1);
        return top == null ? List.of() : List.copyOf(top);
    }

    /** Hạng của một bidder (0 = cao nhất). */
    public Long rankOf(Long auctionId, String bidder) {
        return redis.opsForZSet().reverseRank(key(auctionId), bidder);
    }

    /** Xoá leaderboard khi đóng phiên. */
    public void clear(Long auctionId) {
        redis.delete(key(auctionId));
    }
}
```

### Bước 5 — CHALLENGE: Tái hiện Cache Stampede và sửa bằng `sync`

> 🏆 Yêu cầu:
> 1. Viết 1 vòng lặp tạo 10 Threads cùng gọi method `getAuction(42L)` đồng thời (mô phỏng 10 người xem cùng lúc khi cache hết hạn).
> 2. Quan sát log xem chữ `⛏️  MISS -> query DB...` được in ra bao nhiêu lần. (Nếu nhiều hơn 1, tức là DB bị "đập" đồng thời).
> 3. Sửa lỗi này bằng cách thêm 1 thuộc tính `sync = true` vào annotation ở Bài 3.
> 4. Chạy lại test (lúc này chỉ 1 thread được gọi DB, 9 thread khác tự động chờ và dùng lại cache khi thread kia nạp xong).

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Self-invocation nuốt `@Cacheable`** (như `@Transactional`). Gọi method cache bằng `this` từ trong cùng class → proxy không chặn → cache không chạy. Tách bean.
- **Cache cả `null` mà không kiểm soát** → cache penetration. Dùng `unless = "#result == null"` hoặc `disableCachingNullValues()`, hoặc chủ động cache null sentinel TTL ngắn.
- **Quên invalidate khi update qua đường khác.** Sửa DB bằng SQL trực tiếp hay service khác mà không `@CacheEvict` → cache "thiu". Mọi đường ghi phải đồng bộ cache.
- **TTL giống hệt nhau cho nhiều key** → avalanche khi cùng hết hạn. Thêm jitter.
- **Dùng JDK serialization mặc định.** Output nhị phân, vỡ khi đổi class, không đọc được bằng `redis-cli`. Chuyển sang Jackson JSON.
- **Cache object quá lớn / cây quan hệ JPA lazy.** Serialize entity còn `@OneToMany` lazy → `LazyInitializationException` hoặc nuốt cả DB vào Redis. **Cache DTO**, không cache entity.
- **Tin Redis luôn còn dữ liệu.** Redis có thể evict (chính sách `maxmemory`) hay restart mất cache. Code phải **chịu được miss** (fallback DB) — cache là tối ưu, không phải nguồn sự thật.
- **Lẫn lộn `@Cacheable` với ZSet leaderboard.** `@Cacheable` là cache-aside cho 1 value; leaderboard cần **cấu trúc ZSet** thao tác bằng `RedisTemplate`, không phải annotation.

---

## 🚀 Liên hệ Spring Boot / Production

- **Theo dõi hit ratio.** Bật metric Micrometer cho cache (`cache.gets` tag `result=hit/miss`) — Day 43. Hit ratio thấp = cache vô dụng, cần xem lại key/TTL.
- **Chính sách eviction Redis:** đặt `maxmemory` + `maxmemory-policy allkeys-lru` để Redis tự dọn khi đầy RAM, tránh OOM.
- **Lettuce vs Jedis:** Spring Boot mặc định **Lettuce** (netty, non-blocking, thread-safe, hỗ trợ cluster tốt). Giữ mặc định trừ khi có lý do.
- **Redis cluster / sentinel** cho HA: cấu hình `spring.data.redis.cluster.nodes`. Cache không nên là single point of failure.
- **Chống stampede ở key nóng:** dùng `@Cacheable(sync = true)` (Spring chỉ cho **một** thread nạp lại, số còn lại chờ) — giải pháp đơn giản cho dog-pile trong một instance.
- **Cache versioning khi deploy:** đổi cấu trúc DTO → thêm version vào key/prefix để tránh đọc nhầm format cũ. Hoặc flush cache theo deploy.
- **Đừng cache thứ phải đúng tuyệt đối thời gian thực** (giá đang chốt giao dịch). Cache phục vụ đọc; ghi quan trọng vẫn qua DB + locking (Day 32).

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Đấu giá có những phiên **rất hot**: hàng nghìn người cùng xem một item, cùng đòi xem "ai đang dẫn đầu". Nếu mỗi lần xem đều `ORDER BY amount DESC` trên DB thì DB **gục**. Hôm nay ta: (1) cache thông tin phiên hot bằng `@Cacheable`, (2) dựng **leaderboard giá** bằng Redis ZSet.

**Nhiệm vụ Day 39:**

1. Thêm Annotation cho `getAuction(id)` với TTL 10s; viết test chứng minh lần gọi thứ 2 **không** chạm DB (chỉ 1 dòng "MISS" trong log).
2. Khi đặt giá thành công (nối tiếp `BidService` Day 32), gọi `BidLeaderboardService.recordBid(auctionId, bidder, amount)` để cập nhật ZSet.
3. Tạo endpoint `GET /auctions/{id}/leaderboard?top=10` trả top N người trả giá cao nhất (giá + bidder) lấy từ Redis, **không chạm DB**.
4. Khi `closeAuction`, dùng Annotation để xoá cache phiên và `clear()` luôn key leaderboard.
5. Hoàn thành **CHALLENGE** ở Bước 5: Bật `sync = true` để chống stampede ở phiên hot; kiểm chứng qua test đa luồng.

**Kết quả mong đợi:** Xem thông tin phiên và bảng xếp hạng người trả giá cao **không tạo thêm tải DB** ở các lần đọc lặp lại; leaderboard luôn phản ánh đúng top theo giá; đóng phiên thì cache được dọn sạch.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Cache-aside pattern là gì?**
> **Đáp:** Mẫu "đọc-bên-cạnh": đọc cache trước; hit thì trả luôn; miss thì đọc DB, ghi kết quả vào cache (kèm TTL) rồi trả. Khi ghi/sửa thì cập nhật (`@CachePut`) hoặc xoá (`@CacheEvict`) entry. Đây chính là thứ `@Cacheable` và `Cache::remember()` cài sẵn.

**Q2: Phân biệt `@Cacheable`, `@CachePut`, `@CacheEvict`?**
> **Đáp:** `@Cacheable`: hit thì **không gọi method**, miss thì gọi rồi lưu. `@CachePut`: **luôn** gọi method rồi ghi đè cache bằng kết quả (dùng cho update). `@CacheEvict`: xoá entry (hoặc cả cache với `allEntries=true`, dùng cho delete/invalidate).

**Q3: TTL để làm gì? Tại sao không cache vĩnh viễn?**
> **Đáp:** TTL giới hạn thời gian sống của entry, hết hạn Redis tự xoá. Không cache vĩnh viễn vì dữ liệu DB sẽ thay đổi → cache thành "thiu", trả dữ liệu sai. TTL là cách invalidation đơn giản nhất, đặt theo độ "đổi" của dữ liệu.

**Q4: Vì sao nên cache DTO thay vì entity JPA?**
> **Đáp:** Entity thường có quan hệ lazy (`@OneToMany`...) → serialize ngoài transaction gây `LazyInitializationException`, hoặc kéo cả cây quan hệ vào cache. Entity cũng gắn với persistence context. DTO phẳng, nhẹ, ổn định, serialize JSON sạch sẽ.

### Mức Senior

**Q5: Cache stampede (dog-pile) là gì và chữa thế nào?**
> **Đáp:** Khi một key **hot hết hạn đồng thời**, hàng loạt request cùng miss và cùng đập DB để nạp lại → DB quá tải đột ngột. Chữa: (1) lock nạp lại — chỉ một thread nạp, số còn lại chờ (`@Cacheable(sync=true)` trong một instance, hoặc distributed lock cho nhiều instance); (2) làm mới sớm trước khi hết hạn (probabilistic early expiration); (3) jitter TTL để các key không cùng hết hạn.

**Q6: Vì sao Redis Sorted Set hợp cho leaderboard hơn `ORDER BY` trên DB?**
> **Đáp:** ZSet giữ phần tử **luôn sắp xếp theo score** trong RAM. `ZADD` là O(log N), lấy top-N (`ZREVRANGE`) cũng O(log N + N). Không cần quét bảng + sort + index DB cho mỗi request đọc; không sinh tải nặng lên DB; cập nhật real-time. DB `ORDER BY ... LIMIT` mỗi lần đọc sẽ thành điểm nóng khi nhiều người xem cùng phiên.

**Q7: Cache penetration khác avalanche thế nào, và biện pháp?**
> **Đáp:** Penetration = liên tục query key **không tồn tại** → mọi lần miss đập DB (thường do tấn công). Chữa: cache giá trị "không tồn tại" (null sentinel TTL ngắn) hoặc Bloom filter chặn key chắc chắn không có. Avalanche = **nhiều** key hết hạn **cùng lúc** (TTL đồng đều) → sóng request đập DB. Chữa: jitter TTL, warm-up cache, hoặc tầng cache nhiều lớp (local + Redis).

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được vì sao cần cache và cache-aside pattern
- [ ] Dùng được `@EnableCaching`, `@Cacheable`/`@CachePut`/`@CacheEvict` với key/condition/unless
- [ ] Cấu hình `RedisCacheManager`: serializer JSON + TTL riêng từng cache
- [ ] Hiểu stampede/penetration/avalanche và cách chữa
- [ ] Dùng Redis ZSet làm leaderboard giá (ZADD/ZREVRANGE)
- [ ] Nhớ bẫy self-invocation và "cache DTO không cache entity"
- [ ] Hoàn thành Mini Project: cache phiên hot + leaderboard người trả giá cao
- [ ] Hoàn thành Challenge: Fix lỗi Stampede bằng tính năng Sync
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Framework Reference — "Cache Abstraction" (`@Cacheable`, key/condition/unless, `sync`)
- Spring Data Redis Reference — `RedisCacheManager`, `RedisTemplate`, `opsForZSet`
- Redis Docs — Data types: Strings, Hashes, Lists, Sets, **Sorted Sets**; lệnh `ZADD`/`ZREVRANGE`
- Baeldung — "A Guide to Redis with Spring Boot" và "Spring Boot Cache with Redis"
- Bài viết "Cache penetration / stampede / avalanche" (khái niệm phổ biến trong thiết kế hệ thống)
- Laravel Docs — "Cache" và "Redis" (đối chiếu `Cache::remember`, `Redis::zadd`)
