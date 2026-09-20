package io.github.wanlianyida.staticopenapi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.1 Schema Object (简化,支持 object / array / primitive / reference).
 */
public class Schema {
    private String name;
    private String type;            // object / array / string / integer / number / boolean
    private String format;          // date-time / int64 / ...
    private String description;
    private String ref;             // #/components/schemas/Foo
    private Map<String, Schema> properties = new LinkedHashMap<>();
    private List<String> required = new ArrayList<>();
    private Schema items;           // array items
    /** Map 值类型 schema (additionalProperties); null = 未声明 */
    private Schema additionalProperties;
    private String example;
    private List<String> enumValues = new ArrayList<>();   // 枚举可选值 (序列化为 "enum")

    public String getName() { return name; }
    public Schema setName(String name) { this.name = name; return this; }

    public String getType() { return type; }
    public Schema setType(String type) { this.type = type; return this; }

    public String getFormat() { return format; }
    public Schema setFormat(String format) { this.format = format; return this; }

    public String getDescription() { return description; }
    public Schema setDescription(String description) { this.description = description; return this; }

    public String getRef() { return ref; }
    public Schema setRef(String ref) { this.ref = ref; return this; }

    public Map<String, Schema> getProperties() { return properties; }
    public Schema addProperty(String name, Schema s) { properties.put(name, s); return this; }
    public Schema setProperties(Map<String, Schema> properties) { this.properties = properties; return this; }

    public List<String> getRequired() { return required; }
    public Schema addRequired(String name) { if (!required.contains(name)) required.add(name); return this; }
    public Schema setRequired(List<String> required) { this.required = required; return this; }

    public Schema getItems() { return items; }
    public Schema setItems(Schema items) { this.items = items; return this; }

    public Schema getAdditionalProperties() { return additionalProperties; }
    public Schema setAdditionalProperties(Schema additionalProperties) { this.additionalProperties = additionalProperties; return this; }

    public String getExample() { return example; }
    public Schema setExample(String example) { this.example = example; return this; }

    public List<String> getEnumValues() { return enumValues; }
    public Schema addEnumValue(String value) { enumValues.add(value); return this; }
    public Schema setEnumValues(List<String> enumValues) { this.enumValues = enumValues; return this; }
}
