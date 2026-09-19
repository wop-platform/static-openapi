package io.github.wanlianyida.staticopenapi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.1 MediaType Object (简化).
 */
public class MediaType {
    /** 完整 schema (ref / 内联 array / primitive 均可) */
    private Schema schema;
    private String example;
    private final Map<String, Example> examples = new LinkedHashMap<>();

    public Schema getSchema() { return schema; }
    public MediaType setSchema(Schema schema) { this.schema = schema; return this; }

    public String getExample() { return example; }
    public MediaType setExample(String example) { this.example = example; return this; }

    public Map<String, Example> getExamples() { return examples; }
    public MediaType addExample(String name, Example ex) { examples.put(name, ex); return this; }
}
