package io.github.partmeai.openclaw.management;

import io.github.partmeai.openclaw.api.OpenClawApi;

public class OpenClawModelManager {
    private final OpenClawApi api;
    private final ModelManagementOptions options;

    public OpenClawModelManager(OpenClawApi api, ModelManagementOptions options) {
        this.api = api;
        this.options = options;
    }

    public void pullModel(String model, PullModelStrategy strategy) {
        // No-op: OpenClaw Gateway manages models server-side
    }
}
