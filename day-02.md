# Day 02 - Memory Model

> **Giai đoạn:** Java Foundation & JVM
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Nắm rõ **các vùng nhớ của JVM**: Heap, JVM Stack, Metaspace, PC Register, Native Method Stack — vùng nào dùng chung, vùng nào riêng từng thread.
- Hiểu **biến primitive nằm ở Stack, object nằm ở Heap**, và reference chỉ là "con trỏ" trên Stack trỏ tới object trên Heap.
- Hiểu cách Heap chia **Young Generation (Eden + S0 + S1)** và **Old Generation** — nền tảng để học GC sau này.
- Phân biệt **`StackOverflowError` vs `OutOfMemoryError`** — câu hỏi phỏng vấn kinh điển.
- Biết các flag JVM **`-Xms`, `-Xmx`, `-Xss`, `-XX:MaxMetaspaceSize`** dùng để làm gì.
- Hiểu vì sao Java (long-running process) phải bận tâm chuyện bộ nhớ, còn PHP/Laravel thì "giấu" giúp bạn.

---

## 🧠 Lý thuyết cốt lõi

### 1. Toàn cảnh các vùng nhớ trong JVM

Khi JVM khởi động, nó xin một khối RAM từ hệ điều hành rồi tự **chia khối đó thành nhiều vùng nhớ (Runtime Data Areas)**, mỗi vùng một nhiệm vụ:

```
┌──────────────────────────────────────────────────────────────────┐
│                          JVM Process (RAM)                         │
│                                                                    │
│  ┌────────────────────────────┐   ┌────────────────────────────┐  │
│  │   DÙNG CHUNG mọi thread     │   │     RIÊNG từng thread       │  │
│  │                            │   │                            │  │
│  │  ┌──────────────────────┐  │   │  Thread-1   Thread-2 ...    │  │
│  │  │       HEAP           │  │   │  ┌────────┐ ┌────────┐      │  │
│  │  │ (object, mảng,       │  │   │  │ JVM    │ │ JVM    │      │  │
│  │  │  String Pool)        │  │   │  │ Stack  │ │ Stack  │      │  │
│  │  └──────────────────────┘  │   │  ├────────┤ ├────────┤      │  │
│  │  ┌──────────────────────┐  │   │  │ PC Reg │ │ PC Reg │      │  │
│  │  │     METASPACE        │  │   │  ├────────┤ ├────────┤      │  │
│  │  │ (metadata của class) │  │   │  │ Native │ │ Native │      │  │
│  │  │  -> native memory    │  │   │  │ Method │ │ Method │      │  │
│  │  └──────────────────────┘  │   │  │ Stack  │ │ Stack  │      │  │
│  │                            │   │  └────────┘ └────────┘      │  │
│  └────────────────────────────┘   └────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

Phân loại theo phạm vi truy cập — đây là điều **phải nhớ**:

| Vùng nhớ | Phạm vi | Lưu gì | Khi nào hết bộ nhớ |
|---|---|---|---|
| **Heap** | Dùng chung mọi thread | Mọi object (`new ...`), mảng, String Pool | `OutOfMemoryError: Java heap space` |
| **JVM Stack** | Riêng từng thread | Stack frame của mỗi lời gọi method (biến local primitive, reference) | `StackOverflowError` (hoặc OOM khi tạo quá nhiều thread) |
| **Metaspace** | Dùng chung mọi thread | Metadata của class (cấu trúc class, method, field, constant pool) | `OutOfMemoryError: Metaspace` |
| **PC Register** | Riêng từng thread | Địa chỉ lệnh bytecode đang chạy của thread đó | (rất nhỏ, hầu như không bao giờ tràn) |
| **Native Method Stack** | Riêng từng thread | Stack cho code native (C/C++) gọi qua JNI | Hiếm gặp lỗi |

> 💡 **Quy tắc vàng để nhớ:** *Object luôn ở Heap (dùng chung). Mọi thứ "cục bộ trong một lời gọi method" ở Stack (riêng từng thread).* Reference tới object nằm ở Stack, nhưng bản thân object thì nằm ở Heap.

### 2. Heap — nơi sống của mọi object

Heap là vùng lớn nhất, **dùng chung cho mọi thread**, và là nơi **Garbage Collector (GC)** làm việc. Mọi lần bạn gọi `new`, object được cấp phát ở đây.

Để GC hiệu quả, Heap được chia thành **Young Generation** và **Old Generation** (kiến trúc "generational"):

```
                              HEAP
