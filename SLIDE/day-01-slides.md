---
marp: true
theme: default
paginate: true
header: 'Java Roadmap 45 Days'
footer: 'Day 01 - Java Foundation & JVM'
---

# Day 01 - JDK, JRE, JVM & Bytecode

**Giai đoạn:** Java Foundation & JVM
**Thời lượng:** 3 giờ (Lý thuyết, Code, Ôn phỏng vấn)
**Dành cho:** Lập trình viên PHP/Laravel chuyển sang Java

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt rõ **JDK vs JRE vs JVM** (Câu hỏi phỏng vấn kinh điển).
- Hiểu mô hình **"biên dịch một lần, chạy mọi nơi"** (Write Once, Run Anywhere).
- Khám phá **bytecode** và sự khác biệt với mã máy (native code).
- Nắm bắt cơ chế JVM: **nạp – kiểm tra – thực thi** và sức mạnh của **JIT compiler**.
- Liên hệ tư duy giữa PHP/Laravel và Java.

---

## 🧠 Bức tranh tổng thể: Từ `.java` đến lúc chạy

```text
   Mã nguồn          Trình biên dịch        Bytecode          Máy ảo
   Hello.java  ──►   javac (trong JDK)  ──►  Hello.class  ──►  JVM  ──►  CPU/OS
  (con người đọc)                          (JVM đọc được)    (dịch & chạy)
```

- **`.java`**: Mã nguồn bạn viết.
- **`javac`**: Biên dịch `.java` thành **bytecode** (`.class`).
- **Bytecode**: Mã trung gian, không phải mã máy của CPU.
- **JVM**: Nạp `.class`, kiểm tra an toàn, vừa thông dịch vừa biên dịch nóng (JIT) sang mã máy.

> 💡 Nhờ "bytecode + JVM" tạo nên khẩu hiệu **Write Once, Run Anywhere**.

---

## 🏗️ Phân biệt JDK vs JRE vs JVM

Quan hệ lồng nhau: **`JDK ⊃ JRE ⊃ JVM`**

| Thành phần | Là gì | Cấu phần bên trong | Dành cho ai |
|---|---|---|---|
| **JVM** | Máy ảo thực thi bytecode | Class Loader, Heap/Stack, JIT, GC | Hệ thống lúc chạy |
| **JRE** | Môi trường để chạy app | JVM + thư viện (`java.lang`,...) | Người dùng chạy app |
| **JDK** | Bộ công cụ phát triển | JRE + `javac`, `jar`, `javadoc`... | Lập trình viên |

---

## 🧩 Cách nhớ nhanh

- Chỉ muốn **chạy** app Java → Cần **JRE**.
- Muốn **viết & biên dịch** code → Cần **JDK**.
- **JVM** luôn nằm bên trong cả hai, đóng vai trò là "trái tim" thực thi.

*(Lưu ý: Từ Java 11, Oracle không phát hành JRE riêng, thường cài JDK là đủ mọi thứ).*

---

## 🔍 Bytecode trông như thế nào?

Bytecode là tập lệnh dựa trên ngăn xếp (stack-based), độc lập hệ điều hành.

```java
// Hello.java
public class Hello {
    public static void main(String[] args) {
        System.out.println("Xin chào Java");
    }
}
```

Dùng lệnh `javap -c Hello` để xem:
```text
  Code:
     0: getstatic     #7   // Field System.out
     3: ldc           #13  // String Xin chào Java
     5: invokevirtual #15  // Method PrintStream.println
     8: return
```

---

## ⚡ JVM thực thi bytecode ra sao?

JVM kết hợp 2 cơ chế:

1. **Interpreter (Thông dịch):** Đọc và chạy từng lệnh. Nhanh lúc khởi động, nhưng chậm về lâu dài.
2. **JIT Compiler (Just-In-Time):** Theo dõi các đoạn code chạy nhiều (**hot path**) -> biên dịch sang mã máy gốc -> lưu cache -> chạy cực nhanh!

