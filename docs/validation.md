# Kết quả kiểm chứng triển khai

Ngày kiểm tra: **14/10/2026**, timezone Asia/Ho_Chi_Minh. Các phép kiểm tra dưới đây đã chạy thực tế trong workspace, không phải kế hoạch kiểm thử.

| Kiểm tra | Kết quả |
| --- | --- |
| Maven / Java 17, H2 integration suite | 14 tests, 0 failure, 0 error |
| Maven verify / PostgreSQL 15 | 14 tests, 0 failure, 0 error |
| Flyway trên PostgreSQL mới | V1 + V2 apply thành công, Hibernate schema validate thành công |
| Frontend ESLint | Pass |
| Vite production build | Pass, 59 modules; JS khoảng 362 kB, gzip khoảng 121 kB |
| pnpm 10.17.1 frozen-lockfile | Pass |
| AI Pydantic / deferred endpoint contracts | 2 tests pass; không chạy model |
| Docker build Core / Frontend | Pass |
| Compose PostgreSQL / Core / nginx | Khởi động và healthchecks pass |
| Browser E2E trên Compose production | Pass toàn bộ luồng dưới đây |
| OpenAPI JSON | Parse thành công và tất cả schema references hợp lệ |

## Phạm vi test backend

- Đăng ký, username trùng, đăng nhập đúng/sai, API không JWT.
- DTO tải quá mức, host ngoài allowlist, port 0, restricted headers và CRLF.
- Cần xác nhận target trước khi bắt đầu execution.
- Chạy đến terminal, lịch sử metrics, report DEFERRED và CSV.
- Sửa cấu hình giữa phiên vẫn giữ snapshot execution cũ.
- Dừng idempotent, tài khoản khác không đọc/dừng/extract dữ liệu qua execution/config/dataset ID.
- HTTP 500, timeout, redirect không được theo.
- Engine nhận 300 VUs và vẫn giữ rate cap dùng chung; admission không nhận test thứ ba khi mặc định 2 test đang hoạt động.
- Payload thủ công xoay vòng và thuộc đúng config/owner.
- STOMP nhận metrics cho owner; subscription của tài khoản khác bị từ chối.
- Recovery PENDING sau gián đoạn chuyển FAILED và tạo report ENGINE_INTERRUPTED.
- RPS thấp vẫn giữ duration thay vì hoàn tất sớm sau request đầu.
- Histogram average/p50/p95/errorRate/interval throughput, paced limiter và cancellation.

## Phạm vi test giao diện

Script [browser-smoke.mjs](../scripts/browser-smoke.mjs) đã chạy trên `http://localhost:3000` qua nginx:

1. Đăng ký tài khoản và nhận dữ liệu workspace.
2. Tạo cấu hình GET qua biểu mẫu.
3. Nút bắt đầu disabled đến khi xác nhận quyền test.
4. Nhận STOMP metrics và vẽ Chart.js; đợi đủ mẫu để chụp live dashboard.
5. Hoàn thành, mở report, tải CSV và xem history.
6. Reload vẫn giữ session và xem lại report đã lưu.
7. Mô phỏng WebSocket không truy cập được bằng endpoint port 1 trong browser test; REST polling vẫn nhận mẫu và dừng được test.
8. Tạo cấu hình POST, lưu mảng JSON thủ công, chọn bộ dữ liệu và hoàn tất execution.
9. AI Workspace hiển thị deferred; nút gửi model disabled.
10. Desktop 1440px và mobile 390px không có page overflow; xem builder mobile.
11. Đăng xuất rồi đăng nhập lại.
12. Không có browser `pageerror`.

Ảnh đã chụp và xem trực quan nằm ở `output/screenshots/`: login, builder, live dashboard, history/report, AI Workspace, mobile dashboard và mobile builder. Đây là artifacts cục bộ, được bỏ qua trong Git.

## Giới hạn của bằng chứng

Test 300 VUs chứng minh engine có thể tạo và điều phối số worker đó trong luồng kiểm tra ngắn; không chứng minh throughput cực đại, ổn định nhiều giờ hay SLA dashboard trên máy triển khai mục tiêu. Demo target cùng JVM dùng kiểm tra luồng, không phải target benchmark độc lập. Chưa kiểm định JSON Schema của mock payload, chưa triển khai AI hoặc chạy đánh giá model. CI workflow đã được cấu hình, chưa có kết quả GitHub Actions từ remote trong phiên này.

## Bản chạy thử cục bộ

Stack kiểm chứng dùng Compose project `loadsense-validation`, giao diện tại `http://localhost:3000`. Có thể tự đăng ký tài khoản để xem; tài khoản/dữ liệu smoke test chỉ phục vụ QA.

```bash
# Dừng bản chạy thử, giữ volume của nó:
docker compose -p loadsense-validation -f infra/docker-compose.yml down
# Sau đó chạy stack riêng theo README:
docker compose --env-file .env -f infra/docker-compose.yml up --build -d
```
