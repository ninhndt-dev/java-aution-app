public class GcDemo {
    public static void main(String[] args) {
        // Mỗi vòng tạo ~1MB rồi VỨT đi (không giữ tham chiếu) => thành rác ngay.
        // GC sẽ phải dọn liên tục ở Young Gen (Minor GC).
        for (int i = 0; i < 50_000; i++) {
            byte[] tam = new byte[1024 * 1024]; // khởi tạo mảng 1MB
            tam[0] = 1;                         // chạm vào để JVM không tối ưu bỏ
            // Hết vòng lặp: 'tam' ra khỏi scope => unreachable => trở thành rác
            if (i % 10_000 == 0) {
                System.out.println("Đã tạo " + i + " object 1MB...");
            }
        }
        System.out.println("Xong. Quan sát GC log phía trên.");
    }
}
