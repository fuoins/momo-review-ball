package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口（传统 Xposed API 82 模式，依赖 assets/xposed_init 加载）。
 *
 * <p>模块没有桌面图标，也不提供独立界面：
 * <ul>
 *   <li>手势输入接管墨墨自带的复习悬浮球，见 {@link FloatingButtonHook}；</li>
 *   <li>配置入口注入墨墨的「手势悬浮球」设置页，见 {@link SettingsInjector}。</li>
 * </ul>
 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String PACKAGE_NAME = "com.maimemo.android.momo";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !PACKAGE_NAME.equals(lpparam.packageName)) return;

        XposedLog.i("模块已注入 process=" + lpparam.processName
                + " sdk=" + android.os.Build.VERSION.SDK_INT);

        FloatingButtonHook.install(lpparam.classLoader);
        hookApplicationOnCreate();
        hookActivityResume();
        hookWindowFocusChanged();
    }

    private void hookApplicationOnCreate() {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Context context = (Context) param.thisObject;
                    new Thread(() -> {
                        Config.get(context);
                        // 加固应用的类可能晚于入口才可加载，这里补一次尝试。
                        FloatingButtonHook.install(context.getClassLoader());
                        XposedLog.i("初始化完成，配置已预读");
                    }, "momo-ball-init").start();
                }
            });
        } catch (Throwable t) {
            XposedLog.e("hook Application.onCreate 失败", t);
        }
    }

    private void hookActivityResume() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    SettingsInjector.maybeInject((Activity) param.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedLog.e("hook Activity.onResume 失败", t);
        }
    }

    /** 从后台切回、页面重建后补一次注入。 */
    private void hookWindowFocusChanged() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onWindowFocusChanged",
                    boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!Boolean.TRUE.equals(param.args[0])) return;
                            SettingsInjector.maybeInject((Activity) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedLog.e("hook onWindowFocusChanged 失败", t);
        }
    }
}
