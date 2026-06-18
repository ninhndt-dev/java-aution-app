# Day 15 - Annotation & Mini Project

> **Giai đoạn:** Reflection & Annotations
> **Thời lượng gợi ý:** 3 giờ (1h lý thuyết · 1h code · 1h ghi chú/ôn phỏng vấn)
> **Dành cho:** Lập trình viên Laravel/PHP đã quen Reflection (Day 14), nay muốn hiểu "ma thuật" của Spring đến tận gốc.

---

## 🎯 Mục tiêu ngày hôm nay

- Hiểu **annotation là metadata** gắn vào code — nó *mô tả* code chứ không *thay đổi* logic trực tiếp.
- Nắm các **annotation có sẵn**: `@Override`, `@Deprecated`, `@SuppressWarnings`, `@FunctionalInterface`, `@SafeVarargs` — mỗi cái dùng khi nào.
- Hiểu **meta-annotation**: `@Retention`, `@Target`, `@Documented`, `@Inherited`, `@Repeatable` — đặc biệt phân biệt `SOURCE` / `CLASS` / `RUNTIME`.
- **Tự định nghĩa annotation** có element (phần tử), `default`, phần tử đặc biệt `value()`.
- **Đọc annotation lúc runtime** bằng reflection: `isAnnotationPresent()`, `getAnnotation()`, `getAnnotationsByType()`.
- Phân biệt **annotation processing lúc biên dịch** (APT — Lombok/MapStruct) với **đọc runtime** (Spring).
- **Checkpoint giữa khóa:** ghép Reflection + Annotation thành một **mini validation framework** — bản thu nhỏ của Bean Validation.

---

## 🧠 Lý thuyết cốt lõi

### 1. Annotation là gì? Metadata, không phải logic

Annotation là một dạng **metadata** (dữ liệu về dữ liệu) bạn "dán" lên class, method, field, parameter... Nó **không tự làm gì cả**. Một annotation nằm trơ trọi trên code thì vô nghĩa — phải có **ai đó đọc nó ra rồi hành động** (compiler, một annotation processor, hoặc framework đọc bằng reflection lúc chạy).

```
   Code Java         Annotation (metadata)        Người đọc & xử lý
   class Bid {                                    ┌─ javac (compile-time check)
     @NotNull   ◄──── "trường này không null"  ──►├─ Annotation Processor (sinh code)
     String bidder;                               └─ Framework qua Reflection (runtime)
   }
```

> 💡 Hãy nhớ câu thần chú: **annotation chỉ là tờ giấy ghi chú dán lên code.** Tự nó không kiểm tra, không validate, không inject gì hết. Sức mạnh đến từ *bộ đọc* annotation. Spring chính là một "bộ đọc khổng lồ".

### 2. Annotation có sẵn (built-in)

Java cung cấp sẵn vài annotation dùng cực nhiều:

| Annotation | Đặt ở đâu | Dùng để làm gì |
|---|---|---|
| `@Override` | method | Báo compiler "tôi *đang* ghi đè method cha". Nếu signature sai → **compile lỗi** ngay, tránh bug âm thầm. |
| `@Deprecated` | mọi nơi | Đánh dấu API "lỗi thời, đừng dùng". IDE gạch ngang, compiler cảnh báo. |
| `@SuppressWarnings` | mọi nơi | Tắt một loại warning cụ thể, ví dụ `@SuppressWarnings("unchecked")`. |
| `@FunctionalInterface` | interface | Khẳng định interface chỉ có **đúng 1 abstract method**. Thêm method thứ 2 → compile lỗi. |
| `@SafeVarargs` | method/constructor | Khẳng định method varargs generic an toàn, tắt cảnh báo "unchecked varargs". |

