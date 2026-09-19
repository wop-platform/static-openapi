package io.github.wanlianyida.staticopenapi.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 静态生成器的运行时配置.
 *
 * <p>字段默认 null, 由 getter 提供兜底默认值 — 这样入口 (Mojo/CLI) 能区分
 * "未设置" 与 "用户显式设置", 按优先级正确应用: 工程基线 &gt; 配置文件 &gt; 显式参数.
 */
public class GeneratorConfig {

    private String projectName;
    private String projectDir;
    private String outPath;
    private final List<String> packages = new ArrayList<>();
    private String openapiVersion;
    private String apiVersion;
    private Boolean prettyPrint;
    /** 通用错误响应 schema 引用 (null = 不生成 500 response). 例如 "ResultModel" */
    private String errorResponseSchema;
    /** 泛型实例化 schema 的命名风格: guillemet (默认, PagingInfo«X») / pascal (PagingInfoX, Apifox 风格) */
    private String schemaNameStyle;

    public String getProjectName() { return projectName != null ? projectName : "openapi-doc"; }
    public GeneratorConfig setProjectName(String projectName) { this.projectName = projectName; return this; }

    public String getProjectDir() { return projectDir; }
    public GeneratorConfig setProjectDir(String projectDir) { this.projectDir = projectDir; return this; }

    public String getOutPath() { return outPath != null ? outPath : "./target/static-openapi"; }
    /** outPath 是否被显式设置 (配置文件/参数); 未设置时 getOutPath() 返回兜底值 */
    public boolean isOutPathSet() { return outPath != null; }
    public GeneratorConfig setOutPath(String outPath) { this.outPath = outPath; return this; }

    public List<String> getPackages() { return packages; }
    public GeneratorConfig setPackages(List<String> packages) {
        this.packages.clear();
        if (packages != null) this.packages.addAll(packages);
        return this;
    }
    public GeneratorConfig addPackage(String pkg) { this.packages.add(pkg); return this; }

    public String getOpenapiVersion() { return openapiVersion != null ? openapiVersion : "3.1.0"; }
    public GeneratorConfig setOpenapiVersion(String openapiVersion) { this.openapiVersion = openapiVersion; return this; }

    public String getApiVersion() { return apiVersion != null ? apiVersion : "v1.0.0"; }
    public GeneratorConfig setApiVersion(String apiVersion) { this.apiVersion = apiVersion; return this; }

    public boolean isPrettyPrint() { return prettyPrint == null || prettyPrint; }
    public GeneratorConfig setPrettyPrint(boolean prettyPrint) { this.prettyPrint = prettyPrint; return this; }

    public String getErrorResponseSchema() { return errorResponseSchema; }
    public GeneratorConfig setErrorResponseSchema(String errorResponseSchema) { this.errorResponseSchema = errorResponseSchema; return this; }

    public String getSchemaNameStyle() { return schemaNameStyle != null ? schemaNameStyle : "guillemet"; }
    public GeneratorConfig setSchemaNameStyle(String schemaNameStyle) { this.schemaNameStyle = schemaNameStyle; return this; }
}
