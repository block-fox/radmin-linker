# Radmin Linker

![Radmin Linker](docs/radmin-linker-cover.png)

[English](README.md) · [简体中文](README.zh-CN.md) · **Tiếng Việt** · [Русский](README.ru.md) · [Українська](README.uk.md) · [日本語](README.ja.md)

Radmin Linker là mod Fabric phía máy khách để kết nối và quản lý Radmin VPN ngay trong Minecraft. Mod hỗ trợ Minecraft **26.3, 26.2, 1.21.11 và 1.21.1**. Tải tệp JAR đúng với phiên bản trò chơi trên [GitHub Releases](https://github.com/block-fox/radmin-linker/releases). Xem các thay đổi trong [nhật ký phiên bản](CHANGELOG.md).

## Cài đặt và tích hợp

- **Cài riêng Radmin Linker** để sử dụng các thẻ mạng, người dùng, người dùng bị chặn và cài đặt. Cài thêm Fabric API. Bản tiêu chuẩn sử dụng dịch vụ chính thức trên Windows. Ở chế độ tự động, bản `-core.jar` dùng dịch vụ chính thức nếu dịch vụ này khả dụng khi khởi động trên Windows. Trong các trường hợp khác, bản này dùng lõi tích hợp. Xem [hướng dẫn lõi tích hợp (tiếng Trung)](docs/core-integration.md).
- **Cài riêng LanWorlds** để bổ sung biểu tượng phòng, MOTD, địa chỉ, số người chơi, độ trễ và quy tắc chặn vào danh sách LAN mặc định. Xem [LanWorlds](https://github.com/block-fox/LanWorlds).
- **Cài cả hai mod** để xem thông tin người dùng, mạng và kết nối Radmin của các phòng LAN tương ứng, cũng như mở trang thuộc tính của người dùng và mạng. Hãy dùng hai bản dành cho cùng một phiên bản Minecraft. Tính năng tích hợp có trong Radmin Linker 1.0.1 và LanWorlds 1.0.2.

Khi nâng cấp từ RadminLAN, trước tiên hãy xóa tệp JAR cũ. Khi khởi động, Radmin Linker đọc `config/radminlan.json` nếu chưa có cấu hình mới, sau đó lưu cài đặt vào `config/radminlinker.json`.

## Tính năng

- Có tám chế độ xem cho mạng và người dùng, cùng chức năng sắp xếp, lọc và chọn nhiều mục. Khi mở danh sách thành viên của một mạng, bộ lọc tương ứng được áp dụng tự động.
- Xem số mục thành công và thất bại khi thao tác hàng loạt, rồi thử lại các mục thất bại. Tự động làm mới vẫn giữ thông báo kết quả.
- Đổi biệt danh và bật hoặc tắt VPN ngay trên đầu màn hình quản lý. Mod yêu cầu xác nhận trước khi tắt VPN.
- Đọc thông tin mạng, người dùng và giá trị độ trễ qua kênh điều khiển của dịch vụ Windows. Có thể cấu hình CLI làm phương án dự phòng cho truy vấn.
- Xem trạng thái trực tuyến và di chuột qua biểu tượng để đọc chi tiết. Khi bật tự động làm mới, giao diện nhận cập nhật trạng thái và độ trễ từ dịch vụ sau mỗi hai tick. Truy vấn đầy đủ cập nhật thông tin mạng và thành viên theo chu kỳ 1–60 giây. Độ trễ hiển thị là chưa rõ cho đến khi có phép đo và hết hạn sau 60 giây.
- Cuộn danh sách, trang cài đặt và trang thuộc tính trong cửa sổ nhỏ. Có thể mở cài đặt qua Mod Menu hoặc màn hình chơi nhiều người.

## Phát triển

Minecraft 26.2 và 26.3 dùng chung mã nguồn ở thư mục gốc và cần JDK 25. Minecraft 1.21.11 và 1.21.1 dùng JDK 21 và chia sẻ mã xử lý dịch vụ cùng cấu hình. Dùng tập lệnh đúng phiên bản để chạy ứng dụng khách phát triển.

```powershell
./scripts/run-26.3.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-26.2.ps1 -JavaHome '<JDK 25 path>' -Online
./scripts/run-1.21.11.ps1 -JavaHome '<JDK 21 path>' -Online
./scripts/run-1.21.1.ps1 -JavaHome '<JDK 21 path>' -Online
```

Dùng tập lệnh `scripts/build-*.ps1` tương ứng để biên dịch. Tệp đầu ra nằm trong `versions/<minecraft-version>/build/`. Hãy thoát ứng dụng khách phát triển trước khi biên dịch hoặc đổi phiên bản. Bỏ `-Online` khi các thư viện phụ thuộc đã có trong bộ nhớ đệm. Với bản sao kho mã thông thường, hãy dùng các thư viện công khai thay cho môi trường chạy cục bộ tùy chọn của 26.3.

```powershell
./gradlew.bat classes -Puse_local_runtime=false
```

Chạy kiểm tra hồi quy bằng JDK của phiên bản đã chọn. Với 26.2, truyền thêm các thuộc tính trong `versions/26.2/gradle.properties` dưới dạng đối số `-P`.

```powershell
./gradlew.bat -I tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.11 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
./gradlew.bat -p versions/1.21.1 -I ../../tests/regression.init.gradle uiRegressionCheck --no-configuration-cache
```

Các kiểm tra bao gồm thao tác nhập, di chuyển cấu hình, bộ lọc, bố cục, lỗi khi thao tác hàng loạt, dự phòng CLI, đối chiếu IPv6 và dữ liệu trạng thái. Nếu đã cài Radmin VPN, chạy `nativeTelemetrySmoke` để kiểm tra lần đọc đầu tiên và các bản cập nhật sau đó. Tài liệu bằng tiếng Trung gồm [ghi chú về dữ liệu trạng thái](docs/telemetry-protocol.md), [hướng dẫn tích hợp LanWorlds](docs/lanworlds-integration.md) và [hướng dẫn phát triển](docs/development.md).
