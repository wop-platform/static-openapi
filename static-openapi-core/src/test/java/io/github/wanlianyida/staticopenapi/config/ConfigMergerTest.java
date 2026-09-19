package io.github.wanlianyida.staticopenapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigMergerTest {

    @TempDir
    Path tempDir;

    private GeneratorConfig merge(String json) throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.write(file, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GeneratorConfig config = new GeneratorConfig();
        ConfigMerger.mergeFromJson(file.toFile(), config);
        return config;
    }

    @Test
    void packagesArray() throws Exception {
        GeneratorConfig config = merge("{\"packages\":[\"com.a.controller\",\"com.b.controller\"]}");
        assertEquals(List.of("com.a.controller", "com.b.controller"), config.getPackages());
    }

    @Test
    void packageFiltersAsString() throws Exception {
        GeneratorConfig config = merge("{\"packageFilters\":\"com.a.controller, com.b.controller\"}");
        assertEquals(List.of("com.a.controller", "com.b.controller"), config.getPackages());
    }

    @Test
    void packageFiltersAsArray() throws Exception {
        GeneratorConfig config = merge("{\"packageFilters\":[\"com.a.controller\",\"com.b.controller\"]}");
        assertEquals(List.of("com.a.controller", "com.b.controller"), config.getPackages());
    }

    @Test
    void nullFieldsKeepDefaults() throws Exception {
        GeneratorConfig config = merge("{\"projectName\":null,\"projectDir\":\"/tmp/project\"}");
        assertEquals("openapi-doc", config.getProjectName());
        assertEquals("3.1.0", config.getOpenapiVersion());
        assertEquals("/tmp/project", config.getProjectDir());
    }

    @Test
    void configFileOverridesBaseline() throws Exception {
        // 模拟 Mojo 顺序: 先设工程基线默认值, 再 merge 配置文件 → 配置文件覆盖基线
        GeneratorConfig config = new GeneratorConfig().setProjectDir("/baseline/project");
        Path file = tempDir.resolve("config2.json");
        ObjectNode json = new ObjectMapper().createObjectNode();
        json.put("projectDir", "/json/project");
        Files.write(file, json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ConfigMerger.mergeFromJson(file.toFile(), config);
        assertEquals("/json/project", config.getProjectDir());
    }
}
