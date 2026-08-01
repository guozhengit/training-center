package com.guoyongzheng.training.judge;

import com.guoyongzheng.training.catalog.RunnerKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SourceValidatorTest {

    private static final Path B001 =
            Path.of("java/src/main/java/com/guoyongzheng/exam/basic/B001TwoSum.java");
    private static final Path PY001 = Path.of("python/src/ai_exam/i001_jsonl_cleaning.py");

    @Test
    void acceptsMatchingJavaSource() {
        assertThatCode(() -> SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                package com.guoyongzheng.exam.basic;

                public final class B001TwoSum {
                    public static int[] twoSum(int[] nums, int target) {
                        return nums;
                    }
                }
                """)).doesNotThrowAnyException();
    }

    @Test
    void acceptsRecordTypeName() {
        assertThatCode(() -> SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                package com.guoyongzheng.exam.basic;

                public record B001TwoSum(int[] nums, int target) {
                }
                """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankSource() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.MAVEN, B001, "   "));
    }

    @Test
    void rejectsNullSource() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.MAVEN, B001, null));
    }

    @Test
    void rejectsWrongPackage() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                        package com.guoyongzheng.exam.other;

                        public class B001TwoSum {
                        }
                        """)).withMessageContaining("expected com.guoyongzheng.exam.basic");
    }

    @Test
    void rejectsMissingPackage() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                        public class B001TwoSum {
                        }
                        """)).withMessageContaining("package");
    }

    @Test
    void rejectsWrongTypeName() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                        package com.guoyongzheng.exam.basic;

                        public class Foo {
                        }
                        """)).withMessageContaining("expected B001TwoSum");
    }

    @Test
    void ignoresJavaModifiersAndAnnotations() {
        assertThatCode(() -> SourceValidator.requireValid(RunnerKind.MAVEN, B001, """
                package com.guoyongzheng.exam.basic;

                @Deprecated
                public abstract class B001TwoSum {
                }
                """)).doesNotThrowAnyException();
    }

    @Test
    void pythonSourceOnlyRequiresNonBlank() {
        assertThatCode(() -> SourceValidator.requireValid(RunnerKind.PYTEST, PY001, """
                def clean(lines):
                    return [line.strip() for line in lines if line.strip()]
                """)).doesNotThrowAnyException();
    }

    @Test
    void rejectsBlankPythonSource() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                SourceValidator.requireValid(RunnerKind.PYTEST, PY001, ""));
    }

    @Test
    void skipsDeepValidationForNonStandardMavenLayout() {
        assertThatCode(() -> SourceValidator.requireValid(
                RunnerKind.MAVEN, Path.of("some/other/Path.java"), "not a real java source"))
                .doesNotThrowAnyException();
    }
}
