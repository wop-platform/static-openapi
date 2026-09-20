package io.github.wanlianyida.staticopenapi.reader;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import io.github.wanlianyida.staticopenapi.merge.OpenApiMerger;
import io.github.wanlianyida.staticopenapi.model.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把 Java 类型解析为 OpenAPI Schema.
 *
 * <p>对内置类型 (String/Long/Integer/...) 直接映射 primitive schema; 对自定义类型,
 * 根据简单名在 sourceRoots 下查找 .java 源文件, 用 JavaParser 解析后读取字段 +
 * 注解 + JavaDoc. 支持:
 * <ul>
 *   <li>集合类型 (List/Set/Collection/...) → array + items</li>
 *   <li>包装类型 (ResponseEntity/Optional/Mono/...) → 解包为泛型实参</li>
 *   <li>泛型实例化 → 真实类型变量替换 (ResultModel&lt;UserVO&gt; 的 data:T → UserVO),
 *       缓存 key 带实参 (ResultModel«UserVO»), 不同实参互不污染</li>
 *   <li>枚举 → type:string + enum 值; Record → 组件作为 properties</li>
 *   <li>父类字段沿 extends 链合并进子类 schema</li>
 *   <li>自引用/互引用 DTO: schema 先占位再填充, 递归命中缓存返回 $ref</li>
 * </ul>
 */
