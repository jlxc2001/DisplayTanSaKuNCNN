这是给 DisplayTanSaKuNCNN / ncnn-android-nanodet 项目的整包覆盖文件。

使用方法：
1. 解压这个 zip。
2. 打开解压后的 DisplayTanSaKuNCNN_PROJECT_ROOT 文件夹。
3. 把里面的所有内容拖到 GitHub 仓库根目录上传并覆盖。
   注意：上传的是文件夹里面的内容，不要把 DisplayTanSaKuNCNN_PROJECT_ROOT 这个外层文件夹本身上传进去。
4. Commit changes。
5. 到 Actions -> release-apk -> Run workflow，手动开一个新的 workflow。
   不要点旧失败任务的 Re-run all jobs。

这版包含：
- 屏幕录屏识别输入 MediaProjection + ImageReader
- 悬浮窗绘制识别框
- 战斗机 HUD 风格锁定框
- 首次识别收缩锁定动画
- TRACK / LOCK / LOCKED 状态分级
- 绿色 / 黄色 / 红色置信度分级
- GitHub Actions 自动清理 DetectionOverlayView(1).java 等重复文件

重要：
如果你的仓库里还存在 DetectionOverlayView(1).java，新的 release-apk.yml 会在编译前自动删除它。
