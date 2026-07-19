package com.datamaster.app.api;

import com.datamaster.app.domain.WorkspaceCurrentResponse;
import com.datamaster.app.service.WorkspacePersistenceService;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {
    private final WorkspacePersistenceService workspace;
    private final ObjectMapper objectMapper;

    public WorkspaceController(WorkspacePersistenceService workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/current")
    public WorkspaceCurrentResponse current() {
        return workspace.current();
    }

    @DeleteMapping("/current")
    public WorkspaceCurrentResponse clear() {
        workspace.clear();
        return WorkspaceCurrentResponse.empty();
    }

    @DeleteMapping("/sources/{sourceId}")
    public WorkspaceCurrentResponse deleteSource(@PathVariable String sourceId) {
        return workspace.deleteSource(sourceId);
    }

    /** Desktop/local append path.  The legacy analysis upload endpoint intentionally retains its
     * replacement behavior so hosted web clients do not change semantics without opting in. */
    @PostMapping(path = "/sources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkspaceCurrentResponse appendSources(
            @RequestPart("files") MultipartFile[] files,
            @RequestPart(value = "mapping", required = false) String mappingJson) {
        return workspace.append(files, parseMapping(mappingJson));
    }

    @PostMapping("/current/reanalyze")
    public WorkspaceCurrentResponse reanalyze(@RequestBody(required = false) Map<String, String> mapping) {
        return workspace.reanalyze(mapping == null ? Map.of() : mapping);
    }

    private Map<String, String> parseMapping(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(mappingJson, new TypeReference<>() { });
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("字段映射 JSON 格式无效，请重新选择字段后再试", ex);
        }
    }
}