┌───────────────────────────────────────────────┬───────────────────┐
│            YOUNG GENERATION                     │   OLD GENERATION  │
│                                                 │   (Tenured)       │
│  ┌──────────────┐  ┌─────────┐  ┌─────────┐     │                   │
│  │     EDEN     │  │   S0    │  │   S1    │     │  Object "sống lâu" │
│  │ (object mới  │  │Survivor │  │Survivor │     │  trụ lại sau nhiều │
│  │  sinh ra)    │  │   0     │  │   1     │     │  vòng GC -> dồn về  │
│  └──────────────┘  └─────────┘  └─────────┘     │  đây                │
│         Minor GC dọn nhanh, thường xuyên         │  Major/Full GC dọn  │
│                                                 │  chậm hơn, ít hơn   │
└───────────────────────────────────────────────┴───────────────────┘
```

Vòng đời điển hình của một object:

1. `new` → object sinh ra ở **Eden**.
2. Eden đầy → chạy **Minor GC**: object còn sống được copy sang một Survivor (S0 hoặc S1), object chết bị dọn.
3. Mỗi lần sống sót qua một Minor GC, object tăng "tuổi" (age) và đảo qua lại giữa S0 ↔ S1.
4. Khi đủ già (vượt ngưỡng `MaxTenuringThreshold`), object được **promote** sang **Old Generation**.
5. Old Gen đầy → chạy **Major/Full GC** (nặng hơn).

> 💡 Triết lý đằng sau thiết kế này: **"hầu hết object chết trẻ"** (weak generational hypothesis). Đa số object (DTO, biến tạm trong một request) sống rất ngắn. Tách Young Gen ra để dọn nhanh đám "chết trẻ" này mà không phải quét cả Heap.

> ⚠️ Từ **Java 7**, **String Pool** (nơi cache các string literal) được chuyển vào **Heap**. Trước đó nó nằm trong PermGen. Ta sẽ đào sâu String & String Pool ở **Day 05**, hôm nay chỉ cần biết: string literal sống trong Heap.

### 3. JVM Stack & Stack Frame — vùng nhớ của mỗi lời gọi method

Mỗi **thread** có một **JVM Stack riêng**. Mỗi khi thread gọi một method, JVM **push** một **Stack Frame** lên đỉnh stack đó. Method trả về → frame bị **pop** ra. Đây là cơ chế "vào sau ra trước" (LIFO) kinh điển.

Một **Stack Frame** chứa 3 thành phần:

```
        JVM STACK của Thread-1 (LIFO)
   ┌──────────────────────────────────┐
   │  Frame: c()   <-- đỉnh, đang chạy │
   ├──────────────────────────────────┤
   │  Frame: b()                       │
   ├──────────────────────────────────┤
   │  Frame: main()  <-- đáy           │
   └──────────────────────────────────┘

   Mỗi Frame gồm:
   ┌─────────────────────────────────────────────┐
   │ 1) Local Variable Array                      │
   │    - biến local primitive (int, double...)   │
   │    - reference tới object (chỉ con trỏ!)     │
   │    - tham số của method, biến `this`         │
   ├─────────────────────────────────────────────┤
   │ 2) Operand Stack                             │
   │    - vùng tạm để JVM tính toán bytecode      │
   │      (cộng, trừ, gọi method...)              │
   ├─────────────────────────────────────────────┤
   │ 3) Frame Data                                │
   │    - tham chiếu tới constant pool của class  │
   │    - thông tin để xử lý exception, return    │
   └─────────────────────────────────────────────┘
```

Điểm mấu chốt cần khắc cốt ghi tâm:

```java
void demo() {
    int count = 5;                 // 'count' (primitive) nằm TRONG Local Variable Array (Stack)
    Auction a = new Auction();     // object Auction nằm ở HEAP;
                                   // 'a' chỉ là reference (con trỏ) nằm ở Stack, trỏ tới Heap
}
```

```
   STACK (frame demo)          HEAP
   ┌───────────────┐           ┌─────────────────────┐
   │ count = 5     │           │  Auction object     │
   │ a  ───────────┼──────────►│  (các field bên trong)│
   └───────────────┘           └─────────────────────┘
