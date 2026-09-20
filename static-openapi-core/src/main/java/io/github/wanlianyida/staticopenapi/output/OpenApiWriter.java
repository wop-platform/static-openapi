package io.github.wanlianyida.staticopenapi.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.wanlianyida.staticopenapi.config.GeneratorConfig;
import io.github.wanlianyida.staticopenapi.model.Components;
import io.github.wanlianyida.staticopenapi.model.Example;
import io.github.wanlianyida.staticopenapi.model.Info;
import io.github.wanlianyida.staticopenapi.model.MediaType;
import io.github.wanlianyida.staticopenapi.model.OpenApiDocument;
import io.github.wanlianyida.staticopenapi.model.Operation;
import io.github.wanlianyida.staticopenapi.model.Parameter;
import io.github.wanlianyida.staticopenapi.model.PathItem;
import io.github.wanlianyida.staticopenapi.model.RequestBody;
import io.github.wanlianyida.staticopenapi.model.Response;
import io.github.wanlianyida.staticopenapi.model.Schema;
import io.github.wanlianyida.staticopenapi.model.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 序列化为 OpenAPI 3.x JSON.
 */
public class OpenApiWriter {

    private final ObjectMapper mapper = new ObjectMapper();

    public Path write(OpenApiDocument doc, GeneratorConfig config) throws IOException {
        Path outDir = Paths.get(config.getOutPath());
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve("openapi.json");

        ObjectMapper local = mapper.copy();
        if (config.isPrettyPrint()) {
            local.enable(SerializationFeature.INDENT_OUTPUT);
        }
        local.writeValue(outFile.toFile(), toMap(doc));
        return outFile;
    }

    private Map<String, Object> toMap(OpenApiDocument doc) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", doc.getOpenapi());

