package io.casehub.desiredstate.example.pipeline.annotated;

import io.casehub.desiredstate.annotations.DependsOn;
import io.casehub.desiredstate.annotations.DesiredState;
import io.casehub.desiredstate.annotations.FaultPolicyDef;
import io.casehub.desiredstate.annotations.Node;
import io.casehub.desiredstate.annotations.Tier;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.FaultEvent;
import io.casehub.desiredstate.api.HumanGating;
import io.casehub.desiredstate.example.pipeline.AiReviewSpec;
import io.casehub.desiredstate.example.pipeline.CleanserSpec;
import io.casehub.desiredstate.example.pipeline.DataSourceSpec;
import io.casehub.desiredstate.example.pipeline.EnricherSpec;
import io.casehub.desiredstate.example.pipeline.HumanReviewSpec;
import io.casehub.desiredstate.example.pipeline.IngestionSpec;
import io.casehub.desiredstate.example.pipeline.SchemaSpec;
import io.casehub.desiredstate.example.pipeline.SinkSpec;
import io.casehub.desiredstate.example.pipeline.TransformerSpec;
import io.casehub.desiredstate.example.pipeline.ValidatorSpec;
import java.util.List;

@DesiredState(namespace = "pipeline", name = "medallion")
@FaultPolicyDef(
        faultTypes = {"PROVISION_FAILED"},
        nodeTypes = {"transformer", "sink"},
        tiers = {
                @Tier(threshold = 3, review = "createAiReview"),
                @Tier(threshold = 5, review = "createHumanReview")
        }
)
public interface MedallionPipeline {

    // --- Bronze layer ---

    @Node("csv-source")
    default DataSourceSpec csvSource() {
        return new DataSourceSpec("customers", "CSV", "s3://data/customers.csv");
    }

    @Node("customer-schema")
    default SchemaSpec customerSchema() {
        return new SchemaSpec("customer-schema", List.of("id", "name", "email"), 1);
    }

    @Node("csv-ingest")
    @DependsOn("csv-source")
    default IngestionSpec csvIngestion() {
        return new IngestionSpec("csv-source", 1000, "CSV");
    }

    // --- Silver layer ---

    @Node("dedup-cleanser")
    @DependsOn({"csv-ingest", "customer-schema"})
    default CleanserSpec dedupCleanser() {
        return new CleanserSpec(List.of("dedup", "nullcheck"), true, "DROP");
    }

    @Node("geo-enricher")
    @DependsOn("dedup-cleanser")
    default EnricherSpec geoEnricher() {
        return new EnricherSpec("geo-lookup", List.of("address"), List.of("lat", "lon"));
    }

    @Node("quality-validator")
    @DependsOn({"geo-enricher", "customer-schema"})
    default ValidatorSpec qualityValidator() {
        return new ValidatorSpec("customer-schema", 0.95, true);
    }

    // --- Gold layer ---

    @Node(value = "aggregate-tx", humanGating = HumanGating.PROVISION_ONLY)
    @DependsOn("quality-validator")
    default TransformerSpec aggregateTransformer() {
        return new TransformerSpec(List.of("sum", "avg"), List.of(), "parquet", true);
    }

    @Node(value = "warehouse-sink", humanGating = HumanGating.PROVISION_ONLY)
    @DependsOn("aggregate-tx")
    default SinkSpec warehouseSink() {
        return new SinkSpec("s3://warehouse/gold/", "parquet", List.of("date"), true);
    }

    // --- Fault policy review spec factories ---

    default AiReviewSpec createAiReview(FaultEvent event, DesiredStateGraph graph) {
        return new AiReviewSpec(event.node(), event.detail());
    }

    default HumanReviewSpec createHumanReview(FaultEvent event, DesiredStateGraph graph) {
        return new HumanReviewSpec(event.node(), event.detail(), "Requires manual review");
    }
}
