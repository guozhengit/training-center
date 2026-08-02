package com.guoyongzheng.training.judge;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw javac/Maven compilation diagnostics into a short, actionable hint
 * for the web judge UI. The most common self-inflicted compile failure in this
 * training catalog is referencing {@code java.util} types without importing
 * them, so that case is detected explicitly and reported with the missing
 * imports.
 */
public final class CompileErrorDiagnostics {

    private static final Pattern COMPILATION_ERROR = Pattern.compile("COMPILATION ERROR");
    private static final Pattern CANNOT_FIND_SYMBOL = Pattern.compile(
            "cannot find symbol[\\r\\n]\\s*(?:\\[ERROR]\\s*)?symbol:\\s+"
                    + "(?:variable|class|method|constructor|package)\\s+"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Map<String, String> CANDIDATES = new LinkedHashMap<>();

    static {
        CANDIDATES.put("Scanner", "java.util.Scanner");
        CANDIDATES.put("List", "java.util.List");
        CANDIDATES.put("ArrayList", "java.util.ArrayList");
        CANDIDATES.put("LinkedList", "java.util.LinkedList");
        CANDIDATES.put("Arrays", "java.util.Arrays");
        CANDIDATES.put("Comparator", "java.util.Comparator");
        CANDIDATES.put("Map", "java.util.Map");
        CANDIDATES.put("HashMap", "java.util.HashMap");
        CANDIDATES.put("LinkedHashMap", "java.util.LinkedHashMap");
        CANDIDATES.put("TreeMap", "java.util.TreeMap");
        CANDIDATES.put("Set", "java.util.Set");
        CANDIDATES.put("HashSet", "java.util.HashSet");
        CANDIDATES.put("TreeSet", "java.util.TreeSet");
        CANDIDATES.put("Optional", "java.util.Optional");
        CANDIDATES.put("Collections", "java.util.Collections");
        CANDIDATES.put("Objects", "java.util.Objects");
        CANDIDATES.put("Queue", "java.util.Queue");
        CANDIDATES.put("Deque", "java.util.Deque");
        CANDIDATES.put("ArrayDeque", "java.util.ArrayDeque");
        CANDIDATES.put("PriorityQueue", "java.util.PriorityQueue");
        CANDIDATES.put("Stack", "java.util.Stack");
        CANDIDATES.put("Random", "java.util.Random");
        CANDIDATES.put("Date", "java.util.Date");
        CANDIDATES.put("Iterator", "java.util.Iterator");
    }

    private CompileErrorDiagnostics() {
    }

    /**
     * Returns a concise Chinese hint when {@code stderr} shows a compilation
     * failure caused by missing {@code java.util} imports, or a generic
     * compilation-failure hint otherwise, or {@code null} when the output does
     * not indicate a compilation failure.
     */
    public static String compileHint(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return null;
        }
        if (!COMPILATION_ERROR.matcher(stderr).find() && !stderr.contains("cannot find symbol")) {
            return null;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = CANNOT_FIND_SYMBOL.matcher(stderr);
        while (matcher.find()) {
            String candidate = CANDIDATES.get(matcher.group(1));
            if (candidate != null) {
                missing.add(candidate);
            }
        }
        if (missing.isEmpty()) {
            return "代码未通过编译，请根据下方错误详情修正后重新判题。";
        }
        String imports = String.join("、", missing);
        return "代码未通过编译：引用了 java.util 类型但缺少 import。"
                + "请补充 " + imports
                + "（在 package 声明后添加对应 import 语句）后重新判题。";
    }
}
