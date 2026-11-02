# 墨墨复习悬浮球

让「墨墨背单词」自带的复习悬浮球支持**自定义手势**。基于 LSPosed，不改墨墨本体，不额外造一个球。

> 已适配：墨墨背单词 **5.5.25**（versionCode 846）和 **5.6.00**（versionCode 900）。

## 形态说明

模块接管的是**墨墨自己那个悬浮球**（`rev_floating_btn` / `MMFloatingButton`）：

- 外观、按压动画、拖动跟手、边缘吸附、拖动范围**全部由墨墨原生处理**，模块一概不碰；
- 模块只是在手指抬起的那一刻做一次手势判定：方向命中就执行你的映射，
  同时给原生补一个 `ACTION_CANCEL`，让原生那套固定手势不会跟着一起触发；
- 页面上始终只有一个球，不会出现两个。

模块本身：

- **没有桌面图标**，装完在桌面找不到，也不能直接打开；
- **没有任何 Activity / Service / Provider**，只有 Xposed 入口；
- 所有设置都在墨墨的「手势悬浮球」设置页里，右下角多了个橙色的「模块设置」按钮。

## 功能

- 单击、上滑、下滑、左滑、右滑五种手势，分别配置动作。
- **单词出现前 / 出现后**两套独立映射。
- 动作：认识、模糊、忘记、熟知、发音、显示单词、瞄一眼、返回上一个单词、无操作。
- **长按后可拖动**（墨墨原生行为，边界、吸附都保留）；在设置里打开「锁定位置」后，长按也拖不动。
- 位置由墨墨自己持久化，模块不覆盖、不改写。
- 振动反馈、模块开关可在设置里调。

## 默认映射

| 手势 | 单词出现前 | 单词出现后 |
| --- | --- | --- |
| 单击 | 显示单词 | 认识 |
| 上滑 | 发音 | 熟知 |
| 下滑 | 瞄一眼 | 忘记 |
| 左滑 | 返回上一个单词 | 模糊 |
| 右滑 | 无操作 | 返回上一个单词 |

「单词出现前」指还没点开答案、页面提示「请回忆…点击屏幕显示答案」的阶段；出现「认识 / 模糊 / 忘记」评分按钮后即为「单词出现后」。

## 安装

1. 从 [Releases](../../releases) 下载 `momo-review-ball-2.1.0.apk` 并安装。**桌面不会出现图标，这是正常的。**
2. 打开 LSPosed，在「模块」中启用 **墨墨复习悬浮球**。
3. 在模块的「作用域」中勾选 **墨墨背单词**。
4. 强制停止墨墨背单词（设置 → 应用 → 墨墨背单词 → 强行停止），再重新打开。
5. 进复习页，自带悬浮球的手势即按默认映射生效。

## 设置

墨墨内进入「手势悬浮球（单手操作）」设置页（设置 → 学习设置 → 手势悬浮球），
页面右下角有橙色 **「模块设置」** 按钮，点开即可配置两套手势映射。

> 该设置页需要墨墨本身开启悬浮球功能才会有意义；如果自带球没开，模块没有可接管的控件。

## 诊断

设置面板里有「启用模块手势」开关，关掉即完全交还给墨墨原生手势。

抓日志：

```bash
adb logcat -s MoMoBall
```

关键日志：

| 日志 | 含义 |
| --- | --- |
| `模块已注入 process=...` | LSPosed 已加载模块 |
| `已监听布局加载` | 已挂上 inflate 探测 |
| `已接管自带悬浮球，原有 listener=...` | 成功找到并接管了自带球 |
| `已在手势设置页注入入口` | 设置页按钮已插入 |
| `gesture=单击 answerShown=false action=show` | 手势识别与动作分发 |

如果动作无效，日志会打印 `action not found: ... visible texts: [...]`，把那一行发出来即可定位。

## 原理与限制

- 墨墨使用梆梆加固（DexHelper）+ native 检测，业务方法静态分析不到。
- 模块通过 Hook `LayoutInflater.inflate`，在返回的视图树里按 **资源 id 名** `rev_floating_btn` 找悬浮球，
  再给它设置 `OnTouchListener` 接管触摸。不依赖类名，所以加固和版本改版都不影响定位。
- 备用路径：Hook `MMFloatingButton` 的构造方法（类能被 classLoader 加载时生效）。
- 复习页判定沿用控件可见性：`rev_cl_operation_bar` 等评分控件真实可见时视为「答案已显示」。
- 动作执行等价于手动点按页面按钮，不修改墨墨 APK、不联网、不上传数据。
- 接管后墨墨原生手势不再触发；关掉「启用模块手势」即可恢复。
- 如果某个大版本改了控件名，需要在 `FloatingButtonHook.ID_NAME` / `ActionExecutor` 里更新。

## 构建

GitHub Actions 自动构建：推送到 `main` 或手动运行 `build` workflow，产物在 Actions 的 Artifacts 中；打 `v*` 标签会自动发布 Release。

本地构建：

```bash
# 需要 JDK 17、Android SDK 34
./gradlew assembleRelease
```

Release 包使用仓库 Secrets 中的固定签名密钥（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`），保证后续版本可以直接覆盖安装；fork 后未配置这些 Secrets 时会自动回退到 debug 签名。

## 目录

```
app/src/main/java/com/fuoins/momoreviewball/
  ModuleEntry.java        LSPosed 入口
  FloatingButtonHook.java 定位并接管墨墨自带悬浮球
  SettingsInjector.java   在墨墨设置页注入「模块设置」入口
  SettingsPanel.java      模块设置面板
  ActionExecutor.java     查找页面控件并执行动作
  Config.java             配置模型（读写墨墨进程内 SharedPreferences）
  Util.java               通用工具
  XposedLog.java          日志包装
```

## 免责声明

仅供个人学习与自用，请遵守墨墨背单词的用户协议。项目与墨墨官方无关。
