class Animal {
    static {
        System.out.println("1. static block - Animal (cha)");
    }
    {
        System.out.println("3. instance init block - Animal (cha)");
    }
    Animal() {
        System.out.println("4. constructor - Animal (cha)");
    }
}

class Dog extends Animal {
    static {
        System.out.println("2. static block - Dog (con)");
    }
    {
        System.out.println("5. instance init block - Dog (con)");
    }
    Dog() {
        // super() được chạy ngầm định ở đây
        System.out.println("6. constructor - Dog (con)");
    }
}

public class InitOrderDemo {
    public static void main(String[] args) {
        System.out.println("=== new Dog() lần 1 ===");
        new Dog();
        System.out.println("=== new Dog() lần 2 ===");
        new Dog(); // static block sẽ không chạy lại
    }
}
