# Day 01 - JDK, JRE, JVM & Bytecode

> **Giai đoạn:** Java Foundation & JVM
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Java tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt rõ **JDK vs JRE vs JVM** — đây là câu hỏi phỏng vấn kinh điển.
- Hiểu Java chạy theo mô hình **"biên dịch một lần, chạy mọi nơi"** (Write Once, Run Anywhere) như thế nào.
- Hiểu **bytecode** là gì, vì sao nó tồn tại, và nó khác mã máy (native code) ra sao.
- Biết JVM **nạp – kiểm tra – thực thi** bytecode, và **JIT compiler** giúp Java nhanh thế nào.
- Liên hệ với cách PHP/Laravel chạy để bạn nắm nhanh nhờ cái đã biết.

---

## 🧠 Lý thuyết cốt lõi

### 1. Bức tranh tổng thể: từ `.java` đến lúc chạy

```
   Mã nguồn          Trình biên dịch        Bytecode          Máy ảo
   Hello.java  ──►   javac (trong JDK)  ──►  Hello.class  ──►  JVM  ──►  CPU/OS
  (con người đọc)                          (JVM đọc được)    (dịch & chạy)
```

- Bạn viết file `.java`.
- `javac` (Java Compiler, nằm trong JDK) biên dịch nó thành **bytecode** — file `.class`.
- Bytecode **không phải** mã máy của CPU. Nó là "mã trung gian" mà **JVM** hiểu được.
- JVM nạp file `.class`, kiểm tra an toàn, rồi **thực thi** — vừa thông dịch (interpret) vừa biên dịch nóng (JIT) sang mã máy.

> 💡 Chính lớp trung gian "bytecode + JVM" này tạo nên khẩu hiệu **Write Once, Run Anywhere**: cùng một file `.class` chạy được trên Windows, Linux, macOS, miễn là máy đó có JVM phù hợp.

### 2. JDK vs JRE vs JVM — phân biệt cho chuẩn

Hãy nhớ theo quan hệ **lồng nhau**: `JDK ⊃ JRE ⊃ JVM`.

| Thành phần | Là gì | Bên trong có | Ai dùng |
|---|---|---|---|
| **JVM** (Java Virtual Machine) | Máy ảo *thực thi* bytecode | Class Loader, vùng nhớ (Heap/Stack...), Execution Engine (Interpreter + JIT), Garbage Collector | Lúc **chạy** chương trình |
| **JRE** (Java Runtime Environment) | Môi trường để **chạy** app Java | JVM **+** thư viện chuẩn (`java.lang`, `java.util`...) + file cấu hình | Người chỉ cần **chạy** app |
| **JDK** (Java Development Kit) | Bộ công cụ để **phát triển** | JRE **+** `javac` (compiler), `jar`, `javadoc`, `jdb` (debugger), `jshell`... | **Lập trình viên** |

**Cách nhớ nhanh:**
- Muốn **chạy** app Java người khác viết → cần **JRE**.
- Muốn **viết & biên dịch** code Java → cần **JDK**.
- **JVM** là "trái tim" thực thi, luôn nằm bên trong cả hai.

> ⚠️ Từ Java 11 trở đi, Oracle **không phát hành JRE riêng** nữa. Bạn thường cài thẳng JDK (OpenJDK / Temurin / Amazon Corretto) là có đủ mọi thứ.

### 3. Bytecode trông như thế nào?

Bytecode là tập **lệnh dạng stack-based** (dựa trên ngăn xếp). Bạn không cần viết tay nó, nhưng nên biết nó tồn tại. Với file `Hello.java`:

```java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Xin chào Java");
    }
}
```

Dùng lệnh `javap -c Hello` để xem bytecode đã biên dịch, bạn sẽ thấy đại loại:

```
public static void main(java.lang.String[]);
  Code:
     0: getstatic     #7   // Field java/lang/System.out:Ljava/io/PrintStream;
     3: ldc           #13  // String Xin chào Java
     5: invokevirtual #15  // Method java/io/PrintStream.println:(Ljava/lang/String;)V
     8: return
```

- `getstatic`, `ldc`, `invokevirtual`... là các **opcode** (mã lệnh) của JVM.
- Đây là thứ **độc lập với hệ điều hành**: cùng bytecode này chạy y nguyên trên mọi nền tảng.

### 4. JVM thực thi bytecode ra sao? (Interpreter + JIT)

JVM dùng kết hợp hai cơ chế:

1. **Interpreter (thông dịch):** đọc và thực thi bytecode từng lệnh một. Khởi động nhanh nhưng chạy chậm vì lặp lại liên tục.
2. **JIT Compiler (Just-In-Time):** JVM theo dõi xem đoạn code nào chạy nhiều ("hot path"). Với đoạn nóng đó, nó **biên dịch sang mã máy gốc** một lần rồi cache lại → chạy nhanh gần bằng C/C++.

```
Bytecode ──► Interpreter (chạy ngay, hơi chậm)
                  │
                  └─► phát hiện "hot code" ──► JIT biên dịch ──► mã máy (cache) ──► chạy rất nhanh
```

