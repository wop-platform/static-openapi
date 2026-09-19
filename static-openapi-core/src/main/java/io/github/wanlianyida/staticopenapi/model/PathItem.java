package io.github.wanlianyida.staticopenapi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.1 Path Item Object (简化,只保留本工具需要的字段).
 */
public class PathItem {
    private String summary;
    private String description;
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    public String getSummary() { return summary; }
    public PathItem setSummary(String summary) { this.summary = summary; return this; }

    public String getDescription() { return description; }
    public PathItem setDescription(String description) { this.description = description; return this; }

    public Map<String, Operation> getOperations() { return operations; }
    public PathItem addOperation(String method, Operation op) {
        operations.put(method.toLowerCase(), op);
        return this;
    }
}
