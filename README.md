# Apple TV 安卓遥控器与键盘

免费开源的 Android Apple TV 遥控与文字输入工具。手机通过局域网直接连接 Apple TV，不需要红外、电脑中转或云服务器，无广告、统计与付费解锁。

Native Android remote and live keyboard for Apple TV, built with Kotlin and Jetpack Compose. LAN connection; no infrared hardware or desktop bridge required.

[下载安装包](https://github.com/ggbqq0-lang/apple-tv-/releases/latest) · [安装说明](docs/INSTALL.md) · [问题反馈](https://github.com/ggbqq0-lang/apple-tv-/issues) · [隐私说明](PRIVACY.md)

## 下载与连接

请在 [Releases](https://github.com/ggbqq0-lang/apple-tv-/releases/latest) 下载 **AppleTV-Remote-Keyboard-0.5.0.apk**。也可下载分享 ZIP，解压后打开其中的 APK。按安卓提示允许当前文件管理器或浏览器安装未知应用。

1. 手机使用 Android 9 或更新版本，与 Apple TV 连接同一可互通的局域网。电视可以用网线。
2. 打开应用，进入底部“设备”，搜索并选择自己的 Apple TV。
3. 输入电视当前显示的四位验证码；配对后会自动重连。
4. 首页直接遥控；需要输入文字时，先在电视选中输入框并显示键盘，再进入手机“键盘”页。

每部新手机都需与自己的电视配对。更新请直接覆盖安装，保留原配对记录。

## 功能

- 圆形触控板：外圈点方向，中央轻点确定，滑动移动并提供系统触感，松手停止。
- 中央、独立确认与键盘页确认支持持续按住，松手结束；电视选中退格后可长按连续删除，取决于目标应用支持。
- 返回、TV、播放/暂停、电源、音量加减；返回与 TV 支持长按。
- 文字默认实时同步；支持中文选字、Unicode、剪贴板主动读取、整段替换与追加。
- 键盘页固定方向、返回和确认；手机输入法展开时仍位于其上方。操作遥控前先同步尚未发送的已确认文字，随后暂停同步，继续输入时重新开启开关。
- 自动发现、手动 IP/Companion 端口、多设备管理、加密凭据保存。

## 兼容性与限制

目前已在 Android 16 手机与 Apple TV 4K 第三代完成配对、中文输入、方向滑动、长按与实时同步的用户试用。**尚未验证所有手机、tvOS 版本或第三方应用。**

- 目标应用需支持 Companion 远程输入。部分应用或密码框可能不兼容；电视输入框必须获得焦点。
- 音量和静音分别检测。只有能读取并核对绝对音量时才开放网络静音；静音灰显不影响已支持的音量加减。不使用红外或品牌专用码。
- 文字替换由清空和插入事件组成，不能保证原子性。回读异常时暂停，连接恢复不会自动重发文字。
- 64 KiB 是本地保护上限，并非实测协议极限。未实现 Siri、蓝牙 HID 或绕圈时间线。
- 当前为个人侧载实验版，未上架应用商店。Apple、Apple TV 是其各自权利人的商标，本项目为独立社区项目。

## 隐私

不保存文字历史，不向开发者上传输入或设备数据；剪贴板只在主动点击时读取。配对凭据经 Android Keystore 加密保存在本机；已禁用系统备份。普通文字显示，不提供敏感内容模式。窗口使用 FLAG_SECURE，截图或最近任务预览可能呈黑屏。详见 [PRIVACY.md](PRIVACY.md)。

## 从源码构建

需要 JDK 17、Android SDK Platform 36 / Build Tools 36.0.0。Android Studio 打开本目录，配置本机 SDK。

```sh
./gradlew assembleDebug
./gradlew assembleRelease lintRelease
```

产物在 `app/build/outputs/apk/`。仓库不包含本机 SDK 路径、签名私钥或配对信息。源码自行构建会使用构建者自己的本地证书；它无法覆盖官方 Releases 的签名包。官方发布由维护者保管现有证书，以维持升级兼容。

## 检查与贡献

```sh
python3.12 -m venv .venv
.venv/bin/python -m pip install -r mac_probe/requirements.lock.txt
.venv/bin/python -m mac_probe.run_checks
./gradlew assembleDebug lintDebug
```

一键检查会启动仅监听 `127.0.0.1` 的独立 pyatv 模拟设备，运行协议与 Python 测试并关闭模拟进程。单独执行 `./gradlew test` 会跳过需要模拟端的集成测试。最近本机结果为 Python 47 项、JVM 53 项通过，14 组 RTI 数据独立解码；模拟检查不能代替实际电视验收，见 [验证范围](docs/VERIFICATION.md)。

[贡献指南](CONTRIBUTING.md) · [社区准则](CODE_OF_CONDUCT.md) · [安全问题报告](SECURITY.md) · [更新记录](CHANGELOG.md)

## 许可证与致谢

项目采用 [MIT License](LICENSE)。Companion、HAP 与 RTI 协议实现参考 [pyatv](https://github.com/postlund/pyatv)，保留 Pierre Ståhl 的 MIT 声明；加密使用 Bouncy Castle。第三方许可与完整许可证文本见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 及应用 assets。
