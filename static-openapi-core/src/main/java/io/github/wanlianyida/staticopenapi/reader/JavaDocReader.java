package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;

/**
 * 读 JavaDoc 注释作为兜底. 优先级低于 swagger 注解.
 *
 * - 类的 JavaDoc 第一句作为 description
 * - 方法的 JavaDoc 第一句作为 summary, 剩余部分作为 description
 * - 字段的 JavaDoc 作为 description
 */
public class JavaDocReader {

    /** 类/接口/枚举/Record 的 JavaDoc 描述 */
    public String readTypeDescription(TypeDeclaration<?> td) {
        if (td == null) return "";
        return td.getJavadoc().map(j -> j.getDescription().toText()).orElse("").trim();
    }

    public String readClassDescription(ClassOrInterfaceDeclaration cls) {
        return javadocText(cls);
    }

    public String readClassSummary(ClassOrInterfaceDeclaration cls) {
        return firstSentence(javadocText(cls));
    }

    public String readMethodSummary(MethodDeclaration method) {
        return firstSentence(javadocText(method));
    }

    /**
     * 完整 javadoc, 但去掉开头 summary 部分 (避免和 summary 重复).
     * 如果全文只有 summary 长度,返回空 (description 不需要与 summary 重复).
     */
    public String readMethodDescription(MethodDeclaration method) {
        String full = javadocText(method);
        String summary = readMethodSummary(method);
        if (summary.isEmpty() || full.equals(summary)) {
            // 没有 summary 段时直接返全文; summary=full 时返回空避免重复
            return summary.isEmpty() ? full : "";
        }
        int idx = full.indexOf(summary);
        if (idx == 0) {
            String rest = full.substring(summary.length()).trim();
            return rest;
        }
        return full;
    }

    /** 字段 javadoc 作为 description */
    public String readFieldDescription(FieldDeclaration field) {
        if (field == null) return "";
        return field.getJavadoc().map(j -> j.getDescription().toText()).orElse("").trim();
    }

    public String readParamDescription(Parameter param) {
        return "";
    }

    public String readMethodParamDescription(MethodDeclaration method, String paramName) {
        if (method == null || paramName == null) return "";
        String full = javadocText(method);
        String marker = "@param " + paramName + " ";
        int idx = full.indexOf(marker);
        if (idx < 0) {
            marker = "@param " + paramName;
            idx = full.indexOf(marker);
            if (idx < 0) return "";
            idx += marker.length();
        } else {
            idx += marker.length();
        }
        int end = full.indexOf("@", idx);
        String desc = end > idx ? full.substring(idx, end).trim() : full.substring(idx).trim();
        int periodIdx = desc.indexOf("。");
        return periodIdx > 0 ? desc.substring(0, periodIdx + 1) : desc;
    }

    private static String javadocText(ClassOrInterfaceDeclaration cls) {
        return cls.getJavadoc().map(j -> j.getDescription().toText()).orElse("");
    }

    private static String javadocText(MethodDeclaration method) {
        return method.getJavadoc().map(j -> j.getDescription().toText()).orElse("");
    }

    /**
     * 切到第一个完整句子. 分隔符: 中文句号/英文句号+空格/换行.
     */
    private static String firstSentence(String text) {
        if (text == null || text.isEmpty()) return "";
        String trimmed = text.trim();
        int minEnd = trimmed.length();
        int idx;
        idx = trimmed.indexOf('。');
        if (idx >= 0) minEnd = Math.min(minEnd, idx + 1);
        idx = trimmed.indexOf(". ");
        if (idx >= 0) minEnd = Math.min(minEnd, idx + 1);
        idx = trimmed.indexOf('\n');
        if (idx >= 0) minEnd = Math.min(minEnd, idx);
        return trimmed.substring(0, minEnd).trim();
    }
}

