package io.casehub.desiredstate.example.spatial.specs;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeType;
import io.casehub.desiredstate.example.spatial.terrain.TerrainType;

public record CellSpec(int row, int col, int height, TerrainType terrainType) implements NodeSpec {
    @Override
    public NodeType nodeType() {return SpatialNodeTypes.CELL;}
}
