# Day 44 - Deployment (Đưa ứng dụng lên Production)

> **Giai đoạn:** Production Engineering
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên đến từ Laravel/PHP đã build được app Spring Boot hoàn chỉnh, giờ cần biết cách **đóng gói – cấu hình – deploy – rollback** an toàn ở môi trường thật.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **vòng đời artifact**: từ source code → `mvn package` → **fat JAR** → image Docker → container chạy production.
- Nắm **externalized configuration**: cùng một artifact chạy được mọi môi trường (dev/staging/prod) nhờ tách cấu hình ra ngoài, theo đúng **thứ tự ưu tiên** của Spring.
- Quản lý **secrets** đúng cách: tuyệt đối **không hardcode**, dùng biến môi trường / secret manager.
- Dựng một **pipeline CI/CD** tối thiểu: build → test → build image → deploy (ví dụ GitHub Actions).
- Hiểu **zero-downtime deployment**: rolling update, blue-green, readiness/liveness probe, **graceful shutdown**.
- Biết **tinh chỉnh JVM cho container** (`-Xmx`, chọn GC, heap dump khi OOM) và quy trình **rollback** khi deploy hỏng.
- Liên hệ với cách bạn đã quen deploy Laravel (Forge/Envoyer, `.env`, `php artisan migrate`, `queue:restart`).

---

## 🧠 Lý thuyết cốt lõi

### 1. Từ source code đến container đang chạy — bức tranh tổng thể

```
  Source code        Build tool            Artifact            Image              Runtime
  src/**.java   ──►   mvn package      ──►  app.jar       ──►  docker build  ──►  container
  (Git repo)         (compile+test)        (fat JAR:            (JAR + JRE +       (k8s pod /
                                            code+libs+Tomcat)    OS layer)          VM / ECS)
```

- **Build**: `mvn clean package` (hoặc `./gradlew bootJar`) biên dịch, chạy test, rồi gói tất cả thành **một file JAR duy nhất** — gọi là **fat JAR** (hoặc *uber JAR*).
- **Artifact**: chính là file `app.jar` đó. Nó **bất biến** (immutable) — build một lần, dùng đi mọi môi trường. Đây là nguyên tắc vàng: *build once, deploy many*.
- **Image**: đóng `app.jar` cùng một bản JRE và lớp OS tối thiểu thành Docker image — đơn vị triển khai chuẩn ngày nay.
- **Runtime**: container chạy trên Kubernetes / ECS / một VM. Mỗi môi trường **chỉ khác nhau ở cấu hình bơm vào lúc chạy**, không khác ở artifact.

> 💡 Khác biệt tư duy lớn nhất với Laravel: ở Laravel bạn thường **deploy source code** (git pull lên server rồi `composer install`). Ở Spring Boot bạn deploy **một artifact đã build sẵn** — server production không cần JDK, không cần Maven, không cần internet để tải dependency. Gọn, nhanh, tái lập được.

### 2. Fat JAR — cấu trúc bên trong

Spring Boot dùng plugin `spring-boot-maven-plugin` để tạo fat JAR có cấu trúc đặc biệt:

```
app.jar
├── META-INF/MANIFEST.MF        ← Main-Class: org.springframework.boot.loader.launch.JarLauncher
├── org/springframework/boot/loader/...   ← bootstrap loader của Spring Boot
└── BOOT-INF/
    ├── classes/                ← code đã biên dịch + application.yml của BẠN
    ├── lib/                    ← tất cả thư viện .jar phụ thuộc (Tomcat nhúng, Hibernate...)
    └── classpath.idx           ← thứ tự classpath
```

- Vì Tomcat được **nhúng** sẵn trong JAR nên không cần cài web server ngoài → chạy chỉ với `java -jar app.jar`.
- Lệnh build:

```bash
mvn clean package -DskipTests=false   # build + chạy test, sinh ra target/app.jar
java -jar target/app.jar              # chạy thử fat JAR
```

