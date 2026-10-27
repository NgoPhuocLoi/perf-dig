package vn.nploi;

import java.util.Random;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/orders")
public class OrderResource {

    private static final Random RANDOM = new Random();

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        try {
            // ~90% fast (10-50ms), ~10% slow (1-3s)
            long delay = RANDOM.nextInt(100) < 90
                    ? 10 + RANDOM.nextInt(41)
                    : 1000 + RANDOM.nextInt(2001);
            Thread.sleep(delay);
        } catch (Exception e) {
            // Ignore
        }
        return "/orders Response";
    }
}
