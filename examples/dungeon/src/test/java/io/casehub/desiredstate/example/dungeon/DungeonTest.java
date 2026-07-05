package io.casehub.desiredstate.example.dungeon;

import io.casehub.desiredstate.api.*;
import io.casehub.desiredstate.runtime.DefaultDesiredStateGraphFactory;
import io.casehub.desiredstate.runtime.TransitionPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for the Nefarious Dungeons domain model and SPI implementations.
 * Tests are manual (not @QuarkusTest) to demonstrate direct API usage.
 */
class DungeonTest {

    private DungeonWorld world;
    private DesiredStateGraphFactory factory;
    private DungeonGoalCompiler compiler;
    private GoblinProvisioner provisioner;
    private DungeonActualStateAdapter adapter;
    private TransitionPlanner planner;

    @BeforeEach
    void setUp() {
        world = new DungeonWorld();
        factory = new DefaultDesiredStateGraphFactory();
        compiler = new DungeonGoalCompiler();
        provisioner = new GoblinProvisioner(world);
        adapter = new DungeonActualStateAdapter(world);
        planner = new TransitionPlanner();
    }

    @Test
    void buildBasicDungeon() {
        // Create a basic dungeon: lair, hatchery, library + dark-wizard (depends on library)
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("lair", "Main lair with treasure hoard", 100)
            .room("hatchery", "Dragon egg incubation chamber", 50)
            .room("library", "Ancient tomes of dark magic", 75)
            .creature("dark-wizard", "wizard", 10, "library")
            .build();

        // Compile to graph
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Verify graph structure
        assertEquals(4, graph.nodes().size(), "Should have 4 nodes");
        assertTrue(graph.nodes().containsKey(NodeId.of("lair")));
        assertTrue(graph.nodes().containsKey(NodeId.of("hatchery")));
        assertTrue(graph.nodes().containsKey(NodeId.of("library")));
        assertTrue(graph.nodes().containsKey(NodeId.of("dark-wizard")));

        // Verify dependency: dark-wizard depends on library
        assertTrue(graph.dependenciesOf(NodeId.of("dark-wizard")).contains(NodeId.of("library")),
            "Dark wizard should depend on library");

        // Read actual state (all should be ABSENT initially)
        ActualState actual = adapter.readActual(graph, "default");
        assertEquals(NodeStatus.ABSENT, actual.statusOf(NodeId.of("lair")).orElse(null));

        // Plan transitions
        TransitionPlan plan = planner.plan(graph, actual);
        assertEquals(0, plan.removals().size(), "No removals needed for empty dungeon");
        assertEquals(4, plan.additions().size(), "Should add all 4 nodes");

        // Verify ordering: roots before leaves
        List<OrderedStep> additions = plan.additions();
        // Rooms (roots) should come before dark-wizard (depends on library)
        int libraryIndex = -1;
        int wizardIndex = -1;
        for (int i = 0; i < additions.size(); i++) {
            if (additions.get(i).node().id().equals(NodeId.of("library"))) {
                libraryIndex = i;
            }
            if (additions.get(i).node().id().equals(NodeId.of("dark-wizard"))) {
                wizardIndex = i;
            }
        }
        assertTrue(libraryIndex < wizardIndex, "Library must be provisioned before dark-wizard");

        // Execute provisioning manually
        for (OrderedStep step : additions) {
            ProvisionContext context = new ProvisionContext("test-tenancy", graph);
            ProvisionResult result = provisioner.provision(step.node(), context);
            assertInstanceOf(ProvisionResult.Success.class, result,
                "Provisioning " + step.node().id() + " should succeed");
        }

        // Verify all nodes are now BUILT/PRESENT in world
        assertEquals(DungeonWorld.State.BUILT, world.roomState(NodeId.of("lair")));
        assertEquals(DungeonWorld.State.BUILT, world.roomState(NodeId.of("hatchery")));
        assertEquals(DungeonWorld.State.BUILT, world.roomState(NodeId.of("library")));
        assertEquals(DungeonWorld.State.PRESENT, world.creatureState(NodeId.of("dark-wizard")));
    }

    @Test
    void heroRaid_destroysRoom() {
        // Set library as BUILT, dark-wizard as PRESENT
        world.setRoom(NodeId.of("library"), DungeonWorld.State.BUILT);
        world.setCreature(NodeId.of("dark-wizard"), DungeonWorld.State.PRESENT);

        // Simulate hero raid that destroys the library
        world.destroyRoom(NodeId.of("library"));

        // Verify state
        assertEquals(DungeonWorld.State.DESTROYED, world.roomState(NodeId.of("library")));
    }

    @Test
    void heroRaidFaultPolicy_rebuildsRoom() {
        // Create graph with library node
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("library", "Ancient tomes of dark magic", 75)
            .build();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Create fault policy
        HeroRaidFaultPolicy policy = new HeroRaidFaultPolicy();

        // Simulate NODE_DESTROYED fault for library
        FaultEvent fault = new FaultEvent(
            NodeId.of("library"),
            FaultType.NODE_DESTROYED,
            "Heroes destroyed the library"
        );

        // Policy should return AddNode mutation
        List<GraphMutation> mutations = policy.onFault(fault, graph, new ActualState(Map.of()));
        assertEquals(1, mutations.size(), "Should return one mutation");
        assertInstanceOf(GraphMutation.AddNode.class, mutations.get(0));

        GraphMutation.AddNode addNode = (GraphMutation.AddNode) mutations.get(0);
        assertEquals(NodeId.of("library"), addNode.node().id());
        assertEquals(DungeonNodeTypes.ROOM, addNode.node().type());
    }

