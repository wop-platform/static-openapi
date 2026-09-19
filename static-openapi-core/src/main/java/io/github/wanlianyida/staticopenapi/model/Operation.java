package io.github.wanlianyida.staticopenapi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.1 Operation Object (简化,只保留本工具需要的字段).
 */
public class Operation {
    private String summary;
    private String description;
    private String operationId;
    private final List<String> tags = new ArrayList<>();
    private boolean deprecated;
    private final List<Parameter> parameters = new ArrayList<>();
    private RequestBody requestBody;
    /** response code → Response: 支持 "200"/"default"/"2XX" 等 range code */
    private final Map<String, Response> responses = new LinkedHashMap<>();

    public String getSummary() { return summary; }
    public Operation setSummary(String summary) { this.summary = summary; return this; }

    public String getDescription() { return description; }
    public Operation setDescription(String description) { this.description = description; return this; }

    public String getOperationId() { return operationId; }
    public Operation setOperationId(String operationId) { this.operationId = operationId; return this; }

    public List<String> getTags() { return tags; }
    public Operation addTag(String tag) { tags.add(tag); return this; }
    public Operation setTags(List<String> tags) { this.tags.clear(); this.tags.addAll(tags); return this; }

    public boolean isDeprecated() { return deprecated; }
    public Operation setDeprecated(boolean deprecated) { this.deprecated = deprecated; return this; }

    public List<Parameter> getParameters() { return parameters; }
    public Operation addParameter(Parameter p) { parameters.add(p); return this; }

    public RequestBody getRequestBody() { return requestBody; }
    public Operation setRequestBody(RequestBody requestBody) { this.requestBody = requestBody; return this; }

    public Map<String, Response> getResponses() { return responses; }
    public Operation addResponse(String code, Response r) { responses.put(code, r); return this; }
    /** 便捷方法: 接受 int code (常用情况),内部转 String. */
    public Operation addResponse(int code, Response r) { return addResponse(String.valueOf(code), r); }
}