> 💡 Đây là lý do một service Java thường "chậm vài giây đầu rồi nhanh dần": JIT cần thời gian "làm nóng" (warm-up). Khái niệm này cực quan trọng khi đo hiệu năng — đừng đo lúc app vừa khởi động.

---

## 🔁 Đối chiếu với Laravel/PHP

Bạn quen PHP nên hãy neo vào đó:

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| Mã nguồn | `.php` | `.java` |
| Bước biên dịch | Hầu như không (PHP thông dịch lúc chạy) | `javac` biên dịch trước thành `.class` |
| "Bytecode" | Có, nhưng ẩn — **OPcache** lưu opcode của PHP | `.class` chứa bytecode, là sản phẩm rõ ràng |
| Môi trường chạy | PHP runtime (php-fpm, Zend Engine) | JVM |
| "Run anywhere" | Cần PHP cùng version + extension trên server | Cần JVM tương thích; bytecode bất biến |
| Khởi động | Mỗi request thường khởi tạo lại (stateless) | App chạy **lâu dài** (long-running process), giữ state trong RAM |

**Khác biệt tư duy quan trọng nhất:**
- PHP/Laravel: mỗi HTTP request thường "sinh ra rồi chết" — framework bootstrap lại từ đầu. Vì vậy bạn ít quan tâm tới bộ nhớ tích lũy.
- Java/Spring Boot: tiến trình **chạy liên tục hàng ngày/tháng**. JVM giữ object trong Heap, có Garbage Collector dọn rác. Vì vậy bạn sẽ phải quan tâm tới **memory, GC, thread, connection pool** — những thứ PHP "giấu" giúp bạn.

> 🧩 Hệ quả: ở Java, một biến `static` hay một Singleton bean **sống suốt vòng đời ứng dụng**, không reset mỗi request như Laravel. Ghi nhớ điều này để tránh bug "dữ liệu request này lẫn sang request khác".

---

## 💻 Thực hành code

### Bước 1 — Viết, biên dịch, chạy thủ công (hiểu cơ chế)

```java
// File: Hello.java
public ___ Hello { // Điền từ khóa khai báo lớp
    public ___ void main(String[] args) { // Điền từ khóa tĩnh để chạy hàm main không cần khởi tạo đối tượng
        System.out.println("Java version đang chạy: " + System.getProperty("java.version"));
        System.out.println("Tên JVM: " + System.getProperty("java.vm.name"));
    }
}
```

Chạy trên terminal:

```bash
javac Hello.java     # JDK biên dịch -> sinh ra Hello.class (bytecode)
ls                   # thấy Hello.java và Hello.class
java Hello           # JVM nạp & chạy Hello.class
```

### Bước 2 — Soi bytecode

```bash
javap -c Hello       # xem bytecode dạng đọc được
javap -p -v Hello    # xem chi tiết: constant pool, version, flags...
```

Hãy đọc và **tự giải thích** vài dòng opcode đầu tiên — bạn không cần thuộc, chỉ cần "à, hóa ra nó là thế này".

### Bước 3 — Chứng minh "Write Once, Run Anywhere"

Copy đúng file `Hello.class` (không phải `.java`) sang một máy/OS khác có cài JVM rồi chạy `java Hello`. Nó chạy y nguyên — vì bytecode độc lập nền tảng.

### Bước 4 — Kiểm tra version đang dùng

```bash
java -version     # version JRE/JVM đang chạy
javac -version    # version compiler trong JDK
```

> ✅ **Bài tập tự giải thích:** Vì sao copy `Hello.class` sang máy khác chạy được, nhưng copy file thực thi `.exe` của C++ từ Windows sang Linux thì không?

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Nhầm "Java biên dịch sang mã máy".** Sai — `javac` chỉ ra **bytecode**, JVM mới dịch tiếp sang mã máy (qua JIT).
- **Tưởng JVM == JRE == JDK.** Chúng lồng nhau, không bằng nhau.
- **Cài nhầm version.** Code biên dịch bằng JDK 21 nhưng chạy trên JVM 17 → lỗi `UnsupportedClassVersionError`. Phải nhớ: **JVM chạy được bytecode bằng hoặc cũ hơn version của nó**, không chạy được bytecode mới hơn.
- **Đo hiệu năng lúc app vừa khởi động.** JIT chưa "warm-up" → số liệu sai. Luôn cho app chạy ấm rồi mới đo (benchmark dùng JMH).
- **Để nhiều JDK trên máy mà không quản lý.** Dùng `jenv` hoặc `sdkman` để chuyển version cho gọn (giống cách bạn dùng `phpbrew` cho PHP).

---

## 🚀 Liên hệ Spring Boot / Production

