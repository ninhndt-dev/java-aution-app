# Day 40 - Kafka (Event Streaming)

> **Giai đoạn:** Infrastructure & Integration
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Kafka & event streaming trong Spring tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **kiến trúc event streaming** và Kafka khác **queue truyền thống** (Redis/SQS) thế nào.
- Nắm các khái niệm cốt lõi: **topic, partition, offset, broker**.
- Hiểu **producer** (key → partition, `acks`) và **consumer + consumer group** (rebalance, mỗi partition gắn 1 consumer trong group).
- Hiểu **thứ tự (ordering)** chỉ đảm bảo **trong một partition**.
- Nắm **delivery semantics**: at-least-once (mặc định) → **idempotent consumer**.
- Dùng được **Spring Kafka**: `KafkaTemplate` (producer) và `@KafkaListener` (consumer).
- Đối chiếu với queue + job của Laravel — và nhấn mạnh Kafka là **log bền, replay được**, khác queue.

---

## 🧠 Lý thuyết cốt lõi

### 1. Event streaming là gì? Kafka khác queue ra sao

Hãy phân biệt hai mô hình:

- **Queue truyền thống** (Laravel queue trên Redis/SQS, RabbitMQ): message vào hàng đợi, một worker **lấy ra rồi xoá**. Message **biến mất sau khi xử lý**. Không replay được. Tốt cho "giao việc nền" (gửi mail, resize ảnh).
- **Kafka (log bền)**: message được **ghi nối tiếp (append) vào một log trên đĩa** và **giữ lại** theo cấu hình retention (vd 7 ngày). Consumer chỉ **đọc** và **đánh dấu vị trí đã đọc tới (offset)** — **không xoá** message. Nhiều consumer độc lập đọc lại cùng dữ liệu; có thể **tua lại (replay)** từ đầu.

```
QUEUE (RabbitMQ/SQS):           KAFKA (log bền):
[m1][m2][m3]  ──► worker        log: [m0][m1][m2][m3][m4]...  (giữ lại)
   (lấy ra & XOÁ)                       ▲offset=2 (consumer A đọc tới đây)
   message biến mất                     ▲offset=4 (consumer B đọc tới đây)
                                  message KHÔNG mất -> replay được
```

> 💡 Đây là khác biệt **then chốt** phỏng vấn hay hỏi: Kafka **không phải** queue. Nó là **distributed commit log**. Một sự kiện "đã đặt giá" có thể được tiêu thụ bởi service-analytics, service-notification, service-fraud **độc lập**, mỗi cái giữ offset riêng — điều queue truyền thống làm rất khó.

### 2. Topic, partition, offset, broker

- **Topic**: một "kênh" sự kiện theo chủ đề (vd `bid-placed`). Producer ghi vào topic, consumer đọc từ topic.
- **Partition**: mỗi topic được chia thành nhiều **partition** — đây là **đơn vị song song và đơn vị thứ tự**. Mỗi partition là một log nối tiếp riêng.
- **Offset**: số thứ tự **tăng dần trong một partition**, đánh dấu vị trí từng message. Consumer lưu "đã đọc tới offset nào".
- **Broker**: một server Kafka. Một **cluster** gồm nhiều broker; các partition (và bản sao replica của chúng) được phân bố trên các broker để chịu tải & chịu lỗi.

```
Topic "bid-placed" (3 partition):
  Partition 0: [o0][o1][o2][o3] ...   ◄── log nối tiếp, offset tăng dần
  Partition 1: [o0][o1][o2] ...
  Partition 2: [o0][o1][o2][o3][o4] ...
  (mỗi partition nằm trên một broker, có replica để chịu lỗi)
```

> 💡 Số partition = trần độ song song của một consumer group. Muốn 10 consumer cùng group xử lý song song → topic cần ≥ 10 partition.

### 3. Producer: key → partition, acks

Khi producer gửi một record `(key, value)`:

