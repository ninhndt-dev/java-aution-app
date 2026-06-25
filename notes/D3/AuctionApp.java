import java.math.BigDecimal;

public class AuctionApp {
    public static void main(String[] args) {
        Auction auction = new Auction("Tranh cổ", new BigDecimal("1000000"));

        try {
            auction.placeBid(new Bid("An", new BigDecimal("1200000")));
            auction.placeBid(new Bid("Bình", new BigDecimal("1500000")));
            
            // Lượt đặt giá không hợp lệ (thấp hơn giá cao nhất hiện tại) -> Sẽ ném Exception
            // auction.placeBid(new Bid("Cường", new BigDecimal("1400000")));
        } catch (IllegalArgumentException e) {
            System.out.println("Lỗi đặt giá: " + e.getMessage());
        }

        System.out.println("Phiên đấu giá: " + auction.getTitle());
        System.out.println("Giá khởi điểm: " + auction.getStartingPrice() + " VNĐ");
        System.out.println("Số lượt đấu giá: " + auction.getBids().size());
        
        for (Bid bid : auction.getBids()) {
            System.out.println("- Người bid: " + bid.getBidder() 
                + ", số tiền: " + bid.getAmount() 
                + " VNĐ, thời gian: " + bid.getPlacedAt());
        }
    }
}
