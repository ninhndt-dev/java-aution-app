import java.lang.ref.WeakReference;

public class WeakRefDemo {
    public static void main(String[] args) {
        // strong reference: object chắc chắn sống
        Object strong = new Object();
        // weak reference bọc cùng object đó
        WeakReference<Object> weak = new WeakReference<>(strong);

        System.out.println("Trước khi bỏ strong, weak.get() = " + weak.get()); // != null

        // Bỏ strong reference -> chỉ còn weak trỏ tới object
        strong = null;

        // Gợi ý JVM chạy GC
        System.gc();

        // Cho GC chút thời gian dọn dẹp
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {
        }

        Object afterGc = weak.get();
        if (afterGc == null) {
            System.out.println("Sau GC, weak.get() = null  -> object ĐÃ bị thu hồi");
        } else {
            System.out.println("Sau GC, object vẫn còn (GC chưa chạy xong)");
        }
    }
}
