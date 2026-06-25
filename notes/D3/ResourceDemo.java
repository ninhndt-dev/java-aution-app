public class ResourceDemo {
    // Tài nguyên giả lập thực thi AutoCloseable
    static class Connection implements AutoCloseable {
        Connection() {
            System.out.println("Mở connection");
        }
        void query() {
            System.out.println("Đang query...");
        }
        @Override
        public void close() {
            System.out.println("Đóng connection (tự động)");
        }
    }

    public static void main(String[] args) {
        // Tự động đóng connection khi ra khỏi khối try
        try (Connection conn = new Connection()) {
            conn.query();
        }
        System.out.println("Đã ra khỏi khối try — connection chắc chắn đã đóng");
    }
}