> ⚠️ Đừng nhầm `mvn package` (tạo fat JAR chạy được) với JAR thường. Nếu thiếu `spring-boot-maven-plugin`, bạn sẽ ra một JAR "mỏng" thiếu dependency, chạy lên báo `ClassNotFoundException`.

### 3. Externalized Configuration & thứ tự ưu tiên (cốt lõi nhất)

Cùng một `app.jar` phải chạy được ở dev (DB local) lẫn prod (DB cloud). Bí quyết: **không nhúng giá trị cụ thể vào code**, mà để Spring đọc cấu hình từ nhiều nguồn theo **thứ tự ưu tiên**. Nguồn ưu tiên cao **ghi đè** nguồn thấp hơn:

```
ƯU TIÊN CAO  ─────────────────────────────────────────────►  ƯU TIÊN THẤP
┌───────────────┐ ┌──────────────┐ ┌──────────────────────┐ ┌─────────────────┐
│ Command-line  │ │ Biến môi      │ │ application-{profile} │ │ application.yml │
│ args          │>│ trường (ENV)  │>│ .yml (vd: -prod)      │>│ (mặc định)      │
│ --server.port │ │ SERVER_PORT   │ │                       │ │                 │
└───────────────┘ └──────────────┘ └──────────────────────┘ └─────────────────┘
```

Quy tắc thực dụng:
1. **`application.yml`** — giá trị **mặc định**, an toàn cho dev. Commit vào git.
2. **`application-{profile}.yml`** — phần riêng từng môi trường (`application-prod.yml`). Chỉ khai báo **những gì khác** so với mặc định.
3. **Biến môi trường** — nơi đặt **secrets** và giá trị tùy hạ tầng (DB host, password). **Không** commit.
4. **Command-line args** (`--key=value`) — ghi đè tạm thời khi chạy, ưu tiên cao nhất.

> 💡 Spring tự ánh xạ tên biến môi trường sang property theo **relaxed binding**: `spring.datasource.url` ↔ biến `SPRING_DATASOURCE_URL`. Dấu chấm/gạch ngang → gạch dưới, viết HOA. Đây là cách bơm config vào container chuẩn nhất.

Kích hoạt profile prod bằng một trong các cách:

```bash
java -jar app.jar --spring.profiles.active=prod      # command-line
# hoặc
export SPRING_PROFILES_ACTIVE=prod && java -jar app.jar   # biến môi trường
```

**Spring Cloud Config (sơ lược):** khi có **nhiều service** (microservices), việc mỗi service tự giữ file cấu hình trở nên khó quản. **Spring Cloud Config Server** là một service trung tâm đọc cấu hình từ một Git repo và **phục vụ** cấu hình qua HTTP cho mọi service lúc khởi động. Lợi ích: cấu hình tập trung, có version (theo Git), đổi config không cần build lại app. Hôm nay chỉ cần biết khái niệm; với app đơn lẻ thì env + `application-prod.yml` là đủ.

### 4. Quản lý Secrets — tuyệt đối không hardcode

```
❌ SAI                                  ✅ ĐÚNG
spring:                                 spring:
  datasource:                             datasource:
    password: MyP@ss123   ← lộ trong       password: ${DB_PASSWORD}  ← đọc từ ENV
                            git, log!                                   lúc chạy
```

- **Không bao giờ** commit password, API key, JWT secret vào git. Một khi lên Git, coi như đã lộ (kể cả xóa sau, lịch sử vẫn còn).
- Cách bơm secret theo cấp độ trưởng thành:
  - Cơ bản: **biến môi trường** (`DB_PASSWORD`, `JWT_SECRET`) — đủ cho hầu hết trường hợp.
  - Tốt hơn: **secret manager** (AWS Secrets Manager, HashiCorp Vault, GCP Secret Manager, Kubernetes Secrets). Service lấy secret lúc khởi động, có xoay vòng (rotation), có audit.
- Trong Spring, luôn tham chiếu qua placeholder `${TÊN_BIẾN}` để giá trị thật đến từ bên ngoài.

