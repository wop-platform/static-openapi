package io.github.wanlianyida.staticopenapi.model;

import java.util.Objects;

/**
 * OpenAPI 3.1 Tag Object (顶层 tags 数组元素).
 */
public class Tag {
    private String name;
    private String description;

    public Tag() {}

    public Tag(String name) { this.name = name; }

    public Tag(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public String getName() { return name; }
    public Tag setName(String name) { this.name = name; return this; }

    public String getDescription() { return description; }
    public Tag setDescription(String description) { this.description = description; return this; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tag)) return false;
        Tag tag = (Tag) o;
        return Objects.equals(name, tag.name);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(name);
    }
}
