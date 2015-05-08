package lodi;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/**
 * The location disambiguation algorithm
 */
public class Disambiguator {

    /**
     * Exact match to input string in google database
     */
    public final static int CODE_GOOGLE = 1;

    /**
     * Exact match on city/state (for US cities) or city/country (for non-US cities)
     */
    public final static int CODE_EXACT_CITY = 2;

    /**
     * Fuzzy match on city name, blocked by state (for US cities) or country (for non-US cities)
     */
    public final static int CODE_FUZZY_CITY = 3;

    /**
     * Disambiguate the list of raw locations.
     *
     * @param conn A connection to the geolocation database.
     * @param rawLocations A list of raw location records to disambiguate
     * @param googleConfidenceThreshold The confidence threshold to use when loading the google table
     * @param matchThreshold The required match level when doing fuzzy-matching using Jaro-Winkler.
     */
    public static void disambiguate(
            Connection conn, 
            List<RawLocation.Record> rawLocations,
            double googleConfidenceThreshold,
            double matchThreshold) 
        throws SQLException
    {
        System.out.print("Loading cities table... ");
        Cities cities = new Cities(conn);
        System.out.format("got %d records\n", cities.size());

        System.out.print("Loading google_cities table... ");
        GoogleCities goog = new GoogleCities(conn, googleConfidenceThreshold, cities);
        System.out.format("got %d records\n", goog.size());

        disambiguate(cities, goog, rawLocations, googleConfidenceThreshold, matchThreshold);
    }

    /**
     * Disambiguation the list of raw locations.
     *
     * @param cities The cities table from the geolocation database
     * @param goog The google_cities table from the geolocation database
     * @param rawLocations A list of raw location records to disambiguate
     * @param googleConfidenceThreshold The confidence threshold to use when loading the google table
     * @param matchThreshold The required match level when doing fuzzy-matching using Jaro-Winkler.
     */
    public static void disambiguate(
            Cities cities,
            GoogleCities goog,
            List<RawLocation.Record> rawLocations,
            double googleConfidenceThreshold,
            double matchThreshold) 
    {

        // try looking up the cleaned, concatenated locations in the google database

        System.out.print("Google lookup... ");

        rawLocations
            .parallelStream()
            .forEach(loc -> {
                if (goog.containsKey(loc.cleanedLocation)) {
                    loc.linkedCity = goog.get(loc.cleanedLocation).city;
                    loc.linkCode = CODE_GOOGLE;
                }
            });

        List<RawLocation.Record> unidentifiedLocations =
            rawLocations
            .parallelStream()
            .filter(loc -> loc.linkedCity == null)
            .collect(Collectors.toList());

        int identifiedCount = rawLocations.size() - unidentifiedLocations.size();
        System.out.println("(" + identifiedCount + " locations identified)");

        // handle unmatched locations

        // perform exact match on city and state name

        System.out.print("Exact match... ");
        
        unidentifiedLocations
            .parallelStream()
            .forEach(loc -> {
                if ("US".equalsIgnoreCase(loc.country)) {
                    loc.linkedCity = cities.getCityInState(loc.city, loc.state);

                    if (loc.linkedCity != null)
                        loc.linkCode = CODE_EXACT_CITY;
                }
                else {
                    loc.linkedCity = cities.getCityInCountry(loc.city, loc.country);

                    if (loc.linkedCity != null)
                        loc.linkCode = CODE_FUZZY_CITY;
                }
            });

        List<RawLocation.Record> unidentifiedLocations2 =
            unidentifiedLocations
            .parallelStream()
            .filter(loc -> loc.linkedCity == null)
            .collect(Collectors.toList());

        identifiedCount = rawLocations.size() - unidentifiedLocations2.size();
        System.out.println("(" + identifiedCount + " locations identified)");
        
        // group unidentified locations by country

        System.out.println("Fuzzy city lookup...");

        ConcurrentMap<String, List<RawLocation.Record>> unidentifiedGroupedLocations =
            unidentifiedLocations2
            .parallelStream()
            .collect(Collectors.groupingByConcurrent(Disambiguator::countryGroup));

        for  (final Map.Entry<String, List<RawLocation.Record>> entry: unidentifiedGroupedLocations.entrySet()) {

            List<RawLocation.Record> countryLocations = entry.getValue();

            // group locations by unique city

            Map<String, List<RawLocation.Record>> rawCities =
                countryLocations
                .stream()
                .collect(Collectors.groupingBy(loc -> loc.city));

            // get database cities from this country

            String country = entry.getKey();
            List<Cities.Record> cityList;

            if (country.startsWith("US:")) {
                // this "country" encodes a US state (see #countryGroup)
                String state = country.substring(3);
                cityList = cities.getState(state);
            } else {
                cityList = cities.getCountry(country);
            }

            int cityCount = (cityList == null) ? 0 : cityList.size();

            System.out.println(
                    String.format("Missing for: %s (%d locations, %d unique, %d known cities)", 
                                  country, countryLocations.size(), rawCities.size(), cityCount));

            // for each unmatched location in this country, find the best matching city

            rawCities.keySet()
                .parallelStream()
                .forEach(rawString -> {
                    CityScore cscore = bestScore(rawString, cityList);

                    if (cscore.score > matchThreshold && cscore.city.stringValue != country)
                        rawCities.get(rawString).stream().forEach(loc -> loc.linkedCity = cscore.city);
                });
        }

        List<RawLocation.Record> finalLinked =
            rawLocations
            .parallelStream()
            .filter(loc -> loc.linkedCity != null)
            .collect(Collectors.toList());

        System.out.println("Count of identified locations (final): " + finalLinked.size());
    }

