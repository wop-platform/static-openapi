package io.github.wanlianyida.staticopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端集成测试: 在临时目录生成一个最小 Spring 工程, 跑完整生成流程, 断言输出的 openapi.json.
 */
class OpenApiGeneratorTest {

    @TempDir
    Path tempDir;

    private JsonNode doc;

    private static String src(String... lines) {
        return String.join("\n", lines) + "\n";
    }

    @BeforeEach
    void generateSpec() throws Exception {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir.resolve("controller"));
        Files.createDirectories(srcDir.resolve("model"));

        write(srcDir, "model/BaseVO.java", src(
                "package com.example.model;",
                "import io.swagger.v3.oas.annotations.media.Schema;",
                "/** 基础 VO */",
                "public class BaseVO {",
                "    @Schema(description = \"主键\")",
                "    private Long id;",
                "}"));
        write(srcDir, "model/Gender.java", src(
                "package com.example.model;",
                "/** 性别 */",
                "public enum Gender { MALE, FEMALE }"));
        write(srcDir, "model/UserVO.java", src(
                "package com.example.model;",
                "import io.swagger.v3.oas.annotations.media.Schema;",
                "/** 用户 VO */",
                "@Schema(name = \"CustomUser\", description = \"schema desc\")",
                "public class UserVO extends BaseVO {",
                "    @Schema(description = \"性别描述\")",
                "    private Gender gender;",
                "    @Schema(description = \"标签\")",
                "    private java.util.List<String> tags;",
                "}"));
        write(srcDir, "model/UserReq.java", src(
                "package com.example.model;",
                "import io.swagger.v3.oas.annotations.media.Schema;",
                "/** 创建请求 */",
                "public class UserReq {",
                "    @Schema(description = \"用户名\", requiredMode = Schema.RequiredMode.REQUIRED)",
                "    private String name;",
                "}"));
        write(srcDir, "model/Result.java", src(
                "package com.example.model;",
                "/** 包装 */",
                "public class Result<T> {",
                "    private String code;",
                "    private T data;",
                "}"));
        write(srcDir, "model/TreeNode.java", src(
                "package com.example.model;",
                "/** 树节点 */",
                "public class TreeNode {",
                "    private TreeNode parent;",
                "    private java.util.List<TreeNode> children;",
                "}"));
        write(srcDir, "controller/UserController.java", src(
                "package com.example.controller;",
                "import com.example.model.*;",
                "import io.swagger.annotations.Api;",
                "import io.swagger.annotations.ApiOperation;",
                "import io.swagger.v3.oas.annotations.Operation;",
                "import io.swagger.v3.oas.annotations.Hidden;",
                "import io.swagger.v3.oas.annotations.tags.Tag;",
                "import org.springframework.web.bind.annotation.*;",
                "import java.util.List;",
                "",
                "/** 用户管理 javadoc */",
                "@Api(tags = \"user-api\", description = \"v2 desc\")",
                "@Tag(name = \"user-api\", description = \"v3 desc\")",
                "@RestController",
                "@RequestMapping(\"/api/users\")",
                "public class UserController {",
                "",
                "    /** javadoc 摘要 */",
                "    @ApiOperation(value = \"v2 summary\", notes = \"v2 notes\")",
                "    @Operation(summary = \"v3 summary\", description = \"v3 desc\", operationId = \"customId\", deprecated = true)",
                "    @Deprecated",
                "    @GetMapping(\"/{id}\")",
                "    public Result<UserVO> get(@PathVariable(\"id\") Long id) { return null; }",
                "",
                "    /** 创建用户 */",
                "    @PostMapping",
                "    public UserVO create(@RequestBody UserReq req) { return null; }",
                "",
                "    /** 列表 */",
                "    @GetMapping(\"/list\")",
                "    public List<UserVO> list() { return null; }",
                "",
                "    /** 树 */",
                "    @GetMapping(\"/tree\")",
                "    public TreeNode tree() { return null; }",
                "",
                "    /** 隐藏接口 */",
                "    @Hidden",
                "    @GetMapping(\"/secret\")",
                "    public String secret() { return null; }",
                "",
                "    /** 多路径 */",
                "    @GetMapping({\"/a\", \"/b\"})",
                "    public String multi() { return null; }",
                "}"));

        GeneratorConfig config = new GeneratorConfig()
                .setProjectName("test-api")
                .setProjectDir(tempDir.toString())
                .setOutPath(tempDir.resolve("out").toString())
                .setPackages(List.of("com.example.controller"));