> ⚠️ Bẫy kinh điển: in cả object config ra log lúc debug → password lọt vào log tập trung. Đừng log `application.yml` đã resolve, và đừng để `/actuator/env` lộ ra public (Day 42).

### 5. CI/CD — tự động hóa build → test → deploy

**CI (Continuous Integration):** mỗi lần push code, một runner tự động **build + chạy test**. Nếu test fail, chặn merge → giữ nhánh chính luôn "xanh".

**CD (Continuous Delivery/Deployment):** sau khi CI xanh, tự động **build image** và **deploy** lên môi trường.

```
  Push code ──► CI: build + test ──► build Docker image ──► push registry ──► deploy
   (Git)          (Maven, JUnit)       (docker build)        (GHCR/ECR)       (rolling)
                       │
                  fail ↓ → chặn merge, báo developer
```

### 6. Zero-downtime deployment — deploy không gián đoạn

Mục tiêu: thay phiên bản mới **mà người dùng không thấy lỗi**. Hai chiến lược chính:

**a) Rolling update** (mặc định của Kubernetes): thay từng instance một. Khởi động instance mới, đợi nó **sẵn sàng (ready)**, rồi mới tắt instance cũ — lặp lại.

```
Trước:  [v1] [v1] [v1]
Bước 1: [v2] [v1] [v1]   ← v2 mới, đợi ready rồi mới tắt 1 con v1
Bước 2: [v2] [v2] [v1]
Sau:    [v2] [v2] [v2]
```

**b) Blue-Green**: chạy song song hai môi trường đầy đủ. "Blue" (v1) đang phục vụ; deploy "Green" (v2) bên cạnh, test xong thì **chuyển toàn bộ traffic** sang Green tức thì. Rollback = chuyển ngược lại. Tốn gấp đôi tài nguyên nhưng rollback cực nhanh.

**Liveness vs Readiness probe** — chìa khóa để zero-downtime hoạt động:

| Probe | Câu hỏi nó trả lời | Fail thì sao |
|---|---|---|
| **Liveness** | App còn **sống** không? (không deadlock/treo) | Container bị **restart** |
| **Readiness** | App đã **sẵn sàng nhận traffic** chưa? (DB connect xong, cache warm) | **Ngừng gửi traffic** tới (không restart) |

Spring Boot Actuator cung cấp sẵn:
- `/actuator/health/liveness`
- `/actuator/health/readiness`

> 💡 Vì sao cần readiness riêng? App Spring Boot mất vài giây để khởi tạo context, mở connection pool, warm-up JIT (nhớ Day 01). Nếu k8s gửi traffic ngay khi process vừa lên → request đầu lỗi. Readiness probe nói "khoan, chưa sẵn sàng" cho tới khi thật sự ổn.

**Graceful shutdown** — tắt êm, không cắt request đang dở:

```
Nhận tín hiệu SIGTERM (k8s muốn tắt pod)
        │
        ├─► ngừng nhận request MỚI
        ├─► CHỜ các request đang xử lý hoàn tất (tới timeout)
        ├─► đóng connection pool, đóng Kafka consumer...
        └─► JVM thoát sạch
```

Bật trong `application.yml`:

```yaml
server:
  shutdown: graceful            # mặc định là "immediate" (cắt phũ)
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s   # tối đa chờ 30s cho request dở xong
```

### 7. JVM tuning cho production (trong container)

```bash
java \
  -XX:MaxRAMPercentage=75.0 \              # dùng tối đa 75% RAM của container làm heap
  -XX:+UseG1GC \                            # G1 GC: cân bằng tốt cho service web
  -XX:+HeapDumpOnOutOfMemoryError \         # tự dump heap khi OOM để điều tra
  -XX:HeapDumpPath=/tmp/heapdump.hprof \
  -jar app.jar
```