```java
@FunctionalInterface          // nếu lỡ thêm abstract method thứ 2 -> compile lỗi
interface BidValidator {
    boolean isValid(Bid bid); // đúng 1 abstract method
}

class BaseAuction {
    @Deprecated                       // báo "đừng dùng nữa"
    void oldPlaceBid() { /* ... */ }
}

class EnglishAuction extends BaseAuction {
    @Override                         // compiler đảm bảo đúng method cha
    public String toString() { return "EnglishAuction"; }
}
```

> ⚠️ `@Override` "tốn 0 chi phí runtime" nhưng cứu bạn vô số bug: gõ nhầm `toString()` thành `tostring()` mà không có `@Override` thì compiler im lặng, bạn tạo *method mới* chứ không override gì cả.

### 3. Meta-annotation — annotation dán lên annotation

Khi bạn **tự định nghĩa** annotation, bạn cần khai báo nó bằng các *meta-annotation* (annotation dùng để mô tả annotation):

| Meta-annotation | Ý nghĩa |
|---|---|
| `@Retention` | Annotation **sống đến khi nào**: SOURCE / CLASS / RUNTIME. |
| `@Target` | Annotation được phép **dán lên đâu**: TYPE, FIELD, METHOD, PARAMETER... |
| `@Documented` | Annotation xuất hiện trong **Javadoc** sinh ra. |
| `@Inherited` | Class con **kế thừa** annotation đặt trên class cha (chỉ áp dụng cho TYPE). |
| `@Repeatable` | Cho phép **dán cùng một annotation nhiều lần** lên một phần tử. |

#### 3.1. `@Retention` — vòng đời annotation (QUAN TRỌNG NHẤT)

Đây là phần dễ nhầm nhất và hay bị hỏi phỏng vấn. `RetentionPolicy` có 3 mức:

```
SOURCE   ──► chỉ tồn tại trong file .java
             javac đọc xong là VỨT, KHÔNG vào .class
             VD: @Override, @SuppressWarnings, các annotation của Lombok

CLASS    ──► được ghi vào file .class (bytecode)
             nhưng JVM KHÔNG nạp lên runtime
             (mặc định nếu không khai báo @Retention)

RUNTIME  ──► ghi vào .class VÀ JVM nạp lên runtime
             => CHỈ mức này mới đọc được bằng Reflection!
             VD: @Component, @Autowired, @Entity của Spring/JPA
```

| Mức | Có trong `.class`? | Reflection đọc được? | Ai dùng |
|---|---|---|---|
| `SOURCE` | ❌ | ❌ | Compiler check, annotation processor (APT), Lombok |
| `CLASS` (mặc định) | ✔️ | ❌ | Công cụ phân tích bytecode (ASM), một số tool đặc thù |
| `RUNTIME` | ✔️ | ✔️ | **Spring, JPA, Jackson, Bean Validation** — mọi thứ "ma thuật runtime" |

> 💡 Quy tắc vàng: muốn **framework đọc annotation lúc chạy** (như Spring quét `@Service`), annotation đó **bắt buộc** `@Retention(RetentionPolicy.RUNTIME)`. Quên dòng này là lỗi kinh điển — annotation tự viết "không hoạt động" mà không hiểu vì sao.

#### 3.2. `@Target` — annotation dán được ở đâu

`ElementType` liệt kê các vị trí hợp lệ. Vài giá trị hay gặp:

| ElementType | Dán lên |
|---|---|
| `TYPE` | class, interface, enum, annotation |
| `FIELD` | thuộc tính (field) |
| `METHOD` | phương thức |
| `PARAMETER` | tham số method |
| `CONSTRUCTOR` | hàm khởi tạo |
| `ANNOTATION_TYPE` | annotation khác (làm meta-annotation) |
| `TYPE_USE` | mọi nơi dùng kiểu (VD `@NonNull String`) |

Nếu bạn dán annotation sai chỗ so với `@Target` → **compile lỗi**. Đây là rào chắn tốt: `@Column` chỉ nên dán lên FIELD, đừng cho phép dán lên class.

