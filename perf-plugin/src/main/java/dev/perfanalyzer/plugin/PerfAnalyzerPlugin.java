package dev.perfanalyzer.plugin;

import com.android.build.api.instrumentation.FramesComputationMode;
import com.android.build.api.instrumentation.InstrumentationScope;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import com.android.build.api.variant.ApplicationVariant;
import kotlin.Unit;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * Applied in an app module: `plugins { id 'dev.perfanalyzer' }`. For the debug
 * build type only, routes every OkHttpClient through PerfOkHttp.instrument() so
 * PerfInterceptor sees all API calls — the Firebase Performance approach.
 * Release builds are never touched.
 */
public class PerfAnalyzerPlugin implements Plugin<Project> {
    static final String DEBUG = "debug";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", applied -> {
            // The rewritten bytecode calls dev.perfanalyzer.PerfOkHttp — without the SDK
            // on the debug classpath, the first OkHttpClient would crash the app.
            String version = PerfAnalyzerPlugin.class.getPackage().getImplementationVersion();
            if (version != null && !"unspecified".equals(version)) {
                project.getDependencies().add(DEBUG + "Implementation", "com.github.parityfix:perf_analyzer:" + version);
            }

            ApplicationAndroidComponentsExtension components =
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
            components.onVariants(components.selector().all(), (Action<ApplicationVariant>) variant -> {
                if (!DEBUG.equals(variant.getBuildType())) return;
                // ALL = app code and its libraries (Coil, Glide etc. build their own clients).
                variant.getInstrumentation().transformClassesWith(
                    OkHttpVisitorFactory.class, InstrumentationScope.ALL, params -> Unit.INSTANCE);
                // Each inserted call takes and returns one reference: stack depth and
                // frames are unchanged, so existing frames can be copied as-is.
                variant.getInstrumentation().setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES);
            });
        });
    }
}