```text
Bytecode ──► Interpreter (chạy ngay, hơi chậm)
                  │
                  └─► phát hiện "hot code" ──► JIT biên dịch ──► mã máy (cache) 
```

> 💡 Ứng dụng Java thường "cần làm nóng" (warm-up) vài giây đầu trước khi đạt tốc độ đỉnh.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | PHP / Laravel | Java |
|---|---|---|
| **Mã nguồn** | `.php` | `.java` |
| **Biên dịch** | Hầu như không (Thông dịch lúc chạy) | `javac` biên dịch ra `.class` |
| **Môi trường** | PHP runtime (php-fpm) | JVM |
| **Khởi động** | Request nào sinh ra/chết đi request đó | App chạy lâu dài (Long-running process) |

---

## ⚠️ Khác biệt tư duy cốt lõi

- **PHP/Laravel:** Stateless. Biến sinh ra trong 1 request rồi bị hủy. Ít lo về bộ nhớ tích lũy.
- **Java/Spring:** Tiến trình chạy liên tục. Object lưu trên RAM. 
  👉 Bạn phải bắt đầu làm quen với: **Memory Management, Garbage Collector (GC), Thread pool, Connection pool**.
  👉 Một biến `static` sẽ **sống mãi** trong ứng dụng, có thể gây lỗi rò rỉ dữ liệu giữa các request nếu không cẩn thận.

---

## 💻 Thực hành điền khuyết

```java
// File: Hello.java
public ___ Hello { // Điền từ khóa khai báo lớp
    public ___ void main(String[] args) { // Hàm chạy độc lập
        System.out.println("Version: " + System.getProperty("java.version"));
    }
}
```

**Thao tác terminal:**
```bash
javac Hello.java     # Biên dịch
java Hello           # Chạy
javap -c Hello       # Xem bytecode
```

---

## ⛔ Bẫy thường gặp (Pitfalls)

- ❌ *Nhầm tưởng "Java dịch trực tiếp sang mã máy"*. -> Sự thật: Dịch ra bytecode trước, JIT lo phần còn lại.
- ❌ *Tưởng JVM = JRE = JDK*. -> Sự thật: Chúng lồng nhau.
- ❌ *Cài nhầm version*. -> Chú ý: JVM chỉ chạy được bytecode phiên bản **nhỏ hơn hoặc bằng** version của nó.
- ❌ *Đo hiệu năng app Java lúc vừa khởi động*. -> Sự thật: Cần đợi JIT "warm-up" mới đo được tốc độ thực tế.

---

## 🏗️ Mini Project — Auction API

**Nhiệm vụ Day 01 (Xây nền móng):**

1. Điền các chỗ trống `___` trong code thực hành ở trên.
2. Cài đặt JDK 21 và kiểm tra `java -version`.
3. Viết class `AuctionApp` có hàm `main` in ra phiên bản JVM.
4. Biên dịch và chạy thủ công, soi bytecode bằng `javap`.
5. Tự viết lại khái niệm phân biệt JDK/JRE/JVM bằng lời của mình vào file note.

---

## ❓ Q&A Phỏng vấn kinh điển

- **Q: JDK, JRE, JVM khác nhau thế nào?**
  A: JVM là trái tim thực thi. JRE = JVM + thư viện (để chạy). JDK = JRE + Tools (để code).
- **Q: Vì sao copy file `.class` chạy được mọi nơi?**
  A: Nhờ bytecode là mã chung độc lập hệ điều hành, được dịch bởi JVM tương thích trên từng máy.
- **Q: JIT Compiler giải quyết vấn đề gì?**
  A: Khắc phục nhược điểm chạy chậm của thông dịch bằng cách dịch "hot code" ra thẳng mã máy và cache lại.

---

# Cảm ơn bạn đã tham gia Day 01!
> Chúc bạn code vui vẻ và hẹn gặp lại ở Day 02: Java Core Types & OOP.
