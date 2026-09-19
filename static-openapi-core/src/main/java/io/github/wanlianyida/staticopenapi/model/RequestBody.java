package io.github.wanlianyida.staticopenapi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.1 RequestBody Object (简化).
 */
public class RequestBody {
    private String description;
    private boolean required;
    private Map<String, MediaType> content = new LinkedHashMap<>();

    public String getDescription() { return description; }
    public RequestBody setDescription(String description) { this.description = description; return this; }

    public boolean isRequired() { return required; }
    public RequestBody setRequired(boolean required) { this.required = required; return this; }

    public Map<String, MediaType> getContent() { return content; }
    public RequestBody addContent(String type, MediaType mt) { content.put(type, mt); return this; }
}
