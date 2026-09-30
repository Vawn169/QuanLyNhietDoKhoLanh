package coldstorage.util;

import coldstorage.model.Product;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Quản lý danh mục SẢN PHẨM và việc gán sản phẩm vào từng kho.
 *
 * QUY TẮC NGƯỠNG AN TOÀN CỦA MỘT KHO:
 *  - Kho CHƯA gán sản phẩm nào  -> dùng ngưỡng chung theo loại kho (DONG/MAT) trong ConfigUtil như cũ.
 *  - Kho có 1 sản phẩm          -> dùng đúng dải nhiệt độ của sản phẩm đó.
 *  - Kho có NHIỀU sản phẩm      -> dùng dải GIAO (chung) của tất cả: max(các min) ~ min(các max).
 *                                  Không cho gán nếu dải giao rỗng (các sản phẩm không thể để chung kho).
 */
public class ProductUtil {

    private static class Snapshot {
        final Map<Integer, Product> productsById;
        final Map<String, List<Product>> productsByWarehouse;

        Snapshot(Map<Integer, Product> productsById, Map<String, List<Product>> productsByWarehouse) {
            this.productsById = productsById;
            this.productsByWarehouse = productsByWarehouse;
        }
    }

    private static volatile Snapshot snapshot = new Snapshot(
            Collections.<Integer, Product>emptyMap(), Collections.<String, List<Product>>emptyMap());

    /** Gọi 1 lần sau DatabaseUtil.initDatabase(): nạp sản phẩm mẫu (lần đầu tiên) rồi nạp snapshot. */
    public static synchronized void init() {
        if (DatabaseUtil.isProductTableNeverUsed()) {
            DatabaseUtil.insertProduct("Thịt đông lạnh", -20.0, -16.0);
            DatabaseUtil.insertProduct("Hải sản đông lạnh", -25.0, -18.0);
            DatabaseUtil.insertProduct("Thịt tươi (ướp mát)", 0.0, 4.0);
            DatabaseUtil.insertProduct("Sữa tươi", 2.0, 6.0);
            DatabaseUtil.insertProduct("Rau củ quả", 4.0, 8.0);
            DatabaseUtil.insertProduct("Vắc-xin / thuốc bảo quản lạnh", 2.0, 8.0);
            DatabaseUtil.insertProduct("Dược phẩm bảo quản nơi mát", 8.0, 15.0);
            System.out.println("[Product] Đã nạp danh mục sản phẩm mẫu (có thể xóa/thêm trong cửa sổ Sản phẩm).");
        }
        reload();
    }

    /**
     * Nạp lại sản phẩm + liên kết kho-sản phẩm từ database (BackupLauncher gọi định kỳ vì nó là tiến trình RIÊNG,
     * không thấy thay đổi do Server thực hiện trong bộ nhớ). Nếu đọc DB lỗi thì GIỮ NGUYÊN snapshot cũ - tuyệt đối không
     * thay bằng snapshot rỗng, vì khi đó mọi kho sẽ bị áp ngưỡng chung của loại kho (sai) mà không ai hay biết.
     * @return true nếu nạp thành công
     */
    public static synchronized boolean refresh() {
        return reload();
    }

