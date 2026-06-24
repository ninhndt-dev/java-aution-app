public class MemoryDemo {

    static class Point {
        int x;
        int y;
        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public static void main(String[] args) {
        int count = 10;
        Point p1 = new Point(1, 2);
        Point p2 = p1;
        p2.x = 999;
        System.out.println("p1.x = " + p1.x);

        int a = count;
        a = 50;
        System.out.println("count = " + count);
    }
}
