package com.function.cardissuer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Method;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Chooses a per-service policy from observable type dependencies, with explicit overrides. */
final class IsolationPlanner {
    static final String AUTO = "auto";
    static final String ORIGINAL = "original";
    static final String MINIMAL = "minimal";
    private final String applicationType;
    private final String policy;
    private final List<String> services;
    private final Map<String, Boolean> exports;
    private final Map<String, String> processes;
    private final Set<String> shared;
    private final Map<String, Shape> shapes = new HashMap<>();
    private final Set<String> contextDependentMethods = new HashSet<>();
    private final Map<String, String> superClasses = new HashMap<>();
    private final Set<String> applicationDependentTypes = new HashSet<>();

    IsolationPlanner(BinaryManifest manifest, String policy) {
        if (!policy.equals(AUTO) && !policy.equals(ORIGINAL) && !policy.equals(MINIMAL)) {
            throw new IllegalArgumentException("未知隔离策略");
        }
        this.applicationType = "L" + manifest.applicationClass.replace('.', '/') + ";";
        this.policy = policy;
        this.services = manifest.isolatedServices;
        this.exports = manifest.isolatedExports;
        this.processes = manifest.isolatedProcesses;
        this.shared = manifest.sharedIsolatedServices;
        for (String service : services) shapes.put(service, new Shape());
    }

    void inspect(byte[] dex) {
        if (services.isEmpty()) return;
        int version = Integer.parseInt(new String(dex, 4, 3, java.nio.charset.StandardCharsets.US_ASCII));
        DexBackedDexFile input = new DexBackedDexFile(Opcodes.forDexVersion(version), dex);
        for (ClassDef definition : input.getClasses()) {
            String className = definition.getType();
            superClasses.put(className, definition.getSuperclass());
            List<Shape> owners = new ArrayList<>();
            for (String service : services) {
                String type = "L" + service.replace('.', '/');
                if (className.equals(type + ";") || className.startsWith(type + "$")) {
                    Shape shape = shapes.get(service);
                    shape.types.add(className);
                    owners.add(shape);
                }
            }
            boolean classDependsOnApplication = false;
            for (Method method : definition.getMethods()) {
                if (method.getImplementation() == null) continue;
                boolean appType = false;
                boolean appContext = false;
                for (Instruction instruction : method.getImplementation().getInstructions()) {
                    if (!(instruction instanceof ReferenceInstruction)) continue;
                    Reference reference = ((ReferenceInstruction) instruction).getReference();
                    if (reference instanceof TypeReference) {
                        appType |= ((TypeReference) reference).getType().equals(applicationType);
                    } else if (reference instanceof MethodReference) {
                        MethodReference call = (MethodReference) reference;
                        appContext |= call.getName().equals("getApplication") || call.getName().equals("getApplicationContext");
                        classDependsOnApplication |= call.getDefiningClass().equals(applicationType);
                        for (Shape owner : owners) {
                            owner.calls.add(methodId(call));
                            owner.directApplication |= call.getDefiningClass().equals(applicationType);
                        }
                    } else if (reference instanceof FieldReference) {
                        FieldReference field = (FieldReference) reference;
                        classDependsOnApplication |= field.getDefiningClass().equals(applicationType)
                                || field.getType().equals(applicationType);
                        for (Shape owner : owners) owner.directApplication |= field.getDefiningClass().equals(applicationType)
                                || field.getType().equals(applicationType);
                    }
                }
                if (appType && appContext) contextDependentMethods.add(methodId(method));
                classDependsOnApplication |= appType;
                for (Shape owner : owners) owner.directApplication |= appType;
            }
            if (classDependsOnApplication) applicationDependentTypes.add(className);
        }
    }

    JSONArray plans() throws Exception {
        JSONArray plans = new JSONArray();
        for (String service : services) {
            Shape shape = shapes.get(service);
            boolean dependency = !applicationType.equals("Landroid/app/Application;")
                    && (shape.directApplication || shape.types.stream().anyMatch(this::inheritsApplicationDependency)
                        || shape.calls.stream().anyMatch(contextDependentMethods::contains));
            String mode = policy.equals(AUTO) ? (dependency || shape.types.isEmpty() ? ORIGINAL : MINIMAL) : policy;
            String reason;
            if (!policy.equals(AUTO)) reason = "用户选择的初始化策略";
            else if (shape.types.isEmpty()) reason = "原 DEX 中未找到服务实现，保留原初始化以兼容动态加载";
            else if (dependency) reason = "检测到服务、父类或绑定接口显式引用自定义 Application";
            else reason = "未检测到显式的自定义 Application 类型依赖，使用受限进程基础初始化";
            plans.put(new JSONObject().put("service", service).put("applicationMode", mode).put("reason", reason)
                    .put("exported", Boolean.TRUE.equals(exports.get(service)))
                    .put("process", processes.get(service)));
        }
        Set<String> originalProcesses = new HashSet<>();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.getJSONObject(i);
            if (shared.contains(plan.getString("service")) && plan.getString("applicationMode").equals(ORIGINAL)) {
                originalProcesses.add(plan.getString("process"));
            }
        }
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.getJSONObject(i);
            if (shared.contains(plan.getString("service")) && originalProcesses.contains(plan.getString("process"))
                    && !plan.getString("applicationMode").equals(ORIGINAL)) {
                plan.put("applicationMode", ORIGINAL).put("reason", "同一共享隔离进程内存在原 Application 依赖，统一初始化方式");
            }
        }
        return plans;
    }

    boolean isContextType(String type) {
        for (int depth = 0; type != null && depth < 64; depth++) {
            if (type.equals("Landroid/content/Context;") || type.equals("Landroid/content/ContextWrapper;")
                    || type.equals("Landroid/app/Activity;") || type.equals("Landroid/app/Application;")
                    || type.equals("Landroid/app/Service;") || type.equals("Landroid/view/ContextThemeWrapper;")) return true;
            type = superClasses.get(type);
        }
        return false;
    }

    private boolean inheritsApplicationDependency(String type) {
        for (int depth = 0; type != null && depth < 64; depth++) {
            if (applicationDependentTypes.contains(type)) return true;
            type = superClasses.get(type);
        }
        return false;
    }

    static String report(String policy, JSONArray plans) throws Exception {
        if (plans.length() == 0) return "标准组件加载，未声明隔离服务。";
        StringBuilder text = new StringBuilder("隔离服务数量  ").append(plans.length());
        text.append("\n策略  ").append(policy.equals(AUTO) ? "自动分析" : policy.equals(ORIGINAL) ? "保留原初始化" : "基础初始化");
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.getJSONObject(i);
            text.append("\n\n").append(plan.getString("service"));
            text.append("\n").append(plan.getString("applicationMode").equals(ORIGINAL) ? "保留原 Application" : "基础 Application");
            text.append("\n").append(plan.getString("reason"));
        }
        text.append("\n\n自动分析覆盖可识别的类型引用。反射、动态注册和应用自定义初始化仍需运行验证，可切换策略重新生成。");
        return text.toString();
    }

    private static String methodId(MethodReference method) {
        return method.getDefiningClass() + "->" + method.getName() + method.getParameterTypes() + method.getReturnType();
    }
    private static final class Shape {
        boolean directApplication;
        final Set<String> calls = new HashSet<>();
        final Set<String> types = new HashSet<>();
    }
}
