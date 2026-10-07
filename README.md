# 口风琴 MIDI 播放器 · 测试版 0.2.0

**这是由 AI（OpenAI Codex）编写的 Android 测试版 App。** 功能根据使用者需求实现，并在真我 GT 上做过现场验证；目前仍处于实验阶段，尚未完成广泛设备兼容性测试。

App 使用 Java 和 Android 原生 API 解析 MIDI，通过无障碍 `dispatchGesture` 在《三角洲行动》手游口风琴页面模拟触摸。它不修改游戏文件、内存或网络协议，也不需要 root。它不是游戏官方工具，普通触摸自动化仍可能受游戏规则限制，无法保证免封号。

## 功能

- 使用系统文件选择器导入标准 MIDI 0/1，单文件最大 5 MB。
- 导入后保存到 App 本地曲库，支持选择曲目和确认删除。
- 每首曲目分别记住旋律通道、速度和移调；重复导入相同内容去重，同名但内容不同的文件独立保存。
- 浮窗支持播放、暂停、继续、停止和键位校准。
- 只在游戏前台且横屏时开始演奏；开始前本地检查琴键及变调模式。
- 内置《极乐净土》约 43 秒候选主旋律，139 个音符，默认原速、移调 -2。

没有网络、麦克风或广泛存储读取权限。MIDI 通过系统文件选择器复制到 App 私有目录，截图仅用于开始前的模式检查，不保存或上传。

## 使用

1. 在本机构建并安装 `build/harmonica-player.apk`，开启“口风琴触摸控制”无障碍服务。
2. 导入 MIDI 或选择内置曲目，在“本地曲库”下拉列表选择要演奏的曲目。
3. 选择旋律通道，设置速度和移调。速度 `1` 为原速，`0.75` 为慢速。
4. 勾选琴键页面确认项，点击“保存配置并打开游戏浮窗”。
5. 在游戏中打开口风琴手动琴键页面，再点击浮窗播放。

“删除所选曲目”只删除曲库副本和该曲目的配置，不删除导入时选择的原文件。内置曲目始终保留。卸载 App 或清除应用数据会删除本地曲库；覆盖安装更新会保留。

## 测试版限制

- 只播放一个旋律通道，不使用打击乐通道 10；同一时刻的和弦取最高音。
- 每个音最长按住 2 秒，略缩短尾部，为松键和变调留时间。
- 当前默认琴键按真我 GT 横屏 `2880 × 1440` 页面校准，支持范围为 C3～C#6。换设备、改分辨率或修改游戏布局后应重新校准。
- 超出音域、变调过密或调度明显落后会停止并提示。
- 模式识别依赖当前游戏按钮颜色，游戏界面更新后可能失效。
- 演奏中和暂停时不要手动修改游戏半音与八度。
- 暂不支持和弦、自动音频转谱、联网选曲或曲库导出。

## 构建

当前使用 Windows PowerShell、Android SDK 35、Build Tools 36.1.0、Android Studio 自带 JBR 21。直接调用 `aapt`、`javac`、D8、`zipalign` 和 `apksigner`，没有第三方 Android 依赖，也不需要联网下载构建依赖。

```powershell
.\build.ps1
```

脚本默认从当前用户的 `%LOCALAPPDATA%\Android\Sdk` 读取 SDK，从 `%USERPROFILE%\.android\debug.keystore` 读取本地调试签名。路径不同可以显式传参：

```powershell
.\build.ps1 -Sdk 'D:\Android\Sdk' -Jdk 'C:\Program Files\Android\Android Studio\jbr' -Keystore 'D:\local-keys\debug.keystore'
```

调试 keystore 必须在本机准备，使用 Android 标准调试密码 `android`。本仓库不包含签名私钥或 APK。如果本机还没有调试 keystore，可使用 JDK 的 `keytool` 创建：

```powershell
New-Item -ItemType Directory -Path "$env:USERPROFILE\.android" -Force | Out-Null
& 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe' -genkeypair -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
```

仅在文件不存在时创建；更新安装到同一手机时，应持续使用同一份本地签名。

## 源码与检查

| 文件 | 用途 |
|---|---|
| `MainActivity.java` | MIDI 导入、本地选曲、删除和演奏配置 |
| `SongLibrary.java` | App 私有曲库、重复内容去重和配置持久化 |
| `MidiFile.java` | 标准 MIDI 解析和旋律通道选择 |
| `Planner.java` | 音高映射和演奏时序 |
| `PlayerService.java` | 无障碍触摸、浮窗、暂停与键位校准 |
| `tests/CoreCheck.java` | 音符、时序、变速、错误 MIDI 和音域保护检查 |
| `tests/LibraryCheck.java` | 保存、去重、中文名称、配置恢复和删除边界检查 |

Java 检查可通过本机 `javac` 编译上述两个检查类及对应的 `MidiFile`、`Planner`、`SongLibrary` 后运行。`CoreCheck` 接收原始《极乐净土》MIDI 和 `assets/gokuraku.mid` 两个路径；`LibraryCheck` 接收两个内容不同的 MIDI 文件路径。原始外部 MIDI 未放入仓库。Windows Java 对中文命令行文件名的处理曾出现问题，检查时可使用英文命名的临时副本。

已完成的检查包括 MIDI 解析与演奏时序、本地保存、重复导入、重启恢复和删除不影响源文件。早期版本在真机完成过 139 个音符演奏、暂停、继续、停止和校准；0.2 曲库版本没有重新运行完整游戏演奏。短音符听感、其他设备和后续游戏版本仍需测试。

仓库仅保留必要源码、资源、构建脚本和检查代码。APK、游戏录音、截图、手机备份、现场日志、签名文件和本机验证记录保留在本地，不上传。