#### 3.3. `@Inherited` và `@Repeatable` (ngắn gọn)

- `@Inherited`: nếu `@Audited` đặt trên class `A` và có `@Inherited`, thì `class B extends A` cũng được xem như có `@Audited` khi hỏi reflection. **Chỉ áp dụng cho annotation đặt trên class (TYPE), không áp dụng cho field/method.**
- `@Repeatable`: cho phép viết `@Role("ADMIN") @Role("USER")` lên cùng một chỗ. Cần khai báo thêm một annotation "container" chứa mảng. Đọc bằng `getAnnotationsByType()`.

### 4. Tự định nghĩa annotation có element (phần tử)

Annotation tự định nghĩa khai báo bằng `@interface`. Bên trong là các **element** trông như method nhưng thực chất là **tham số cấu hình**:

```java
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)   // để reflection đọc được
@Target(ElementType.FIELD)            // chỉ dán lên field
public @interface Column {
    String name();                    // element BẮT BUỘC (không default)
    boolean nullable() default true;  // element có giá trị mặc định
    int length() default 255;
}
```

Cách dùng: `@Column(name = "user_email", nullable = false, length = 320)`.

**Kiểu trả về cho phép của element** bị giới hạn — không phải kiểu nào cũng được:

| Cho phép | Ví dụ |
|---|---|
| Primitive | `int`, `boolean`, `long`, `double`... |
| `String` | `String name()` |
| `Class` | `Class<?> validator()` |
| Enum | `RetentionPolicy policy()` |
| Annotation khác | `Column col()` |
| Mảng của các loại trên | `String[] groups()` |

> ⚠️ **Không được** dùng kiểu object thường (như `List`, `Map`, `Object`, kiểu tự định nghĩa). Element annotation phải là *hằng compile-time*. Cũng **không có** giá trị `null` — muốn "không có" thì đặt `default` rỗng (`""` hoặc mảng rỗng `{}`).

#### Phần tử đặc biệt `value()`

Nếu annotation chỉ có **một** element tên là `value()` (hoặc các element khác đều có `default`), bạn được phép **bỏ tên** khi dùng:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
@interface Min {
    int value();                 // tên đặc biệt: value
}

// nhờ tên value(), viết gọn được:
@Min(100)        // tương đương @Min(value = 100)
int amount;
```

Đây chính là lý do bạn viết được `@RequestMapping("/api")` hay `@Min(18)` mà không cần `value =`.

### 5. Đọc annotation lúc runtime bằng Reflection

Đây là cầu nối với Day 14. Mọi phần tử reflection (`Class`, `Field`, `Method`, `Parameter`) đều cài đặt interface `AnnotatedElement` với 3 method chủ lực:

```java
Field f = Bid.class.getDeclaredField("amount");

// 1. Có annotation @Min không?
boolean has = f.isAnnotationPresent(Min.class);     // true/false

// 2. Lấy annotation ra để đọc element
Min min = f.getAnnotation(Min.class);               // null nếu không có
if (min != null) {
    int floor = min.value();                        // đọc element value()
}

// 3. Annotation @Repeatable -> lấy mảng
Role[] roles = method.getAnnotationsByType(Role.class);
```

> 💡 Nhắc lại điều kiện sống còn: 3 method trên **chỉ trả về kết quả nếu annotation là `RUNTIME`**. Với `SOURCE`/`CLASS`, `getAnnotation()` luôn trả `null` — JVM không có dữ liệu đó.

### 6. Annotation processing lúc biên dịch (APT) — khác hẳn reflection runtime

Có một con đường thứ hai, hoàn toàn khác: **xử lý annotation lúc *biên dịch*** thông qua **APT** (Annotation Processing Tool), nằm trong gói `javax.annotation.processing`.

```
─── Đường 1: RUNTIME (reflection) ───────────────────────────
  @Retention(RUNTIME)  ──►  app chạy  ──►  framework đọc qua reflection
  VD: Spring quét @Service lúc khởi động                  (chậm hơn, linh hoạt)

