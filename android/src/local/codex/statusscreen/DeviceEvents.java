package local.codex.statusscreen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DeviceEvents {
    private static final long OFFLINE_AFTER_MS = 9800;
    private long lastResponse = -1;
    private boolean connected;
    private boolean completionBaseline;
    private long newestCompletion;
    private Set<String> newestIds = new HashSet<>();

    void startMonitoring() {
        lastResponse = -1;
        connected = false;
        completionBaseline = false;
        newestCompletion = 0;
        newestIds.clear();
    }

    void received(long now) {
        lastResponse = now;
        connected = true;
    }

    boolean hasResponse() { return lastResponse >= 0; }

    boolean online(long now) {
        return hasResponse() && now - lastResponse < OFFLINE_AFTER_MS;
    }

    boolean takeOffline(long now) {
        if (!connected || online(now)) return false;
        connected = false;
        return true;
    }

    List<Completion> observeCompletions(List<Completion> entries) {
        long latest = newestCompletion;
        Set<String> latestIds = new HashSet<>(newestIds);
        Set<String> announced = new HashSet<>();
        List<Completion> fresh = new ArrayList<>();
        for (Completion entry : entries) {
            if (completionBaseline && (entry.at > newestCompletion
                    || entry.at == newestCompletion && !newestIds.contains(entry.id)) && announced.add(entry.id)) {
                fresh.add(entry);
            }
            if (entry.at > latest) {
                latest = entry.at;
                latestIds.clear();
            }
            if (entry.at == latest) latestIds.add(entry.id);
        }
        newestCompletion = latest;
        newestIds = latestIds;
        completionBaseline = true;
        return fresh;
    }

    static final class Completion {
        final String id, thread, title;
        final long at;

        Completion(String thread, String turn, String title, long at) {
            this.id = thread + "/" + turn;
            this.thread = thread;
            this.title = title;
            this.at = at;
        }
    }
}
