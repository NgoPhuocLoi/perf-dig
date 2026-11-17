package vn.nploi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
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
 * Locks the /hib/orders N+1 fix: the customers must load in the same statement as the orders.
 * Batch fetching is off (fetch.batch-size=-1), so without the fetch graph this request issues
 * 1 + 20 statements (one lazy customer select per order).
 */
@QuarkusTest
class HibOrderResourceTest {

    @Inject
    EntityManager em;

    @Inject
    SessionFactory sf;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("delete from CustomerOrder").executeUpdate();
            em.createQuery("delete from Customer").executeUpdate();
            for (long i = 1; i <= 20; i++) {
                var customer = new Customer();
                customer.setId(i);
                customer.setName("cust_" + i);
                em.persist(customer);

                var order = new CustomerOrder();
                order.setId(300 + i);
                order.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
                order.setStatus("SHIPPED");
                order.setTotalCents(1_000 * i);
                order.setCustomer(customer);
                em.persist(order);
            }
        });
    }

    @Test
    void loadsOrdersAndTheirCustomersInOneStatement() {
        sf.getStatistics().clear();

        given()
                .queryParam("from", 301)
                .queryParam("to", 320)
                .when().get("/hib/orders")
                .then()
                .statusCode(200)
                .body("size()", is(20))
                .body("[0].orderId", is(301))
                .body("[0].customerName", is("cust_1"))
                .body("[19].customerName", is("cust_20"));

        assertEquals(1, sf.getStatistics().getPrepareStatementCount());
    }
}
