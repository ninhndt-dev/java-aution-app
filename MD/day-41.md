# Day 41 - Docker (Đóng gói & chạy Spring Boot trong container)

> **Giai đoạn:** Infrastructure & Integration
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP muốn hiểu Docker hoá Spring Boot tới tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Phân biệt **image vs container vs layer** — nền tảng để hiểu mọi thứ về Docker.
- Viết được **Dockerfile multi-stage** cho Spring Boot (build bằng JDK+Maven → runtime bằng JRE slim) và tận dụng **layered jar** để cache layer.
- Hiểu chọn **base image** (`eclipse-temurin:21-jre`) và **JVM trong container** (cgroup memory, `-XX:MaxRAMPercentage`, `UseContainerSupport`).
- Biết dùng **`.dockerignore`** và vì sao nó quan trọng.
- Viết **docker-compose** dựng cả stack: app + Postgres + Redis + Kafka, có **healthcheck**.
- Đối chiếu với Dockerfile php-fpm + nginx và Laravel Sail.

---

## 🧠 Lý thuyết cốt lõi

### 1. Image vs Container vs Layer

Ba khái niệm nền tảng, đừng nhầm lẫn:

| Khái niệm | Là gì | Ví von |
|---|---|---|
| **Image** | "Khuôn" bất biến: hệ thống file + metadata để chạy app. Chỉ đọc. | Như một **class** |
| **Container** | Một **lần chạy** của image — tiến trình cô lập (namespace, cgroup). | Như một **object** (instance của class) |
| **Layer** | Image được xếp từ nhiều **lớp** chồng lên nhau; mỗi lệnh Dockerfile tạo một layer. Layer được **cache & chia sẻ**. | Như các "commit" chồng lên nhau |

```
Image = chồng các layer (read-only):
  ┌──────────────────────────┐
  │ layer 4: COPY app.jar     │  ◄── đổi thường xuyên (code của bạn)
  │ layer 3: COPY dependencies │  ◄── đổi hiếm (thư viện)
  │ layer 2: cài JRE           │
  │ layer 1: base OS (debian)  │  ◄── gần như không đổi
  └──────────────────────────┘
Container = image + 1 lớp ghi (read-write) ở trên cùng khi chạy
```

> 💡 **Tại sao layer quan trọng?** Docker cache theo layer. Nếu bạn sắp xếp Dockerfile để **layer ít thay đổi nằm dưới** (dependencies) và **layer hay đổi nằm trên** (code), thì mỗi lần build chỉ phải làm lại layer trên → build **nhanh hơn nhiều**. Đây là lý do dùng layered jar (mục 3).

### 2. Multi-stage build: vì sao cần

Build Spring Boot cần **JDK + Maven** (nặng, ~500MB+). Nhưng **chạy** chỉ cần **JRE** (nhẹ hơn nhiều). Nếu đóng cả JDK+Maven vào image production → image phình to, nhiều công cụ thừa = bề mặt tấn công lớn.

**Multi-stage build**: dùng nhiều `FROM` trong một Dockerfile. Stage đầu (`builder`) build ra jar; stage cuối **chỉ copy jar** sang một base JRE gọn. Mọi thứ của stage build (JDK, Maven cache, source) **không** lọt vào image cuối.

```
Stage 1 (builder): JDK 21 + Maven  ──► mvn package ──► app.jar
                                              │ (chỉ copy jar)
Stage 2 (runtime): JRE 21 slim  ◄────────────┘  ──► image production NHỎ
```

### 3. Layered jar — cache layer thông minh cho Spring Boot

Spring Boot fat jar gộp **dependencies + code của bạn** vào một file. Vấn đề: code đổi mỗi commit nhưng dependencies hiếm đổi — nếu copy nguyên jar thành **một layer**, mỗi lần đổi code phải tải lại cả dependencies.

Spring Boot hỗ trợ **layered jar**: tách jar thành các lớp `dependencies`, `spring-boot-loader`, `snapshot-dependencies`, `application`. Copy từng lớp thành layer Docker riêng → đổi code chỉ rebuild layer `application` (nhỏ), giữ nguyên layer `dependencies` (lớn) trong cache.

```bash
# Trích các layer từ jar (Spring Boot 2.4+):
java -Djarmode=layertools -jar app.jar extract
# -> sinh ra các thư mục: dependencies/ spring-boot-loader/ snapshot-dependencies/ application/
```

### 4. Base image: chọn cái nào

