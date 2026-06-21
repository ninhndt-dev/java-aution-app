# Day 10 - LinkedList Internals

> **Giai đoạn:** Collections & Generics
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu cấu trúc dữ liệu trong Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **cấu trúc bên trong** của `LinkedList`: là một **doubly-linked list** (danh sách liên kết đôi), không phải mảng.
- Nắm vững **độ phức tạp**: vì sao thêm/xóa ở đầu–cuối là `O(1)` nhưng `get(index)` lại là `O(n)`.
- Biết `LinkedList` cài đặt cả `List`, `Deque`, `Queue` → dùng được như **stack / queue / deque**.
- So sánh **ArrayList vs LinkedList** một cách thực dụng — và hiểu vì sao **thực tế ArrayList thường thắng** dù lý thuyết tưởng ngược lại.
- Biết khi nào nên dùng **`ArrayDeque`** thay cho `LinkedList`, và đối chiếu với `SplDoublyLinkedList`/`SplQueue`/`SplStack` của PHP.
- Áp dụng vào Mini Project: dựng **hàng đợi xử lý bid** FIFO cho Auction API.

---

## 🧠 Lý thuyết cốt lõi

### 1. `LinkedList` không phải mảng — nó là danh sách liên kết đôi

Khác hẳn `ArrayList` (lưu dữ liệu trên một mảng liền kề trong bộ nhớ), `LinkedList` lưu mỗi phần tử trong một **Node** riêng nằm rải rác trên Heap. Mỗi Node có **3 trường**:

```java
// Lớp Node nội bộ (private static) bên trong java.util.LinkedList
private static class Node<E> {
    E item;        // dữ liệu thật sự
    Node<E> next;  // con trỏ tới node kế tiếp
    Node<E> prev;  // con trỏ tới node phía trước
}
```

Bản thân `LinkedList` chỉ giữ **hai con trỏ**: `first` (node đầu) và `last` (node cuối), cùng một biến `size`.

```
  first                                              last
    │                                                  │
    ▼                                                  ▼
 ┌──────┐      ┌──────┐      ┌──────┐      ┌──────┐
 │ prev=null   │ prev─┼─────►│ prev─┼─────►│ prev─┼──┐
 │ item=A │    │ item=B │    │ item=C │    │ item=D │ │
 │ next─┼─────►│ next─┼─────►│ next─┼─────►│ next=null
 └──────┘ ◄────┼─────┘ ◄────┼─────┘ ◄────┼─────┘
   (A)         │  (B)        │  (C)        │  (D)
```

Mỗi node "biết" hàng xóm trái–phải của mình. Muốn đi từ A tới D phải **lần theo con trỏ `next` từng bước** — không có cách nhảy thẳng tới phần tử thứ `i` như mảng.

> 💡 "Doubly" (đôi) nghĩa là có cả `next` lẫn `prev`, nên duyệt được **cả hai chiều**. Đây là điều khiến `LinkedList` cài đặt được `Deque` (hàng đợi hai đầu).

### 2. Vì sao add/remove ở ĐẦU và CUỐI là `O(1)`

Vì `LinkedList` luôn giữ sẵn con trỏ `first` và `last`, thao tác ở hai đầu chỉ là **chỉnh vài con trỏ**, không phải dịch chuyển phần tử nào.

Ví dụ `addFirst(X)` — thêm node mới vào đầu:

```
Trước:        first ─► [A] ⇄ [B] ⇄ [C]

addFirst(X):  tạo node X, X.next = A, A.prev = X, first = X

Sau:   first ─► [X] ⇄ [A] ⇄ [B] ⇄ [C]
```

Chỉ 3–4 phép gán, **không phụ thuộc size** → `O(1)`. Tương tự cho:

| Phương thức | Ý nghĩa | Độ phức tạp |
|---|---|---|
| `addFirst(e)` / `addLast(e)` | thêm vào đầu / cuối | `O(1)` |
| `removeFirst()` / `removeLast()` | xóa ở đầu / cuối | `O(1)` |
| `getFirst()` / `getLast()` | lấy phần tử đầu / cuối | `O(1)` |

> 💡 So với `ArrayList`: thêm vào **đầu** mảng là `O(n)` vì phải dịch toàn bộ phần tử sang phải một ô. Đây là điểm `LinkedList` thắng về lý thuyết.

