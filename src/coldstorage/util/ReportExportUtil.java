package coldstorage.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Xuất báo cáo dạng CSV (mở trực tiếp được bằng Excel, không cần cài thêm gì). Gồm 2 phần:
 * 1. Tổng hợp theo từng kho đang hoạt động (loại, sản phẩm, tổng số lần đo, số lần cảnh báo, tỉ lệ).
 * 2. Lịch sử sự cố gần đây (bắt đầu, kết thúc, thời lượng, nhiệt độ đỉnh điểm).
 *
 * LƯU Ý: đây KHÔNG phải file .xlsx (Excel) hay .pdf thật - môi trường đóng gói không có sẵn các thư viện
 * cần thiết (Apache POI cho .xlsx, iText/PDFBox cho .pdf) để tải về khi build. CSV là định dạng đơn giản
 * nhất mà Excel mở được ngay (double-click là ra bảng, có thể lưu lại thành .xlsx từ Excel nếu cần), không
 * cần phụ thuộc thư viện ngoài. Nếu muốn nâng cấp lên .xlsx/.pdf thật, cần tự thêm các jar tương ứng vào
 * dist/lib và import trong project.
 */
public final class ReportExportUtil {

    private static final SimpleDateFormat SDF = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

    private ReportExportUtil() {
    }

    /** Ghi báo cáo ra file CSV (UTF-8 kèm BOM để Excel tự nhận đúng font tiếng Việt) */
    public static void export(File file, Long fromMillis, Long toMillis) throws IOException {
        try (OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
             PrintWriter pw = new PrintWriter(osw)) {
            pw.write('\uFEFF'); // BOM - thiếu dòng này Excel (Windows) có thể hiện sai dấu tiếng Việt

            pw.println("BAO CAO GIAM SAT KHO LANH - Xuat luc " + SDF.format(new Date()));
            if (fromMillis != null && toMillis != null) {
                pw.println("Tu ngay: " + SDF.format(new Date(fromMillis)) + " den ngay: " + SDF.format(new Date(toMillis)));
            }
            pw.println();

            pw.println("1. TONG HOP THEO KHO");
            pw.println(csvRow("Kho", "Loai kho", "San pham", "Tong so lan do", "So lan canh bao", "Ti le trong nguong (%)", 
                              "Nhiet do MIN", "Nhiet do AVG", "Nhiet do MAX", "Tong thoi gian su co (phut)", "MKT (C)"));
            List<DatabaseUtil.WarehouseReportRow> report = DatabaseUtil.getWarehouseReport(fromMillis, toMillis);
            for (DatabaseUtil.WarehouseReportRow r : report) {
                String typeText = "DONG".equalsIgnoreCase(r.type) ? "Kho dong" : "Kho mat";
                String mktStr = r.mkt == null ? "" : String.format("%.2f", r.mkt);
                pw.println(csvRow(r.name, typeText, r.products.isEmpty() ? "(chua gan)" : r.products,
                        String.valueOf(r.totalReadings), String.valueOf(r.warningReadings),
                        String.format("%.1f", r.safePercent()),
                        String.format("%.1f", r.minTemp), String.format("%.1f", r.avgTemp), String.format("%.1f", r.maxTemp),
                        String.valueOf(r.totalAlertMinutes), mktStr));
            }
            pw.println();

            pw.println("2. LICH SU SU CO GAN DAY");
            pw.println(csvRow("Kho", "Bat dau", "Ket thuc", "Thoi luong (phut)", "Nhiet do dinh diem (C)"));
            List<DatabaseUtil.AlertIncident> incidents = DatabaseUtil.getAlertHistory(500);
            for (DatabaseUtil.AlertIncident inc : incidents) {
                // ... (filtering incidents by date if necessary, but request didn't explicitly say to filter the recent incidents list, just the warehouse summary. I will just leave it as is or add simple filtering if fromMillis is specified).
                if (fromMillis != null && (inc.endedAt > 0 && inc.endedAt < fromMillis)) continue;
                if (toMillis != null && inc.startedAt > toMillis) continue;
                
                boolean ongoing = inc.isOngoing();
                long endMs = ongoing ? System.currentTimeMillis() : inc.endedAt;
                double minutes = Math.max(0, (endMs - inc.startedAt) / 60000.0);
                pw.println(csvRow(inc.warehouseName, SDF.format(new Date(inc.startedAt)),
                        ongoing ? "Dang dien ra" : SDF.format(new Date(inc.endedAt)),
                        String.format("%.1f", minutes), String.format("%.1f", inc.peakTemp)));
            }
        }
    }

    /** Nối các ô thành 1 dòng CSV, tự thêm dấu ngoặc kép quanh ô có chứa dấu phẩy/ngoặc kép/xuống dòng */
    private static String csvRow(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(cells[i]));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) s = "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