- **Đặt heap theo container, không theo máy host.** Trong Docker/k8s nên dùng `-XX:MaxRAMPercentage` thay cho `-Xmx` cứng, để JVM tự co giãn theo memory limit của container (JDK hiện đại nhận biết cgroup). Nếu muốn cố định: `-Xms` = `-Xmx` (tránh JVM phải mở rộng heap lúc chạy).
- **Chọn GC**: `G1GC` (mặc định từ Java 9) tốt cho hầu hết service. Cần độ trễ cực thấp → cân nhắc `ZGC`. Đừng tối ưu sớm — đo trước (Day 06/07).
- **`HeapDumpOnOutOfMemoryError`**: khi OOM, JVM dump toàn bộ heap ra file để bạn mở bằng Eclipse MAT điều tra rò rỉ. Bật sẵn ở prod.
- Đừng đặt heap **bằng** memory limit container — JVM còn cần metaspace, thread stack, off-heap. Để heap ~75%.

### 8. Rollback — kế hoạch B luôn phải có

Deploy hỏng là chuyện thường. Phải rollback được **trong vài phút**:

- **Image rollback**: vì image bất biến và có tag (version), chỉ cần deploy lại tag cũ. `kubectl rollout undo deployment/auction-api` quay về revision trước.
- **DB migration**: nguy hiểm nhất. Migration phải **tương thích ngược** (backward compatible) để v1 và v2 cùng chạy được trong lúc rolling update. Quy tắc: **thêm cột trước, dùng sau; bỏ cột sau cùng** (expand-then-contract). Tránh migration "phá vỡ" khiến không rollback được.
- Luôn giữ lại N phiên bản image gần nhất trong registry để rollback nhanh.

---

## 🔁 Đối chiếu với Laravel/PHP

| Khái niệm | Laravel / PHP | Spring Boot |
|---|---|---|
| Đơn vị deploy | **Source code** (git pull + `composer install`) | **Artifact bất biến** (`app.jar` / Docker image) |
| Cấu hình theo môi trường | File `.env` (đọc qua `config()` / `env()`) | `application-{profile}.yml` + biến môi trường |
| Đổi profile/môi trường | `APP_ENV=production` trong `.env` | `SPRING_PROFILES_ACTIVE=prod` |
| Secrets | `.env` không commit (`.gitignore`) | Biến môi trường / Secret Manager, placeholder `${...}` |
| Công cụ deploy phổ biến | **Forge / Envoyer**, Deployer | Docker + Kubernetes / ECS, GitHub Actions |
| Chạy migration khi deploy | `php artisan migrate --force` | Flyway/Liquibase chạy lúc app khởi động |
| Web server | Nginx + php-fpm (ngoài app) | Tomcat **nhúng** trong JAR |
| Restart worker sau deploy | `php artisan queue:restart` | Graceful shutdown + rolling restart pod |
| Mô hình tiến trình | Mỗi request một process php-fpm (stateless) | Một process JVM **chạy lâu dài**, giữ state |

**Khác biệt tư duy quan trọng nhất:**

- Laravel: deploy là **đồng bộ code lên server** và server tự chạy. Cấu hình `.env` nằm trên server.
- Spring Boot: deploy là **thay artifact đã build sẵn**; cùng artifact chạy mọi nơi, môi trường chỉ khác ở config bơm lúc chạy. Vì process **sống lâu**, bạn phải lo graceful shutdown để không cắt ngang request — điều Laravel hầu như không phải nghĩ tới (php-fpm xử lý xong request là process xong).

> 🧩 Envoyer của Laravel thực ra cũng làm "zero-downtime" bằng cách deploy vào thư mục mới rồi đổi symlink — ý tưởng giống blue-green. Bạn không lạ với khái niệm này, chỉ khác cách hiện thực.

---

## 💻 Thực hành code

### Bước 1 — `application.yml` (mặc định, an toàn cho dev)

```yaml
# src/main/resources/application.yml
spring:
  application:
    name: auction-api
  datasource:
    url: jdbc:postgresql://localhost:5432/auction   # mặc định DB local
    username: auction
    password: auction        # mật khẩu dev tầm thường, KHÔNG dùng ở prod
  jpa:
    hibernate:
      ddl-auto: validate     # prod không bao giờ để create/update
    open-in-view: false
server:
  port: 8080
  shutdown: graceful         # bật tắt êm
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true        # bật /health/liveness và /health/readiness
```