### 3. Vì sao `get(index)` lại là `O(n)` — không có random access

Mảng cho phép truy cập tức thì: `array[i]` chỉ là phép tính địa chỉ `base + i * size`. `LinkedList` **không có** điều đó. Muốn lấy phần tử thứ `i`, JVM phải **duyệt từng node** cho tới khi đếm đủ.

JDK có tối ưu nhỏ: nếu `index` nằm ở **nửa đầu** thì duyệt xuôi từ `first`, nếu ở **nửa sau** thì duyệt ngược từ `last` — nhưng vẫn là `O(n)`:

```java
// Trích ý tưởng từ LinkedList.node(int index)
Node<E> node(int index) {
    if (index < (size >> 1)) {          // index < size/2 → duyệt xuôi
        Node<E> x = first;
        for (int i = 0; i < index; i++) x = x.next;
        return x;
    } else {                            // ngược lại → duyệt ngược từ last
        Node<E> x = last;
        for (int i = size - 1; i > index; i--) x = x.prev;
        return x;
    }
}
```

```
get(2) trên list 4 phần tử:
 first ─► [A] ─► [B] ─► [C] ─► [D]
          i=0    i=1    i=2 ✓
          (phải nhảy qua A, B rồi mới tới C)
```

> ⚠️ `set(index, e)` cũng `O(n)` vì nó phải gọi `node(index)` trước. Và **vòng lặp `for (int i=0; i<list.size(); i++) list.get(i)` trên `LinkedList` là `O(n²)`** — một cái bẫy hiệu năng kinh điển. Luôn dùng `Iterator`/for-each khi duyệt `LinkedList`.

Bảng độ phức tạp tổng hợp:

| Thao tác | LinkedList | ArrayList |
|---|---|---|
| `get(i)` / `set(i)` | `O(n)` | `O(1)` |
| `add(e)` (cuối) | `O(1)` | `O(1)` phân bổ đều (amortized) |
| `addFirst(e)` / thêm đầu | `O(1)` | `O(n)` |
| `add(i, e)` (chèn giữa) | `O(n)` tìm + `O(1)` nối | `O(n)` dịch mảng |
| `remove(i)` (giữa) | `O(n)` tìm + `O(1)` gỡ | `O(n)` dịch mảng |
| duyệt tuần tự (iterator) | `O(n)` | `O(n)` |

### 4. `LinkedList` là `List` + `Deque` + `Queue` → một lớp "ba trong một"

Khai báo thật của nó:

```java
public class LinkedList<E>
        extends AbstractSequentialList<E>
        implements List<E>, Deque<E>, Cloneable, Serializable
```

Vì `Deque extends Queue`, `LinkedList` xài được trọn bộ API hàng đợi / ngăn xếp / hàng đợi hai đầu:

| Vai trò | Phương thức | Hành vi |
|---|---|---|
| **Queue (FIFO)** | `offer(e)` → thêm cuối · `poll()` → lấy & xóa đầu · `peek()` → xem đầu | Vào trước ra trước |
| **Stack (LIFO)** | `push(e)` → thêm đầu · `pop()` → lấy & xóa đầu · `peek()` | Vào sau ra trước |
| **Deque** | `offerFirst/offerLast`, `pollFirst/pollLast`, `peekFirst/peekLast` | Thao tác cả hai đầu |

> 💡 Cặp `offer/poll/peek` **không ném exception** khi rỗng (trả `null`/`false`), còn cặp `add/remove/element` thì **ném** `NoSuchElementException`. Trong production thường dùng `offer/poll/peek` để tránh exception điều khiển luồng.

### 5. ArrayList vs LinkedList — và cú twist về "cache locality"

Lý thuyết bảng trên khiến nhiều người nghĩ "thao tác đầu/giữa nhiều thì chọn `LinkedList`". **Thực tế thường ngược lại.** Hai lý do:

**(a) Overhead bộ nhớ mỗi node.** Một phần tử trong `ArrayList` chỉ tốn 1 ô tham chiếu (4–8 byte). Một node `LinkedList` là **cả một object** gồm: object header (~12–16 byte) + 3 tham chiếu (`item`, `next`, `prev`) + padding → **mỗi phần tử tốn gấp 3–6 lần** bộ nhớ.

