package net.beptt.imagefilter;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.util.MethodUtil;
import java.io.File;
import java.io.IOException;
import java.util.*;

final class Fingerprint {
    private static final String STRING = "Ljava/lang/String;";
    private static final String ARRAY_LIST = "Ljava/util/ArrayList;";

    static final class Site {
        final Method imageExtractor, webExtractor;
        final FieldReference imageUrl, webUrl;
        final MethodReference append;
        final List<Method> parsers;

        Site(Method imageExtractor, Method webExtractor, FieldReference imageUrl, FieldReference webUrl,
             MethodReference append, List<Method> parsers) {
            this.imageExtractor = imageExtractor;
            this.webExtractor = webExtractor;
            this.imageUrl = imageUrl;
            this.webUrl = webUrl;
            this.append = append;
            this.parsers = parsers;
        }
    }

    static Site locate(File apk) throws IOException {
        var container = DexFileFactory.loadDexContainer(apk, null);
        List<Method> methods = new ArrayList<>();
        for (String entry : container.getDexEntryNames()) {
            for (ClassDef type : container.getEntry(entry).getDexFile().getClasses()) {
                for (Method method : type.getMethods()) {
                    if (method.getImplementation() != null) methods.add(method);
                }
            }
        }
        Method imageRow = unique(methods, m -> has(m, "ImageRow(url="), "image row");
        Method webRow = unique(methods, m -> has(m, "WebRow(url="), "web row");
        FieldReference imageUrl = urlField(methods, imageRow);
        FieldReference webUrl = urlField(methods, webRow);
        Method image = unique(methods, m -> MethodUtil.isStatic(m) && ARRAY_LIST.equals(m.getReturnType())
                && params(m, STRING, "Z") && has(m, "OIUD"), "image extractor");
        Method web = unique(methods, m -> MethodUtil.isStatic(m) && ARRAY_LIST.equals(m.getReturnType())
                && params(m, STRING) && has(m, image), "web extractor");

        List<Method> parsers = new ArrayList<>();
        MethodReference append = null;
        for (Method method : methods) {
            if (!"V".equals(method.getReturnType()) || !has(method, image) || !has(method, web)) continue;
            List<Instruction> code = new ArrayList<>();
            method.getImplementation().getInstructions().forEach(code::add);
            MethodReference imageAppend = rowAppend(code, imageRow.getDefiningClass());
            MethodReference webAppend = rowAppend(code, webRow.getDefiningClass());
            if (imageAppend == null || !imageAppend.equals(webAppend)) continue;
            if (append != null && !append.equals(imageAppend)) throw new IllegalStateException("Ambiguous preview output");
            append = imageAppend;
            parsers.add(method);
        }
        if (append == null) throw new IllegalStateException("No article preview parser");
        return new Site(image, web, imageUrl, webUrl, append, parsers);
    }

    private static FieldReference urlField(List<Method> methods, Method row) {
        String owner = row.getDefiningClass();
        Method constructor = unique(methods, m -> owner.equals(m.getDefiningClass()) && "<init>".equals(m.getName())
                && params(m, STRING, STRING), "preview constructor");
        int receiver = constructor.getImplementation().getRegisterCount() - 3;
        FieldReference result = null;
        for (Instruction insn : constructor.getImplementation().getInstructions()) {
            if (insn.getOpcode() != Opcode.IPUT_OBJECT || !(ref(insn) instanceof FieldReference)) continue;
            TwoRegisterInstruction registers = (TwoRegisterInstruction) insn;
            FieldReference field = (FieldReference) ref(insn);
            if (registers.getRegisterA() != receiver + 1 || registers.getRegisterB() != receiver
                    || !owner.equals(field.getDefiningClass()) || !STRING.equals(field.getType())) continue;
            if (result != null) throw new IllegalStateException("Ambiguous preview URL field");
            result = field;
        }
        if (result == null) throw new IllegalStateException("No preview URL field");
        return result;
    }

    private static MethodReference rowAppend(List<Instruction> code, String row) {
        MethodReference result = null;
        for (int i = 0; i + 1 < code.size(); i++) {
            Instruction constructor = code.get(i), append = code.get(i + 1);
            if (constructor.getOpcode() != Opcode.INVOKE_DIRECT || append.getOpcode() != Opcode.INVOKE_VIRTUAL
                    || !(ref(constructor) instanceof MethodReference) || !(ref(append) instanceof MethodReference)) continue;
            MethodReference init = (MethodReference) ref(constructor);
            MethodReference call = (MethodReference) ref(append);
            if (!row.equals(init.getDefiningClass()) || !"<init>".equals(init.getName())
                    || !params(init, STRING, STRING) || !"V".equals(call.getReturnType())
                    || call.getParameterTypes().size() != 1
                    || ((FiveRegisterInstruction) constructor).getRegisterC() != ((FiveRegisterInstruction) append).getRegisterD()) continue;
            if (result != null && !result.equals(call)) throw new IllegalStateException("Ambiguous row append");
            result = call;
        }
        return result;
    }

    private static Method unique(List<Method> methods, java.util.function.Predicate<Method> match, String label) {
        Method result = null;
        for (Method method : methods) {
            if (!match.test(method)) continue;
            if (result != null) throw new IllegalStateException("Ambiguous " + label);
            result = method;
        }
        if (result == null) throw new IllegalStateException("No " + label);
        return result;
    }

    private static boolean has(Method method, Object marker) {
        for (Instruction insn : method.getImplementation().getInstructions()) {
            Reference reference = ref(insn);
            if (marker.equals(reference) || (reference instanceof StringReference
                    && marker.equals(((StringReference) reference).getString()))) return true;
        }
        return false;
    }

    private static boolean params(MethodReference method, String... types) {
        List<? extends CharSequence> params = method.getParameterTypes();
        if (params.size() != types.length) return false;
        for (int i = 0; i < types.length; i++) if (!types[i].contentEquals(params.get(i))) return false;
        return true;
    }

    private static Reference ref(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    static String className(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }
}