```

> 💡 Vì Stack rất nhanh (chỉ push/pop) và tự dọn khi method return, **biến local không cần GC**. GC chỉ quét Heap. Đó là lý do nên ưu tiên biến cục bộ ngắn hạn.

### 4. Metaspace — thay thế PermGen từ Java 8

Trước Java 8, metadata của class được lưu trong **PermGen (Permanent Generation)**, nằm **bên trong Heap** và có kích thước cố định → rất dễ dính `OutOfMemoryError: PermGen space` khi nạp nhiều class (đặc biệt app dùng nhiều framework, redeploy nhiều lần).

Từ **Java 8**, PermGen bị **xóa bỏ** và thay bằng **Metaspace**:

| Tiêu chí | PermGen (≤ Java 7) | Metaspace (≥ Java 8) |
|---|---|---|
| Nằm ở đâu | Bên trong **Heap** | **Native memory** (RAM của OS, ngoài Heap) |
| Kích thước | Cố định, do `-XX:MaxPermSize` | Tự **co giãn** theo nhu cầu (mặc định không giới hạn) |
| Lưu gì | Metadata class, (xưa cả String Pool) | Metadata class (cấu trúc class, method, field...) |
| Lỗi điển hình | `OutOfMemoryError: PermGen space` | `OutOfMemoryError: Metaspace` |
| Giới hạn bằng flag | `-XX:MaxPermSize=...` | `-XX:MaxMetaspaceSize=...` |

> ⚠️ Vì Metaspace mặc định **co giãn tự do trong native memory**, một bug nạp class vô hạn (ví dụ sinh proxy/class động liên tục) có thể **ăn hết RAM của máy** chứ không chỉ tràn Heap. Trong production nên đặt `-XX:MaxMetaspaceSize` để có "phanh".

### 5. `StackOverflowError` vs `OutOfMemoryError` — phân biệt cho chuẩn

Đây là cặp đôi hay bị nhầm. Cả hai đều là `Error` (không phải `Exception`), thường **không nên catch** để xử lý logic.

| | `StackOverflowError` | `OutOfMemoryError` |
|---|---|---|
| Cạn vùng nào | **JVM Stack** của 1 thread | **Heap** (hoặc **Metaspace**) |
| Nguyên nhân điển hình | Đệ quy quá sâu / không có điều kiện dừng; chuỗi gọi method quá dài | Tạo quá nhiều object sống lâu; rò rỉ bộ nhớ (memory leak); collection phình to không kiểm soát |
| Giới hạn bởi | `-Xss` (kích thước stack mỗi thread) | `-Xmx` (heap tối đa) / `-XX:MaxMetaspaceSize` |
| Thông điệp hay gặp | `java.lang.StackOverflowError` | `OutOfMemoryError: Java heap space` / `: Metaspace` |
| Khắc phục đúng | Sửa logic đệ quy (thêm điều kiện dừng, chuyển sang vòng lặp) | Tìm & vá memory leak, chỉnh `-Xmx`, dùng heap dump phân tích |

> ⚠️ **Đừng "chữa cháy" sai chỗ.** Gặp `StackOverflowError` mà tăng `-Xss` là sai bản chất — gốc rễ thường là đệ quy lỗi. Gặp `OutOfMemoryError` mà tăng `-Xmx` cũng chỉ trì hoãn nếu có memory leak thật sự. Phải tìm **nguyên nhân**, không chỉ nới giới hạn.

### 6. Java Memory Model (JMM) — sơ lược

Đừng nhầm: **"vùng nhớ JVM" (mục 1)** khác với **"Java Memory Model (JMM)"**. JMM là **bộ quy tắc** mô tả cách các **thread** nhìn thấy giá trị biến khi chạy song song — nó nói về **tính nhất quán bộ nhớ giữa các thread**, không phải về layout Heap/Stack.

Ba khái niệm cốt lõi của JMM (chỉ cần "nghe tên" hôm nay):

- **Visibility (tính nhìn thấy):** thread A ghi một biến, liệu thread B có thấy giá trị mới không? (liên quan từ khóa `volatile`).
- **Atomicity (tính nguyên tử):** một thao tác có "trọn vẹn không bị chen ngang" không? (ví dụ `count++` thực ra **không** nguyên tử).
- **Happens-before:** quan hệ "xảy ra trước" đảm bảo thứ tự và visibility giữa các thao tác trên nhiều thread.

> 💡 Vì sao nhắc ở đây? Vì Heap **dùng chung mọi thread** → khi nhiều thread cùng đọc/ghi object trên Heap, sẽ phát sinh vấn đề nhất quán. JMM chính là tập luật giải quyết chuyện đó. **Ta sẽ đào sâu JMM, `volatile`, `synchronized`, happens-before ở phần Concurrency (các ngày sau).** Hôm nay chỉ cần nhớ: *Stack riêng từng thread nên an toàn; Heap dùng chung nên mới sinh ra bài toán đồng bộ.*

### 7. Các flag JVM quan trọng về bộ nhớ

| Flag | Ý nghĩa | Ví dụ |
|---|---|---|
| `-Xms` | **Heap khởi tạo** (initial heap size) — JVM xin sẵn từng đó ngay khi start | `-Xms512m` |
| `-Xmx` | **Heap tối đa** (maximum heap size) — Heap không được vượt mức này, vượt → `OutOfMemoryError` | `-Xmx2g` |
| `-Xss` | **Kích thước Stack mỗi thread** — stack nhỏ → dễ `StackOverflowError`; stack lớn → tốn RAM khi nhiều thread | `-Xss512k` |
| `-XX:MaxMetaspaceSize` | Giới hạn **Metaspace** (native memory chứa metadata class) | `-XX:MaxMetaspaceSize=256m` |

> 💡 **Mẹo production:** đặt `-Xms` **bằng** `-Xmx` (ví dụ `-Xms2g -Xmx2g`) để JVM cấp sẵn toàn bộ Heap ngay từ đầu, tránh chi phí resize Heap lúc runtime và tránh GC pause bất ngờ. Đây là cấu hình rất phổ biến cho service Java trong container.

> ⚠️ `-Xss` áp cho **mỗi thread**. Một server web có hàng nghìn thread; đặt `-Xss` quá lớn (ví dụ `-Xss20m`) có thể ngốn RAM khủng khiếp (20m × số thread). Mặc định thường ~512k–1m là đủ.

---

## 🔁 Đối chiếu với Laravel/PHP

Mô hình bộ nhớ của PHP và Java **khác nhau căn bản** — đây là chỗ dân Laravel hay vấp:

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| Vòng đời tiến trình | Mỗi request "sinh ra rồi chết" (shared-nothing) | Tiến trình **chạy lâu dài** (long-running), giữ Heap suốt vòng đời |
| Heap dùng chung sống lâu | **Không có** — bộ nhớ giải phóng sạch khi request kết thúc | **Có** — Heap tồn tại xuyên suốt, object có thể sống nhiều giờ/ngày |
| Giới hạn bộ nhớ | `memory_limit` trong `php.ini` (mỗi request) | `-Xmx` cho toàn JVM (cả app) |
| Dọn rác | Cứ kết thúc request là OS thu hồi toàn bộ | **Garbage Collector** chạy nền dọn Heap theo generation |
| State giữa request | Phải lưu ngoài (DB, Redis, session) | Có thể giữ trong RAM (static, Singleton bean, cache in-memory) |
| Stack/đệ quy | Có giới hạn riêng, nhưng request ngắn nên ít gặp | `StackOverflowError` rõ ràng, chỉnh bằng `-Xss` |
| "Memory leak" | Hiếm khi phải lo (request chết là sạch) | **Phải lo** — object bị giữ tham chiếu mãi sẽ không bao giờ được GC |

**Khác biệt tư duy quan trọng nhất:**

- **PHP/Laravel (shared-nothing):** mỗi HTTP request được cấp một vùng nhớ riêng, chạy xong → giải phóng **toàn bộ**. Bạn gần như không bao giờ phải nghĩ tới "object còn sống sau request" hay "rò rỉ bộ nhớ tích lũy". `memory_limit` chỉ bảo vệ **một** request không ngốn quá nhiều.

- **Java/Spring Boot (long-running):** chỉ có **một** tiến trình JVM phục vụ **mọi** request. Heap **dùng chung và sống mãi**. Nếu bạn vô tình giữ tham chiếu (ví dụ nhét object vào một `static List` mà không bao giờ xóa), object đó **không bao giờ được GC** → Heap phình dần → `OutOfMemoryError` sau vài ngày. Đây chính là **memory leak** — khái niệm mà dân PHP thuần thường chưa quen.

> 🧩 Hệ quả thực tế: ở Laravel bạn quen `Cache::put(...)` rồi quên; ở Java nếu bạn tự cache bằng một `HashMap` static mà không có chính sách hết hạn/evict, cache đó sẽ ăn Heap đến chết. Long-running buộc bạn phải **suy nghĩ về vòng đời object**.

---

## 💻 Thực hành code

### Bước 1 — Minh họa: primitive ở Stack, object ở Heap

```java
// File: MemoryDemo.java  (Java 21)
public class MemoryDemo {