```
ArrayList:   [ref][ref][ref][ref]      ← liền kề, gọn
LinkedList:  Node{hdr,item,next,prev}  Node{...}  Node{...}
             nằm rải rác khắp Heap, mỗi cái là một object riêng
```

**(b) Cache locality.** CPU đọc bộ nhớ theo từng "cache line" (~64 byte). Mảng của `ArrayList` nằm liền kề → đọc 1 phần tử là kéo luôn vài phần tử kế bên vào cache → duyệt cực nhanh. Node của `LinkedList` nằm rải rác → mỗi lần nhảy `next` dễ **cache miss**, CPU phải chờ RAM. Pointer-chasing này khiến `LinkedList` chậm trong thực tế.

> 💡 **Lời khuyên thực dụng:** mặc định dùng `ArrayList`. Ngay cả nhiều kịch bản mà lý thuyết tưởng `LinkedList` nhanh hơn (chèn/xóa nhiều) thì `ArrayList` vẫn thắng nhờ cache locality và việc dịch mảng được tối ưu bằng `System.arraycopy` ở mức rất thấp.

### 6. Khi nào THỰC SỰ cần `LinkedList`? (Hiếm)

Câu trả lời thẳng: **rất hiếm.** Lý do duy nhất "đúng sách" là khi bạn liên tục thêm/xóa ở đầu danh sách **và** đã giữ sẵn vị trí qua `ListIterator` (chèn/xóa `O(1)` không cần duyệt lại).

Nhưng nếu mục đích là **queue/deque/stack** thì **`ArrayDeque` gần như luôn tốt hơn** `LinkedList`:

| Tiêu chí | `ArrayDeque` | `LinkedList` (dùng như Deque) |
|---|---|---|
| Cấu trúc | mảng vòng (circular array) | doubly-linked list |
| Overhead/phần tử | gần như 0 (chỉ 1 ô mảng) | cả một Node + 3 tham chiếu |
| Cache locality | tốt (liền kề) | kém (rải rác) |
| Cho phép `null`? | **Không** | Có |
| Thực tế | nhanh hơn | chậm hơn |

> 💡 **Quy tắc nằm lòng:** cần `List` → `ArrayList`. Cần `Queue`/`Stack`/`Deque` → `ArrayDeque`. `LinkedList` gần như chỉ còn giá trị học thuật để hiểu danh sách liên kết.

---

## 🔁 Đối chiếu với Laravel/PHP

PHP cũng có sẵn các cấu trúc liên kết đôi trong thư viện chuẩn SPL (Standard PHP Library):

| Java | PHP / SPL | Ghi chú |
|---|---|---|
| `LinkedList` | `SplDoublyLinkedList` | Cùng là danh sách liên kết đôi, push/pop/shift/unshift |
| `Queue` (FIFO) | `SplQueue extends SplDoublyLinkedList` | `enqueue()` ↔ `offer()`, `dequeue()` ↔ `poll()` |
| `Deque` dùng như Stack | `SplStack extends SplDoublyLinkedList` | `push()`/`pop()` LIFO |
| `ArrayList` | `array` của PHP (ordered map) | Trong PHP `array` đa năng làm cả list lẫn map |
| `ArrayDeque` | *không có tương đương trực tiếp* | PHP thường dùng thẳng `SplQueue`/`SplStack` |

```php
// PHP: hàng đợi FIFO bằng SplQueue
$queue = new SplQueue();
$queue->enqueue("bid-1");   // thêm cuối
$queue->enqueue("bid-2");
$first = $queue->dequeue(); // lấy ra "bid-1" (FIFO)
```

**Khác biệt tư duy quan trọng nhất:**

- Trong PHP, `array` là "con dao đa năng" làm mọi việc (list, map, stack, queue) và bạn **hiếm khi phải nghĩ về cấu trúc dữ liệu bên dưới** — engine lo hết. SPL chỉ dùng khi cần ngữ nghĩa rõ ràng.
- Trong Java, **bạn phải chủ động chọn** đúng cấu trúc (`ArrayList` vs `LinkedList` vs `ArrayDeque`) vì mỗi lựa chọn ảnh hưởng trực tiếp tới hiệu năng và bộ nhớ của một tiến trình **chạy lâu dài**. Đây là kỹ năng cốt lõi mà thế giới PHP "stateless mỗi request" ít rèn cho bạn.

