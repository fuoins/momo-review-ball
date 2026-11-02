package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（传统 Xposed API 82 模式，依赖 assets/xposed_init 加载）。
 *
 * <p>刻意不使用 META-INF/xposed/module.prop 的现代模块格式：那种格式要求入口实现
 * libxposed 的 XposedModule / IXposedMod 接口，只实现 IXposedHookLoadPackage 时
 * LSPosed 会按新格式加载而我们又拿不到回调，表现为「模块装了但完全没反应」。
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MoMoBall";
    private static final String PACKAGE_NAME = "com.maimemo.android.momo";

    static volatile boolean sHooked = false;
    static volatile String sProcessName = "";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !PACKAGE_NAME.equals(lpparam.packageName)) return;

        sProcessName = lpparam.processName == null ? "" : lpparam.processName;
        XposedBridge.log(TAG + ": 模块已注入 process=" + sProcessName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        hookApplicationOnCreate();

        // 三个入口互为兜底：不同 ROM / 加固方案下命中情况不一样。
        hookInstrumentationResume();
        hookActivityResume();
        hookWindowFocusChanged();
        hookActivityDestroy();

        sHooked = true;
        XposedBridge.log(TAG + ": hook 安装完成");
    }

    private void hookApplicationOnCreate() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Context context = (Context) param.thisObject;
                    // 进程启动就在后台线程把配置拉一次，避免第一次进复习页时同步读取。
                    new Thread(() -> {
                        Config.Client.get(context);
                        XposedBridge.log(TAG + ": 配置预读 " + Config.Client.describeSource());
                    }, "momo-ball-config").start();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Application.onCreate 失败: " + t);
        }
    }

    private void hookInstrumentationResume() {
        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume",
                    Activity.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0) return;
                            BallController.maybeAttach((Activity) param.args[0], "instrumentation");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook callActivityOnResume 失败: " + t);
        }
    }

    private void hookActivityResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    BallController.maybeAttach((Activity) param.thisObject, "onResume");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onResume 失败: " + t);
        }
    }

    /** 从后台切回、Fragment 事务结束后补一次探测，覆盖 onResume 之后才 inflate 的页面。 */
    private void hookWindowFocusChanged() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.args[0])) return;
                            BallController.maybeAttach((Activity) param.thisObject, "focus");
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onWindowFocusChanged 失败: " + t);
        }
    }

    private void hookActivityDestroy() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    BallController.detach((Activity) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onDestroy 失败: " + t);
        }
    }
}