    // Một class đơn giản để tạo object trên Heap
    static class Point {
        int x;            // field nằm BÊN TRONG object -> ở Heap
        int y;
        Point(int x, int y) { this.x = x; this.y = y; }
    }

    public static void main(String[] args) {
        // 'count' là biến local primitive -> nằm ở STACK (trong Local Variable Array của frame main)
        int count = 10;

        // 'p1' là REFERENCE -> nằm ở STACK;
        // còn object Point thật sự (x=1, y=2) -> nằm ở HEAP
        Point p1 = new Point(1, 2);

        // 'p2' là một reference KHÁC trên Stack, nhưng trỏ vào CÙNG object Heap với p1
        Point p2 = p1;

        // Vì p1 và p2 trỏ cùng 1 object trên Heap -> sửa qua p2 thì p1 cũng "thấy"
        p2.x = 999;
        System.out.println("p1.x = " + p1.x);   // In ra 999, KHÔNG phải 1!

        // Còn primitive thì copy theo GIÁ TRỊ, không chia sẻ:
        int a = count;     // 'a' là bản sao của count trên Stack
        a = 50;
        System.out.println("count = " + count); // Vẫn là 10, vì a chỉ là bản sao
    }
}
```

Chạy:

```bash
javac MemoryDemo.java
java MemoryDemo
# p1.x = 999
# count = 10
```

> 💡 Bài học: **reference được copy theo địa chỉ** (cùng trỏ vào 1 object Heap), còn **primitive được copy theo giá trị**. Đây là gốc rễ của vô số bug "sao sửa object này lại ảnh hưởng object kia".

### Bước 2 — Gây `StackOverflowError` bằng đệ quy vô hạn

```java
// File: StackOverflowDemo.java  (Java 21)
public class StackOverflowDemo {