─── Đường 2: COMPILE-TIME (APT) ─────────────────────────────
  @Retention(SOURCE)  ──►  lúc javac  ──►  Processor SINH CODE mới (.java/.class)
  VD: Lombok sinh getter/setter, MapStruct sinh mapper    (nhanh, 0 chi phí runtime)
```

- **Lombok** đọc `@Getter`, `@Builder`... *lúc biên dịch* và **chèn thêm bytecode** — nên runtime không tốn gì, nhưng cũng không "thấy" annotation đó qua reflection (chúng là `SOURCE`).
- **MapStruct** đọc `@Mapper` lúc biên dịch và **sinh ra một class implementation** thật sự.
- Khác biệt cốt lõi: APT chạy **một lần lúc build**, reflection chạy **mỗi lần app khởi động/gọi**.

### 7. Reflection + Annotation = nền tảng của Spring

Ghép Day 14 + Day 15 lại, bạn hiểu được "ma thuật" Spring:

```
1. Spring SCAN classpath, dùng reflection tìm class có @Component/@Service/@Repository
2. Đọc @Autowired trên field/constructor -> biết cần inject gì
3. Đọc @RequestMapping/@GetMapping -> map URL tới method
4. Tạo bean, tự động inject dependency, đăng ký route
```

Tất cả các annotation đó đều `@Retention(RUNTIME)`. Không có gì huyền bí — chỉ là **reflection đọc annotation rồi hành động**. Sau Day 15 bạn hoàn toàn có thể tự viết một "Spring tí hon".

---

## 🔁 Đối chiếu với Laravel/PHP

PHP 8 mới có **Attributes** với cú pháp `#[...]` — gần như tương đương annotation Java. Trước PHP 8, người ta phải "giả lập" metadata bằng **docblock comment** (`@ORM\Column` trong Doctrine), parse bằng regex — rất chắp vá.

| Khía cạnh | Java Annotation | PHP 8 Attribute |
|---|---|---|
| Cú pháp khai báo | `public @interface Min { int value(); }` | `#[Attribute] class Min { public function __construct(public int $value) {} }` |
| Cú pháp sử dụng | `@Min(100)` | `#[Min(100)]` |
| Ví dụ route | `@GetMapping("/bids")` | `#[Route('/bids', methods: ['GET'])]` |
| Lưu trữ | Trong `.class` (nếu RUNTIME) | Trong opcode, lazy |
| Cách đọc | `field.getAnnotation(Min.class)` | `$reflProp->getAttributes(Min::class)` |
| "Khởi tạo" attribute | Element là hằng, không chạy code | `newInstance()` mới gọi constructor PHP |
| "Retention" | SOURCE/CLASS/RUNTIME (3 mức) | Không có khái niệm này — luôn đọc được lúc runtime |
| Validate vị trí | `@Target` (compile-time) | `#[Attribute(Attribute::TARGET_PROPERTY)]` |

```php
// PHP 8 — rất giống Java
#[Attribute(Attribute::TARGET_PROPERTY)]
class Min {
    public function __construct(public int $value) {}
}

class Bid {
    #[Min(100)]
    public int $amount;
}

// đọc bằng Reflection
$ref = new ReflectionProperty(Bid::class, 'amount');
foreach ($ref->getAttributes(Min::class) as $attr) {
    $min = $attr->newInstance();   // gọi constructor -> $min->value === 100
}
```

**Khác biệt tư duy quan trọng nhất:**
- PHP attribute là một **class bình thường**, khi `newInstance()` thì **constructor chạy thật** (có thể có logic). Java annotation element là **hằng số compile-time**, không có constructor, không chạy code.
- Java có **3 mức retention**; nếu quên `RUNTIME` thì reflection mù tịt. PHP không phân biệt — attribute luôn đọc được runtime (vì PHP vốn là ngôn ngữ thông dịch).

