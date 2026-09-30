package coldstorage.util;

import coldstorage.model.TemperatureData;

/**
 * Kiểm tra ngưỡng nhiệt độ an toàn.
 * Ngưỡng của mỗi kho được xác định bởi ProductUtil:
 *  - kho có gán sản phẩm  -> theo dải nhiệt độ RIÊNG của (các) sản phẩm trong kho;
 *  - kho chưa gán sản phẩm -> theo ngưỡng chung của loại kho (DONG/MAT) trong ConfigUtil.
 */
public class AlertUtil {

    public enum Status { SAFE, WARNING }

    /** Dải an toàn {min, max} đang áp dụng cho kho phát ra gói dữ liệu này */
    public static double[] getSafeRange(TemperatureData data) {
        return ProductUtil.getSafeRange(data.getWarehouseName(), data.getWarehouseType());
    }

    public static Status checkStatus(TemperatureData data) {
        double t = data.getTemperature();
        double[] range = getSafeRange(data);
        return (t >= range[0] && t <= range[1]) ? Status.SAFE : Status.WARNING;
    }

    /** Ngưỡng theo kho cụ thể (có tính đến sản phẩm trong kho) */
    public static String getThresholdText(String warehouseName, String warehouseType) {
        double[] range = ProductUtil.getSafeRange(warehouseName, warehouseType);
        return range[0] + "°C ~ " + range[1] + "°C";
    }

    public static String getThresholdText(TemperatureData data) {
        return getThresholdText(data.getWarehouseName(), data.getWarehouseType());
    }

    /** Ngưỡng CHUNG theo loại kho (không xét sản phẩm) - giữ lại để tương thích với code cũ */
    public static String getThresholdText(String warehouseType) {
        if ("DONG".equalsIgnoreCase(warehouseType)) {
            return ConfigUtil.getDongMin() + "°C ~ " + ConfigUtil.getDongMax() + "°C";
        } else {
            return ConfigUtil.getMatMin() + "°C ~ " + ConfigUtil.getMatMax() + "°C";
        }
    }
}