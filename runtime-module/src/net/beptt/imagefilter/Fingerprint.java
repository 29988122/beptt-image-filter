package net.beptt.imagefilter;

import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import java.io.File;
import java.io.IOException;
import java.util.*;

final class Fingerprint {
    static final String LIST = "Ljava/util/List;";
    static final String STRING = "Ljava/lang/String;";

    static final class Site {
        final Method parser;
        final MethodReference converter;
        final FieldReference text, links;
        Set<String> hostClasses;

        Site(Method parser, MethodReference converter, FieldReference text, FieldReference links) {
            this.parser = parser;
            this.converter = converter;
            this.text = text;
            this.links = links;
        }
    }

    static Site locate(File apk) throws IOException {
        List<Site> matches = new ArrayList<>();
        Set<String> hostClasses = new HashSet<>();
        var container = DexFileFactory.loadDexContainer(apk, null);
        for (String entry : container.getDexEntryNames()) {
            var dex = container.getEntry(entry).getDexFile();
            for (ClassDef type : dex.getClasses()) {
                hostClasses.add(className(type.getType()));
                for (Method method : type.getMethods()) {
                    Site match = match(method);
                    if (match != null) matches.add(match);
                }
            }
        }
        if (matches.size() != 1) throw new IllegalStateException("Expected one image parser, found " + matches.size());
        Site result = matches.get(0);
        result.hostClasses = hostClasses;
        return result;
    }

    private static Site match(Method method) {
        if (!"V".equals(method.getReturnType()) || method.getParameterTypes().size() != 2
                || method.getImplementation() == null) return null;
        String line = method.getParameterTypes().get(0).toString();
        if (!line.startsWith("L") || !method.getParameterTypes().get(1).toString().startsWith("L")) return null;
        List<Instruction> code = new ArrayList<>();
        boolean marker = false;
        for (Instruction instruction : method.getImplementation().getInstructions()) {
            code.add(instruction);
            if (ref(instruction) instanceof StringReference
                    && "※ 文章網址:".equals(((StringReference) ref(instruction)).getString())) marker = true;
        }
        if (!marker || code.size() < 8) return null;
        // Record fields are the first two object reads immediately after the two parameter moves.
        if (code.get(0).getOpcode() != Opcode.MOVE_OBJECT_FROM16
                || code.get(1).getOpcode() != Opcode.MOVE_OBJECT_FROM16) return null;
        int lineRegister = ((TwoRegisterInstruction) code.get(0)).getRegisterA();
        if (((TwoRegisterInstruction) code.get(0)).getRegisterB() != method.getImplementation().getRegisterCount() - 2) return null;
        FieldReference text = field(code.get(2), line, STRING, lineRegister);
        FieldReference links = field(code.get(3), line, LIST, lineRegister);
        if (text == null || links == null) return null;

        Site result = null;
        for (int i = 0; i + 7 < code.size(); i++) {
            Instruction first = code.get(i), second = code.get(i + 2);
            if (first.getOpcode() != Opcode.INVOKE_STATIC || second.getOpcode() != Opcode.INVOKE_STATIC
                    || !(ref(first) instanceof MethodReference) || !ref(first).equals(ref(second))) continue;
            MethodReference converter = (MethodReference) ref(first);
            if (!LIST.equals(converter.getReturnType()) || !params(converter, "Ljava/lang/Iterable;")) continue;
            if (code.get(i + 1).getOpcode() != Opcode.MOVE_RESULT_OBJECT
                    || code.get(i + 3).getOpcode() != Opcode.MOVE_RESULT_OBJECT
                    || code.get(i + 4).getOpcode() != Opcode.MOVE
                    || code.get(i + 5).getOpcode() != Opcode.INVOKE_INTERFACE
                    || code.get(i + 6).getOpcode() != Opcode.MOVE_RESULT
                    || code.get(i + 7).getOpcode() != Opcode.IF_GE
                    || !(ref(code.get(i + 5)) instanceof MethodReference)) continue;
            MethodReference size = (MethodReference) ref(code.get(i + 5));
            int other = ((OneRegisterInstruction) code.get(i + 1)).getRegisterA();
            int images = ((OneRegisterInstruction) code.get(i + 3)).getRegisterA();
            if (!LIST.equals(size.getDefiningClass()) || !"size".equals(size.getName()) || !params(size)
                    || !"I".equals(size.getReturnType()) || images == other
                    || ((FiveRegisterInstruction) code.get(i + 5)).getRegisterC() != other) continue;
            int calls = 0;
            boolean imageLoop = false;
            for (int j = 0; j < code.size(); j++) {
                Reference instructionRef = ref(code.get(j));
                if (converter.equals(instructionRef)) calls++;
                if (j > i + 7 && code.get(j).getOpcode() == Opcode.INVOKE_INTERFACE
                        && instructionRef instanceof MethodReference) {
                    MethodReference call = (MethodReference) instructionRef;
                    if (LIST.equals(call.getDefiningClass()) && "get".equals(call.getName())
                            && params(call, "I") && ((FiveRegisterInstruction) code.get(j)).getRegisterC() == images) {
                        imageLoop = true;
                    }
                }
            }
            if (calls != 2 || !imageLoop) continue;
            if (result != null) throw new IllegalStateException("Ambiguous converter layout");
            result = new Site(method, converter, text, links);
        }
        return result;
    }

    private static boolean params(MethodReference method, String... types) {
        List<? extends CharSequence> params = method.getParameterTypes();
        if (params.size() != types.length) return false;
        for (int i = 0; i < types.length; i++) if (!types[i].contentEquals(params.get(i))) return false;
        return true;
    }

    private static FieldReference field(Instruction instruction, String owner, String type, int register) {
        if (instruction.getOpcode() != Opcode.IGET_OBJECT || !(ref(instruction) instanceof FieldReference)) return null;
        FieldReference field = (FieldReference) ref(instruction);
        return owner.equals(field.getDefiningClass()) && type.equals(field.getType())
                && ((TwoRegisterInstruction) instruction).getRegisterB() == register ? field : null;
    }

    private static Reference ref(Instruction instruction) {
        return instruction instanceof ReferenceInstruction ? ((ReferenceInstruction) instruction).getReference() : null;
    }

    static String className(String descriptor) {
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }
}
