# 更新日志

本项目的所有显著变更都记录在此文件中。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### 修复

- `@RequestMapping(value = "...", method = RequestMethod.POST)` 被误判为"所有 HTTP 方法"展开成 7 个 operation：现在正确识别枚举引用（含数组、静态导入写法）
- 方法级 `@RequestMapping("/path")` 单值写法把路径字符串当成 HTTP method 输出非法文档：已修复
- `@ApiResponses(@ApiResponse(...))` 单值写法（数组省略花括号）导致整个生成 ClassCastException 崩溃：v2/v3 均已兼容，并支持 v2 `code = 404` int 字面量
- `Map<String, OrderVO>` 生成错误的占位 schema：现在输出 `object` + `additionalProperties`（支持嵌套）
- javadoc `@param` 参数描述从未生效（在不含 block tag 的 description 里搜索导致永远匹配不到）：改用 block tag 解析，query/path/header 参数与 `@RequestBody` 参数均可获取描述
- `@Parameter(name)` / `@ApiParam(name)` / `@Schema(name)` / `@ApiModelProperty(name)` 重命名属性被忽略：现已生效
- `errorResponseSchema` 产生悬空 `$ref`：生成时主动将目标类解析进 `components`，解析不到时告警
- 同名方法跨 Controller 导致 `operationId` 重复（违反 OpenAPI 规范）：冲突时自动追加 `_2`/`_3` 后缀
- 同 path + method 重复注册时静默覆盖：现在打告警
- `byte[]` 生成 `array<string>`：按惯例输出 `string` + `format: byte`
- Controller 继承父类（如 BaseController）的映射方法丢失：现沿 extends/implements 递归收集
- 无 Spring 注解但参数名命中路径模板（`{uid}`）的参数仍推断为 query：现在推断为 `path` 并默认必填
- schema 经 `@Schema(name)` 重命名后与其他类名冲突导致覆盖、`$ref` 指错内容：改为唯一化重命名（追加 `_2`）并告警

### 新增

- CLI `-errorResponseSchema` 参数、Maven 插件 `errorResponseSchema` 配置（此前仅配置文件可设）
- operationId / 输出顺序确定性：文件排序遍历，多机器构建产物可 diff

### 性能

- 新增共享源码解析缓存（`SourceParser`），每个源文件整个流程只解析一次（原先 controller 扫描与 DTO 解析至少各解析一遍）

### 变更

- Maven 插件 reactor 根模块判断改用 `project.isExecutionRoot()`（原为实例引用比较）
- 兜底包装类型启发式不再匹配名字含 "model" 的类（避免 `UserModel` 等普通类被误判为响应包装）
## [1.0.0-SNAPSHOT]

### 新增

- 静态分析 Java 源码生成 OpenAPI 3.1 文档（CLI + Maven 插件两种使用方式）
- 支持 Swagger 2 / Swagger 3 / Javadoc 三类文档来源，可混用并按优先级合并（Swagger 3 > Swagger 2 > Javadoc）
- 多模块工程支持：递归发现各模块 `src/main/java`，配置只需包路径；直接调用 goal 时仅在 reactor 根模块执行一次
- 泛型支持：集合展开为 `array`、包装类型（ResponseEntity/Optional/Mono 等）解包、泛型实参真实替换、按实例化缓存互不污染
- 枚举映射为 `type: string` + `enum`；Record 组件解析；父类字段沿继承链合并
- 自引用 / 互引用 DTO 以 `$ref` 递归，不会栈溢出
- `@Hidden` / `@ApiIgnore` / `hidden` 属性的接口与字段隐藏
- 配置文件 `static-openapi.json` 自动发现；配置优先级：显式参数 > 配置文件 > 默认值
- `schemaNameStyle` 配置：`guillemet`（springfox 风格，默认）/ `pascal`（Apifox 风格，符合规范字符集）
- `errorResponseSchema` 可选 500 通用错误响应
