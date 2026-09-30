package coldstorage.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phát hiện kho MẤT TÍN HIỆU (cảm biến hỏng, mất điện, đứt mạng...): kho im lặng quá
 * max(MIN_MS, FACTOR x chu kỳ gửi ước lượng) thì bị coi là mất tín hiệu, cho tới khi có gói mới.
 *
 * Bản cũ hoàn toàn không có cơ chế này: cảm biến chết thì bảng vẫn hiện số cuối cùng và "AN TOÀN" mãi mãi -
 * nguy hiểm nhất với kho lạnh vì đúng lúc hỏng thì không ai được báo.
 *
 * Lớp thuần logic (không phụ thuộc Swing), KHÔNG an toàn đa luồng: chỉ gọi từ luồng Swing (EDT).
 */
public class OfflineTracker {

    public static final long MIN_MS = 15_000;
    public static final int FACTOR = 3;
    private static final long MAX_INTERVAL_SAMPLE_MS = 10 * 60_000;

    /** Một kho vừa chuyển sang trạng thái mất tín hiệu */
    public static class Lost {
        public final String warehouse;
        public final long lastSeenAt;
        /** true nếu KHÔNG cần gửi cảnh báo (dữ liệu khôi phục từ DB / cảm biến giả lập bị quản trị viên chủ động dừng) */
        public final boolean silent;

        Lost(String warehouse, long lastSeenAt, boolean silent) {
            this.warehouse = warehouse;
            this.lastSeenAt = lastSeenAt;
            this.silent = silent;
        }
    }

    // kho -> {thời điểm nhận gói gần nhất, chu kỳ gửi ước lượng (ms)}
    private final Map<String, long[]> seen = new HashMap<>();
    private final Set<String> offline = new HashSet<>();
    private final Set<String> muted = new HashSet<>();

    /** Dữ liệu khôi phục từ DB khi khởi động là dữ liệu CŨ: coi như mất tín hiệu (im lặng) tới khi có gói mới. */
    public void restoreAsOffline(String warehouse, long lastDataTimestamp) {
        seen.put(warehouse, new long[]{lastDataTimestamp, 0});
        offline.add(warehouse);
        muted.add(warehouse);
    }

    /**
     * Ghi nhận vừa nhận gói từ kho.
     * @return true nếu kho này VỪA CÓ TÍN HIỆU TRỞ LẠI sau khi bị coi là mất tín hiệu
     */
    public boolean onData(String warehouse, long receivedAt) {
        long[] prev = seen.get(warehouse);
        long est = prev == null ? 0 : prev[1];
        if (prev != null && !offline.contains(warehouse)) {
            long delta = receivedAt - prev[0];
            if (delta > 0 && delta <= MAX_INTERVAL_SAMPLE_MS) {
                est = est == 0 ? delta : (est + delta) / 2; // trung bình trượt
            }
        }
        seen.put(warehouse, new long[]{receivedAt, est});
        boolean wasOffline = offline.remove(warehouse);
        muted.remove(warehouse);
        return wasOffline;
    }

    /** Kiểm tra định kỳ: trả về các kho VỪA chuyển sang mất tín hiệu (mỗi lần mất chỉ trả về 1 lần) */
    public List<Lost> check(long now) {
        List<Lost> result = new ArrayList<>();
        for (Map.Entry<String, long[]> e : seen.entrySet()) {
            String warehouse = e.getKey();
            if (offline.contains(warehouse)) continue;
            long lastSeenAt = e.getValue()[0];
            long limit = Math.max(MIN_MS, FACTOR * e.getValue()[1]);
            if (now - lastSeenAt > limit) {
                offline.add(warehouse);
                result.add(new Lost(warehouse, lastSeenAt, muted.contains(warehouse)));
            }
        }
        return result;
    }

    public boolean isOffline(String warehouse) { return offline.contains(warehouse); }

    public int offlineCount() { return offline.size(); }

    /** Kho này có thể im lặng mà không cần cảnh báo (vd: cảm biến giả lập bị dừng chủ động) */
    public void mute(String warehouse) { muted.add(warehouse); }

    public void unmute(String warehouse) { muted.remove(warehouse); }

    /** Xóa hoàn toàn dấu vết của 1 kho (dùng khi xóa hẳn kho khỏi hệ thống) */
    public void forget(String warehouse) {
        seen.remove(warehouse);
        offline.remove(warehouse);
        muted.remove(warehouse);
    }
}
