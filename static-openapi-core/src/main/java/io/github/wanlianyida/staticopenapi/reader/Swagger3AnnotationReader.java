package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import io.github.wanlianyida.staticopenapi.model.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 读 swagger 3.x / OpenAPI 3 注解 (io.swagger.v3.oas.annotations.*).
 *
 * <ul>
 *   <li>@Tag(name/description) → class 级别 Tag</li>
 *   <li>@Operation(summary/description/operationId/deprecated/hidden) → operation</li>
 *   <li>@Parameter(name/description/required/in/hidden) → parameter</li>
 *   <li>@Schema(name/description/example/required/requiredMode/hidden) → schema</li>
 *   <li>@RequestBody(required) → method 级请求体校验</li>
 * </ul>
 */
public class Swagger3AnnotationReader {

    private static final String NS_TAG = "io.swagger.v3.oas.annotations.tags.Tag";
    private static final String NS_OPERATION = "io.swagger.v3.oas.annotations.Operation";
    private static final String NS_PARAMETER = "io.swagger.v3.oas.annotations.Parameter";
    private static final String NS_SCHEMA = "io.swagger.v3.oas.annotations.media.Schema";
    private static final String NS_REQUEST_BODY = "io.swagger.v3.oas.annotations.parameters.RequestBody";
    private static final String NS_API_RESPONSES = "io.swagger.v3.oas.annotations.responses.ApiResponses";
    private static final String NS_API_RESPONSE = "io.swagger.v3.oas.annotations.responses.ApiResponse";
    private static final String NS_CONTENT = "io.swagger.v3.oas.annotations.media.Content";
    private static final String NS_ARRAY_SCHEMA = "io.swagger.v3.oas.annotations.media.ArraySchema";

