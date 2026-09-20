# 使用教程

本教程从零走一遍完整流程：准备 Controller 源码 → 配置 → 生成 → 查看结果，并给出注解支持明细与类型映射规则的完整参考。环境准备与安装方式见 [README](../README.md#快速开始)，多模块 / CI / 平台导入见 [接入手册](INTEGRATION.md)。

## 1. 一个完整例子

### 准备源码

假设工程里有如下 Controller 和 DTO（无需编译，源码即可）：

```java
// com/example/controller/UserController.java
package com.example.controller;

import com.example.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 用户管理 */
@Tag(name = "user", description = "用户管理接口")
@RestController
@RequestMapping("/api/users")
public class UserController {

    /** 查询用户详情
     * @param id 用户ID
     */
    @Operation(summary = "用户详情", operationId = "getUser")
    @GetMapping("/{id}")
    public Result<UserVO> get(@PathVariable Long id) { return null; }

    /** 创建用户 */
    @PostMapping
    public Long create(@RequestBody UserCreateReq req) { return null; }

    /** 用户列表 */
    @GetMapping("/list")
    public Result<List<UserVO>> list() { return null; }
}
```

```java
// com/example/model/UserVO.java
package com.example.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** 用户 VO */
@Schema(description = "用户信息")
public class UserVO {
    @Schema(description = "用户名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    /** 年龄 */
    private Integer age;
    // getter/setter 省略
}
```

### 编写配置

在工程根目录创建 `static-openapi.json`：

```json
{
  "projectName": "my-api",
  "projectDir": ".",
  "packages": ["com.example.controller"],
  "schemaNameStyle": "pascal"
}
```

### 生成

```bash
# Maven 插件方式
mvn static-openapi:openapi

# 或 CLI 方式
java -jar static-openapi-cli-1.0.0-SNAPSHOT.jar
```

### 查看结果

输出到 `./target/static-openapi/openapi.json`，节选如下：

```json
{
  "openapi": "3.1.0",
  "info": { "title": "my-api", "version": "v1.0.0" },
  "tags": [ { "name": "user", "description": "用户管理接口" } ],
  "paths": {
    "/api/users/{id}": {
      "get": {
        "summary": "用户详情",
        "tags": ["user"],
        "operationId": "getUser",
        "parameters": [
          { "name": "id", "in": "path", "description": "用户ID", "required": true,
            "schema": { "type": "integer", "format": "int64" } }
        ],
        "responses": {
          "200": {
            "description": "OK",
            "content": {
              "application/json": { "schema": { "$ref": "#/components/schemas/ResultUserVO" } }
            }
          }
        }
      }
    }
  },
  "components": {
    "schemas": {
      "ResultUserVO": {
        "type": "object",
        "properties": {
          "code": { "type": "string" },
          "data": { "$ref": "#/components/schemas/UserVO" }
        }
      },
      "UserVO": {
        "type": "object",
        "description": "用户信息",
        "required": ["name"],
        "properties": {
          "name": { "type": "string", "description": "用户名" },
          "age": { "type": "integer", "format": "int32", "description": "年龄" }
        }
      }
    }
  }
}
```

可以看到工具自动完成了：`@PathVariable` 推断为 path 参数且必填、`Result<UserVO>` 泛型实参替换、`@Operation` 与 Javadoc 按优先级合并、`@Schema(requiredMode)` 生成 required 数组、Javadoc 字段注释作为属性描述兜底。

## 2. 三种使用方式

### Maven 插件

pom 中声明插件（可零配置，默认扫描整个 `${project.basedir}`）：

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</plugin>
```

```bash
mvn static-openapi:openapi                    # 使用 static-openapi.json / 默认值
mvn static-openapi:openapi -DprojectName=demo # -D 参数覆盖配置文件
mvn static-openapi:openapi -DerrorResponseSchema=ResultModel
```

也可以把参数直接写在 pom 的 `<configuration>` 里，或在生命周期中绑定（如 `package` 阶段自动生成），见[接入手册](INTEGRATION.md#maven-工程接入)。

### CLI

```bash
java -jar static-openapi-cli-1.0.0-SNAPSHOT.jar [options]
```

#### 命令行参数

| 参数 | 说明 | 默认值 |
|------|------|--------|
| `-configFile <path>` | 配置文件路径 | 自动发现当前目录 `static-openapi.json` |
| `-projectDir <path>` | 项目根目录（必填，除非配置文件提供） | - |
| `-projectName <name>` | API 项目名称 | `openapi-doc` |
| `-packages <csv>` | Controller 包名，逗号分隔 | 全部扫描 |
| `-outPath <path>` | 输出目录 | `<projectDir>/target/static-openapi` |
| `-openapiVersion <v>` | OpenAPI 版本 | `3.1.0` |
| `-apiVersion <ver>` | `info.version` | `v1.0.0` |
| `-schemaNameStyle <s>` | `guillemet` / `pascal` | `guillemet` |
| `-errorResponseSchema <n>` | 500 响应 schema 名 | 不生成 500 |
| `-h` / `--help` | 帮助 | - |

### 配置文件

`static-openapi.json` 字段与上表一致（去掉 `-` 前缀，驼峰命名），另支持 `prettyPrint`（布尔，默认 `true`）与 `packageFilters`（兼容 smart-doc 命名，数组或逗号分隔字符串均可）。

**优先级**：命令行 / `-D` / pom 显式参数 > 配置文件 > 默认值。

## 3. 文档来源与优先级

一个接口的文档信息可以来自三类来源，工具按固定优先级合并（高 → 低），可混用：

| 字段 | 优先级 |
|------|--------|
| summary | `@Operation(summary)` > `@ApiOperation(value)` > Javadoc 首句 > 方法名 |
| description | `@Operation(description)` > `@ApiOperation(notes)` > Javadoc 描述 |
| operationId | `@Operation(operationId)` > 方法名（跨 Controller 重名自动加 `_2`/`_3`） |
| schema 名 | `@Schema(name)` > `@ApiModel(value)` > 类名 |
| 参数名 | `@Parameter(name)` / `@ApiParam(name)` > Java 参数名 |
| 属性名 | `@Schema(name)` / `@ApiModelProperty(name)` > Java 字段名 |
| 参数描述 | `@Parameter(description)` / `@ApiParam(value)` > Javadoc `@param` |
| 字段描述 | `@Schema(description)` / `@ApiModelProperty(value)` > Javadoc 字段注释 |
| 字段示例 | `@Schema(example)` / `@ApiModelProperty(example)` |
| 参数位置 | `@Parameter(in)` > Spring 注解（`@PathVariable`/`@RequestParam`/`@RequestHeader`）> 路径模板命中 > query |

各来源支持的注解：

**Spring Web**：`@RestController` / `@Controller` 识别接口；`@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping` 识别路径与 HTTP 方法（多路径 `@GetMapping({"/a", "/b"})`、老式 `method = RequestMethod.POST`、`value` / `path` 双属性写法均支持）；`@PathVariable` / `@RequestParam` / `@RequestHeader` 推断参数位置；参数名命中路径模板（如 `{id}`）时自动推断为 path 参数。

**Swagger 2**（`io.swagger.annotations`）：`@Api`、`@ApiOperation`、`@ApiParam`、`@ApiModel`、`@ApiModelProperty`、`@ApiResponses`/`@ApiResponse`（`code` 支持 int 字面量）。

**Swagger 3**（`io.swagger.v3.oas.annotations`）：`@Tag`、`@Operation`、`@Parameter`（含 `ParameterIn.PATH` 枚举写法）、`@Schema`（含 `RequiredMode.REQUIRED` 枚举写法）、`@RequestBody`、`@ApiResponses`/`@ApiResponse`、`@Hidden`。

**Javadoc**：类描述、方法首句/描述、字段注释、`@param` 参数说明，作为注解缺失时的兜底。

**隐藏规则**：类或方法标注 `@Hidden` / `@ApiIgnore`，或 `@Operation(hidden=true)` / `@ApiOperation(hidden=true)` / `@Parameter(hidden=true)` / `@Schema(hidden=true)` / `@ApiModelProperty(hidden=true)` 后不会出现在文档中。

## 4. 类型映射规则

| Java 类型 | OpenAPI Schema |
|------|--------|
| `String` / `char` / `CharSequence` | `string` |
| `Integer` / `int` / `Short` / `short` | `integer` + `int32` |
| `Long` / `long` | `integer` + `int64` |
| `BigInteger` | `integer` |
| `Double` / `double` | `number` + `double` |
| `Float` / `float` | `number` + `float` |
| `BigDecimal` | `number` |
| `Boolean` / `boolean` | `boolean` |
| `LocalDate` | `string` + `date` |
| `LocalDateTime` / `Date` | `string` + `date-time` |
| `LocalTime` | `string` + `time` |
| `byte[]` | `string` + `format: byte` |
| 枚举 | `string` + `enum`（枚举常量名） |
| `List` / `Set` / `Collection` 及常用实现类、数组 | `array` + `items` |
| `Map<String, OrderVO>` 及常用实现类 | `object` + `additionalProperties`（值为第二个泛型实参的 schema） |
| `Optional<T>` / `ResponseEntity<T>` / `Mono<T>` / `Flux<T>` / `CompletableFuture<T>` 等包装类型 | 解包为 `T` 的 schema |
| 业务泛型类 `ResultModel<UserVO>` | 按源码展开，形参 `T` 用实参替换；不同实参生成独立 schema（`ResultModel«UserVO»` 或 pascal 风格 `ResultModelUserVO`） |
| Record | record 组件作为 properties |
| 父类字段 | 沿 extends 链合并（父类在前，子类同名字段覆盖） |
| 自引用 / 互引用 DTO | 以 `$ref` 递归，不会栈溢出 |
| 无源码类型 | 退化为 `object` |

泛型类源码找不到时（如只引用了 jar 依赖），对名字含 `result` / `response` 的类型按常见包装结构兜底，其余生成 `{ "value": <实参 schema> }`；建议将 DTO 源码纳入扫描范围获得精确结果。

## 5. schema 命名风格

泛型实例化会生成独立 schema，命名风格由 `schemaNameStyle` 控制：

| 风格 | 示例 | 适用 |
|------|------|------|
| `guillemet`（默认） | `PagingInfo«UserVO»` | springfox 风格，本地查看 / Swagger UI |
| `pascal` | `PagingInfoUserVO` | **导入 Apifox 等严格校验平台时使用**（component key 只允许 `[a-zA-Z0-9.-_]`） |

嵌套泛型在 pascal 风格下递归压平：`PagingInfo<List<UserVO>>` → `PagingInfoListUserVO`。

## 6. 500 通用错误响应

配置 `errorResponseSchema` 后，每个接口自动追加 500 响应：

```json
{
  "errorResponseSchema": "ResultModel"
}
```

```json
"responses": {
  "200": { "...": "..." },
  "500": {
    "description": "Internal Server Error",
    "content": { "application/json": { "schema": { "$ref": "#/components/schemas/ResultModel" } } }
  }
}
```

目标类会被自动解析进 `components`（即使它没有被任何接口直接返回）；`@ApiResponses` / `@ApiResponse` 显式声明的响应码优先，不会重复生成 200/500。

## 7. 输出说明

- 输出文件固定为 `<outPath>/openapi.json`，目录不存在会自动创建
- `prettyPrint: true`（默认）输出带缩进的 JSON， false 时为单行（体积更小）
- 文件排序遍历生成，同一份源码多次构建产物完全一致，可用于 CI 中 diff 检测接口变更
