package com.guoyongzheng.training.web.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provides progressive hint levels for coding question starter files.
 *
 * <p>Level 0: method/class signatures only (bodies replaced with ellipsis).
 * Level 1: signatures + inline TODO/hint comments preserved.
 * Level 2: full starter source.</p>
 */
@Service
public class StarterHintService {

    private static final Pattern METHOD_BODY = Pattern.compile(
            "(\\{)\\s*\\n([\\s\\S]*?)(\\n\\s*\\})", Pattern.MULTILINE);
    private static final Pattern TODO_COMMENT = Pattern.compile(
            "^\\s*(//\\s*(?:TODO|HINT|NOTE|FIXME).*)$", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT_HINT = Pattern.compile(
            "/\\*\\*[\\s\\S]*?\\*/", Pattern.MULTILINE);

    public record HintResponse(int level, String content, int totalLevels) {
    }

    public HintResponse reveal(String fullSource, int level) {
        int safeLevel = Math.max(0, Math.min(level, 2));
        String content = switch (safeLevel) {
            case 0 -> signaturesOnly(fullSource);
            case 1 -> signaturesWithHints(fullSource);
            default -> fullSource;
        };
        return new HintResponse(safeLevel, content, 3);
    }

    private String signaturesOnly(String source) {
        String stripped = METHOD_BODY.matcher(source).replaceAll("$1\n        // ...\n    $3");
        return removeHintComments(stripped);
    }

    private String signaturesWithHints(String source) {
        return METHOD_BODY.matcher(source).replaceAll("$1\n        // ...\n    $3");
    }

    private String removeHintComments(String source) {
        String result = TODO_COMMENT.matcher(source).replaceAll("");
        result = BLOCK_COMMENT_HINT.matcher(result).replaceAll("");
        return collapseBlankLines(result);
    }

    private String collapseBlankLines(String source) {
        return source.replaceAll("\\n{3,}", "\n\n");
    }

    public List<String> describeLevels() {
        return List.of(
                "Level 0: 仅方法签名（隐藏实现体和提示注释）",
                "Level 1: 方法签名 + TODO/HINT 注释",
                "Level 2: 完整 Starter 源码"
        );
    }
}