> 🧩 Hệ quả: ở Java, chọn sai cấu trúc dữ liệu trong một service xử lý hàng triệu phần tử có thể khiến GC quá tải hoặc latency tăng vọt — điều bạn ít gặp ở Laravel vì mỗi request sống ngắn rồi giải phóng.

---

## 💻 Thực hành code

### Bước 1 — `LinkedList` đóng vai Queue và Stack

```java
import java.util.LinkedList;
import java.util.Queue;
import java.util.Deque;

public class LinkedListDemo {
    public static void main(String[] args) {
        // ---- Dùng như QUEUE (FIFO: vào trước ra trước) ----
        ___<String> queue = new ___<>(); // Điền interface và class cho hàng đợi
        queue.offer("A");   // thêm vào cuối
        queue.offer("B");
        queue.offer("C");
        System.out.println("Queue peek (xem đầu): " + queue.peek()); // A
        System.out.println("Queue poll (lấy đầu): " + queue.poll()); // A — ra trước
        System.out.println("Queue poll: " + queue.poll());           // B
        System.out.println("Còn lại: " + queue);                     // [C]

        // ---- Dùng như STACK (LIFO: vào sau ra trước) ----
        ___<String> stack = new ___<>(); // Điền interface và class cho ngăn xếp
        stack.push("X");   // push = thêm vào ĐẦU
        stack.push("Y");
        stack.push("Z");
        System.out.println("Stack peek: " + stack.peek()); // Z
        System.out.println("Stack pop:  " + stack.pop());  // Z — ra trước
        System.out.println("Stack pop:  " + stack.pop());  // Y
        System.out.println("Còn lại: " + stack);           // [X]
    }
}
```

> 💡 Lưu ý kiểu khai báo: dùng interface `Queue`/`Deque` ở vế trái, `LinkedList` ở vế phải. Đây là nguyên tắc "program to interface" — sau này muốn đổi sang `ArrayDeque` chỉ sửa **một dòng**.

### Bước 2 — Đo `get(index)` chậm thế nào trên `LinkedList` lớn

```java
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

public class RandomAccessBenchmark {
    public static void main(String[] args) {
        int n = 100_000;

        ___<Integer> arrayList = new ___<>(); // Điền interface và class cho mảng động
        ___<Integer> linkedList = new ___<>(); // Điền interface và class cho danh sách liên kết
        for (int i = 0; i < n; i++) {
            arrayList.add(i);
            linkedList.add(i);
        }

        // Truy cập ngẫu nhiên 10.000 lần bằng get(index)
        int accesses = 10_000;
        long seed = 42;

        long t1 = System.nanoTime();
        readByIndex(arrayList, accesses, seed, n);
        long arrayTime = System.nanoTime() - t1;

        long t2 = System.nanoTime();
        readByIndex(linkedList, accesses, seed, n);
        long linkedTime = System.nanoTime() - t2;

        System.out.printf("ArrayList  get(index): %,d ns%n", arrayTime);
        System.out.printf("LinkedList get(index): %,d ns%n", linkedTime);
        System.out.printf("LinkedList chậm hơn ~%.0f lần%n",
                (double) linkedTime / arrayTime);
    }

    // Đọc theo index ngẫu nhiên; trả về tổng để JIT không loại bỏ vòng lặp
    private static long readByIndex(List<Integer> list, int times, long seed, int n) {
        Random rnd = new Random(seed);
        long sum = 0;
        for (int i = 0; i < times; i++) {
            sum += list.get(rnd.nextInt(n)); // ArrayList O(1), LinkedList O(n)
        }
        return sum;
    }
}
```

Kết quả tiêu biểu: `ArrayList` xong trong vài trăm nghìn ns, còn `LinkedList` chậm **hàng trăm tới hàng nghìn lần** vì mỗi `get` phải duyệt trung bình `n/4` node.

> ⚠️ Đây là benchmark "bỏ túi" để **cảm nhận** sự khác biệt, không phải benchmark chuẩn. Đo hiệu năng Java nghiêm túc phải dùng **JMH** vì JIT warm-up (đã học ở Day 01) làm sai lệch phép đo thô bằng `System.nanoTime`.

