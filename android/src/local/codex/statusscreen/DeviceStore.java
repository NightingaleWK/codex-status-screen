package local.codex.statusscreen;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class DeviceStore {
    final List<Device> devices = new ArrayList<>();
    private final SharedPreferences preferences;

    DeviceStore(SharedPreferences preferences) {
        this.preferences = preferences;
        try {
            JSONArray saved = new JSONArray(preferences.getString("devices", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject raw = saved.optJSONObject(i);
                if (raw != null) devices.add(new Device(raw));
            }
        } catch (JSONException ignored) {}
    }

    Device find(String id) {
        for (Device device : devices) if (device.id.equals(id)) return device;
        return null;
    }

    Device put(CollectorClient.HostPair pair) {
        Device old = find(pair.id);
        Device device = new Device(pair);
        if (old != null) {
            device.name = old.name;
            device.snapshot = old.snapshot;
            devices.set(devices.indexOf(old), device);
        } else {
            devices.add(device);
        }
        save();
        return device;
    }

    void save() {
        JSONArray array = new JSONArray();
        for (Device device : devices) array.put(device.serialize());
        preferences.edit().putString("devices", array.toString()).apply();
    }

    static final class Device {
        final String id, pin, token;
        String name, endpoint, discovered;
        JSONObject snapshot;
        final DeviceEvents events = new DeviceEvents();
        String lastNetworkError;
        boolean polling, checkingAddress;
        int endpointRevision;

        Device(CollectorClient.HostPair pair) {
            id = pair.id;
            pin = pair.pin;
            token = pair.token;
            name = pair.name;
            endpoint = pair.endpoint;
        }

        Device(JSONObject data) throws JSONException {
            id = data.getString("id");
            pin = data.getString("pin");
            token = data.getString("token");
            name = data.getString("name");
            endpoint = data.getString("endpoint");
            snapshot = data.optJSONObject("snapshot");
        }

        boolean online(long now) {
            return events.online(now);
        }

        JSONObject serialize() {
            try {
                return new JSONObject().put("id", id).put("name", name).put("endpoint", endpoint)
                        .put("pin", pin).put("token", token).put("snapshot", snapshot);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
