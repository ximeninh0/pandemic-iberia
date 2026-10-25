package com.iberia.engine;

import com.iberia.model.Board;
import com.iberia.model.City;
import com.iberia.model.DiseaseType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoardTest {
    private final Board b = Board.get();

    private Set<String> neighbors(String city) {
        return Arrays.stream(b.neighbors(b.byName(city).id)).mapToObj(i -> b.city(i).name).collect(Collectors.toSet());
    }

    @Test
    void topologyCounts() {
        assertEquals(48, b.cityCount());
        assertEquals(81, b.routes.size());
        // Euler: V - E + F = 2 with F including the outer face
        assertEquals(34, b.regions.size());
        for (DiseaseType d : DiseaseType.values()) {
            assertEquals(12, b.cities.stream().filter(c -> c.color == d).count(), d.name());
        }
        assertEquals(19, b.cities.stream().filter(c -> c.port).count());
        assertEquals(6, b.routes.stream().filter(r -> r.dashed).count());
    }

    @Test
    void rulebookExamplesMatchTheMap() {
        // Cholera example: Albufeira only connects to Lisboa and Huelva
        assertEquals(new HashSet<>(Arrays.asList("Lisboa", "Huelva")), neighbors("Albufeira"));
        Set<String> two = b.citiesAtDistanceTwo(b.byName("Albufeira").id).stream()
                .map(i -> b.city(i).name).collect(Collectors.toSet());
        assertEquals(new HashSet<>(Arrays.asList("Porto", "Coimbra", "Évora", "Sevilla")), two);
        // outbreak example
        assertEquals(new HashSet<>(Arrays.asList("Madrid", "Teruel", "Albacete", "Valencia")), neighbors("Cuenca"));
        assertEquals(new HashSet<>(Arrays.asList("Tarragona", "Alicante", "Albacete", "Palma de Mallorca", "Cuenca")), neighbors("Valencia"));
        // Zaragoza borders six regions (purify example 2)
        assertEquals(6, b.regionsOf(b.byName("Zaragoza").id).length);
        // the region north of Barcelona (purify example 1)
        int zaragoza = b.byName("Zaragoza").id, huesca = b.byName("Huesca").id, andorra = b.byName("Andorra la Vella").id,
                girona = b.byName("Girona").id, barcelona = b.byName("Barcelona").id;
        boolean found = b.regions.stream().anyMatch(r -> r.cities.length == 5 && r.borders(zaragoza) && r.borders(huesca)
                && r.borders(andorra) && r.borders(girona) && r.borders(barcelona));
        assertTrue(found);
        // the sea region between Barcelona, Tarragona, Valencia and Palma
        int tarragona = b.byName("Tarragona").id, valencia = b.byName("Valencia").id, palma = b.byName("Palma de Mallorca").id;
        assertTrue(b.regions.stream().anyMatch(r -> r.cities.length == 4 && r.borders(barcelona) && r.borders(tarragona)
                && r.borders(valencia) && r.borders(palma)));
    }

    @Test
    void everyCityBordersARegion() {
        for (City c : b.cities) assertTrue(b.regionsOf(c.id).length >= 1, c.name);
    }
}
