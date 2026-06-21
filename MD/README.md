# Lộ trình Java Deep Dive 45 ngày

Bộ tài liệu gồm 45 file markdown, mỗi file tương ứng một ngày học, đi từ nền tảng Java/JVM đến hệ thống backend doanh nghiệp với Spring Boot.

> **Dành cho:** Lập trình viên đã có nền (đặc biệt từ Laravel/PHP) muốn hiểu Java tới tận gốc. Mỗi ngày đều có phần **đối chiếu với Laravel/PHP** để học nhanh nhờ liên hệ cái đã biết.

## ⏱️ Lịch học gợi ý

- **3 giờ/ngày**
  - 1 giờ lý thuyết
  - 1 giờ code thực hành
  - 1 giờ ghi chú + ôn câu hỏi phỏng vấn

## 🎯 Mục tiêu sau 45 ngày

- Hiểu sâu Java core & **JVM** (memory model, garbage collection, bytecode)
- Nắm chắc **Collections** & Generics tới mức internals
- Làm chủ **Concurrency** (thread, lock, executor, concurrent collections)
- Hiểu **Spring internals** (IoC, DI, AOP, proxy, auto-configuration)
- Thành thạo **JPA/Hibernate** (persistence context, N+1, transaction)
- Tích hợp **Redis, Kafka, Docker**
- Vận hành **Testing, Monitoring, Deployment** mức production
- Hoàn thành một dự án xuyên suốt: **Auction API (hệ thống đấu giá)**

## 🗺️ Các giai đoạn

| Ngày | Giai đoạn | Nội dung chính |
|---|---|---|
| 01–05 | Java Foundation & JVM | JDK/JRE/JVM, Memory Model, Object Lifecycle, GC, String Pool |
| 06–07 | OOP & Core Java | OOP chuyên sâu, Interface vs Abstract |
| 08–13 | Collections & Generics | Collections, ArrayList/LinkedList/HashMap internals, Generics, Exception |
| 14–15 | Reflection & Annotations | Reflection, Annotation + mini project |
| 16–25 | Concurrency | Thread, Synchronization, Race condition, Deadlock, volatile, Executor, CompletableFuture, Concurrent Collections, **Auction Engine** |
| 26–35 | Spring Internals | IoC, DI, Bean lifecycle, AOP, Proxy, Context, Auto-config, Startup, MVC, Security |
| 36–38 | JPA & Hibernate | JPA internals, Persistence Context, Transactions |
| 39–41 | Infrastructure | Redis caching, Kafka, Docker |
| 42–44 | Production | Testing, Monitoring, Deployment |
| 45 | Capstone | Final Review Project — ráp toàn bộ Auction API |

## 🏗️ Dự án xuyên suốt: Auction API

Mỗi ngày đều có một nhiệm vụ nhỏ trong mục **Mini Project**, cùng nhau dựng dần một hệ thống đấu giá (Auction API) hoàn chỉnh — từ thiết kế class, engine đấu giá đa luồng, đến REST API + JPA + Redis + Kafka + Docker + test + deploy. Day 45 là buổi tổng kết ráp tất cả lại.

## 📖 Cách dùng mỗi file ngày học

Mỗi `day-NN.md` có cấu trúc thống nhất:

1. 🎯 Mục tiêu ngày hôm nay
2. 🧠 Lý thuyết cốt lõi (có sơ đồ, bảng, ghi chú)
3. 🔁 Đối chiếu với Laravel/PHP
4. 💻 Thực hành code (Java 21, có giải thích)
5. ⚠️ Bẫy thường gặp
6. 🚀 Liên hệ Spring Boot / Production
7. 🏗️ Mini Project — Auction API
8. ❓ Câu hỏi phỏng vấn (có đáp án, mức Junior/Mid và Senior)
9. ✅ Checklist hoàn thành
10. 📚 Tài liệu tham khảo

> 💡 **Mẹo:** Mỗi ngày hãy tạo một git commit khi hoàn thành checklist — vừa luyện thói quen, vừa theo dõi tiến độ học.
