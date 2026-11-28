package com.progameflixx.cafectrl;

import com.jayway.jsonpath.JsonPath;
import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.entity.AuditEvent;
import com.progameflixx.cafectrl.entity.CustomerSession;
import com.progameflixx.cafectrl.entity.GameSession;
import com.progameflixx.cafectrl.entity.RateCard;
import com.progameflixx.cafectrl.entity.User;
import com.progameflixx.cafectrl.repository.AuditEventRepository;
import com.progameflixx.cafectrl.repository.CustomerSessionRepository;
import com.progameflixx.cafectrl.repository.RateCardRepository;
import com.progameflixx.cafectrl.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Player count is mandatory (and must be priced) for player-priced resources on every start path. */
@SpringBootTest
@AutoConfigureMockMvc
class PlayerCountTest {

    static final String CAFE = "cafe-player-count";
    static final String ADMIN = "players.admin@test.local";
    static final String START = "2026-11-02T10:00:00";
    static final String ONE_HOUR_LATER = "2026-11-02T11:00:00";
    /** 60-minute price per player count on the PS5 card (30-minute slab is cheaper, unused by a 1-hour bill). */
    static final Map<Integer, Integer> PS5_HOURLY = Map.of(1, 100, 2, 180, 3, 250, 4, 300);

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RateCardRepository rateCards;
    @Autowired CustomerSessionRepository sessions;
    @Autowired AuditEventRepository audit;
    @Autowired JdbcTemplate jdbc;

    final RequestPostProcessor asAdmin = user(ADMIN).roles("CAFE_ADMIN");
    String ps5Card;
    String poolCard;

    @BeforeEach
    void setUp() {
        User u = users.findByEmail(ADMIN).orElseGet(User::new);
        u.setEmail(ADMIN);
        u.setName("Pia Admin");
        u.setRole("CAFE_ADMIN");
        u.setCafeId(CAFE);
        u.setActive(true);
        u.setPermissions(new ArrayList<>());
        users.save(u);
        ps5Card = card(List.of(1, 2, 3, 4), PS5_HOURLY);
        poolCard = card(List.of(1), Map.of(1, 120));
    }

    String card(List<Integer> counts, Map<Integer, Integer> hourly) {
        Map<String, Object> hour = new HashMap<>(), half = new HashMap<>();
        hourly.forEach((pc, price) -> { hour.put(String.valueOf(pc), price); half.put(String.valueOf(pc), price / 2 + 10); });
        Map<String, Object> prices = new HashMap<>();
        for (String d : List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun")) {
            prices.put(d, List.of(Map.of("duration", 30, "prices", half), Map.of("duration", 60, "prices", hour)));
        }
        RateCard rc = new RateCard();
        rc.setCafeId(CAFE);
        rc.setName("RC " + UUID.randomUUID());
        rc.setWeekdayPrices(prices);
        rc.setBillingIntervalMinutes(30);
        rc.setGraceMinutes(0);
        rc.setPlayerCounts(counts);
        return rateCards.save(rc).getId();
    }

    MvcResult send(MockHttpServletRequestBuilder req, String json) throws Exception {
        req.with(asAdmin);
        if (json != null) req.contentType(MediaType.APPLICATION_JSON).content(json);
        return mvc.perform(req).andReturn();
    }

