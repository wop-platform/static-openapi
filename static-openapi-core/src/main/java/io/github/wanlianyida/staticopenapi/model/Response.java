package io.github.wanlianyida.staticopenapi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.1 Response Object (简化).
 */
public class Response {
    private String description;
    private Map<String, MediaType> content = new LinkedHashMap<>();

    public String getDescription() { return description; }
    public Response setDescription(String description) { this.description = description; return this; }

    public Map<String, MediaType> getContent() { return content; }
    public Response addContent(String type, MediaType mt) { content.put(type, mt); return this; }
}
