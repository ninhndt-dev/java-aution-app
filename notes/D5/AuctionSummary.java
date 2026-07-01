// File: AuctionSummary.java  (Java 21)
public class AuctionSummary {

    // Dữ liệu giả lập
    private final String itemName  = "Đồng hồ cổ Thuỵ Sĩ";
    private final long   currentPrice = 1_500;     // đơn vị: nghìn đồng
    private final int    totalBids   = 7;
    private final String topBidder   = "ninh.dev";

    // Cách 1: StringBuilder — linh hoạt, thêm dòng có điều kiện dễ
    public String buildSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== TÓM TẮT PHIÊN ĐẤU GIÁ =====\n");
        sb.append("Sản phẩm     : ").append(itemName).append('\n');
        sb.append("Giá hiện tại : ").append(currentPrice).append(" nghìn đ\n");
        sb.append("Số lượt bid  : ").append(totalBids).append('\n');
        if (totalBids > 0) {
            sb.append("Đang dẫn đầu : ").append(topBidder).append('\n');
        } else {
            sb.append("Đang dẫn đầu : (chưa có lượt bid nào)\n");
        }
        sb.append("=================================");
        return sb.toString();   // chỉ tạo String 1 lần ở cuối
    }

    // Cách 2: Text Block + formatted — đẹp cho khuôn cố định
    public String buildSummaryTextBlock() {
        return """
                ===== TÓM TẮT PHIÊN ĐẤU GIÁ =====
                Sản phẩm     : %s
                Giá hiện tại : %d nghìn đ
                Số lượt bid  : %d
                Đang dẫn đầu : %s
                =================================""".formatted(
                itemName, currentPrice, totalBids,
                totalBids > 0 ? topBidder : "(chưa có lượt bid nào)");
    }

    public static void main(String[] args) {
        AuctionSummary a = new AuctionSummary();
        System.out.println(a.buildSummary());
        System.out.println();
        System.out.println(a.buildSummaryTextBlock());
    }
}
