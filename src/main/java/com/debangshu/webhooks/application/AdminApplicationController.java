package com.debangshu.webhooks.application;

import com.debangshu.webhooks.application.ApplicationDtos.ApplicationKeyResponse;
import com.debangshu.webhooks.application.ApplicationDtos.ApplicationResponse;
import com.debangshu.webhooks.application.ApplicationDtos.CreateApplicationRequest;
import com.debangshu.webhooks.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/admin/applications")
@Tag(name = "Admin: applications", description = "Needs the admin key")
public class AdminApplicationController {

    private final ApplicationService applicationService;

    public AdminApplicationController(ApplicationService applicationService) {
        this.applicationService = applicationService;
    }

    @PostMapping
    @Operation(summary = "Create an application. The response contains its API key, which is only shown this once.")
    public ResponseEntity<ApplicationKeyResponse> create(@Valid @RequestBody CreateApplicationRequest request) {
        ApplicationKeyResponse created = applicationService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/applications/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List applications, newest first")
    public PageResponse<ApplicationResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return applicationService.list(PageResponse.newestFirst(page, size));
    }

    @GetMapping("/{id}")
    public ApplicationResponse get(@PathVariable long id) {
        return applicationService.get(id);
    }

    @PostMapping("/{id}/api-key/rotate")
    @Operation(summary = "Replace the application's API key. The old key stops working immediately.")
    public ApplicationKeyResponse rotateApiKey(@PathVariable long id) {
        return applicationService.rotateApiKey(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete an application with all its endpoints, events and deliveries")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        applicationService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
