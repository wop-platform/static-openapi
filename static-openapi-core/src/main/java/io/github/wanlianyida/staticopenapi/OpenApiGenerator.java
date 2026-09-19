package io.github.wanlianyida.staticopenapi;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import io.github.wanlianyida.staticopenapi.merge.OpenApiMerger;
import io.github.wanlianyida.staticopenapi.model.Components;
import io.github.wanlianyida.staticopenapi.model.Info;
import io.github.wanlianyida.staticopenapi.model.MediaType;
import io.github.wanlianyida.staticopenapi.model.OpenApiDocument;
import io.github.wanlianyida.staticopenapi.model.Operation;
import io.github.wanlianyida.staticopenapi.model.PathItem;
import io.github.wanlianyida.staticopenapi.model.RequestBody;
import io.github.wanlianyida.staticopenapi.model.Response;
import io.github.wanlianyida.staticopenapi.model.Schema;
import io.github.wanlianyida.staticopenapi.model.Tag;
import io.github.wanlianyida.staticopenapi.output.OpenApiWriter;
import io.github.wanlianyida.staticopenapi.reader.AnnotationUtils;
import io.github.wanlianyida.staticopenapi.reader.JavaDocReader;
import io.github.wanlianyida.staticopenapi.reader.SpringWebAnnotationReader;
import io.github.wanlianyida.staticopenapi.reader.Swagger2AnnotationReader;
import io.github.wanlianyida.staticopenapi.reader.Swagger3AnnotationReader;
import io.github.wanlianyida.staticopenapi.reader.TypeSchemaResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 静态分析 Java 源码生成 OpenAPI 3.1 spec.
 *
 * <p>核心流程:
 * <ol>
 *   <li>用 JavaParser 解析 sourceRoots 下所有 .java</li>
 *   <li>过滤 packages 下的 @Controller/@RestController (类/方法级 @Hidden 与 @ApiIgnore 跳过)</li>
 *   <li>每个 controller: 读 class 注解 (api tag) + method mapping 注解 + swagger annotation/javadoc</li>
 *   <li>对每个 method: 解析参数 + return type 为 OpenAPI schema
 *       (递归读 @RequestBody 类型, 读 @ApiModel/@ApiModelProperty/@Schema/JavaDoc)</li>
 *   <li>合并到 OpenApiDocument, OpenApiWriter 输出 JSON</li>
 * </ol>
 */
