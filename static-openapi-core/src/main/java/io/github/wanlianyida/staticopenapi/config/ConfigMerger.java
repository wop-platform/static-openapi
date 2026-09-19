package io.github.wanlianyida.staticopenapi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;

/**
 * 配置文件 JSON 字段到 GeneratorConfig 的合并.
 *
 * <p>调用方 (Mojo/CLI) 的推荐顺序: 先设工程基线默认值 → merge 配置文件 → 应用显式参数.
 * 因此本类对 JSON 中出现的标量字段无条件覆盖 (配置文件优先于基线),
 * 显式参数由调用方在 merge 之后自行覆盖 (始终最高优先级).
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    public static void mergeFromJson(File file, GeneratorConfig config) throws Exception {
        if (file == null || !file.exists()) return;
        JsonNode root = new ObjectMapper().readTree(file);
        if (root.hasNonNull("projectName"))    config.setProjectName(root.get("projectName").asText());
        if (root.hasNonNull("projectDir"))     config.setProjectDir(root.get("projectDir").asText());
        if (root.hasNonNull("outPath"))        config.setOutPath(root.get("outPath").asText());
        if (root.hasNonNull("openapiVersion")) config.setOpenapiVersion(root.get("openapiVersion").asText());
        if (root.hasNonNull("apiVersion"))     config.setApiVersion(root.get("apiVersion").asText());
        if (root.hasNonNull("prettyPrint"))    config.setPrettyPrint(root.get("prettyPrint").asBoolean());
        if (root.hasNonNull("errorResponseSchema")) config.setErrorResponseSchema(root.get("errorResponseSchema").asText());
        if (root.hasNonNull("schemaNameStyle"))     config.setSchemaNameStyle(root.get("schemaNameStyle").asText());

        if (root.has("packages") && root.get("packages").isArray()) {
            if (config.getPackages().isEmpty()) {
                root.get("packages").forEach(n -> config.addPackage(n.asText()));
            }
        } else if (root.has("packageFilters")) {
            // 兼容 smart-doc 命名: 数组或逗号分隔字符串均可
            if (config.getPackages().isEmpty()) {
                JsonNode pf = root.get("packageFilters");
                if (pf.isArray()) {
                    pf.forEach(n -> {
                        String t = n.asText().trim();
                        if (!t.isEmpty()) config.addPackage(t);
                    });
                } else if (pf.isTextual()) {
                    for (String p : pf.asText().split(",")) {
                        String t = p.trim();
                        if (!t.isEmpty()) config.addPackage(t);
                    }
                }
            }
        }
    }
}