| Base image | Đặc điểm |
|---|---|
| `eclipse-temurin:21-jdk` | Có compiler — dùng cho **stage build** |
| `eclipse-temurin:21-jre` | Chỉ runtime — **nhỏ hơn**, dùng cho **stage runtime** |
| `eclipse-temurin:21-jre-alpine` | Rất nhỏ (musl libc) — nhẹ nhưng đôi khi vướng thư viện native |
| `gcr.io/distroless/java21` | Không có shell/package manager — **bảo mật nhất**, khó debug hơn |

> 💡 Khuyến nghị thực tế: build bằng `21-jdk`, chạy bằng `21-jre` (hoặc distroless nếu siết bảo mật). Nhớ Day 01: **JRE đủ để chạy, JDK để build** — đây là lúc kiến thức đó trả về giá trị thực tế.

### 5. JVM trong container — cái bẫy kinh điển

Ngày xưa JVM **không** nhìn thấy giới hạn bộ nhớ của container (cgroup); nó đọc RAM của **cả máy host** → đặt heap quá lớn → container bị **OOM-killed**. Từ **JDK 10+** (và đặc biệt 11/17/21), JVM **tự nhận diện cgroup**:

- **`UseContainerSupport`**: **bật mặc định** — JVM đọc giới hạn bộ nhớ/CPU của container, không phải host.
- **`-XX:MaxRAMPercentage`**: đặt heap theo **phần trăm RAM container** (vd `75.0`), thay cho `-Xmx` cố định. Linh hoạt hơn khi đổi `--memory`.

```bash
# Đặt heap = 75% RAM mà container được cấp (vd --memory=512m -> heap ~384m):
java -XX:MaxRAMPercentage=75.0 -jar app.jar
```

> ⚠️ Vẫn phải **đặt `--memory` cho container** (compose: `mem_limit` / k8s: `resources.limits.memory`). JVM cần biết trần để tính. Không giới hạn → JVM lại tưởng có cả RAM host.

> 💡 Để dành ~25% RAM ngoài heap cho metaspace, thread stack, buffer của netty/Lettuce, code cache... Đừng đặt `MaxRAMPercentage=100`.

### 6. `.dockerignore`

Giống `.gitignore` nhưng cho Docker build context. Loại trừ `target/`, `.git/`, `node_modules/`, file IDE... khỏi context gửi tới Docker daemon. Lợi ích: build **nhanh hơn** (context nhỏ), image **sạch hơn**, **không lọt secret** (`.env`).

### 7. docker-compose & healthcheck

`docker-compose.yml` mô tả **nhiều container liên kết** (app + DB + Redis + Kafka) chạy cùng nhau, chung mạng nội bộ (gọi nhau bằng **tên service**, vd `jdbc:postgresql://postgres:5432/...`).

**Healthcheck** cho mỗi service để compose biết khi nào "thực sự sẵn sàng". Dùng `depends_on: condition: service_healthy` để app chỉ khởi động **sau khi** Postgres/Kafka đã healthy → tránh lỗi "connection refused" lúc boot.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel/PHP | Spring Boot |
|---|---|---|
| Runtime | php-fpm + nginx (2 process/container) | JVM chạy fat jar (1 process) |
| Đóng gói | Dockerfile cài PHP, ext, copy source, `composer install` | Multi-stage: build jar rồi copy sang JRE |
| Web server | Cần nginx/apache đứng trước php-fpm | **Tomcat nhúng** trong jar — không cần web server riêng |
| Stack dev | **Laravel Sail** (`sail up`) | `docker-compose up` |
| Biến môi trường | `.env` (đọc lúc runtime) | env vars / `application.yml` (Spring đọc env) |
| Quản lý dependency | `composer install` trong build | `mvn package` / layered jar |
| Migration | `php artisan migrate` (entrypoint) | Flyway/Liquibase tự chạy lúc app khởi động |

**Khác biệt tư duy:**

```dockerfile
# Laravel: 1 container thường cần PHP + nhiều extension + (nginx hoặc supervisor)
FROM php:8.3-fpm
RUN docker-php-ext-install pdo_mysql ...   # cài từng extension
COPY . /var/www && composer install
```
```dockerfile
# Spring Boot: 1 jar tự chứa Tomcat -> image gọn, 1 process, chỉ cần JRE
FROM eclipse-temurin:21-jre
COPY app.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
```

> 💡 Người Laravel hay quen "container = php-fpm, còn nginx là container khác". Spring Boot **không cần** web server tách rời — Tomcat nằm sẵn trong jar. Một container = một process Java chạy thẳng. Đơn giản hơn nhiều.

---

## 💻 Thực hành code

### Bước 1 — `.dockerignore`

