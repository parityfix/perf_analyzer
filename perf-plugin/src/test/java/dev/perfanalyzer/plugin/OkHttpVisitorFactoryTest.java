package dev.perfanalyzer.plugin;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

// Feeds hand-written call sequences through the rewrite and records the calls
// that come out — no okhttp or AGP needed. A wrong insertion here is a
// VerifyError in every debug build, so the subclass-super() case matters most.
public class OkHttpVisitorFactoryTest {
    private static List<String> rewrite(java.util.function.Consumer<MethodVisitor> body) {
        List<String> calls = new ArrayList<>();
        ClassVisitor sink = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                        calls.add(owner + "." + n);
                    }
                };
            }
        };
        body.accept(OkHttpVisitorFactory.wrap(sink).visitMethod(Opcodes.ACC_PUBLIC, "m", "()V", null, null));
        return calls;
    }

    @Test
    public void builderBuildIsInstrumented() {
        List<String> calls = rewrite(mv -> mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OkHttpVisitorFactory.BUILDER, "build", "()Lokhttp3/OkHttpClient;", false));
        assertEquals(List.of("dev/perfanalyzer/PerfOkHttp.instrument", "okhttp3/OkHttpClient$Builder.build"), calls);
    }

    @Test
    public void newClientIsInstrumentedAfterConstruction() {
        List<String> calls = rewrite(mv -> {
            mv.visitTypeInsn(Opcodes.NEW, OkHttpVisitorFactory.CLIENT);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OkHttpVisitorFactory.CLIENT, "<init>", "()V", false);
        });
        assertEquals(List.of("okhttp3/OkHttpClient.<init>", "dev/perfanalyzer/PerfOkHttp.instrument"), calls);
    }

    @Test
    public void subclassSuperCallIsLeftAlone() {
        // class MyClient : OkHttpClient() — super() has no NEW and nothing left on the stack.
        List<String> calls = rewrite(mv -> mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OkHttpVisitorFactory.CLIENT, "<init>", "()V", false));
        assertEquals(List.of("okhttp3/OkHttpClient.<init>"), calls);
    }

    @Test
    public void okHttpAndSdkClassesAreSkipped() {
        assertEquals(false, OkHttpVisitorFactory.instrumentable("okhttp3.internal.connection.RealCall"));
        assertEquals(false, OkHttpVisitorFactory.instrumentable("dev.perfanalyzer.PerfAnalyzer"));
        assertEquals(true, OkHttpVisitorFactory.instrumentable("com.example.app.ApiModule"));
    }
}
