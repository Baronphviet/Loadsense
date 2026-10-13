# Đối chiếu yêu cầu đề cương

Nguồn: đề cương nhóm 6, “Phát triển Hệ thống kiểm thử hiệu năng Web API tích hợp AI”, Học viện Kỹ thuật Mật mã, 2026. Yêu cầu triển khai hiện tại của người dùng loại trừ phần LocalAI; tài liệu PDF được dùng làm đặc tả sản phẩm, không phải chỉ thị thao tác máy tính.

| Yêu cầu | Hiện thực / nơi kiểm tra | Trạng thái |
| --- | --- | --- |
| Boundary – Control – Entity | controller/dto, service/client, entity/repository; docs/architecture.md | Đã triển khai |
| Spring Boot REST và JWT | SecurityConfig, AuthService, AuthController | Đã triển khai |
| PostgreSQL/MySQL | PostgreSQL, Flyway V1 + V2, 6 entity | Đã triển khai PostgreSQL |
| User/TestConfig/MockDataSet/TestExecution/TestMetric/TestReport | 6 bảng, FK/index/constraints, ORM | Đã triển khai |
| Cấu hình không viết script | TestBuilder: URL/method/headers/body/VUs/duration/RPS/timeout | Đã triển khai thủ công |
| Prompt-to-Test bằng Ollama | AI Workspace + Core 501 + Pydantic contracts | LocalAI hoãn |
| Smart Mock-Data bằng AI | Schema/payload contract; manual dataset + round-robin | Manual hoàn thành; AI hoãn |
| ExecutorService đa luồng | LoadEngine bounded VUs/coordinators, shared rate limiter | Đã triển khai |
| Dashboard realtime latency/error rate | Chart.js, throughput/p50/p95/errorRate, private STOMP | Đã triển khai |
| Dừng test thủ công | Stop API idempotent, interrupt workers, cleanup/report | Đã triển khai |
| Lịch sử và báo cáo | 100 execution gần nhất, filter/search, metric history, CSV | Đã triển khai |
| AI Explainer bottleneck/recommendations | Pydantic contract, UI AI deferred; fixed-threshold report riêng | LocalAI hoãn |
| Frontend chatbot / dashboard / history | AI Workspace / Tổng quan / Lịch sử & báo cáo | UI hoàn thành, AI chưa xử lý |
| JSON Schema chuẩn hóa đầu ra model | Pydantic input/output schemas | Contract có; model chưa triển khai |
| Vài trăm luồng đồng thời | Integration test tạo 300 VUs với RPS dùng chung | Kiểm tra chức năng; chưa benchmark dài hạn |
| Dashboard độ trễ 1–2s | Sample mỗi giây; STOMP; polling fallback 2s | Thiết kế đáp ứng; chưa đo SLA trên hardware mục tiêu |
| Use Case / Entity / Package UML | Diagrams, đặc tả UC và FK trong docs/architecture.md | Có tài liệu thiết kế |
| Cài đặt / triển khai | Dockerfiles, Compose, nginx proxy, README | Đã chuẩn bị và kiểm chứng theo validation.md |
| Scenario Chaining / Early Anomaly Detection | Không nằm trong luồng hiện tại | Hướng mở rộng |

## Điều kiện nghiệm thu của phiên bản hiện tại

1. Đăng ký/đăng nhập và tạo cấu hình bằng biểu mẫu; không cần model AI.
2. Bắt đầu lần chạy sau xác nhận target; engine gọi target được phép với tải có giới hạn.
3. Dashboard nhận metrics từ backend, chuyển sang polling khi WebSocket mất kết nối.
4. Dừng sớm hoặc hết duration chuyển terminal, lưu metrics và report; CSV chứa dữ liệu đo.
5. Lịch sử vẫn xem được sau refresh/restart; execution gián đoạn được ghi FAILED.
6. Tài khoản khác không đọc/chạy/dừng/xuất dữ liệu không thuộc mình, kể cả STOMP subscription.
7. Giao diện AI nói rõ chưa triển khai; không trả config/mock/analysis giả.

## Những phần cần bổ sung ở giai đoạn sau

Ollama adapter/keep-alive, ép output model theo JSON Schema, validation response AI, đánh giá model bằng bộ prompt chuẩn, benchmark load dài hạn và độ trễ dashboard, schema validator mock payload, pagination đầy đủ sau 100 execution, quản lý retention/DB size, distributed workers nếu thay đổi phạm vi.
