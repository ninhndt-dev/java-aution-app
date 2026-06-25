import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Auction {
    private final String title;             // tên phiên đấu giá (bất biến)
    private final BigDecimal startingPrice; // giá khởi điểm (bất biến)
    private final List<Bid> bids = new ArrayList<>();

    public Auction(String title, BigDecimal startingPrice) {
        this.title = Objects.requireNonNull(title, "Tên phiên không được null");
        Objects.requireNonNull(startingPrice, "Giá khởi điểm không được null");
        if (startingPrice.signum() <= 0) {       // giá khởi điểm > 0
            throw new IllegalArgumentException("Giá khởi điểm phải > 0");
        }
        this.startingPrice = startingPrice;
    }

    /** Thêm một lượt đấu giá hợp lệ: phải cao hơn giá khởi điểm và giá cao nhất hiện tại. */
    public void placeBid(Bid bid) {
        Objects.requireNonNull(bid, "Bid không được null");
        BigDecimal current = highestAmount();
        if (bid.getAmount().compareTo(current) <= 0) {
            throw new IllegalArgumentException("Giá bid phải cao hơn giá hiện tại: " + current);
        }
        bids.add(bid);
    }

    private BigDecimal highestAmount() {
        return bids.isEmpty()
            ? startingPrice
            : bids.get(bids.size() - 1).getAmount();
    }

    public String getTitle()             { return title; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public List<Bid> getBids()           { return List.copyOf(bids); } // trả bản copy bất biến
}