> 🧩 Nếu bạn từng dùng `#[Route]` của Symfony hay attribute validation của Laravel, bạn đã hiểu 80% annotation Java rồi. Khác biệt lớn nhất chỉ là cái bẫy `@Retention(RUNTIME)`.

---

## 💻 Thực hành code

### Bước 1 — Tự viết annotation `@Column` với đầy đủ meta-annotation

```java
package com.auction.meta;

import java.lang.annotation.*;

@Documented                            // hiện trong Javadoc
@Retention(RetentionPolicy.RUNTIME)    // BẮT BUỘC để reflection đọc được
@Target(ElementType.FIELD)             // chỉ cho phép dán lên field
public @interface Column {
    String name();                     // bắt buộc: tên cột DB
    boolean nullable() default true;   // mặc định cho null
    int length() default 255;          // độ dài mặc định
}
```

### Bước 2 — Viết các annotation validation: `@NotNull`, `@Min`

```java
package com.auction.validation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface NotNull {
    String message() default "không được để trống";
}

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Min {
    int value();                              // dùng tên value() -> viết @Min(100)
    String message() default "phải >= giá trị tối thiểu";
}
```

### Bước 3 — Quét field, đọc annotation bằng reflection (demo `@Column`)

```java
package com.auction.meta;

import java.lang.reflect.Field;

public class ColumnScanner {

    // Quét mọi field, in ra mapping field -> cột DB dựa trên @Column
    public static void describe(Class<?> entity) {
        System.out.println("Bảng cho class: " + entity.getSimpleName());

        for (Field f : entity.getDeclaredFields()) {
            // chỉ xử lý field có dán @Column
            if (f.isAnnotationPresent(Column.class)) {
                Column col = f.getAnnotation(Column.class);   // lấy annotation
                System.out.printf("  %-14s -> cột '%s' (nullable=%b, length=%d)%n",
                        f.getName(), col.name(), col.nullable(), col.length());
            } else {
                System.out.printf("  %-14s -> (không map DB)%n", f.getName());
            }
        }
    }
}
```

Dùng thử:

```java
class User {
    @Column(name = "user_email", nullable = false, length = 320)
    String email;

    @Column(name = "display_name")     // dùng default nullable=true, length=255
    String name;

    String temporaryToken;             // không có @Column -> bỏ qua
}

// ColumnScanner.describe(User.class);
// Bảng cho class: User
//   email          -> cột 'user_email' (nullable=false, length=320)
//   name           -> cột 'display_name' (nullable=true, length=255)
//   temporaryToken -> (không map DB)
```

> 💡 Đây chính xác là cách **JPA/Hibernate** đọc `@Column`, `@Table`, `@Id` của entity bạn. Không có gì hơn ngoài reflection + annotation.

---

## ⚠️ Bẫy thường gặp (Common Pitfalls)

- **Quên `@Retention(RUNTIME)`.** Mặc định là `CLASS` → reflection trả `null`, annotation "không hoạt động". Lỗi số 1 khi tự viết annotation cho framework.
- **Tưởng annotation tự làm gì đó.** Không. Dán `@Min(100)` lên field mà không có Validator đọc nó thì giá trị âm vẫn lọt. Annotation **luôn cần bộ đọc**.
- **Dùng `getAnnotation` thay vì `getAnnotationsByType` cho `@Repeatable`.** Với annotation lặp lại, `getAnnotation` trả `null` hoặc chỉ container — phải dùng `getAnnotationsByType`.
- **Đặt kiểu element không hợp lệ.** Dùng `Object`, `List`, `Date` trong `@interface` → compile lỗi. Chỉ primitive/String/Class/enum/annotation/mảng.
- **Trông chờ `null` trong element.** Không có. Muốn "trống" thì dùng `default ""` / `default {}` rồi tự kiểm tra rỗng.
- **`@Inherited` không áp dụng cho field/method.** Nó chỉ kế thừa annotation đặt trên **class**. Đừng mong field con tự kế thừa `@Column` của field cha.
- **Lẫn lộn APT với reflection.** Lombok (`@Getter`) là SOURCE/compile-time, đừng cố `getAnnotation(Getter.class)` lúc runtime — không thấy đâu.

