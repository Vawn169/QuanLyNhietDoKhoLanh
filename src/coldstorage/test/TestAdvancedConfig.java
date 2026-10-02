package coldstorage.test;

import coldstorage.util.ConfigUtil;

import java.util.HashMap;
import java.util.Map;

public class TestAdvancedConfig {
    public static void main(String[] args) {
        System.out.println("=== TEST ADVANCED CONFIG ===");
        System.out.println("Trước khi thay đổi:");
        System.out.println("alert.remind.minutes = " + ConfigUtil.getAlertRemindMinutes());
        
        // Thay đổi thông qua ConfigUtil
        Map<String, String> configs = new HashMap<>();
        configs.put("alert.remind.minutes", "15.0");
        ConfigUtil.setAdvancedConfigs(configs);
        
        System.out.println("\nSau khi lưu (cùng tiến trình):");
        System.out.println("alert.remind.minutes = " + ConfigUtil.getAlertRemindMinutes());
        
        // Giả lập tiến trình khác (hoặc load lại để chắc chắn)
        ConfigUtil.reload();
        
        System.out.println("\nSau khi gọi ConfigUtil.reload() (giả lập tiến trình khác đọc lại file):");
        System.out.println("alert.remind.minutes = " + ConfigUtil.getAlertRemindMinutes());
        
        // Khôi phục mặc định cho ứng dụng chạy bình thường
        configs.put("alert.remind.minutes", "5.0");
        ConfigUtil.setAdvancedConfigs(configs);
        
        System.out.println("\nĐã khôi phục mặc định. Hoàn tất Test.");
    }
}
