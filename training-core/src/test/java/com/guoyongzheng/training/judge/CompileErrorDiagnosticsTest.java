package com.guoyongzheng.training.judge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompileErrorDiagnosticsTest {

    @Test
    void returnsNullForPassingOutput() {
        assertThat(CompileErrorDiagnostics.compileHint("[INFO] Tests run: 5, Failures: 0, Errors: 0")).isNull();
        assertThat(CompileErrorDiagnostics.compileHint(null)).isNull();
        assertThat(CompileErrorDiagnostics.compileHint("  ")).isNull();
    }

    @Test
    void listsMissingJavaUtilImportsFromMavenCompilationError() {
        String stderr = """
                [ERROR] /work/java/src/main/java/com/guoyongzheng/exam/basic/B005MergeIntervals.java:[28,9] cannot find symbol
                  symbol:   variable Arrays
                  location: class com.guoyongzheng.exam.basic.B005MergeIntervals
                [ERROR] /work/.../B005MergeIntervals.java:[29,34] cannot find symbol
                  symbol:   class ArrayList
                  location: class com.guoyongzheng.exam.basic.B005MergeIntervals
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile
                """;

        assertThat(CompileErrorDiagnostics.compileHint(stderr))
                .contains("java.util.Arrays")
                .contains("java.util.ArrayList")
                .contains("缺少 import");
    }

    @Test
    void detectsScannerSymbolClass() {
        String stderr = """
                [ERROR] /work/.../OD006strstr.java:[73,13] cannot find symbol
                  symbol:   class Scanner
                  location: class com.guoyongzheng.exam.od.OD006strstr.Main
                """;

        assertThat(CompileErrorDiagnostics.compileHint(stderr))
                .contains("java.util.Scanner");
    }

    @Test
    void handlesPrefixedErrorSymbolLines() {
        String stderr = """
                [ERROR] /work/.../X.java:[1,1] cannot find symbol
                [ERROR] symbol:   variable List
                """;

        assertThat(CompileErrorDiagnostics.compileHint(stderr)).contains("java.util.List");
    }

    @Test
    void fallsBackToGenericHintWhenNoKnownCandidate() {
        String stderr = """
                [ERROR] /work/.../X.java:[10,9] cannot find symbol
                  symbol:   class MyMissingType
                  location: class com.guoyongzheng.exam.X
                [ERROR] COMPILATION ERROR
                """;

        assertThat(CompileErrorDiagnostics.compileHint(stderr))
                .contains("代码未通过编译")
                .doesNotContain("java.util");
    }

    @Test
    void genericCompileFailureWithoutCannotFindSymbolStillGetsHint() {
        String stderr = """
                [ERROR] COMPILATION ERROR : /work/.../X.java: illegal start of expression
                """;

        assertThat(CompileErrorDiagnostics.compileHint(stderr)).isNotNull();
    }
}