### Bước 2 — `application-prod.yml` (chỉ phần KHÁC so với mặc định)

```yaml
# src/main/resources/application-prod.yml
spring:
  datasource:
    # Tất cả giá trị nhạy cảm đến từ BIẾN MÔI TRƯỜNG, không hardcode
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20       # pool lớn hơn cho tải prod
  jpa:
    hibernate:
      ddl-auto: validate          # tuyệt đối không cho Hibernate sửa schema ở prod
    show-sql: false               # tắt log SQL ở prod
  lifecycle:
    timeout-per-shutdown-phase: 30s
logging:
  level:
    root: INFO
    com.example.auction: INFO

app:
  jwt:
    secret: ${JWT_SECRET}         # secret JWT bơm từ ngoài
    expiration-ms: 3600000
```

### Bước 3 — Cấu hình graceful shutdown cho task nền (nếu có)

```java
// Ví dụ: một component có vòng lặp nền cần dừng êm khi app tắt
@Component
public class BidExpiryWorker {

    private volatile boolean running = true;

    @Async
    public void run() {
        while (running) {
            // ... xử lý hết phiên đấu giá hết hạn ...
        }
    }

    /**
     * Spring gọi @PreDestroy khi context đóng (lúc graceful shutdown).
     * Ta set cờ để vòng lặp thoát sạch, không cắt ngang.
     */
    @PreDestroy
    public void stop() {
        this.running = false;   // báo vòng lặp dừng
    }
}
```

### Bước 4 — Dockerfile production (multi-stage, image gọn)

```dockerfile
# ----- Giai đoạn build: dùng JDK đầy đủ để compile -----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Cache layer dependency: copy pom trước, tải lib trước khi copy source
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -B
COPY src ./src
RUN ./mvnw clean package -DskipTests   # test đã chạy ở CI, ở đây skip cho nhanh

# ----- Giai đoạn runtime: chỉ cần JRE, image nhỏ & an toàn hơn -----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# Chạy bằng user không phải root (bảo mật)
RUN useradd -r -u 1001 appuser
COPY --from=build /app/target/*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/tmp/heapdump.hprof", \
  "-jar", "app.jar"]
```

> 💡 **Multi-stage build**: giai đoạn `build` dùng JDK (nặng) để biên dịch; image cuối chỉ lấy `app.jar` đặt lên **JRE** gọn (nhớ Day 01: production chỉ cần JRE). Image cuối không chứa source, không chứa Maven → nhỏ và bề mặt tấn công ít hơn.

### Bước 5 — GitHub Actions workflow (CI/CD mẫu)

```yaml
# .github/workflows/deploy.yml
name: Build & Deploy Auction API

on:
  push:
    branches: [ main ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Cài JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven          # cache ~/.m2 cho build nhanh

      - name: Build & test
        run: ./mvnw clean verify   # verify = compile + unit + integration test

  build-image:
    needs: build-and-test          # chỉ build image khi test xanh
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write              # quyền push image lên GHCR
    steps:
      - uses: actions/checkout@v4

      - name: Đăng nhập GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build & push image
        uses: docker/build-push-action@v6
        with:
          context: .
          push: true
          # tag theo commit SHA để mỗi build có version riêng, rollback dễ
          tags: ghcr.io/${{ github.repository }}:${{ github.sha }}

  deploy:
    needs: build-image
    runs-on: ubuntu-latest
    steps:
      - name: Deploy lên Kubernetes
        run: |
          # Cập nhật image -> k8s tự rolling update
          kubectl set image deployment/auction-api \
            auction-api=ghcr.io/${{ github.repository }}:${{ github.sha }}
          kubectl rollout status deployment/auction-api --timeout=120s
```

### Bước 6 — Probe trong Kubernetes Deployment

