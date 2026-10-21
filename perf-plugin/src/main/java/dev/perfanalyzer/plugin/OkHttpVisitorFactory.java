package dev.perfanalyzer.plugin;

import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites the two ways an app creates an OkHttpClient:
 *   builder.build()   → PerfOkHttp.instrument(builder).build()
 *   new OkHttpClient() → PerfOkHttp.instrument(new OkHttpClient())
 */
public abstract class OkHttpVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    static final String BUILDER = "okhttp3/OkHttpClient$Builder";
    static final String CLIENT = "okhttp3/OkHttpClient";
    static final String HOOK = "dev/perfanalyzer/PerfOkHttp";

    @Override
    public boolean isInstrumentable(ClassData classData) {
        return instrumentable(classData.getClassName());
    }

    // Skips OkHttp itself, and the SDK (its own WebSocket client must not be intercepted).
    static boolean instrumentable(String className) {
        return !className.startsWith("okhttp3.") && !className.startsWith("dev.perfanalyzer.");
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext classContext, ClassVisitor next) {
        return wrap(next);
    }

    // Separate from createClassVisitor so it can be exercised without AGP (see OkHttpVisitorFactoryTest).
    static ClassVisitor wrap(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new HookCalls(super.visitMethod(access, name, descriptor, signature, exceptions));
            }
        };
    }

    static final class HookCalls extends MethodVisitor {
        // `new OkHttpClient()` seen but not yet constructed. A subclass constructor's
        // super() call is the same INVOKESPECIAL with no NEW before it — and nothing
        // on the stack to pass along — so only constructions we saw start count.
        private int pendingNew = 0;

        HookCalls(MethodVisitor next) {
            super(Opcodes.ASM9, next);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && CLIENT.equals(type)) pendingNew++;
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            boolean build = opcode == Opcodes.INVOKEVIRTUAL && BUILDER.equals(owner) && "build".equals(name);
            if (build) {
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "instrument", "(L" + BUILDER + ";)L" + BUILDER + ";", false);
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            boolean construct = opcode == Opcodes.INVOKESPECIAL && CLIENT.equals(owner) && "<init>".equals(name) && "()V".equals(descriptor);
            if (construct && pendingNew > 0) {
                pendingNew--;
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "instrument", "(L" + CLIENT + ";)L" + CLIENT + ";", false);
            }
        }
    }
}
