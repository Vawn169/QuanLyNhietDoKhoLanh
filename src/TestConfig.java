import coldstorage.util.ConfigUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Properties;

public class TestConfig {
    public static void main(String[] args) throws Exception {
        System.out.println("Starting Config Test...");
        
        // Ensure ConfigUtil is loaded
        ConfigUtil.reload();
        
        // Set a value manually in the properties file to simulate another process doing it
        File f = new File("config.properties");
        Properties p = new Properties();
        if (f.exists()) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                p.load(in);
            }
        }
        
        p.setProperty("alert.remind.minutes", "15.0");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, null);
        }
        
        // Wait a bit, then reload
        System.out.println("Reloading config...");
        ConfigUtil.reload();
        
        // Check value
        double remindMins = ConfigUtil.getAlertRemindMinutes();
        System.out.println("alert.remind.minutes: " + remindMins);
        
        if (Math.abs(remindMins - 15.0) < 0.01) {
            System.out.println("=> PASS: Value updated successfully.");
        } else {
            System.out.println("=> FAIL: Expected 15.0, got " + remindMins);
        }
        
        // Restore original for tests
        p.setProperty("alert.remind.minutes", "5.0");
        try (FileOutputStream out = new FileOutputStream(f)) {
            p.store(out, null);
        }
    }
}
