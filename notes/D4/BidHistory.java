import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BidHistory {
    
    // ==========================================
    // ⚠️ THIẾT KẾ RÒ RỈ BỘ NHỚ (MEMORY LEAK VERSION)
    // ==========================================
    // Biến static là GC Root, sống suốt vòng đời ứng dụng.
    // Nếu cứ lưu tất cả các bid của mọi phiên đấu giá (kể cả phiên đã đóng) vào đây,
    // Heap RAM sẽ phình to liên tục dẫn đến OutOfMemoryError.
    private static final List<Bid> LEAKY_ALL_BIDS = new ArrayList<>();

    public static void recordLeaky(Bid bid) {
        LEAKY_ALL_BIDS.add(bid); // Chỉ thêm vào mà không bao giờ xóa
    }

    public static int getLeakySize() {
        return LEAKY_ALL_BIDS.size();
    }


    // ==========================================
    // 🛡️ THIẾT KẾ AN TOÀN (SAFE VERSION - CLEANUP STRATEGY)
    // ==========================================
    // Sử dụng Map để lưu trữ Bid theo từng phiên đấu giá.
    // Khi một phiên đấu giá kết thúc, ta chủ động xóa (cắt tham chiếu) danh sách bid 
    // của phiên đó để GC có thể thu hồi bộ nhớ của chúng trên Heap.
    private static final Map<String, List<Bid>> ACTIVE_BIDS = new ConcurrentHashMap<>();

    public static void recordSafe(String auctionTitle, Bid bid) {
        ACTIVE_BIDS.computeIfAbsent(auctionTitle, k -> new ArrayList<>()).add(bid);
    }

    public static List<Bid> getSafeHistory(String auctionTitle) {
        List<Bid> bids = ACTIVE_BIDS.get(auctionTitle);
        return bids != null ? List.copyOf(bids) : List.of();
    }

    /**
     * Hàm dọn dẹp quan trọng để tránh Memory Leak:
     * Cắt đứt tham chiếu của các Bid thuộc phiên đấu giá đã kết thúc.
     */
    public static void clearHistoryForClosedAuction(String auctionTitle) {
        ACTIVE_BIDS.remove(auctionTitle);
        System.out.println("[GC Safe] Đã xóa lịch sử đặt giá của '" + auctionTitle + "' khỏi RAM để GC thu hồi.");
    }
}
