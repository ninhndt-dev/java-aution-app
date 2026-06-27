import java.math.BigDecimal;

public class AuctionApp {
    public static void main(String[] args) {
        System.out.println("=== Khởi chạy Auction API (Day 04) ===");

        // Tạo một phiên đấu giá
        Auction tranhCo = new Auction("Tranh cổ thế kỷ 18", new BigDecimal("1000000"));

        // Ghi nhận các lượt bid bằng cả 2 cách
        Bid bid1 = new Bid("An", new BigDecimal("1200000"));
        Bid bid2 = new Bid("Bình", new BigDecimal("1500000"));

        // 1. Cách gây leak (Leaky version)
        BidHistory.recordLeaky(bid1);
        BidHistory.recordLeaky(bid2);

        // 2. Cách an toàn (Safe version)
        BidHistory.recordSafe(tranhCo.getTitle(), bid1);
        BidHistory.recordSafe(tranhCo.getTitle(), bid2);
        
        tranhCo.placeBid(bid1);
        tranhCo.placeBid(bid2);

        System.out.println("Phiên: " + tranhCo.getTitle());
        System.out.println("Số lượt bid trong static leak list: " + BidHistory.getLeakySize());
        System.out.println("Số lượt bid trong active Map: " + BidHistory.getSafeHistory(tranhCo.getTitle()).size());

        // Đóng phiên đấu giá
        tranhCo.close();
        
        // Tiến hành dọn dẹp để phòng tránh rò rỉ bộ nhớ (Memory Leak)
        BidHistory.clearHistoryForClosedAuction(tranhCo.getTitle());
        
        // Lúc này các đối tượng Bid thuộc phiên Tranh cổ không còn bất kỳ đường dẫn 
        // tham chiếu nào tới GC Root (unreachable), GC sẽ thu hồi bộ nhớ ở lần quét tiếp theo.
    }
}
