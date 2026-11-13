package com.function.protect;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** The packager relocates the bundled bridge to avoid dependency collisions with the source APK. */
final class RuntimeBridge {
    private RuntimeBridge() {}

    static void prepare() {
        if (!HiddenApiBypass.addHiddenApiExemptions("Landroid/app/Service;", "Landroid/app/Application;")) {
            throw new IllegalStateException("当前系统不支持隔离服务上下文恢复");
        }
    }

    static void attachApplication(Application application, Context context) throws Exception {
        Method attach = Application.class.getDeclaredMethod("attach", Context.class);
        attach.setAccessible(true);
        attach.invoke(application, context);
    }

    static void attachService(Service service, Service host, Context context, String name, Application application)
            throws Exception {
        Method attach = null;
        for (Method method : Service.class.getDeclaredMethods()) {
            if (method.getName().equals("attach") && method.getParameterCount() == 6) attach = method;
        }
        if (attach == null) throw new IllegalStateException("当前系统的 Service 上下文接口不受支持");
        attach.setAccessible(true);
        attach.invoke(service, context, field(host, "mThread"), name, field(host, "mToken"),
                application, field(host, "mActivityManager"));
    }

    private static Object field(Service service, String name) throws Exception {
        Field field = Service.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(service);
    }
}
