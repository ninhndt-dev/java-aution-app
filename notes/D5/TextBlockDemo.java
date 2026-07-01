// File: TextBlockDemo.java  (Java 21)
public class TextBlockDemo {
    public static void main(String[] args) {
        String sql = """
                SELECT id, name, current_price
                FROM auctions
                WHERE status = 'OPEN'
                ORDER BY current_price DESC
                """;
        System.out.println(sql);   // giữ nguyên xuống dòng, không cần \n hay escape
    }
}
