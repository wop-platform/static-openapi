package io.github.wanlianyida.staticopenapi.model;

/**
 * OpenAPI 3.1 Parameter Object (简化).
 */
public class Parameter {
    private String name;
    private String in;   // query / path / header / cookie
    private String description;
    private boolean required;
    /** 完整 schema (ref / 内联 array / primitive 均可) */
    private Schema schema;
    private String example;

    public String getName() { return name; }
    public Parameter setName(String name) { this.name = name; return this; }

    public String getIn() { return in; }
    public Parameter setIn(String in) { this.in = in; return this; }

    public String getDescription() { return description; }
    public Parameter setDescription(String description) { this.description = description; return this; }

    public boolean isRequired() { return required; }
    public Parameter setRequired(boolean required) { this.required = required; return this; }

    public Schema getSchema() { return schema; }
    public Parameter setSchema(Schema schema) { this.schema = schema; return this; }

    public String getExample() { return example; }
    public Parameter setExample(String example) { this.example = example; return this; }
}