```yaml
# k8s/deployment.yaml (trích phần probe)
spec:
  template:
    spec:
      containers:
        - name: auction-api
          image: ghcr.io/example/auction-api:latest
          ports:
            - containerPort: 8080
          envFrom:
            - secretRef:
                name: auction-secrets   # bơm DB_PASSWORD, JWT_SECRET... từ k8s Secret
          # App còn sống không? Fail -> restart container
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
          # App sẵn sàng nhận traffic chưa? Fail -> ngừng gửi traffic (không restart)
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
```

> ✅ **Bài tập tự kiểm chứng:** Build fat JAR, chạy `java -jar app.jar --spring.profiles.active=prod` với các biến `DB_URL`, `DB_PASSWORD`, `JWT_SECRET` set tạm trong terminal. Gọi thử `curl localhost:8080/actuator/health/readiness`. Sau đó gửi `kill -SIGTERM <pid>` và quan sát log: app phải **ngừng nhận request mới** rồi mới thoát.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Hardcode secret trong `application.yml` rồi commit lên git.** Một khi lên Git là lộ vĩnh viễn (trong history). Luôn dùng `${ENV_VAR}`.
- **Để `ddl-auto: update` (hoặc `create`) ở prod.** Hibernate sẽ tự sửa schema → cực nguy hiểm, có thể mất dữ liệu. Prod luôn `validate`, dùng Flyway/Liquibase quản lý migration.
- **Quên bật `server.shutdown: graceful`.** Mặc định Spring cắt phũ (immediate) → request đang xử lý bị đứt giữa chừng khi rolling update.
- **Đặt `-Xmx` bằng đúng memory limit container.** JVM còn cần metaspace + thread stack + off-heap → bị OOMKilled. Để heap ~75% bằng `MaxRAMPercentage`.
- **Không có readiness probe** (chỉ có liveness): k8s gửi traffic ngay khi process vừa lên, lúc connection pool/cache chưa sẵn → request đầu lỗi.
- **Migration không tương thích ngược.** Drop cột ngay trong bản deploy mới khiến instance v1 (còn đang chạy trong rolling update) gãy → không rollback được. Dùng expand-then-contract.
- **Build image từ JDK đầy đủ cho production.** Image to, nhiều lỗ hổng, chứa cả source & build tool. Dùng multi-stage, image cuối trên JRE.
- **Đo hiệu năng/đẩy full traffic ngay khi deploy xong** — JIT chưa warm-up (Day 01). Tăng traffic dần, dùng readiness để chờ app "ấm".

---

## 🚀 Liên hệ Spring Boot / Production

- **`spring-boot-maven-plugin`** chính là thứ tạo fat JAR chạy được. Layered JARs (chia BOOT-INF thành các layer dependency/snapshot/application) giúp Docker cache layer tốt hơn — đổi code chỉ rebuild layer cuối.
- **Buildpacks**: `mvn spring-boot:build-image` tạo Docker image **tối ưu sẵn** mà không cần viết Dockerfile (dựa trên Paketo Buildpacks) — lựa chọn nhanh, an toàn cho nhiều team.
- **Actuator** (Day 42) cung cấp đúng các endpoint mà hạ tầng cần: `/health/liveness`, `/health/readiness`, `/metrics`, `/prometheus`. Đây là cầu nối giữa app và Kubernetes/monitoring.
- **GraalVM Native Image**: biên dịch Spring Boot thành binary native khởi động cực nhanh (mili giây), RAM thấp — hợp serverless/scale-to-zero. Đánh đổi: build lâu, mất tính linh hoạt của JVM. Biết để cân nhắc, chưa cần dùng ngay.
- **12-Factor App**: triết lý nền cho mọi điều trên — config tách khỏi code (factor III), build/release/run tách bạch (factor V), xử lý stateless (factor VI), graceful shutdown (factor IX). Đáng đọc một lần.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> Hôm nay ta **chuẩn bị deploy Auction API** ra production một cách bài bản.

**Nhiệm vụ Day 44:**

