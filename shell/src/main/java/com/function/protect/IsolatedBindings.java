package com.function.protect;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.json.JSONArray;

/** Binder capabilities are sent only to explicit isolated components in this protected APK. */
public final class IsolatedBindings {
    static final String BROKER = "com.function.protect.activationBroker.v1";
    static final String DESCRIPTOR = "com.function.protect.ActivationBroker.v1";
    static final String WARMUP = "com.function.protect.WARMUP.";
    static final String STATUS = "com.function.protect.IsolatedStatus.v1";
    private static final List<ServiceConnection> warmConnections = new ArrayList<>();
    private static boolean warming;

    private IsolatedBindings() {}

    public static boolean bindService(Context context, Intent intent, ServiceConnection connection, int flags) {
        return context.bindService(authorize(context, intent), connection, flags);
    }

    public static boolean bindService(Context context, Intent intent, int flags, Executor executor, ServiceConnection connection) {
        return context.bindService(authorize(context, intent), flags, executor, connection);
    }

    public static boolean bindIsolatedService(Context context, Intent intent, int flags, String instance,
            Executor executor, ServiceConnection connection) {
        return context.bindIsolatedService(authorize(context, intent), flags, instance, executor, connection);
    }

    public static Context getApplicationContext(Context context) {
        if (android.os.Process.isIsolated() && ShellRuntime.application != null) return ShellRuntime.application;
        return context.getApplicationContext();
    }

    public static Application getApplication(Context context) {
        if (android.os.Process.isIsolated() && ShellRuntime.application != null) return ShellRuntime.application;
        if (context instanceof android.app.Service) return ((android.app.Service) context).getApplication();
        if (context instanceof android.app.Activity) return ((android.app.Activity) context).getApplication();
        return (Application) context.getApplicationContext();
    }

    static Intent authorize(Context context, Intent original) {
        if (original == null || android.os.Process.isIsolated() || ShellRuntime.activeToken == null) return original;
        ComponentName component = original.getComponent();
        if (component == null && ShellRuntime.info.packageName.equals(original.getPackage())) {
            android.content.pm.ResolveInfo resolved = context.getPackageManager().resolveService(original, 0);
            if (resolved != null && resolved.serviceInfo != null) {
                component = new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name);
            }
        }
        if (component == null || !component.getPackageName().equals(ShellRuntime.info.packageName)) return original;
        JSONArray services = ShellRuntime.config.optJSONArray("isolatedServices");
        if (services == null) return original;
        boolean isolated = false;
        for (int i = 0; i < services.length(); i++) {
            if (component.getClassName().equals(services.optString(i))) isolated = true;
        }
        if (!isolated) return original;
        String token = ShellRuntime.activeToken;
        String buildId = ShellRuntime.config.optString("id");
        Binder broker = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws android.os.RemoteException {
                if (code != IBinder.FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
                data.enforceInterface(DESCRIPTOR);
                int caller = Binder.getCallingUid();
                int appId = caller % 100000;
                if (caller / 100000 != android.os.Process.myUid() / 100000 || appId < 90000 || appId > 99999) {
                    throw new SecurityException("仅允许当前用户的隔离进程获取授权");
                }
                String requested = data.readString();
                if (!buildId.equals(requested)) throw new SecurityException("隔离服务包标识不匹配");
                if (!ShellRuntime.isAuthorized()) throw new SecurityException(ShellRuntime.loadError);
                // Only the explicitly bound receiver holds this Binder capability.
                reply.writeNoException();
                reply.writeString(token);
                return true;
            }
        };
        Intent intent = new Intent(original);
        intent.setComponent(component);
        android.os.Bundle extras = new android.os.Bundle();
        extras.putBinder(BROKER, broker);
        intent.putExtras(extras);
        return intent;
    }

    static synchronized void warmup(Application application) {
        if (warming || android.os.Process.isIsolated() || !ShellRuntime.isAuthorized()) return;
        JSONArray services = ShellRuntime.config.optJSONArray("isolatedServices");
        if (services == null || services.length() == 0) return;
        warming = true;
        for (int i = 0; i < services.length(); i++) {
            String name = services.optString(i);
            org.json.JSONArray plans = ShellRuntime.config.optJSONArray("isolationPlans");
            boolean exported = plans == null;
            if (plans != null) for (int j = 0; j < plans.length(); j++) {
                org.json.JSONObject plan = plans.optJSONObject(j);
                if (plan != null && name.equals(plan.optString("service"))) exported = plan.optBoolean("exported");
            }
            if (!exported) continue;
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName component, IBinder binder) {
                    Parcel request = Parcel.obtain();
                    Parcel response = Parcel.obtain();
                    try {
                        request.writeInterfaceToken(STATUS);
                        if (!binder.transact(IBinder.FIRST_CALL_TRANSACTION, request, response, 0)) {
                            throw new IllegalStateException("隔离服务状态接口不可用");
                        }
                        response.readException();
                        boolean ready = response.readInt() != 0;
                        String detail = response.readString();
                        if (!ready) throw new IllegalStateException(detail);
                        android.util.Log.i("FunctionProtect", "Isolated service ready: " + component.getClassName());
                    } catch (Exception error) {
                        String message = "隔离服务初始化失败，可切换初始化策略重新生成。\n" + component.getClassName() + "\n" + error.getMessage();
                        android.util.Log.e("FunctionProtect", message, error);
                        android.widget.Toast.makeText(application, message, android.widget.Toast.LENGTH_LONG).show();
                    } finally { request.recycle(); response.recycle(); }
                }
                @Override public void onServiceDisconnected(ComponentName component) {}
            };
            try {
                Intent intent = new Intent().setComponent(new ComponentName(application.getPackageName(), name));
                intent.setAction(WARMUP + ShellRuntime.config.optString("id"));
                if (bindService(application, intent, connection, Context.BIND_AUTO_CREATE)) warmConnections.add(connection);
            } catch (RuntimeException error) {
                android.util.Log.e("FunctionProtect", "Isolated service warmup failed: " + name, error);
            }
        }
    }

    static void observe(Application application) {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPreResumed(android.app.Activity activity) {
                if (activity instanceof GateActivity || activity instanceof RestartActivity || ShellRuntime.isAuthorized()) return;
                activity.startActivity(new Intent(activity, GateActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                activity.finish();
            }
            @Override public void onActivityCreated(android.app.Activity activity, android.os.Bundle saved) { warmup(application); }
            @Override public void onActivityStarted(android.app.Activity activity) {}
            @Override public void onActivityResumed(android.app.Activity activity) {}
            @Override public void onActivityPaused(android.app.Activity activity) {}
            @Override public void onActivityStopped(android.app.Activity activity) {}
            @Override public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle out) {}
            @Override public void onActivityDestroyed(android.app.Activity activity) {}
        });
    }
}