```dockerignore
# File: .dockerignore
target/
.git/
.gitignore
.idea/
*.iml
___ # Điền tên file chứa biến môi trường KHÔNG BAO GIỜ được cho vào Docker Image
*.md
docker-compose.yml
```

### Bước 2 — Dockerfile multi-stage + layered jar

```dockerfile
# File: Dockerfile

# ---------- STAGE 1: BUILD (JDK + Maven) ----------
# Điền từ khóa để đánh dấu stage này tên là "builder"
FROM eclipse-temurin:21-jdk ___ builder
WORKDIR /build

# Copy file build trước, tải dependency -> tận dụng cache layer
# (dependency hiếm đổi -> layer này hiếm rebuild)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B

# Giờ mới copy source (đổi thường xuyên) rồi build
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Trích layered jar thành các thư mục layer
RUN java -Djarmode=layertools -jar target/*.jar extract --destination extracted

# ---------- STAGE 2: RUNTIME (chỉ JRE) ----------
# Điền từ khóa để đánh dấu stage này tên là "runtime"
FROM eclipse-temurin:21-jre ___ runtime
WORKDIR /app

# Chạy bằng user thường (không root) -> an toàn hơn
RUN groupadd -r app && useradd -r -g app app
USER app

# Copy TỪNG layer theo thứ tự ÍT đổi -> NHIỀU đổi (tối ưu cache)
# Điền cờ tham số copy từ stage builder sang stage runtime
COPY ___=builder /build/extracted/dependencies/ ./
COPY ___=builder /build/extracted/spring-boot-loader/ ./
COPY ___=builder /build/extracted/snapshot-dependencies/ ./
COPY ___=builder /build/extracted/application/ ./

EXPOSE 8080

# UseContainerSupport bật mặc định; đặt heap = 75% RAM container
___ ["java", "-XX:MaxRAMPercentage=75.0", \
            "org.springframework.boot.loader.launch.JarLauncher"]  # Điền lệnh khai báo điểm khởi chạy của Image
```

> 💡 `JarLauncher` là entrypoint của layered jar đã extract (Spring Boot 3.2+ nằm ở `org.springframework.boot.loader.launch.JarLauncher`). Nếu không dùng layered, chỉ cần `ENTRYPOINT ["java","-jar","app.jar"]`.

### Bước 3 — docker-compose: app + Postgres + Redis + Kafka

```yaml
# File: docker-compose.yml
services:

  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: auction
      POSTGRES_USER: auction
      POSTGRES_PASSWORD: secret
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U auction"]
      interval: 5s
      timeout: 3s
      retries: 5

  # ... (redis và kafka tương tự bài mẫu) ...

  app:
    build: .
    ports: ["8080:8080"]
    mem_limit: 512m            # ⭐ JVM tính heap theo trần này (MaxRAMPercentage)
    environment:
      # Gọi nhau bằng TÊN SERVICE trong mạng compose, không phải localhost
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/auction
      SPRING_DATASOURCE_USERNAME: auction
      SPRING_DATASOURCE_PASSWORD: secret
      SPRING_DATA_REDIS_HOST: redis
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ___:                       # Điền thuộc tính để quy định thứ tự khởi động
      postgres: { condition: ___ }   # Điền trạng thái chờ DB thực sự sẵn sàng
      redis:    { condition: ___ }
      kafka:    { condition: ___ }
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 10s
      timeout: 5s
      retries: 5
```

### Bước 4 — Build & chạy

```bash
docker build -t auction-api:latest .     # build image (multi-stage)
docker compose ___ -d                    # Điền lệnh để chạy toàn bộ file docker-compose ở chế độ background
docker compose ps                        # xem trạng thái (healthy?)
```

### Bước 5 — CHALLENGE: Ép Memory Limit