### Bước 3 — Cùng bài toán, đổi sang `ArrayDeque` (chỉ sửa 1 dòng)

```java
import java.util.ArrayDeque;
import java.util.Queue;

public class ArrayDequeQueueDemo {
    public static void main(String[] args) {
        // Chỉ đổi LinkedList -> ArrayDeque, phần còn lại y nguyên
        ___<String> queue = new ___<>(); // Điền interface và class cho hàng đợi mảng vòng
        queue.offer("bid-1");
        queue.offer("bid-2");
        queue.offer("bid-3");

        // Xử lý lần lượt theo FIFO
        String item;
        while ((item = queue.poll()) != null) {
            System.out.println("Đang xử lý: " + item);
        }
        // ArrayDeque ngắn gọn, không Node overhead, cache locality tốt hơn LinkedList.
        // ⚠️ Nhớ: ArrayDeque KHÔNG cho phép null -> queue.offer(null) sẽ ném NPE.
    }
}
```

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Duyệt `LinkedList` bằng `for (i=0; i<size; i++) get(i)`.** Đây là `O(n²)` vì mỗi `get(i)` duyệt lại từ đầu. Luôn dùng for-each / `Iterator`.
- **Chọn `LinkedList` vì "chèn/xóa nhanh hơn".** Trong tuyệt đại đa số trường hợp `ArrayList` vẫn nhanh hơn nhờ cache locality. Đừng tối ưu theo cảm tính.
- **Dùng `LinkedList` làm queue thay vì `ArrayDeque`.** `ArrayDeque` ít overhead và nhanh hơn. JDK khuyến nghị `ArrayDeque` cho stack/queue.
- **Quên `ArrayDeque` không nhận `null`.** Khác `LinkedList` (cho phép `null`). Nếu logic có thể có `null`, dùng sentinel hoặc `Optional`, đừng nhét `null` vào `ArrayDeque`.
- **Nhầm `peek`/`poll` (trả `null`) với `element`/`remove` (ném exception) khi rỗng.** Chọn sai cặp sẽ làm code hoặc nuốt lỗi âm thầm, hoặc crash bất ngờ.
- **Dùng lớp cũ `java.util.Stack` làm ngăn xếp.** `Stack` kế thừa `Vector`, đồng bộ hóa (synchronized) chậm và thiết kế lỗi thời. Java khuyến nghị dùng `Deque`/`ArrayDeque`.

---

## 🚀 Liên hệ Spring Boot / Production

- **Hàng đợi trong bộ nhớ (in-memory queue).** Một số tác vụ nhẹ (gom log, đệm sự kiện, buffer) dùng `Queue`/`Deque` ngay trong tiến trình. Khi cần thread-safe, ta dùng **`ConcurrentLinkedQueue`** hoặc **`ArrayBlockingQueue`/`LinkedBlockingQueue`** (sẽ học ở phần concurrency) — chứ không phải `LinkedList` trần.
- **Đừng nhầm với message queue thật.** Hàng đợi `Deque`/`ArrayDeque` chỉ sống trong RAM của một instance; mất khi app restart. Production xử lý bid quy mô lớn dùng **Kafka / RabbitMQ / Redis** để bền vững và phân tán. `ArrayDeque` chỉ hợp cho buffer cục bộ, ngắn hạn.
- **Chọn cấu trúc = chọn hiệu năng & chi phí GC.** Service Java chạy lâu dài, mỗi node `LinkedList` thừa là một object cho GC dọn. Với khối lượng lớn, `ArrayList`/`ArrayDeque` giảm áp lực GC rõ rệt.
- **`@RestController` trả `List`.** Khi controller trả về danh sách, Jackson chỉ cần một `List` để serialize JSON — `ArrayList` là mặc định hợp lý nhất.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta thêm **hàng đợi xử lý bid** cho Auction API: mỗi lượt trả giá (bid) được đẩy vào queue rồi xử lý tuần tự theo **FIFO** (ai đặt trước xử lý trước).

**Nhiệm vụ Day 10:**

