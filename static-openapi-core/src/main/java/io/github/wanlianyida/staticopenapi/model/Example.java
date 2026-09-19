package io.github.wanlianyida.staticopenapi.model;

/**
 * OpenAPI 3.1 Example Object (简化).
 */
public class Example {
    private String summary;
    private String description;
    private String value;

    public String getSummary() { return summary; }
    public Example setSummary(String summary) { this.summary = summary; return this; }

    public String getDescription() { return description; }
    public Example setDescription(String description) { this.description = description; return this; }

    public String getValue() { return value; }
    public Example setValue(String value) { this.value = value; return this; }
}
