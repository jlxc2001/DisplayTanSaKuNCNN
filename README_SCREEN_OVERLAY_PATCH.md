# ncnn-android-nanodet 屏幕识别悬浮窗补丁

这个补丁把 `nihui/ncnn-android-nanodet` 从默认的「NDK Camera 摄像头识别」改成「MediaProjection 录屏帧识别 + 悬浮窗绘制检测框」。

## 覆盖方式

把本压缩包里的 `app/` 目录覆盖到原项目根目录下的 `app/` 目录。

会替换/新增这些文件：

- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/layout/main.xml`
- `app/src/main/java/com/tencent/nanodetncnn/MainActivity.java`
- `app/src/main/java/com/tencent/nanodetncnn/NanoDetNcnn.java`
- `app/src/main/java/com/tencent/nanodetncnn/ScreenDetectService.java`
- `app/src/main/java/com/tencent/nanodetncnn/DetectionOverlayView.java`
- `app/src/main/jni/nanodetncnn.cpp`
- `app/src/main/jni/CMakeLists.txt`
- `app/build.gradle`

## 运行逻辑

1. 打开 App。
2. 选择模型，建议 32 位设备先用 `ELite0_320`。
3. 选择 CPU，32 位设备不要先用 GPU。
4. 点击「开始识别屏幕画面」。
5. 允许悬浮窗权限。
6. 允许系统录屏权限。
7. 切到其他 App，识别框会通过透明悬浮窗画在屏幕上。

## 重要设置

`app/build.gradle` 里默认只打 32 位 ARM：

```gradle
ndk {
    abiFilters "armeabi-v7a"
}
```

如果你后续要打 64 位，可以改成：

```gradle
ndk {
    abiFilters "armeabi-v7a", "arm64-v8a"
}
```

`ScreenDetectService.java` 里默认把输入帧缩放到 640 宽再推理：

```java
private static final int MAX_DETECT_WIDTH = 640;
```

如果设备卡，把它改成 416 或 320。如果识别框位置正常但精度不够，再改回 720/960。

推理间隔：

```java
private static final long INFER_INTERVAL_MS = 220;
```

这个值越大越省性能，越小越实时。

## 限制

- 普通 App 必须弹系统录屏授权框，不能无感读取屏幕。
- 银行 App、密码页、视频版权页等带 `FLAG_SECURE` 的画面可能是黑屏。
- 原版 NanoDet 是 COCO 80 类模型，只能识别人、车、手机、屏幕、椅子等通用物体；如果要识别车机 UI 图标/按钮，需要换成你自己训练的模型。