        Path output = new OpenApiGenerator(config).generate();
        doc = new ObjectMapper().readTree(Files.readAllBytes(output));
    }

    private static void write(Path srcDir, String rel, String content) throws Exception {
        Files.write(srcDir.resolve(rel), content.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode op(String path, String method) {
        return doc.at("/paths").get(path).get(method);
    }

    @Test
    void summaryPrioritySwagger3OverSwagger2AndJavadoc() {
        assertEquals("v3 summary", op("/api/users/{id}", "get").get("summary").asText());
    }

    @Test
    void descriptionPrioritySwagger3OverSwagger2AndJavadoc() {
        assertEquals("v3 desc", op("/api/users/{id}", "get").get("description").asText());
    }

    @Test
    void tagDescriptionPrefersSwagger3AndNoDuplicateTags() {
        JsonNode tags = doc.get("tags");
        assertEquals(1, tags.size());
        assertEquals("v3 desc", tags.get(0).get("description").asText());

        JsonNode opTags = op("/api/users/{id}", "get").get("tags");
        assertEquals(1, opTags.size());
        assertEquals("user-api", opTags.get(0).asText());
    }

    @Test
    void operationIdFromAnnotation() {
        assertEquals("customId", op("/api/users/{id}", "get").get("operationId").asText());
    }

    @Test
    void deprecatedFromSwaggerOrJava() {
        assertTrue(op("/api/users/{id}", "get").get("deprecated").asBoolean());
    }

    @Test
    void hiddenMethodExcluded() {
        assertNull(doc.at("/paths").get("/api/users/secret"));
    }

    @Test
    void requestBodyParamNotDuplicatedAsQueryParameter() {
        JsonNode post = op("/api/users", "post");
        assertFalse(post.has("parameters"));
        assertNotNull(post.get("requestBody"));
        assertEquals("#/components/schemas/UserReq",
                post.at("/requestBody/content/application~1json/schema/$ref").asText());
    }

    @Test
    void listReturnTypeBecomesArrayWithItemsRef() {
        JsonNode schema = op("/api/users/list", "get")
                .at("/responses/200/content/application~1json/schema");
        assertEquals("array", schema.get("type").asText());
        assertEquals("#/components/schemas/CustomUser",
                schema.at("/items/$ref").asText());
    }

    @Test
    void genericWrapperInstantiatedWithTypeSubstitution() {
        JsonNode result = doc.at("/components/schemas/Result«UserVO»");
        assertNotNull(result);
        assertEquals("#/components/schemas/CustomUser",
                result.at("/properties/data/$ref").asText());
        assertTrue(result.at("/properties").has("code"));
    }

    @Test
    void selfReferencingDtoDoesNotOverflowAndUsesRef() {
        JsonNode tree = doc.at("/components/schemas/TreeNode");
        assertNotNull(tree);
        assertEquals("#/components/schemas/TreeNode",
                tree.at("/properties/parent/$ref").asText());
        assertEquals("#/components/schemas/TreeNode",
                tree.at("/properties/children/items/$ref").asText());
    }

    @Test
    void enumSchemaWithTypeStringAndValues() {
        JsonNode gender = doc.at("/components/schemas/Gender");
        assertEquals("string", gender.get("type").asText());
        assertEquals("MALE", gender.get("enum").get(0).asText());
        assertEquals("FEMALE", gender.get("enum").get(1).asText());
    }

    @Test
    void parentClassFieldsMerged() {
        JsonNode user = doc.at("/components/schemas/CustomUser");
        assertTrue(user.at("/properties").has("id"));
        assertEquals("主键", user.at("/properties/id/description").asText());
    }

    @Test
    void schemaNameOverride() {
        assertTrue(doc.at("/components/schemas").has("CustomUser"));
        assertFalse(doc.at("/components/schemas").has("UserVO"));
    }

    @Test
    void refSiblingDescriptionKept() {
        JsonNode genderProp = doc.at("/components/schemas/CustomUser/properties/gender");
        assertEquals("#/components/schemas/Gender", genderProp.get("$ref").asText());
        assertEquals("性别描述", genderProp.get("description").asText());
    }

    @Test
    void requiredModeEnumReferenceDetected() {
        JsonNode req = doc.at("/components/schemas/UserReq");
        assertEquals("name", req.get("required").get(0).asText());
    }

    @Test
    void listOfStringFieldBecomesArray() {
        JsonNode tags = doc.at("/components/schemas/CustomUser/properties/tags");
        assertEquals("array", tags.get("type").asText());
        assertEquals("string", tags.at("/items/type").asText());
    }

    @Test
    void multiplePathsExpanded() {
        assertNotNull(doc.at("/paths").get("/api/users/a"));
        assertNotNull(doc.at("/paths").get("/api/users/b"));
    }

    @Test
    void pathParameterRequired() {
        JsonNode param = op("/api/users/{id}", "get").get("parameters").get(0);
        assertEquals("path", param.get("in").asText());
        assertTrue(param.get("required").asBoolean());
    }

    @Test
    void openapiVersionIs31() {
        assertEquals("3.1.0", doc.get("openapi").asText());
    }

    @Test
    void multiModuleSourceRootsDiscovered() throws Exception {
        // 模拟多模块工程: <tempDir>/multi/{module-a,module-b}/src/main/java
        // controller 在 module-a, DTO 在 module-b — 配置只给包路径, 不给模块路径
        Path multi = tempDir.resolve("multi");
        Path modA = multi.resolve("module-a/src/main/java/com/example");
        Path modB = multi.resolve("module-b/src/main/java/com/example");
        Files.createDirectories(modA.resolve("controller"));
        Files.createDirectories(modB.resolve("model"));
        // 干扰项: 构建/测试目录不应被当作源码根
        Files.createDirectories(multi.resolve("module-a/target/classes"));
        Files.createDirectories(multi.resolve("module-a/src/test/java/com/example"));

        Files.write(modB.resolve("model/OrderDTO.java"), src(
                "package com.example.model;",
                "/** 订单 */",
                "public class OrderDTO {",
                "    private String orderNo;",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(modA.resolve("controller/OrderController.java"), src(
                "package com.example.controller;",
                "import com.example.model.OrderDTO;",
                "import org.springframework.web.bind.annotation.*;",
                "/** 订单接口 */",
                "@RestController",
                "@RequestMapping(\"/orders\")",
                "public class OrderController {",
                "    /** 查订单 */",
                "    @GetMapping(\"/{id}\")",
                "    public OrderDTO get(@PathVariable(\"id\") String id) { return null; }",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        GeneratorConfig config = new GeneratorConfig()
                .setProjectName("multi-module")
                .setProjectDir(multi.toString())
                .setOutPath(tempDir.resolve("out-multi").toString())
                .setPackages(List.of("com.example.controller"));
        JsonNode multiDoc = new ObjectMapper().readTree(
                Files.readAllBytes(new OpenApiGenerator(config).generate()));

        // 跨模块: controller 在 module-a, DTO 在 module-b, 均被扫描并正确解析
        // 注意: 路径 key "/orders/{id}" 中的斜杠在 JSON Pointer 里要转义为 ~1
        assertNotNull(multiDoc.at("/paths").get("/orders/{id}"));
        assertEquals("#/components/schemas/OrderDTO",
                multiDoc.at("/paths/~1orders~1{id}/get/responses/200/content/application~1json/schema/$ref").asText());
        assertTrue(multiDoc.at("/components/schemas/OrderDTO/properties").has("orderNo"));
    }

    @Test
    void pascalSchemaNameStyleFlattensGenerics() throws Exception {
        // Apifox 风格命名: PagingInfo<UserVO> → "PagingInfoUserVO" (嵌套与数组递归压平, 无非法字符)
        Path pascalDir = tempDir.resolve("pascal");
        Path pc = pascalDir.resolve("src/main/java/com/example");
        Files.createDirectories(pc.resolve("controller"));
        Files.createDirectories(pc.resolve("model"));
        Files.write(pc.resolve("model/PagingInfo.java"), src(
                "package com.example.model;",
                "import java.util.List;",
                "/** 分页包装 */",
                "public class PagingInfo<T> {",
                "    private List<T> records;",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(pc.resolve("model/UserVO.java"), src(
                "package com.example.model;",
                "/** 用户 */",
                "public class UserVO {",
                "    private java.util.List<OrderVO> orders;",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(pc.resolve("model/OrderVO.java"), src(
                "package com.example.model;",
                "/** 订单 */",
                "public class OrderVO {",
                "    private String orderNo;",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(pc.resolve("controller/PagingController.java"), src(
                "package com.example.controller;",
                "import com.example.model.*;",
                "import org.springframework.web.bind.annotation.*;",
                "import java.util.List;",
                "/** 分页 */",
                "@RestController",
                "public class PagingController {",
                "    /** 分页查用户 */",
                "    @GetMapping(\"/users\")",
                "    public PagingInfo<UserVO> users() { return null; }",
                "    /** 嵌套泛型 */",
                "    @GetMapping(\"/nested\")",
                "    public PagingInfo<List<UserVO>> nested() { return null; }",
                "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));

        GeneratorConfig config = new GeneratorConfig()
                .setProjectDir(pascalDir.toString())
                .setOutPath(tempDir.resolve("out-pascal").toString())
                .setSchemaNameStyle("pascal");
        JsonNode pascalDoc = new ObjectMapper().readTree(
                Files.readAllBytes(new OpenApiGenerator(config).generate()));

        // PagingInfo<UserVO> → PagingInfoUserVO
        JsonNode paged = pascalDoc.at("/components/schemas/PagingInfoUserVO");
        assertEquals("#/components/schemas/UserVO", paged.at("/properties/records/items/$ref").asText());
        // 嵌套: PagingInfo<List<UserVO>> → PagingInfoListUserVO, records 为数组的数组
        JsonNode nested = pascalDoc.at("/components/schemas/PagingInfoListUserVO");
        assertEquals("#/components/schemas/UserVO", nested.at("/properties/records/items/items/$ref").asText());
        // 字段泛型指向正确
        assertEquals("#/components/schemas/OrderVO",
                pascalDoc.at("/components/schemas/UserVO/properties/orders/items/$ref").asText());
        // pascal 风格下所有 schema key 都是规范允许的字符
        for (String key : (Iterable<String>) pascalDoc.at("/components/schemas")::fieldNames) {
            assertTrue(key.matches("[a-zA-Z0-9.\\-_]+"), "illegal schema key: " + key);
        }
    }
}
