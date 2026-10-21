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

## 流式消息回归

桥接的 `text` 是增量，不按正文前缀猜测快照。正式回复到达时结算临时气泡；若结束事件先到，临时气泡等待历史补偿，不另存一条未确认回复。历史刷新保留已接收的新消息和已加载的旧页，订阅及异步回包绑定当前会话。

在仓库已有 `ds-bot/node_modules/esbuild`（`cd ds-bot && npm ci`）的环境，还可执行无密钥的本地测试：

```sh
node scripts/test-logic.cjs
node --test scripts/test-state.cjs
```

前者执行纯逻辑测试，后者用页面的实际控制器方法验证事件交错；两者均不替代 DevEco 编译及真机 UI 验收。`Streaming.test.ets` 同时注册到鸿蒙测试入口。

已读确认使用前台会话实际展示到的消息序号；进入、接收消息、历史补偿和返回列表均会同步。返回列表等待已读请求完成，迟到列表仅在服务端序号未超过已确认位置时清零角标。后台消息和离开后新增回复保留未读；同步失败保留重试队列并显示提示。

## 已有助手的思考模式

助手资料页以两个上下排列的下拉框选择模型与思考强度，按当前模型查询可用档位，可立即保存档位或恢复「模型默认」。切换模型会清除旧模型的档位覆盖，再加载新模型的能力；无档位的模型禁用思考强度下拉框并说明原因。查询失败显示重试，保存失败保留原选择。需要配套桥接插件在助手资料及保存响应中返回 `effort`，旧版桥接会提示更新。
