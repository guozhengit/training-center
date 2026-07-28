package com.guoyongzheng.training.starter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerLeakageTest {

    private static final int IDENTICAL_BODY_MIN_TOKENS = 12;
    private static final int SUSPICIOUS_RUN_TOKENS = 30;
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*|\\d+(?:\\.\\d+)?|==|!=|<=|>=|&&|\\|\\||"
                    + "->|::|\\+\\+|--|[{}()\\[\\];,.?:+\\-*/%<>=!&|^~]");
    private static final Pattern PYTHON_FUNCTION = Pattern.compile(
            "^(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");

    static Stream<StarterTestSupport.MappingRow> selectedMappings() {
        return StarterTestSupport.selectedMappingStream();
    }

    @Test
    void scannerRejectsCopiedBodiesDespiteCommentAndWhitespaceChanges() {
        String reference = """
                class Example {
                    int solve(int[] values) {
                        int total = 0;
                        for (int value : values) {
                            total += value * 2;
                        }
                        return total + values.length;
                    }
                }
                """;
        String copied = """
                class Example {
                    int solve(int[] values) {
                        // superficial formatting must not hide a copied body
                        int total=0;
                        for(int value:values){ total += value*2; }
                        return total+values.length;
                    }
                }
                """;
        List<List<String>> referenceBodies = implementationBodies(reference, true);
        List<List<String>> copiedBodies = implementationBodies(copied, true);
        Set<String> normalized = new HashSet<>();
        referenceBodies.stream()
                .map(body -> String.join("\u001f", body))
                .forEach(normalized::add);

        assertThat(copiedBodies.stream()
                .map(body -> String.join("\u001f", body))
                .filter(normalized::contains))
                .isNotEmpty();
    }

    @Test
    void scannerAllowsConstructorAndSignatureBoilerplate() {
        String javaShape = """
                record SharedModel(String first, String second, int count) {
                    SharedModel {
                        first = first == null ? "" : first;
                        second = second == null ? "" : second;
                        count = Math.max(0, count);
                    }
                }
                """;
        String pythonShape = """
                class SharedModel:
                    def __init__(self, first: str, second: str, count: int):
                        self.first = first
                        self.second = second
                        self.count = max(0, count)
                """;

        assertThat(implementationBodies(javaShape, true)).isEmpty();
        assertThat(implementationBodies(pythonShape, false)).isEmpty();
    }

    @Test
    void pythonScannerSkipsCompleteMultilineSignatureAndKeepsPlaceholderBody() {
        String starter = """
                from collections.abc import Iterable

                def solve(
                    values: list[int],
                    *,
                    limit: int = max(1, 10),
                ) -> list[int]:
                    raise NotImplementedError("%s")
                """.formatted("TO" + "DO");

        List<List<String>> bodies = implementationBodies(starter, false);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0))
                .containsExactly("raise", "notimplementederror", "(", ")");
    }

    @Test
    void pythonScannerPreservesTabIndentedCopiedImplementation() {
        String source = "def solve(values):\n"
                + "\tresult = []\n"
                + "\tfor value in values:\n"
                + "\t\tresult.append(value * 2)\n"
                + "\treturn result\n";

        List<List<String>> bodies = implementationBodies(source, false);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("result", "append", "return");
        assertThat(bodies.get(0)).hasSizeGreaterThanOrEqualTo(IDENTICAL_BODY_MIN_TOKENS);
        assertThat(implementationBodies(source, false)).isEqualTo(bodies);
    }

    @Test
    void pythonScannerCapturesOneLineSuiteAfterSignatureColon() {
        List<List<String>> bodies = implementationBodies(
                "def solve(values: list[int]) -> int: "
                        + "return sum(values) + len(values) + max(values) - min(values)\n",
                false);

        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0))
                .containsExactly(
                        "return", "sum", "(", "values", ")", "+",
                        "len", "(", "values", ")", "+", "max", "(",
                        "values", ")", "-", "min", "(", "values", ")");
        assertThat(bodies.get(0)).hasSizeGreaterThanOrEqualTo(IDENTICAL_BODY_MIN_TOKENS);
    }

    @Test
    void scannerDetectsAnActualThirtyTokenCopiedRunWithoutWholeBodyEquality() {
        List<String> run = java.util.stream.IntStream.range(0, SUSPICIOUS_RUN_TOKENS)
                .mapToObj(index -> "token" + index)
                .toList();
        List<String> starter = new ArrayList<>(run);
        starter.add("starter-tail");
        List<String> reference = new ArrayList<>();
        reference.add("reference-head");
        reference.addAll(run);

        assertThat(suspiciousRuns(List.of(starter), List.of(reference))).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("selectedMappings")
    void starterDoesNotContainReferenceImplementation(StarterTestSupport.MappingRow mapping)
            throws IOException {
        Path starter = StarterTestSupport.resolvePortable(
                StarterTestSupport.workspaceRoot(), mapping.starterPath());
        assertThat(Files.isRegularFile(starter, LinkOption.NOFOLLOW_LINKS))
                .withFailMessage("Missing Starter for leakage scan %s: %s",
                        mapping.questionId(), mapping.starterPath())
                .isTrue();
        StarterTestSupport.assertSafeExisting(
                StarterTestSupport.starterRoot(),
                mapping.sandboxSourcePath(),
                "Starter " + mapping.questionId());
        Path reference = StarterTestSupport.resolvePortable(
                StarterTestSupport.examRoot(), mapping.sandboxSourcePath());

        List<List<String>> starterBodies = implementationBodies(
                Files.readString(starter, StandardCharsets.UTF_8),
                mapping.sandboxSourcePath().endsWith(".java"));
        List<List<String>> referenceBodies = implementationBodies(
                Files.readString(reference, StandardCharsets.UTF_8),
                mapping.sandboxSourcePath().endsWith(".java"));

        Set<String> referenceNormalized = new HashSet<>();
        for (List<String> body : referenceBodies) {
            if (body.size() >= IDENTICAL_BODY_MIN_TOKENS) {
                referenceNormalized.add(String.join("\u001f", body));
            }
        }
        List<String> identical = starterBodies.stream()
                .filter(body -> body.size() >= IDENTICAL_BODY_MIN_TOKENS)
                .map(body -> String.join("\u001f", body))
                .filter(referenceNormalized::contains)
                .toList();
        assertThat(identical)
                .withFailMessage("%s contains %d identical normalized implementation bodies",
                        mapping.questionId(), identical.size())
                .isEmpty();

        List<String> suspicious = suspiciousRuns(starterBodies, referenceBodies);
        assertThat(suspicious)
                .withFailMessage("%s contains copied implementation token runs: %s",
                        mapping.questionId(), suspicious)
                .isEmpty();
    }

    private static List<List<String>> implementationBodies(String source, boolean javaSource) {
        String stripped = stripCommentsAndLiterals(source);
        return javaSource ? javaBodies(stripped) : pythonBodies(stripped);
    }

    private static List<List<String>> javaBodies(String source) {
        List<String> tokens = tokens(source);
        Set<String> typeNames = new HashSet<>();
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (Set.of("class", "interface", "enum", "record").contains(tokens.get(index))) {
                typeNames.add(tokens.get(index + 1));
            }
        }
        List<List<String>> bodies = new ArrayList<>();
        ArrayDeque<Integer> starts = new ArrayDeque<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if ("{".equals(token)) {
                starts.push(index + 1);
            } else if ("}".equals(token) && !starts.isEmpty()) {
                int start = starts.pop();
                if (looksLikeExecutableJavaBlock(tokens, start - 1, typeNames)) {
                    bodies.add(List.copyOf(tokens.subList(start, index)));
                }
            }
        }
        return bodies;
    }

    private static boolean looksLikeExecutableJavaBlock(
            List<String> tokens, int brace, Set<String> typeNames) {
        int previous = brace - 1;
        if (previous < 0) {
            return false;
        }
        String token = tokens.get(previous);
        if ("else".equals(token) || "try".equals(token) || "finally".equals(token)
                || "do".equals(token)) {
            return true;
        }
        if (previous >= 1 && ("=".equals(token) || "->".equals(token))) {
            return true;
        }
        for (int cursor = previous; cursor >= 0 && cursor >= brace - 24; cursor--) {
            String candidate = tokens.get(cursor);
            if (")".equals(candidate)) {
                for (int between = cursor + 1; between < brace; between++) {
                    if (Set.of("class", "interface", "enum", "record")
                            .contains(tokens.get(between))) {
                        return false;
                    }
                }
                int open = matchingOpenParenthesis(tokens, cursor);
                String callable = open > 0 ? tokens.get(open - 1) : "";
                return !typeNames.contains(callable);
            }
            if (";".equals(candidate) || "{".equals(candidate) || "}".equals(candidate)) {
                return false;
            }
        }
        return false;
    }

    private static int matchingOpenParenthesis(List<String> tokens, int close) {
        int depth = 0;
        for (int cursor = close; cursor >= 0; cursor--) {
            if (")".equals(tokens.get(cursor))) {
                depth++;
            } else if ("(".equals(tokens.get(cursor)) && --depth == 0) {
                return cursor;
            }
        }
        return -1;
    }

    private static List<List<String>> pythonBodies(String source) {
        List<List<String>> bodies = new ArrayList<>();
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String trimmed = lines[index].stripLeading();
            if (!(trimmed.startsWith("def ") || trimmed.startsWith("async def "))) {
                continue;
            }
            Matcher declaration = PYTHON_FUNCTION.matcher(trimmed);
            if (!declaration.find()) {
                throw new IllegalArgumentException(
                        "Malformed Python function declaration at line " + (index + 1));
            }
            String functionName = declaration.group(1);
            SignatureEnd signatureEnd = findPythonSignatureEnd(lines, index);
            if ("__init__".equals(functionName) || "__new__".equals(functionName)) {
                index = signatureEnd.line();
                continue;
            }
            Indentation declarationIndent = indentation(lines[index]);
            List<String> bodyLines = new ArrayList<>();
            String oneLineSuite = lines[signatureEnd.line()]
                    .substring(signatureEnd.characterAfterColon());
            if (!oneLineSuite.isBlank()) {
                bodyLines.add(oneLineSuite);
            }
            for (int cursor = signatureEnd.line() + 1; cursor < lines.length; cursor++) {
                String line = lines[cursor];
                if (line.isBlank()) {
                    continue;
                }
                Indentation indent = indentation(line);
                if (indent.columns() <= declarationIndent.columns()) {
                    break;
                }
                bodyLines.add(line.substring(indent.characters()));
            }
            bodies.add(tokens(String.join("\n", bodyLines)));
            index = signatureEnd.line();
        }
        return bodies;
    }

    private static SignatureEnd findPythonSignatureEnd(String[] lines, int startLine) {
        int depth = 0;
        for (int line = startLine; line < lines.length; line++) {
            String value = lines[line];
            for (int character = 0; character < value.length(); character++) {
                char token = value.charAt(character);
                if (token == '(' || token == '[' || token == '{') {
                    depth++;
                } else if (token == ')' || token == ']' || token == '}') {
                    depth--;
                    if (depth < 0) {
                        throw new IllegalArgumentException(
                                "Unbalanced Python signature at line " + (line + 1));
                    }
                } else if (token == ':' && depth == 0) {
                    return new SignatureEnd(line, character + 1);
                }
            }
        }
        throw new IllegalArgumentException(
                "Python function signature has no closing colon at line " + (startLine + 1));
    }

    private static Indentation indentation(String line) {
        int characters = 0;
        int columns = 0;
        while (characters < line.length()) {
            char value = line.charAt(characters);
            if (value == ' ') {
                characters++;
                columns++;
            } else if (value == '\t') {
                characters++;
                columns += 8 - (columns % 8);
            } else {
                break;
            }
        }
        return new Indentation(characters, columns);
    }

    private static String stripCommentsAndLiterals(String source) {
        StringBuilder result = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        boolean tripleQuote = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            char afterNext = index + 2 < source.length() ? source.charAt(index + 2) : 0;
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    result.append('\n');
                } else {
                    result.append(' ');
                }
            } else if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    result.append("  ");
                    index++;
                } else {
                    result.append(current == '\n' ? '\n' : ' ');
                }
            } else if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (tripleQuote
                        && current == quote
                        && next == quote
                        && afterNext == quote) {
                    quote = 0;
                    tripleQuote = false;
                    result.append("   ");
                    index += 2;
                    continue;
                } else if (!tripleQuote && current == quote) {
                    quote = 0;
                }
                result.append(current == '\n' ? '\n' : ' ');
            } else if (current == '/' && next == '/') {
                lineComment = true;
                result.append("  ");
                index++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                result.append("  ");
                index++;
            } else if (current == '#') {
                lineComment = true;
                result.append(' ');
            } else if (current == '"' || current == '\'') {
                quote = current;
                tripleQuote = next == current && afterNext == current;
                if (tripleQuote) {
                    result.append("   ");
                    index += 2;
                } else {
                    result.append(' ');
                }
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    private static List<String> tokens(String source) {
        List<String> result = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(source);
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(result);
    }

    private static List<String> suspiciousRuns(
            List<List<String>> starterBodies, List<List<String>> referenceBodies) {
        Set<String> referenceRuns = new HashSet<>();
        for (List<String> body : referenceBodies) {
            addRuns(referenceRuns, body);
        }
        List<String> matches = new ArrayList<>();
        for (List<String> body : starterBodies) {
            if (body.size() < SUSPICIOUS_RUN_TOKENS) {
                continue;
            }
            for (int index = 0; index <= body.size() - SUSPICIOUS_RUN_TOKENS; index++) {
                String run = String.join("\u001f",
                        body.subList(index, index + SUSPICIOUS_RUN_TOKENS));
                if (referenceRuns.contains(run)) {
                    matches.add(String.join(" ",
                            body.subList(index, index + SUSPICIOUS_RUN_TOKENS)));
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    private static void addRuns(Set<String> target, List<String> body) {
        for (int index = 0; index <= body.size() - SUSPICIOUS_RUN_TOKENS; index++) {
            target.add(String.join("\u001f",
                    body.subList(index, index + SUSPICIOUS_RUN_TOKENS)));
        }
    }

    private record SignatureEnd(int line, int characterAfterColon) {
    }

    private record Indentation(int characters, int columns) {
    }
}
