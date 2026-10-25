package io.casehub.desiredstate.plugin.runtime.primitives;

import io.casehub.yaml.plugin.api.Action;
import io.casehub.yaml.plugin.api.Result;
import io.casehub.yaml.plugin.api.ServiceRegistry;

import java.util.Map;

public class ApprovalGatePrimitive implements Action {

    @Override
    public Result execute(Map<String, Object> parameters, ServiceRegistry services) {
        String reason = (String) parameters.getOrDefault("reason", "approval required");
        String approver = (String) parameters.get("approver");

        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("gateName", parameters.getOrDefault("name", "approval-gate"));
        output.put("reason", reason);
        output.put("status", "pending");
        if (approver != null) {
            output.put("approver", approver);
        }
        return Result.of(output);
    }
}
