package com.fuoins.momoreviewball;

import android.app.Activity;
import android.app.Instrumentation;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** LSPosed 入口：只在墨墨背单词进程里挂载。 */
public class ModuleEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MoMoBall";
    private static final String PACKAGE_NAME = "com.maimemo.android.momo";
    private static final String REVIEW_ACTIVITY = "com.maimemo.android.momo.revision.ReviewActivity";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || !PACKAGE_NAME.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": loaded in " + lpparam.processName);

        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnResume",
                    Activity.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.args[0];
                            if (isReviewActivity(activity)) {
                                BallController.attach(activity);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook callActivityOnResume failed");
            XposedBridge.log(t);
        }

        // 某些 ROM 会跳过 Instrumentation 回调，补一个 Activity.onResume 兜底。
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (isReviewActivity(activity)) {
                        BallController.attach(activity);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onResume failed");
            XposedBridge.log(t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    if (isReviewActivity(activity)) {
                        BallController.detach(activity);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onDestroy failed");
            XposedBridge.log(t);
        }
    }

    private boolean isReviewActivity(Activity activity) {
        if (activity == null) return false;
        try {
            if (activity.getIntent() != null && activity.getIntent().getComponent() != null
                    && REVIEW_ACTIVITY.equals(activity.getIntent().getComponent().getClassName())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        Class<?> clazz = activity.getClass();
        while (clazz != null) {
            if (REVIEW_ACTIVITY.equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }
}
