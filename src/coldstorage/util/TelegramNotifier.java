package coldstorage.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Gửi thông báo đẩy THẬT qua Telegram Bot API, để người quản lý biết cảnh báo ngay cả khi không mở
 * màn hình Server. Chỉ dùng thư viện có sẵn trong JDK (java.net.http.HttpClient, từ Java 11), không cần
 * thêm jar ngoài.
 *
 * Cách lấy bot token + chat id (làm 1 lần):
 * 1. Trên Telegram, chat với @BotFather -> gõ /newbot -> đặt tên -> nhận về 1 chuỗi token dạng
 *    "123456789:AAExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".
 * 2. Bắt đầu chat với bot vừa tạo (bấm Start), hoặc thêm bot vào 1 nhóm.
 * 3. Mở trình duyệt: https://api.telegram.org/bot<TOKEN>/getUpdates (sau khi đã gửi ít nhất 1 tin nhắn
 *    cho bot), tìm giá trị "chat":{"id": ...} trong kết quả JSON - đó là chat id cần điền.
 * 4. Điền cả 2 giá trị vào AdminPanel (mục "Thông báo Telegram") rồi bật "Kích hoạt".
 *
 * Gửi BẤT ĐỒNG BỘ (sendAsync): nếu Telegram chậm/lỗi mạng, việc xử lý gói tin cảm biến (đang chạy trên
 * luồng của ThreadPoolExecutor) KHÔNG bị chặn chờ; lỗi chỉ được log ra console, không làm rớt gói tin.
 */
public final class TelegramNotifier {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private TelegramNotifier() {
    }

    /** Gửi 1 tin nhắn văn bản tới chat đã cấu hình. Không làm gì nếu Telegram chưa được bật/cấu hình đủ. */
    public static void send(String message) {
        if (!ConfigUtil.isTelegramEnabled()) return;
        sendWith(ConfigUtil.getTelegramBotToken(), ConfigUtil.getTelegramChatId(), message);
    }

    /** Gửi bằng token/chat id chỉ định, KHÔNG đọc/ghi cấu hình và không phụ thuộc trạng thái "Kích hoạt". */
    private static void sendWith(String token, String chatId, String message) {
        token = token == null ? "" : token.trim();
        chatId = chatId == null ? "" : chatId.trim();
        if (token.isEmpty() || chatId.isEmpty()) {
            System.err.println("[Telegram] Chưa điền đủ bot token / chat id, bỏ qua gửi.");
            return;
        }

        String url = "https://api.telegram.org/bot" + token + "/sendMessage";
        String body = "chat_id=" + urlEncode(chatId) + "&text=" + urlEncode(message);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> future =
                CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        future.thenAccept(response -> {
            if (response.statusCode() != 200) {
                System.err.println("[Telegram] Gửi thất bại (HTTP " + response.statusCode() + "): " + response.body());
            }
        }).exceptionally(ex -> {
            System.err.println("[Telegram] Lỗi khi gửi (kiểm tra kết nối mạng / bot token): " + ex.getMessage());
            return null;
        });
    }

    /** Gửi 1 tin nhắn thử để xác nhận cấu hình đúng (gọi từ AdminPanel khi bấm "Gửi thử") */
    public static void sendTest(String token, String chatId) {
        sendWith(token, chatId, "✅ Kết nối Telegram thành công! Hệ thống giám sát kho lạnh sẽ gửi cảnh báo qua đây.");
    }

    /** Bản cũ: gửi thử theo cấu hình đã lưu (vẫn phải đang bật) */
    public static void sendTest() {
        send("✅ Kết nối Telegram thành công! Hệ thống giám sát kho lạnh sẽ gửi cảnh báo qua đây.");
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s; // không bao giờ xảy ra với UTF-8, nhưng URLEncoder khai báo checked exception
        }
    }
}
