DisplayTanSaKuNCNN 真 UVC 摄像头识别分支

这版不是 Camera2 外接摄像头方案，而是真 UVC 直连方案：
USB UVC 摄像头 -> Android USB Host -> org.uvccamera/libuvc -> NV21 帧回调 -> NanoDet -> HUD 识别框。

新增/修改：
- app/src/main/java/com/tencent/nanodetncnn/UvcCameraDetectActivity.java
- app/src/main/res/layout/uvc_camera_detect.xml
- app/src/main/java/com/tencent/nanodetncnn/MainActivity.java
- app/src/main/res/layout/main.xml
- app/src/main/AndroidManifest.xml
- app/build.gradle
- settings.gradle

依赖：
- org.uvccamera:lib:0.0.13

使用：
1. 覆盖到仓库根目录。
2. Commit changes。
3. Actions -> release-apk -> Run workflow，手动新开一次任务。
4. 安装 APK。
5. 打开 App，选择 ELite0_320 + CPU。
6. 点击“UVC摄像头识别（真UVC直连）”。
7. 插入 UVC 摄像头，点“打开UVC”，允许 USB 访问权限。

说明：
- 这版默认 640x480、MJPEG 优先，失败后尝试 YUYV。
- 默认每约 220ms 做一次识别，适合低配置 32 位车机。
- 如果提示打开失败，常见原因是摄像头不支持 640x480/MJPEG/YUYV、OTG供电不足、系统禁用了 USB Host，或者该摄像头不是标准 UVC。
- 如果画面能预览但识别框方向/比例不对，后续需要根据实际摄像头画面加旋转或比例适配。
