package com.function.protect;

import com.function.protocol.ActivationKeys;

/** Each authorized app process also enforces the signed usage deadline while running. */
final class UsageGuard {
    private static boolean started;
    private UsageGuard() {}

    static synchronized void start(ActivationKeys.Policy policy) {
        if (started || policy == null || policy.usageExpiresAt == 0) return;
        started = true;
        Thread watcher = new Thread(() -> {
            while (true) {
                try {
                    ActivationKeys.ensureUsageTime(policy);
                    Thread.sleep(1000);
                } catch (java.security.GeneralSecurityException error) {
                    android.util.Log.w("DoroEncrypt", error.getMessage());
                    android.os.Process.killProcess(android.os.Process.myPid());
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    android.os.Process.killProcess(android.os.Process.myPid());
                    return;
                }
            }
        }, "doro-usage-deadline");
        watcher.setDaemon(true);
        watcher.start();
    }
}
