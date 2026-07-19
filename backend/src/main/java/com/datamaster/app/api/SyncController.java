package com.datamaster.app.api;

import com.datamaster.app.service.SyncService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @GetMapping("/status")
    public SyncService.SyncStatus status() {
        return sync.status();
    }

    @PutMapping("/connect")
    public SyncService.SyncStatus connect(@RequestBody SyncTokenRequest request) {
        return sync.connect(request == null ? null : request.token());
    }

    @PostMapping("/pull")
    public SyncService.SyncActionResult pull() {
        return sync.pull();
    }

    @PostMapping("/push")
    public SyncService.SyncActionResult push() {
        return sync.push();
    }

    @DeleteMapping("/disconnect")
    public SyncService.SyncStatus disconnect() {
        return sync.disconnect();
    }

    public record SyncTokenRequest(String token) {
    }
}
