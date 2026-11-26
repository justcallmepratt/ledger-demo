package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.api.dto.ActivityItemResponse;
import dev.justcallmepratt.ledger.api.dto.Views;
import dev.justcallmepratt.ledger.app.ActivityService;
import dev.justcallmepratt.ledger.events.ActivityBroadcaster;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/activity")
@Tag(name = "Activity")
public class ActivityController {

    private final ActivityService service;
    private final ActivityBroadcaster broadcaster;

    public ActivityController(ActivityService service, ActivityBroadcaster broadcaster) {
        this.service = service;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    @Operation(summary = "Latest activity, newest first")
    public List<ActivityItemResponse> latest(@RequestParam(name = "limit", defaultValue = "50") int limit) {
        return service.latest(limit).stream().map(Views::activity).toList();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live activity as server-sent events",
            description = "Event name `activity`, id = CloudEvent id. Heartbeat comment every 15 s. "
                    + "Reconnect with Last-Event-ID to replay missed entries.")
    public SseEmitter stream(@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return broadcaster.subscribe(lastEventId);
    }
}
