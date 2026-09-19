package io.github.wanlianyida.staticopenapi.cli;

import io.github.wanlianyida.staticopenapi.OpenApiGenerator;
import io.github.wanlianyida.staticopenapi.config.ConfigMerger;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 入口: 命令行参数风格与 smart-doc 一致.
 *
 * <p>配置优先级: 命令行显式参数 &gt; static-openapi.json (当前目录自动发现) &gt; 默认值.
 */
public class Main {

    /** 配置文件自动发现的文件名 */
    static final String DEFAULT_CONFIG_FILE = "static-openapi.json";

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            GeneratorConfig config = parseArgs(args);
            validate(config);
            OpenApiGenerator generator = new OpenApiGenerator(config);
            Path output = generator.generate();
            log.info("Done. Output: {}", output.toAbsolutePath());
        } catch (Exception e) {
            log.error("static-openapi generation failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    static GeneratorConfig parseArgs(String[] args) throws Exception {
        GeneratorConfig config = new GeneratorConfig();
        String configFile = null;
        String explicitProjectName = null;
        String explicitProjectDir = null;
        String explicitOutPath = null;
        List<String> explicitPackages = null;
        String explicitOpenapiVersion = null;
        String explicitApiVersion = null;
        String explicitSchemaNameStyle = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-configFile":
                    configFile = nextArg(args, i, a);
                    i++;
                    break;
                case "-projectName":
                    explicitProjectName = nextArg(args, i, a);
                    i++;
                    break;
                case "-projectDir":
                    explicitProjectDir = nextArg(args, i, a);
                    i++;
                    break;
                case "-outPath":
                    explicitOutPath = nextArg(args, i, a);
                    i++;
                    break;
                case "-packages":
                    explicitPackages = splitCsv(nextArg(args, i, a));
                    i++;
                    break;
                case "-openapiVersion":
                    explicitOpenapiVersion = nextArg(args, i, a);
                    i++;
                    break;
                case "-apiVersion":
                    explicitApiVersion = nextArg(args, i, a);
                    i++;
                    break;
                case "-schemaNameStyle":
                    explicitSchemaNameStyle = nextArg(args, i, a);
                    i++;
                    break;
                case "-h":
                case "--help":
                    printHelp();
                    System.exit(0);
                default:
                    throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }

        // 自动发现配置文件: 当前目录下的 static-openapi.json
        if (configFile == null) {
            File autoConfig = new File(DEFAULT_CONFIG_FILE);
            if (autoConfig.exists()) {
                configFile = autoConfig.getAbsolutePath();
            }
        }

        // 优先级 1: configFile 作为基线
        if (configFile != null) {
            mergeFromJson(new File(configFile), config);
        }

        // 优先级 2: 命令行显式参数 (覆盖 configFile)
        if (explicitProjectName != null) config.setProjectName(explicitProjectName);
        if (explicitProjectDir != null) config.setProjectDir(explicitProjectDir);
        if (explicitPackages != null) config.setPackages(explicitPackages);
        if (explicitOpenapiVersion != null) config.setOpenapiVersion(explicitOpenapiVersion);
        if (explicitApiVersion != null) config.setApiVersion(explicitApiVersion);
        if (explicitSchemaNameStyle != null) config.setSchemaNameStyle(explicitSchemaNameStyle);

        // 优先级 3: 默认值 (仅补缺失; outPath 相对 projectDir, 配置文件已设置时不覆盖)
        if (explicitOutPath != null) {
            config.setOutPath(explicitOutPath);
        } else if (!config.isOutPathSet()
                && config.getProjectDir() != null && !config.getProjectDir().isEmpty()) {
            config.setOutPath(config.getProjectDir() + "/target/static-openapi");
        }

        return config;
    }

    private static String nextArg(String[] args, int flagIndex, String flag) {
        if (flagIndex + 1 >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[flagIndex + 1];
    }

    private static void mergeFromJson(File file, GeneratorConfig config) throws Exception {
        if (!file.exists()) {
            throw new IllegalArgumentException("configFile not found: " + file.getAbsolutePath());
        }
        ConfigMerger.mergeFromJson(file, config);
    }

    private static List<String> splitCsv(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) {
            String x = t.trim();
            if (!x.isEmpty()) out.add(x);
        }
        return out;
    }

    private static void validate(GeneratorConfig config) {
        if (config.getProjectDir() == null || config.getProjectDir().isEmpty()) {
            throw new IllegalArgumentException(
                    "projectDir is required. Use -projectDir /path/to/project or " + DEFAULT_CONFIG_FILE + ".");
        }
        if (config.getPackages().isEmpty()) {
            log.warn("packages is empty. Will scan ALL @Controller under projectDir.");
        }
    }

    private static void printHelp() {
        System.out.println("Usage: java -jar static-openapi-cli.jar [options]");
        System.out.println("Options:");
        System.out.println("  -configFile <path>    JSON configuration file (default: ./static-openapi.json)");
        System.out.println("  -projectName <name>  API project name (default: openapi-doc)");
        System.out.println("  -projectDir <path>   Project root directory (required)");
        System.out.println("  -outPath <path>      Output directory (default: <projectDir>/target/static-openapi)");
        System.out.println("  -packages <csv>      Comma-separated packages to scan (controller packages)");
        System.out.println("  -openapiVersion <v>  OpenAPI version: 3.0.0 / 3.1.0 (default 3.1.0)");
        System.out.println("  -apiVersion <ver>    API version in info.version (default v1.0.0)");
        System.out.println("  -schemaNameStyle <s> Generic schema naming: guillemet (default) / pascal (Apifox style)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java -jar ... -projectDir /path/to/project \\");
        System.out.println("                 -packages com.example.controller");
        System.out.println("  java -jar ... -configFile ./static-openapi.json");
    }
}
