package coldstorage.sensor;

/**
 * Chạy file này để khởi động nhiều cảm biến giả lập (mô phỏng nhiều kho lạnh)
 * gửi dữ liệu song song tới Server bằng UDP. Mỗi cảm biến chạy trên 1 Thread riêng.
 */
public class SensorLauncher {

    public static void main(String[] args) {
        String serverHost = "localhost";
        int serverPort = 9876;

        // Tạo 3 cảm biến mô phỏng 3 kho khác nhau, mỗi cảm biến 1 Thread
        SensorClient sensor1 = new SensorClient("S01", "Kho Đông A", "DONG", -16.5, serverHost, serverPort, 3000);
        SensorClient sensor2 = new SensorClient("S02", "Kho Mát B", "MAT", 5.0, serverHost, serverPort, 3000);
        SensorClient sensor3 = new SensorClient("S03", "Kho Đông C", "DONG", -17.0, serverHost, serverPort, 4000);

        new Thread(sensor1, "Thread-Sensor-S01").start();
        new Thread(sensor2, "Thread-Sensor-S02").start();
        new Thread(sensor3, "Thread-Sensor-S03").start();

        System.out.println("Đã khởi động 3 cảm biến giả lập. Nhấn Ctrl+C để dừng.");
    }
}