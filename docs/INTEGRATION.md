# 接入手册

面向工程接入与 DevOps 场景：Maven 工程接入、多模块工程、CI/CD 集成、把生成结果导入 Apifox / Swagger UI / Postman，以及常见问题排查。基础用法见[使用教程](USAGE.md)。

## 目录

- [Maven 工程接入](#maven-工程接入)
- [多模块工程](#多模块工程)
- [CI/CD 集成](#cicd-集成)
- [导入 API 平台](#导入-api-平台)
- [常见问题](#常见问题)

## Maven 工程接入

### 最小接入（推荐）

pom 中声明插件，无需其他改造：

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</plugin>
```

工程根目录放置 `static-openapi.json`（最小配置只需包路径）：

```json
{
  "projectName": "my-api",
  "packages": ["com.example.controller"]
}
```

```bash
mvn static-openapi:openapi
```

### 参数写在 pom 中

不想用配置文件时，可直接写在 `<configuration>`：

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <configuration>
        <projectName>my-api</projectName>
        <packages>
            <package>com.example.controller</package>
        </packages>
        <schemaNameStyle>pascal</schemaNameStyle>
        <errorResponseSchema>ResultModel</errorResponseSchema>
    </configuration>
</plugin>
```

### 绑定到生命周期（打包时自动生成）

让 `mvn package` 自动产出文档：

```xml
<plugin>
    <groupId>io.github.wanlianyida</groupId>
    <artifactId>static-openapi-maven-plugin</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <id>generate-openapi</id>
            <phase>package</phase>
            <goals>
                <goal>openapi</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

### 参数覆盖

临时覆盖配置（优先级：`-D` 参数 > `static-openapi.json` > pom `<configuration>` > 默认值）：

```bash
mvn static-openapi:openapi -DprojectName=demo-api -DschemaNameStyle=pascal -DerrorResponseSchema=ResultModel
```

## 多模块工程

工具自动递归发现所有模块的 `src/main/java`（跳过 `target` / `src/test` 等目录），**配置只写 Controller 所在包，不需要指定模块路径**：

```text
my-project/
├── pom.xml                        # reactor 根
├── static-openapi.json            # 放根目录
├── module-web/
│   └── src/main/java/com/my/web/...Controller
├── module-service/
│   └── src/main/java/com/my/service/...DTO（被接口引用，自动解析）
└── module-dto/
    └── src/main/java/com/my/dto/...DTO
```

```json
{
  "projectName": "my-project",
  "packages": ["com.my.web"]
}
```

要点：

- 在根目录执行 `mvn static-openapi:openapi`，只会在 reactor 根模块执行一次，子模块自动跳过，不会互相覆盖输出
- Controller 在 A 模块、DTO 在 B 模块没有关系，按简单类名在全部源码根中索引查找
- 跨模块继承同样支持：Controller 继承另一个模块里的 `BaseController`，其映射方法会被收集
- 输出位置为运行目录（根模块）的 `target/static-openapi/openapi.json`；如需为某个子模块单独生成文档，`cd` 到该子模块目录运行，或在子模块 pom 中绑定 execution

## CI/CD 集成

生成产物是确定性输出（文件排序遍历），可用于 CI 中对比接口是否变更。

### GitHub Actions

```yaml
name: openapi

on:
  pull_request:

jobs:
  generate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Generate OpenAPI spec
        run: mvn static-openapi:openapi -DerrorResponseSchema=ResultModel

      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: openapi.json
          path: target/static-openapi/openapi.json

      # 可选: 接口变更时在 PR 上评论提示 (以仓库中提交的 openapi.json 为基线 diff)
      - name: Detect spec changes
        run: |
          git add -N target/static-openapi/openapi.json
          git diff --exit-code -- target/static-openapi/openapi.json || \
            echo "::warning::OpenAPI spec changed, please review"
```

### GitLab CI

```yaml
openapi:
  stage: build
  image: maven:3.9-eclipse-temurin-17
  script:
    - mvn static-openapi:openapi
  artifacts:
    paths:
      - target/static-openapi/openapi.json
    expire_in: 1 week
```

### Jenkins（Pipeline）

```groovy
pipeline {
    agent any
    tools { maven 'maven-3.9'; jdk 'jdk-17' }
    stages {
        stage('OpenAPI') {
            steps {
                sh 'mvn static-openapi:openapi'
                archiveArtifacts artifacts: 'target/static-openapi/openapi.json'
            }
        }
    }
}
```

### 定时生成 + 归档（CLI 方式）

非 Maven 工程，或不想改动构建流程时，用 CLI 独立运行：

```bash
mvn package -DskipTests          # 一次性构建工具自身
java -jar static-openapi-cli/target/static-openapi-cli-1.0.0-SNAPSHOT.jar \
    -projectDir /path/to/your/project \
    -packages com.example.controller \
    -outPath /data/docs/my-api
```

## 导入 API 平台

### Apifox

1. 建议在配置中使用 `"schemaNameStyle": "pascal"` —— Apifox 对 schema key 做严格校验，默认的 `guillemet` 风格（`PagingInfo«UserVO»`）含非规范字符会被拒
2. 项目设置 → 导入数据 → OpenAPI/Swagger → 选择 `target/static-openapi/openapi.json`（或输入 CI 产物 URL）
3. 确认导入选项中的"覆盖模式"，重复导入时可按接口路径增量更新

### Swagger UI

把 `openapi.json` 放到静态资源目录，或直接指向本地/远程 URL：

```yaml
# spring boot 静态放置示例: src/main/resources/static/openapi.json
swagger-ui:
  urls:
    - url: /openapi.json
      name: my-api
```

本地快速预览（无需任何工程改造）：

```bash
docker run --rm -p 8080:8080 \
  -e SWAGGER_JSON=/openapi.json \
  -v $(pwd)/target/static-openapi/openapi.json:/openapi.json \
  swaggerapi/swagger-ui
```

### Postman

Import → 选择 `openapi.json` 文件（或 URL）→ Postman 自动生成 Collection，路径参数 / 请求体示例都会保留。

### 通用提示

- 产物为标准 OpenAPI 3.1 JSON；仅支持 3.0 的平台（部分老版本工具）可把配置中 `openapiVersion` 设为 `3.0.0`（schema 语义差异极小）
- 文档中不含 `servers`，导入平台后请自行配置环境地址

## 常见问题

**接口没有出现在文档里？**

按顺序排查：
1. `packages` 是否覆盖 Controller 所在包（递归匹配子包；不配置则扫描全部）
2. Controller 是否标注了 `@Controller` / `@RestController`（接口方式实现时，注解写在实现类或接口上均可）
3. 类/方法是否带了 `@Hidden` / `@ApiIgnore` 或 `hidden = true`
4. 方法是否有 Spring 映射注解；方法需为 public（继承来的方法 public/protected 均可）
5. 源码文件是否能通过 JavaParser 语法解析（严重语法错误的文件会被跳过并打 warn 日志）

**接口生成的 HTTP 方法不对（如一个接口出现 get/post/put 等多个）？**

`@RequestMapping` 未声明 `method` 属性时按 Spring 语义接受所有标准方法，会展开为多个 operation 并打 warn 日志。请显式声明 `@RequestMapping(value = "/x", method = RequestMethod.POST)` 或改用 `@PostMapping`。

**描述/注释没有生成？**

检查优先级：Swagger 注解存在时 Javadoc 不生效（注解优先）。若注解的对应字段为空（如 `@Operation` 只写了 summary 未写 description），description 会回落到 Javadoc —— 只有字段级完全缺失才兜底。

**泛型类生成了奇怪的 schema？**

- `Result«UserVO»`（guillemet 风格）导入 Apifox 校验失败 → 配置 `"schemaNameStyle": "pascal"`
- 业务泛型类按源码展开，请确认该类源码在扫描范围内
- 无源码类型退化为 `object`，把对应源码目录纳入 `projectDir` 即可精确展开

**Map 类型的文档怎么表示？**

`Map<String, OrderVO>` 输出为 `object` + `additionalProperties: {$ref: OrderVO}`，key 类型不体现（JSON 对象 key 恒为字符串）。

**如何隐藏某些接口或字段？**

方法/类上加 `@Hidden`（v3）或 `@ApiIgnore`（v2）；字段上加 `@Schema(hidden = true)` / `@ApiModelProperty(hidden = true)`。

**重复构建输出不一致？**

输出是确定性的（文件排序遍历）。若出现差异，通常来自源码本身的变更；确认没有并发运行两个生成进程写同一输出文件。

**和运行时工具（springdoc/smart-doc）的关系？**

本工具零依赖静态生成：不要求工程能编译/运行，不注入任何依赖，适合离线文档归档、CI 检查与导入第三方平台。需要在线调试/精确到运行时行为时，运行时工具更合适，两者可并存。
