package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读 Spring Web 注解 (@RequestMapping / @GetMapping / @PostMapping 等).
 *
 * <p>同一注解声明的多个 path 是"或"关系 (Spring 语义), 全部展开;
 * @RequestMapping 未声明 method 时视为接受所有标准 HTTP 方法.
 */
public class SpringWebAnnotationReader {

    /** 表示接受所有标准 HTTP 方法 (生成时展开为 get/post/put/...) */
    public static final String ANY_METHOD = "ANY";

    private static final String NS_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String NS_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
    private static final String NS_POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
    private static final String NS_PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";
    private static final String NS_DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping";
    private static final String NS_PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping";
    private static final String NS_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String NS_CONTROLLER = "org.springframework.stereotype.Controller";

    private static final Set<String> MAPPING_NAMESPACES = new LinkedHashSet<>(Arrays.asList(
            NS_REQUEST_MAPPING, NS_GET_MAPPING, NS_POST_MAPPING,
            NS_PUT_MAPPING, NS_DELETE_MAPPING, NS_PATCH_MAPPING));

    public boolean isController(ClassOrInterfaceDeclaration cls) {
        return cls.getAnnotations().stream().anyMatch(a ->
                AnnotationUtils.matches(a, NS_REST_CONTROLLER) || AnnotationUtils.matches(a, NS_CONTROLLER));
    }

    /** class 级别 @RequestMapping 的 path 列表 (每个值都是可选前缀, Spring 语义为"或"). 无注解返回 [""] */
    public List<String> readBasePaths(ClassOrInterfaceDeclaration cls) {
        Optional<AnnotationExpr> ann = cls.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_REQUEST_MAPPING))
                .findFirst();
        List<String> paths = ann.map(a -> stringOrArray(a, "value", "path")).orElse(new ArrayList<>());
        if (paths.isEmpty()) return Collections.singletonList("");
        return paths;
    }

    /** 读 method 上的 mapping 注解,得到 HTTP methods × paths ("或"关系列表). 无 mapping 返回 null. */
    public MappingInfo readMapping(MethodDeclaration method) {
        for (String ns : MAPPING_NAMESPACES) {
            Optional<AnnotationExpr> ann = method.getAnnotations().stream()
                    .filter(a -> AnnotationUtils.matches(a, ns))
                    .findFirst();
            if (ann.isEmpty()) continue;

            List<String> httpMethods;
            if (NS_REQUEST_MAPPING.equals(ns)) {
                List<String> declared = stringOrArray(ann.get(), "method");
                httpMethods = declared.isEmpty()
                        ? Collections.singletonList(ANY_METHOD)
                        : declared.stream().map(SpringWebAnnotationReader::stripRequestMethodPrefix).collect(Collectors.toList());
            } else {
                httpMethods = Collections.singletonList(
                        AnnotationUtils.shortName(ns).replace("Mapping", "").toUpperCase(Locale.ROOT));
            }

            List<String> paths = stringOrArray(ann.get(), "value", "path");
            if (paths.isEmpty()) paths = Collections.singletonList("");
            return new MappingInfo(httpMethods, paths);
        }
        return null;
    }

    /** "RequestMethod.POST" → "POST"; "POST" → "POST" */
    private static String stripRequestMethodPrefix(String s) {
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }

    private List<String> stringOrArray(AnnotationExpr ann, String... keys) {
        List<String> keyList = Arrays.asList(keys);
        // 1. NormalAnnotationExpr: 按 key 精确取 value/path/method
        if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
                if (keyList.contains(p.getNameAsString())) {
                    return extractStrings(p.getValue());
                }
            }
            return new ArrayList<>();
        }
        // 2. SingleMemberAnnotationExpr: @PostMapping("/path") 等价于 value="/path".
        //    仅当请求的 key 包含 value 时才取, 否则会把路径误当成 method.
        if (ann instanceof SingleMemberAnnotationExpr && keyList.contains("value")) {
            return extractStrings(((SingleMemberAnnotationExpr) ann).getMemberValue());
        }
        return new ArrayList<>();
    }

    private List<String> extractStrings(Expression expr) {
        List<String> out = new ArrayList<>();
        collectStrings(expr, out);
        return out;
    }

    /** 字符串字面量 / RequestMethod.POST 枚举引用 / 其数组, 其余忽略 */
    private static void collectStrings(Expression expr, List<String> out) {
        if (expr.isStringLiteralExpr()) {
            out.add(expr.asStringLiteralExpr().getValue());
        } else if (expr.isFieldAccessExpr()) {
            // RequestMethod.POST → "POST"; 全限定写法取最后一段
            out.add(expr.asFieldAccessExpr().getNameAsString());
        } else if (expr.isNameExpr()) {
            // 静态导入的 POST
            out.add(expr.asNameExpr().getNameAsString());
        } else if (expr.isArrayInitializerExpr()) {
            for (Expression item : expr.asArrayInitializerExpr().getValues()) {
                collectStrings(item, out);
            }
        }
    }

    public static class MappingInfo {
        /** GET/POST/... 或 ANY (接受所有标准方法) */
        public final List<String> httpMethods;
        /** 可选路径列表, 空串表示仅用类级前缀 */
        public final List<String> paths;
        public MappingInfo(List<String> httpMethods, List<String> paths) {
            this.httpMethods = httpMethods;
            this.paths = paths;
        }
    }
}
