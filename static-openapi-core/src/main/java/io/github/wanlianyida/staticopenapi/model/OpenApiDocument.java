package io.github.wanlianyida.staticopenapi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.1 root object (top-level).
 */
public class OpenApiDocument {
    private String openapi = "3.1.0";
    private Info info;
    private final List<Tag> tags = new ArrayList<>();
    private final Map<String, PathItem> paths = new LinkedHashMap<>();
    private Components components = new Components();

    public String getOpenapi() { return openapi; }
    public OpenApiDocument setOpenapi(String openapi) { this.openapi = openapi; return this; }

    public Info getInfo() { return info; }
    public OpenApiDocument setInfo(Info info) { this.info = info; return this; }

    public List<Tag> getTags() { return tags; }
    public OpenApiDocument addTag(Tag tag) { tags.add(tag); return this; }

    public Map<String, PathItem> getPaths() { return paths; }
    public OpenApiDocument addPath(String path, PathItem item) { paths.put(path, item); return this; }

    public Components getComponents() { return components; }
    public OpenApiDocument setComponents(Components components) { this.components = components; return this; }
}
