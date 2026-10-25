package com.iberia.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.iberia.model.DiseaseType.BLACK;
import static com.iberia.model.DiseaseType.BLUE;
import static com.iberia.model.DiseaseType.RED;
import static com.iberia.model.DiseaseType.YELLOW;

/**
 * Raw map data transcribed from the 1848 board: city colours, ports (anchor
 * symbols), positions and every brown line. Founding years printed in the
 * rulebook are used where known; the rest are historical approximations.
 */
final class BoardData {
    private BoardData() {
    }

    static List<City> cities() {
        List<City> list = new ArrayList<>();
        Object[][] raw = {
                // name, colour, port, x, y, founded
                {"A Coruña", BLUE, true, 135, 144, -62},
                {"Santiago de Compostela", BLUE, false, 134, 187, 830},
                {"Vigo", BLUE, true, 117, 242, -150},
                {"Ourense", BLUE, false, 222, 225, -25},
                {"Braga", BLUE, false, 200, 291, -16},
                {"Porto", BLUE, true, 127, 327, -300},
                {"Coimbra", BLUE, false, 184, 404, -100},
                {"Salamanca", BLUE, false, 306, 345, -220},
                {"Cáceres", BLUE, false, 264, 459, -34},
                {"Lisboa", BLUE, true, 96, 517, -1000},
                {"Évora", BLUE, false, 174, 534, -57},
                {"Albufeira", BLUE, true, 147, 645, -200},

                {"Gijón", RED, true, 310, 132, -500},
                {"León", RED, false, 313, 212, -29},
                {"Santander", RED, true, 418, 140, -26},
                {"Bilbao-Bilbo", RED, false, 475, 157, 1300},
                {"San Sebastián-Donostia", RED, true, 543, 131, 1180},
                {"Vitoria-Gasteiz", RED, false, 493, 222, 1181},
                {"Pamplona", RED, false, 551, 194, -74},
                {"Burgos", RED, false, 428, 237, 884},
                {"Valladolid", RED, false, 365, 289, 1072},
                {"Soria", RED, false, 485, 295, -133},
                {"Madrid", RED, false, 427, 386, 865},
                {"Huesca", RED, false, 628, 240, -100},

                {"Andorra la Vella", YELLOW, false, 741, 216, 1278},
                {"Girona", YELLOW, false, 821, 261, -77},
                {"Barcelona", YELLOW, true, 782, 308, -15},
                {"Zaragoza", YELLOW, false, 598, 289, -14},
                {"Tarragona", YELLOW, true, 695, 334, -218},
                {"Teruel", YELLOW, false, 584, 393, 1171},
                {"Cuenca", YELLOW, false, 522, 415, 714},
                {"Valencia", YELLOW, true, 626, 459, -138},
                {"Palma de Mallorca", YELLOW, true, 784, 447, -123},
                {"Albacete", YELLOW, false, 538, 498, 1146},
                {"Alicante", YELLOW, true, 622, 545, -324},
                {"Cartagena", YELLOW, true, 590, 605, -227},

                {"Toledo", BLACK, false, 363, 427, -192},
                {"Ciudad Real", BLACK, false, 411, 497, 1255},
                {"Badajoz", BLACK, false, 231, 509, 875},
                {"Huelva", BLACK, true, 232, 631, -800},
                {"Sevilla", BLACK, false, 290, 624, -700},
                {"Córdoba", BLACK, false, 360, 583, -169},
                {"Jaén", BLACK, false, 432, 567, -200},
                {"Granada", BLACK, false, 445, 621, -550},
                {"Cádiz", BLACK, true, 271, 686, -1104},
                {"Gibraltar", BLACK, true, 322, 715, 1160},
                {"Málaga", BLACK, true, 381, 672, -750},
                {"Almería", BLACK, true, 502, 665, 955},
        };
        for (int i = 0; i < raw.length; i++) {
            Object[] r = raw[i];
            list.add(new City(i, (String) r[0], (DiseaseType) r[1], (Boolean) r[2],
                    ((Integer) r[3]).doubleValue(), ((Integer) r[4]).doubleValue(), (Integer) r[5]));
        }
        return list;
    }

    /** "*" marks a dashed line (no railroads allowed). */
    private static final String[] ROUTES = {
            "A Coruña|Santiago de Compostela", "A Coruña|Gijón",
            "Santiago de Compostela|Vigo", "Santiago de Compostela|Ourense",
            "Vigo|Ourense", "Vigo|Braga", "Vigo|Porto", "Ourense|León",
            "Braga|Porto", "Braga|Salamanca", "Porto|Coimbra", "Porto|Lisboa",
            "Coimbra|Lisboa", "Coimbra|Cáceres", "Salamanca|León", "Salamanca|Valladolid",
            "Salamanca|Madrid", "Salamanca|Cáceres", "Cáceres|Toledo", "Cáceres|Badajoz",
            "Lisboa|Évora", "Lisboa|Albufeira", "Évora|Badajoz", "Évora|Huelva", "Albufeira|Huelva",
            "Gijón|León", "Gijón|Santander", "Santander|Bilbao-Bilbo", "Santander|Valladolid",
            "Bilbao-Bilbo|San Sebastián-Donostia", "Bilbao-Bilbo|Vitoria-Gasteiz",
            "San Sebastián-Donostia|Pamplona", "Vitoria-Gasteiz|Pamplona", "Vitoria-Gasteiz|Burgos",
            "Vitoria-Gasteiz|Zaragoza", "Pamplona|Huesca", "Burgos|Valladolid", "Burgos|Soria",
            "Valladolid|Madrid", "Soria|Zaragoza", "Madrid|Zaragoza", "Madrid|Cuenca",
            "Madrid|Toledo", "Madrid|Ciudad Real", "Huesca|Zaragoza", "Huesca|Andorra la Vella*",
            "Andorra la Vella|Girona*", "Girona|Barcelona", "Barcelona|Zaragoza", "Barcelona|Tarragona",
            "Barcelona|Palma de Mallorca*", "Tarragona|Teruel", "Tarragona|Valencia", "Zaragoza|Teruel",
            "Teruel|Cuenca", "Cuenca|Valencia", "Cuenca|Albacete", "Valencia|Albacete",
            "Valencia|Alicante", "Valencia|Palma de Mallorca*", "Albacete|Ciudad Real",
            "Albacete|Cartagena", "Albacete|Jaén", "Alicante|Cartagena", "Cartagena|Almería",
            "Toledo|Ciudad Real", "Ciudad Real|Badajoz", "Ciudad Real|Córdoba", "Badajoz|Córdoba",
            "Córdoba|Sevilla", "Córdoba|Jaén", "Córdoba|Málaga", "Huelva|Sevilla", "Sevilla|Cádiz",
            "Sevilla|Málaga", "Cádiz|Gibraltar*", "Gibraltar|Málaga*", "Jaén|Granada",
            "Granada|Málaga", "Granada|Almería", "Málaga|Almería",
    };

    static List<Route> routes(List<City> cities) {
        Map<String, Integer> idx = new HashMap<>();
        for (City c : cities) idx.put(c.name, c.id);
        List<Route> list = new ArrayList<>();
        for (String s : ROUTES) {
            boolean dashed = s.endsWith("*");
            String[] p = (dashed ? s.substring(0, s.length() - 1) : s).split("\\|");
            Integer a = idx.get(p[0]);
            Integer b = idx.get(p[1]);
            if (a == null || b == null) throw new IllegalStateException("Bad route " + s);
            list.add(new Route(list.size(), a, b, dashed));
        }
        return list;
    }
}