    // Đệ quy KHÔNG có điều kiện dừng -> mỗi lần gọi push thêm 1 frame lên Stack
    static int recurse(int depth) {
        // In ra độ sâu để thấy stack lớn cỡ nào trước khi tràn
        System.out.println("Độ sâu: " + depth);
        return recurse(depth + 1);   // gọi lại chính nó mãi mãi
    }

    public static void main(String[] args) {
        try {
            recurse(1);
        } catch (StackOverflowError e) {
            // StackOverflowError là Error, nhưng ở demo ta bắt để thấy nó xảy ra
            System.out.println(">>> Đã tràn Stack! JVM ném StackOverflowError.");
        }
    }
}
```

Chạy với **stack nhỏ** rồi **stack lớn** để thấy khác biệt:

```bash
javac StackOverflowDemo.java

# Stack nhỏ (256k): tràn rất SỚM, depth thấp
java -Xss256k StackOverflowDemo

# Stack lớn (4m): tràn MUỘN hơn nhiều, depth cao hơn hẳn
java -Xss4m StackOverflowDemo
```

Bạn sẽ thấy: với `-Xss256k`, chương trình in tới độ sâu thấp rồi tràn; với `-Xss4m`, nó đi sâu hơn rất nhiều mới tràn. Điều đó **chứng minh `-Xss` điều khiển kích thước Stack mỗi thread**.

> ⚠️ Lưu ý: con số `depth` cụ thể phụ thuộc JVM, OS, kích thước frame — **không cố định**. Trọng tâm là quan sát *xu hướng*: stack nhỏ → tràn sớm, stack lớn → tràn muộn.

### Bước 3 — Quan sát thông tin bộ nhớ runtime

```java
// File: HeapInfo.java  (Java 21)
public class HeapInfo {
    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024;
        // Các con số này phản ánh giới hạn Heap mà -Xms / -Xmx thiết lập
        System.out.println("Heap tối đa (maxMemory ~ -Xmx): " + rt.maxMemory() / mb + " MB");
        System.out.println("Heap đang cấp (totalMemory):    " + rt.totalMemory() / mb + " MB");
        System.out.println("Heap còn trống (freeMemory):    " + rt.freeMemory() / mb + " MB");
    }
}
```

```bash
javac HeapInfo.java
# So sánh kết quả khi đổi -Xmx:
java -Xms64m -Xmx128m HeapInfo
java -Xms256m -Xmx512m HeapInfo
```

> ✅ **Bài tập tự giải thích:** Vì sao trong demo Bước 1, sửa `p2.x` lại làm `p1.x` đổi theo, nhưng gán `a = count` rồi đổi `a` thì `count` không đổi?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Tưởng object nằm ở Stack.** Sai — object **luôn** ở Heap; chỉ **reference** (con trỏ) tới nó nằm ở Stack. Primitive local mới nằm trực tiếp ở Stack.
- **Nhầm `StackOverflowError` với `OutOfMemoryError`.** Một cái cạn Stack (đệ quy sâu), một cái cạn Heap/Metaspace (quá nhiều object / leak). Chữa sai chỗ là vô ích.
- **Tăng `-Xss` để "sửa" `StackOverflowError`.** Gốc rễ gần như luôn là đệ quy thiếu điều kiện dừng — phải sửa logic, không phải nới stack.
- **Nghĩ GC dọn được mọi thứ tự động nên không bao giờ leak.** Sai — nếu bạn **còn giữ tham chiếu** (static collection, listener không gỡ, cache không evict), GC **không** dọn được → leak. Dân Laravel rất hay dính vì quen "request chết là sạch".
- **Nhầm Metaspace vẫn là PermGen.** Từ Java 8, PermGen đã bị xóa; metadata class nằm ở Metaspace trong **native memory**, không phải trong Heap.
- **Đặt `-Xms` rất nhỏ so với `-Xmx` trong container.** Heap phải resize liên tục, gây GC pause; nên đặt `-Xms = -Xmx` cho service production.
- **Catch `Error` để "xử lý cho an toàn".** `StackOverflowError`/`OutOfMemoryError` là dấu hiệu hệ thống đã hỏng; catch để chạy tiếp thường che giấu bug nghiêm trọng.

---

## 🚀 Liên hệ Spring Boot / Production

- App **Spring Boot** chạy bằng `java -jar app.jar`. Bạn cấu hình bộ nhớ ngay ở đây, ví dụ:
  ```bash
  java -Xms1g -Xmx1g -Xss512k -XX:MaxMetaspaceSize=256m -jar app.jar
  ```
- Trong **Docker/Kubernetes**, JVM hiện đại (Java 11+) **tự nhận diện giới hạn RAM của container** (cgroups). Có thể dùng `-XX:MaxRAMPercentage=75.0` để Heap chiếm 75% RAM container thay vì hardcode `-Xmx`. Nếu không cấu hình, container dễ bị **OOMKilled** (kernel kill khi vượt limit) — khác với `OutOfMemoryError` của JVM.
- **Memory leak là sự cố production kinh điển của Java:** một `static Map` cache không evict, một `ThreadLocal` không `remove()`, hay listener không gỡ → Heap phình dần, GC chạy ngày càng nhiều, app chậm rồi `OutOfMemoryError`. Công cụ điều tra: **heap dump** (`jmap`, `-XX:+HeapDumpOnOutOfMemoryError`) + **Eclipse MAT** / **VisualVM**.
- **Metaspace leak** xuất hiện ở app nạp class động liên tục (hot-reload, sinh proxy). Đặt `-XX:MaxMetaspaceSize` để phát hiện sớm thay vì để nó ăn hết RAM host.
- Hiểu Young/Old Gen giúp bạn đọc log GC và chọn **GC collector** phù hợp (G1, ZGC...) — chủ đề ta sẽ đào sâu khi học về Garbage Collection.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Tiếp nối Day 01 (đã có class `AuctionApp`). Hôm nay ta **phác thảo domain** và **phân tích chúng nằm ở vùng nhớ nào**.

Phác thảo hai class lõi:

```java
// File: Auction.java  (Java 21)
import java.util.ArrayList;
import java.util.List;

