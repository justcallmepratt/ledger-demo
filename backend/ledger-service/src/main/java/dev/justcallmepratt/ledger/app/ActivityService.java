package dev.justcallmepratt.ledger.app;

import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import dev.justcallmepratt.ledger.persistence.ActivityRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivityService {

    private final ActivityRepository activity;

    public ActivityService(ActivityRepository activity) {
        this.activity = activity;
    }

    /** Newest first. */
    public List<ActivityEntry> latest(int limit) {
        return activity.latest(Cursors.checkLimit(limit));
    }
}
