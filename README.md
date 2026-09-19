# static-openapi

静态分析 Java 源码生成 OpenAPI 3.1 文档的 CLI / Maven 插件工具。

通过 JavaParser 解析源码，读取 Spring Web 注解 + Swagger 2/3 注解 + Javadoc，自动产出符合 OpenAPI 3.1 规范的 JSON 文档。

## 特性

- **零运行时依赖**：仅扫描源码，不依赖 Spring Boot 运行时
- **注解支持**：
  - Spring Web: `@RequestMapping`, `@GetMapping`, `@PostMapping`, etc.
  - Swagger 2: `@Api`, `@ApiOperation`, `@ApiModel`, `@ApiModelProperty`, etc.
  - Swagger 3 / OpenAPI 3: `@Tag`, `@Operation`, `@Schema`, `@Parameter`, `@RequestBody`, etc.
  - Javadoc: 自动作为 summary / description 的兜底来源
- **泛型支持**：ResultModel\<User\> / List\<T\> / Optional\<T\> 等自动解析
- **继承支持**：父类字段自动合并到子类 schema
- **自引用检测**：TreeNode.parent 等自引用 DTO 不会 StackOverflow
- **枚举支持**：自动映射为 `type: string` + `enum: [...]`
- **多模块支持**：`projectDir` 指向工程根目录即可，自动递归发现各模块的 `src/main/java`（跳过 target/.git 等）；配置里只需写包路径，DTO 跨模块也能正确解析
- **隐藏接口**：支持 `@Hidden` / `@ApiIgnore` / `@Operation(hidden=true)`

## 安装

### Maven Plugin

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <projectName>my-api</projectName>
        <packages>
            <param>com.example.controller</param>
        </packages>
    </configuration>
</plugin>
```

运行：
```bash
mvn static-openapi:openapi
```

> **多模块工程**：直接调用只在 reactor 根模块执行一次（读取根目录的 `static-openapi.json`），子模块自动跳过、不会覆盖输出。如需为某个子模块单独生成文档，`cd` 到该子模块目录运行，或在子模块 pom 中绑定 execution。

### CLI JAR

从 Maven 构建：
```bash
mvn package -DskipTests
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar \
    -projectName my-api \
    -projectDir ./ \
    -packages com.example.controller \
    -outPath ./openapi-out
```

## 配置

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `projectName` | API 项目名称 | - |
| `projectDir` | 项目根目录（递归发现所有模块的 `src/main/java`，支持多模块工程） | - |
| `packages` | Controller 所在包（递归匹配子包） | - |
| `outPath` | 输出目录 | `<projectDir>/target/static-openapi` |
| `openapiVersion` | OpenAPI 版本 | `3.1.0` |
| `apiVersion` | API 版本（写入 info.version） | `v1.0.0` |
| `prettyPrint` | 格式化输出 JSON | `true` |
| `errorResponseSchema` | 500 错误响应引用的 schema 名称 | - |

### 配置文件

在项目根目录放置 `static-openapi.json` 即可自动发现（Maven 插件在 `${project.basedir}` 下查找，CLI 在当前工作目录下查找），无需传参：

```json
{
  "projectName": "my-api",
  "projectDir": "./",
  "packages": ["com.example.controller"],
  "outPath": "./target/static-openapi",
  "openapiVersion": "3.1.0",
  "apiVersion": "v1.0.0",
  "prettyPrint": true
}
```

```bash
# 自动发现 ./static-openapi.json
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar -projectDir ./

# 或显式指定配置文件
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar -configFile static-openapi.json
```

**优先级**：命令行/pom 显式参数 > static-openapi.json > 默认值

生成结果输出到 `<projectDir>/target/static-openapi/openapi.json`。

## 注解优先级

当多个来源同时存在时：

| 字段 | 优先级（高→低） |
|------|----------------|
| summary | @Operation(summary) > @ApiOperation(value) > Javadoc 首句 > 方法名 |
| description | @Operation(description) > @ApiOperation(notes) > Javadoc 描述 |
| operationId | @Operation(operationId) > 方法名 |
| schema name | @Schema(name) > @ApiModel(value) > 类名 |
| field name | @Schema(name) > @ApiModelProperty(name) > 字段名 |

## 输出示例

```json
{
  "openapi": "3.1.0",
  "info": {
    "title": "my-api",
    "version": "v1.0.0"
  },
  "paths": {
    "/users/{id}": {
      "get": {
        "summary": "获取用户",
        "operationId": "getUser",
        "parameters": [...],
        "responses": {
          "200": {
            "description": "OK",
            "content": {
              "application/json": {
                "schema": { "$ref": "#/components/schemas/User" }
              }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "User": {
        "type": "object",
        "properties": {
          "id": { "type": "integer", "format": "int64" },
          "name": { "type": "string" }
        }
      }
    }
  }
}
```

## 项目结构

```
static-openapi/
├── static-openapi-core/      # 核心库
│   └── src/main/java/.../
│       ├── OpenApiGenerator.java        # 主入口
│       ├── reader/                      # 注解/源码解析
│       ├── merge/                       # 文档合并
│       ├── output/                      # JSON 序列化
│       └── model/                       # 数据模型
├── static-openapi-cli/        # CLI 入口
└── static-openapi-maven-plugin/  # Maven 插件
```

## 构建

```bash
mvn clean package -DskipTests
```

## License

Apache License 2.0