public class Auction {
    private final String itemName;        // reference -> String trong Heap
    private double currentPrice;          // primitive field -> nằm trong object (Heap)
    private final List<Bid> bids;         // reference -> List trong Heap

    public Auction(String itemName, double startPrice) {
        this.itemName = itemName;
        this.currentPrice = startPrice;
        this.bids = new ArrayList<>();     // List object cấp phát trên Heap
    }

    public void placeBid(Bid bid) {        // 'bid' là tham số reference -> ở Stack của frame này
        if (bid.amount() > currentPrice) { // 'bid.amount()' đọc field của object trên Heap
            this.currentPrice = bid.amount();
            this.bids.add(bid);            // thêm reference Bid vào List trên Heap
        }
    }

    public int bidCount() {
        return bids.size();
    }
}
```

```java
// File: Bid.java  (Java 21) -- dùng record cho gọn
public record Bid(String bidder, double amount) { }
// Mỗi Bid là một object trên Heap; 'bidder' (String) và 'amount' (double field) nằm trong object đó
```

```java
// File: AuctionApp.java  (Java 21) -- nối tiếp Day 01
public class AuctionApp {
    public static void main(String[] args) {
        // 'count' là biến local primitive -> nằm ở STACK (frame main)
        int count = 0;

        // 'auction' là REFERENCE trên STACK;
        // object Auction (kèm field currentPrice và List<Bid> rỗng) -> nằm ở HEAP
        Auction auction = new Auction("Tranh cổ", 1_000_000);

        // Mỗi 'new Bid(...)' tạo một object Bid trên HEAP;
        // biến tạm trong vòng lặp là reference trên Stack, trỏ vào Heap
        auction.placeBid(new Bid("An", 1_200_000));
        auction.placeBid(new Bid("Bình", 1_500_000));
        count = auction.bidCount();        // gán giá trị primitive vào 'count' trên Stack

        System.out.println("Số lượt đấu giá: " + count);   // 2
    }
}
```

**Phân tích vùng nhớ (nhiệm vụ Day 02):**

```
   STACK (frame main)                 HEAP (dùng chung)
   ┌────────────────────┐             ┌──────────────────────────────┐
   │ count = 2          │             │ Auction { currentPrice=1.5tr,│
   │ auction ───────────┼────────────►│           bids ──┐           │
   └────────────────────┘             │ }                │           │
                                      │   ┌──────────────▼─────────┐ │
                                      │   │ ArrayList<Bid>          │ │
                                      │   │  [0] ─► Bid("An",1.2tr)  │ │
                                      │   │  [1] ─► Bid("Bình",1.5tr)│ │
                                      │   └─────────────────────────┘ │
                                      └──────────────────────────────┘
