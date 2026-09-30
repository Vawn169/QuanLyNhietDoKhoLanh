package coldstorage.model;

/**
 * Một loại sản phẩm được bảo quản trong kho lạnh (thịt, hải sản, sữa...).
 * Mỗi sản phẩm có dải nhiệt độ an toàn RIÊNG (minTemp ~ maxTemp), độc lập với loại kho DONG/MAT.
 */
public class Product {

    private final int id;
    private final String name;
    private final double minTemp;
    private final double maxTemp;

    public Product(int id, String name, double minTemp, double maxTemp) {
        this.id = id;
        this.name = name;
        this.minTemp = minTemp;
        this.maxTemp = maxTemp;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public double getMinTemp() { return minTemp; }
    public double getMaxTemp() { return maxTemp; }

    /** Dải nhiệt độ dạng chữ, ví dụ "-25.0°C ~ -18.0°C" */
    public String getRangeText() {
        return minTemp + "°C ~ " + maxTemp + "°C";
    }

    /** Hiển thị trong JComboBox / JList: "Hải sản đông lạnh (-25.0°C ~ -18.0°C)" */
    @Override
    public String toString() {
        return name + " (" + getRangeText() + ")";
    }
}