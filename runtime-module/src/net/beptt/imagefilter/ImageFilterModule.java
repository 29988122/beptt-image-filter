package net.beptt.imagefilter;

import android.content.SharedPreferences;
import android.util.Log;
import android.os.Build;
import io.github.libxposed.api.XposedModule;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImageFilterModule extends XposedModule {
    private static final String TARGET = "y_studio.ka5983.ptt2";
    private static final String TAG = "BePTT Image Filter";
    private final ThreadLocal<Line> active = new ThreadLocal<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean reportedFilter = new AtomicBoolean();
    private String lastConfig;
    private BepttUrlRules lastRules;

    private static final class Line {
        final String text;
        final List<?> links;
        final BepttUrlRules rules;
        int calls;

        Line(String text, List<?> links, BepttUrlRules rules) {
            this.text = text;
            this.links = links;
            this.rules = rules;
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()
                || !started.compareAndSet(false, true)) return;
        try {
            install(param);
        } catch (Exception | LinkageError | io.github.libxposed.api.error.HookFailedError failure) {
            log(Log.WARN, TAG, "No filter installed. APK layout or Xposed environment unavailable.", failure);
        }
    }

    private void install(PackageReadyParam param) throws Exception {
        Fingerprint.Site site = Fingerprint.locate(new File(param.getApplicationInfo().sourceDir));
        ClassLoader loader = param.getClassLoader();
        Class<?> owner = Class.forName(Fingerprint.className(site.parser.getDefiningClass()), false, loader);
        Class<?> lineType = Class.forName(Fingerprint.className(site.text.getDefiningClass()), false, loader);
        Class<?> output = Class.forName(Fingerprint.className(site.parser.getParameterTypes().get(1).toString()), false, loader);
        Class<?> conversion = Class.forName(Fingerprint.className(site.converter.getDefiningClass()), false, loader);
        Method parser = owner.getDeclaredMethod(site.parser.getName(), lineType, output);
        Method converter = conversion.getDeclaredMethod(site.converter.getName(), Iterable.class);
        Field text = lineType.getDeclaredField(site.text.getName());
        Field links = lineType.getDeclaredField(site.links.getName());
        text.setAccessible(true);
        links.setAccessible(true);
        SharedPreferences prefs = getRemotePreferences(Config.GROUP);

        HookHandle converterHook = hook(converter).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
            Object result = chain.proceed();
            Line line = active.get();
            if (line == null || !(result instanceof List<?>)) return result;
            try {
                if (!calledDirectlyByParser(site, parser, converter) || ++line.calls != 2) return result;
                List<?> images = (List<?>) result;
                ArrayList<Object> kept = new ArrayList<>(images.size());
                for (Object image : images) {
                    if (!(image instanceof String) || !line.rules.hides((String) image, line.text, line.links)) {
                        kept.add(image);
                    }
                }
                if (kept.size() == images.size()) return result;
                if (reportedFilter.compareAndSet(false, true)) log(Log.INFO, TAG, "Image preview filter active");
                return kept;
            } catch (RuntimeException | LinkageError invalidRule) {
                return result;
            }
        });
        boolean installed = false;
        try {
            // Force the parser's calls to remain observable even if ART compiled it earlier.
            deoptimize(parser);
            hook(parser).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                Line saved = active.get();
                Line current = null;
                try {
                    BepttUrlRules rules = rules(prefs);
                    if (rules != null && !rules.urls.isEmpty()) {
                        Object record = chain.getArg(0);
                        Object raw = text.get(record);
                        Object urls = links.get(record);
                        current = new Line((String) raw, urls instanceof List<?> ? (List<?>) urls : Collections.emptyList(), rules);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Unavailable config or a mismatched record leaves this article unchanged.
                }
                if (current == null) active.remove(); else active.set(current);
                try {
                    return chain.proceed();
                } finally {
                    if (saved == null) active.remove(); else active.set(saved);
                }
            });
            installed = true;
        } finally {
            if (!installed) converterHook.unhook();
        }
        log(Log.INFO, TAG, "Image parser fingerprint matched; module ready");
    }

    private static boolean calledDirectlyByParser(Fingerprint.Site site, Method parserMethod, Method converterMethod) {
        if (Build.VERSION.SDK_INT >= 34) return ModernStack.isDirectCaller(parserMethod, converterMethod);
        return legacyCallerCheck(site);
    }

    private static final class ModernStack {
        private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

        static boolean isDirectCaller(Method parser, Method converter) {
            ClassLoader appLoader = parser.getDeclaringClass().getClassLoader();
            return WALKER.walk(frames -> frames
                    .filter(frame -> frame.getDeclaringClass().getClassLoader() == appLoader)
                    .filter(frame -> frame.getDeclaringClass() != converter.getDeclaringClass()
                            || !converter.getName().equals(frame.getMethodName()))
                    .findFirst()
                    .map(frame -> frame.getDeclaringClass() == parser.getDeclaringClass()
                            && parser.getName().equals(frame.getMethodName())
                            && parser.getReturnType() == frame.getMethodType().returnType()
                            && java.util.Arrays.equals(parser.getParameterTypes(), frame.getMethodType().parameterArray()))
                    .orElse(false));
        }
    }

    private static boolean legacyCallerCheck(Fingerprint.Site site) {
        String parser = Fingerprint.className(site.parser.getDefiningClass());
        String converter = Fingerprint.className(site.converter.getDefiningClass());
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (converter.equals(name) && site.converter.getName().equals(frame.getMethodName())) continue;
            if (site.hostClasses.contains(name)) {
                return parser.equals(name) && site.parser.getName().equals(frame.getMethodName());
            }
        }
        return false;
    }

    private synchronized BepttUrlRules rules(SharedPreferences prefs) {
        if (!prefs.getBoolean("enabled", true)) return null;
        String config = prefs.getString("urls", Config.DEFAULT_URLS);
        if (!config.equals(lastConfig)) {
            lastRules = new BepttUrlRules(config);
            lastConfig = config;
        }
        return lastRules;
    }
}
