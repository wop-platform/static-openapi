package io.github.wanlianyida.staticopenapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试: 修复 "注解非常规写法产出错误/崩溃" 的一组问题, 每个用例一个最小工程.
 */
class RegressionFixesTest {

    @TempDir
    Path tempDir;

    private JsonNode generate(Map<String, String> sources) throws IOException {
        return generate(sources, c -> {});
    }

    private JsonNode generate(Map<String, String> sources,
                              java.util.function.Consumer<GeneratorConfig> customizer) throws IOException {
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        for (Map.Entry<String, String> e : sources.entrySet()) {
            Path file = srcDir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        GeneratorConfig config = new GeneratorConfig()
                .setProjectDir(tempDir.toString())
                .setOutPath(tempDir.resolve("out").toString())
                .setPackages(List.of("com.example.controller"));
        customizer.accept(config);
        return new ObjectMapper().readTree(
                Files.readAllBytes(new OpenApiGenerator(config).generate()));
    }

    private static String controller(String name, String... body) {
        // import 行提升到类声明之前, 其余进类体
        java.util.List<String> imports = new java.util.ArrayList<>();
        imports.add("import org.springframework.web.bind.annotation.*;");
        imports.add("import org.springframework.web.bind.annotation.RequestMethod;");
        imports.add("import com.example.model.*;");
        java.util.List<String> members = new java.util.ArrayList<>();
        for (String line : body) {
            if (line.trim().startsWith("import ")) {
                imports.add(line.trim());
            } else {
                members.add("    " + line);
            }
        }
        StringBuilder sb = new StringBuilder("package com.example.controller;\n");
        for (String imp : imports) sb.append(imp).append("\n");
        sb.append("@RestController\n").append("public class ").append(name).append(" {\n");
        for (String member : members) sb.append(member).append("\n");
        return sb.append("}\n").toString();
    }

    @Test
    void requestMappingWithRequestMethodEnumGeneratesOnlyThatMethod() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/LegacyController.java", controller("LegacyController",
                "@RequestMapping(value = \"/legacy\", method = RequestMethod.POST)",
                "public String legacy() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode item = doc.at("/paths").get("/legacy");
        assertNotNull(item);
        // 之前 RequestMethod.POST 被当成 ANY, 展开成 7 个 method
        assertEquals(1, item.size());
        assertNotNull(item.get("post"));
        assertNull(item.get("get"));
    }

