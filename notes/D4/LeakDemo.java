import java.util.ArrayList;
import java.util.List;

public class LeakDemo {
    // static => GC ROOT, sống mãi. Cứ add mà KHÔNG remove => leak.
    private static final List<byte[]> LEAK = new ArrayList<>();

    public static void main(String[] args) {
        int mb = 0;
        try {
            while (true) {
                LEAK.add(new byte[1024 * 1024]); // giữ tham chiếu mãi mãi
                mb++;
                if (mb % 50 == 0) {
                    System.out.println("Đang giữ " + mb + " MB (không bao giờ dọn được)...");
                }
            }
        } catch (OutOfMemoryError e) {
            // Bắt để in minh hoạ; thực tế KHÔNG nên catch OOM để chạy tiếp.
            System.out.println("💥 OutOfMemoryError sau khi giữ ~" + mb + " MB");
            System.out.println("Lý do: object reachable từ static LEAK => GC không thu hồi được.");
        }
    }
}