---

## 🚀 Liên hệ Spring Boot / Production

- **Stereotype annotation** (`@Component`, `@Service`, `@Repository`, `@Controller`) đều `@Retention(RUNTIME)`. Lúc khởi động, Spring (qua `ClassPathScanningCandidateComponentProvider`) quét, dùng reflection phát hiện chúng và tạo bean.
- **`@Autowired`, `@Value`, `@Qualifier`** được đọc qua reflection để biết inject gì vào đâu.
- **`@GetMapping("/bids")`** thực chất là `@RequestMapping(method=GET)` — Spring đọc element `value()`/`path()` để dựng bảng định tuyến (handler mapping).
- **Bean Validation (`jakarta.validation`)**: `@NotNull`, `@Min`, `@Size`, `@Email`... — Hibernate Validator dùng reflection quét field, đọc annotation, gom lỗi thành `ConstraintViolation`. **Mini project hôm nay chính là phiên bản tí hon của thư viện này.**
- **`@Transactional`** dùng annotation + AOP proxy; Spring đọc annotation để bọc method trong transaction.
- **Custom annotation thực chiến**: bạn sẽ tự viết `@RateLimited`, `@AuditLog`... rồi xử lý bằng AOP — kỹ năng "đọc annotation rồi hành động" hôm nay là nền tảng trực tiếp.

---

## 🏗️ Mini Project — Auction API (Hệ thống đấu giá)

> 🚩 **CHECKPOINT GIỮA KHÓA.** Đây là cột mốc quan trọng: bạn **tổng hợp Reflection (Day 14) + Annotation (Day 15)** thành một sản phẩm hoàn chỉnh. Hãy tự code lại từ đầu, không copy.

**Nhiệm vụ:** Xây một **mini validation framework** dựa trên annotation cho lớp `Bid` (lệnh đặt giá) trong Auction API. Đây là bản thu nhỏ của `jakarta.validation`.

### Lớp dữ liệu `Bid` được "gắn luật" bằng annotation

```java
package com.auction.model;

import com.auction.validation.Min;
import com.auction.validation.NotNull;

public class Bid {

    @NotNull(message = "Người đặt giá là bắt buộc")
    private String bidder;            // ai đặt giá

    @Min(value = 100, message = "Giá đặt tối thiểu là 100")
    private int amount;               // số tiền đặt

    public Bid(String bidder, int amount) {
        this.bidder = bidder;
        this.amount = amount;
    }
}
```

### Validator — trái tim của framework (reflection + annotation)

```java
package com.auction.validation;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class Validator {

    // Quét mọi field của object, áp dụng luật từ annotation, trả danh sách lỗi
    public static List<String> validate(Object obj) {
        List<String> errors = new ArrayList<>();
        Class<?> clazz = obj.getClass();

        for (Field field : clazz.getDeclaredFields()) {
            field.setAccessible(true);        // đọc cả field private
            try {
                Object value = field.get(obj);

                // Luật @NotNull
                if (field.isAnnotationPresent(NotNull.class)) {
                    NotNull rule = field.getAnnotation(NotNull.class);
                    if (value == null) {
                        errors.add(field.getName() + ": " + rule.message());
                    }
                }

                // Luật @Min (chỉ áp dụng cho số)
                if (field.isAnnotationPresent(Min.class)) {
                    Min rule = field.getAnnotation(Min.class);
                    if (value instanceof Number num && num.longValue() < rule.value()) {
                        errors.add(field.getName() + ": " + rule.message()
                                + " (hiện tại = " + num + ")");
                    }
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Không đọc được field: " + field.getName(), e);
            }
        }
        return errors;
    }

    // Tiện ích: ném exception nếu có lỗi (giống @Valid của Spring)
    public static void validateOrThrow(Object obj) {
        List<String> errors = validate(obj);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Dữ liệu không hợp lệ:\n - "
                    + String.join("\n - ", errors));
        }
    }
}
```