0. Điền các chỗ trống `___` trong code thực hành ở trên.
1. Tạo `record Bid(String bidder, long amount, long timestamp)` để mô tả một lượt trả giá.
2. Tạo lớp `BidQueueProcessor` dùng `Deque<Bid>` (khởi tạo bằng **`ArrayDeque`**) để:
   - `submit(Bid bid)` → `offer` bid vào cuối hàng đợi.
   - `processAll()` → `poll` từng bid theo FIFO, in ra "Xử lý bid của <bidder> giá <amount>".
3. Trong comment, **giải thích vì sao chọn `ArrayDeque` thay vì `LinkedList`** (ít overhead, cache locality tốt, không cần lưu `null`).

```java
import java.util.ArrayDeque;
import java.util.Deque;

// 1. record mô tả một lượt trả giá
public record Bid(String bidder, long amount, long timestamp) {}

// 2. Bộ xử lý hàng đợi bid theo FIFO
class BidQueueProcessor {
    // Chọn ArrayDeque (không phải LinkedList) vì:
    //  - Không tạo Node + 3 con trỏ cho mỗi phần tử -> ít overhead bộ nhớ, nhẹ cho GC.
    //  - Mảng vòng liền kề -> cache locality tốt -> xử lý nhanh hơn.
    //  - Hàng đợi bid không bao giờ chứa null, nên hạn chế null của ArrayDeque không cản trở.
    private final Deque<Bid> queue = new ArrayDeque<>();

    public void submit(Bid bid) {
        queue.offer(bid); // thêm vào CUỐI hàng đợi
    }

    public void processAll() {
        Bid bid;
        while ((bid = queue.poll()) != null) { // lấy từ ĐẦU -> FIFO
            System.out.printf("Xử lý bid của %s giá %,d%n",
                    bid.bidder(), bid.amount());
        }
    }

    public int pending() {
        return queue.size();
    }
}

class AuctionApp {
    public static void main(String[] args) {
        BidQueueProcessor processor = new BidQueueProcessor();
        processor.submit(new Bid("an",   100_000, System.currentTimeMillis()));
        processor.submit(new Bid("binh", 120_000, System.currentTimeMillis()));
        processor.submit(new Bid("cuong",150_000, System.currentTimeMillis()));

        System.out.println("Số bid đang chờ: " + processor.pending()); // 3
        processor.processAll(); // in theo thứ tự an -> binh -> cuong (FIFO)
        System.out.println("Số bid đang chờ: " + processor.pending()); // 0
    }
}
```

> ✅ **Bài tập mở rộng:** thử thay `ArrayDeque` bằng `LinkedList` và xác nhận chương trình chạy y hệt — đó là minh chứng cho việc "program to interface" (`Deque`) giúp đổi cài đặt mà không sửa logic. Sau đó tự trả lời: vì sao production ta vẫn chọn `ArrayDeque`?

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: `LinkedList` cài đặt theo cấu trúc dữ liệu nào? Mỗi node gồm những gì?**
> **Đáp:** Doubly-linked list (danh sách liên kết đôi). Mỗi node gồm 3 trường: `item` (dữ liệu), `next` (con trỏ tới node sau) và `prev` (con trỏ tới node trước). Bản thân `LinkedList` giữ con trỏ `first`, `last` và biến `size`.

**Q2: Vì sao `get(index)` trên `LinkedList` là `O(n)` còn trên `ArrayList` là `O(1)`?**
> **Đáp:** `ArrayList` lưu trên mảng liền kề nên truy cập theo index chỉ là phép tính địa chỉ — tức thì. `LinkedList` không có random access; muốn lấy phần tử thứ `i` phải duyệt từng node theo con trỏ `next`/`prev`. JDK tối ưu bằng cách duyệt từ đầu gần hơn (xuôi nếu `i < size/2`, ngược nếu ngược lại) nhưng vẫn là `O(n)`.

**Q3: Thao tác nào trên `LinkedList` là `O(1)`?**
> **Đáp:** Thêm/xóa/lấy ở **đầu** và **cuối**: `addFirst/addLast`, `removeFirst/removeLast`, `getFirst/getLast`. Vì luôn có sẵn con trỏ `first`/`last` nên chỉ cần chỉnh vài con trỏ, không dịch chuyển phần tử nào.