- **Chọn partition**: nếu có **key**, Kafka băm key → luôn rơi vào **cùng một partition**. Cùng key ⇒ cùng partition ⇒ **giữ đúng thứ tự** cho key đó. Không key → phân bổ luân phiên (round-robin/sticky).
- **`acks` (độ bền ghi)**:
  - `acks=0`: gửi xong không chờ — nhanh nhất, dễ mất.
  - `acks=1`: chờ **leader** ghi xong — cân bằng.
  - `acks=all` (hay `-1`): chờ **mọi replica đồng bộ (ISR)** ghi xong — bền nhất, chậm hơn. Dùng cho sự kiện quan trọng.

> 💡 Với đấu giá: dùng **`auctionId` làm key** → mọi bid của cùng một phiên vào **cùng partition** → consumer xử lý **đúng thứ tự thời gian** cho phiên đó. Cực quan trọng để "giá cao nhất" tính đúng.

### 4. Consumer & consumer group

- Một **consumer group** là một nhóm consumer cùng `group.id` chia nhau đọc một topic.
- **Quy tắc vàng**: trong một group, **mỗi partition chỉ được gán cho ĐÚNG một consumer**. Nếu group có nhiều consumer hơn partition → có consumer **ngồi không**.
- **Rebalance**: khi consumer vào/ra group (scale up/down, crash), Kafka **phân bổ lại** partition cho các consumer còn lại.
- **Nhiều group độc lập**: mỗi group giữ offset riêng → cùng một topic phục vụ nhiều mục đích (analytics, notification...) mà không đụng nhau.

```
Topic 3 partition, group "analytics" có 3 consumer:
  P0 ──► Consumer-A
  P1 ──► Consumer-B      (mỗi partition đúng 1 consumer trong group)
  P2 ──► Consumer-C

Group "notification" (độc lập) cũng đọc cùng topic, offset riêng:
  P0,P1,P2 ──► Consumer-X (1 consumer ôm cả 3 partition)
```

### 5. Ordering: chỉ trong partition

Kafka **chỉ đảm bảo thứ tự trong một partition**, **không** đảm bảo thứ tự giữa các partition. Hệ quả thiết kế:

- Cần thứ tự cho một thực thể (vd 1 phiên đấu giá) → đẩy mọi sự kiện của thực thể đó vào **cùng partition** bằng cách dùng **cùng key**.
- Không cần thứ tự toàn cục → cứ để Kafka rải đều, tối đa song song.

### 6. Delivery semantics & idempotent consumer

Mặc định Spring Kafka cho **at-least-once** (ít nhất một lần): sau khi xử lý xong consumer mới commit offset. Nếu crash **sau khi xử lý nhưng trước khi commit offset** → message được **giao lại** → **xử lý 2 lần**.

| Semantics | Nghĩa | Đánh đổi |
|---|---|---|
| **At-most-once** | Tối đa 1 lần (commit offset trước khi xử lý) | Có thể **mất** message |
| **At-least-once** *(mặc định)* | Ít nhất 1 lần | Có thể **trùng** message |
| **Exactly-once** | Đúng 1 lần (transactional producer + read-process-write) | Phức tạp, chậm hơn |

> ⚠️ Vì at-least-once có thể trùng, **consumer phải idempotent**: xử lý cùng một sự kiện nhiều lần cho **cùng kết quả**. Cách thường dùng: mỗi sự kiện có **eventId** duy nhất; consumer lưu các eventId đã xử lý (DB/Redis), gặp lại thì bỏ qua.

### 7. Spring Kafka

