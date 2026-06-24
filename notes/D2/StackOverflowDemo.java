public class StackOverflowDemo {

    static int recurse(int depth) {
        System.out.println("Độ sâu: " + depth);
        return recurse(depth + 1);
    }

    public static void main(String[] args) {
        try {
            recurse(1);
        } catch (StackOverflowError e) {
            System.out.println(">>> Đã tràn Stack! JVM ném StackOverflowError.");
        }
    }
}
