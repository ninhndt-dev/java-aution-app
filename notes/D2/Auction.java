import java.util.ArrayList;
import java.util.List;

public class Auction {
    private final String itemName;        // reference -> String trong Heap
    private double currentPrice;          // primitive field -> nằm trong object (Heap)
    private final List<Bid> bids;         // List object cấp phát trên Heap

    public Auction(String itemName, double startPrice) {
        this.itemName = itemName;
        this.currentPrice = startPrice;
        this.bids = new ArrayList<>();     // List object cấp phát trên Heap
    }

    public void placeBid(Bid bid) {        // 'bid' là tham số reference -> ở Stack của frame này
        if (bid.amount() > currentPrice) { // 'bid.amount()' đọc field của object trên Heap
            this.currentPrice = bid.amount();
            this.bids.add(bid);            // thêm reference Bid vào List trên Heap
        }
    }

    public int bidCount() {
        return bids.size();
    }
}
