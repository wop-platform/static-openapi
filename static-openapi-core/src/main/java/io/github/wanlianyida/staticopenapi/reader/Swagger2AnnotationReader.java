package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import io.github.wanlianyida.staticopenapi.model.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读 swagger 2.x 注解 (io.swagger.annotations.*).
 *
 * <ul>
 *   <li>@Api(tags/description) → class 级别 Tag</li>
 *   <li>@ApiOperation(value/notes/hidden) → method summary/description</li>
 *   <li>@ApiParam(value/name/required/hidden) → parameter description</li>
 *   <li>@ApiModel(value/description) → schema 重命名 + description</li>
 *   <li>@ApiModelProperty(value/notes/example/name/required/hidden) → schema property</li>
 * </ul>
 */
public class Swagger2AnnotationReader {

    private static final String NS_API = "io.swagger.annotations.Api";
    private static final String NS_API_OPERATION = "io.swagger.annotations.ApiOperation";
    private static final String NS_API_RESPONSES = "io.swagger.annotations.ApiResponses";
    private static final String NS_API_PARAM = "io.swagger.annotations.ApiParam";
    private static final String NS_API_MODEL = "io.swagger.annotations.ApiModel";
    private static final String NS_API_MODEL_PROPERTY = "io.swagger.annotations.ApiModelProperty";

    /** 读 class 级别 @Api → Tag(name=tags[0], description=description). */
    public Optional<Tag> readClassTag(ClassOrInterfaceDeclaration cls) {
        Optional<AnnotationExpr> ann = findAnnotation(cls, NS_API);
        if (ann.isEmpty()) return Optional.empty();

        List<String> tags = stringArrayValue(ann.get(), "tags");
        String description = stringValue(ann.get(), "description");
        if (tags.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Tag(tags.get(0), description));
    }

    /** 读 @ApiOperation → summary/description (供 Operation merger 使用). */
    public OperationAnnotation readOperation(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = findAnnotation(method, NS_API_OPERATION);
        if (ann.isEmpty()) return null;

        String value = stringValue(ann.get(), "value");
        String notes = stringValue(ann.get(), "notes");
        return new OperationAnnotation(value, notes);
    }

    public Map<String, ResponseAnnotation> readApiResponses(MethodDeclaration method) {
        Optional<AnnotationExpr> ann = findAnnotation(method, NS_API_RESPONSES);
        if (ann.isEmpty()) return null;
        Map<String, ResponseAnnotation> responses = new LinkedHashMap<>();
        for (NormalAnnotationExpr respAnn : Swagger3AnnotationReader.memberAnnotationList(ann.get(), "value")) {
            // code 官方是 int, 但也宽容支持字符串写法; message 同理
            String responseCode = literalValue(respAnn, "code");
            if (responseCode.isEmpty()) continue;
            responses.put(responseCode, new ResponseAnnotation(literalValue(respAnn, "message")));
        }
        return responses;
    }

    /** 字面量文本: 字符串去掉引号, int/枚举引用取源码文本 */
    private static String literalValue(AnnotationExpr ann, String key) {
        Expression v = Swagger3AnnotationReader.memberValue(ann, key);
        if (v == null) return "";
        if (v.isStringLiteralExpr()) return v.asStringLiteralExpr().getValue();
        return v.toString();
    }

    /** 读 @ApiParam → parameter annotation. */
    public ParamAnnotation readParam(Parameter param) {
        Optional<AnnotationExpr> ann = findAnnotationOnParam(param, NS_API_PARAM);
        if (ann.isEmpty()) return null;
        String value = stringValue(ann.get(), "value");
        String name = stringValue(ann.get(), "name");
        boolean required = booleanValue(ann.get(), "required", false);
        boolean hidden = booleanValue(ann.get(), "hidden", false);
        return new ParamAnnotation(value, name, required, hidden);
    }

    /** 读 @ApiModel → schema 重命名 (value) + description. */
    public ModelAnnotation readModel(ClassOrInterfaceDeclaration cls) {
        Optional<AnnotationExpr> ann = findAnnotation(cls, NS_API_MODEL);
        if (ann.isEmpty()) return null;
        String value = stringValue(ann.get(), "value");
        String description = stringValue(ann.get(), "description");
        return new ModelAnnotation(value, description);
    }