    private static synchronized boolean reload() {
        List<Product> allProducts;
        Map<String, List<Integer>> assignments;
        try {
            allProducts = DatabaseUtil.queryAllProducts();
            assignments = DatabaseUtil.queryAllAssignments();
        } catch (java.sql.SQLException ex) {
            System.err.println("[Product] Không nạp lại được từ database, giữ dữ liệu cũ: " + ex.getMessage());
            return false;
        }
        Map<Integer, Product> byId = new LinkedHashMap<>();
        for (Product p : allProducts) {
            byId.put(p.getId(), p);
        }
        Map<String, List<Product>> byWarehouse = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> e : assignments.entrySet()) {
            List<Product> list = new ArrayList<>();
            for (Integer pid : e.getValue()) {
                Product p = byId.get(pid);
                if (p != null) list.add(p);
            }
            if (!list.isEmpty()) {
                byWarehouse.put(e.getKey(), Collections.unmodifiableList(list));
            }
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(byWarehouse));
        return true;
    }

    // ============================== TRUY VẤN (an toàn đa luồng) ==============================

    public static List<Product> getAllProducts() {
        return new ArrayList<>(snapshot.productsById.values());
    }

    public static List<Product> getProductsOf(String warehouseName) {
        List<Product> list = snapshot.productsByWarehouse.get(warehouseName);
        return list == null ? Collections.<Product>emptyList() : list;
    }

    /** Tên các sản phẩm trong kho, ngăn cách bằng dấu phẩy; chuỗi rỗng nếu kho chưa gán sản phẩm nào */
    public static String getProductNames(String warehouseName) {
        StringBuilder sb = new StringBuilder();
        for (Product p : getProductsOf(warehouseName)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getName());
        }
        return sb.toString();
    }

    public static boolean hasProducts(String warehouseName) {
        return !getProductsOf(warehouseName).isEmpty();
    }

    /** Danh sách kho đang chứa sản phẩm này (dùng để cảnh báo trước khi xóa sản phẩm) */
    public static List<String> getWarehousesUsing(int productId) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, List<Product>> e : snapshot.productsByWarehouse.entrySet()) {
            for (Product p : e.getValue()) {
                if (p.getId() == productId) {
                    result.add(e.getKey());
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Dải nhiệt độ an toàn đang áp dụng cho 1 kho: {min, max}.
     * Có sản phẩm -> giao các dải của sản phẩm; không có -> ngưỡng chung theo loại kho.
     */
    public static double[] getSafeRange(String warehouseName, String warehouseType) {
        List<Product> products = getProductsOf(warehouseName);
        if (products.isEmpty()) {
            return typeRange(warehouseType);
        }
        return intersect(products, null);
    }

    private static double[] typeRange(String warehouseType) {
        if ("DONG".equalsIgnoreCase(warehouseType)) {
            return new double[]{ConfigUtil.getDongMin(), ConfigUtil.getDongMax()};
        }
        return new double[]{ConfigUtil.getMatMin(), ConfigUtil.getMatMax()};
    }

    /** Giao các dải của danh sách sản phẩm (cộng thêm 1 sản phẩm dự kiến gán nếu extra != null) */
    private static double[] intersect(List<Product> products, Product extra) {
        double min = Double.NEGATIVE_INFINITY;
        double max = Double.POSITIVE_INFINITY;
        for (Product p : products) {
            min = Math.max(min, p.getMinTemp());
            max = Math.min(max, p.getMaxTemp());
        }
        if (extra != null) {
            min = Math.max(min, extra.getMinTemp());
            max = Math.min(max, extra.getMaxTemp());
        }
        return new double[]{min, max};
    }

    // ============================== THAY ĐỔI (gọi từ giao diện) ==============================

    /** @return null nếu thành công, ngược lại là thông báo lỗi để hiển thị cho người dùng */
    public static synchronized String addProduct(String name, double min, double max) {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) return "Vui lòng nhập tên sản phẩm.";
        if (name.length() > 60) return "Tên sản phẩm tối đa 60 ký tự.";
        if (Double.isNaN(min) || Double.isNaN(max) || Double.isInfinite(min) || Double.isInfinite(max)) {
            return "Nhiệt độ không hợp lệ.";
        }
        if (min >= max) return "Nhiệt độ tối thiểu phải nhỏ hơn tối đa.";
        for (Product p : snapshot.productsById.values()) {
            if (p.getName().equalsIgnoreCase(name)) {
                return "Sản phẩm '" + p.getName() + "' đã tồn tại.";
            }
        }
        if (!DatabaseUtil.insertProduct(name, min, max)) {
            return "Không ghi được sản phẩm vào database (xem log trên console).";
        }
        reload();
        return null;
    }

    /** Xóa sản phẩm khỏi danh mục và gỡ khỏi mọi kho đang chứa nó */
    public static synchronized void deleteProduct(int productId) {
        DatabaseUtil.deleteProduct(productId);
        reload();
    }

    /**
     * Gán 1 sản phẩm vào 1 kho.
     * @return null nếu thành công, ngược lại là thông báo lỗi (vd: dải nhiệt độ chung bị rỗng)
     */
    public static synchronized String assignProduct(String warehouseName, int productId) {
        warehouseName = warehouseName == null ? "" : warehouseName.trim();
        if (warehouseName.isEmpty()) return "Vui lòng chọn hoặc nhập tên kho.";
        Product candidate = snapshot.productsById.get(productId);
        if (candidate == null) return "Sản phẩm không tồn tại (có thể vừa bị xóa).";

        List<Product> current = getProductsOf(warehouseName);
        for (Product p : current) {
            if (p.getId() == productId) return "Kho '" + warehouseName + "' đã có sản phẩm này.";
        }
        if (!current.isEmpty()) {
            double[] range = intersect(current, candidate);
            if (range[0] >= range[1]) { // rỗng hoặc chỉ còn 1 điểm nhiệt độ duy nhất -> không dùng được
                double[] existing = intersect(current, null);
                return "Không thể gán: '" + candidate.getName() + "' (" + candidate.getRangeText()
                        + ") không có dải nhiệt độ chung với các sản phẩm đang có trong kho ("
                        + existing[0] + "°C ~ " + existing[1] + "°C).";
            }
        }
        if (!DatabaseUtil.assignProduct(warehouseName, productId)) {
            return "Không ghi được vào database (xem log trên console).";
        }
        reload();
        return null;
    }

    public static synchronized void unassignProduct(String warehouseName, int productId) {
        DatabaseUtil.unassignProduct(warehouseName == null ? "" : warehouseName.trim(), productId);
        reload();
    }

    /** Tập tên các kho đã được gán sản phẩm */
    public static Set<String> getWarehousesWithProducts() {
        return new LinkedHashSet<>(snapshot.productsByWarehouse.keySet());
    }

    /** Gỡ toàn bộ sản phẩm đang gán cho 1 kho (dùng khi xóa hẳn kho khỏi hệ thống) */
    public static synchronized void forgetWarehouse(String warehouseName) {
        DatabaseUtil.deleteAllAssignmentsForWarehouse(warehouseName);
        reload();
    }
}
