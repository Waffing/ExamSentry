# 考境哨兵 0.1.0-preview 第三方组件与模型声明

项目作者：**Waffing** · [源代码](https://github.com/Waffing/ExamSentry) · [公开联系邮箱](mailto:203458615@qq.com)

项目自有代码采用 MIT 许可，版权为 `Copyright (c) 2026 Waffing`。MIT 不替代模型、SDK、系统组件及其他第三方内容各自的许可。

以下列出直接使用的主要组件与随包模型。各组件及传递依赖的版权、商标和许可归其权利人所有；完整适用条款以固定版本的发行包、Maven 元数据和官方许可为准。本软件与学习通、原参考网站、Google、Microsoft 或 Silero 不存在由本文件声明的授权、认证或背书关系。

## 主要组件

| 组件 | 本工程版本 | 用途 | 许可与官方来源 |
|---|---|---|---|
| Kotlin | 2.2.21 | 原生程序语言与编译插件 | [Apache License 2.0](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt) |
| Android Gradle Plugin | 8.13.2 | Android 构建工具 | [Android 开源项目许可](https://source.android.com/docs/setup/about/licenses) |
| AndroidX / Jetpack Compose / Material | Compose BOM 2025.10.00；Activity 1.11.0；Lifecycle 2.9.4；Core 1.17.0 | 原生界面、生命周期与系统兼容 | [AndroidX 源码与许可](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)；各发行包声明适用 |
| Room | 2.8.3 | 本地会话、事件与覆盖记录 | AndroidX，Apache License 2.0 |
| CameraX | 1.5.0 | 本地相机预览与图像分析 | AndroidX，Apache License 2.0 |
| AndroidX WebKit | 1.14.0 | 本地资源加载与受限 WebView 消息接口 | AndroidX，Apache License 2.0；系统 WebView 有自身第三方声明 |
| ML Kit Face Detection | 16.1.7（随包模型版本） | 本机人脸数量估计 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms)；该版本 Maven POM 明确引用此条款，不应笼统标为 Apache 开源模型 |
| ONNX Runtime Android | 1.23.2 | 本地 VAD 模型推理 | [MIT License](https://github.com/microsoft/onnxruntime/blob/v1.23.2/LICENSE)；另见发行包第三方声明 |
| Silero VAD | v6.2，固定提交如下 | 本地语音活动检测模型 | [MIT License](https://github.com/snakers4/silero-vad/blob/be95df9152c0d7618fa1edfeb296fc3dae32376f/LICENSE) |
| JUnit / AndroidX Test | JUnit 4.13.2；Runner 1.7.0；Ext JUnit 1.3.0 | 开发测试，不作为摄像头准确率证明 | [JUnit EPL 1.0](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)；AndroidX Test 发行包许可 |
| ipify | 在线服务，无模型随包 | 用户可选出口 IP 检查 | [ipify 官方说明](https://www.ipify.org/)；默认关闭，开启后服务能看到请求出口地址 |

ML Kit 使用随包人脸模型在设备上处理帧，应用代码不保存或上传原始人脸画面。其官方条款和 [Android 数据披露说明](https://developers.google.com/ml-kit/android-data-disclosure) 包括性能、API 使用、设备及应用等诊断指标；当前公开 `MlKit` API 未提供已确认可用的指标收集关闭开关。

本版本移除了自动初始化提供程序，用户开启视觉检测时才通过公开 `MlKit.initialize(Context)` 初始化。应用 Network Security Config 默认不信任系统公共根证书，仅对 `api64.ipify.org` 配置系统信任，以阻止遵循该配置的其他标准 TLS 诊断连接。随包模型不需要首次下载即可运行。这是应用进程标准 TLS 的限制措施，不是对原生自定义网络栈、外部系统服务或整台手机流量的绝对封锁保证。

应用自行实现的在线网络检查仅在用户开启后请求 ipify，事件记录和音视频不随该请求发送；部署时仍应结合具体系统与 SDK 版本验证实际网络行为。

## 随包 Silero 模型来源

- 上游仓库：`https://github.com/snakers4/silero-vad`
- 标签：`v6.2`
- 固定提交：`be95df9152c0d7618fa1edfeb296fc3dae32376f`
- 上游文件：`src/silero_vad/data/silero_vad.onnx`
- 应用资源：`assets/models/silero_vad_v6.2.onnx`
- 文件长度：`2327524` 字节
- SHA256：`1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`
- 16 kHz 单声道，512 个新采样点/帧，64 点上下文，循环状态形状 `[2,1,128]`。
- 模型来源、许可和验证说明也随 APK 放在 `assets/models/provenance.json`、`assets/models/LICENSE-silero.txt`。

Silero 输出表示语音活动概率，不代表语音文本、说话人身份、对话事实或作弊概率。上游测试音频只用于开发时推理检查，没有作为用户考试录音分发。

## Silero MIT 许可全文

```text
MIT License

Copyright (c) 2020-present Silero Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 参考网页与离线专项

[考试页监控能力实测台](https://xxt.xswk.shop/) 用于确定待覆盖的行为与系统权限边界。本应用的原生模块、离线 HTML/JavaScript 和界面独立编写，不加载原站脚本、上报地址或题目内容。

离线专项使用系统 WebView 的 Canvas、WebGL、WebRTC、BroadcastChannel 和标准 DOM 事件。各接口是否可用由设备 WebView 决定；接口受限时保留受限状态。浏览器水印属于本地样本核验功能，不构成外部平台自动取证服务。

## 再分发注意

重新分发应用或源码时，应保留模型来源、许可证及依赖发行包要求的版权和第三方声明。变更组件版本后须重新核对实际 POM 与随包许可；本文件不是对全部传递依赖许可证的自动审计报告。
