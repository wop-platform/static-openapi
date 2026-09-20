# static-openapi

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

静态分析 Java 源码生成 OpenAPI 3.1 文档的 CLI / Maven 插件工具。

通过 JavaParser 解析源码，读取 Spring Web 注解 + Swagger 2/3 注解 + Javadoc，自动产出符合 OpenAPI 3.1 规范的 JSON 文档。**零侵入**：被扫描的工程不需要任何代码或依赖改造，源码即可。

## 文档

| 文档 | 内容 |
|------|------|
| [使用教程](docs/USAGE.md) | 完整示例：从 Controller 源码到生成 openapi.json，注解支持明细、类型映射规则 |
| [接入手册](docs/INTEGRATION.md) | 多模块工程接入、CI/CD 集成（GitHub Actions / GitLab CI / Jenkins）、导入 Apifox / Swagger UI / Postman、常见问题 |
| [更新日志](CHANGELOG.md) | 版本变更记录 |

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

> **多模块工程**：直接调用只在 reactor 根模块执行一次（读取根目录的 `static-openapi.json`），子模块自动跳过、不会覆盖输出。工具会递归发现各模块的 `src/main/java`，配置里只需写包路径，无需指定模块路径。详见[接入手册](docs/INTEGRATION.md)。

### 方式二：CLI

```bash
mvn package -DskipTests
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar \
    -projectDir ./ \
    -packages com.example.controller
```

完整参数见 `--help`，或查看[使用教程](docs/USAGE.md#命令行参数)。

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
| `errorResponseSchema` | 500 错误响应引用的 schema 名称（类名或 `@Schema`/`@ApiModel` 重命名后的名字；目标类会被自动解析进 components） | 不生成 500 |
| `schemaNameStyle` | 泛型包装类 schema 命名风格：`guillemet`（`PagingInfo«Query»`，springfox 风格）/ `pascal`（`PagingInfoQuery`，Apifox 风格，符合规范的字符集要求） | `guillemet` |

以上参数均可通过三种途径设置：Maven 插件 `-D` 参数（如 `-DerrorResponseSchema=ResultModel`）/ pom `<configuration>`、CLI 命令行参数（如 `-errorResponseSchema ResultModel`）、配置文件字段。

## 注解支持与优先级

支持三类文档来源，可同时存在：

| 来源 | 注解 |
|------|------|
| Spring Web | `@RestController` / `@Controller` / `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`；`@PathVariable` / `@RequestParam` / `@RequestHeader`（参数位置推断） |
| Swagger 2（`io.swagger.annotations`） | `@Api`、`@ApiOperation`、`@ApiParam`、`@ApiModel`、`@ApiModelProperty`、`@ApiResponses` / `@ApiResponse` |
| Swagger 3 / OpenAPI 3（`io.swagger.v3.oas.annotations`） | `@Tag`、`@Operation`、`@Parameter`、`@Schema`、`@RequestBody`、`@ApiResponses` / `@ApiResponse`、`@Hidden` |
| Javadoc | 类 / 方法 / 字段 / `@param` 注释，作为兜底来源 |

继承链上的接口方法同样会被收集：Controller 实现的接口、继承的父类（如 `BaseController` 公共 CRUD）都会参与生成。

同一信息多来源同时存在时的取值优先级（高 → 低）：

| 字段 | 优先级 |
|------|--------|
| summary | `@Operation(summary)` > `@ApiOperation(value)` > Javadoc 首句 > 方法名 |
| description | `@Operation(description)` > `@ApiOperation(notes)` > Javadoc 描述 |
| operationId | `@Operation(operationId)` > 方法名（重复时自动追加 `_2`/`_3` 保证全文档唯一） |
| schema 名 | `@Schema(name)` > `@ApiModel(value)` > 类名（冲突时唯一化并告警） |
| 参数名 | `@Parameter(name)` / `@ApiParam(name)` > Java 参数名 |
| 属性名 | `@Schema(name)` / `@ApiModelProperty(name)` > Java 字段名 |
| 参数位置 | `@Parameter(in)` > `@PathVariable` / `@RequestParam` / `@RequestHeader` > 路径模板命中（参数名出现在 `{uid}` 中） > query |

隐藏接口：方法或类标注 `@Hidden` / `@ApiIgnore` / `@Operation(hidden=true)` / `@ApiOperation(hidden=true)` / `@Schema(hidden=true)` 后不会出现在文档中。

## 类型映射规则

| Java 类型 | OpenAPI Schema |
|------|--------|
| `String` / `char` / `Character` | `string` |
| `Integer` / `int` / `Short` | `integer` + `int32` |
| `Long` / `long` / `BigInteger` | `integer`（`Long` 带 `int64`） |
| `Double` / `Float` / `BigDecimal` | `number` |
| `LocalDate` / `Date` | `string` + `date` / `date-time` |
| `byte[]` | `string` + `format: byte` |
| 枚举 | `string` + `enum` 可选值 |
| `List` / `Set` / `Collection` 及实现类 | `array` + `items` |
| `Map<String, OrderVO>` 及实现类 | `object` + `additionalProperties: {$ref}` |
| `Optional` / `ResponseEntity` / `Mono` 等包装类型 | 解包为泛型实参本身 |
| `ResultModel<UserVO>` 等业务泛型类 | 按源码展开并以实参替换形参（`data: T` → `UserVO`），不同实参生成独立 schema |
| Record | 组件作为 properties |
| 父类字段 | 沿 extends 链合并进子类 schema |
| 自引用 / 互引用 DTO | 以 `$ref` 递归，不会栈溢出 |
| 无源码的第三方类型 | 退化为 `object`；需要展开时把对应源码目录纳入扫描范围 |

## 工作原理

1. 递归发现 `projectDir` 下所有模块的 `src/main/java`，用 JavaParser 解析全部 `.java` 源文件（带缓存，每个文件只解析一次）
2. 过滤 `packages` 下带 `@Controller` / `@RestController` 的类，读取 Spring 映射注解得到路径与 HTTP 方法
3. 对每个接口：解析参数与返回类型的 Java 类型为 OpenAPI Schema，合并 Swagger 注解与 Javadoc 描述
4. 输出 OpenAPI 3.1 JSON（文件排序遍历，构建产物可重复、可 diff）

## 支持范围与已知限制

- 仅识别 **Spring MVC** 注解的接口；JAX-RS、WebFlux 函数式端点等不支持
- 仅输出 **JSON** 格式，不支持 YAML
- `src/test/java` 不参与文档生成
- **无源码的类型**（如第三方 jar 中的 DTO）退化为空 `object`；需要展开时请把对应源码目录纳入扫描范围
- 同一简单类名在多个模块出现时，按排序取第一个命中的源文件
- 同 path + method 仅保留一个 operation（重复注册会告警，如仅靠 `params=` 区分的重载）
- Swagger 注解支持的是核心常用子集，`@ApiImplicitParam`、`@ArraySchema`、`@Server`、`@SecurityScheme`、`@ApiResponse` 的 `content`/`schema` 属性等暂不支持
- `pascal` 命名风格为有损压平，不同泛型实参理论上可能拼出同名（发生时会自动加后缀并告警）

## 项目结构

```
static-openapi/
├── static-openapi-core/         # 核心库
│   └── src/main/java/.../
│       ├── OpenApiGenerator.java        # 主入口
│       ├── reader/                      # 注解/源码解析（含 SourceParser 解析缓存）
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
