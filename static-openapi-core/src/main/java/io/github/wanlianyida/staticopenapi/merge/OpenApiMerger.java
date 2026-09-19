package io.github.wanlianyida.staticopenapi.merge;

/**
 * 文档信息合并: 优先级语义, 首个非空生效.
 *
 * <p>统一顺序: swagger 3 注解 > swagger 2 注解 > JavaDoc > 默认值.
 * 即 javadoc 与 swagger 注解同时出现时以 swagger 为主, javadoc 只在注解缺失时兜底.
 */
public class OpenApiMerger {

    public String mergeSummary(String swagger3Summary, String swagger2Value,
                                String javadocSummary, String defaultValue) {
        return firstNonEmpty(swagger3Summary, swagger2Value, javadocSummary, defaultValue);
    }

    public String mergeDescription(String swagger3Description, String swagger2Notes,
                                    String javadocDescription) {
        return firstNonEmpty(swagger3Description, swagger2Notes, javadocDescription);
    }

    /** 返回首个 trim 后非空的候选值; 全空返回空串 */
    public static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            String t = c.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }
}
