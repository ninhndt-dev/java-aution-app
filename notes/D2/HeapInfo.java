public class HeapInfo {
    public static void main(String[] args) {
        Runtime rt = Runtime.getRuntime();
        long mb = 1024 * 1024;
        System.out.println("Heap max (-Xmx): " + rt.maxMemory() / mb + " MB");
        System.out.println("Heap total:      " + rt.totalMemory() / mb + " MB");
        System.out.println("Heap free:       " + rt.freeMemory() / mb + " MB");
    }
}
