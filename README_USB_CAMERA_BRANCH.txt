DisplayTanSaKuNCNN USB Camera2 分支说明

本覆盖包在屏幕录制识别版本基础上，新增一条“USB摄像头识别（Camera2）”入口。

新增文件：
- app/src/main/java/com/tencent/nanodetncnn/UsbCameraDetectActivity.java
- app/src/main/res/layout/usb_camera_detect.xml

修改文件：
- app/src/main/java/com/tencent/nanodetncnn/MainActivity.java
- app/src/main/res/layout/main.xml
- app/src/main/AndroidManifest.xml

工作方式：
1. 进入 App。
2. 选择 ELite0_320 + CPU。
3. 点击“USB摄像头识别（Camera2）”。
4. 授权相机权限。
5. 如果系统把 USB/UVC 摄像头暴露为 Camera2 外接摄像头，会优先打开 LENS_FACING_EXTERNAL。
6. 检测框直接叠加在摄像头预览画面上。

限制：
- 这版是 Camera2 外接摄像头分支。
- 如果你的摄像头只能被某个厂商 App 打开，系统 Camera2 列表里没有这个 USB 摄像头，则这版无法直接读取，需要另做 libuvc / UVCCamera 原生 USB 协议分支。
- 如果点击后打开的是默认摄像头，说明系统没有把 USB 摄像头暴露为 Camera2 外接摄像头。

性能建议：
- 32 位安卓 10 车机优先 ELite0_320 + CPU。
- 当前识别节流约 220ms 一次，适合低配置设备。
