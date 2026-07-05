package io.casehub.desiredstate.ras;

import io.casehub.ras.api.CorrelationKeyExtractor;
import io.cloudevents.CloudEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DesiredStateCorrelationKeyExtractor implements CorrelationKeyExtractor {

    private static final Logger LOG = Logger.getLogger(DesiredStateCorrelationKeyExtractor.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String extract(CloudEvent event) {
        if (event.getData() != null) {
            try {
                JsonNode root = MAPPER.readTree(event.getData().toBytes());
                JsonNode parent = root.get("parentNodeId");
                if (parent != null && !parent.isNull()) {
                    return parent.asText();
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to extract parentNodeId from CloudEvent data", e);
            }
        }
        String subject = event.getSubject();
        return subject != null ? subject : event.getId();
    }
}
