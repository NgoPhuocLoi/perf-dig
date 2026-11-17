package vn.nploi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Locks the /hib/orders/by-ids fix: all ids load in one statement, and the response keeps request order
 * and repeated ids. The old em.find loop issues one select per distinct id (5 here).
 */
@QuarkusTest
class HibOrderByIdsTest {

    @Inject
    EntityManager em;

    @Inject
    SessionFactory sf;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("delete from CustomerOrder").executeUpdate();
            em.createQuery("delete from Customer").executeUpdate();

            var customer = new Customer();
            customer.setId(1L);
            customer.setName("cust_1");
            em.persist(customer);

            long[][] orders = { { 501, 5_010 }, { 777, 7_770 }, { 1200, 12_000 }, { 9000, 90_000 }, { 42, 420 } };
            for (var row : orders) {
                var order = new CustomerOrder();
                order.setId(row[0]);
                order.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
                order.setStatus("SHIPPED");
                order.setTotalCents(row[1]);
                order.setCustomer(customer);
                em.persist(order);
            }
        });
    }

    @Test
    void loadsAllIdsInOneStatementInRequestOrder() {
        sf.getStatistics().clear();

        given()
                .queryParam("ids", 501, 777, 1200, 501, 9000, 42)
                .when().get("/hib/orders/by-ids")
                .then()
                .statusCode(200)
                .body("size()", is(6))
                .body("orderId", contains(501, 777, 1200, 501, 9000, 42))
                .body("totalCents", contains(5_010, 7_770, 12_000, 5_010, 90_000, 420));

        assertEquals(1, sf.getStatistics().getPrepareStatementCount());
    }
}
