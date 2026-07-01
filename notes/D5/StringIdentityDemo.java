// File: StringIdentityDemo.java  (Java 21)
public class StringIdentityDemo {
    public static void main(String[] args) {
        // --- Nhóm literal: dùng chung object trong String Pool ---
        String a = "abc";
        String b = "abc";                 // lấy lại từ pool -> cùng object với a
        System.out.println("a == b          : " + (a == b));          // true  (cùng reference pool)
        System.out.println("a.equals(b)     : " + a.equals(b));       // true  (so sánh nội dung chuỗi)

        // --- new String: luôn tạo object MỚI ngoài pool ---
        String c = new String("abc");     // object riêng trên heap
        System.out.println("a == c          : " + (a == c));          // false (khác reference)
        System.out.println("a.equals(c)     : " + a.equals(c));       // true  (cùng nội dung)

        // --- intern(): kéo nội dung về object trong pool ---
        String d = c.intern();            // d trỏ tới object "abc" TRONG pool (chính là a)
        System.out.println("a == d          : " + (a == d));          // true  (cùng object pool)
        System.out.println("c == d          : " + (c == d));          // false (c vẫn là object heap riêng)

        // --- Ghép literal là hằng số: compiler gộp tại thời điểm biên dịch ---
        String e = "ab" + "c";            // "ab"+"c" đều là hằng -> compiler tính ra "abc" và đặt vào pool
        System.out.println("a == e          : " + (a == e));          // true  (compile-time constant folding)

        // --- Ghép có biến: tính lúc chạy -> object MỚI, không vào pool tự động ---
        String pre = "ab";
        String f = pre + "c";             // pre là biến -> ghép lúc runtime -> object mới ngoài pool
        System.out.println("a == f          : " + (a == f));          // false
        System.out.println("a == f.intern() : " + (a == f.intern())); // true (intern kéo về pool)
    }
}
