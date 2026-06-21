# 🚀 Java Deep Dive 45 Ngày & Auction API (Hệ thống Đấu giá)

Chào mừng bạn đến với lộ trình học **Java chuyên sâu trong 45 ngày** kết hợp xây dựng dự án **Auction API** (Hệ thống Đấu giá trực tuyến). Bộ tài liệu này được thiết kế đặc biệt dành cho các lập trình viên đã có nền tảng (đặc biệt hữu ích cho những ai chuyển từ Laravel/PHP sang) muốn chinh phục Java/JVM từ gốc rễ đến mức vận hành thực tế (Production).

---

## 🎯 Điểm Đặc Biệt Của Lộ Trình
* 🔍 **Hiểu Sâu Bản Chất**: Không chỉ học cú pháp, lộ trình đi thẳng vào cơ chế hoạt động của JVM, Memory Model, Garbage Collection, Collections Internals, Concurrency (Đa luồng) và Spring Boot Internals.
* 🔁 **Đối Chiếu Laravel/PHP**: Mỗi ngày học đều có phần đối chiếu trực tiếp với các khái niệm tương đương trong PHP/Laravel giúp bạn liên hệ và nắm bắt cực nhanh.
* 🏗️ **Học Đi Đôi Với Hành (Auction API)**: Xây dựng một dự án thực tế xuyên suốt qua từng ngày học. Từ thiết kế Class đơn giản, Engine đấu giá đa luồng, cho đến REST API hoàn chỉnh tích hợp JPA, Redis Caching, Kafka, Docker, Test và Deploy.
* 💼 **Sẵn Sàng Phỏng Vấn**: Cung cấp bộ câu hỏi phỏng vấn thực tế từ mức Junior/Mid đến Senior cho từng chủ đề hàng ngày.

---

## 🗺️ Bản Đồ Lộ Trình 45 Ngày

| Tuần / Giai Đoạn | Chủ Đề Chính | Nội Dung Chi Tiết |
| :--- | :--- | :--- |
| **Giai đoạn 1 (Day 01–05)** | **Java Foundation & JVM** | JDK/JRE/JVM, Memory Model, Object Lifecycle, Garbage Collection (GC), String Pool |
| **Giai đoạn 2 (Day 06–07)** | **OOP & Core Java** | OOP chuyên sâu, So sánh Interface vs Abstract Class, Cơ chế binding |
| **Giai đoạn 3 (Day 08–13)** | **Collections & Generics** | ArrayList, LinkedList, HashMap internals, Generics, Exception Handling |
| **Giai đoạn 4 (Day 14–15)** | **Reflection & Annotations** | Meta-programming với Reflection, Custom Annotation & mini framework |
| **Giai đoạn 5 (Day 16–25)** | **Concurrency (Đa luồng)** | Thread, Synchronization, Race condition, Deadlock, Volatile, Executor Service, CompletableFuture, Concurrent Collections, **Auction Engine** |
| **Giai đoạn 6 (Day 26–35)** | **Spring Internals** | IoC & DI Container, Bean lifecycle, AOP & Dynamic Proxy, Auto-configuration, Spring MVC, Spring Security |
| **Giai đoạn 7 (Day 36–38)** | **JPA & Hibernate** | JPA internals, Persistence Context, N+1 Problem, Transaction Management |
| **Giai đoạn 8 (Day 39–41)** | **Infrastructure & Message Broker** | Tích hợp Redis Caching, Apache Kafka event-driven, Containerization với Docker |
| **Giai đoạn 9 (Day 42–44)** | **Production Readiness** | Unit Testing/Integration Testing (JUnit 5, Mockito), Monitoring (Actuator, Prometheus, Grafana), Deployment CI/CD |
| **Giai đoạn 10 (Day 45)** | **Capstone Project** | Hoàn thành và lắp ghép toàn bộ hệ thống **Auction API** |

---

## 🏗️ Dự Án Xuyên Suốt: Auction API

Hệ thống Đấu giá trực tuyến (Auction API) được phát triển tăng dần độ phức tạp qua từng ngày học:
- **Core Engine (Day 20–25)**: Thiết kế luồng xử lý đấu giá đồng thời (Concurrent bidding) chịu tải cao, giải quyết các bài toán về Race Condition khi nhiều người cùng đặt giá tại một thời điểm bằng Locks, Atomic variables và Executor Service.
- **Backend API (Day 26–38)**: Đưa Engine vào khung Spring Boot, thiết kế RESTful API, quản lý dữ liệu với JPA/Hibernate, tối ưu hóa truy vấn và giao dịch (Transactions).
- **Scalability & Event-Driven (Day 39–41)**: Tích hợp Redis để làm Cache cho danh sách đấu giá sôi động và Apache Kafka để xử lý bất đồng bộ các sự kiện khi phiên đấu giá kết thúc (thanh toán, gửi mail thông báo thắng cuộc).

---

## 📁 Cấu Trúc Thư Mục
```text
java-roadmap-45-days/
├── README.md               # Tài liệu hướng dẫn tổng quan (File này)
├── MD/                     # Chứa 45 tài liệu học tập chi tiết hàng ngày
│   ├── day-01.md
│   ├── day-02.md
│   └── ...
└── SLIDE/                  # Slide bài giảng hỗ trợ học tập
    └── day-01-slides.md
```

---

## 📖 Cách Sử Dụng Tài Liệu

Mỗi ngày học trong thư mục [MD/](file:///Users/ninhndt/Downloads/java-roadmap-45-days/MD) được thiết kế theo cấu trúc chuẩn hóa:
1. 🎯 **Mục tiêu ngày hôm nay**: Định hướng kiến thức cần đạt được.
2. 🧠 **Lý thuyết cốt lõi**: Giải thích chi tiết, kèm theo sơ đồ trực quan và bảng so sánh.
3. 🔁 **Đối chiếu với Laravel/PHP**: Dành cho các bạn dev PHP chuyển hệ dễ liên tưởng.
4. 💻 **Thực hành code**: Các đoạn mã nguồn minh họa chạy trên Java 21.
5. ⚠️ **Bẫy thường gặp**: Các lỗi phổ biến, bug kinh điển dễ mắc phải trong thực tế.
6. 🚀 **Liên hệ Spring Boot / Production**: Ứng dụng thực tế của kiến thức ngày hôm đó trong dự án thực tế.
7. 🏗️ **Mini Project (Auction API)**: Bài tập thực hành tích lũy tính năng cho hệ thống đấu giá.
8. ❓ **Câu hỏi phỏng vấn**: Các câu hỏi kèm đáp án gợi ý phục vụ ôn luyện phỏng vấn.
9. ✅ **Checklist hoàn thành**: Đánh giá mức độ tiếp thu bài học.

*💡 **Mẹo nhỏ**: Hãy tạo một git commit sau mỗi ngày học hoàn thành để lưu lại hành trình học tập và kiểm soát tiến độ tốt hơn!*

---

Chúc bạn có một hành trình học tập Java thật bùng nổ và sớm làm chủ ngôn ngữ mạnh mẽ này! 🚀