1. **Tách cấu hình theo môi trường:** giữ `application.yml` cho dev, tạo `application-prod.yml` với mọi giá trị nhạy cảm (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, Redis/Kafka host) đọc qua `${ENV}`. Đảm bảo `ddl-auto: validate` ở prod.
2. **Build fat JAR:** `mvn clean package`, chạy thử `java -jar target/auction-api.jar --spring.profiles.active=prod` với các biến môi trường tạm.
3. **Graceful shutdown:** bật `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 30s`. Mô phỏng một request chậm rồi `SIGTERM` để chứng minh request không bị cắt.
4. **Healthcheck:** bật `management.endpoint.health.probes.enabled=true`, kiểm tra `/actuator/health/liveness` và `/actuator/health/readiness`.
5. **Đóng gói:** viết `Dockerfile` multi-stage (JDK build → JRE runtime, user không root, bật `HeapDumpOnOutOfMemoryError`). Build image và chạy container thử.
6. **CI/CD:** thêm `.github/workflows/deploy.yml` build → test → build image → (mô phỏng) deploy. Tag image theo commit SHA.
7. Ghi vào `notes/day-44.md`: kế hoạch **rollback** cho Auction API (rollback image + chiến lược migration tương thích ngược).

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Fat JAR là gì? Vì sao Spring Boot dùng nó?**
> **Đáp:** Fat JAR (uber JAR) là một file JAR duy nhất chứa code đã biên dịch của bạn **cùng tất cả thư viện phụ thuộc và một web server nhúng** (Tomcat). Nhờ vậy chỉ cần `java -jar app.jar` là chạy, không cần cài web server hay tải dependency ở server. Spring Boot tạo nó qua `spring-boot-maven-plugin`, với cấu trúc `BOOT-INF/classes` (code) và `BOOT-INF/lib` (thư viện).

**Q2: Thứ tự ưu tiên cấu hình trong Spring Boot là gì?**
> **Đáp:** Từ cao đến thấp (cao ghi đè thấp): **command-line args** (`--key=value`) > **biến môi trường** (qua relaxed binding) > **`application-{profile}.yml`** của profile đang active > **`application.yml`** mặc định. Nhờ thứ tự này, cùng một artifact chạy mọi môi trường: giá trị mặc định ở file, giá trị riêng từng môi trường bơm qua env/args.

**Q3: Tại sao không được hardcode secret và nên đặt nó ở đâu?**
> **Đáp:** Hardcode secret trong code/yaml rồi commit khiến nó lộ vĩnh viễn trong lịch sử Git và dễ lọt vào log. Thay vào đó tham chiếu qua placeholder `${DB_PASSWORD}` và bơm giá trị thật từ **biến môi trường** hoặc **secret manager** (Vault, AWS Secrets Manager, Kubernetes Secret) lúc chạy. Secret manager còn cho xoay vòng key và audit.

**Q4: Liveness probe và readiness probe khác nhau thế nào?**
> **Đáp:** **Liveness** trả lời "app còn sống không" — fail thì container bị **restart** (dùng để thoát khỏi deadlock/treo). **Readiness** trả lời "app đã sẵn sàng nhận traffic chưa" — fail thì hệ thống **ngừng gửi traffic** (không restart), dùng để chờ app khởi tạo xong connection pool, warm-up. Spring Boot Actuator cung cấp sẵn `/health/liveness` và `/health/readiness`.

### Mức Senior

**Q5: Làm sao đạt zero-downtime deployment và graceful shutdown đóng vai trò gì?**
> **Đáp:** Dùng **rolling update** (hoặc **blue-green**): thay từng instance, instance mới phải **pass readiness** rồi mới tắt instance cũ — nên luôn có instance sẵn sàng phục vụ. **Graceful shutdown** đảm bảo khi một instance cũ nhận SIGTERM, nó **ngừng nhận request mới nhưng hoàn tất các request đang xử lý** trong một khoảng timeout rồi mới thoát, đóng pool/consumer sạch sẽ. Thiếu graceful shutdown thì các request đang dở bị cắt giữa chừng → lỗi 5xx ngay trong lúc deploy. Bật bằng `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`.