**Q4: `LinkedList` cài đặt những interface nào ngoài `List`?**
> **Đáp:** Cài cả `Deque` (và do đó `Queue`). Nhờ vậy dùng được như queue (`offer/poll/peek`), stack (`push/pop`) và deque (thao tác hai đầu).

### Mức Senior

**Q5: Lý thuyết nói `LinkedList` chèn/xóa nhanh hơn, vì sao thực tế `ArrayList` thường thắng?**
> **Đáp:** Hai lý do. (1) **Overhead bộ nhớ:** mỗi node `LinkedList` là một object với header + 3 tham chiếu, tốn gấp nhiều lần một ô của `ArrayList`, làm tăng áp lực GC. (2) **Cache locality:** mảng `ArrayList` liền kề nên CPU đọc theo cache line rất nhanh; node `LinkedList` rải rác khắp Heap gây cache miss khi nhảy con trỏ (pointer chasing). Ngoài ra việc dịch mảng của `ArrayList` được tối ưu ở mức rất thấp qua `System.arraycopy`. Kết quả là `ArrayList` thường nhanh hơn ngay cả ở các kịch bản tưởng chừng có lợi cho `LinkedList`.

**Q6: Khi nào nên dùng `ArrayDeque` thay cho `LinkedList`, và vì sao?**
> **Đáp:** Gần như mọi lúc cần stack/queue/deque. `ArrayDeque` dùng mảng vòng nên không tạo node phụ (ít overhead, cache locality tốt) → nhanh hơn và nhẹ hơn cho GC. Hạn chế: không cho phép `null`. JDK cũng khuyến nghị `ArrayDeque` thay cho cả `Stack` (cũ, synchronized) lẫn `LinkedList` khi làm ngăn xếp/hàng đợi.

**Q7: Vì sao vòng lặp `for (int i=0;i<list.size();i++) list.get(i)` trên `LinkedList` lại nguy hiểm?**
> **Đáp:** Vì mỗi `get(i)` là `O(n)` (duyệt lại từ đầu/cuối), tổng cộng thành `O(n²)` — với danh sách lớn sẽ chậm thảm họa. Phải duyệt bằng `Iterator`/for-each (mỗi bước chỉ nhảy một con trỏ `next`) để giữ `O(n)`.

**Q8: Khác biệt giữa cặp `offer/poll/peek` và `add/remove/element` của `Queue`?**
> **Đáp:** `offer/poll/peek` xử lý "nhẹ nhàng" khi hàng đợi rỗng/đầy: trả về `false`/`null` thay vì ném exception. `add/remove/element` ném `IllegalStateException`/`NoSuchElementException`. Trong code production thường ưu tiên `offer/poll/peek` để không dùng exception điều khiển luồng.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ được sơ đồ doubly-linked list với `prev`/`item`/`next`, `first`/`last`
- [ ] Giải thích vì sao add/remove đầu–cuối là `O(1)` nhưng `get(index)` là `O(n)`
- [ ] Biết `LinkedList` dùng được như Queue, Stack, Deque (`offer/poll/peek`, `push/pop`)
- [ ] Lập bảng so sánh ArrayList vs LinkedList và hiểu vai trò cache locality
- [ ] Giải thích vì sao mặc định dùng `ArrayList`, cần queue/deque thì dùng `ArrayDeque`
- [ ] Đối chiếu được với `SplDoublyLinkedList`/`SplQueue`/`SplStack` của PHP
- [ ] Chạy benchmark `get(index)` thấy `LinkedList` chậm hơn `ArrayList`
- [ ] Hoàn thành Mini Project: `BidQueueProcessor` dùng `ArrayDeque` (FIFO)
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Javadoc `java.util.LinkedList`, `java.util.ArrayDeque`, `java.util.Deque`, `java.util.Queue`
- Baeldung — "ArrayList vs. LinkedList" và "Java ArrayDeque"
- Bài giảng/đoạn nói của Joshua Bloch & các maintainer JDK về việc "đừng dùng LinkedList" (tìm "LinkedList rarely the right choice")
- Sách *Effective Java* (Joshua Bloch) — mục về chọn cấu trúc dữ liệu phù hợp
- PHP Manual — `SplDoublyLinkedList`, `SplQueue`, `SplStack` (để đối chiếu)
- Sách *Java Performance* (Scott Oaks) — phần về collection và cache behavior
