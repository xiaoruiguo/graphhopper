package com.graphhopper.routing.weighting.custom;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.graphhopper.json.geo.JsonFeature;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.CarFlagEncoder;
import com.graphhopper.routing.util.CustomModel;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.parsers.OSMBikeNetworkTagParser;
import com.graphhopper.routing.util.parsers.OSMHazmatParser;
import com.graphhopper.routing.util.parsers.OSMTollParser;
import com.graphhopper.routing.weighting.FastestWeighting;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.GHUtility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.graphhopper.routing.ev.RoadClass.*;
import static com.graphhopper.routing.weighting.TurnCostProvider.NO_TURN_COST_PROVIDER;
import static com.graphhopper.routing.weighting.custom.CustomWeighting.FIRST_MATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomWeightingTest {

    GraphHopperStorage graph;
    DecimalEncodedValue avSpeedEnc;
    BooleanEncodedValue accessEnc;
    DecimalEncodedValue maxSpeedEnc;
    EnumEncodedValue<RoadClass> roadClassEnc;
    EncodingManager encodingManager;
    FlagEncoder carFE;

    @BeforeEach
    public void setup() {
        carFE = new CarFlagEncoder().setSpeedTwoDirections(true);
        encodingManager = new EncodingManager.Builder().add(carFE).add(new OSMTollParser()).add(new OSMHazmatParser()).add(new OSMBikeNetworkTagParser()).build();
        avSpeedEnc = carFE.getAverageSpeedEnc();
        accessEnc = carFE.getAccessEnc();
        maxSpeedEnc = encodingManager.getDecimalEncodedValue(MaxSpeed.KEY);
        roadClassEnc = encodingManager.getEnumEncodedValue(KEY, RoadClass.class);
        graph = new GraphBuilder(encodingManager).create();
    }

    @Test
    public void speedOnly() {
        // 50km/h -> 72s per km, 100km/h -> 36s per km
        EdgeIteratorState edge;
        GHUtility.setSpeed(50, 100, carFE, edge = graph.edge(0, 1).setDistance(1000));
        assertEquals(72, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeWeight(edge, false), 1.e-6);
        assertEquals(36, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeWeight(edge, true), 1.e-6);
    }

    @Test
    public void withPriority() {
        // 25km/h -> 144s per km, 50km/h -> 72s per km, 100km/h -> 36s per km
        EdgeIteratorState slow = GHUtility.setSpeed(25, true, true, carFE, graph.edge(0, 1).setDistance(1000)).
                set(roadClassEnc, SECONDARY);
        EdgeIteratorState medium = GHUtility.setSpeed(50, true, true, carFE, graph.edge(0, 1).setDistance(1000)).
                set(roadClassEnc, SECONDARY);
        EdgeIteratorState fast = GHUtility.setSpeed(100, true, true, carFE, graph.edge(0, 1).setDistance(1000)).
                set(roadClassEnc, SECONDARY);

        // without priority costs fastest weighting is the same as custom weighting
        assertEquals(144, new FastestWeighting(carFE, NO_TURN_COST_PROVIDER).calcEdgeWeight(slow, false), .1);
        assertEquals(72, new FastestWeighting(carFE, NO_TURN_COST_PROVIDER).calcEdgeWeight(medium, false), .1);
        assertEquals(36, new FastestWeighting(carFE, NO_TURN_COST_PROVIDER).calcEdgeWeight(fast, false), .1);

        CustomModel model = new CustomModel().setDistanceInfluence(0);
        assertEquals(144, createWeighting(model).calcEdgeWeight(slow, false), .1);
        assertEquals(72, createWeighting(model).calcEdgeWeight(medium, false), .1);
        assertEquals(36, createWeighting(model).calcEdgeWeight(fast, false), .1);

        // if we reduce the priority we get higher edge weights
        model.getPriority().put("road_class == SECONDARY", 0.5);
        // the absolute priority costs depend on the speed, so setting priority=0.5 means a lower absolute weight
        // weight increase for fast edges and a higher absolute increase for slower edges
        assertEquals(2 * 144, createWeighting(model).calcEdgeWeight(slow, false), .1);
        assertEquals(2 * 72, createWeighting(model).calcEdgeWeight(medium, false), .1);
        assertEquals(2 * 36, createWeighting(model).calcEdgeWeight(fast, false), .1);
    }

    @Test
    public void withDistanceInfluence() {
        BooleanEncodedValue accessEnc = carFE.getAccessEnc();
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(10_000).set(avSpeedEnc, 50).set(accessEnc, true, true);
        assertEquals(720, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeWeight(edge, false), .1);
        assertEquals(720_000, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeMillis(edge, false), .1);
        // distance_influence=30 means that for every kilometer we get additional costs of 30s, so +300s here
        assertEquals(1020, createWeighting(new CustomModel().setDistanceInfluence(30)).calcEdgeWeight(edge, false), .1);
        // ... but the travelling time stays the same
        assertEquals(720_000, createWeighting(new CustomModel().setDistanceInfluence(30)).calcEdgeMillis(edge, false), .1);

        // we can also imagine a shorter but slower road that takes the same time
        edge = graph.edge(0, 1).setDistance(5_000).set(avSpeedEnc, 25).set(accessEnc, true, true);
        assertEquals(720, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeWeight(edge, false), .1);
        assertEquals(720_000, createWeighting(new CustomModel().setDistanceInfluence(0)).calcEdgeMillis(edge, false), .1);
        // and if we include the distance influence the weight will be bigger but still smaller than what we got for
        // the longer and faster edge
        assertEquals(870, createWeighting(new CustomModel().setDistanceInfluence(30)).calcEdgeWeight(edge, false), .1);
    }

    @Test
    public void testSpeedFactorBooleanEV() {
        EdgeIteratorState edge = GHUtility.setSpeed(15, true, true, carFE, graph.edge(0, 1).setDistance(10));
        CustomModel vehicleModel = new CustomModel();
        assertEquals(3.1, createWeighting(vehicleModel).calcEdgeWeight(edge, false), 0.01);
        // here we increase weight for edges that are road class links
        vehicleModel.getPriority().put(RoadClassLink.KEY, 0.5);
        Weighting weighting = createWeighting(vehicleModel);
        BooleanEncodedValue rcLinkEnc = encodingManager.getBooleanEncodedValue(RoadClassLink.KEY);
        assertEquals(3.1, weighting.calcEdgeWeight(edge.set(rcLinkEnc, false), false), 0.01);
        assertEquals(5.5, weighting.calcEdgeWeight(edge.set(rcLinkEnc, true), false), 0.01);
    }

    @Test
    public void testBoolean() {
        carFE = new CarFlagEncoder();
        BooleanEncodedValue specialEnc = new SimpleBooleanEncodedValue("special", true);
        encodingManager = new EncodingManager.Builder().add(carFE).add(specialEnc).build();
        avSpeedEnc = carFE.getAverageSpeedEnc();
        graph = new GraphBuilder(encodingManager).create();

        BooleanEncodedValue accessEnc = carFE.getAccessEnc();
        EdgeIteratorState edge = graph.edge(0, 1).set(accessEnc, true).setReverse(accessEnc, true).
                set(avSpeedEnc, 15).set(specialEnc, false).setReverse(specialEnc, true).setDistance(10);

        CustomModel vehicleModel = new CustomModel();
        assertEquals(3.1, createWeighting(vehicleModel).calcEdgeWeight(edge, false), 0.01);
        vehicleModel.getPriority().put("special == true", 0.8);
        vehicleModel.getPriority().put("special == false", 0.4);
        Weighting weighting = createWeighting(vehicleModel);
        assertEquals(6.7, weighting.calcEdgeWeight(edge, false), 0.01);
        assertEquals(3.7, weighting.calcEdgeWeight(edge, true), 0.01);
    }

    @Test
    public void testPriority() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class != PRIMARY", 0.5);

        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.73, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class != PRIMARY", 0.5);
        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.73, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        vehicleModel = new CustomModel();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("road_class != PRIMARY", 0.5);
        map.put("road_class == SECONDARY", 0.7);
        map.put("true", 0.9);
        vehicleModel.getPriority().put(FIRST_MATCH, map);
        assertEquals(1.2, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.73, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        // force integer value
        vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class == PRIMARY", 1);
        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
    }

    @Test
    public void testSpeedFactorAndPriority() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class != PRIMARY", 0.5);
        vehicleModel.getSpeedFactor().put("road_class != PRIMARY", 0.9);
        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.84, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        vehicleModel = new CustomModel();
        Map<String, Object> map = new LinkedHashMap<>();
        vehicleModel.getPriority().put(FIRST_MATCH, map);
        map.put("road_class == PRIMARY", 1.0);
        map.put("true", 0.5);
        vehicleModel.getSpeedFactor().put("road_class != PRIMARY", 0.9);
        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.84, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);
    }

    @Test
    public void testSpeedFactorAndPriorityAndMaxSpeed() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class == PRIMARY", 0.9);
        vehicleModel.getSpeedFactor().put("road_class == PRIMARY", 0.8);
        assertEquals(1.33, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.21, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        vehicleModel.getMaxSpeed().put("road_class != PRIMARY", 50);
        assertEquals(1.33, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.42, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);
    }

    @Test
    public void testIssueSameKey() {
        EdgeIteratorState withToll = graph.edge(0, 1).setDistance(10).
                set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState noToll = graph.edge(1, 2).setDistance(10).
                set(avSpeedEnc, 80).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getSpeedFactor().put("toll != NO", 0.8);
        vehicleModel.getSpeedFactor().put("hazmat != NO", 0.8);
        assertEquals(1.26, createWeighting(vehicleModel).calcEdgeWeight(withToll, false), 0.01);
        assertEquals(1.26, createWeighting(vehicleModel).calcEdgeWeight(noToll, false), 0.01);

        vehicleModel = new CustomModel();
        vehicleModel.getSpeedFactor().put("bike_network != OTHER", 0.8);
        assertEquals(1.26, createWeighting(vehicleModel).calcEdgeWeight(withToll, false), 0.01);
        assertEquals(1.26, createWeighting(vehicleModel).calcEdgeWeight(noToll, false), 0.01);
    }

    @Test
    public void testFirstMatch() {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getSpeedFactor().put("road_class == PRIMARY", 0.8);
        assertEquals(1.26, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.21, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        Map<String, Object> map = new LinkedHashMap<>();
        map.put("road_class == PRIMARY", 0.9);
        map.put("road_class == SECONDARY", 0.8);
        vehicleModel.getPriority().put(FIRST_MATCH, map);
        assertEquals(1.33, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.34, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);

        map = new LinkedHashMap<>();
        map.put("true", 0.9);
        map.put("road_class == SECONDARY", 0.8);
        vehicleModel.getPriority().put(FIRST_MATCH, map);
        assertThrows(IllegalArgumentException.class, () -> createWeighting(vehicleModel).calcEdgeWeight(primary, false));
    }

    @Test
    public void testCarAccess() {
        EdgeIteratorState edge40 = graph.edge(0, 1).setDistance(10).set(avSpeedEnc, 40).set(accessEnc, true, true);
        EdgeIteratorState edge50 = graph.edge(1, 2).setDistance(10).set(avSpeedEnc, 50).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("car$average_speed > 40", 0.5);

        assertEquals(1.60, createWeighting(vehicleModel).calcEdgeWeight(edge40, false), 0.01);
        assertEquals(2.14, createWeighting(vehicleModel).calcEdgeWeight(edge50, false), 0.01);
    }

    @Test
    public void testArea() throws Exception {
        EdgeIteratorState primary = graph.edge(0, 1).setDistance(10).
                set(roadClassEnc, PRIMARY).set(avSpeedEnc, 80).set(accessEnc, true, true);
        EdgeIteratorState secondary = graph.edge(1, 2).setDistance(10).
                set(roadClassEnc, SECONDARY).set(avSpeedEnc, 70).set(accessEnc, true, true);

        CustomModel vehicleModel = new CustomModel();
        vehicleModel.getPriority().put("road_class == PRIMARY", 1.0);
        vehicleModel.getPriority().put("in_area_custom1", 0.5);

        ObjectMapper om = new ObjectMapper().registerModule(new JtsModule());
        JsonFeature json = om.readValue("{ \"geometry\":{ \"type\": \"Polygon\", \"coordinates\": " +
                "[[[11.5818,50.0126], [11.5818,50.0119], [11.5861,50.0119], [11.5861,50.0126], [11.5818,50.0126]]] }}", JsonFeature.class);
        vehicleModel.getAreas().put("custom1", json);

        assertEquals(1.15, createWeighting(vehicleModel).calcEdgeWeight(primary, false), 0.01);
        assertEquals(1.21, createWeighting(vehicleModel).calcEdgeWeight(secondary, false), 0.01);
    }

    private Weighting createWeighting(CustomModel vehicleModel) {
        return new CustomWeighting(carFE, encodingManager, NO_TURN_COST_PROVIDER, vehicleModel);
    }
}