package io.github.partmeai.openclaw.management;

public record ModelManagementOptions(PullModelStrategy pullModelStrategy) {
    public static ModelManagementOptions defaults() {
        return new ModelManagementOptions(PullModelStrategy.NEVER);
    }
}
