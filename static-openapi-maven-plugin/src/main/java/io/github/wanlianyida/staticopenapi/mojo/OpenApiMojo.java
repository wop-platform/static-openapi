package io.github.wanlianyida.staticopenapi.mojo;

import io.github.wanlianyida.staticopenapi.OpenApiGenerator;
import io.github.wanlianyida.staticopenapi.config.ConfigMerger;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Maven plugin goal: 静态扫描源码生成 OpenAPI 3.1 spec.
 *
 * <p>配置优先级: 显式 -D / pom 配置 &gt; static-openapi.json (项目根目录自动发现) &gt; 工程默认值.
 *
 * <p>多模块工程: 直接调用 (mvn static-openapi:openapi) 只在 reactor 根模块执行一次,
 * 子模块自动跳过; 若需为某个子模块单独生成文档, 在该子模块目录下运行,
 * 或在子模块 pom 中绑定 execution.
 *
 * <pre>
 *   mvn static-openapi:openapi
 *   mvn static-openapi:openapi -DprojectName=my-api   # 显式参数覆盖配置文件
 * </pre>
 */
@Mojo(name = "openapi", threadSafe = true, requiresProject = true)
public class OpenApiMojo extends AbstractMojo {

    private static final Logger log = LoggerFactory.getLogger(OpenApiMojo.class);

    /** 配置文件自动发现的文件名 */
    static final String DEFAULT_CONFIG_FILE = "static-openapi.json";

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Parameter(defaultValue = "${mojoExecution}", readonly = true)
    private MojoExecution mojoExecution;

    /** 未声明 = 未显式设置, 不与配置文件/默认值抢占优先级 */
    @Parameter(property = "configFile")
    private File configFile;

    @Parameter(property = "projectName")
    private String projectName;

    @Parameter(property = "projectDir")
    private String projectDir;

    @Parameter(property = "outPath")
    private String outPath;

    @Parameter(property = "packages")
    private List<String> packages;

    @Parameter(property = "openapiVersion")
    private String openapiVersion;

    @Parameter(property = "apiVersion")
    private String apiVersion;

    /** 用包装类型区分"未设置"与 false */
    @Parameter(property = "prettyPrint")
    private Boolean prettyPrint;

    /** 泛型实例化 schema 命名风格: guillemet (默认) / pascal (Apifox 风格) */
    @Parameter(property = "schemaNameStyle")
    private String schemaNameStyle;

    @Override
    public void execute() throws MojoExecutionException {
        // 直接调用 (default-cli) 只在 reactor 根模块执行一次:
        // 多模块 reactor 下 Maven 会对每个模块注入执行, 子模块没有根目录的配置文件,
        // 且会覆写输出文件, 因此跳过. 生命周期绑定 (executionId != default-cli) 不受影响.
        if (mojoExecution != null && "default-cli".equals(mojoExecution.getExecutionId())
                && session != null && session.getTopLevelProject() != project) {
            log.info("Skipping static-openapi for non-root module '" + project.getArtifactId()
                    + "' (direct invocation runs once at the reactor root)");
            return;
        }

        try {
            GeneratorConfig config = new GeneratorConfig();

            // 优先级 1 (基线): 工程默认值 — 文档写到当前模块自己的 target
            config.setProjectDir(project.getBasedir().getAbsolutePath());
            config.setOutPath(project.getBuild().getDirectory() + "/static-openapi");

            // 优先级 2: static-openapi.json (项目根目录自动发现 / -DconfigFile 显式指定)
            if (configFile == null) {
                File autoConfig = new File(project.getBasedir(), DEFAULT_CONFIG_FILE);
                if (autoConfig.exists()) {
                    configFile = autoConfig;
                }
            }
            if (configFile != null) {
                if (!configFile.exists()) {
                    throw new MojoExecutionException("configFile not found: " + configFile.getAbsolutePath());
                }
                ConfigMerger.mergeFromJson(configFile, config);
            }

            // 优先级 3: 显式 -D / pom <configuration> (覆盖配置文件)
            if (projectName != null && !projectName.isEmpty()) {
                config.setProjectName(projectName);
            }
            if (projectDir != null && !projectDir.isEmpty()) {
                config.setProjectDir(projectDir);
            }
            if (outPath != null && !outPath.isEmpty()) {
                config.setOutPath(outPath);
            }
            if (openapiVersion != null && !openapiVersion.isEmpty()) {
                config.setOpenapiVersion(openapiVersion);
            }
            if (apiVersion != null && !apiVersion.isEmpty()) {
                config.setApiVersion(apiVersion);
            }
            if (prettyPrint != null) {
                config.setPrettyPrint(prettyPrint);
            }
            if (packages != null && !packages.isEmpty()) {
                config.setPackages(packages);
            }
            if (schemaNameStyle != null && !schemaNameStyle.isEmpty()) {
                config.setSchemaNameStyle(schemaNameStyle);
            }

            log.info("static-openapi starting. project={}, projectDir={}, packages={}",
                    config.getProjectName(), config.getProjectDir(), config.getPackages());

            OpenApiGenerator generator = new OpenApiGenerator(config);
            Path output = generator.generate();
            log.info("OpenAPI spec generated: {}", output.toAbsolutePath());
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("static-openapi generation failed", e);
        }
    }
}
