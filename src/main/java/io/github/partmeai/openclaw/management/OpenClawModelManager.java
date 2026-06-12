package io.github.partmeai.openclaw.management;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.partmeai.openclaw.api.OpenClawApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages OpenClaw agent-target models via the Gateway's {@code GET /v1/models} endpoint.
 * <p>
 * OpenClaw uses agent-target routing rather than raw provider model IDs.
 * The {@code /v1/models} endpoint returns top-level agent targets only:
 * {@code openclaw}, {@code openclaw/default}, and {@code openclaw/<agentId>} entries.
 * Sub-agents and backend provider models are internal topology and do not
 * appear in the model list.
 * <p>
 * Use the returned ids directly as OpenAI {@code model} values in chat
 * completion requests.
 *
 * @author Loong Wan
 * @see <a href="https://docs.openclaw.ai/gateway/openai-http-api#model-list-and-agent-routing">Model list and agent routing</a>
 */
public class OpenClawModelManager {

	private static final Logger logger = LoggerFactory.getLogger(OpenClawModelManager.class);

	private final OpenClawApi api;

	public OpenClawModelManager(OpenClawApi api) {
		this.api = api;
	}

	/**
	 * List all available agent targets from the Gateway.
	 * <p>
	 * Returns top-level agent-target ids such as {@code openclaw}, {@code openclaw/default},
	 * and {@code openclaw/<agentId>} entries. These can be used directly as OpenAI
	 * {@code model} values in chat completion requests.
	 * <p>
	 * Backend provider models and sub-agents are NOT included — they remain
	 * internal execution topology.
	 *
	 * @return list of agent-target model ids
	 */
	public List<String> listAgentTargets() {
		try {
			OpenClawApi.ListModelResponse response = this.api.listModels();
			if (response.data() == null) {
				return List.of();
			}
			return response.data().stream()
				.map(OpenClawApi.ModelData::id)
				.collect(Collectors.toList());
		}
		catch (Exception e) {
			logger.warn("Failed to list agent targets from OpenClaw Gateway: {}", e.getMessage());
			return List.of();
		}
	}

	/**
	 * Get details about a specific agent target.
	 *
	 * @param modelId the agent-target id (e.g. "openclaw/default")
	 * @return the model response, or empty if not found
	 */
	public Optional<OpenClawApi.ModelResponse> getAgentTarget(String modelId) {
		try {
			return Optional.ofNullable(this.api.getModel(modelId));
		}
		catch (Exception e) {
			logger.warn("Failed to get agent target '{}': {}", modelId, e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Check whether a specific agent target is available on the Gateway.
	 *
	 * @param modelId the agent-target id to check
	 * @return true if the target is listed by the Gateway
	 */
	public boolean isAgentTargetAvailable(String modelId) {
		return listAgentTargets().contains(modelId);
	}

	/**
	 * Get the default agent target id. This is always {@code openclaw/default},
	 * which is the stable alias for the configured default agent.
	 *
	 * @return "openclaw/default"
	 */
	public String getDefaultAgentTarget() {
		return "openclaw/default";
	}
}