    @Test
    void singleValueRequestMappingKeepsPathAsPathAndUniqueOperationIds() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/SingleController.java", controller("SingleController",
                "@RequestMapping(\"/thing\")",
                "public String thing() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode item = doc.at("/paths").get("/thing");
        assertNotNull(item);
        // 之前路径字符串被当成 HTTP method key ("/thing"), 现在是合法 verb 集合
        assertTrue(item.has("get") && item.has("post") && item.has("put"));
        assertFalse(item.has("/thing"));
        // ANY 展开的每个 operation 都有唯一 operationId
        java.util.Set<String> ids = new java.util.HashSet<>();
        item.forEach(op -> ids.add(op.get("operationId").asText()));
        assertEquals(item.size(), ids.size());
    }

    @Test
    void singleValueApiResponsesV3DoesNotCrashAndKeepsDeclaredResponse() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/RespV3Controller.java", controller("RespV3Controller",
                "import io.swagger.v3.oas.annotations.responses.ApiResponse;",
                "import io.swagger.v3.oas.annotations.responses.ApiResponses;",
                "@ApiResponses(@ApiResponse(responseCode = \"404\", description = \"not found\"))",
                "@GetMapping(\"/v3\")",
                "public String v3() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode responses = doc.at("/paths/~1v3/get/responses");
        assertEquals("not found", responses.at("/404/description").asText());
    }

    @Test
    void singleValueApiResponsesV2WithIntCodeDoesNotCrash() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/RespV2Controller.java", controller("RespV2Controller",
                "import io.swagger.annotations.ApiResponse;",
                "import io.swagger.annotations.ApiResponses;",
                "@ApiResponses(@ApiResponse(code = 404, message = \"missing\"))",
                "@GetMapping(\"/v2\")",
                "public String v2() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode responses = doc.at("/paths/~1v2/get/responses");
        assertEquals("missing", responses.at("/404/description").asText());
    }

    @Test
    void mapFieldsBecomeObjectWithAdditionalProperties() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("model/OrderVO.java",
                "package com.example.model;\npublic class OrderVO { public String orderNo; }\n");
        sources.put("model/UserVO.java",
                "package com.example.model;\n"
                + "import java.util.Map;\n"
                + "public class UserVO {\n"
                + "    public Map<String, OrderVO> orders;\n"
                + "    public Map<String, java.util.List<OrderVO>> index;\n"
                + "}\n");
        sources.put("controller/MapController.java", controller("MapController",
                "@GetMapping(\"/map\")",
                "public java.util.Map<String, UserVO> map() { return null; }"));
        JsonNode doc = generate(sources);

        // 之前生成 bogus "Map«String,UserVO»" {value: string}; 现在是 additionalProperties
        JsonNode schema = doc.at("/paths/~1map/get/responses/200/content/application~1json/schema");
        assertEquals("object", schema.get("type").asText());
        assertEquals("#/components/schemas/UserVO",
                schema.at("/additionalProperties/$ref").asText());
        assertFalse(hasSchemaKeyPrefix(doc, "Map«"));

        JsonNode orders = doc.at("/components/schemas/UserVO/properties/orders");
        assertEquals("#/components/schemas/OrderVO",
                orders.at("/additionalProperties/$ref").asText());
        // 嵌套 Map 值为数组
        JsonNode index = doc.at("/components/schemas/UserVO/properties/index");
        assertEquals("array", index.at("/additionalProperties/type").asText());
        assertEquals("#/components/schemas/OrderVO",
                index.at("/additionalProperties/items/$ref").asText());
    }

    private static boolean hasSchemaKeyPrefix(JsonNode doc, String prefix) {
        for (String key : (Iterable<String>) doc.at("/components/schemas")::fieldNames) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    @Test
    void javadocParamDescriptionAppliedToPlainParameter() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/JavadocController.java",
                "package com.example.controller;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "@RestController\n"
                + "public class JavadocController {\n"
                + "    /** 查询\n"
                + "     * @param uid 用户唯一ID\n"
                + "     */\n"
                + "    @GetMapping(\"/q\")\n"
                + "    public String q(String uid) { return null; }\n"
                + "}\n");
        JsonNode doc = generate(sources);

        JsonNode param = doc.at("/paths/~1q/get/parameters").get(0);
        assertEquals("用户唯一ID", param.get("description").asText());
    }

    @Test
    void unannotatedParamMatchingPathTemplateBecomesPathParameter() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/TemplateController.java", controller("TemplateController",
                "@GetMapping(\"/u/{uid}/orders/{oid}\")",
                "public String get(String uid, String oid, String keyword) { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode params = doc.at("/paths/~1u~1{uid}~1orders~1{oid}/get/parameters");
        assertEquals(3, params.size());
        for (JsonNode p : params) {
            String name = p.get("name").asText();
            if (name.equals("keyword")) {
                assertEquals("query", p.get("in").asText());
            } else {
                assertEquals("path", p.get("in").asText());
                assertTrue(p.get("required").asBoolean());
            }
        }
    }

    @Test
    void paramNameOverrideAndModelPropertyRenameApplied() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("model/Query.java",
                "package com.example.model;\n"
                + "import io.swagger.annotations.ApiModelProperty;\n"
                + "public class Query {\n"
                + "    @ApiModelProperty(name = \"page_no\", value = \"页码\", required = true)\n"
                + "    public int pageNo;\n"
                + "}\n");
        sources.put("controller/RenameController.java", controller("RenameController",
                "@GetMapping(\"/r\")",
                "public Query r(@io.swagger.v3.oas.annotations.Parameter(name = \"session_id\") String sessionId) { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode param = doc.at("/paths/~1r/get/parameters").get(0);
        assertEquals("session_id", param.get("name").asText());

        JsonNode schema = doc.at("/components/schemas/Query/properties");
        assertTrue(schema.has("page_no"));
        assertFalse(schema.has("pageNo"));
        assertTrue(doc.at("/components/schemas/Query/required").get(0).asText().equals("page_no"));
    }

    @Test
    void byteArrayMapsToStringWithByteFormat() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("model/Avatar.java",
                "package com.example.model;\npublic class Avatar { public byte[] data; }\n");
        sources.put("controller/ByteController.java", controller("ByteController",
                "@GetMapping(\"/b\")",
                "public Avatar b() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode data = doc.at("/components/schemas/Avatar/properties/data");
        assertEquals("string", data.get("type").asText());
        assertEquals("byte", data.get("format").asText());
    }

    @Test
    void errorResponseSchemaResolvedIntoComponentsEvenIfUnreferenced() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("model/ErrVO.java",
                "package com.example.model;\n"
                + "public class ErrVO { public String code; }\n");
        sources.put("controller/ErrController.java", controller("ErrController",
                "@GetMapping(\"/e\")",
                "public String e() { return null; }"));
        JsonNode doc = generate(sources, c -> c.setErrorResponseSchema("ErrVO"));

        // ErrVO 未被任何接口返回, 之前 $ref 悬空; 现在主动解析进 components
        assertTrue(doc.at("/components/schemas").has("ErrVO"));
        assertEquals("#/components/schemas/ErrVO",
                doc.at("/paths/~1e/get/responses/500/content/application~1json/schema/$ref").asText());
    }

    @Test
    void inheritedControllerMethodsAreCollected() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        // 父类无 @RestController 注解, 仅提供公共映射方法 (BaseController 模式)
        sources.put("controller/BaseController.java",
                "package com.example.controller;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "public class BaseController {\n"
                + "    @GetMapping(\"/base-get\")\n"
                + "    public String baseGet() { return null; }\n"
                + "}\n");
        sources.put("controller/ChildController.java",
                "package com.example.controller;\n"
                + "import org.springframework.web.bind.annotation.*;\n"
                + "@RestController\n"
                + "@RequestMapping(\"/child\")\n"
                + "public class ChildController extends BaseController {\n"
                + "    @GetMapping(\"/own\")\n"
                + "    public String own() { return null; }\n"
                + "}\n");
        JsonNode doc = generate(sources);

        assertNotNull(doc.at("/paths").get("/child/own"));
        // 父类 (非 controller 注解) 的映射方法被继承
        assertNotNull(doc.at("/paths").get("/child/base-get"));
    }

    @Test
    void duplicateOperationIdsAcrossControllersAreUniquefied() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("controller/AController.java", controller("AController",
                "@GetMapping(\"/a\")",
                "public String get() { return null; }"));
        sources.put("controller/BController.java", controller("BController",
                "@GetMapping(\"/b\")",
                "public String get() { return null; }"));
        JsonNode doc = generate(sources);

        String idA = doc.at("/paths/~1a/get/operationId").asText();
        String idB = doc.at("/paths/~1b/get/operationId").asText();
        assertEquals("get", idA);
        assertEquals("get_2", idB);
    }

    @Test
    void schemaNameOverrideCollisionIsUniquefiedNotOverwritten() throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        // Alpha 重命名为 "Beta", 与真实类 Beta 撞名
        sources.put("model/Alpha.java",
                "package com.example.model;\n"
                + "import io.swagger.v3.oas.annotations.media.Schema;\n"
                + "@Schema(name = \"Beta\")\n"
                + "public class Alpha { public String a; }\n");
        sources.put("model/Beta.java",
                "package com.example.model;\npublic class Beta { public String b; }\n");
        sources.put("controller/CollisionController.java", controller("CollisionController",
                "@GetMapping(\"/alpha\")",
                "public Alpha alpha() { return null; }",
                "@GetMapping(\"/beta\")",
                "public Beta beta() { return null; }"));
        JsonNode doc = generate(sources);

        JsonNode schemas = doc.at("/components/schemas");
        assertTrue(schemas.has("Beta"));
        assertTrue(schemas.has("Beta_2"));
        // 引用保持一致: 先解析者占用原名 (alpha 先声明 → Alpha 重命名占 "Beta", 真实 Beta 改为 "Beta_2")
        assertEquals("#/components/schemas/Beta",
                doc.at("/paths/~1alpha/get/responses/200/content/application~1json/schema/$ref").asText());
        assertEquals("#/components/schemas/Beta_2",
                doc.at("/paths/~1beta/get/responses/200/content/application~1json/schema/$ref").asText());
        assertEquals("a", schemas.at("/Beta/properties").fieldNames().next());
        assertEquals("b", schemas.at("/Beta_2/properties").fieldNames().next());
    }
}
