public class AuctionApp {
    public static void main(String[] args) {
        // 'count' là biến local primitive -> nằm ở STACK (frame main)
        int count = 0;

        // 'auction' là REFERENCE trên STACK;
        // object Auction (kèm field currentPrice và List<Bid> rỗng) -> nằm ở HEAP
        Auction auction = new Auction("Tranh cổ", 1_000_000);

        // Mỗi 'new Bid(...)' tạo một object Bid trên HEAP;
        // biến tạm trong vòng lặp là reference trên Stack, trỏ vào Heap
        auction.placeBid(new Bid("An", 1_200_000));
        auction.placeBid(new Bid("Bình", 1_500_000));
        count = auction.bidCount();        // gán giá trị primitive vào 'count' trên Stack

        System.out.println("Số lượt đấu giá: " + count);   // 2
    }
}
