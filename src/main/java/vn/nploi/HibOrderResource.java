package vn.nploi;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/hib/orders")
@Produces(MediaType.APPLICATION_JSON)
public class HibOrderResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public List<OrderCustomerName> orders(@QueryParam("from") long from, @QueryParam("to") long to) {
        // Fetch graph: load the customer association with the orders (one SQL join), no JPQL join needed
        var graph = em.createEntityGraph(CustomerOrder.class);
        graph.addAttributeNodes("customer");
        var orders = em.createQuery(
                "select o from CustomerOrder o where o.id between :from and :to order by o.id",
                CustomerOrder.class)
                .setHint("jakarta.persistence.fetchgraph", graph)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
        var result = new ArrayList<OrderCustomerName>();
        for (var o : orders) {
            result.add(new OrderCustomerName(o.getId(), o.getCustomer().getName()));
        }
        return result;
    }
}
