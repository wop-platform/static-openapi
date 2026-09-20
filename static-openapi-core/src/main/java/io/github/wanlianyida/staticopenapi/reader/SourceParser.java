package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带缓存的源文件解析器: path → CompilationUnit.
 *
 * <p>同一文件在生成流程中会被多次需要 (controller 扫描 / DTO schema 解析 /
 * 接口与父类展开), JavaParser 解析开销大, 统一从这里取, 每个文件只解析一次.
 * 实例内串行使用, 缓存用并发容器只为防御性安全.
 */
public class SourceParser {

    private static final Logger log = LoggerFactory.getLogger(SourceParser.class);

    private final JavaParser parser = new JavaParser();
    private final Map<Path, CompilationUnit> cache = new ConcurrentHashMap<>();
    private final Set<Path> failed = ConcurrentHashMap.newKeySet();

    /** 解析失败 (语法错误/IO 错误) 返回 null, 并记住失败避免重复尝试 */
    public CompilationUnit parse(Path file) {
        CompilationUnit hit = cache.get(file);
        if (hit != null) return hit;
        if (failed.contains(file)) return null;
        try {
            ParseResult<CompilationUnit> result = parser.parse(file);
            CompilationUnit unit = result.isSuccessful() ? result.getResult().orElse(null) : null;
            if (unit != null) {
                cache.put(file, unit);
            } else {
                log.warn("Failed to parse {}: {}", file,
                        result.getProblems().isEmpty() ? "unknown problem" : result.getProblems().get(0));
                failed.add(file);
            }
            return unit;
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
            failed.add(file);
            return null;
        }
    }
}