    /**
     * Normalize locations in inventor portfolios. If the same city name appears multiple
     * times for different US states, consolidate to the dominant state.
     */
    public static void normalizeInventor(List<RawLocation.Record> records) {
        // group rawlocation records by city

        Map<Cities.Record, List<RawLocation.Record>> map =
            records.stream()
            .filter(r -> r.linkedCity != null && "US".equalsIgnoreCase(r.linkedCity.country))
            .collect(Collectors.groupingBy(r -> r.linkedCity));

        // group the linked city records by city name

        Map<String, List<Cities.Record>> cities =
            map.keySet().stream()
            .filter(c -> c.country.equalsIgnoreCase("US"))
            .collect(Collectors.groupingBy(c -> c.city));

        for (String cityName: cities.keySet()) {
            // Find the US state with the most instances of this city name

            List<Cities.Record> cityStates = 
                cities.get(cityName).stream()
                .sorted((x, y) -> map.get(x).size() - map.get(y).size())
                .collect(Collectors.toList());

            if (cityStates.size() > 1) {
                Cities.Record first = cityStates.get(0);
                Cities.Record second = cityStates.get(1);

                if (map.get(first).size() == map.get(second).size()) {
                    throw new IllegalArgumentException(
                            "City appears same number of times in two states");
                }

                // re-link raw-locations to first

                cityStates.stream()
                    .skip(1)
                    .forEach(city -> {
                        map.get(city).stream().forEach(loc -> loc.linkedCity = first);
                    });
            }
        }
    }

    /**
     * Create a special "country" code for US states. This is used when splitting up the
     * raw locations by country. This is a little hacky but works.
     */
    protected static String countryGroup(RawLocation.Record loc) {
        if (loc.cleanedCountry.equalsIgnoreCase("US")) {
            return String.format("US:%s", loc.state);
        }
        else {
            return loc.cleanedCountry;
        }
    }

    /**
     * This is a utility class used to represent a city object with a score attribute.
     *
     * @see #bestScore(String, List<Cities.Record>)
     */
    protected static class CityScore {
        public final Cities.Record city;
        public final double score;

        public CityScore(Cities.Record city, double score) {
            this.city = city;
            this.score = score;
        }

        public static CityScore max(CityScore a, CityScore b) {
            return (a.score >= b.score) ? a : b;
        }
    }

    /**
     * Compare a string to a city record and give the result as a {@link CityScore}
     * object. The method tries to compare the input string to the city's "city"
     * field, but if that field is null it falls back on the region field. The algorithm
     * prefers to match to a city, however, and slightly penalizes comparisons to regions.
     *
     * @param s The name of a city to compare
     * @param city The city object to compare to.
     */
    protected static CityScore score(String s, Cities.Record city) {
        String t = city.city;
        double scoreAdjust = 0.0;

        if (t == null) {
            t = city.region;
            scoreAdjust = -0.05;
        }

        double score = StringUtils.getJaroWinklerDistance(s, t);
        return new CityScore(city, score + scoreAdjust);
    }

    /**
     * This method takes and input string and finds the best match from a list of cities.
     *
     * @param rawString The input city name
     * @param cities A list of cities to compare to
     *
     * @return A CityScore object containing the best-matching city and its match score
     */
    protected static CityScore bestScore(String rawString, List<Cities.Record> cities) {
        if (cities == null)
            return new CityScore(null, -1);

        Optional<CityScore> maxScore = 
            cities.stream()
            .map(c -> score(rawString, c))
            .reduce(CityScore::max);

        if (maxScore.isPresent())
            return maxScore.get();
        else
            return new CityScore(null, -1);
    }

}
