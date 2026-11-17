package vn.nploi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.everyItem;
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
 * Locks the /hib/customers fix: Postgres counts the orders and sends one row per customer, in one statement.
 * Loading customers and calling c.getOrders().size() issues 1 + 20 statements (one collection select per customer).
 */
@QuarkusTest
class HibCustomerResourceTest {

    @Inject
    EntityManager em;

    @Inject
    SessionFactory sf;

    @BeforeEach
    void seed() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createQuery("delete from CustomerOrder").executeUpdate();
            em.createQuery("delete from Customer").executeUpdate();
            long orderId = 1;
            for (long i = 1; i <= 20; i++) {
                var customer = new Customer();
                customer.setId(i);
                customer.setName("cust_" + i);
                em.persist(customer);

                for (int k = 0; k < 3; k++) {
                    var order = new CustomerOrder();
                    order.setId(orderId++);
                    order.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
                    order.setStatus("SHIPPED");
                    order.setTotalCents(1_000);
                    order.setCustomer(customer);
                    em.persist(order);
                }
            }
        });
    }

    @Test
    void countsOrdersPerCustomerInOneStatement() {
        sf.getStatistics().clear();

        given()
                .queryParam("from", 1)
                .queryParam("to", 20)
                .when().get("/hib/customers")
                .then()
                .statusCode(200)
                .body("size()", is(20))
                .body("orderCount", everyItem(is(3)))
                .body("[0].customerId", is(1))
                .body("[0].name", is("cust_1"));

        assertEquals(1, sf.getStatistics().getPrepareStatementCount());
    }
}