### Chạy thử

```java
package com.auction;

import com.auction.model.Bid;
import com.auction.validation.Validator;

public class Day15App {
    public static void main(String[] args) {
        Bid ok       = new Bid("alice", 500);   // hợp lệ
        Bid badAmt   = new Bid("bob", 50);      // amount < 100
        Bid noBidder = new Bid(null, 200);      // thiếu bidder

        System.out.println("ok      -> " + Validator.validate(ok));
        System.out.println("badAmt  -> " + Validator.validate(badAmt));
        System.out.println("noBidder-> " + Validator.validate(noBidder));
        // ok      -> []
        // badAmt  -> [amount: Giá đặt tối thiểu là 100 (hiện tại = 50)]
        // noBidder-> [bidder: Người đặt giá là bắt buộc]
    }
}
```

**Tiêu chí hoàn thành:**
1. `validate()` quét **mọi field** bằng reflection, đọc `@NotNull` / `@Min` qua `getAnnotation()`.
2. Trả về **danh sách lỗi** (rỗng nếu hợp lệ), lỗi lấy thông điệp từ element `message()`.
3. Thêm `validateOrThrow()` mô phỏng hành vi `@Valid` của Spring.
4. **Nâng cao (tự làm):** thêm annotation `@Max`, `@Size(min, max)` cho String; cho `Min` hỗ trợ `@Repeatable`; gom field-level + class-level.
5. Ghi `notes/day-15.md`: giải thích vì sao quên `@Retention(RUNTIME)` thì Validator "im lặng" không báo lỗi.

---

## ❓ Câu hỏi phỏng vấn (có đáp án)

### Mức Junior/Mid

**Q1: Annotation trong Java là gì? Nó có tự thay đổi logic không?**
> **Đáp:** Annotation là **metadata** gắn lên class/method/field... Nó **không** tự thay đổi logic. Tự thân annotation vô nghĩa; phải có ai đó (compiler, annotation processor, hoặc framework qua reflection) **đọc nó ra rồi hành động**. Ví dụ `@Min(100)` chỉ là ghi chú "tối thiểu 100", phải có Validator đọc mới có tác dụng.

**Q2: Phân biệt `@Override` và `@Deprecated`.**
> **Đáp:** `@Override` báo compiler rằng method *đang ghi đè* method cha — nếu signature sai sẽ compile lỗi, giúp tránh bug "tưởng override mà thật ra tạo method mới". `@Deprecated` đánh dấu API lỗi thời, không nên dùng — IDE gạch ngang, compiler cảnh báo, nhưng vẫn chạy được.

**Q3: 3 mức `@Retention` khác nhau thế nào? Mức nào reflection đọc được?**
> **Đáp:** `SOURCE` chỉ tồn tại trong `.java`, javac đọc xong là vứt (VD `@Override`, Lombok). `CLASS` (mặc định) được ghi vào `.class` nhưng JVM không nạp runtime. `RUNTIME` ghi vào `.class` **và** JVM nạp lên runtime. **Chỉ `RUNTIME`** mới đọc được bằng reflection — đây là mức Spring/JPA/Jackson dùng.

**Q4: Element `value()` đặc biệt ở chỗ nào?**
> **Đáp:** Nếu annotation chỉ có một element tên `value()` (hoặc các element khác đều có `default`), khi dùng được phép **bỏ tên**: viết `@Min(100)` thay vì `@Min(value = 100)`. Nhờ vậy mới có cú pháp gọn như `@RequestMapping("/api")`.

### Mức Senior

