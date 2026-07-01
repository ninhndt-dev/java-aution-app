// File: StringConcatBenchmark.java  (Java 21)
public class StringConcatBenchmark {

    static final int N = 100_000;   // 100 nghìn vòng

    public static void main(String[] args) {
        // --- Cách SAI: nối bằng += trong vòng lặp -> O(n^2) ---
        long t1 = System.nanoTime();
        String s = "";
        for (int i = 0; i < N; i++) {
            s += i;   // mỗi vòng tạo String mới = copy lại toàn bộ phần trước
        }
        long t2 = System.nanoTime();
        System.out.printf("String +=        : %,d ms (độ dài cuối = %d)%n",
                (t2 - t1) / 1_000_000, s.length());

        // --- Cách ĐÚNG: dùng StringBuilder -> ~O(n) ---
        long t3 = System.nanoTime();
        StringBuilder sb = new StringBuilder(); // class hỗ trợ nối chuỗi hiệu suất cao
        for (int i = 0; i < N; i++) {
            sb.append(i);   // append vào buffer khả biến, không copy lại từ đầu
        }
        String s2 = sb.toString();
        long t4 = System.nanoTime();
        System.out.printf("StringBuilder    : %,d ms (độ dài cuối = %d)%n",
                (t4 - t3) / 1_000_000, s2.length());

        // Kiểm chứng hai cách cho cùng kết quả
        System.out.println("Hai kết quả giống nhau? " + s.equals(s2));
    }
}
