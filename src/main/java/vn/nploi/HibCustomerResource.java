package vn.nploi;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@Path("/hib/customers")
@Produces(MediaType.APPLICATION_JSON)
public class HibCustomerResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public List<CustomerOrderCount> customers(@QueryParam("from") long from, @QueryParam("to") long to) {
        // Postgres counts and groups; only one row per customer comes back, no entities are loaded.
        // Left join keeps customers with no orders (count 0).
        return em.createQuery(
                "select new vn.nploi.CustomerOrderCount(c.id, c.name, count(o.id))"
                        + " from Customer c left join c.orders o"
                        + " where c.id between :from and :to"
                        + " group by c.id, c.name order by c.id",
                CustomerOrderCount.class)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
    }
}
