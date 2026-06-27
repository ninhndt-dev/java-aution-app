import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public final class Bid {
    private final String bidder;       // người đặt giá
    private final BigDecimal amount;   // số tiền
    private final Instant placedAt;    // thời điểm đặt

    public Bid(String bidder, BigDecimal amount) {
        this.bidder = Objects.requireNonNull(bidder, "Người đặt giá không được null");
        Objects.requireNonNull(amount, "Số tiền không được null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Số tiền bid phải > 0");
        }
        this.amount = amount;
        this.placedAt = Instant.now();
    }

    public String getBidder()    { return bidder; }
    public BigDecimal getAmount() { return amount; }
    public Instant getPlacedAt()  { return placedAt; }
}