        root.put("info", infoToMap(doc.getInfo()));
        root.put("servers", Collections.emptyList());
        root.put("tags", tagsToMap(doc.getTags()));
        root.put("paths", pathsToMap(doc.getPaths()));
        root.put("components", componentsToMap(doc.getComponents()));
        return root;
    }

    private Map<String, Object> infoToMap(Info info) {
        if (info == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        if (info.getTitle() != null) map.put("title", info.getTitle());
        if (info.getDescription() != null) map.put("description", info.getDescription());
        if (info.getVersion() != null) map.put("version", info.getVersion());
        if (info.getTermsOfService() != null) map.put("termsOfService", info.getTermsOfService());
        return map;
    }

    private List<Map<String, Object>> tagsToMap(List<Tag> tags) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tag t : tags) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", t.getName());
            if (t.getDescription() != null && !t.getDescription().isEmpty()) {
                map.put("description", t.getDescription());
            }
            list.add(map);
        }
        return list;
    }

    private Map<String, Object> pathsToMap(Map<String, PathItem> paths) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, PathItem> e : paths.entrySet()) {
            Map<String, Object> pathItem = new LinkedHashMap<>();
            if (e.getValue().getSummary() != null) pathItem.put("summary", e.getValue().getSummary());
            if (e.getValue().getDescription() != null) pathItem.put("description", e.getValue().getDescription());
            for (Map.Entry<String, Operation> op : e.getValue().getOperations().entrySet()) {
                pathItem.put(op.getKey(), operationToMap(op.getValue()));
            }
            map.put(e.getKey(), pathItem);
        }
        return map;
    }

    private Map<String, Object> operationToMap(Operation op) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (op.getSummary() != null && !op.getSummary().isEmpty()) map.put("summary", op.getSummary());
        if (op.getDescription() != null && !op.getDescription().isEmpty()) map.put("description", op.getDescription());
        if (!op.getTags().isEmpty()) map.put("tags", op.getTags());
        if (op.isDeprecated()) map.put("deprecated", true);
        if (op.getOperationId() != null) map.put("operationId", op.getOperationId());
        if (!op.getParameters().isEmpty()) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (Parameter p : op.getParameters()) params.add(parameterToMap(p));
            map.put("parameters", params);
        }
        if (op.getRequestBody() != null) map.put("requestBody", requestBodyToMap(op.getRequestBody()));
        Map<String, Object> responses = new LinkedHashMap<>();
        for (Map.Entry<String, Response> e : op.getResponses().entrySet()) {
            responses.put(e.getKey(), responseToMap(e.getValue()));
        }
        map.put("responses", responses);
        return map;
    }

    private Map<String, Object> parameterToMap(Parameter p) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", p.getName());
        map.put("in", p.getIn());
        if (p.getDescription() != null && !p.getDescription().isEmpty()) map.put("description", p.getDescription());
        if (p.isRequired()) map.put("required", true);
        map.put("schema", p.getSchema() != null
                ? schemaToMap(p.getSchema())
                : Collections.singletonMap("type", "object"));
        if (p.getExample() != null) map.put("example", p.getExample());
        return map;
    }

    private Map<String, Object> requestBodyToMap(RequestBody rb) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (rb.getDescription() != null) map.put("description", rb.getDescription());
        map.put("required", rb.isRequired());
        map.put("content", mediaTypeMapToMap(rb.getContent()));
        return map;
    }

    private Map<String, Object> responseToMap(Response r) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (r.getDescription() != null) map.put("description", r.getDescription());
        if (!r.getContent().isEmpty()) map.put("content", mediaTypeMapToMap(r.getContent()));
        return map;
    }

    private Map<String, Object> mediaTypeMapToMap(Map<String, MediaType> content) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            map.put(e.getKey(), mediaTypeToMap(e.getValue()));
        }
        return map;
    }

    private Map<String, Object> mediaTypeToMap(MediaType mt) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema", mt.getSchema() != null
                ? schemaToMap(mt.getSchema())
                : Collections.singletonMap("type", "object"));
        if (mt.getExample() != null) map.put("example", mt.getExample());
        if (!mt.getExamples().isEmpty()) {
            Map<String, Object> examplesMap = new LinkedHashMap<>();
            for (Map.Entry<String, Example> e : mt.getExamples().entrySet()) {
                examplesMap.putAll(examplesToMap(e.getKey(), e.getValue()));
            }
            map.put("examples", examplesMap);
        }
        return map;
    }

    private Map<String, Object> examplesToMap(String name, Example ex) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (ex.getSummary() != null) map.put("summary", ex.getSummary());
        if (ex.getDescription() != null) map.put("description", ex.getDescription());
        if (ex.getValue() != null) map.put("value", ex.getValue());
        return Collections.singletonMap(name, map);
    }

    private Map<String, Object> componentsToMap(Components components) {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> schemas = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : components.getSchemas().entrySet()) {
            schemas.put(e.getKey(), schemaToMap(e.getValue()));
        }
        map.put("schemas", schemas);
        return map;
    }

    private Map<String, Object> schemaToMap(Schema s) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (s.getRef() != null) {
            map.put("$ref", s.getRef());
            // OpenAPI 3.1 允许 $ref 同级 siblings (3.0 不允许), 保留引用类型上的描述/示例
            if (s.getDescription() != null && !s.getDescription().isEmpty()) {
                map.put("description", s.getDescription());
            }
            if (s.getExample() != null) map.put("example", s.getExample());
            return map;
        }
        if (s.getType() != null) map.put("type", s.getType());
        if (s.getFormat() != null) map.put("format", s.getFormat());
        if (!s.getEnumValues().isEmpty()) map.put("enum", s.getEnumValues());
        if (s.getDescription() != null && !s.getDescription().isEmpty()) map.put("description", s.getDescription());
        if (s.getExample() != null) map.put("example", s.getExample());
        if (!s.getProperties().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            for (Map.Entry<String, Schema> e : s.getProperties().entrySet()) {
                properties.put(e.getKey(), schemaToMap(e.getValue()));
            }
            map.put("properties", properties);
        }
        if (!s.getRequired().isEmpty()) map.put("required", s.getRequired());
        if (s.getItems() != null) map.put("items", schemaToMap(s.getItems()));
        if (s.getAdditionalProperties() != null) {
            map.put("additionalProperties", schemaToMap(s.getAdditionalProperties()));
        }
        return map;
    }
}