public class OpenApiGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenApiGenerator.class);

    /** 共享 JavaParser 单例 (创建开销大, 复用) */
    private static final JavaParser SHARED_PARSER = new JavaParser();

    /** OpenAPI 3.1 path item 下的标准 HTTP 动词 */
    private static final List<String> STANDARD_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "head", "options");

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String RESPONSE_200 = "200";
    private static final String RESPONSE_500 = "500";

    /** 浅复制 operation (用于 ANY method / 多 path 展开) */
    private static Operation copyOperation(Operation src) {
        Operation copy = new Operation()
                .setSummary(src.getSummary())
                .setDescription(src.getDescription())
                .setOperationId(src.getOperationId())
                .setDeprecated(src.isDeprecated())
                .setRequestBody(src.getRequestBody());
        copy.setTags(new java.util.ArrayList<>(src.getTags()));
        for (var p : src.getParameters()) copy.addParameter(p);
        for (var e : src.getResponses().entrySet()) copy.addResponse(e.getKey(), e.getValue());
        return copy;
    }

    private final GeneratorConfig config;
    private final SpringWebAnnotationReader springWeb = new SpringWebAnnotationReader();
    private final Swagger2AnnotationReader swagger2 = new Swagger2AnnotationReader();
    private final Swagger3AnnotationReader swagger3 = new Swagger3AnnotationReader();
    private final JavaDocReader javadoc = new JavaDocReader();
    private final OpenApiMerger merger = new OpenApiMerger();
    private final OpenApiWriter writer = new OpenApiWriter();

    public OpenApiGenerator(GeneratorConfig config) {
        this.config = config;
    }

    /** 主入口: 扫描 → 构造 → 输出. 返回输出文件路径. */
    public Path generate() throws IOException {
        log.info("OpenAPI generator starting. project={}, version={}",
                config.getProjectName(), config.getOpenapiVersion());

        Path projectDir = Paths.get(config.getProjectDir());
        List<Path> sourceRoots = detectSourceRoots(projectDir);
        if (sourceRoots.isEmpty()) {
            log.warn("No source roots found under {}", projectDir.toAbsolutePath());
        }
        for (Path root : sourceRoots) {
            log.info("Using source root: {}", root.toAbsolutePath());
        }

        TypeSchemaResolver typeResolver = new TypeSchemaResolver(sourceRoots, config.getSchemaNameStyle());

        OpenApiDocument doc = new OpenApiDocument()
                .setOpenapi(config.getOpenapiVersion())
                .setInfo(buildInfo());
        Components components = doc.getComponents();
        Map<String, Tag> tagMap = new LinkedHashMap<>();

        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                        .forEach(javaFile -> parseOneController(javaFile, typeResolver, doc, tagMap, components));
            } catch (IOException e) {
                log.warn("Failed to walk {}: {}", root, e.getMessage());
            }
        }

        // tagMap → 顶层 tags (按出现顺序)
        tagMap.values().forEach(doc::addTag);

        Path output = writer.write(doc, config);
        log.info("OpenAPI spec written to: {}", output);
        return output;
    }

    /** 递归查找时不进入的目录 (构建产物/版本控制/IDE 等) */
    private static final List<String> SKIP_DIRS = List.of(
            "target", "build", "out", "node_modules", ".git", ".svn", ".idea", ".mvn", ".gradle");

    /**
     * 发现源码根目录: 递归遍历 projectDir (含多模块工程), 收集所有 src/main/java.
     * 测试源码 (src/test/java) 不参与文档生成.
     */
    private List<Path> detectSourceRoots(Path projectDir) {
        List<Path> roots = new ArrayList<>();
        if (!Files.isDirectory(projectDir)) {
            return roots;
        }
        try {
            Files.walkFileTree(projectDir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = dir.getFileName().toString();
                    if (!dir.equals(projectDir) && SKIP_DIRS.contains(name)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    if (isMavenJavaSourceRoot(dir)) {
                        roots.add(dir);
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk {}: {}", projectDir, e.getMessage());
        }
        return roots;
    }

    /** 目录形如 <模块>/src/main/java */
    private static boolean isMavenJavaSourceRoot(Path dir) {
        if (!"java".equals(dir.getFileName().toString())) return false;
        Path parent = dir.getParent();
        if (parent == null || !"main".equals(parent.getFileName().toString())) return false;
        Path grand = parent.getParent();
        return grand != null && "src".equals(grand.getFileName().toString());
    }

    private void parseOneController(Path javaFile, TypeSchemaResolver typeResolver,
                                     OpenApiDocument doc, Map<String, Tag> tagMap, Components components) {

        CompilationUnit unit;
        try {
            ParseResult<CompilationUnit> result = SHARED_PARSER.parse(javaFile);
            if (!result.isSuccessful()) return;
            unit = result.getResult().orElse(null);
            if (unit == null) return;
        } catch (IOException e) {
            return;
        }

        for (TypeDeclaration<?> td : unit.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration)) continue;
            ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) td;
            if (!springWeb.isController(cls)) continue;
            if (!inPackages(cls, config.getPackages())) continue;
            // 类级 @Hidden / @ApiIgnore: 整个 controller 跳过
            if (AnnotationUtils.isHidden(cls)) continue;

            List<String> basePaths = springWeb.readBasePaths(cls);
            Map<String, Tag> classTags = readClassTags(cls);
            // 顶层 tags 登记 (按出现顺序, @Tag/@Api 的 description 保留)
            classTags.forEach(tagMap::putIfAbsent);

            Map<MethodKey, MethodDeclaration> methods = collectMethods(cls, typeResolver);

            for (Map.Entry<MethodKey, MethodDeclaration> entry : methods.entrySet()) {
                MethodDeclaration method = entry.getValue();
                // 方法级 @Hidden / @ApiIgnore / @Operation(hidden) / @ApiOperation(hidden): 跳过
                if (AnnotationUtils.isHidden(method)) continue;

                SpringWebAnnotationReader.MappingInfo mapping = springWeb.readMapping(method);
                if (mapping == null) continue;

                Operation operation = buildOperation(method, classTags, typeResolver, components);

                // 多前缀 × 多路径 × 多 HTTP method 全部展开
                boolean first = true;
                for (String base : basePaths) {
                    for (String sub : mapping.paths) {
                        String fullPath = joinPath(base, sub);
                        if (fullPath.isEmpty()) continue;
                        for (String httpMethod : mapping.httpMethods) {
                            if (SpringWebAnnotationReader.ANY_METHOD.equals(httpMethod)) {
                                log.warn("@RequestMapping on {}.{}() without method, replicating to all standard methods",
                                        cls.getNameAsString(), method.getNameAsString());
                                for (String m : STANDARD_METHODS) {
                                    PathItem item = doc.getPaths().computeIfAbsent(fullPath, k -> new PathItem());
                                    item.addOperation(m, first ? operation : copyOperation(operation));
                                    first = false;
                                }
                            } else {
                                PathItem item = doc.getPaths().computeIfAbsent(fullPath, k -> new PathItem());
                                item.addOperation(httpMethod, first ? operation : copyOperation(operation));
                                first = false;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * class 级 tag 候选 (name → Tag): @Tag (v3) 优先, 其次 @Api (v2), 最后从类名兜底.
     * LinkedHashMap 保证去重且顺序稳定.
     */
    private Map<String, Tag> readClassTags(ClassOrInterfaceDeclaration cls) {
        Map<String, Tag> tags = new LinkedHashMap<>();
        swagger3.readClassTag(cls).ifPresent(t -> tags.putIfAbsent(t.getName(), t));
        swagger2.readClassTag(cls).ifPresent(t -> tags.putIfAbsent(t.getName(), t));
        if (tags.isEmpty()) {
            // 兜底: 无 swagger 注解时从 controller 类名生成 tag
            String name = stripControllerSuffix(cls.getNameAsString());
            tags.put(name, new Tag(name));
        }
        return tags;
    }

    /**
     * 收集 controller 上的方法 + 它 implements 接口上的方法.
     * 重载方法 (同名不同参数类型) 各自保留; 接口方法签名写法与实现不一致时按名字+参数个数兜底匹配.
     */
    private Map<MethodKey, MethodDeclaration> collectMethods(ClassOrInterfaceDeclaration cls,
                                                              TypeSchemaResolver typeResolver) {
        Map<MethodKey, MethodDeclaration> methods = new LinkedHashMap<>();
        for (MethodDeclaration m : cls.getMethods()) {
            if (m == null || !m.isPublic()) continue;
            methods.put(new MethodKey(m), m);
        }
        for (var implementedType : cls.getImplementedTypes()) {
            String ifName = stripGenerics(implementedType.getNameAsString());
            Path ifFile = typeResolver.getSourceBySimpleName().get(ifName);
            if (ifFile == null) continue;
            try {
                CompilationUnit ifUnit = SHARED_PARSER.parse(ifFile).getResult().orElse(null);
                if (ifUnit == null) continue;
                for (TypeDeclaration<?> ifTd : ifUnit.getTypes()) {
                    if (!(ifTd instanceof ClassOrInterfaceDeclaration)) continue;
                    if (!((ClassOrInterfaceDeclaration) ifTd).isInterface()) continue;
                    for (MethodDeclaration ifMethod : ((ClassOrInterfaceDeclaration) ifTd).getMethods()) {
                        if (ifMethod == null) continue;
                        mergeInterfaceMethod(methods, ifMethod);
                    }
                }
            } catch (IOException ignore) {
                // skip unreachable interface
            }
        }
        return methods;
    }

    /** 接口方法合并: 精确签名匹配; 不一致时按 name+参数个数兜底, mapping 注解优先取接口侧 */
    private void mergeInterfaceMethod(Map<MethodKey, MethodDeclaration> methods, MethodDeclaration ifMethod) {
        MethodKey key = new MethodKey(ifMethod);
        MethodDeclaration existing = methods.get(key);
        if (existing != null) {
            if (!hasMapping(existing) && hasMapping(ifMethod)) {
                methods.put(key, ifMethod);
            }
            return;
        }
        MethodDeclaration sameNameCount = null;
        MethodKey sameNameCountKey = null;
        for (Map.Entry<MethodKey, MethodDeclaration> e : methods.entrySet()) {
            if (e.getKey().matchesLoosely(ifMethod)) {
                sameNameCount = e.getValue();
                sameNameCountKey = e.getKey();
                break;
            }
        }
        if (sameNameCount == null) {
            methods.put(key, ifMethod);
        } else if (!hasMapping(sameNameCount) && hasMapping(ifMethod)) {
            methods.remove(sameNameCountKey);
            methods.put(key, ifMethod);
        }
    }

    /** method 上是否带 mapping 注解 */
    private boolean hasMapping(MethodDeclaration method) {
        if (method == null) return false;
        return method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.endsWith("RequestMapping") || n.endsWith("GetMapping")
                    || n.endsWith("PostMapping") || n.endsWith("PutMapping")
                    || n.endsWith("DeleteMapping") || n.endsWith("PatchMapping");
        });
    }

    private boolean isJavaDeprecated(MethodDeclaration method) {
        return method.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.equals("Deprecated") || n.endsWith(".Deprecated");
        });
    }

    private Operation buildOperation(MethodDeclaration method, Map<String, Tag> classTags,
                                      TypeSchemaResolver typeResolver, Components components) {

        Swagger3AnnotationReader.OperationInfo ann3 = swagger3.readOperation(method);
        Swagger2AnnotationReader.OperationAnnotation ann2 = swagger2.readOperation(method);

        // operationId: @Operation(operationId) > 方法名
        Operation operation = new Operation()
                .setOperationId(ann3 != null && !ann3.operationId.isEmpty()
                        ? ann3.operationId
                        : method.getNameAsString());

        // 1. tags (class 级已去重, @Operation(tags=...) 追加)
        for (Tag t : classTags.values()) {
            operation.addTag(t.getName());
        }
        if (ann3 != null && ann3.tags != null) {
            for (String t : ann3.tags) {
                if (!t.isEmpty()) operation.addTag(t);
            }
        }

        // 2. summary/description: swagger3 > swagger2 > javadoc > 方法名兜底
        operation.setSummary(merger.mergeSummary(
                ann3 != null ? ann3.summary : null,
                ann2 != null ? ann2.value : null,
                javadoc.readMethodSummary(method),
                method.getNameAsString()));

        operation.setDescription(merger.mergeDescription(
                ann3 != null ? ann3.description : null,
                ann2 != null ? ann2.notes : null,
                javadoc.readMethodDescription(method)));

        // 3. deprecated: @Operation(deprecated=true) 或 Java @Deprecated
        operation.setDeprecated((ann3 != null && ann3.deprecated) || isJavaDeprecated(method));

        // 4. parameters (跳过 @RequestBody 参数与 hidden 参数)
        for (Parameter param : method.getParameters()) {
            if (isRequestBodyParam(param) || AnnotationUtils.isHidden(param)) continue;
            operation.addParameter(buildParameter(param, typeResolver, components));
        }

        // 5. request body: 第一个带 @RequestBody 注解的参数
        Swagger3AnnotationReader.RequestBodyInfo rbInfo = swagger3.readRequestBody(method);
        for (Parameter param : method.getParameters()) {
            if (!isRequestBodyParam(param)) continue;
            Schema bodySchema = typeResolver.resolve(param.getType(), components.getSchemas());
            String rbDesc = rbInfo != null ? rbInfo.description : "";
            if (rbDesc.isEmpty()) {
                rbDesc = javadoc.readMethodParamDescription(method, param.getNameAsString());
            }
            if (!rbDesc.isEmpty() && bodySchema.getRef() != null) {
                bodySchema.setDescription(rbDesc);
            }
            RequestBody rb = new RequestBody()
                    .setRequired(rbInfo != null ? rbInfo.required : true);
            if (!rbDesc.isEmpty()) {
                rb.setDescription(rbDesc);
            }
            String contentType = (rbInfo != null && !rbInfo.contentType.isEmpty())
                    ? rbInfo.contentType : CONTENT_TYPE_JSON;
            rb.addContent(contentType, new MediaType().setSchema(bodySchema));
            operation.setRequestBody(rb);
            break;
        }

        // 6. responses: @ApiResponses 声明的响应 + 200/500 自动生成
        Map<String, Swagger3AnnotationReader.ResponseInfo> apiResponsesV3 = swagger3.readApiResponses(method);
        Map<String, Swagger2AnnotationReader.ResponseAnnotation> apiResponsesV2 = swagger2.readApiResponses(method);
        boolean has200 = (apiResponsesV3 != null && apiResponsesV3.containsKey(RESPONSE_200))
                || (apiResponsesV2 != null && apiResponsesV2.containsKey("200"));
        boolean has500 = (apiResponsesV3 != null && apiResponsesV3.containsKey(RESPONSE_500))
                || (apiResponsesV2 != null && apiResponsesV2.containsKey("500"));

        if (apiResponsesV3 != null) {
            for (Map.Entry<String, Swagger3AnnotationReader.ResponseInfo> e : apiResponsesV3.entrySet()) {
                if (!RESPONSE_200.equals(e.getKey())) {
                    operation.addResponse(e.getKey(),
                            new Response().setDescription(e.getValue().description));
                }
            }
        }
        if (apiResponsesV2 != null) {
            for (Map.Entry<String, Swagger2AnnotationReader.ResponseAnnotation> e : apiResponsesV2.entrySet()) {
                if (!"200".equals(e.getKey())) {
                    operation.addResponse(e.getKey(),
                            new Response().setDescription(e.getValue().description));
                }
            }
        }

        if (method.getType().isVoidType()) {
            if (!has200) {
                operation.addResponse(RESPONSE_200, new Response().setDescription("OK"));
            }
        } else {
            Schema respSchema = typeResolver.resolve(method.getType(), components.getSchemas());
            if (!has200) {
                operation.addResponse(RESPONSE_200, new Response()
                        .setDescription("OK")
                        .addContent(CONTENT_TYPE_JSON, new MediaType().setSchema(respSchema)));
            }
        }
        String errSchema = config.getErrorResponseSchema();
        if (errSchema != null && !errSchema.isEmpty() && !has500) {
            operation.addResponse(RESPONSE_500, new Response()
                    .setDescription("Internal Server Error")
                    .addContent(CONTENT_TYPE_JSON, new MediaType()
                            .setSchema(new Schema().setRef("#/components/schemas/" + errSchema))));
        }

        return operation;
    }

    private boolean isRequestBodyParam(Parameter param) {
        return param.getAnnotations().stream().anyMatch(a -> {
            String n = a.getNameAsString();
            return n.endsWith("RequestBody") || n.equals("RequestBody");
        });
    }

    private io.github.wanlianyida.staticopenapi.model.Parameter buildParameter(
            Parameter param, TypeSchemaResolver typeResolver, Components components) {

        Swagger3AnnotationReader.ParamInfo ann3 = swagger3.readParam(param);
        Swagger2AnnotationReader.ParamAnnotation ann2 = swagger2.readParam(param);

        // 位置: @Parameter(in=...) 显式声明 > Spring 注解推断 > query 兜底
        String in = ann3 != null && !ann3.in.isEmpty()
                ? ann3.in
                : determineParamLocation(param);

        io.github.wanlianyida.staticopenapi.model.Parameter p =
                new io.github.wanlianyida.staticopenapi.model.Parameter()
                        .setName(param.getNameAsString())
                        .setIn(in);

        // path 参数默认必填
        if ("path".equals(in)) p.setRequired(true);

        String desc = merger.mergeDescription(
                ann3 != null ? ann3.description : null,
                ann2 != null ? ann2.value : null,
                javadoc.readParamDescription(param));
        if (!desc.isEmpty()) p.setDescription(desc);
        if (ann2 != null && ann2.required) p.setRequired(true);
        if (ann3 != null && ann3.required) p.setRequired(true);

        Schema schema = typeResolver.resolve(param.getType(), components.getSchemas());
        if (desc.isEmpty()) {
            desc = javadoc.readParamDescription(param);
        }
        if (!desc.isEmpty() && schema.getRef() != null) {
            schema.setDescription(desc);
        }
        p.setSchema(schema);
        return p;
    }

    /** 推断 OpenAPI parameter location: query/path/header */
    private String determineParamLocation(Parameter param) {
        for (var ann : param.getAnnotations()) {
            String n = ann.getNameAsString();
            int dot = n.lastIndexOf('.');
            String sn = dot < 0 ? n : n.substring(dot + 1);
            switch (sn) {
                case "PathVariable": return "path";
                case "RequestHeader": return "header";
                case "RequestParam": return "query";
            }
        }
        return "query";
    }

    private boolean inPackages(ClassOrInterfaceDeclaration cls, List<String> packages) {
        if (packages.isEmpty()) return true;
        String fqn = cls.getFullyQualifiedName().orElse(cls.getNameAsString());
        for (String pkg : packages) {
            // 精确匹配 + 子包匹配 (递归覆盖)
            if (fqn.equals(pkg) || fqn.startsWith(pkg + ".")) return true;
        }
        return false;
    }

    /** 拼接 base + sub, 处理 / 边界 */
    private String joinPath(String base, String sub) {
        String b = base == null ? "" : base.trim();
        String s = sub == null ? "" : sub.trim();
        if (b.isEmpty()) return s.isEmpty() ? "" : (s.startsWith("/") ? s : "/" + s);
        if (s.isEmpty()) return b.startsWith("/") ? b : "/" + b;
        String prefix = b.startsWith("/") ? b : "/" + b;
        String suffix = s.startsWith("/") ? s : "/" + s;
        if (prefix.endsWith("/")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix + suffix;
    }

    /** "AppSignController" → "AppSign" */
    private String stripControllerSuffix(String name) {
        if (name == null) return "";
        return name.endsWith("Controller")
                ? name.substring(0, name.length() - "Controller".length())
                : name;
    }

    /** "List<UserVO>" → "List" */
    private String stripGenerics(String name) {
        int idx = name.indexOf('<');
        return idx < 0 ? name : name.substring(0, idx);
    }

    private Info buildInfo() {
        return new Info()
                .setTitle(config.getProjectName())
                .setDescription("Generated by static-openapi (github.com/wop-platform/static-openapi)")
                .setVersion(config.getApiVersion());
    }

    /** method 唯一标识: name + 参数类型签名. matchesLoosely 支持接口/实现写法差异的兜底匹配. */
    private static final class MethodKey {
        final String name;
        final List<String> paramTypes;

        MethodKey(MethodDeclaration m) {
            this.name = m.getNameAsString();
            this.paramTypes = m.getParameters().stream()
                    .map(p -> p.getType().asString())
                    .collect(Collectors.toList());
        }

        /** 同名同参数个数 (不比较具体类型) */
        boolean matchesLoosely(MethodDeclaration m) {
            return name.equals(m.getNameAsString()) && paramTypes.size() == m.getParameters().size();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof MethodKey)) return false;
            MethodKey k = (MethodKey) o;
            return paramTypes.equals(k.paramTypes) && name.equals(k.name);
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 31 + paramTypes.hashCode();
        }
    }
}
