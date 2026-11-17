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

@Path("/hib/customers")
@Produces(MediaType.APPLICATION_JSON)
public class HibCustomerResource {

    @Inject
    EntityManager em;

    @GET
    @Transactional
    public List<CustomerOrderCount> customers(@QueryParam("from") long from, @QueryParam("to") long to) {
        var customers = em.createQuery(                                              // line 1
                "select c from Customer c join fetch c.orders where c.id between :from and :to order by c.id",
                Customer.class)
                .setParameter("from", from).setParameter("to", to)
                .getResultList();
        var result = new ArrayList<CustomerOrderCount>();
        for (var c : customers) {
            result.add(new CustomerOrderCount(c.getId(), c.getName(),
                    c.getOrders().size()));                                          // line 2
        }
        return result;
    }
}
