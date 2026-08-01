package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.catalog.RunnerKind;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 判题前对提交源码的轻量结构预校验, 提前暴露明显错误, 避免等到编译/测试阶段才失败. */
public final class SourceValidator {

    private static final Pattern PACKAGE = Pattern.compile(
            "(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern TOP_LEVEL_TYPE = Pattern.compile(
            "(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+)?"
                    + "(?:class|record|interface|enum)\\s+([\\w$]+)");
    private static final Path MAVEN_SOURCE_ROOT = Path.of("java", "src", "main", "java");

    private SourceValidator() {
    }

    /**
     * 校验提交的源码与题目沙箱目标文件一致.
     * 目前只对 MAVEN (Java) 做包名/顶层类型名匹配; 其它语言仅要求非空.
     *
     * @param sandboxSourcePath 沙箱内目标文件相对路径, 如 java/src/main/java/com/.../B001TwoSum.java
     */
    public static void requireValid(RunnerKind runnerKind, Path sandboxSourcePath, String sourceCode) {
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode must not be blank");
        }
        if (runnerKind != RunnerKind.MAVEN || !sandboxSourcePath.startsWith(MAVEN_SOURCE_ROOT)) {
            return;
        }
        Path relative = MAVEN_SOURCE_ROOT.relativize(sandboxSourcePath);
        String expectedPackage = javaPackage(relative);
        String expectedType = simpleName(relative);

        String actualPackage = find(PACKAGE, sourceCode);
        if (!expectedPackage.equals(actualPackage)) {
            throw new IllegalArgumentException("Source package does not match the question: expected "
                    + expectedPackage + " but got " + (actualPackage == null ? "<missing package>" : actualPackage));
        }
        String actualType = find(TOP_LEVEL_TYPE, sourceCode);
        if (!expectedType.equals(actualType)) {
            throw new IllegalArgumentException("Source top-level type does not match the question: expected "
                    + expectedType + " but got " + (actualType == null ? "<no public type>" : actualType));
        }
    }

    private static String javaPackage(Path relativeSource) {
        Path parent = relativeSource.getParent();
        if (parent == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Path segment : parent) {
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(segment);
        }
        return result.toString();
    }

    private static String simpleName(Path relativeSource) {
        String fileName = relativeSource.getFileName().toString();
        return fileName.substring(0, fileName.length() - ".java".length());
    }

    private static String find(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }
}
