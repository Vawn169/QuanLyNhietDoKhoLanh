package coldstorage.sensor;

/**
 * Đại diện cho "hành vi" thay đổi nhiệt độ của 1 cảm biến qua từng lần đo.
 * Tách riêng khỏi SensorClient (vốn chỉ nên lo việc đóng gói + gửi UDP) theo nguyên tắc
 * Single Responsibility - cho phép cắm (plug) nhiều kiểu mô phỏng khác nhau mà không cần
 * sửa logic mạng, và dễ dàng viết unit test riêng cho từng hành vi.
 */
public interface TemperatureBehavior {

    /**
     * Tính nhiệt độ mới cho lần đo tiếp theo.
     * @param currentTemp nhiệt độ hiện tại
     * @param now thời điểm hiện tại (System.currentTimeMillis())
     * @return nhiệt độ sau khi áp dụng hành vi này
     */
    double nextTemperature(double currentTemp, long now);

    /**
     * Mô tả ngắn về trạng thái đặc biệt (nếu có) sau lần gọi nextTemperature() gần nhất,
     * ví dụ "đang hồi phục". Trả về null nếu không có gì đặc biệt cần thông báo.
     * Có cài đặt mặc định để các hành vi đơn giản không bắt buộc phải override.
     */
    default String getStatusNote() {
        return null;
    }
}