- App **Spring Boot** đóng gói thành **fat JAR** (`app.jar`) chứa bytecode + thư viện + Tomcat nhúng. Chạy đơn giản: `java -jar app.jar`. Đây chính là JVM nạp bytecode của bạn.
- Trong **Docker**, image thường dựa trên một bản JRE/JDK gọn (ví dụ `eclipse-temurin:21-jre`). Hiểu JRE vs JDK giúp bạn chọn base image nhỏ, nhẹ, bảo mật hơn (đừng đóng cả JDK vào image production nếu chỉ cần chạy).
- Tinh chỉnh JVM lúc chạy bằng flag: `java -Xms512m -Xmx512m -jar app.jar` (đặt vùng Heap). Những ngày sau ta sẽ đào sâu.
- **Hot path + JIT** giải thích vì sao service Java cần "warm-up" sau khi deploy mới đạt hiệu năng đỉnh — liên quan trực tiếp tới chiến lược rollout, health check, readiness probe.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Xuyên suốt 45 ngày, ta sẽ dựng dần một **Auction API**. Hôm nay là ngày "dựng móng".

**Nhiệm vụ Day 01:**
1. Cài JDK 21 (Temurin/Corretto) và xác nhận `java -version`, `javac -version` cùng major version.
2. Tạo class `AuctionApp` có `main` in ra dòng: `"Auction API khởi động trên JVM <tên> <version>"` (đọc qua `System.getProperty`).
3. Biên dịch thủ công bằng `javac`, chạy bằng `java`, rồi dùng `javap -c` soi bytecode method `main`.
4. Ghi vào file `notes/day-01.md`: 3 câu trả lời cho "JDK/JRE/JVM khác nhau ở đâu" bằng lời của chính bạn.
5. Điền các chỗ trống `___` trong code thực hành ở trên.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: JDK, JRE, JVM khác nhau thế nào?**
> **Đáp:** JVM là máy ảo thực thi bytecode (có class loader, vùng nhớ, execution engine, GC). JRE = JVM + thư viện chuẩn, đủ để **chạy** app. JDK = JRE + công cụ phát triển (`javac`, `jar`, `javadoc`...), đủ để **viết & biên dịch**. Quan hệ lồng nhau: JDK ⊃ JRE ⊃ JVM.

**Q2: "Write Once, Run Anywhere" hoạt động nhờ đâu?**
> **Đáp:** Nhờ lớp trung gian bytecode + JVM. `javac` biên dịch ra bytecode độc lập nền tảng; mỗi OS có một JVM riêng biết cách dịch bytecode đó sang mã máy của nó. Nên cùng một `.class` chạy được mọi nơi có JVM tương thích.

**Q3: Bytecode khác mã máy (native code) thế nào?**
> **Đáp:** Mã máy phụ thuộc kiến trúc CPU/OS cụ thể (x86, ARM...). Bytecode là tập lệnh ảo của JVM, không gắn với CPU nào; JVM mới là cầu nối dịch bytecode → mã máy lúc chạy.

### Mức Senior

**Q4: JIT compiler là gì và vì sao Java vẫn nhanh dù chạy trên máy ảo?**
> **Đáp:** JIT (Just-In-Time) là bộ biên dịch nóng trong JVM. Ban đầu code chạy bằng interpreter (chậm). JVM đo đếm tần suất thực thi; với "hot path" nó biên dịch bytecode sang mã máy gốc, tối ưu (inlining, loop unrolling, escape analysis...) rồi cache lại. Nhờ vậy đoạn code chạy nhiều đạt tốc độ gần native. HotSpot JVM còn có 2 tầng (C1/C2) để cân bằng warm-up nhanh và tối ưu sâu.

**Q5: `UnsupportedClassVersionError` xảy ra khi nào và xử lý ra sao?**
> **Đáp:** Khi JVM cố nạp bytecode được biên dịch bằng JDK **mới hơn** version của chính nó (mỗi version có "class file major version" riêng). JVM chỉ chạy được bytecode bằng hoặc cũ hơn. Cách xử lý: chạy bằng JDK đủ mới, hoặc biên dịch lại với `--release <n>` để nhắm version đích cũ hơn.

**Q6: Vì sao benchmark một service Java ngay sau khi khởi động lại cho số liệu sai?**
> **Đáp:** Vì JIT chưa warm-up: code còn đang chạy bằng interpreter, class chưa nạp/khởi tạo hết, các tối ưu chưa kích hoạt, GC chưa ổn định. Phải để app chạy ấm dưới tải thực rồi mới đo (và dùng công cụ như JMH để loại nhiễu).

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được JDK/JRE/JVM bằng lời của mình (không nhìn tài liệu)
- [ ] Tự tay `javac` + `java` + `javap` cho một class
- [ ] Hiểu bytecode là gì và vì sao nó độc lập nền tảng
- [ ] Hiểu vai trò Interpreter vs JIT, khái niệm warm-up
- [ ] Hoàn thành nhiệm vụ Mini Project Day 01
- [ ] Trả lời được 6 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "About the Java Technology"
- Baeldung — "Difference Between JVM, JRE, and JDK"
- Sách *Java Performance* (Scott Oaks) — chương về JIT/HotSpot (đọc lướt phần đầu)
- `man javap`, `man javac` — đọc qua các flag chính
- OpenJDK Wiki — HotSpot Runtime Overview (tham khảo khi cần đào sâu)
