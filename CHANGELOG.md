# 更新日志

本项目的所有显著变更都记录在此文件中。
格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

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