    /** 读 class 级别 @Tag → Tag(name, description). */
    public Optional<Tag> readClassTag(ClassOrInterfaceDeclaration cls) {
        Optional<AnnotationExpr> ann = cls.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_TAG))
                .findFirst();
        if (ann.isEmpty()) return Optional.empty();
        String name = stringValue(ann.get(), "name");
        if (name.isEmpty()) return Optional.empty();
        return Optional.of(new Tag(name, stringValue(ann.get(), "description")));
    }

    public OperationInfo readOperation(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = method.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_OPERATION))
                .findFirst();
        if (ann.isEmpty()) return null;
        AnnotationExpr op = ann.get();
        return new OperationInfo(
                stringValue(op, "summary"),
                stringValue(op, "description"),
                stringValue(op, "operationId"),
                booleanValue(op, "deprecated", false),
                booleanValue(op, "hidden", false),
                stringArrayValue(op, "tags"));
    }

    public ParamInfo readParam(Parameter param) {
        Optional<AnnotationExpr> ann = param.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_PARAMETER))
                .findFirst();
        if (ann.isEmpty()) return null;
        // in 属性可能写字面量 ("path") 或枚举引用 (ParameterIn.PATH), 统一转小写
        String in = expressionValue(ann.get(), "in").toLowerCase(Locale.ROOT);
        if ("path".equals(in) || "header".equals(in) || "query".equals(in) || "cookie".equals(in)) {
            // 合法取值, 直接使用
        } else {
            in = "";
        }
        return new ParamInfo(
                stringValue(ann.get(), "name"),
                stringValue(ann.get(), "description"),
                booleanValue(ann.get(), "required", false),
                in,
                booleanValue(ann.get(), "hidden", false));
    }

    public SchemaInfo readModel(ClassOrInterfaceDeclaration cls) {
        Optional<AnnotationExpr> ann = cls.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_SCHEMA))
                .findFirst();
        if (ann.isEmpty()) return null;
        return new SchemaInfo(stringValue(ann.get(), "name"), stringValue(ann.get(), "description"));
    }

    public PropertyInfo readProperty(FieldDeclaration field) {
        Optional<AnnotationExpr> ann = field.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_SCHEMA))
                .findFirst();
        if (ann.isEmpty()) return null;
        // requiredMode 可能是字符串字面量或枚举引用 (Schema.RequiredMode.REQUIRED)
        String requiredMode = expressionValue(ann.get(), "requiredMode");
        // @Schema 无 required=true (旧版本), requiredMode = REQUIRED 同样表达必填
        boolean required = "true".equalsIgnoreCase(stringValue(ann.get(), "required"))
                || "REQUIRED".equalsIgnoreCase(requiredMode);
        return new PropertyInfo(
                stringValue(ann.get(), "name"),
                stringValue(ann.get(), "description"),
                stringValue(ann.get(), "example"),
                required,
                booleanValue(ann.get(), "hidden", false));
    }

    public RequestBodyInfo readRequestBody(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = method.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_REQUEST_BODY))
                .findFirst();
        if (ann.isEmpty()) return null;
        AnnotationExpr rb = ann.get();
        return new RequestBodyInfo(
                stringValue(rb, "description"),
                booleanValue(rb, "required", false),
                stringValue(rb, "contentType"));
    }

    public Map<String, ResponseInfo> readApiResponses(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = method.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, NS_API_RESPONSES))
                .findFirst();
        if (ann.isEmpty()) return null;
        Map<String, ResponseInfo> responses = new LinkedHashMap<>();
        AnnotationExpr responsesAnn = ann.get();
        for (MemberValuePair pair : ((NormalAnnotationExpr) responsesAnn).getPairs()) {
            if (!"value".equals(pair.getNameAsString())) continue;
            if (!pair.getValue().isArrayInitializerExpr()) continue;
            for (Expression item : pair.getValue().asArrayInitializerExpr().getValues()) {
                if (!item.isNormalAnnotationExpr()) continue;
                NormalAnnotationExpr respAnn = item.asNormalAnnotationExpr();
                String responseCode = null;
                String description = "";
                for (MemberValuePair respPair : respAnn.getPairs()) {
                    switch (respPair.getNameAsString()) {
                        case "responseCode":
                            responseCode = stringValue(respAnn, "responseCode");
                            break;
                        case "description":
                            description = stringValue(respAnn, "description");
                            break;
                    }
                }
                if (responseCode == null || responseCode.isEmpty()) continue;
                responses.put(responseCode, new ResponseInfo(description));
            }
        }
        return responses;
    }

    // ---- helpers ----

    /** 字符串属性; 不支持常量引用等非常量表达式 (返回空串) */
    private String stringValue(AnnotationExpr ann, String key) {
        if (!(ann instanceof NormalAnnotationExpr)) return "";
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (p.getNameAsString().equals(key) && p.getValue().isStringLiteralExpr()) {
                return p.getValue().asStringLiteralExpr().getValue();
            }
        }
        return "";
    }

    /** 属性值为字符串字面量或枚举引用 (ParameterIn.PATH → "PATH") */
    private String expressionValue(AnnotationExpr ann, String key) {
        if (!(ann instanceof NormalAnnotationExpr)) return "";
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (!p.getNameAsString().equals(key)) continue;
            if (p.getValue().isStringLiteralExpr()) {
                return p.getValue().asStringLiteralExpr().getValue();
            }
            if (p.getValue().isFieldAccessExpr()) {
                return p.getValue().asFieldAccessExpr().getNameAsString();
            }
            if (p.getValue().isNameExpr()) {
                return p.getValue().asNameExpr().getNameAsString();
            }
        }
        return "";
    }

    private boolean booleanValue(AnnotationExpr ann, String key, boolean defaultValue) {
        if (!(ann instanceof NormalAnnotationExpr)) return defaultValue;
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (p.getNameAsString().equals(key)) {
                return "true".equals(p.getValue().toString());
            }
        }
        return defaultValue;
    }

    private List<String> stringArrayValue(AnnotationExpr ann, String key) {
        if (!(ann instanceof NormalAnnotationExpr)) return List.of();
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (!p.getNameAsString().equals(key)) continue;
            if (p.getValue().isArrayInitializerExpr()) {
                List<String> out = new ArrayList<>();
                for (Expression e : p.getValue().asArrayInitializerExpr().getValues()) {
                    if (e.isStringLiteralExpr()) out.add(e.asStringLiteralExpr().getValue());
                }
                return out;
            }
        }
        return List.of();
    }

    public static class OperationInfo {
        public final String summary;
        public final String description;
        public final String operationId;
        public final boolean deprecated;
        public final boolean hidden;
        public final List<String> tags;
        public OperationInfo(String summary, String description, String operationId,
                             boolean deprecated, boolean hidden, List<String> tags) {
            this.summary = summary;
            this.description = description;
            this.operationId = operationId;
            this.deprecated = deprecated;
            this.hidden = hidden;
            this.tags = tags;
        }
    }

    public static class RequestBodyInfo {
        public final String description;
        public final boolean required;
        public final String contentType;
        public RequestBodyInfo(String description, boolean required, String contentType) {
            this.description = description;
            this.required = required;
            this.contentType = contentType;
        }
    }

    public static class ResponseInfo {
        public final String description;
        public ResponseInfo(String description) {
            this.description = description;
        }
    }

    public static class ParamInfo {
        public final String name;
        public final String description;
        public final boolean required;
        /** query/path/header/cookie, 空串 = 未声明 */
        public final String in;
        public final boolean hidden;
        public ParamInfo(String name, String description, boolean required, String in, boolean hidden) {
            this.name = name;
            this.description = description;
            this.required = required;
            this.in = in;
            this.hidden = hidden;
        }
    }

    public static class SchemaInfo {
        public final String name;
        public final String description;
        public SchemaInfo(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }

    public static class PropertyInfo {
        public final String name;
        public final String description;
        public final String example;
        public final boolean required;
        public final boolean hidden;
        public PropertyInfo(String name, String description, String example,
                            boolean required, boolean hidden) {
            this.name = name;
            this.description = description;
            this.example = example;
            this.required = required;
            this.hidden = hidden;
        }
    }
}