    /** 读 @ApiModelProperty → field property info. */
    public PropertyAnnotation readProperty(FieldDeclaration field) {
        Optional<AnnotationExpr> ann = findAnnotationOnField(field, NS_API_MODEL_PROPERTY);
        if (ann.isEmpty()) return null;
        String value = stringValue(ann.get(), "value");
        String notes = stringValue(ann.get(), "notes");
        String example = stringValue(ann.get(), "example");
        String name = stringValue(ann.get(), "name");
        boolean required = booleanValue(ann.get(), "required", false);
        boolean hidden = booleanValue(ann.get(), "hidden", false);
        return new PropertyAnnotation(value, notes, example, name, required, hidden);
    }

    // ---- helpers ----

    private Optional<AnnotationExpr> findAnnotation(ClassOrInterfaceDeclaration cls, String fqn) {
        return cls.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, fqn))
                .findFirst();
    }

    private Optional<AnnotationExpr> findAnnotation(MethodDeclaration method, String fqn) {
        return method.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, fqn))
                .findFirst();
    }

    private Optional<AnnotationExpr> findAnnotationOnParam(com.github.javaparser.ast.body.Parameter param, String fqn) {
        return param.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, fqn))
                .findFirst();
    }

    private Optional<AnnotationExpr> findAnnotationOnField(FieldDeclaration field, String fqn) {
        return field.getAnnotations().stream()
                .filter(a -> AnnotationUtils.matches(a, fqn))
                .findFirst();
    }

    private String stringValue(AnnotationExpr ann, String key) {
        if (ann instanceof NormalAnnotationExpr) {
            for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
                if (p.getNameAsString().equals(key) && p.getValue().isStringLiteralExpr()) {
                    return p.getValue().asStringLiteralExpr().getValue();
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr) {
            // @ApiModelProperty("商户号") — 单值写法,默认绑定到 value
            if ("value".equals(key) && ((SingleMemberAnnotationExpr) ann).getMemberValue().isStringLiteralExpr()) {
                return ((SingleMemberAnnotationExpr) ann).getMemberValue().asStringLiteralExpr().getValue();
            }
        }
        return "";
    }

    private List<String> stringArrayValue(AnnotationExpr ann, String key) {
        if (!(ann instanceof NormalAnnotationExpr)) return Collections.emptyList();
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (!p.getNameAsString().equals(key)) continue;
            if (p.getValue().isArrayInitializerExpr()) {
                List<String> out = new ArrayList<>();
                p.getValue().asArrayInitializerExpr().getValues().forEach(v -> {
                    if (v.isStringLiteralExpr()) out.add(v.asStringLiteralExpr().getValue());
                });
                return out;
            }
            if (p.getValue().isStringLiteralExpr()) {
                return Collections.singletonList(p.getValue().asStringLiteralExpr().getValue());
            }
        }
        return Collections.emptyList();
    }

    private boolean booleanValue(AnnotationExpr ann, String key, boolean defaultValue) {
        if (!(ann instanceof NormalAnnotationExpr)) return defaultValue;
        for (MemberValuePair p : ((NormalAnnotationExpr) ann).getPairs()) {
            if (p.getNameAsString().equals(key)) {
                String v = p.getValue().toString();
                return "true".equals(v);
            }
        }
        return defaultValue;
    }

    public static class OperationAnnotation {
        public final String value;
        public final String notes;
        public OperationAnnotation(String value, String notes) {
            this.value = value;
            this.notes = notes;
        }
    }

    public static class ResponseAnnotation {
        public final String description;
        public ResponseAnnotation(String description) {
            this.description = description;
        }
    }

    public static class ParamAnnotation {
        public final String value;
        public final String name;
        public final boolean required;
        public final boolean hidden;
        public ParamAnnotation(String value, String name, boolean required, boolean hidden) {
            this.value = value;
            this.name = name;
            this.required = required;
            this.hidden = hidden;
        }
    }

    public static class ModelAnnotation {
        public final String value;
        public final String description;
        public ModelAnnotation(String value, String description) {
            this.value = value;
            this.description = description;
        }
    }

    public static class PropertyAnnotation {
        public final String value;
        public final String notes;
        public final String example;
        public final String name;
        public final boolean required;
        public final boolean hidden;
        public PropertyAnnotation(String value, String notes, String example,
                                  String name, boolean required, boolean hidden) {
            this.value = value;
            this.notes = notes;
            this.example = example;
            this.name = name;
            this.required = required;
            this.hidden = hidden;
        }
    }
}
