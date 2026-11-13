package com.function.cardissuer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction3rc;
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.Rewriter;
import org.jf.dexlib2.rewriter.RewriterModule;
import org.jf.dexlib2.rewriter.Rewriters;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;

/** Keeps instruction lengths/registers unchanged while routing framework calls through the shell. */
final class IsolatedDexAdapter {
    private static final String CONTEXT = "Landroid/content/Context;";
    private static final List<String> BIND = Arrays.asList("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", "I");
    private static final List<String> BIND_EXECUTOR = Arrays.asList("Landroid/content/Intent;", "I", "Ljava/util/concurrent/Executor;", "Landroid/content/ServiceConnection;");
    private static final String FLAGS = "Landroid/content/Context$BindServiceFlags;";
    private static final List<String> BIND_FLAGS = Arrays.asList("Landroid/content/Intent;", "Landroid/content/ServiceConnection;", FLAGS);
    private static final List<String> BIND_FLAGS_EXECUTOR = Arrays.asList("Landroid/content/Intent;", FLAGS, "Ljava/util/concurrent/Executor;", "Landroid/content/ServiceConnection;");
    private static final List<String> ISOLATED = Arrays.asList("Landroid/content/Intent;", "I", "Ljava/lang/String;", "Ljava/util/concurrent/Executor;", "Landroid/content/ServiceConnection;");
    private static final List<String> ISOLATED_FLAGS = Arrays.asList("Landroid/content/Intent;", FLAGS, "Ljava/lang/String;", "Ljava/util/concurrent/Executor;", "Landroid/content/ServiceConnection;");

    private IsolatedDexAdapter() {}

    static byte[] prepareShell(byte[] dex) throws Exception {
        int version = Integer.parseInt(new String(dex, 4, 3, java.nio.charset.StandardCharsets.US_ASCII));
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<String> getTypeRewriter(Rewriters rewriters) {
                return type -> type.replace("Lorg/lsposed/hiddenapibypass/", "Lcom/function/protect/runtimebridge/");
            }
        });
        return write(rewriter, new DexBackedDexFile(Opcodes.forDexVersion(version), dex));
    }

    static byte[] adapt(byte[] dex, java.util.function.Predicate<String> contextTypes) throws Exception {
        int version = Integer.parseInt(new String(dex, 4, 3, java.nio.charset.StandardCharsets.US_ASCII));
        DexBackedDexFile input = new DexBackedDexFile(Opcodes.forDexVersion(version), dex);
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> rewrite(instruction, contextTypes);
            }
        });
        return write(rewriter, input);
    }

    private static byte[] write(DexRewriter rewriter, DexBackedDexFile input) throws Exception {
        MemoryDataStore output = new MemoryDataStore();
        try {
            DexPool.writeTo(output, rewriter.getDexFileRewriter().rewrite(input));
            return output.getData();
        } finally { output.close(); }
    }

    private static Instruction rewrite(Instruction instruction, java.util.function.Predicate<String> contextTypes) {
        if (instruction.getOpcode() != Opcode.INVOKE_VIRTUAL
                && instruction.getOpcode() != Opcode.INVOKE_VIRTUAL_RANGE) return instruction;
        if (!(instruction instanceof ReferenceInstruction)
                || !(((ReferenceInstruction) instruction).getReference() instanceof MethodReference)) return instruction;
        MethodReference method = (MethodReference) ((ReferenceInstruction) instruction).getReference();
        String owner = method.getDefiningClass();
        // Route framework Context operations through the protected runtime.
        if (!contextTypes.test(owner)) return instruction;
        List<String> parameters = new ArrayList<>();
        for (CharSequence parameter : method.getParameterTypes()) parameters.add(parameter.toString());
        boolean binding = method.getName().equals("bindService") && method.getReturnType().equals("Z")
                && (parameters.equals(BIND) || parameters.equals(BIND_EXECUTOR)
                    || parameters.equals(BIND_FLAGS) || parameters.equals(BIND_FLAGS_EXECUTOR));
        binding |= method.getName().equals("bindIsolatedService") && method.getReturnType().equals("Z")
                && (parameters.equals(ISOLATED) || parameters.equals(ISOLATED_FLAGS));
        boolean applicationContext = method.getName().equals("getApplicationContext")
                && parameters.isEmpty() && method.getReturnType().equals(CONTEXT);
        boolean application = method.getName().equals("getApplication") && parameters.isEmpty()
                && method.getReturnType().equals("Landroid/app/Application;");
        if (!binding && !applicationContext && !application) return instruction;
        String helper = parameters.contains(FLAGS) ? "Lcom/function/protect/Api34Bindings;" : "Lcom/function/protect/IsolatedBindings;";
        parameters.add(0, CONTEXT);
        MethodReference replacement = new ImmutableMethodReference(helper,
                method.getName(), parameters, method.getReturnType());
        if (instruction instanceof Instruction35c) {
            Instruction35c call = (Instruction35c) instruction;
            return new ImmutableInstruction35c(Opcode.INVOKE_STATIC, call.getRegisterCount(),
                    call.getRegisterC(), call.getRegisterD(), call.getRegisterE(), call.getRegisterF(), call.getRegisterG(), replacement);
        }
        Instruction3rc call = (Instruction3rc) instruction;
        return new ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, call.getStartRegister(), call.getRegisterCount(), replacement);
    }
}
