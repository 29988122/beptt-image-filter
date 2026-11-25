package net.beptt.imagefilter;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;
import org.jf.dexlib2.iface.reference.FieldReference;
import org.jf.dexlib2.iface.reference.MethodReference;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ImageFilterModule extends XposedModule {
    private static final String TARGET = "y_studio.ka5983.ptt2";
    private static final String TAG = "BePTT Image Filter";
    private final ThreadLocal<ArticleParse> active = new ThreadLocal<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean reportedImage = new AtomicBoolean();
    private final AtomicBoolean reportedCard = new AtomicBoolean();
    private String lastConfig;
    private BepttUrlRules lastRules;

    private static final class ArticleParse {
        final BepttUrlRules rules;
        final PreviewSources images = new PreviewSources();
        final PreviewSources cards = new PreviewSources();
        boolean extracting;
        boolean failed;

        ArticleParse(BepttUrlRules rules) {
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
            log(Log.WARN, TAG, "No preview filter installed. APK layout or Xposed environment unavailable.", failure);
        }
    }

    private void install(PackageReadyParam param) throws Exception {
        Fingerprint.Site site = Fingerprint.locate(new File(param.getApplicationInfo().sourceDir));
        ClassLoader loader = param.getClassLoader();
        Method imageExtractor = resolve(site.imageExtractor, loader);
        Method webExtractor = resolve(site.webExtractor, loader);
        Method append = resolve(site.append, loader);
        Field imageUrl = resolve(site.imageUrl, loader);
        Field webUrl = resolve(site.webUrl, loader);
        SharedPreferences prefs = getRemotePreferences(Config.GROUP);
        List<HookHandle> hooks = new ArrayList<>();
        boolean installed = false;
        try {
            hooks.add(observe(imageExtractor, true));
            hooks.add(observe(webExtractor, false));
            hooks.add(hook(append).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                ArticleParse parse = active.get();
                if (parse != null && !parse.failed && hidesRow(parse, chain.getArg(0), imageUrl, webUrl)) return null;
                return chain.proceed();
            }));
            for (MethodReference reference : site.parsers) {
                Method parser = resolve(reference, loader);
                deoptimize(parser);
                hooks.add(hook(parser).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
                    ArticleParse parent = active.get();
                    BepttUrlRules rules = readRules(prefs);
                    if (rules == null || rules.urls.isEmpty()) active.remove();
                    else active.set(new ArticleParse(rules));
                    try {
                        return chain.proceed();
                    } finally {
                        if (parent == null) active.remove(); else active.set(parent);
                    }
                }));
            }
            installed = true;
        } finally {
            if (!installed) for (HookHandle handle : hooks) handle.unhook();
        }
        log(Log.INFO, TAG, "Article preview filter ready (" + site.parsers.size() + " parser entry points)");
    }

    private HookHandle observe(Method extractor, boolean image) {
        Invoker<?, Method> original = getInvoker(extractor).setType(Invoker.Type.ORIGIN);
        return hook(extractor).setExceptionMode(ExceptionMode.PASSTHROUGH).intercept(chain -> {
            ArticleParse parse = active.get();
            if (parse == null || parse.extracting || parse.failed) return chain.proceed();
            parse.extracting = true;
            try {
                Object result = chain.proceed();
                if (result instanceof List<?>) {
                    try {
                        Object imageMode = image ? chain.getArg(1) : null;
                        PreviewSources sources = image ? parse.images : parse.cards;
                        sources.record(parse.rules, (String) chain.getArg(0), (List<?>) result, source -> (List<?>)
                                (image ? original.invoke(null, source, imageMode) : original.invoke(null, source)));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        parse.failed = true;
                    }
                }
                return result;
            } finally {
                parse.extracting = false;
            }
        });
    }

    private boolean hidesRow(ArticleParse parse, Object row, Field imageUrl, Field webUrl) {
        try {
            if (imageUrl.getDeclaringClass().isInstance(row)) {
                if (!parse.images.hides((String) imageUrl.get(row), false)) return false;
                if (reportedImage.compareAndSet(false, true)) log(Log.INFO, TAG, "Matched image preview hidden");
                return true;
            }
            if (webUrl.getDeclaringClass().isInstance(row)) {
                String url = (String) webUrl.get(row);
                // OSC links can be added directly as a card without passing through the web extractor.
                if (!parse.cards.hides(url, parse.rules.urls.contains(url))) return false;
                if (reportedCard.compareAndSet(false, true)) log(Log.INFO, TAG, "Matched link preview hidden");
                return true;
            }
            parse.images.clear();
            parse.cards.clear();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            parse.failed = true;
        }
        return false;
    }

    private static Method resolve(MethodReference method, ClassLoader loader) throws ReflectiveOperationException {
        List<? extends CharSequence> descriptors = method.getParameterTypes();
        Class<?>[] types = new Class<?>[descriptors.size()];
        for (int i = 0; i < types.length; i++) {
            String descriptor = descriptors.get(i).toString();
            if ("I".equals(descriptor)) types[i] = int.class;
            else if ("Z".equals(descriptor)) types[i] = boolean.class;
            else types[i] = Class.forName(Fingerprint.className(descriptor), false, loader);
        }
        return Class.forName(Fingerprint.className(method.getDefiningClass()), false, loader)
                .getDeclaredMethod(method.getName(), types);
    }

    private static Field resolve(FieldReference field, ClassLoader loader) throws ReflectiveOperationException {
        Field reflected = Class.forName(Fingerprint.className(field.getDefiningClass()), false, loader)
                .getDeclaredField(field.getName());
        reflected.setAccessible(true);
        return reflected;
    }

    private synchronized BepttUrlRules readRules(SharedPreferences prefs) {
        try {
            if (!prefs.getBoolean("enabled", true)) return null;
            String config = prefs.getString("urls", Config.DEFAULT_URLS);
            if (!config.equals(lastConfig)) {
                lastRules = new BepttUrlRules(config);
                lastConfig = config;
            }
            return lastRules;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
