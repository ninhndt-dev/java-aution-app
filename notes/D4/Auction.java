import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Auction {
    private final String title;
    private final BigDecimal startingPrice;
    private final List<Bid> bids = new ArrayList<>();
    private boolean closed = false;

    public Auction(String title, BigDecimal startingPrice) {
        this.title = Objects.requireNonNull(title, "Tên phiên không được null");
        Objects.requireNonNull(startingPrice, "Giá khởi điểm không được null");
        if (startingPrice.signum() <= 0) {
            throw new IllegalArgumentException("Giá khởi điểm phải > 0");
        }
        this.startingPrice = startingPrice;
    }

    public void placeBid(Bid bid) {
        Objects.requireNonNull(bid, "Bid không được null");
        if (closed) {
            throw new IllegalStateException("Phiên đấu giá đã kết thúc, không thể đặt giá!");
        }
        BigDecimal current = highestAmount();
        if (bid.getAmount().compareTo(current) <= 0) {
            throw new IllegalArgumentException("Giá bid phải cao hơn giá hiện tại: " + current);
        }
        bids.add(bid);
    }

    public void close() {
        this.closed = true;
    }

    public boolean isClosed() {
        return closed;
    }

    private BigDecimal highestAmount() {
        return bids.isEmpty()
            ? startingPrice
            : bids.get(bids.size() - 1).getAmount();
    }

    public String getTitle()             { return title; }
    public BigDecimal getStartingPrice() { return startingPrice; }
    public List<Bid> getBids()           { return List.copyOf(bids); }
}