> 🏆 Yêu cầu:
> 1. Trong file `docker-compose.yml`, sửa `mem_limit: 512m` của service `app` thành `mem_limit: 128m` (mức rất thấp cho JVM).
> 2. Chạy `docker compose up -d` và gọi vài API.
> 3. Quan sát xem app có bị chết (OOM) hay chạy chậm lại không.
> 4. Hiểu được vai trò sinh tồn của cờ `-XX:MaxRAMPercentage` và vì sao `MaxRAMPercentage=100.0` lại gây OOM Killed ở mức container.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Đóng cả JDK + Maven vào image production.** Image phình to, nhiều công cụ thừa = bề mặt tấn công lớn. Dùng multi-stage, runtime chỉ JRE.
- **Copy nguyên fat jar thành 1 layer.** Đổi 1 dòng code phải tải lại cả dependencies. Dùng **layered jar** để dependencies nằm layer riêng được cache.
- **Quên đặt `--memory`/`mem_limit` cho container.** JVM tưởng có cả RAM host → đặt heap quá lớn → OOM-killed. Luôn giới hạn memory + dùng `MaxRAMPercentage`.
- **Đặt `MaxRAMPercentage=100`.** Không chừa RAM cho metaspace/thread stack/buffer ngoài heap → container OOM. Để ~75%.
- **Dùng `localhost` trong config khi chạy compose.** Trong container, `localhost` là chính nó, không phải máy host hay service khác. Gọi nhau bằng **tên service** (`postgres`, `redis`, `kafka`).
- **Không có healthcheck + `depends_on` chỉ kiểu cũ.** `depends_on` mặc định chỉ chờ container **start**, không chờ **sẵn sàng**. App connect DB lúc DB chưa nhận kết nối → lỗi. Dùng `condition: service_healthy`.
- **Chạy bằng `root` trong container.** Rủi ro bảo mật. Tạo và dùng user thường (`USER app`).
- **Không có `.dockerignore`.** Gửi cả `target/`, `.git/`, `.env` vào build context → chậm, có thể lọt secret.
- **`latest` tag ở production.** Không tái lập được. Tag theo version/commit SHA.

---

## 🚀 Liên hệ Spring Boot / Production

- **Buildpacks thay Dockerfile:** `./mvnw spring-boot:build-image` tạo OCI image **tối ưu sẵn** (layered, non-root, JVM tuning) mà không cần viết Dockerfile — tiện cho team không muốn bảo trì Dockerfile.
- **Jib (Google):** plugin Maven/Gradle build image **không cần Docker daemon**, layer hoá tự động, build nhanh trong CI.
- **Image scanning:** quét lỗ hổng (Trivy, Snyk) trong CI; dùng base image cập nhật, distroless để giảm CVE.
- **Multi-arch:** build cho cả `amd64` và `arm64` (`docker buildx`) khi deploy lên môi trường hỗn hợp (Mac M-series dev, server x86).
- **Graceful shutdown:** Spring Boot hỗ trợ `server.shutdown=graceful`; container nhận `SIGTERM` → app xử lý nốt request đang chạy rồi tắt. Đảm bảo `java` là PID 1 nhận signal (dùng `exec` form của ENTRYPOINT, đã làm ở trên).
- **Readiness vs liveness probe** (k8s): Actuator `/actuator/health/readiness` và `/liveness` (liên hệ Day 43) để orchestrator biết khi nào route traffic và khi nào restart.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Đến giờ Auction API đã dùng Postgres (DB), Redis (cache + leaderboard, Day 39), Kafka (event, Day 40). Hôm nay ta **đóng gói toàn bộ stack** chạy bằng một lệnh `docker compose up` — để bất kỳ ai clone repo cũng chạy được ngay, giống `sail up` của Laravel nhưng cho cả hệ.

**Nhiệm vụ Day 41:**

1. Viết `Dockerfile` theo các bước điền khuyết ở trên. Chú ý sử dụng multi-stage build (JDK build → JRE runtime), dùng **layered jar**, chạy bằng user non-root, đặt `-XX:MaxRAMPercentage=75.0`.
2. Viết `.dockerignore` loại `target/`, `.git/`, `.env`...
3. Cấu hình hoàn chỉnh file `docker-compose.yml` với healthcheck rõ ràng, đảm bảo App chờ 3 services kia.
4. Chạy lệnh Build + Up Docker và xem logs xem server đã khởi động mượt mà hay chưa. Gọi thử `POST /auctions/{id}/bids` thấy ghi DB + cập nhật Redis leaderboard + phát Kafka event hoạt động xuyên suốt.
5. Hoàn thành **CHALLENGE** ở Bước 5: Cấu hình Memory Limit để trải nghiệm OOM/Garbage Collection tranh chấp và cảm nhận sức mạnh của `-XX:MaxRAMPercentage`.

**Kết quả mong đợi:** Một lệnh dựng được toàn bộ Auction API + hạ tầng; app chỉ khởi động sau khi DB/Redis/Kafka sẵn sàng; image runtime gọn (chỉ JRE), build lại nhanh nhờ cache layer khi chỉ đổi code.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Image, container, layer khác nhau thế nào?**
> **Đáp:** Image là khuôn bất biến (chỉ đọc) chứa filesystem + metadata để chạy app — như một class. Container là một lần chạy của image, một tiến trình cô lập — như một object/instance. Layer là các lớp xếp chồng tạo nên image; mỗi lệnh Dockerfile tạo một layer, được cache và chia sẻ giữa các image.

