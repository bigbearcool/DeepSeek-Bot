# dsh 鸿蒙手机端

纯血鸿蒙（HarmonyOS NEXT）原生 App，功能对齐 `dsh-mobile-android` 0.2.4，协议沿用 `dsh-mobile-bridge`（`/api/mobile-bridge/v1`）。

- 包名：`com.deepseek.dshmobile.harmony`（与 Android 区分）
- 技术栈：ArkTS + ArkUI，Stage 模型，单 HAP
- 兼容：`compatibleSdkVersion 6.0.0(20)`，`targetSdkVersion 26.0.0`

## 功能
配对（扫码/手填）、会话列表（未读、时间、正在回复）、单聊（历史、实时流式、停止、Markdown）、群聊（多成员实时回复、@ 选择器、全角＠、谁会回复提示）、建群、群设置（回复方式、改名、成员）、图片和文件收发、审批同意/拒绝、助手资料与切模型、新建助手（模型、思考深度）、搜索、本地通知与静音、重新配对、深色模式、G8 图标。

## 构建（命令行，无需登录）
```sh
D=/Applications/DevEco-Studio.app/Contents
export JAVA_HOME=$D/jbr/Contents/Home DEVECO_SDK_HOME=$D/sdk
export PATH=$JAVA_HOME/bin:$D/tools/node/bin:$D/tools/ohpm/bin:$D/tools/hvigor/bin:$PATH
ohpm install
hvigorw assembleHap -p buildMode=debug --no-daemon   # 产物 entry/build/default/outputs/default/entry-default-unsigned.hap
hvigorw test -p module=entry@default --no-daemon     # 本地单测
```
装到真机需要签名：在 DevEco Studio 登录华为开发者账号后用自动签名，再 `hdc install`。

## 已知限制
- 通知只在应用运行时（前台或刚切到后台）能发出，没有接 Push Kit。
- 文件附件只显示卡片，不能在手机上直接打开。