**Q6: Cấu hình JVM heap trong container nên làm thế nào và vì sao?**
> **Đáp:** Không nên đặt `-Xmx` cứng bằng host RAM, mà dùng `-XX:MaxRAMPercentage=75.0` để JVM tính heap theo **memory limit của container** (JDK hiện đại nhận biết cgroup). Để heap khoảng 75% vì JVM còn cần metaspace, thread stack, code cache, buffer off-heap — nếu heap = limit thì kernel sẽ **OOMKill** container. Nên đặt `-Xms = -Xmx` để tránh JVM phải mở rộng heap lúc chạy gây pause. Kèm `-XX:+HeapDumpOnOutOfMemoryError` để có dữ liệu điều tra khi OOM, và chọn GC (`G1GC` mặc định, `ZGC` nếu cần latency thấp).

**Q7: Migration database trong môi trường rolling update cần lưu ý gì để rollback được?**
> **Đáp:** Trong rolling update, **v1 và v2 chạy song song** một lúc, nên schema phải **tương thích cả hai chiều**. Áp dụng **expand-then-contract**: bản deploy A chỉ *thêm* cột/bảng mới (không xóa, không đổi nghĩa cột cũ) — v1 vẫn chạy được; sau khi v2 ổn định và không còn dùng cột cũ, bản deploy B mới *xóa* cột cũ. Như vậy có thể rollback A bất cứ lúc nào. Migration "phá vỡ" (drop/rename ngay) khiến không rollback được và làm gãy instance còn lại trong lúc rollout. Dùng Flyway/Liquibase để version hóa và chạy migration có kiểm soát.

**Q8: So sánh build-once-deploy-many với cách deploy source code, ưu nhược điểm?**
> **Đáp:** **Build once, deploy many**: build một **artifact bất biến** (JAR/image có version) rồi promote qua dev → staging → prod, chỉ thay config. Ưu: thứ chạy ở prod **đúng y** thứ đã test ở staging (không có "khác môi trường lúc build"); rollback = đổi lại tag image; reproducible, audit được. Nhược: cần hạ tầng registry/CI. Ngược lại, deploy source rồi build trên server (kiểu Laravel cổ điển) đơn giản hơn nhưng dễ "works on staging, fails on prod" do dependency/version khác nhau giữa các lần build, và rollback khó hơn.

---

## ✅ Checklist hoàn thành

- [ ] Build được fat JAR và chạy bằng `java -jar app.jar`
- [ ] Tách cấu hình dev/prod, hiểu đúng thứ tự ưu tiên config của Spring
- [ ] Mọi secret đọc qua `${ENV}`, không còn giá trị nhạy cảm trong git
- [ ] Bật và kiểm chứng graceful shutdown (SIGTERM không cắt request dở)
- [ ] Bật và gọi được liveness/readiness probe của Actuator
- [ ] Viết Dockerfile multi-stage (JDK build → JRE runtime, user không root)
- [ ] Có workflow CI/CD build → test → build image → deploy
- [ ] Hiểu JVM tuning trong container (`MaxRAMPercentage`, GC, heap dump)
- [ ] Có kế hoạch rollback (image + migration tương thích ngược)
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Spring Boot Reference — "Deploying Spring Boot Applications", "Externalized Configuration", "Container Images"
- Spring Boot Reference — "Graceful Shutdown" và "Kubernetes Probes" (Actuator)
- The Twelve-Factor App — https://12factor.net (đọc factor III, V, VI, IX)
- Kubernetes Docs — "Configure Liveness, Readiness and Startup Probes", "Rolling Update Deployment"
- GitHub Actions Docs — "Building and testing Java with Maven", `docker/build-push-action`
- Baeldung — "Spring Boot Externalized Configuration", "Dockerizing a Spring Boot Application"
- *Java Performance* (Scott Oaks) — chương về GC tuning và sizing heap trong container
