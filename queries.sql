-- 1. San pham trong tung kho
SELECT w.name AS "Kho", w.type AS "Loại", p.name AS "Sản phẩm",
       p.min_temp AS "Nhiệt độ min", p.max_temp AS "Nhiệt độ max"
FROM warehouse w
JOIN warehouse_product wp ON wp.warehouse_id = w.id
JOIN product p ON p.id = wp.product_id
WHERE w.active = 1
ORDER BY w.name, p.name;

-- 2. Kho chua co san pham nao
SELECT w.name AS "Kho chưa có sản phẩm", w.type AS "Loại"
FROM warehouse w
LEFT JOIN warehouse_product wp ON wp.warehouse_id = w.id
WHERE w.active = 1 AND wp.product_id IS NULL;

-- 3. San pham chua gan vao kho nao
SELECT p.name AS "Sản phẩm chưa gán vào kho nào", p.min_temp, p.max_temp
FROM product p
LEFT JOIN warehouse_product wp ON wp.product_id = p.id
WHERE wp.warehouse_id IS NULL;

-- 4. So san pham va danh sach san pham moi kho
SELECT w.name AS "Kho", COUNT(p.id) AS "Số sản phẩm",
       GROUP_CONCAT(p.name, ', ') AS "Danh sách sản phẩm"
FROM warehouse w
LEFT JOIN warehouse_product wp ON wp.warehouse_id = w.id
LEFT JOIN product p ON p.id = wp.product_id
WHERE w.active = 1
GROUP BY w.id, w.name
ORDER BY COUNT(p.id) DESC, w.name;

-- 5. Dai nhiet do chung cua tung kho
SELECT w.name AS "Kho",
       MAX(p.min_temp) AS "Ngưỡng dưới chung",
       MIN(p.max_temp) AS "Ngưỡng trên chung",
       CASE WHEN MAX(p.min_temp) < MIN(p.max_temp) THEN 'Hợp lệ'
            ELSE 'Không có dải chung' END AS "Kết luận"
FROM warehouse w
JOIN warehouse_product wp ON wp.warehouse_id = w.id
JOIN product p ON p.id = wp.product_id
WHERE w.active = 1
GROUP BY w.id, w.name;

-- 6. Nhiet do moi nhat so voi nguong an toan cua san pham
SELECT w.name AS "Kho", l.temperature AS "Nhiệt độ mới nhất", l.log_time AS "Lúc đo",
       MAX(p.min_temp) AS "Ngưỡng dưới", MIN(p.max_temp) AS "Ngưỡng trên",
       CASE WHEN l.temperature BETWEEN MAX(p.min_temp) AND MIN(p.max_temp)
            THEN 'AN TOÀN' ELSE 'VƯỢT NGƯỠNG' END AS "Đánh giá"
FROM warehouse w
JOIN temperature_log l
     ON l.id = (SELECT MAX(id) FROM temperature_log WHERE warehouse_name = w.name)
JOIN warehouse_product wp ON wp.warehouse_id = w.id
JOIN product p ON p.id = wp.product_id
WHERE w.active = 1
GROUP BY w.id, w.name;

-- 7. Thong ke canh bao theo kho
SELECT w.name AS "Kho", COUNT(*) AS "Tổng số lần đo",
       SUM(CASE WHEN l.status = 'WARNING' THEN 1 ELSE 0 END) AS "Số lần cảnh báo",
       ROUND(100.0 * SUM(CASE WHEN l.status = 'WARNING' THEN 1 ELSE 0 END) / COUNT(*), 1) AS "Tỉ lệ cảnh báo (%)"
FROM temperature_log l
JOIN warehouse w ON w.name = l.warehouse_name
GROUP BY w.id, w.name
ORDER BY "Số lần cảnh báo" DESC;

-- 8. Kho da xoa nhung con giu lich su
SELECT w.name AS "Kho đã xóa", COUNT(l.id) AS "Số bản ghi lịch sử còn giữ"
FROM warehouse w
LEFT JOIN temperature_log l ON l.warehouse_name = w.name
WHERE w.active = 0
GROUP BY w.id, w.name;