**Q2: Multi-stage build là gì và vì sao dùng cho Spring Boot?**
> **Đáp:** Dùng nhiều `FROM` trong một Dockerfile: stage `builder` (JDK+Maven) build ra jar; stage runtime chỉ copy jar sang base **JRE** gọn. Mọi công cụ build (JDK, Maven cache, source) không lọt vào image cuối → image **nhỏ, bảo mật hơn**, đúng tinh thần "JRE đủ để chạy".

**Q3: Layered jar giúp gì?**
> **Đáp:** Tách fat jar thành các lớp (dependencies, loader, snapshot-dependencies, application) để copy thành **các layer Docker riêng**. Vì dependencies hiếm đổi còn code hay đổi, mỗi lần đổi code chỉ rebuild layer `application` nhỏ, giữ nguyên layer dependencies lớn trong cache → build nhanh hơn nhiều.

**Q4: Vì sao gọi service trong compose bằng tên service chứ không phải localhost?**
> **Đáp:** Mỗi container có network namespace riêng; `localhost` trong container là **chính container đó**. Compose tạo một mạng nội bộ và DNS để các service gọi nhau bằng **tên service** (vd `jdbc:postgresql://postgres:5432`). Dùng `localhost` sẽ trỏ về chính app, gây "connection refused".

### Mức Senior

**Q5: Vì sao JVM trong container hay bị OOM, và xử lý thế nào?**
> **Đáp:** JVM cũ đọc RAM của **host** (không nhìn cgroup) → đặt heap quá lớn so với giới hạn container → bị OOM-killed. Từ JDK 10+, `UseContainerSupport` **bật mặc định**: JVM đọc giới hạn cgroup của container. Cần (1) đặt giới hạn memory cho container (`mem_limit`/`resources.limits`), (2) dùng `-XX:MaxRAMPercentage` (vd 75%) thay `-Xmx` cố định, chừa ~25% cho metaspace/thread stack/buffer ngoài heap.

**Q6: Làm sao tối ưu thời gian build và kích thước image production?**
> **Đáp:** (1) Multi-stage: runtime chỉ JRE (hoặc distroless). (2) Layered jar + sắp xếp layer ít-đổi nằm dưới. (3) Copy `pom.xml` và tải dependency **trước** khi copy source để cache layer dependency. (4) `.dockerignore` để context nhỏ. (5) Dùng buildpacks/Jib để layer hoá tự động. (6) Quét image, dùng base image cập nhật.

**Q7: Graceful shutdown trong container quan trọng thế nào và cấu hình ra sao?**
> **Đáp:** Khi orchestrator scale-down/deploy, container nhận `SIGTERM`. Nếu app tắt ngay sẽ rớt request đang xử lý. Bật `server.shutdown=graceful` (Spring Boot xử lý nốt request trong `spring.lifecycle.timeout-per-shutdown-phase`) và đảm bảo tiến trình `java` là **PID 1** nhận signal (dùng exec-form `ENTRYPOINT ["java", ...]`, không dùng shell-form). Kết hợp readiness probe để ngừng nhận traffic trước khi tắt.

---

## ✅ Checklist hoàn thành

- [ ] Phân biệt được image / container / layer
- [ ] Viết được Dockerfile multi-stage (JDK build → JRE runtime) + layered jar
- [ ] Hiểu JVM trong container: cgroup, UseContainerSupport, MaxRAMPercentage
- [ ] Có `.dockerignore` và chạy bằng user non-root
- [ ] Viết docker-compose app + postgres + redis + kafka với healthcheck
- [ ] Hiểu gọi service bằng tên service, depends_on condition service_healthy
- [ ] Hoàn thành Mini Project: `docker compose up` dựng toàn bộ Auction API
- [ ] Hoàn thành Challenge: Trải nghiệm OOM do MaxRAMPercentage
- [ ] Trả lời được 7 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Docker Docs — "Build with multi-stage builds", "Best practices for writing Dockerfiles", `.dockerignore`
- Spring Boot Docs — "Container Images" (layered jar, buildpacks, `spring-boot:build-image`)
- Eclipse Temurin (Adoptium) — image tags `21-jdk` / `21-jre`
- Baeldung — "Dockerizing a Spring Boot Application" và "JVM in Containers"
- Confluent — `cp-kafka` image, cấu hình KRaft mode trong docker-compose
- Laravel Docs — "Laravel Sail" (đối chiếu stack dev bằng Docker)
