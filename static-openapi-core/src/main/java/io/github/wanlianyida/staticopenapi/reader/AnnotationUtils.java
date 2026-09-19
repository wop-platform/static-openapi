package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.util.Optional;

/**
 * 注解匹配工具 (供 Spring/Swagger2/Swagger3 reader 及 generator 共用).
 */
public final class AnnotationUtils {

    private static final String NS_HIDDEN_V3 = "io.swagger.v3.oas.annotations.Hidden";
    private static final String NS_API_IGNORE = "io.swagger.annotations.ApiIgnore";
    private static final String NS_OPERATION_V3 = "io.swagger.v3.oas.annotations.Operation";
    private static final String NS_API_OPERATION_V2 = "io.swagger.annotations.ApiOperation";
    private static final String NS_PARAMETER_V3 = "io.swagger.v3.oas.annotations.Parameter";
    private static final String NS_SCHEMA_V3 = "io.swagger.v3.oas.annotations.media.Schema";

    private AnnotationUtils() {}

    /**
     * 判断注解是否匹配给定的全限定类名.
     *
     * <p>注解写的是简名时参考所在 CompilationUnit 的 import: 若工程 import 的是
     * 同简名但不同包的注解 (如自定义 @Tag), 则不匹配, 避免误识别.
     * 无相关 import (同包使用/通配符 import) 时按短名匹配.
     */
    static boolean matches(AnnotationExpr ann, String fqn) {
        String name = ann.getNameAsString();
        if (name.equals(fqn)) return true;
        String sn = shortName(fqn);
        if (name.contains(".")) {
            return name.endsWith("." + sn);
        }
        return !conflictingImport(ann, sn, fqn) && name.equals(sn);
    }

    /** 简名注解是否与目标 fqn 存在 import 冲突 (import 了同简名的其他类) */
    private static boolean conflictingImport(AnnotationExpr ann, String simpleName, String fqn) {
        Optional<CompilationUnit> cu = ann.findAncestor(CompilationUnit.class);
        if (cu.isEmpty()) return false;
        for (ImportDeclaration imp : cu.get().getImports()) {
            String impName = imp.getNameAsString();
            if (impName.equals(fqn)) return false;
            if (impName.endsWith("." + simpleName)) return true;
        }
        return false;
    }

    /** 从全限定名中提取短名 (最后一个 '.' 之后的部分) */
    static String shortName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * 类/方法/参数/字段是否应从文档中隐藏:
     * v3 @Hidden, v2 @ApiIgnore, 或 @Operation/@ApiOperation/@Parameter/@Schema(hidden = true).
     */
    public static boolean isHidden(NodeWithAnnotations<?> node) {
        for (AnnotationExpr ann : node.getAnnotations()) {
            if (matches(ann, NS_HIDDEN_V3) || matches(ann, NS_API_IGNORE)) return true;
            String sn = shortName(ann.getNameAsString());
            boolean hiddenAttr = false;
            if ("Operation".equals(sn) && matches(ann, NS_OPERATION_V3)) {
                hiddenAttr = attrBoolean(ann, "hidden", false);
            } else if ("ApiOperation".equals(sn) && matches(ann, NS_API_OPERATION_V2)) {
                hiddenAttr = attrBoolean(ann, "hidden", false);
            } else if ("Parameter".equals(sn) && matches(ann, NS_PARAMETER_V3)) {
                hiddenAttr = attrBoolean(ann, "hidden", false);
            } else if ("Schema".equals(sn) && matches(ann, NS_SCHEMA_V3)) {
                hiddenAttr = attrBoolean(ann, "hidden", false);
            }
            if (hiddenAttr) return true;
        }
        return false;
    }

    static boolean attrBoolean(AnnotationExpr ann, String key, boolean defaultValue) {
        if (!(ann instanceof NormalAnnotationExpr)) return defaultValue;
        for (var p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (p.getNameAsString().equals(key)) {
                return "true".equals(p.getValue().toString());
            }
        }
        return defaultValue;
    }
}
