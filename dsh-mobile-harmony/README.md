# dsh 鸿蒙手机端

纯血鸿蒙（HarmonyOS NEXT）原生 App，功能对齐 `dsh-mobile-android` 0.2.4，协议沿用 `dsh-mobile-bridge`（`/api/mobile-bridge/v1`）。

- 包名：`com.deepseek.dshmobile.harmony`（与 Android 区分）
- 技术栈：ArkTS + ArkUI，Stage 模型，单 HAP
- 兼容：`compatibleSdkVersion 6.0.0(20)`，`targetSdkVersion 26.0.0`

## 功能
配对（扫码/手填）、会话列表（未读、时间、正在回复）、单聊（历史、实时流式、停止、Markdown）、群聊（多成员实时回复、@ 选择器、全角＠、谁会回复提示）、建群、群设置（回复方式、改名、成员）、图片和文件收发、审批同意/拒绝、助手资料与切模型、助手外观（形象和颜色，和电脑上同一套角色头像）、新建助手（外观、模型、思考深度、工作目录与浏览、工作方式预设）、图片点开看大图并存到图库、文件用手机上的应用打开或另存、长按消息复制或引用回复、按时间分隔、助手之间的往来消息折叠、会话置顶/标为未读/隐藏/静音、浅色深色切换、搜索、本地通知、重新配对、G8 图标。

## 构建（命令行，无需登录）
```sh
D=/Applications/DevEco-Studio.app/Contents
export JAVA_HOME=$D/jbr/Contents/Home DEVECO_SDK_HOME=$D/sdk
export PATH=$JAVA_HOME/bin:$D/tools/node/bin:$D/tools/ohpm/bin:$D/tools/hvigor/bin:$PATH
ohpm install
hvigorw assembleHap -p buildMode=debug --no-daemon   # 产物 entry/build/default/outputs/default/entry-default-unsigned.hap
hvigorw test -p module=entry@default --no-daemon     # 本地单测
```
装到真机需要签名：在 DevEco Studio 打开工程用自动签名，会把 `signingConfigs` 写进 `build-profile.json5`——这部分只留在本机，不要提交。然后 `hdc install -r entry/build/default/outputs/default/entry-default-signed.hap`，`hdc shell aa start -a EntryAbility -b com.deepseek.dshmobile.harmony`。

## 已知限制
- 通知只在应用运行时（前台或刚切到后台）能发出，没有接 Push Kit。
- 群聊只能看到电脑端保留的最近消息（电脑上只留最近 60 行），无法继续往前翻；单聊可以往前翻。