    @Test
    void dungeonBlueprint_multiDependencyCreature() {
        // Create a necromancer that depends on both crypt AND library
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("crypt", "Burial chamber of ancient kings", 60)
            .room("library", "Ancient tomes of dark magic", 75)
            .creature("necromancer", "undead-mage", 15, "crypt", "library")
            .build();

        // Compile to graph
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Verify necromancer has both dependencies
        assertTrue(graph.dependenciesOf(NodeId.of("necromancer")).contains(NodeId.of("crypt")),
            "Necromancer should depend on crypt");
        assertTrue(graph.dependenciesOf(NodeId.of("necromancer")).contains(NodeId.of("library")),
            "Necromancer should depend on library");
        assertEquals(2, graph.dependenciesOf(NodeId.of("necromancer")).size(),
            "Necromancer should have exactly 2 dependencies");
    }

    @Test
    void humanNode_dragonRecruitment() {
        // Create a dragon that requires human approval
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("lair", "Dragon's lair with massive treasure hoard", 200)
            .humanCreature("dragon", "ancient-dragon", 20, "lair")
            .build();

        // Compile to graph
        DesiredStateGraph graph = compiler.compile(blueprint, factory);

        // Verify dragon node has requiresHuman=true
        DesiredNode dragonNode = graph.nodes().get(NodeId.of("dragon"));
        assertNotNull(dragonNode, "Dragon node should exist");
        assertTrue(dragonNode.requiresHuman(), "Dragon recruitment should require human approval");
    }

    @Test
    void trapProvisioning() {
        // Create a room with a trap
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("entrance", "Narrow entrance corridor", 20)
            .trap("spike-trap", "spike", 50, "entrance")
            .build();

        // Compile and provision
        DesiredStateGraph graph = compiler.compile(blueprint, factory);
        ActualState actual = adapter.readActual(graph, "default");
        TransitionPlan plan = planner.plan(graph, actual);

        // Execute provisioning
        for (OrderedStep step : plan.additions()) {
            ProvisionContext context = new ProvisionContext("test-tenancy", graph);
            provisioner.provision(step.node(), context);
        }

        // Verify trap is ARMED
        assertEquals(DungeonWorld.State.ARMED, world.trapState(NodeId.of("spike-trap")));

        // Deprovision trap (triggers it)
        DeprovisionContext deprovContext = new DeprovisionContext("test-tenancy", graph);
        DesiredNode trapNode = graph.nodes().get(NodeId.of("spike-trap"));
        provisioner.deprovision(trapNode, deprovContext);

        // Verify trap is TRIGGERED
        assertEquals(DungeonWorld.State.TRIGGERED, world.trapState(NodeId.of("spike-trap")));
    }

    @Test
    void actualStateAdapter_translatesStates() {
        // Set up various states in world
        world.setRoom(NodeId.of("lair"), DungeonWorld.State.BUILT);
        world.setRoom(NodeId.of("ruins"), DungeonWorld.State.DESTROYED);
        world.setRoom(NodeId.of("unstable"), DungeonWorld.State.DEGRADED);
        world.setCreature(NodeId.of("goblin"), DungeonWorld.State.PRESENT);
        world.setCreature(NodeId.of("fled-orc"), DungeonWorld.State.FLED);
        world.setTrap(NodeId.of("pit-trap"), DungeonWorld.State.ARMED);
        world.setTrap(NodeId.of("used-trap"), DungeonWorld.State.TRIGGERED);

        // Create minimal graph for adapter
        DesiredStateGraph graph = factory.empty();

        // Read actual state
        ActualState actual = adapter.readActual(graph, "default");

        // Verify translations
        assertEquals(NodeStatus.PRESENT, actual.statusOf(NodeId.of("lair")).orElse(null));
        assertEquals(NodeStatus.ABSENT, actual.statusOf(NodeId.of("ruins")).orElse(null));
        assertEquals(NodeStatus.DRIFTED, actual.statusOf(NodeId.of("unstable")).orElse(null));
        assertEquals(NodeStatus.PRESENT, actual.statusOf(NodeId.of("goblin")).orElse(null));
        assertEquals(NodeStatus.ABSENT, actual.statusOf(NodeId.of("fled-orc")).orElse(null));
        assertEquals(NodeStatus.PRESENT, actual.statusOf(NodeId.of("pit-trap")).orElse(null));
        assertEquals(NodeStatus.ABSENT, actual.statusOf(NodeId.of("used-trap")).orElse(null));
    }

    @Test
    void faultPolicy_ignoresNonDestructionFaults() {
        DungeonBlueprint blueprint = DungeonBlueprint.builder()
            .room("library", "Ancient tomes", 75)
            .build();
        DesiredStateGraph graph = compiler.compile(blueprint, factory);
        HeroRaidFaultPolicy policy = new HeroRaidFaultPolicy();

        // Test with PROVISION_FAILED fault
        FaultEvent fault = new FaultEvent(
            NodeId.of("library"),
            FaultType.PROVISION_FAILED,
            "Failed to provision"
        );

        List<GraphMutation> mutations = policy.onFault(fault, graph, new ActualState(Map.of()));
        assertTrue(mutations.isEmpty(), "Policy should ignore non-destruction faults");
    }
}