**Q5: Vì sao annotation tự viết của bạn "không hoạt động" với Spring/reflection? Cách sửa?**
> **Đáp:** Nguyên nhân phổ biến nhất là **quên `@Retention(RetentionPolicy.RUNTIME)`** — mặc định là `CLASS`, nên JVM không nạp annotation lên runtime và `getAnnotation()` trả `null`. Sửa bằng cách khai báo `@Retention(RUNTIME)`. Ngoài ra cần `@Target` đúng vị trí và phải có **bộ đọc** (reflection hoặc AOP/processor) thực sự xử lý annotation đó.

**Q6: Phân biệt xử lý annotation lúc *biên dịch* (APT) và lúc *runtime* (reflection). Lombok thuộc loại nào?**
> **Đáp:** **APT** (`javax.annotation.processing`) chạy *trong lúc javac*, đọc annotation `SOURCE`, có thể **sinh code/bytecode mới** — chạy một lần lúc build, không tốn chi phí runtime (VD Lombok, MapStruct). **Reflection** đọc annotation `RUNTIME` *mỗi lần app chạy*, linh hoạt nhưng tốn hiệu năng hơn (VD Spring quét `@Service`). **Lombok thuộc APT/compile-time** — nó chèn getter/setter vào bytecode lúc biên dịch, nên runtime không "thấy" annotation Lombok qua reflection.

**Q7: Giải thích cách Spring biến `@Service` và `@Autowired` thành "ma thuật". Liên hệ Reflection + Annotation.**
> **Đáp:** Cả hai đều `@Retention(RUNTIME)`. Lúc khởi động, Spring **quét classpath**, dùng reflection tìm class mang `@Service`/`@Component`, tạo bean và đăng ký vào container. Với `@Autowired`, Spring đọc annotation trên field/constructor (qua reflection) để biết cần inject dependency nào, rồi `field.set(...)` hoặc gọi constructor với bean phù hợp. Không có gì huyền bí — chỉ là **reflection đọc annotation runtime rồi hành động**, đúng như mini validation framework hôm nay nhưng ở quy mô lớn.

**Q8: Element annotation cho phép kiểu trả về nào? Có thể là `null` không?**
> **Đáp:** Chỉ cho phép: **primitive, String, Class, enum, annotation khác, và mảng** của các loại đó — vì chúng phải là hằng compile-time. **Không** cho phép `Object`, `List`, kiểu tùy biến. Element **không thể** mang giá trị `null`; muốn biểu thị "không có" thì dùng `default ""` hoặc `default {}` rồi tự kiểm tra rỗng trong bộ đọc.

---

## ✅ Checklist hoàn thành

- [ ] Giải thích được "annotation là metadata, không tự thay đổi logic"
- [ ] Phân biệt rõ 3 mức `@Retention` và biết chỉ `RUNTIME` mới reflection được
- [ ] Biết dùng `@Override`, `@Deprecated`, `@FunctionalInterface` đúng lúc
- [ ] Tự viết được annotation có element, `default`, và `value()`
- [ ] Đọc annotation runtime bằng `isAnnotationPresent` / `getAnnotation`
- [ ] Phân biệt được APT (Lombok) vs reflection (Spring)
- [ ] Hoàn thành mini validation framework cho `Bid` (checkpoint giữa khóa)
- [ ] Trả lời được 8 câu phỏng vấn ở trên
- [ ] Tạo git commit cho ngày học hôm nay

---

## 📚 Tài liệu tham khảo

- Oracle Java Tutorials — "Annotations" (Lesson trong The Java Language)
- Java Language Specification (JLS) — Chapter 9.6 "Annotation Types"
- Baeldung — "Java Custom Annotations" & "A Guide to the Reflection API"
- Jakarta Bean Validation spec — đọc lướt các constraint `@NotNull`, `@Min`, `@Size`
- PHP Manual — "Attributes overview" (so sánh với annotation Java)
- Spring Framework Reference — "Annotation-based Container Configuration"