```

- **Ở Stack:** `count` (primitive), `auction` (reference), tham số `bid` trong `placeBid` (reference) — tất cả đều thuộc frame của method tương ứng, biến mất khi method return.
- **Ở Heap:** object `Auction`, `ArrayList`, mọi object `Bid`, và các `String` (`"Tranh cổ"`, `"An"`, `"Bình"`). Chúng sống đến khi không còn reference nào trỏ tới → GC dọn.

**Nhiệm vụ Day 02:**
1. Tạo các file `Auction.java`, `Bid.java`, cập nhật `AuctionApp.java`, biên dịch & chạy bằng `javac`/`java`.
2. Vẽ lại (tay hoặc ASCII) sơ đồ Stack–Heap cho `AuctionApp.main` như trên.
3. Chạy thử với `-Xmx32m` và tạo vòng lặp thêm thật nhiều `Bid` để **quan sát** khi nào tiến gần `OutOfMemoryError` (chỉ để hiểu, đừng giữ trong code thật).
4. Ghi vào `notes/day-02.md`: trả lời bằng lời của bạn — *"Trong `AuctionApp`, biến nào ở Stack, object nào ở Heap, và vì sao?"*

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Stack và Heap lưu gì? Khác nhau ra sao?**
> **Đáp:** Heap dùng chung mọi thread, lưu **mọi object** (`new`), mảng, String Pool — do GC quản lý. Stack riêng từng thread, lưu **stack frame** của mỗi lời gọi method: biến local primitive, reference tới object, và vùng tính toán tạm. Object luôn ở Heap; reference tới nó nằm ở Stack. Stack tự dọn khi method return (không cần GC), Heap cần GC.

**Q2: `StackOverflowError` và `OutOfMemoryError` khác nhau thế nào?**
> **Đáp:** `StackOverflowError` xảy ra khi **JVM Stack của một thread cạn**, điển hình do đệ quy quá sâu/không có điều kiện dừng; chỉnh bằng `-Xss` nhưng gốc rễ thường là logic. `OutOfMemoryError` xảy ra khi **Heap (hoặc Metaspace) cạn**, do tạo quá nhiều object sống lâu hoặc memory leak; liên quan `-Xmx`/`-XX:MaxMetaspaceSize`. Cả hai đều là `Error`, không nên catch để xử lý logic.

**Q3: Biến primitive và object được lưu khác nhau ra sao?**
> **Đáp:** Biến local primitive (`int`, `double`...) nằm trực tiếp trong Local Variable Array của stack frame. Với object, bản thân object nằm trên Heap, còn biến local chỉ là **reference** (con trỏ) trên Stack trỏ tới object đó. Vì vậy gán hai reference cho cùng một object thì chúng chia sẻ object, còn gán primitive thì copy theo giá trị.

**Q4: `-Xms`, `-Xmx`, `-Xss` làm gì?**
> **Đáp:** `-Xms` đặt Heap khởi tạo (JVM xin sẵn từng đó lúc start). `-Xmx` đặt Heap tối đa (vượt → `OutOfMemoryError`). `-Xss` đặt kích thước Stack cho **mỗi thread** (nhỏ → dễ `StackOverflowError`, lớn → tốn RAM khi nhiều thread). Thực tế thường đặt `-Xms = -Xmx` để tránh resize Heap lúc chạy.

### Mức Senior

**Q5: Metaspace khác PermGen ra sao? Vì sao Java 8 thay đổi?**
> **Đáp:** PermGen (≤ Java 7) nằm **trong Heap**, kích thước cố định (`-XX:MaxPermSize`), rất dễ `OutOfMemoryError: PermGen space` khi nạp nhiều class hoặc redeploy. Từ Java 8, PermGen bị xóa, thay bằng **Metaspace** nằm ở **native memory** (ngoài Heap), tự co giãn theo nhu cầu (mặc định không giới hạn, chặn bằng `-XX:MaxMetaspaceSize`). Mục tiêu: giảm lỗi PermGen và tách metadata class khỏi Heap. Rủi ro mới: Metaspace có thể ăn hết RAM host nếu nạp class động vô hạn.

**Q6: Heap chia Young/Old Generation để làm gì?**
> **Đáp:** Dựa trên giả thuyết "hầu hết object chết trẻ". Young Gen (Eden + 2 Survivor S0/S1) chứa object mới; Minor GC dọn nhanh, thường xuyên, copy object còn sống qua Survivor và tăng tuổi. Object sống đủ lâu được promote sang Old Gen, nơi Major/Full GC chạy ít hơn nhưng nặng hơn. Cách chia này giúp GC chỉ quét vùng nhỏ "chết trẻ" phần lớn thời gian, thay vì quét cả Heap → throughput cao hơn, pause ngắn hơn.

**Q7: Java có GC sao vẫn bị memory leak? Điều tra thế nào?**
> **Đáp:** GC chỉ thu hồi object **không còn reference nào** trỏ tới (unreachable). Nếu code vẫn vô tình giữ tham chiếu — static collection không xóa, `ThreadLocal` không `remove()`, listener/cache không gỡ — object vẫn "reachable" nên **không bao giờ được dọn** dù logic không còn dùng. Hệ quả: Heap phình dần tới `OutOfMemoryError`. Điều tra bằng heap dump (`-XX:+HeapDumpOnOutOfMemoryError`, `jmap`) rồi phân tích dominator tree bằng Eclipse MAT/VisualVM để tìm object giữ tham chiếu lớn nhất.

**Q8: Java Memory Model (JMM) là gì và khác "vùng nhớ JVM" thế nào?**
> **Đáp:** "Vùng nhớ JVM" (Heap/Stack/Metaspace...) nói về **layout bộ nhớ runtime**. JMM là **tập quy tắc** mô tả cách các **thread** thấy giá trị biến khi chạy song song, xoay quanh visibility, atomicity và quan hệ happens-before. Vì Heap dùng chung mọi thread, JMM định nghĩa khi nào một ghi của thread này được đảm bảo nhìn thấy bởi thread khác (qua `volatile`, `synchronized`, `final`...). JMM là nền tảng của lập trình concurrency đúng đắn — sẽ học sâu ở phần đa luồng.

---

## ✅ Checklist hoàn thành

- [ ] Vẽ được sơ đồ các vùng nhớ JVM và nói rõ vùng nào dùng chung / riêng từng thread
- [ ] Giải thích được "primitive ở Stack, object ở Heap, reference ở Stack trỏ tới Heap"
- [ ] Phân biệt rõ Young Gen (Eden/S0/S1) và Old Gen
- [ ] Phân biệt được `StackOverflowError` và `OutOfMemoryError` cùng cách khắc phục đúng
- [ ] Nói được Metaspace khác PermGen ở đâu
- [ ] Tự chạy demo `StackOverflowDemo` với `-Xss256k` và `-Xss4m`, quan sát khác biệt
- [ ] Giải thích được `-Xms`, `-Xmx`, `-Xss`, `-XX:MaxMetaspaceSize`
- [ ] Hoàn thành nhiệm vụ Mini Project Day 02 (phân tích Stack/Heap cho Auction)
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- JVM Specification (JSR) — chương "Run-Time Data Areas"
- Baeldung — "Stack Memory and Heap Space in Java"
- Baeldung — "Java Metaspace" / "Difference Between PermGen and Metaspace"
- Sách *Java Performance* (Scott Oaks) — chương về Heap, Generations, GC
- Oracle Docs — "Java HotSpot VM Options" (danh sách flag `-Xms/-Xmx/-Xss/-XX:...`)
- JSR-133 — "Java Memory Model" (đọc lướt, sẽ đào sâu ở phần concurrency)
