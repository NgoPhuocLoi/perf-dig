package vn.nploi;

import java.util.ArrayList;
import java.util.HashMap;
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

    @GET
    @Path("/by-ids")
    @Transactional
    public List<OrderTotal> byIds(@QueryParam("ids") List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        // One statement for all ids; projection, so no CustomerOrder entities are loaded
        var totals = em.createQuery(
                "select new vn.nploi.OrderTotal(o.id, o.totalCents) from CustomerOrder o where o.id in :ids",
                OrderTotal.class)
                .setParameter("ids", ids)
                .getResultList();
        // Postgres returns each id once in any order; answer in request order, repeats included, unknown ids skipped
        var byId = new HashMap<Long, OrderTotal>();
        for (var t : totals) {
            byId.put(t.orderId(), t);
        }
        var result = new ArrayList<OrderTotal>();
        for (var id : ids) {
            var t = byId.get(id);
            if (t != null) {
                result.add(t);
            }
        }
        return result;
    }
}
