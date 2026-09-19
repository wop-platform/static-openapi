# static-openapi

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

静态分析 Java 源码生成 OpenAPI 3.1 文档的 CLI / Maven 插件工具。

通过 JavaParser 解析源码，读取 Spring Web 注解 + Swagger 2/3 注解 + Javadoc，自动产出符合 OpenAPI 3.1 规范的 JSON 文档。**零侵入**：被扫描的工程不需要任何代码或依赖改造，源码即可。

## 环境要求

| 项 | 要求 |
|------|------|
| 工具运行 | JDK 11+，Maven 3.9+（使用 Maven 插件方式时） |
| 被扫描工程 | Spring MVC 项目源码即可；无需编译通过，无需引入任何额外依赖 |

## 快速开始

### 方式一：Maven 插件（推荐）

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</plugin>
```

在项目根目录放置 `static-openapi.json`，然后：

```bash
mvn static-openapi:openapi
```

> **多模块工程**：直接调用只在 reactor 根模块执行一次（读取根目录的 `static-openapi.json`），子模块自动跳过、不会覆盖输出。工具会递归发现各模块的 `src/main/java`，配置里只需写包路径，无需指定模块路径。如需为某个子模块单独生成文档，`cd` 到该子模块目录运行，或在子模块 pom 中绑定 execution。

### 方式二：CLI

```bash
mvn package -DskipTests
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar \
    -projectDir ./ \
    -packages com.example.controller
```

### 方式三：配置文件自动发现

在项目根目录放置 `static-openapi.json` 即可自动发现（Maven 插件在 `${project.basedir}` 下查找，CLI 在当前工作目录下查找），无需传参：

```json
{
  "projectName": "my-api",
  "projectDir": ".",
  "packages": ["com.example.controller"],
  "schemaNameStyle": "pascal",
  "outPath": "./target/static-openapi",
  "openapiVersion": "3.1.0",
  "apiVersion": "v1.0.0",
  "prettyPrint": true
}
```

```bash
# 自动发现 ./static-openapi.json
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar

# 或显式指定配置文件
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar -configFile static-openapi.json
```

**优先级**：命令行 / pom 显式参数 > `static-openapi.json` > 默认值

生成结果输出到 `<projectDir>/target/static-openapi/openapi.json`。

## 配置项

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `projectName` | API 项目名称（写入 `info.title`） | `openapi-doc` |
| `projectDir` | 项目根目录（递归发现所有模块的 `src/main/java`，支持多模块工程） | Maven 方式自动为 `${project.basedir}`；CLI 必填 |
| `packages` | Controller 所在包（递归匹配子包）；不填则扫描全部 `@Controller` | - |
| `outPath` | 输出目录 | `<projectDir>/target/static-openapi` |
| `openapiVersion` | OpenAPI 版本 | `3.1.0` |
| `apiVersion` | API 版本（写入 `info.version`） | `v1.0.0` |
| `prettyPrint` | 格式化输出 JSON | `true` |
| `errorResponseSchema` | 500 错误响应引用的 schema 名称 | 不生成 500 |
| `schemaNameStyle` | 泛型包装类 schema 命名风格：`guillemet`（`PagingInfo«Query»`，springfox 风格）/ `pascal`（`PagingInfoQuery`，Apifox 风格，符合规范的字符集要求） | `guillemet` |

## 注解支持与优先级

支持三类文档来源，可同时存在：

| 来源 | 注解 |
|------|------|
| Spring Web | `@RestController` / `@Controller` / `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` |
| Swagger 2（`io.swagger.annotations`） | `@Api`、`@ApiOperation`、`@ApiParam`、`@ApiModel`、`@ApiModelProperty` |
| Swagger 3 / OpenAPI 3（`io.swagger.v3.oas.annotations`） | `@Tag`、`@Operation`、`@Parameter`、`@Schema`、`@RequestBody`、`@Hidden` |
| Javadoc | 类 / 方法 / 字段注释，作为兜底来源 |

同一信息多来源同时存在时的取值优先级（高 → 低）：

| 字段 | 优先级 |
|------|--------|
| summary | `@Operation(summary)` > `@ApiOperation(value)` > Javadoc 首句 > 方法名 |
| description | `@Operation(description)` > `@ApiOperation(notes)` > Javadoc 描述 |
| operationId | `@Operation(operationId)` > 方法名 |
| schema 名 | `@Schema(name)` > `@ApiModel(value)` > 类名 |
| 参数位置 | `@Parameter(in)` > `@PathVariable` / `@RequestParam` / `@RequestHeader` > query |

隐藏接口：方法或类标注 `@Hidden` / `@ApiIgnore` / `@Operation(hidden=true)` / `@ApiOperation(hidden=true)` / `@Schema(hidden=true)` 后不会出现在文档中。

## 工作原理

1. 递归发现 `projectDir` 下所有模块的 `src/main/java`，用 JavaParser 解析全部 `.java` 源文件
2. 过滤 `packages` 下带 `@Controller` / `@RestController` 的类，读取 Spring 映射注解得到路径与 HTTP 方法
3. 对每个接口：解析参数与返回类型的 Java 类型为 OpenAPI Schema（集合 → `array`、包装类型解包、泛型实参替换、枚举 → `enum`、父类字段合并、自引用 DTO 以 `$ref` 递归）
4. 合并 Swagger 注解与 Javadoc 描述，输出 OpenAPI 3.1 JSON

## 支持范围与已知限制

- 仅识别 **Spring MVC** 注解的接口；JAX-RS、WebFlux 函数式端点等不支持
- 仅输出 **JSON** 格式，不支持 YAML
- `src/test/java` 不参与文档生成
- **无源码的类型**（如第三方 jar 中的 DTO）退化为空 `object`；需要展开时请把对应源码目录纳入扫描范围
- `Map<K,V>` 类型解析为 `object`，暂不展开 `additionalProperties`
- 响应状态码只生成 `200`（可通过 `errorResponseSchema` 追加 500）；`@ApiResponse` / `@ApiResponses` 暂不支持
- Swagger 注解支持的是核心常用子集，`@ApiImplicitParam`、`@ArraySchema`、`@Server`、`@SecurityScheme` 等暂不支持
- `pascal` 命名风格为有损压平，不同泛型实参理论上可能拼出同名（发生时会打 `Duplicate schema name` 告警）

## 项目结构

```
static-openapi/
├── static-openapi-core/         # 核心库
│   └── src/main/java/.../
│       ├── OpenApiGenerator.java        # 主入口
│       ├── reader/                      # 注解/源码解析
│       ├── merge/                       # 文档合并
│       ├── output/                      # JSON 序列化
│       └── model/                       # 数据模型
├── static-openapi-cli/          # CLI 入口（可执行 uber-jar）
└── static-openapi-maven-plugin/ # Maven 插件
```

## 构建

```bash
mvn clean package -DskipTests   # 构建
mvn clean install               # 构建并执行单元测试
```

## 贡献

欢迎提交 Issue 和 Pull Request。提交 PR 前请确保：

```bash
mvn clean install   # 全部单元测试通过
```

## License

[Apache License 2.0](LICENSE)