public class TypeSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(TypeSchemaResolver.class);

    /** 内置类型 → OpenAPI primitive schema 的映射 */
    private static final Map<String, PrimitiveMapping> PRIMITIVES = new HashMap<>();
    static {
        PRIMITIVES.put("String",        new PrimitiveMapping("string",  null));
        PRIMITIVES.put("CharSequence",  new PrimitiveMapping("string",  null));
        PRIMITIVES.put("Character",     new PrimitiveMapping("string",  null));
        PRIMITIVES.put("char",          new PrimitiveMapping("string",  null));
        PRIMITIVES.put("Byte",          new PrimitiveMapping("string",  null));
        PRIMITIVES.put("byte",          new PrimitiveMapping("string",  null));
        PRIMITIVES.put("Integer",       new PrimitiveMapping("integer", "int32"));
        PRIMITIVES.put("int",           new PrimitiveMapping("integer", "int32"));
        PRIMITIVES.put("Long",          new PrimitiveMapping("integer", "int64"));
        PRIMITIVES.put("long",          new PrimitiveMapping("integer", "int64"));
        PRIMITIVES.put("Short",         new PrimitiveMapping("integer", "int32"));
        PRIMITIVES.put("short",         new PrimitiveMapping("integer", "int32"));
        PRIMITIVES.put("Float",         new PrimitiveMapping("number",  "float"));
        PRIMITIVES.put("float",         new PrimitiveMapping("number",  "float"));
        PRIMITIVES.put("Double",        new PrimitiveMapping("number",  "double"));
        PRIMITIVES.put("double",        new PrimitiveMapping("number",  "double"));
        PRIMITIVES.put("BigDecimal",    new PrimitiveMapping("number",  null));
        PRIMITIVES.put("BigInteger",    new PrimitiveMapping("integer", null));
        PRIMITIVES.put("Boolean",       new PrimitiveMapping("boolean", null));
        PRIMITIVES.put("boolean",       new PrimitiveMapping("boolean", null));
        PRIMITIVES.put("LocalDate",     new PrimitiveMapping("string",  "date"));
        PRIMITIVES.put("LocalDateTime", new PrimitiveMapping("string",  "date-time"));
        PRIMITIVES.put("LocalTime",     new PrimitiveMapping("string",  "time"));
        PRIMITIVES.put("Date",          new PrimitiveMapping("string",  "date-time"));
        PRIMITIVES.put("Object",        new PrimitiveMapping("object",  null));
        PRIMITIVES.put("JSONObject",    new PrimitiveMapping("object",  null));
        PRIMITIVES.put("Map",           new PrimitiveMapping("object",  null));
    }

    /** 单泛型包装类型: 响应 schema 直接解包为泛型实参本身 */
    private static final Set<String> WRAPPER_TYPES = new HashSet<>(Arrays.asList(
            "Optional", "ResponseEntity", "HttpEntity", "RequestEntity",
            "Mono", "Flux", "CompletableFuture", "CompletionStage"));

    /** 集合类型: 解析为 array + items */
    private static final Set<String> COLLECTION_TYPES = new HashSet<>(Arrays.asList(
            "Collection", "List", "ArrayList", "LinkedList", "Set", "HashSet",
            "LinkedHashSet", "TreeSet", "SortedSet", "Iterable", "Queue", "Deque",
            "Stack", "Vector"));

    /** Map 类型: 解析为 object + additionalProperties (值类型取第二个泛型实参) */
    private static final Set<String> MAP_TYPES = new HashSet<>(Arrays.asList(
            "Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap",
            "NavigableMap", "ConcurrentMap", "ConcurrentHashMap", "Hashtable"));

    /** 二进制包装: byte[]/Byte[] → string + format byte */
    private static final Set<String> BINARY_TYPES = new HashSet<>(Arrays.asList("byte", "Byte"));

    private final SourceParser sourceParser;
    private final Swagger2AnnotationReader swagger2 = new Swagger2AnnotationReader();
    private final Swagger3AnnotationReader swagger3 = new Swagger3AnnotationReader();
    private final JavaDocReader javadoc = new JavaDocReader();

    /** 泛型实例化 schema 命名风格: pascal = Apifox 风格拼接 (PagingInfoX), false = 书名号 (PagingInfo«X») */
    private final boolean pascalNaming;

    private final List<Path> sourceRoots;
    private final Map<String, Path> sourceBySimpleName = new HashMap<>();
    /**
     * 已解析 schema 缓存. key: 类简单名 或 泛型实例化名 (ResultModel«UserVO» / ResultUserVO).
     * 解析前先放入占位对象, 保证自引用类型命中缓存返回 $ref.
     */
    private final Map<String, Schema> schemaCache = new HashMap<>();

    public TypeSchemaResolver(List<Path> sourceRoots) {
        this(sourceRoots, "guillemet");
    }

    /**
     * @param schemaNameStyle 泛型实例化 schema 命名风格:
     *                        "guillemet" (默认, PagingInfo«X») / "pascal" (PagingInfoX, Apifox 风格),
     *                        大小写不敏感; 其他值告警并回退 guillemet
     */
    public TypeSchemaResolver(List<Path> sourceRoots, String schemaNameStyle) {
        this(sourceRoots, schemaNameStyle, new SourceParser());
    }

    public TypeSchemaResolver(List<Path> sourceRoots, String schemaNameStyle, SourceParser sourceParser) {
        this.sourceRoots = sourceRoots;
        this.sourceParser = sourceParser;
        if ("pascal".equalsIgnoreCase(schemaNameStyle)) {
            this.pascalNaming = true;
        } else {
            if (schemaNameStyle != null && !schemaNameStyle.isBlank()
                    && !"guillemet".equalsIgnoreCase(schemaNameStyle)) {
                log.warn("Unknown schemaNameStyle '{}', falling back to 'guillemet' (supported: guillemet, pascal)",
                        schemaNameStyle);
            }
            this.pascalNaming = false;
        }
        indexSources();
    }

    private void indexSources() {
        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(root)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
            } catch (IOException ignore) {
                // skip
            }
            // 排序保证多模块/多文件时索引顺序确定 (同名简单类先到先得)
            files.sort(Path::compareTo);
            for (Path p : files) {
                String simple = stripExtension(p.getFileName().toString());
                sourceBySimpleName.putIfAbsent(simple, p);
            }
        }
    }

    /** 全工程 sourceRoots 的 simple-name → path 索引 (供 OpenApiGenerator 解析 controller interface 用) */
    public Map<String, Path> getSourceBySimpleName() {
        return sourceBySimpleName;
    }

    private static String stripExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? filename : filename.substring(0, idx);
    }

    /** 解析 Java 类型为 Schema (可能含 $ref), 不带泛型形参绑定. */
    public Schema resolve(Type type, Map<String, Schema> schemaCollector) {
        return resolve(type, schemaCollector, Collections.emptyMap());
    }

    /**
     * 解析 Java 类型为 Schema (可能含 $ref).
     *
     * @param typeVars 当前类的泛型形参 → 实参类型字符串 (如 T → UserVO)
     * @param schemaCollector 用于收集 components.schemas 的容器
     */
    public Schema resolve(Type type, Map<String, Schema> schemaCollector, Map<String, String> typeVars) {
        // 1. 基本类型
        if (type.isPrimitiveType()) {
            PrimitiveType pt = type.asPrimitiveType();
            PrimitiveMapping m = PRIMITIVES.get(pt.asString());
            if (m != null) return new Schema().setType(m.type).setFormat(m.format);
        }

        // 2. 数组 (T[]); byte[]/Byte[] 按惯例映射 string + format byte
        if (type instanceof ArrayType) {
            Type component = ((ArrayType) type).getComponentType();
            if (isBinaryComponent(component)) {
                return new Schema().setType("string").setFormat("byte");
            }
            Schema items = resolve(component, schemaCollector, typeVars);
            return new Schema().setType("array").setItems(items);
        }

        // 3. 类/接口类型
        if (type instanceof ClassOrInterfaceType) {
            return resolveClassType((ClassOrInterfaceType) type, schemaCollector, typeVars);
        }

        // 4. 通配符 / 其他: 兜底
        String typeName = type.asString();
        return typeName == null || typeName.isEmpty()
                ? new Schema().setType("object")
                : resolveTypeString(typeName, schemaCollector, typeVars);
    }

    /** primitive schema 的 (type, format) 配对之外的 binary 判断: byte/Byte 的数组分量 */
    private static boolean isBinaryComponent(Type component) {
        String name = component instanceof PrimitiveType
                ? component.asPrimitiveType().asString()
                : component instanceof ClassOrInterfaceType
                        ? shortName(component.asClassOrInterfaceType().getNameAsString())
                        : null;
        return name != null && BINARY_TYPES.contains(name);
    }

    // ================= 类型入口 =================

    private Schema resolveClassType(ClassOrInterfaceType cit, Map<String, Schema> collector,
                                    Map<String, String> typeVars) {
        String rawName = shortName(cit.getNameAsString());
        List<String> typeArgs = new ArrayList<>();
        NodeList<Type> argList = cit.getTypeArguments().orElse(null);
        if (argList != null) {
            for (Type ta : argList) typeArgs.add(argTypeString(ta, typeVars));
        }
        return resolveNamedType(rawName, typeArgs, collector, typeVars);
    }

    /** 泛型实参字符串: 通配符取上界, 类型变量按 typeVars 替换 */
    private String argTypeString(Type ta, Map<String, String> typeVars) {
        if (ta.isWildcardType()) {
            return ta.asWildcardType().getExtendedType()
                    .map(Type::asString)
                    .orElse("Object");
        }
        return substituteTypeVars(ta.asString().trim(), typeVars);
    }

    /** "T" → 实参; "List<T>" → "List<Foo>"; 无绑定的变量原样返回 */
    private String substituteTypeVars(String typeStr, Map<String, String> typeVars) {
        if (typeVars.isEmpty()) return typeStr;
        String root = stripGenerics(typeStr);
        String mapped = typeVars.get(root);
        if (mapped != null) {
            // 覆盖 T / T[] 两种形态
            return mapped + typeStr.substring(root.length());
        }
        int lt = typeStr.indexOf('<');
        if (lt < 0 || !typeStr.endsWith(">")) return typeStr;
        String raw = typeStr.substring(0, lt);
        String mappedRaw = typeVars.containsKey(stripGenerics(raw)) ? typeVars.get(stripGenerics(raw)) : raw;
        List<String> parts = splitTopLevel(typeStr.substring(lt + 1, typeStr.length() - 1));
        StringBuilder sb = new StringBuilder(mappedRaw).append('<');
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(substituteTypeVars(parts.get(i).trim(), typeVars));
        }
        return sb.append('>').toString();
    }

    /** 字符串类型入口 (供泛型替换结果 / 包装类型解包复用) */
    private Schema resolveTypeString(String typeStr, Map<String, Schema> collector,
                                     Map<String, String> typeVars) {
        if (typeStr == null || typeStr.trim().isEmpty()) return new Schema().setType("object");
        String s = typeStr.trim();
        if (s.equals("byte[]") || s.equals("Byte[]")) {
            return new Schema().setType("string").setFormat("byte");
        }
        if (s.endsWith("[]")) {
            return new Schema().setType("array")
                    .setItems(resolveTypeString(s.substring(0, s.length() - 2), collector, typeVars));
        }
        int lt = s.indexOf('<');
        if (lt > 0 && s.endsWith(">")) {
            String rawName = shortName(s.substring(0, lt));
            List<String> args = splitTopLevel(s.substring(lt + 1, s.length() - 1)).stream()
                    .map(String::trim)
                    .collect(Collectors.toList());
            return resolveNamedType(rawName, args, collector, typeVars);
        }
        return resolveSimple(stripGenerics(s), collector);
    }

    private Schema resolveNamedType(String rawName, List<String> typeArgs,
                                    Map<String, Schema> collector, Map<String, String> typeVars) {
        // 1. 集合 → array + items
        if (COLLECTION_TYPES.contains(rawName)) {
            Schema items = typeArgs.isEmpty()
                    ? new Schema().setType("object")
                    : resolveTypeString(typeArgs.get(0), collector, typeVars);
            return new Schema().setType("array").setItems(items);
        }
        // 2. 包装类型 → 解包为泛型实参
        if (!typeArgs.isEmpty() && WRAPPER_TYPES.contains(rawName)) {
            return resolveTypeString(typeArgs.get(0), collector, typeVars);
        }
        // 3. Map → object + additionalProperties (JDK 类无源码, 不走泛型实例化)
        if (MAP_TYPES.contains(rawName)) {
            Schema mapSchema = new Schema().setType("object");
            if (typeArgs.size() >= 2) {
                mapSchema.setAdditionalProperties(
                        resolveTypeString(typeArgs.get(1), collector, typeVars));
            }
            return mapSchema;
        }
        // 4. 泛型实例化 → 真实替换形参 (ResultModel<UserVO>)
        if (!typeArgs.isEmpty()) {
            return resolveGenericInstantiation(rawName, typeArgs, collector);
        }
        // 5. 绑定的类型变量 (controller 泛型方法返回 T 等)
        if (typeVars.containsKey(rawName)) {
            return resolveTypeString(typeVars.get(rawName), collector, typeVars);
        }
        return resolveSimple(rawName, collector);
    }

    /**
     * 解析泛型实例化: 解析原始类源码, 形参用实参替换.
     * 缓存 key 带实参 (ResultModel«UserVO»), 同一原始类的不同实例化互不污染.
     */
    private Schema resolveGenericInstantiation(String rawName, List<String> typeArgs,
                                               Map<String, Schema> collector) {
        Path sourceFile = sourceBySimpleName.get(rawName);
        if (sourceFile == null) {
            return buildFallbackSchema(rawName, typeArgs, collector);
        }

        String key = genericKey(rawName, typeArgs);
        Schema cached = schemaCache.get(key);
        if (cached != null) return refTo(cached);

        try {
            CompilationUnit unit = sourceParser.parse(sourceFile);
            if (unit != null) {
                for (TypeDeclaration<?> td : unit.getTypes()) {
                    if (td instanceof ClassOrInterfaceDeclaration && td.getNameAsString().equals(rawName)) {
                        ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) td;
                        Map<String, String> typeVars = bindTypeVars(cls, typeArgs);
                        Schema schema = new Schema().setName(uniqueSchemaName(key, collector)).setType("object");
                        // 先占位再填充: 字段引用自身/原始类时命中缓存, 不会无限递归
                        schemaCache.put(key, schema);
                        collector.put(schema.getName(), schema);
                        populateSchemaFromClass(cls, schema, collector, typeVars, new HashSet<>());
                        return refTo(schema);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", sourceFile, e.getMessage());
        }
        return buildFallbackSchema(rawName, typeArgs, collector);
    }

    /**
     * 当泛型类源码不可用时, 尝试用泛型实参构建 fallback schema.
     * 对于 ResultModel/Result 等包装类型, 保留 model 字段.
     * 对于 PagingInfo 等包装类型, 保留 filterModel/list 字段.
     */
    private Schema buildFallbackSchema(String rawName, List<String> typeArgs,
                                        Map<String, Schema> collector) {
        if (typeArgs.isEmpty()) return new Schema().setType("object");

        String key = genericKey(rawName, typeArgs);
        Schema cached = schemaCache.get(key);
        if (cached != null) return refTo(cached);

        Schema schema = new Schema().setName(key).setType("object");
        schemaCache.put(key, schema);
        collector.put(key, schema);

        Schema itemSchema = resolveTypeString(typeArgs.get(0), collector, Collections.emptyMap());

        if (isLikelyResultWrapper(rawName)) {
            schema.addProperty("succeed", new Schema().setType("boolean"));
            schema.addProperty("code", new Schema().setType("string"));
            schema.addProperty("message", new Schema().setType("string"));
            schema.addProperty("model", new Schema().setType("array").setItems(itemSchema));
            schema.addProperty("total", new Schema().setType("integer").setFormat("int64"));
        } else if (isLikelyPagingWrapper(rawName)) {
            schema.addProperty("filterModel", itemSchema);
            schema.addProperty("countTotal", new Schema().setType("boolean"));
            schema.addProperty("currentPage", new Schema().setType("integer"));
            schema.addProperty("pageLength", new Schema().setType("integer"));
            schema.addProperty("sort", new Schema().setType("string"));
        } else {
            schema.addProperty("value", itemSchema);
        }
        return refTo(schema);
    }

    private boolean isLikelyResultWrapper(String name) {
        String n = name.toLowerCase();
        // 不含 "model": UserModel 等普通业务类名不应被误判为包装类型
        return (n.contains("result") || n.contains("response"))
                && !n.contains("page") && !n.contains("paging");
    }

    private boolean isLikelyPagingWrapper(String name) {
        String n = name.toLowerCase();
        return n.contains("paging") || n.contains("pageinfo");
    }

    private String genericKey(String rawName, List<String> typeArgs) {
        if (pascalNaming) {
            // Apifox 风格: 原始类名 + 实参递归压平拼接, PagingInfo<List<UserVO>> → PagingInfoListUserVO
            StringBuilder sb = new StringBuilder(rawName);
            for (String arg : typeArgs) sb.append(flattenTypeArg(arg));
            return sb.toString();
        }
        return rawName + "«" + String.join(",", typeArgs) + "»";
    }

    /** pascal 风格的类型名压平: "List<UserVO>" → "ListUserVO"; "UserVO[]" → "UserVOArray" */
    private static String flattenTypeArg(String typeStr) {
        String s = typeStr.trim();
        if (s.endsWith("[]")) {
            return flattenTypeArg(s.substring(0, s.length() - 2)) + "Array";
        }
        int lt = s.indexOf('<');
        if (lt > 0 && s.endsWith(">")) {
            StringBuilder sb = new StringBuilder(s.substring(0, lt));
            for (String arg : splitTopLevel(s.substring(lt + 1, s.length() - 1))) {
                sb.append(flattenTypeArg(arg.trim()));
            }
            return sb.toString();
        }
        return s;
    }

    /** 类的泛型形参名 zip 实参列表: <T, R> + [UserVO, OrderVO] → {T=UserVO, R=OrderVO} */
    private Map<String, String> bindTypeVars(ClassOrInterfaceDeclaration cls, List<String> typeArgs) {
        Map<String, String> map = new LinkedHashMap<>();
        NodeList<com.github.javaparser.ast.type.TypeParameter> params = cls.getTypeParameters();
        for (int i = 0; i < params.size() && i < typeArgs.size(); i++) {
            map.put(params.get(i).getNameAsString(), typeArgs.get(i));
        }
        return map;
    }

    /**
     * 解析非泛型类型.
     * - 基本类型 → primitive schema
     * - 找不到源文件 → object (兜底)
     * - 已解析过 → 返回 $ref
     * - 枚举 → string + enum; Record → 组件为 properties
     */
    private Schema resolveSimple(String typeName, Map<String, Schema> schemaCollector) {
        String simpleName = stripGenerics(typeName);

        // 1. primitive
        PrimitiveMapping m = PRIMITIVES.get(simpleName);
        if (m != null) return new Schema().setType(m.type).setFormat(m.format);

        // 2. 找不到源文件 (可能是 jar 里的类, 或泛型类型变量)
        Path sourceFile = sourceBySimpleName.get(simpleName);
        if (sourceFile == null) return new Schema().setType("object");

        // 3. cache
        Schema cached = schemaCache.get(simpleName);
        if (cached != null) return refTo(cached);

        // 4. 按声明类型解析
        try {
            CompilationUnit unit = sourceParser.parse(sourceFile);
            if (unit != null) {
                for (TypeDeclaration<?> td : unit.getTypes()) {
                    if (td.getNameAsString().equals(simpleName)) {
                        return declareSchema(td, simpleName, schemaCollector);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", sourceFile, e.getMessage());
        }
        return new Schema().setType("object");
    }

    /** 供 errorResponseSchema 等按名字补齐 components 的场景使用 */
    public Schema resolveSchemaByName(String schemaName, Map<String, Schema> schemaCollector) {
        if (schemaName == null || schemaName.isEmpty()) return new Schema().setType("object");
        return resolveSimple(schemaName, schemaCollector);
    }

    private Schema declareSchema(TypeDeclaration<?> td, String simpleName, Map<String, Schema> collector) {
        String effectiveName = schemaNameOverride(td, simpleName);
        // 重名冲突唯一化 (如 @Schema(name="X") 与另一个类简单名撞名):
        // 不覆盖既有 schema, 否则已生成的 $ref 会指向错误内容
        if (collector.containsKey(effectiveName)) {
            String renamed = uniqueSchemaName(effectiveName, collector);
            log.warn("Duplicate schema name '{}' from class '{}', renamed to '{}'",
                    effectiveName, simpleName, renamed);
            effectiveName = renamed;
        }
        Schema schema = new Schema().setName(effectiveName).setType("object");
        // 先占位再填充: 自引用/互引用 DTO 命中缓存返回 $ref, 不会 StackOverflow
        schemaCache.put(simpleName, schema);
        collector.put(effectiveName, schema);

        if (td instanceof EnumDeclaration) {
            populateEnum((EnumDeclaration) td, schema);
        } else if (td instanceof RecordDeclaration) {
            populateRecord((RecordDeclaration) td, schema, collector);
        } else if (td instanceof ClassOrInterfaceDeclaration) {
            populateSchemaFromClass((ClassOrInterfaceDeclaration) td, schema, collector,
                    Collections.emptyMap(), new HashSet<>());
        }
        return refTo(schema);
    }

    /** @Schema(name="X") / @ApiModel(value="X") 的 schema 重命名 */
    private String schemaNameOverride(TypeDeclaration<?> td, String fallback) {
        if (td instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration cls = (ClassOrInterfaceDeclaration) td;
            Swagger3AnnotationReader.SchemaInfo m3 = swagger3.readModel(cls);
            if (m3 != null && !m3.name.isEmpty()) return m3.name;
            Swagger2AnnotationReader.ModelAnnotation m2 = swagger2.readModel(cls);
            if (m2 != null && !m2.value.isEmpty()) return m2.value;
        }
        return fallback;
    }

    /** 枚举 → {"type":"string","enum":[...]} */
    private void populateEnum(EnumDeclaration ed, Schema schema) {
        schema.setType("string");
        for (EnumConstantDeclaration entry : ed.getEntries()) {
            schema.addEnumValue(entry.getNameAsString());
        }
        String desc = javadoc.readTypeDescription(ed);
        if (!desc.isEmpty()) schema.setDescription(desc);
    }

    /** Record → 组件作为 properties */
    private void populateRecord(RecordDeclaration rd, Schema schema, Map<String, Schema> collector) {
        String desc = javadoc.readTypeDescription(rd);
        if (!desc.isEmpty()) schema.setDescription(desc);
        for (Parameter comp : rd.getParameters()) {
            Schema propSchema = resolve(comp.getType(), collector);
            schema.addProperty(comp.getNameAsString(), propSchema);
        }
    }

    /** collector 内唯一化 schema 名: 撞名时追加 _2/_3… (引用侧以 schema.getName() 为准, 保持一致) */
    private static String uniqueSchemaName(String desired, Map<String, Schema> collector) {
        String name = desired;
        int i = 2;
        while (collector.containsKey(name)) {
            name = desired + "_" + i++;
        }
        return name;
    }

    /**
     * 类 → schema properties. 沿 extends 链递归合并父类字段 (父类在前),
     * 字段类型解析时用 typeVars 替换泛型形参.
     */
    private void populateSchemaFromClass(ClassOrInterfaceDeclaration cls, Schema schema,
                                         Map<String, Schema> schemaCollector,
                                         Map<String, String> typeVars, Set<String> visited) {
        if (!visited.add(cls.getNameAsString())) return;

        // 1. 父类字段先合并 (父类在前, 子类同名字段覆盖)
        for (ClassOrInterfaceType parentType : cls.getExtendedTypes()) {
            String parentName = stripGenerics(shortName(parentType.getNameAsString()));
            if (visited.contains(parentName)) continue;
            Path parentFile = sourceBySimpleName.get(parentName);
            if (parentFile == null) continue;
            CompilationUnit pUnit = sourceParser.parse(parentFile);
            if (pUnit == null) continue;
            for (TypeDeclaration<?> pTd : pUnit.getTypes()) {
                if (pTd instanceof ClassOrInterfaceDeclaration && pTd.getNameAsString().equals(parentName)) {
                    // 父类泛形参不做跨类替换 (父类字段里的 T 解析为 object 兜底)
                    populateSchemaFromClass((ClassOrInterfaceDeclaration) pTd, schema,
                            schemaCollector, Collections.emptyMap(), visited);
                }
            }
        }

        // 2. class 级别 description (注解 > JavaDoc, 首个非空)
        Swagger3AnnotationReader.SchemaInfo m3 = swagger3.readModel(cls);
        Swagger2AnnotationReader.ModelAnnotation m2 = swagger2.readModel(cls);
        String classDesc = OpenApiMerger.firstNonEmpty(
                m3 != null ? m3.description : null,
                m2 != null ? m2.description : null,
                javadoc.readTypeDescription(cls));
        if (!classDesc.isEmpty()) schema.setDescription(classDesc);

        // 3. 遍历字段 (跳过 static / hidden; 一行多变量逐一处理)
        for (FieldDeclaration field : cls.getFields()) {
            if (field.isStatic()) continue;
            if (AnnotationUtils.isHidden(field)) continue;

            Swagger3AnnotationReader.PropertyInfo p3 = swagger3.readProperty(field);
            Swagger2AnnotationReader.PropertyAnnotation p2 = swagger2.readProperty(field);
            if ((p3 != null && p3.hidden) || (p2 != null && p2.hidden)) continue;

            // 描述/示例/重命名: swagger3 > swagger2 > JavaDoc
            String propDesc = OpenApiMerger.firstNonEmpty(
                    p3 != null ? p3.description : null,
                    p2 != null ? p2.value : null,
                    p2 != null ? p2.notes : null,
                    javadoc.readFieldDescription(field));
            String example = OpenApiMerger.firstNonEmpty(
                    p3 != null ? p3.example : null,
                    p2 != null ? p2.example : null);

            boolean required = (p2 != null && p2.required) || (p3 != null && p3.required);

            for (VariableDeclarator var : field.getVariables()) {
                // 属性重命名: @Schema(name) / @ApiModelProperty(name) > Java 字段名
                String key = OpenApiMerger.firstNonEmpty(
                        p3 != null ? p3.name : null,
                        p2 != null ? p2.name : null,
                        var.getNameAsString());
                Schema propSchema = resolve(var.getType(), schemaCollector, typeVars);
                if (!propDesc.isEmpty()) propSchema.setDescription(propDesc);
                if (!example.isEmpty()) propSchema.setExample(example);
                if (required) schema.addRequired(key);
                schema.addProperty(key, propSchema);
            }
        }
    }

    private Schema refTo(String schemaName) {
        return new Schema().setRef("#/components/schemas/" + schemaName);
    }

    private Schema refTo(Schema original) {
        Schema ref = new Schema().setRef("#/components/schemas/" + original.getName());
        if (original.getDescription() != null) {
            ref.setDescription(original.getDescription());
        }
        if (original.getExample() != null) {
            ref.setExample(original.getExample());
        }
        return ref;
    }

    /** Foo<Bar<X>> → Foo */
    private static String stripGenerics(String typeName) {
        int idx = typeName.indexOf('<');
        return idx < 0 ? typeName : typeName.substring(0, idx);
    }

    /** "java.util.List" → "List" */
    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /** 按顶层逗号拆分泛型实参 ("String, List<Foo>" → ["String", "List<Foo>"]) */
    private static List<String> splitTopLevel(String args) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : args.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** primitive schema 的 (type, format) 配对 */
    private static class PrimitiveMapping {
        final String type;
        final String format;
        PrimitiveMapping(String type, String format) {
            this.type = type;
            this.format = format;
        }
    }
}
