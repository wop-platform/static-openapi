package io.github.wanlianyida.staticopenapi.model;

public class Info {
    private String title;
    private String description;
    private String version;
    private String termsOfService;

    public String getTitle() { return title; }
    public Info setTitle(String title) { this.title = title; return this; }

    public String getDescription() { return description; }
    public Info setDescription(String description) { this.description = description; return this; }

    public String getVersion() { return version; }
    public Info setVersion(String version) { this.version = version; return this; }

    public String getTermsOfService() { return termsOfService; }
    public Info setTermsOfService(String termsOfService) { this.termsOfService = termsOfService; return this; }
}