- **`KafkaTemplate<K,V>`**: gửi message (producer). `kafkaTemplate.send("topic", key, value)`.
- **`@KafkaListener`**: đánh dấu method tiêu thụ message từ topic (consumer).
- Serialization: thường dùng **JSON** (`JsonSerializer`/`JsonDeserializer`) hoặc Avro + Schema Registry cho hệ lớn.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel queue | Kafka |
|---|---|---|
| Gửi việc/sự kiện | `Bus::dispatch(new Job(...))` / `event(...)` | `kafkaTemplate.send(topic, key, value)` |
| Nơi lưu | Redis/SQS/database queue | Distributed **commit log** trên đĩa |
| Sau khi xử lý | Job bị **xoá** khỏi queue | Message **giữ lại**, consumer chỉ dời offset |
| Replay | Không (job đã xoá) | **Có** — tua offset đọc lại |
| Nhiều consumer độc lập | Khó (mỗi job 1 worker lấy) | Dễ — mỗi **consumer group** offset riêng |
| Worker | `php artisan queue:work` | `@KafkaListener` trong consumer group |
| Retry/failed | `failed_jobs` table, `tries` | Retry topic / DLT + offset management |
| Thứ tự | Không đảm bảo (trừ cấu hình đặc biệt) | Đảm bảo **trong partition** (qua key) |

**Khác biệt tư duy quan trọng nhất:**

```php
// Laravel: queue = giao việc nền rồi quên. Job xử lý xong là biến mất.
ProcessBid::dispatch($bid)->onQueue('bids');
```
```java
// Kafka: phát một SỰ KIỆN vào log bền. Nhiều service đọc độc lập, replay được.
kafkaTemplate.send("bid-placed", auctionId.toString(), event);
```

> 💡 Đừng nghĩ Kafka = "Laravel queue nhưng to hơn". Queue là **giao việc** (work queue, xử lý xong xoá). Kafka là **dòng sự kiện bền** (event log) — nó hợp với event-driven architecture, audit, analytics, CDC, replay. Một số việc Laravel dùng queue (gửi mail) thì Kafka cũng làm được, nhưng triết lý khác hẳn: **producer không biết và không quan tâm ai tiêu thụ**.

---

## 💻 Thực hành code

## 💻 Thực hành code

### Bước 1 — Phụ thuộc & cấu hình

```xml
<!-- pom.xml -->
<dependency>
  <groupId>org.springframework.kafka</groupId>
  <artifactId>___</artifactId> <!-- Điền dependency để tích hợp Kafka với Spring Boot -->
</dependency>
```

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: ___                       # Điền cấu hình để chờ mọi replica ghi xong (bền nhất)
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        enable.idempotence: true      # producer idempotent: tránh ghi trùng khi retry
    consumer:
      group-id: auction-analytics     # consumer group
      auto-offset-reset: earliest     # group mới đọc từ đầu log
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.example.auction.event"
    listener:
      ack-mode: record                # commit offset sau khi xử lý XONG mỗi record (at-least-once)
```

### Bước 2 — Khai báo Sự kiện (Event)

```java
// File: BidPlacedEvent.java
package com.example.auction.event;

import java.time.Instant;

// Dùng từ khóa của Java 14+ để tạo class bất biến, gọn nhẹ, thích hợp làm Event.
public ___ BidPlacedEvent(
        String eventId,      // UUID -> chống xử lý trùng (at-least-once)
        Long auctionId,
        String bidder,
        long amount,
        Instant placedAt
) {}
```

### Bước 3 — Producer: Phát `BidPlacedEvent`

```java
// File: BidEventPublisher.java
package com.example.auction.messaging;

import com.example.auction.event.BidPlacedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.UUID;

@Component
public class BidEventPublisher {

    public static final String TOPIC = "bid-placed";
    private final KafkaTemplate<String, BidPlacedEvent> kafkaTemplate;

    public BidEventPublisher(KafkaTemplate<String, BidPlacedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Long auctionId, String bidder, long amount) {
        var event = new BidPlacedEvent(
                UUID.randomUUID().toString(), auctionId, bidder, amount, Instant.now());

        // ⭐ KEY = auctionId.toString() -> mọi bid của cùng phiên vào CÙNG partition
        //    -> consumer xử lý ĐÚNG THỨ TỰ thời gian cho phiên đó.
        kafkaTemplate.___(TOPIC, auctionId.toString(), event) // Điền method để ĐẨY event lên Kafka
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    System.err.println("Gửi event thất bại: " + ex.getMessage());
                }
            });
    }
}
```

### Bước 4 — Consumer: Cập nhật analytics/thông báo

```java
// File: BidAnalyticsConsumer.java
package com.example.auction.messaging;

