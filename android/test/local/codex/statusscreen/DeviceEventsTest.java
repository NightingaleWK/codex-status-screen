package local.codex.statusscreen;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class DeviceEventsTest {
    private static DeviceEvents.Completion done(String id, long at) {
        return new DeviceEvents.Completion("thread", id, id, at);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        DeviceEvents watch = new DeviceEvents();
        check(!watch.takeOffline(25000), "an initially unreachable host must not alert");
        watch.received(1000);
        check(!watch.takeOffline(10799), "a short interruption must not alert");
        check(watch.takeOffline(10800), "a connected host turning offline must alert");
        check(!watch.takeOffline(14000), "the same disconnection must not alert twice");
        watch.received(16000);
        check(watch.online(16000), "a later response re-arms monitoring");
        check(watch.takeOffline(25800), "a second disconnection must alert");

        check(watch.observeCompletions(Arrays.asList(done("old", 100), done("previous", 90))).isEmpty(),
                "the initial snapshot is a baseline");
        List<DeviceEvents.Completion> newTasks = watch.observeCompletions(
                Arrays.asList(done("latest", 170), done("recent", 150), done("same-time", 100), done("old", 100)));
        check(newTasks.size() == 3, "several new tasks between polls, including the same timestamp, must be detected");
        check(watch.observeCompletions(Arrays.asList(done("latest", 170), done("previous", 90))).isEmpty(),
                "repeated and reappearing records must not alert");
        check(watch.observeCompletions(Arrays.asList(done("latest", 170), done("tied", 170), done("tied", 170))).size() == 1,
                "distinct completions at the same time must be recognized without duplicates");
        watch.startMonitoring();
        check(!watch.takeOffline(40000), "returning to the dashboard must not produce a stale disconnect");
        check(watch.observeCompletions(Collections.singletonList(done("while-away", 220))).isEmpty(),
                "completed history must not ring on return");
        check(watch.observeCompletions(Collections.singletonList(done("new-after-return", 300))).size() == 1,
                "completion detection must continue after return");
        System.out.println("DeviceEvents tests passed");
    }
}
