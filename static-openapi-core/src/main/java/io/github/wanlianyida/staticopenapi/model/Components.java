package io.github.wanlianyida.staticopenapi.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenAPI 3.1 Components Object.
 */
public class Components {
    private final Map<String, Schema> schemas = new LinkedHashMap<>();

    public Map<String, Schema> getSchemas() { return schemas; }
    public Components addSchema(String name, Schema schema) { schemas.put(name, schema); return this; }
}