import com.example.auction.event.BidPlacedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BidAnalyticsConsumer {

    // Idempotency đơn giản (demo): nhớ eventId đã xử lý.
    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    @___(topics = BidEventPublisher.TOPIC, groupId = "auction-analytics") // Điền annotation để lắng nghe sự kiện
    public void onBidPlaced(BidPlacedEvent event) {
        // at-least-once -> có thể nhận trùng -> CHECK idempotent
        if (!processed.add(event.eventId())) {
            return;   // đã xử lý rồi -> bỏ qua
        }
        System.out.printf("📊 [analytics] phiên %d: %s đặt %d (offset xử lý xong)%n",
                event.auctionId(), event.bidder(), event.amount());
    }
}

// File: BidNotificationConsumer.java — GROUP KHÁC, đọc CÙNG topic độc lập
@Component
class BidNotificationConsumer {

    @___(topics = BidEventPublisher.TOPIC, groupId = "___") // Tạo groupId KHÁC để 2 consumer group KHÔNG giành giật dữ liệu
    public void notifyOutbid(BidPlacedEvent event) {
        System.out.printf("🔔 [notify] phiên %d có giá mới %d từ %s%n",
                event.auctionId(), event.amount(), event.bidder());
    }
}
```

### Bước 5 — Nối vào nghiệp vụ đặt giá (Cẩn thận Dual-Write)

```java
// Trong BidService.placeBid (Day 36), SAU khi commit transaction DB thành công:
@Transactional
public void placeBid(Long auctionId, String bidder, long amount) {
    // ... cập nhật DB ...
    
    // Lưu ý: publish nên xảy ra SAU commit để không phát event cho transaction đã rollback.
    bidEventPublisher.publish(auctionId, bidder, amount);
}
```

### Bước 6 — CHALLENGE: Lỗi "Gửi Event Nhầm Lúc" (Dual Write)

> 🏆 Yêu cầu:
> 1. Viết 1 test gọi method `placeBid` và cuối method bị lỗi (throw exception).
> 2. Quan sát log: DB đã Rollback nhưng Event đã BAY LÊN KAFKA và hệ thống Notify ĐÃ BÁO CÁO! (Một sự dối trá do ghi lỗi).
> 3. Khắc phục: Xóa `bidEventPublisher.publish` ở trong logic chính, sử dụng annotation `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` để tách việc gửi event an toàn.
> 4. (Tùy chọn khó): Triển khai Outbox Pattern thay thế `@TransactionalEventListener`.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng Kafka đảm bảo thứ tự toàn cục.** Chỉ trong **một partition**. Cần thứ tự cho một thực thể → dùng cùng **key** để ép vào cùng partition.
- **Consumer không idempotent.** At-least-once → trùng message là chuyện thường (rebalance, crash trước commit offset). Phải dùng `eventId` + lưu vết đã xử lý.
- **Nhiều consumer hơn partition trong một group.** Số consumer hữu ích bị trần bởi số partition; phần dư **ngồi không**. Tăng partition trước khi scale consumer.
- **Publish event TRONG transaction rồi DB rollback.** Event đã bay đi nhưng dữ liệu DB không có → bất nhất. Dùng `@TransactionalEventListener(phase = AFTER_COMMIT)` hoặc **outbox pattern**.
- **Xử lý chậm trong listener → rebalance liên tục.** Quá `max.poll.interval.ms` mà chưa poll tiếp → Kafka tưởng consumer chết → rebalance. Việc nặng nên đẩy ra thread/queue khác.
- **`acks=0/1` cho sự kiện quan trọng.** Có thể mất khi broker leader chết. Dùng `acks=all` + `enable.idempotence=true`.
- **Topic ít partition rồi muốn scale.** Tăng partition được nhưng **phá vỡ thứ tự theo key cũ** (key map sang partition khác). Thiết kế số partition đủ rộng từ đầu.
- **Quên cấu hình `trusted.packages` cho JsonDeserializer.** Sẽ ném `IllegalArgumentException` không deserialize được.

---

## 🚀 Liên hệ Spring Boot / Production

- **Dead Letter Topic (DLT) & retry:** dùng `@RetryableTopic` của Spring Kafka để tự retry rồi đẩy message lỗi sang DLT thay vì kẹt cả partition (giống `failed_jobs` của Laravel nhưng mạnh hơn).
- **Outbox pattern:** ghi event vào bảng `outbox` **trong cùng transaction DB** với nghiệp vụ, rồi một tiến trình riêng đọc bảng đó publish lên Kafka — đảm bảo "ghi DB và phát event là nguyên tử". Đây là cách chuẩn xử lý dual-write (liên hệ Day 38 transaction).
- **Exactly-once (EOS):** cần read-process-write transactional + `isolation.level=read_committed`. Đắt, chỉ dùng khi thực sự cần (tài chính).
- **Schema Registry (Avro/Protobuf):** cho hệ lớn nhiều team — quản lý tiến hoá schema để producer/consumer không vỡ khi đổi cấu trúc event.
- **Monitoring:** theo dõi **consumer lag** (offset cuối − offset đã đọc). Lag tăng = consumer xử lý không kịp. Bật metric (Day 43).
- **Quan sát rebalance:** rebalance thường xuyên là dấu hiệu xấu (xử lý chậm, session timeout sai). Tinh chỉnh `max.poll.records`, `max.poll.interval.ms`.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Khi một bid được đặt (Day 38 lo phần ghi DB an toàn), nhiều hệ con khác cần biết: **analytics** (số bid/phút, top item), **notification** (báo người bị vượt giá), **fraud** (phát hiện bot). Thay vì `BidService` gọi trực tiếp từng cái (coupling chặt), ta **phát một sự kiện** lên Kafka; các consumer độc lập tự xử lý.

**Nhiệm vụ Day 40:**

1. Tạo topic `bid-placed`. Định nghĩa Event `BidPlacedEvent` (có `eventId`, `auctionId`, `bidder`, `amount`, `placedAt`) sử dụng Record.
2. Trong `BidService.placeBid`, **sau khi commit**, publish sự kiện với **key = auctionId** (đảm bảo thứ tự theo phiên).
3. Viết `BidAnalyticsConsumer` (group `auction-analytics`): lắng nghe kafka, đếm số bid mỗi phiên, **idempotent theo `eventId`**.
4. Viết `BidNotificationConsumer` với **groupId ĐỘC LẬP**: lắng nghe cùng topic, in thông báo "có giá mới". Chứng minh cả hai group đều nhận đủ sự kiện.
5. Hoàn thành **CHALLENGE** ở Bước 6: Khắc phục lỗi Dual-Write bằng `@TransactionalEventListener`.

**Kết quả mong đợi:** Đặt giá xong, hệ thống phát một sự kiện; analytics và notification xử lý **độc lập** cùng sự kiện đó; bid của cùng một phiên được xử lý **đúng thứ tự** (cùng partition); gửi lại trùng không làm sai số liệu (idempotent), event không gửi đi khi DB lỗi.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Topic, partition, offset là gì?**
> **Đáp:** Topic là kênh sự kiện theo chủ đề. Partition là phần chia của topic — mỗi partition là một log nối tiếp, là **đơn vị song song và đơn vị thứ tự**. Offset là số thứ tự tăng dần trong một partition, đánh dấu vị trí từng message; consumer lưu "đã đọc tới offset nào".

**Q2: Kafka khác queue truyền thống (RabbitMQ/SQS/Laravel queue) thế nào?**
> **Đáp:** Queue: message bị **lấy ra và xoá** sau khi xử lý, không replay. Kafka: là **log bền** — message được giữ lại theo retention, consumer chỉ dời offset chứ không xoá; nhiều consumer group đọc độc lập, có thể **tua lại (replay)**. Kafka hợp event-driven/streaming/audit; queue hợp giao việc nền.

**Q3: Consumer group hoạt động ra sao?**
> **Đáp:** Các consumer cùng `group.id` chia nhau đọc topic. Trong một group, **mỗi partition chỉ gán cho đúng một consumer** (nên consumer dư sẽ ngồi không). Nhiều group khác nhau đọc cùng topic độc lập, mỗi group giữ offset riêng. Khi consumer vào/ra, Kafka **rebalance** phân bổ lại partition.

**Q4: `acks` của producer nghĩa là gì?**
> **Đáp:** Mức xác nhận ghi: `acks=0` không chờ (nhanh, dễ mất); `acks=1` chờ leader ghi (cân bằng); `acks=all` chờ mọi replica đồng bộ (ISR) ghi xong (bền nhất, chậm hơn). Sự kiện quan trọng dùng `acks=all` + `enable.idempotence=true`.

### Mức Senior

**Q5: Làm sao đảm bảo thứ tự xử lý cho các sự kiện của cùng một phiên đấu giá?**
> **Đáp:** Kafka chỉ đảm bảo thứ tự **trong một partition**. Dùng **`auctionId` làm key** → Kafka băm key đưa mọi sự kiện của cùng phiên vào **cùng partition** → consumer xử lý đúng thứ tự thời gian cho phiên đó. Đánh đổi: các phiên khác nhau rải đều nhiều partition để vẫn song song.

**Q6: Delivery semantics mặc định là gì và vì sao consumer phải idempotent?**
> **Đáp:** Mặc định **at-least-once**: offset chỉ commit sau khi xử lý xong; nếu crash giữa "xử lý xong" và "commit offset" thì message được giao lại → **xử lý trùng**. Vì vậy consumer phải idempotent: gắn `eventId` duy nhất cho mỗi sự kiện, lưu vết đã xử lý (DB/Redis), gặp lại thì bỏ qua, để xử lý nhiều lần vẫn cho cùng kết quả.

**Q7: Vì sao không nên publish Kafka event ngay trong transaction DB, và giải pháp?**
> **Đáp:** Vì "ghi DB" và "gửi Kafka" là hai hệ thống khác nhau (dual-write). Nếu publish trong transaction rồi DB rollback → event đã bay đi nhưng dữ liệu không tồn tại → bất nhất. Giải pháp: (1) `@TransactionalEventListener(phase = AFTER_COMMIT)` — chỉ publish sau khi commit; hoặc (2) **outbox pattern** — ghi event vào bảng outbox trong cùng transaction, một tiến trình riêng đọc và publish, đảm bảo nguyên tử và không mất event.

---

## ✅ Checklist hoàn thành

- [ ] Phân biệt được Kafka (log bền, replay) với queue truyền thống
- [ ] Giải thích topic/partition/offset/broker và consumer group
- [ ] Hiểu key → partition và ordering chỉ trong partition
- [ ] Hiểu `acks` và delivery semantics (at-least-once → idempotent consumer)
- [ ] Dùng được `KafkaTemplate` (producer) và `@KafkaListener` (consumer)
- [ ] Biết outbox pattern / `@TransactionalEventListener` chống dual-write
- [ ] Hoàn thành Mini Project: phát BidPlacedEvent, 2 consumer group độc lập
- [ ] Hoàn thành Challenge: Khắc phục Dual-Write
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Apache Kafka Documentation — "Introduction", "Design", "Consumer Groups", "Delivery Semantics"
- Spring for Apache Kafka Reference — `KafkaTemplate`, `@KafkaListener`, `@RetryableTopic`, error handling
- Confluent Developer — "Kafka vs Message Queue", "Idempotent Producer & Consumer"
- Baeldung — "Intro to Apache Kafka with Spring" và "Spring Kafka Dead Letter Topic"
- Microservices.io — "Transactional Outbox" pattern (đối chiếu dual-write với Day 38)
- Laravel Docs — "Queues" (đối chiếu queue/job với event log của Kafka)