    String resource(String rateCardId) throws Exception {
        MvcResult r = send(post("/api/resources"), "{\"name\":\"Station-" + UUID.randomUUID().toString().substring(0, 5)
                + "\",\"rate_card_id\":\"" + rateCardId + "\"}");
        assertEquals(200, r.getResponse().getStatus());
        return JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    /** Start JSON; playersJson is the raw JSON value for player_count, or null to leave the field out. */
    static String startJson(String customer, String resId, String playersJson) {
        return "{\"customer_name\":\"" + customer + "\",\"resource_id\":\"" + resId + "\",\"start_time\":\"" + START + "\""
                + (playersJson == null ? "" : ",\"player_count\":" + playersJson) + "}";
    }

    /** Stored counts of a session's games (JDBC: the games collection is lazy outside a transaction). */
    List<Integer> playerCounts(String sid) {
        return jdbc.queryForList("SELECT player_count FROM game_sessions WHERE customer_session_id = ? ORDER BY player_count",
                Integer.class, sid);
    }

    long sessionsFor(String customer) {
        return sessions.findAll().stream().filter(s -> CAFE.equals(s.getCafeId()) && customer.equals(s.getCustomerName())).count();
    }

    @Test
    void aPlayerPricedResourceWontStartWithoutAPlayerCount() throws Exception {
        String ps5 = resource(ps5Card);
        String customer = "NoCount-" + UUID.randomUUID();
        MvcResult r = send(post("/api/sessions"), startJson(customer, ps5, null));
        assertEquals(400, r.getResponse().getStatus());
        String detail = JsonPath.read(r.getResponse().getContentAsString(), "$.detail");
        assertTrue(detail.startsWith("Choose the number of players for "), detail);
        assertTrue(detail.contains("(1–4)"), detail);
        assertEquals(0, sessionsFor(customer), "nothing is saved when the count is missing");

        assertEquals(400, send(post("/api/sessions"), startJson(customer, ps5, "null")).getResponse().getStatus());
    }

    @Test
    void invalidOrUnpricedPlayerCountsAreRejected() throws Exception {
        String ps5 = resource(ps5Card);
        String customer = "BadCount-" + UUID.randomUUID();
        for (String bad : List.of("0", "5", "-1", "2.5", "\"two\"", "\"\"", "true")) {
            MvcResult r = send(post("/api/sessions"), startJson(customer, ps5, bad));
            assertEquals(400, r.getResponse().getStatus(), "player_count " + bad + " should be refused");
            assertNotNull(JsonPath.read(r.getResponse().getContentAsString(), "$.detail"));
        }
        assertEquals(0, sessionsFor(customer));
    }

    @Test
    void eachPlayerCountIsBilledAtItsOwnRate() throws Exception {
        for (int players = 1; players <= 4; players++) {
            String ps5 = resource(ps5Card);
            String body = send(post("/api/sessions"), startJson("Group of " + players, ps5, String.valueOf(players))).getResponse().getContentAsString();
            String sid = JsonPath.read(body, "$.id");
            assertEquals(players, (Integer) JsonPath.read(body, "$.games[0].player_count"));

            String bill = send(get("/api/sessions/" + sid + "/bill").param("custom_time", ONE_HOUR_LATER), null).getResponse().getContentAsString();
            assertEquals(PS5_HOURLY.get(players).doubleValue(), ((Number) JsonPath.read(bill, "$.games_total")).doubleValue(),
                    "1 hour for " + players + " player(s)");
        }
    }

    @Test
    void aNumericStringCountIsAccepted() throws Exception {
        String ps5 = resource(ps5Card);
        String body = send(post("/api/sessions"), startJson("Text count", ps5, "\"3\"")).getResponse().getContentAsString();
        assertEquals(3, (Integer) JsonPath.read(body, "$.games[0].player_count"));
    }

    @Test
    void resourcesWithoutPlayerPricingStartWithoutACount() throws Exception {
        String pool = resource(poolCard);
        MvcResult r = send(post("/api/sessions"), startJson("Pool player", pool, null));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertEquals(1, (Integer) JsonPath.read(r.getResponse().getContentAsString(), "$.games[0].player_count"));

        // ...but a count the card has no price for is still refused
        assertEquals(400, send(post("/api/sessions"), startJson("Pool pair", resource(poolCard), "2")).getResponse().getStatus());
    }

    @Test
    void addingAGameFollowsTheSameRules() throws Exception {
        String pool = resource(poolCard);
        String sid = JsonPath.read(send(post("/api/sessions"), startJson("Add-game", pool, null)).getResponse().getContentAsString(), "$.id");
        String ps5 = resource(ps5Card);
        String addNoCount = "{\"resource_id\":\"" + ps5 + "\",\"start_time\":\"" + START + "\"}";
        MvcResult refused = send(post("/api/sessions/" + sid + "/games"), addNoCount);
        assertEquals(400, refused.getResponse().getStatus());
        assertTrue(((String) JsonPath.read(refused.getResponse().getContentAsString(), "$.detail")).startsWith("Choose the number of players"));

        String addThree = "{\"resource_id\":\"" + ps5 + "\",\"player_count\":3,\"start_time\":\"" + START + "\"}";
        MvcResult ok = send(post("/api/sessions/" + sid + "/games"), addThree);
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());
        assertEquals(List.of(1, 3), playerCounts(sid));
    }

    @Test
    void theAuditLogRecordsTheChosenPlayerCount() throws Exception {
        String ps5 = resource(ps5Card);
        String sid = JsonPath.read(send(post("/api/sessions"), startJson("Audited", ps5, "2")).getResponse().getContentAsString(), "$.id");
        AuditEvent started = audit.findByEntityTypeAndEntityIdOrderByIdDesc(AuditActions.ENTITY_SESSION, sid).stream()
                .filter(e -> AuditActions.SESSION_STARTED.equals(e.getAction()) && e.getOutcome() == AuditEvent.Outcome.SUCCESS)
                .findFirst().orElseThrow();
        assertEquals(2, ((Number) started.getDetails().get("player_count")).intValue());
        assertTrue(started.getDescription().contains("(2 players)"), started.getDescription());
    }

    @Test
    void anExistingGameWithoutAStoredCountStillBills() throws Exception {
        // a row from before this change with no count: billing keeps its 1-player fallback, nothing breaks
        String ps5 = resource(ps5Card);
        CustomerSession s = new CustomerSession();
        s.setCafeId(CAFE);
        s.setCustomerName("Legacy");
        s.setStatus("active");
        s.setCreatedAt(LocalDateTime.parse(START));
        GameSession g = new GameSession();
        g.setResourceId(ps5);
        g.setResourceName("Legacy PS5");
        g.setRateCardId(ps5Card);
        g.setStartTime(LocalDateTime.parse(START));
        g.setStatus("active");
        s.addGame(g);
        String sid = sessions.save(s).getId();
        assertEquals(Collections.singletonList(null), playerCounts(sid));

        String bill = send(get("/api/sessions/" + sid + "/bill").param("custom_time", ONE_HOUR_LATER), null).getResponse().getContentAsString();
        assertEquals(100.0, ((Number) JsonPath.read(bill, "$.games_total")).doubleValue());
    }
}
